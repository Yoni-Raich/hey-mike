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
    override suspend fun replaceNotes(notes: List<MemoryNote>) = io {
        db.beginTransaction()
        try {
            db.delete("records", "kind=?", arrayOf(NOTE))
            notes.forEach { write(NOTE, it.id, json.encodeToString(MemoryNote.serializer(), it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun loadSignals(): List<TaskSignal> = all(SIGNAL, TaskSignal.serializer())
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
                write(SIGNAL, signal.id, json.encodeToString(TaskSignal.serializer(), signal))
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

    private suspend fun <T> io(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    private class Database(context: Context) : SQLiteOpenHelper(context, "taskboard.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records(kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind, id))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TASK = "task"
        const val NOTE = "note"
        const val SIGNAL = "signal"
        const val RECEIPT = "signal_receipt"
        const val MEMBER = "member"
        const val SETTINGS = "settings"
    }
}
