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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

/**
 * Where rules live: one JSON file per rule under `<homeDirectory>/automations/`.
 *
 * Beside the workflow definitions and for the same reasons — a rule is a
 * document a person can read, diff, delete and hand to someone else, and it has
 * to survive [WorkspaceSeeder], which rewrites the session workspace on every
 * access and force-replaces the bundled skills on every start.
 *
 * A rule that exists is not a rule that runs. Nothing here schedules anything;
 * the host reads this directory, registers whatever the triggers need, and is
 * free to refuse. Keeping the store ignorant of that is what lets the same
 * files be listed, tested and explained on a phone where the notification
 * listener was never granted.
 */
class AutomationLibrary(private val root: File) {

    /** A file that is present but unusable, so a listing can say so instead of hiding it. */
    data class Broken(val file: String, val reason: String)

    fun directory(): File = root

    /**
     * Find a rule by id, or by a name close enough to be unambiguous.
     *
     * A near miss is resolved the way [WorkflowLibrary] resolves one, and an
     * ambiguous name is refused rather than guessed: enabling or deleting the
     * wrong standing rule is not a mistake the user finds out about quickly.
     */
    fun find(name: String): Lookup {
        val wanted = normalize(name)
        if (wanted.isEmpty()) return Lookup.NotFound(name, emptyList())
        val all = all()
        all.firstOrNull { normalize(it.id) == wanted }?.let { return Lookup.Found(it) }
        val near = all.filter { normalize(it.id).contains(wanted) || wanted.contains(normalize(it.id)) }
        return when (near.size) {
            1 -> Lookup.Found(near.first())
            0 -> Lookup.NotFound(name, all.map { it.id })
            else -> Lookup.Ambiguous(name, near.map { it.id })
        }
    }

    /** Every rule that parses, newest file first. Broken files are reported by [broken]. */
    fun all(): List<AutomationRule> = files().mapNotNull { read(it).getOrNull() }

    fun enabled(): List<AutomationRule> = all().filter { it.enabled }

    /** Rules a given trigger kind could wake, so a host registers only what is used. */
    fun watching(kind: AutomationTriggerKind): List<AutomationRule> =
        enabled().filter { it.trigger.kind == kind }

    fun broken(): List<Broken> = files().mapNotNull { file ->
        read(file).exceptionOrNull()?.let { Broken(file.name, it.message ?: "unreadable") }
    }

    /** Write a rule, replacing any file with the same id. Temp file and rename, as elsewhere. */
    fun save(rule: AutomationRule): File {
        require(AutomationRule.ID_RE.matches(rule.id)) { "\"${rule.id}\" is not a usable rule id" }
        require(all().size < MAX_RULES || all().any { it.id == rule.id }) {
            "This phone already holds $MAX_RULES rules; delete one before adding another."
        }
        root.mkdirs()
        val file = File(root, rule.id + SUFFIX)
        val temp = File(root, "." + rule.id + SUFFIX + ".tmp")
        temp.writeText(rule.toJson().toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(rule.toJson().toString(), Charsets.UTF_8)
            temp.delete()
        }
        return file
    }

    /**
     * The rule with exactly this id, or null.
     *
     * What every mode that changes a rule uses. [find] is forgiving on purpose
     * for reading, but "delete morning" must never resolve to "morning-news"
     * because it happened to be the only near match.
     */
    fun get(id: String): AutomationRule? {
        val wanted = id.trim().lowercase(Locale.ROOT)
        if (!AutomationRule.ID_RE.matches(wanted)) return null
        val file = File(root, wanted + SUFFIX)
        return if (file.isFile) read(file).getOrNull() else null
    }

    /** Remove a rule. Only an exact, well-formed id reaches the file system. */
    fun delete(id: String): Boolean {
        val wanted = id.trim().lowercase(Locale.ROOT)
        if (!AutomationRule.ID_RE.matches(wanted)) return false
        return File(root, wanted + SUFFIX).delete()
    }

    /**
     * Change part of a rule and save it.
     *
     * [changes] is merged onto the saved definition key by key: a key replaces
     * the old value whole, and `null` removes it. The result is parsed exactly
     * like a new rule, so an edit cannot save what `create` would refuse, and a
     * failed edit leaves the old file untouched.
     *
     * The id cannot change here. Renaming is delete plus create, said out loud,
     * because the journal - and so the cooldown and the daily count - is keyed
     * by id.
     */
    fun update(id: String, changes: JsonObject): AutomationRule {
        val existing = get(id) ?: throw AutomationFormatException(
            "rule_not_found",
            "There is no rule with the id \"$id\".",
        )
        changes.str("id")?.let { newId ->
            if (newId.lowercase(Locale.ROOT) != existing.id) {
                throw AutomationFormatException(
                    "rule_rename_refused",
                    "An edit cannot change a rule's id (\"${existing.id}\" to \"$newId\"). " +
                        "Create the new rule, then delete the old one.",
                )
            }
        }
        val merged = existing.toJson().toMutableMap()
        for ((key, value) in changes) {
            if (key == "id") continue
            if (value is JsonNull) merged.remove(key) else merged[key] = value
        }
        val updated = AutomationRule.parse(JsonObject(merged))
        save(updated)
        return updated
    }

    /** Turn a rule on or off without rewriting it. Returns null when there is no such rule. */
    fun setEnabled(id: String, enabled: Boolean): AutomationRule? {
        val rule = get(id) ?: return null
        if (rule.enabled == enabled) return rule
        val updated = rule.copy(enabled = enabled)
        save(updated)
        return updated
    }

    private fun files(): List<File> =
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) && !it.name.startsWith(".") && it.name != AutomationJournal.FILE_NAME }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun read(file: File): Result<AutomationRule> = runCatching {
        if (file.length() > MAX_FILE_BYTES) {
            throw AutomationFormatException("automation_invalid", "${file.name} is larger than $MAX_FILE_BYTES bytes.")
        }
        val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
            ?: throw AutomationFormatException("automation_invalid", "${file.name} is not a JSON object.")
        AutomationRule.parse(json, source = file.name, savedAt = file.lastModified())
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    sealed interface Lookup {
        data class Found(val definition: AutomationRule) : Lookup
        data class NotFound(val name: String, val known: List<String>) : Lookup
        data class Ambiguous(val name: String, val candidates: List<String>) : Lookup
    }

    companion object {
        const val DIRECTORY = "automations"
        const val MAX_RULES = 64
        private const val SUFFIX = ".json"
        private const val MAX_FILE_BYTES = 128L * 1024L

        fun directoryIn(homeDirectory: File): File = File(homeDirectory, DIRECTORY)
    }
}
