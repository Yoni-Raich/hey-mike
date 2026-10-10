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
import kotlinx.serialization.json.JsonObject
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

    override suspend fun createSession(engine: EngineKind): ChatSession = mutate { create(engine) }
    override suspend fun createChildSession(parentSessionId: String, engine: EngineKind, title: String): ChatSession = mutate {
        check(loadSessions().any { it.id == parentSessionId }) { "Parent chat no longer exists." }
        create(engine, parentSessionId, title)
    }
    private fun create(engine: EngineKind, parent: String? = null, title: String = "New chat"): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(UUID.randomUUID().toString(), title, now, now, hasMessages = false, engine = engine, titlePending = parent == null, parentSessionId = parent)
        db.insertOrThrow("sessions", null, ContentValues().apply { put("id", session.id); put("title", session.title); put("created", now); put("updated", now); put("engine", engine.name); put("title_pending", if (session.titlePending) 1 else 0); put("parent_session", parent) })
        workspace(session.id).mkdirs()
        refresh()
        return session
    }
    override suspend fun setParent(sessionId: String, parentSessionId: String) = mutate {
        val chats = loadSessions().associateBy { it.id }
        val child = chats[sessionId] ?: error("Child chat no longer exists.")
        require(parentSessionId in chats && sessionId != parentSessionId)
        require(child.parentSessionId == null || child.parentSessionId == parentSessionId) { "Child already belongs to another chat." }
        var ancestor: String? = parentSessionId
        val seen = mutableSetOf<String>()
        while (ancestor != null) {
            require(ancestor != sessionId && seen.add(ancestor)) { "Child link would form a cycle." }
            ancestor = chats[ancestor]?.parentSessionId
        }
        db.execSQL("UPDATE sessions SET parent_session=? WHERE id=?", arrayOf(parentSessionId, sessionId))
        refresh()
    }
    override suspend fun loadAgentTasks(): List<SessionAgentTask> = mutate {
        db.rawQuery("SELECT payload FROM agent_tasks ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Json.decodeFromString<SessionAgentTask>(cursor.getString(0))) }
        }
    }
    override suspend fun setModelChoice(sessionId: String, model: String?, reasoningEffort: String?) = mutate {
        db.update("sessions", ContentValues().apply {
            put("model", model); put("reasoning_effort", reasoningEffort)
        }, "id=?", arrayOf(sessionId))
        refresh()
    }
    override suspend fun saveAgentTask(task: SessionAgentTask) = mutate {
        db.insertWithOnConflict("agent_tasks", null, ContentValues().apply {
            put("id", task.id); put("payload", Json.encodeToString(task))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Agent receipt could not be saved" } }
        Unit
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
    override suspend fun setEngine(sessionId: String, engine: EngineKind) = mutate {
        val session = loadSessions().firstOrNull { it.id == sessionId } ?: return@mutate
        val next = EngineSwitch.switch(session, engine, System.currentTimeMillis())
        if (next == session) return@mutate
        db.update("sessions", ContentValues().apply {
            put("engine", next.engine.name)
            putNull("model"); putNull("reasoning_effort")
            if (next.engineThreadId == null) putNull("thread") else put("thread", next.engineThreadId)
            put("parked", Json.encodeToString(next.parked))
            if (next.catchUpFrom == null) putNull("catch_up") else put("catch_up", next.catchUpFrom)
        }, "id=?", arrayOf(sessionId))
        refresh()
    }
    override suspend fun markCaughtUp(sessionId: String) = mutate {
        db.execSQL("UPDATE sessions SET catch_up=NULL WHERE id=?", arrayOf(sessionId))
        refresh()
    }
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
    override suspend fun rename(sessionId: String, title: String) = mutate {
        db.execSQL("UPDATE sessions SET title=?,title_pending=0 WHERE id=?", arrayOf(ChatTitles.normalize(title).take(ChatTitles.MAX_LENGTH).ifBlank { "New chat" }, sessionId))
        refresh()
    }
    override suspend fun setAutomaticTitle(sessionId: String, title: String, complete: Boolean): Boolean = mutate {
        val changed = db.update("sessions", ContentValues().apply {
            put("title", ChatTitles.normalize(title).take(ChatTitles.MAX_LENGTH))
            if (complete) put("title_pending", 0)
        }, "id=? AND title_pending=1", arrayOf(sessionId)) > 0
        if (changed) refresh()
        changed
    }
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
        val ws = workspaceDirectory(sessionId).apply { mkdirs() }
        WorkspaceSeeder.seed(ws, appContext)
        return ws
    }
    private fun workspaceDirectory(sessionId: String): File {
        require(runCatching { UUID.fromString(sessionId).toString() == sessionId }.getOrDefault(false)) { "Invalid session ID" }
        return File(base, "$sessionId/workspace")
    }
    override suspend fun appendTrace(sessionId: String, entry: JsonObject) = mutate {
        // The workspace is private to this chat, shown in Files, and can be
        // shared by the user. One line is one event, so a stopped run remains
        // readable without needing to close a JSON array.
        File(workspaceDirectory(sessionId).apply { mkdirs() }, "session-trace.jsonl")
            .appendText(entry.toString() + "\n", Charsets.UTF_8)
    }
    override suspend fun saveComposerDraft(sessionId: String, text: String) = mutate {
        if (text.isEmpty()) {
            db.delete("composer_drafts", "session=?", arrayOf(sessionId))
        } else {
            db.insertWithOnConflict("composer_drafts", null, ContentValues().apply {
                put("session", sessionId); put("text", text)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Draft could not be saved" } }
        }
        Unit
    }
    override suspend fun composerDraft(sessionId: String): String? = mutate {
        db.rawQuery("SELECT text FROM composer_drafts WHERE session=?", arrayOf(sessionId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
    private suspend fun <T> mutate(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }
    private fun refresh(sessionId: String? = null) { sessionStream.value = loadSessions(); sessionId?.let { streams[it]?.value = loadMessages(it) } }
    private fun loadSessions(): List<ChatSession> = db.rawQuery("SELECT id,title,created,updated,thread,EXISTS(SELECT 1 FROM messages WHERE session=sessions.id),engine,parked,catch_up,title_pending,parent_session,model,reasoning_effort FROM sessions ORDER BY updated DESC", null).use { c -> buildList { while (c.moveToNext()) add(ChatSession(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4), c.getInt(5) == 1, engineOf(c.getString(6)), parkedOf(c.getString(7)), if (c.isNull(8)) null else c.getLong(8), c.getInt(9) == 1, c.getString(10), c.getString(11), c.getString(12))) } }
    // An unreadable value only loses the thread waiting on the other engine;
    // the chat still opens and that engine is given its text again.
    private fun parkedOf(stored: String?): Map<EngineKind, ParkedThread> =
        stored?.let { runCatching { Json.decodeFromString<Map<EngineKind, ParkedThread>>(it) }.getOrNull() }.orEmpty()
    // A value this release does not know (a newer app, a bad write) must not
    // lose the chat, so it reads as the engine every chat had before.
    private fun engineOf(stored: String?): EngineKind = EngineKind.entries.firstOrNull { it.name == stored } ?: EngineKind.CODEX
    private fun loadMessages(sessionId: String): List<ChatMessage> = db.rawQuery("SELECT id,role,text,created,state,attachments FROM messages WHERE session=? ORDER BY created,rowid", arrayOf(sessionId)).use { c -> buildList { while (c.moveToNext()) add(ChatMessage(c.getString(0), sessionId, c.getString(1), c.getString(2), c.getLong(3), c.getString(4), runCatching { Json.decodeFromString<List<String>>(c.getString(5)) }.getOrDefault(emptyList()))) } }

    // v6 had two shipped schemas: persistent Mike's is_mike column and the
    // dev branch's composer_drafts table. v7 runs the additive migration for
    // both, even when Android sees no version change between those v6 builds.
    private class Database(context: Context) : SQLiteOpenHelper(context, "sessions.db", null, 9) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,thread TEXT,engine TEXT NOT NULL DEFAULT 'CODEX',parked TEXT,catch_up INTEGER,title_pending INTEGER NOT NULL DEFAULT 0,parent_session TEXT REFERENCES sessions(id) ON DELETE SET NULL,model TEXT,reasoning_effort TEXT)")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,role TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,attachments TEXT NOT NULL)")
            db.execSQL("CREATE INDEX message_session ON messages(session,created)")
            db.execSQL("CREATE TABLE run_queue(position INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE composer_drafts(session TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,text TEXT NOT NULL)")
            db.execSQL("CREATE TABLE agent_tasks(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("CREATE TABLE IF NOT EXISTS composer_drafts(session TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,text TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
            if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS run_queue(position INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            // An older build may have opened this file since a newer one wrote
            // it (see onDowngrade): the version went back but the columns
            // stayed, so each one is added only when it is missing.
            val columns = db.rawQuery("PRAGMA table_info(sessions)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
            if ("parent_session" !in columns) db.execSQL("ALTER TABLE sessions ADD COLUMN parent_session TEXT REFERENCES sessions(id) ON DELETE SET NULL")
            if ("model" !in columns) db.execSQL("ALTER TABLE sessions ADD COLUMN model TEXT")
            if ("reasoning_effort" !in columns) db.execSQL("ALTER TABLE sessions ADD COLUMN reasoning_effort TEXT")
            // Every chat before v3 ran on Codex; the default fills existing rows in place.
            if ("engine" !in columns) db.execSQL("ALTER TABLE sessions ADD COLUMN engine TEXT NOT NULL DEFAULT 'CODEX'")
            // v4: a chat can change engine. The thread it leaves and what the other engine missed.
            if ("parked" !in columns) db.execSQL("ALTER TABLE sessions ADD COLUMN parked TEXT")
            if ("catch_up" !in columns) db.execSQL("ALTER TABLE sessions ADD COLUMN catch_up INTEGER")
            // Existing chosen names are protected. Only new chats get automatic titles.
            if ("title_pending" !in columns) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN title_pending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sessions SET title_pending=1 WHERE title='New chat' AND thread IS NULL AND NOT EXISTS(SELECT 1 FROM messages WHERE session=sessions.id AND role='user')")
            }
        }
        // A newer build may have written this file. Its extra columns are
        // harmless here, so keep the history instead of letting SQLite refuse
        // to open it.
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
