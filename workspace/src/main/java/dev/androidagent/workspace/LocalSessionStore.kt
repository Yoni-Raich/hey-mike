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
import dev.androidagent.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalSessionStore(context: Context) : SessionStore {
    private val appContext = context.applicationContext
    private val base = File(context.filesDir, "sessions").apply { mkdirs() }
    private val db = Database(context).writableDatabase
    private val lock = Mutex()
    private val streams = ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val sessionStream = MutableStateFlow(loadSessions())
    override val sessions: StateFlow<List<ChatSession>> = sessionStream.asStateFlow()
    init { db.execSQL("UPDATE messages SET state='interrupted' WHERE state='streaming'") }

    override suspend fun createSession(): ChatSession = mutate {
        val now = System.currentTimeMillis()
        val session = ChatSession(UUID.randomUUID().toString(), "New chat", now, now)
        db.insertOrThrow("sessions", null, ContentValues().apply { put("id", session.id); put("title", session.title); put("created", now); put("updated", now) })
        workspace(session.id).mkdirs()
        refresh()
        session
    }
    override suspend fun getSession(id: String): ChatSession? = withContext(Dispatchers.IO) { loadSessions().firstOrNull { it.id == id } }
    override fun messages(sessionId: String): Flow<List<ChatMessage>> = streams.getOrPut(sessionId) { MutableStateFlow(loadMessages(sessionId)) }.asStateFlow()
    override suspend fun append(message: ChatMessage) = mutate {
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", message.id); put("session", message.sessionId); put("role", message.role); put("text", message.text)
            put("created", message.createdAt); put("state", message.state); put("attachments", Json.encodeToString(message.attachmentPaths))
        })
        db.execSQL("UPDATE sessions SET updated=? WHERE id=?", arrayOf(message.createdAt, message.sessionId))
        refresh(message.sessionId)
    }
    override suspend fun updateMessage(id: String, text: String, state: String) = mutate {
        val session = db.rawQuery("SELECT session FROM messages WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
        db.update("messages", ContentValues().apply { put("text", text); put("state", state) }, "id=?", arrayOf(id))
        // Streaming does not reorder sessions. Avoid reloading every chat and
        // the whole message history on each text flush.
        session?.let { sessionId -> streams[sessionId]?.let { stream ->
            stream.value = stream.value.map { if (it.id == id) it.copy(text = text, state = state) else it }
        } }
        Unit
    }
    override suspend fun setThread(sessionId: String, threadId: String) = mutate { db.execSQL("UPDATE sessions SET thread=? WHERE id=?", arrayOf(threadId, sessionId)); refresh() }
    override suspend fun loadQueuedTurns(): List<QueuedTurn> = mutate {
        db.rawQuery("SELECT payload FROM run_queue ORDER BY position", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Json.decodeFromString<QueuedTurn>(cursor.getString(0))) }
        }
    }
    override suspend fun saveQueuedTurns(turns: List<QueuedTurn>) = mutate {
        db.beginTransaction()
        try {
            db.delete("run_queue", null, null)
            turns.forEachIndexed { index, turn -> db.insertOrThrow("run_queue", null, ContentValues().apply {
                put("position", index); put("payload", Json.encodeToString(turn))
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun rename(sessionId: String, title: String) = mutate { db.execSQL("UPDATE sessions SET title=? WHERE id=?", arrayOf(title.trim().take(80).ifBlank { "New chat" }, sessionId)); refresh() }
    override suspend fun deleteSession(sessionId: String) = mutate {
        val folder = workspace(sessionId).parentFile!!
        require(folder.canonicalFile.parentFile == base.canonicalFile)
        db.beginTransaction()
        try { db.delete("messages", "session=?", arrayOf(sessionId)); db.delete("sessions", "id=?", arrayOf(sessionId)); db.setTransactionSuccessful() } finally { db.endTransaction() }
        folder.deleteRecursively()
        streams.remove(sessionId)?.value = emptyList()
        refresh()
    }
    override fun workspace(sessionId: String): File {
        require(runCatching { UUID.fromString(sessionId).toString() == sessionId }.getOrDefault(false)) { "Invalid session ID" }
        val ws = File(base, "$sessionId/workspace").apply { mkdirs() }
        WorkspaceSeeder.seed(ws, appContext)
        return ws
    }
    private suspend fun <T> mutate(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }
    private fun refresh(sessionId: String? = null) { sessionStream.value = loadSessions(); sessionId?.let { streams[it]?.value = loadMessages(it) } }
    private fun loadSessions(): List<ChatSession> = db.rawQuery("SELECT id,title,created,updated,thread FROM sessions ORDER BY updated DESC", null).use { c -> buildList { while (c.moveToNext()) add(ChatSession(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4))) } }
    private fun loadMessages(sessionId: String): List<ChatMessage> = db.rawQuery("SELECT id,role,text,created,state,attachments FROM messages WHERE session=? ORDER BY created,rowid", arrayOf(sessionId)).use { c -> buildList { while (c.moveToNext()) add(ChatMessage(c.getString(0), sessionId, c.getString(1), c.getString(2), c.getLong(3), c.getString(4), runCatching { Json.decodeFromString<List<String>>(c.getString(5)) }.getOrDefault(emptyList()))) } }

    private class Database(context: Context) : SQLiteOpenHelper(context, "sessions.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,thread TEXT)")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,role TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,attachments TEXT NOT NULL)")
            db.execSQL("CREATE INDEX message_session ON messages(session,created)")
            db.execSQL("CREATE TABLE run_queue(position INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("CREATE TABLE run_queue(position INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
        }
    }
}
