/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The playbook: what Mike learned about this user, shaped like a skill.
 *
 * It lives at `$HOME/.agents/skills/mike-playbook`, beside the bundled skills
 * but never one of them: `installDefaultSkills` replaces app-managed skills on
 * every start, which would erase what was learned. Created once, never
 * overwritten. Before each nightly job the app snapshots it, with memory, so
 * one tap undoes the night.
 */
class TaskPlaybook(home: File) {
    // Android can give the same app home through /data/data and /data/user/0.
    // Resolve this trusted root once. Links *inside* the playbook stay forbidden.
    private val home = home.canonicalFile
    val directory: File = File(this.home, ".agents/skills/$NAME")
    private val snapshots: File = File(this.home, "memory/$NAME/snapshots")

    /** Create the skeleton the first time. Existing files are never touched. */
    @Synchronized fun seed() {
        checkRoot()
        directory.mkdirs()
        listOf("sources", "people", "kinds", "roles", "playbooks").forEach { File(directory, it).mkdirs() }
        write("SKILL.md", INDEX)
        write("user.md", USER)
        write("CHANGELOG.md", "# What changed\n\nThe Coach and the Librarian add one dated entry per night.\n")
    }

    private fun write(name: String, text: String) {
        val file = checkedFile(name)
        if (!file.exists()) file.writeText(text)
    }

