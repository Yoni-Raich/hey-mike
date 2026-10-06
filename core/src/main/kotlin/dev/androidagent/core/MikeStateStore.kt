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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MikeMemory(
    val key: String, val text: String, val kind: String = "fact",
    val sourceSessionId: String? = null, val updatedAt: Long, val revision: Long = 1,
)

@Serializable
enum class MikeTaskStatus { READY, QUEUED, RUNNING, WAITING, PAUSED, DONE, FAILED, UNKNOWN }

@Serializable
data class MikeTask(
    val id: String, val title: String, val instruction: String, val sessionId: String,
    val status: MikeTaskStatus = MikeTaskStatus.READY, val nextStep: String = "",
    val result: String = "", val wakeAt: Long? = null, val updatedAt: Long,
    val completionRequested: Boolean = false, val attempt: String? = null,
    val turnActive: Boolean = false,
)

@Serializable
data class MikeState(
    val version: Int = 1, val revision: Long = 0, val paused: Boolean = false,
    val memories: List<MikeMemory> = emptyList(), val tasks: List<MikeTask> = emptyList(),
)

/** Agent state belongs to Mike, not to a chat folder. Corrupt data is never replaced. */
class MikeStateStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutable = MutableStateFlow(runCatching { read() }.getOrElse {
        mutableError.value = "Mike's saved state could not be read. The file has been kept: ${it.message}"
        MikeState()
    })
    val state = mutable.asStateFlow()

    fun snapshot(): MikeState = synchronized(lock) { read().also { mutable.value = it; mutableError.value = null } }

    fun remember(key: String, text: String, kind: String, source: String?, expectedRevision: Long? = null): MikeMemory {
        val canonicalKey = memoryKey(key)
        require(text.isNotBlank() && text.length <= 1200) { "Memory must be 1–1200 characters." }
        require(kind in setOf("fact", "preference", "lesson")) { "Unknown memory kind." }
        lateinit var result: MikeMemory
        change { old ->
            val previous = old.memories.find { it.key == canonicalKey }
            require(expectedRevision == null || expectedRevision == (previous?.revision ?: 0L)) { "Memory changed. Read it again before editing." }
            result = MikeMemory(canonicalKey, text.trim(), kind, source, now(), (previous?.revision ?: 0L) + 1)
            val memories = old.memories.filterNot { it.key == canonicalKey } + result
            require(memories.size <= 100 && memories.sumOf { it.text.length } <= 40_000) { "Memory is full. Consolidate or remove an old entry." }
            old.copy(memories = memories)
        }
        return result
    }

    fun forget(key: String, expectedRevision: Long? = null) = change { old ->
        val canonicalKey = memoryKey(key)
        val previous = old.memories.find { it.key == canonicalKey } ?: error("Memory not found.")
        require(expectedRevision == null || expectedRevision == previous.revision) { "Memory changed. Read it again." }
        old.copy(memories = old.memories.filterNot { it.key == canonicalKey })
    }

    fun addTask(task: MikeTask) = change { old ->
        require(old.tasks.none { it.id == task.id || it.sessionId == task.sessionId }) { "Task already exists." }
        require(task.title.isNotBlank() && task.title.length <= 100 && task.instruction.isNotBlank() && task.instruction.length <= 8000) { "Enter a title and a task of up to 8000 characters." }
        require(old.tasks.count { it.status !in TERMINAL } < 50 && old.tasks.size < 500) { "Task list is full." }
        old.copy(tasks = old.tasks + task)
    }

    fun updateTask(id: String, update: (MikeTask) -> MikeTask): MikeTask {
        lateinit var result: MikeTask
        change { old ->
            val before = old.tasks.find { it.id == id } ?: error("Task not found.")
            result = update(before).copy(updatedAt = now())
            require(result.id == before.id && result.sessionId == before.sessionId) { "Task identity cannot change." }
            require(result.nextStep.length <= 2000 && result.result.length <= 8000) { "Keep task notes short." }
            old.copy(tasks = old.tasks.map { if (it.id == id) result else it })
        }
        return result
    }

    fun removeTask(id: String) = change { old ->
        val task = old.tasks.find { it.id == id } ?: error("Task not found.")
        require(!task.turnActive && task.status !in setOf(MikeTaskStatus.QUEUED, MikeTaskStatus.RUNNING)) { "Pause this task before removing it." }
        old.copy(tasks = old.tasks.filterNot { it.id == id })
    }

    fun pauseAll() = change { old -> old.copy(paused = true, tasks = old.tasks.map {
        if (it.status in setOf(MikeTaskStatus.READY, MikeTaskStatus.QUEUED))
            it.copy(status = MikeTaskStatus.PAUSED, wakeAt = null, completionRequested = false, updatedAt = now()) else it
    }) }
    fun resume() = change { it.copy(paused = false) }

    /** Running attempts have unknown outcomes after a crash. They need a human decision. */
    fun recover() = change { old -> old.copy(tasks = old.tasks.map {
        when {
            it.turnActive || it.status == MikeTaskStatus.RUNNING -> it.copy(status = MikeTaskStatus.UNKNOWN, turnActive = false, wakeAt = null, completionRequested = false, nextStep = "The app stopped during this task. Check what completed before running it again.", updatedAt = now())
            it.status == MikeTaskStatus.QUEUED -> it.copy(status = MikeTaskStatus.PAUSED, nextStep = "Resume this task when you are ready.", updatedAt = now())
            else -> it
        }
    }) }

    private fun memoryKey(key: String): String = key.trim().lowercase(java.util.Locale.ROOT).also {
        require(it.matches(Regex("[a-z0-9][a-z0-9_.-]{0,79}"))) { "Use letters, numbers, dots, underscores or hyphens for the memory key, up to 80 characters." }
    }

    private fun read(): MikeState {
        if (!file.exists()) return MikeState()
        val value = Json.decodeFromString<MikeState>(file.readText())
        require(value.version == 1) { "This Mike state version cannot be read." }
        require(value.memories.map { it.key }.distinct().size == value.memories.size && value.tasks.map { it.id }.distinct().size == value.tasks.size) { "Mike state contains duplicate identities." }
        return value
    }

    private fun change(update: (MikeState) -> MikeState): MikeState = synchronized(lock) {
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(File(file.parentFile, file.name + ".lock"), "rw").use { guard ->
            guard.channel.lock().use {
                val old = read()
                val next = update(old).copy(revision = old.revision + 1)
                val staging = File(file.parentFile, file.name + "." + UUID.randomUUID() + ".tmp")
                try {
                    FileOutputStream(staging).use { output -> output.write(Json.encodeToString(next).toByteArray(Charsets.UTF_8)); output.fd.sync() }
                    try { Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                    mutable.value = next
                    mutableError.value = null
                    next
                } finally { staging.delete() }
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        val TERMINAL = setOf(MikeTaskStatus.DONE, MikeTaskStatus.FAILED)
    }
}
