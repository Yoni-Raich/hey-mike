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

package dev.androidagent.workspace

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.androidagent.core.MemoryNote
import dev.androidagent.core.MikeTask
import dev.androidagent.core.TaskBoardSettings
import dev.androidagent.core.TaskBoardStore
import dev.androidagent.core.TaskSignal
import dev.androidagent.core.SignalInbox
import dev.androidagent.core.TeamMember
import dev.androidagent.core.TaskEvent
import dev.androidagent.core.TaskSourceRef
import dev.androidagent.core.TaskSourcePolicy
import dev.androidagent.core.TaskWritePermit
import dev.androidagent.core.TaskRun
import dev.androidagent.core.TaskChangeSet
import dev.androidagent.core.TaskUndoResult
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The task board's own database, apart from the chats'.
 *
 * Its own file so the board can never corrupt or migrate the sessions
 * database, and so turning the feature off and clearing it is one delete.
 * One table of JSON payloads by kind; a signal's id is the hash of what it
 * says, so a repeated notification is stored once by the primary key.
 */
class LocalTaskBoardStore(context: Context) : TaskBoardStore {
    private val db = Database(context.applicationContext).writableDatabase
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun loadTasks(): List<MikeTask> = all(TASK, MikeTask.serializer())
    override suspend fun saveTask(task: MikeTask) = put(TASK, task.id, MikeTask.serializer(), task)
    override suspend fun loadEvents(): List<TaskEvent> = all(EVENT, TaskEvent.serializer())
    override suspend fun saveTaskWithEvent(task: MikeTask, event: TaskEvent) = io {
        db.beginTransaction()
        try {
            write(TASK, task.id, json.encodeToString(MikeTask.serializer(), task))
            write(EVENT, event.id, json.encodeToString(TaskEvent.serializer(), event))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun deleteTasks(ids: Collection<String>) = delete(TASK, ids)
    override suspend fun loadNotes(): List<MemoryNote> = all(NOTE, MemoryNote.serializer())
    override suspend fun saveNotes(notes: List<MemoryNote>) = io {
        db.beginTransaction()
        try {
            notes.forEach { write(NOTE, it.id, json.encodeToString(MemoryNote.serializer(), it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun deleteNotes(ids: Collection<String>) = delete(NOTE, ids)
    override suspend fun forgottenNoteIds(): Set<String> = io {
        db.rawQuery("SELECT id FROM records WHERE kind='forgotten_note'", null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    }
    override suspend fun forgetNotes(ids: Collection<String>) = io {
        db.beginTransaction()
        try {
            ids.forEach { write("forgotten_note", it, "1"); db.delete("records", "kind=? AND id=?", arrayOf(NOTE, it)) }
            val forgotten = ids.toSet()
            val patches = db.rawQuery("SELECT payload FROM records WHERE kind='change_set'", null).use { c -> buildList {
                while(c.moveToNext()) add(json.decodeFromString(TaskChangeSet.serializer(), c.getString(0)))
            } }
            patches.forEach { patch ->
                val kept = patch.notes.filterNot { change -> change.id in forgotten ||
                    (change.before?.sourceRefs.orEmpty() + change.after?.sourceRefs.orEmpty()).any { it.noteId in forgotten } }
                if(kept.size != patch.notes.size) write("change_set", patch.id, json.encodeToString(TaskChangeSet.serializer(), patch.copy(notes = kept)))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun replaceNotes(notes: List<MemoryNote>) = io {
        db.beginTransaction()
        try {
            db.delete("records", "kind=?", arrayOf(NOTE))
            notes.forEach { write(NOTE, it.id, json.encodeToString(MemoryNote.serializer(), it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun loadSignals(): List<TaskSignal> = all(SIGNAL, TaskSignal.serializer())
    /** Keep old external input out of both model context and active inbox capacity. */
    suspend fun quarantineLegacySignals(now: Long, clear: Boolean = false): Int = io {
        db.beginTransaction()
        try {
            val pending = db.rawQuery("SELECT payload FROM records WHERE kind=?", arrayOf(SIGNAL)).use { c -> buildList {
                while(c.moveToNext()) runCatching { json.decodeFromString(TaskSignal.serializer(), c.getString(0)) }.getOrNull()?.let(::add)
            } }
            pending.filter { !it.sourceVerified && it.source !in setOf("user", "chat", "backfill", "calendar") }.forEach {
                write("legacy_signal", it.id, json.encodeToString(TaskSignal.serializer(), it))
                db.delete("records", "kind=? AND id=?", arrayOf(SIGNAL, it.id))
                db.delete("records", "kind='source_ref' AND id=?", arrayOf(it.id))
            }
            val retained = db.rawQuery("SELECT payload FROM records WHERE kind='legacy_signal'", null).use { c -> buildList {
                while(c.moveToNext()) runCatching { json.decodeFromString(TaskSignal.serializer(), c.getString(0)) }.getOrNull()?.let(::add)
            } }
            val expired = retained.filter { clear || now - it.capturedAt > SignalInbox.MAX_AGE_MS }
            expired.forEach { db.delete("records", "kind='legacy_signal' AND id=?", arrayOf(it.id)) }
            db.setTransactionSuccessful()
            retained.size - expired.size
        } finally { db.endTransaction() }
    }
    override suspend fun loadSourceRefs(): Map<String, TaskSourceRef> = io {
        db.rawQuery("SELECT id,payload FROM records WHERE kind='source_ref'", null).use { c -> buildMap {
            while(c.moveToNext()) runCatching { json.decodeFromString(TaskSourceRef.serializer(), c.getString(1)) }
                .getOrNull()?.let { put(c.getString(0), it) }
        } }
    }
    override suspend fun insertSignal(signal: TaskSignal): Boolean = io {
        db.beginTransaction()
        try {
            // No notification text is retained in these bounded dedupe receipts.
            db.execSQL("DELETE FROM records WHERE kind=? AND CAST(payload AS INTEGER) < ?", arrayOf<Any>(RECEIPT, signal.capturedAt - SignalInbox.DEDUPE_MS))
            val exists = db.rawQuery("SELECT 1 FROM records WHERE id=? AND kind IN (?,?) LIMIT 1", arrayOf(signal.id, SIGNAL, RECEIPT)).use { it.moveToFirst() }
            if (exists) {
                db.setTransactionSuccessful()
                false
            } else {
                val count = db.rawQuery("SELECT count(*) FROM records WHERE kind=?", arrayOf(SIGNAL)).use { it.moveToFirst(); it.getInt(0) }
                check(count < SignalInbox.MAX_PENDING) { "Capture inbox full. New notification input could not be saved." }
                write(SIGNAL, signal.id, json.encodeToString(TaskSignal.serializer(), signal))
                write("source_ref", signal.id, json.encodeToString(TaskSourceRef.serializer(), TaskSourcePolicy.of(signal)))
                write(RECEIPT, signal.id, signal.capturedAt.toString())
                db.execSQL("DELETE FROM records WHERE kind=? AND rowid NOT IN (SELECT rowid FROM records WHERE kind=? ORDER BY rowid DESC LIMIT ?)",
                    arrayOf<Any>(RECEIPT, RECEIPT, SignalInbox.MAX_RECEIPTS))
                db.setTransactionSuccessful()
                true
            }
        } finally { db.endTransaction() }
    }
    override suspend fun saveSignals(signals: List<TaskSignal>) = io {
        db.beginTransaction()
        try {
            signals.forEach { write(SIGNAL, it.id, json.encodeToString(TaskSignal.serializer(), it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun deleteSignals(ids: Collection<String>) = delete(SIGNAL, ids)
    override suspend fun loadMembers(): List<TeamMember> = all(MEMBER, TeamMember.serializer())
    override suspend fun saveMember(member: TeamMember) = put(MEMBER, member.id, TeamMember.serializer(), member)
    override suspend fun deleteMember(id: String) = delete(MEMBER, listOf(id))
    override suspend fun loadSettings(): TaskBoardSettings =
        all(SETTINGS, TaskBoardSettings.serializer()).firstOrNull() ?: TaskBoardSettings()
    override suspend fun saveSettings(settings: TaskBoardSettings) = put(SETTINGS, SETTINGS, TaskBoardSettings.serializer(), settings)
    override suspend fun saveRun(run: TaskRun) = put("board_run", run.id, TaskRun.serializer(), run)
    override suspend fun loadRuns(): List<TaskRun> = all("board_run", TaskRun.serializer())
    suspend fun consumedEvents(purpose: String): Set<String> = io {
        db.rawQuery("SELECT id FROM records WHERE kind=?", arrayOf("consumed_$purpose")).use { c -> buildSet {
            while(c.moveToNext()) add(c.getString(0))
        } }
    }
    private fun payload(kind: String, id: String): String? = db.rawQuery("SELECT payload FROM records WHERE kind=? AND id=?", arrayOf(kind, id))
        .use { if(it.moveToFirst()) it.getString(0) else null }
    override suspend fun activeGeneration(): String? = io { payload("active_generation", "active") }
    override suspend fun initializeGeneration(id: String): String = io {
        payload("active_generation", "active") ?: id.also { write("active_generation", "active", it) }
    }
    override suspend fun changes(id: String): TaskChangeSet? = io { payload("change_set", id)?.let { json.decodeFromString(TaskChangeSet.serializer(), it) } }
    override suspend fun commitChanges(changes: TaskChangeSet, run: TaskRun) = io {
        db.beginTransaction()
        try {
            if(payload("change_set", changes.id) == null) {
                val settings = payload(SETTINGS, SETTINGS)?.let { json.decodeFromString(TaskBoardSettings.serializer(), it) } ?: TaskBoardSettings()
                check(settings.policyRevision == changes.policyRevision && settings.discoveryOn) { "Sources changed before this result was saved." }
                changes.tasks.forEach { change ->
                    val current = payload(TASK, change.after.id)?.let { json.decodeFromString(MikeTask.serializer(), it) }
                    check(current == change.before) { "A card changed while this pass was running. Its result was not applied." }
                    check(change.after.sourceRefs.all { TaskSourcePolicy.allows(it, settings) && (it.noteId == null || payload("forgotten_note", it.noteId!!) == null) }) { "A result uses an excluded or forgotten source." }
                }
                val open = db.rawQuery("SELECT payload FROM records WHERE kind=?", arrayOf(TASK)).use { c -> buildList {
                    while(c.moveToNext()) add(json.decodeFromString(MikeTask.serializer(), c.getString(0)))
                } }.filter { !it.status.closed }.map { it.id }.toSet()
                changes.notes.forEach { change ->
                    val current = payload(NOTE, change.id)?.let { json.decodeFromString(MemoryNote.serializer(), it) }
                    check(current == change.before) { "Memory changed during this pass. Keep the newer memory." }
                    check(payload("forgotten_note", change.id) == null) { "Forgotten memory cannot return." }
                    if(change.after?.retiredAt != null || change.after?.supersededBy != null) {
                        check(current?.pinned != true && current?.linkedTaskIds.orEmpty().none { it in open }) { "An open task or user pin protects this memory." }
                    }
                    check(change.after?.sourceRefs.orEmpty().all { TaskSourcePolicy.allows(it, settings) && (it.noteId == null || payload("forgotten_note", it.noteId!!) == null) }) { "Memory uses an excluded or forgotten source." }
                }
                changes.members.forEach { change ->
                    val current = payload(MEMBER, change.id)?.let { json.decodeFromString(TeamMember.serializer(), it) }
                    check(current == change.before) { "The team changed during this review." }
                    check(change.before?.kind?.backstage != true && change.before?.kind != dev.androidagent.core.MemberKind.MANAGER) { "Built-in team roles cannot be changed by an automatic review." }
                    check(change.after?.sourceRefs.orEmpty().all { TaskSourcePolicy.allows(it, settings) }) { "Team context uses an excluded source." }
                }
                if(changes.generation != null) {
                    check(payload("active_generation", "active") == changes.baseGeneration) { "Learned rules changed during this pass." }
                    write("active_generation", "active", changes.generation!!)
                }
                changes.tasks.forEach { write(TASK, it.after.id, json.encodeToString(MikeTask.serializer(), it.after)) }
                changes.notes.forEach { change -> change.after?.let { write(NOTE, it.id, json.encodeToString(MemoryNote.serializer(), it)) } }
                changes.events.forEach { write(EVENT, it.id, json.encodeToString(TaskEvent.serializer(), it)) }
                changes.members.forEach { change ->
                    if(change.after == null) db.delete("records", "kind=? AND id=?", arrayOf(MEMBER, change.id))
                    else write(MEMBER, change.id, json.encodeToString(TeamMember.serializer(), change.after!!))
                }
                run.inputIds.forEach { db.delete("records", "kind=? AND id=?", arrayOf(SIGNAL, it)) }
                write("change_set", changes.id, json.encodeToString(TaskChangeSet.serializer(), changes))
                if(run.purpose in setOf("manager", "coach")) run.eventIds.forEach { write("consumed_${run.purpose}", it, "1") }
                val succeeded = when(run.purpose) {
                    "scout" -> settings.copy(lastSuccessfulPassAt = run.startedAt)
                    "coach" -> settings.copy(lastReviewAt = run.startedAt, coachReviewedEvents = (settings.coachReviewedEvents + run.eventIds).distinct().takeLast(4_000))
                    "librarian" -> settings.copy(lastUpkeepAt = run.startedAt, upkeepCursor = run.nextUpkeepCursor ?: settings.upkeepCursor,
                        lastDeepUpkeepAt = if(run.deepUpkeep) run.startedAt else settings.lastDeepUpkeepAt)
                    "manager" -> settings.copy(managerReviewedEvents = (settings.managerReviewedEvents + run.eventIds).distinct().takeLast(4_000))
                    else -> settings
                }
                write(SETTINGS, SETTINGS, json.encodeToString(TaskBoardSettings.serializer(), succeeded))
            }
            write("board_run", run.id, json.encodeToString(TaskRun.serializer(), run.copy(state = "committed")))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun undoChanges(id: String): TaskUndoResult = io {
        db.beginTransaction()
        try {
            val changes = payload("change_set", id)?.let { json.decodeFromString(TaskChangeSet.serializer(), it) } ?: error("No such maintenance change.")
            var restored = 0; var conflicts = 0
            if(!changes.undone) {
                changes.notes.forEach { change ->
                    val current = payload(NOTE, change.id)?.let { json.decodeFromString(MemoryNote.serializer(), it) }
                    if(current != change.after || payload("forgotten_note", change.id) != null) conflicts++
                    else {
                        val before = change.before
                        if(before == null) db.delete("records", "kind=? AND id=?", arrayOf(NOTE, change.id))
                        else write(NOTE, before.id, json.encodeToString(MemoryNote.serializer(), before))
                        restored++
                    }
                }
                if(changes.generation != null) {
                    if(payload("active_generation", "active") != changes.generation) conflicts++
                    else {
                        if(changes.baseGeneration == null) db.delete("records", "kind='active_generation'", null)
                        else write("active_generation", "active", changes.baseGeneration!!)
                        restored++
                    }
                }
                write("change_set", id, json.encodeToString(TaskChangeSet.serializer(), changes.copy(undone = true)))
            }
            db.setTransactionSuccessful()
            TaskUndoResult(restored, conflicts)
        } finally { db.endTransaction() }
    }
    override suspend fun sourceCursor(source: String): Long = io { cursorNow(source) }
    private fun cursorNow(source: String): Long = db.rawQuery("SELECT payload FROM records WHERE kind='source_cursor' AND id=?", arrayOf(source))
        .use { if (it.moveToFirst()) it.getString(0).toLong() else 0 }
    override suspend fun capturePage(source: String, expected: Long, through: Long, signals: List<TaskSignal>): List<TaskSignal> = io {
        db.beginTransaction()
        try {
            check(cursorNow(source) == expected) { "The source cursor changed." }
            val settings = payload(SETTINGS, SETTINGS)?.let { json.decodeFromString(TaskBoardSettings.serializer(), it) } ?: TaskBoardSettings()
            check(signals.all { TaskSourcePolicy.allows(TaskSourcePolicy.of(it), settings) }) { "A capture source was excluded before it was saved." }
            val fresh = signals.distinctBy { it.id }.filter { signal ->
                db.rawQuery("SELECT 1 FROM records WHERE id=? AND kind IN (?,?)", arrayOf(signal.id, SIGNAL, RECEIPT)).use { !it.moveToFirst() }
            }
            val count = db.rawQuery("SELECT count(*) FROM records WHERE kind=?", arrayOf(SIGNAL)).use { it.moveToFirst(); it.getInt(0) }
            check(count + fresh.size <= SignalInbox.MAX_PENDING) { "Capture is waiting: the inbox is full." }
            fresh.forEach {
                write(SIGNAL, it.id, json.encodeToString(TaskSignal.serializer(), it)); write(RECEIPT, it.id, it.capturedAt.toString())
                write("source_ref", it.id, json.encodeToString(TaskSourceRef.serializer(), TaskSourcePolicy.of(it)))
                dev.androidagent.core.TaskFollowThrough.feedback(it)?.let { event ->
                    write(EVENT, event.id, json.encodeToString(TaskEvent.serializer(), event))
                }
            }
            val cutoff = signals.maxOfOrNull { it.capturedAt }?.minus(SignalInbox.DEDUPE_MS)
            db.execSQL("DELETE FROM records WHERE kind=? AND id LIKE 'feedback:%' AND rowid NOT IN " +
                "(SELECT rowid FROM records WHERE kind=? AND id LIKE 'feedback:%' ORDER BY rowid DESC LIMIT 500)", arrayOf(EVENT, EVENT))
            if(cutoff != null) db.execSQL("DELETE FROM records WHERE kind=? AND CAST(payload AS INTEGER) < ?", arrayOf<Any>(RECEIPT, cutoff))
            db.execSQL("DELETE FROM records WHERE kind=? AND rowid NOT IN (SELECT rowid FROM records WHERE kind=? ORDER BY rowid DESC LIMIT ?)",
                arrayOf<Any>(RECEIPT, RECEIPT, SignalInbox.MAX_RECEIPTS))
            write("source_cursor", source, through.toString())
            db.setTransactionSuccessful()
            fresh
        } finally { db.endTransaction() }
    }

    /** Turning the board off for good: every card, note, signal and member. */
    suspend fun clearAll() = io { db.delete("records", null, null); Unit }

    private suspend fun <T> all(kind: String, serializer: KSerializer<T>): List<T> = io {
        db.rawQuery("SELECT payload FROM records WHERE kind=? ORDER BY rowid", arrayOf(kind)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    // One unreadable row is skipped, never allowed to hide the rest.
                    runCatching { json.decodeFromString(serializer, cursor.getString(0)) }.getOrNull()?.let(::add)
                }
            }
        }
    }

    private suspend fun <T> put(kind: String, id: String, serializer: KSerializer<T>, value: T) = io {
        write(kind, id, json.encodeToString(serializer, value))
    }

    private fun write(kind: String, id: String, payload: String) {
        check(db.insertWithOnConflict("records", null, values(kind, id, payload), SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "The task board could not save $kind $id."
        }
    }

    private suspend fun delete(kind: String, ids: Collection<String>) = io {
        if (ids.isEmpty()) return@io
        db.beginTransaction()
        try {
            ids.forEach { db.delete("records", "kind=? AND id=?", arrayOf(kind, it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun values(kind: String, id: String, payload: String) = ContentValues().apply {
        put("kind", kind); put("id", id); put("payload", payload)
    }

    private suspend fun <T> io(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) {
        coroutineContext[TaskWritePermit]?.let { permit ->
            val current = db.rawQuery("SELECT payload FROM records WHERE kind=? AND id=?", arrayOf(SETTINGS, SETTINGS)).use { c ->
                if(c.moveToFirst()) json.decodeFromString(TaskBoardSettings.serializer(), c.getString(0)).policyRevision else 0
            }
            check(current == permit.policyRevision) { "Sources changed. This background result was not saved." }
        }
        block()
    } }

    private class Database(context: Context) : SQLiteOpenHelper(context, "taskboard.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records(kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind, id))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TASK = "task"
        const val EVENT = "task_event"
        const val NOTE = "note"
        const val SIGNAL = "signal"
        const val RECEIPT = "signal_receipt"
        const val MEMBER = "member"
        const val SETTINGS = "settings"
    }
}