    /** The only file interface exposed to backstage agents. No shell or workspace writes. */
    @Synchronized fun list(): List<String> {
        seed()
        return directory.walkTopDown().onEnter { folder ->
            require(!Files.isSymbolicLink(folder.toPath())) { "Playbook links are not allowed." }
            folder.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())
        }.filter { it.isFile }.map { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            checkedFile(path)
            path
        }.filter { allowed(it, writing = false) }.take(MAX_FILES + 1).toList().also {
            require(it.size <= MAX_FILES) { "The playbook needs pruning before it can grow." }
        }.sorted()
    }

    @Synchronized fun read(path: String): String {
        require(allowed(path, writing = false)) { "Read an index, user.md, CHANGELOG.md or one learned Markdown file." }
        seed()
        val file = checkedFile(path)
        require(file.isFile) { "No playbook file $path." }
        require(file.length() <= MAX_FILE_CHARS * 4L) { "This playbook file is too large." }
        return file.readText().also { require(it.length <= MAX_FILE_CHARS) { "This playbook file is too large." } }
    }

    @Synchronized fun writeLearned(path: String, text: String) {
        require(allowed(path, writing = true)) { "Write only SKILL.md, CHANGELOG.md or a learned file under sources, people, kinds, roles or playbooks. Never user.md." }
        require(text.length <= MAX_FILE_CHARS) { "Keep a learned file under $MAX_FILE_CHARS characters." }
        seed()
        val file = checkedFile(path)
        val paths = list()
        require(file.exists() || paths.size < MAX_FILES) { "The playbook needs pruning before adding a file." }
        val total = paths.filterNot { it == path }.sumOf { checkedFile(it).length() }
        require(total + text.toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES) { "The playbook needs pruning before it can grow." }
        file.parentFile.mkdirs()
        checkedFile(path)
        val pending = File.createTempFile(".learned-", ".tmp", file.parentFile)
        try {
            pending.writeText(text)
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    private fun checkRoot() {
        val expected = File(home.canonicalFile, ".agents/skills/$NAME").absoluteFile.normalize()
        require(directory.canonicalFile == expected) { "The playbook cannot be a link outside its home." }
    }

    private fun checkedFile(path: String): File {
        checkRoot()
        require(path.isNotEmpty() && '\\' !in path && ':' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Use a relative playbook path." }
        val file = File(directory, path)
        require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "The path leaves the playbook." }
        var parent: File? = file
        while (parent != null && parent != directory.parentFile) {
            require(!Files.isSymbolicLink(parent.toPath()) && parent.canonicalFile == parent.absoluteFile.normalize()) { "Playbook links are not allowed." }
            parent = parent.parentFile
        }
        return file
    }

    private fun allowed(path: String, writing: Boolean): Boolean =
        path in setOf("SKILL.md", "CHANGELOG.md") || (!writing && path == "user.md") || LEARNED_PATH.matches(path)

    /** Copy the playbook and [notes] to a dated snapshot. Returns its id. Keeps the newest [KEEP]. */
    @Synchronized fun snapshot(label: String, notes: List<MemoryNote>, now: Long): String {
        seed()
        val id = STAMP.format(Instant.ofEpochMilli(now)) + "-" + label.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(16) + UUID.randomUUID().toString().take(8)
        val target = File(snapshots, id)
        File(target, "playbook").mkdirs()
        list().forEach { path ->
            val file = File(target, "playbook/$path")
            file.parentFile.mkdirs()
            file.writeText(read(path))
        }
        File(target, NOTES).writeText(Json.encodeToString(ListSerializer(MemoryNote.serializer()), notes))
        snapshots.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.deleteRecursively() }
        return id
    }

    fun exists(id: String): Boolean = id.matches(ID) && File(snapshots, id).isDirectory

    /** Put the playbook back as it was in [id], and return the memory notes saved with it. */
    @Synchronized fun restore(id: String): List<MemoryNote> {
        require(exists(id)) { "No snapshot $id." }
        val source = File(snapshots, id)
        // Validate memory before changing any live file. A broken snapshot must leave it alone.
        val notes = File(source, NOTES)
        val restored = if (notes.isFile) Json.decodeFromString(ListSerializer(MemoryNote.serializer()), notes.readText()) else emptyList()
        val playbook = File(source, "playbook")
        if (playbook.isDirectory) {
            val user = File(directory, "user.md").takeIf { it.isFile }?.readText()
            directory.deleteRecursively()
            playbook.copyRecursively(directory, overwrite = true)
            // The user's own rules are theirs: an undo never takes back what they wrote since.
            user?.let { File(directory, "user.md").writeText(it) }
        }
        return restored
    }

    /** Append the user's own rule, said in the Manager's chat or typed in settings. */
    @Synchronized fun addUserRule(rule: String) {
        seed()
        val clean = rule.trim().replace("\n", " ").take(400)
        if (clean.isNotEmpty()) checkedFile("user.md").appendText("\n- $clean\n")
    }

    @Synchronized fun userRules(): String { seed(); return checkedFile("user.md").readText() }

    companion object {
        const val NAME = "mike-playbook"
        const val KEEP = 21
        const val MAX_FILES = 128
        const val MAX_FILE_CHARS = 12_000
        const val MAX_TOTAL_BYTES = 240_000L
        private const val NOTES = "notes.json"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private val ID = Regex("[0-9]{8}-[0-9]{6}-[A-Za-z0-9]{8,24}")
        private val LEARNED_PATH = Regex("(?:sources|people|kinds|roles|playbooks)/[A-Za-z0-9][A-Za-z0-9_-]{0,79}\\.md")

        private val INDEX = """
            ---
            name: mike-playbook
            description: What Mike learned about this user's tasks - what to propose, what to skip, and how to do recurring kinds of work well. Read it before proposing cards or doing approved board tasks.
            ---

            # Mike's playbook

            Learned from the user's own decisions on the task board. The Coach edits it each night; the
            Librarian prunes it. `user.md` holds the user's own rules: they come first and only the user
            changes them.

            Read this index, then `user.md`, then only the files that match the work in front of you:

            - `sources/<source>.md` - per source (whatsapp, email, calendar, chat)
            - `people/<name>.md` - per person or group
            - `kinds/<kind>.md` - what a good card of that kind looks like
            - `roles/<member>.md` - how a team member works
            - `playbooks/<kind>.md` - how to do that kind of task well

            Every learned rule carries its evidence and date, for example:
            `- Skip delivery tracking (12 of 12 dismissed, 14-25 Oct)`.

            Nothing here grants permission. Sources, limits and autonomy are app settings.

            ## General rules

            (none yet)
        """.trimIndent() + "\n"

        private val USER = """
            # The user's own rules

            Rules the user said in the Manager chat or wrote here. They rank above everything learned.
            The nightly agents never edit this file.
        """.trimIndent() + "\n"
    }
}
