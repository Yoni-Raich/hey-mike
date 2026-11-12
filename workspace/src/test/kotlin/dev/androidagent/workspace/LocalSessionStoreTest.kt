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

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.EngineKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSessionStoreTest {

    @Test fun childLinksAndTaskReceiptsSurviveReopenWithoutLosingOrdinaryChatState() = runBlocking {
        val store = LocalSessionStore(context)
        val parent = store.createSession()
        val child = store.createChildSession(parent.id, EngineKind.CLAUDE, "Read notes")
        val task = dev.androidagent.core.SessionAgentTask("t1", "request", parent.id, "Read notes", "  exact task\n  ",
            EngineKind.CLAUDE, sessionId = child.id, createdAt = 10, status = "running")
        store.saveAgentTask(task)
        store.setModelChoice(child.id, "model-a", "high")
        store.append(ChatMessage("child-user", child.id, "user", "Continue here", 12))
        store.setThread(child.id, "child-thread")
        store.saveComposerDraft(child.id, "  my draft  ")
        val reopened = LocalSessionStore(context)
        assertEquals(parent.id, reopened.getSession(child.id)!!.parentSessionId)
        assertEquals("Read notes", reopened.getSession(child.id)!!.title)
        assertFalse(reopened.getSession(child.id)!!.titlePending)
        assertEquals("model-a", reopened.getSession(child.id)!!.model)
        assertEquals("high", reopened.getSession(child.id)!!.reasoningEffort)
        assertEquals(EngineKind.CLAUDE, reopened.getSession(child.id)!!.engine)
        assertEquals("child-thread", reopened.getSession(child.id)!!.engineThreadId)
        assertEquals(task, reopened.loadAgentTasks().single())
        assertEquals("  my draft  ", reopened.composerDraft(child.id))
        assertEquals("Continue here", reopened.messages(child.id).first().single().text)
        assertTrue(runCatching { reopened.setParent(parent.id, child.id) }.isFailure)
        val other = reopened.createSession()
        assertTrue(runCatching { reopened.setParent(child.id, other.id) }.isFailure)
        reopened.deleteSession(parent.id)
        assertNull(reopened.getSession(child.id)!!.parentSessionId)
        assertEquals("Continue here", reopened.messages(child.id).first().single().text)
    }

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun schemaEightChildrenGainModelSettingsWithoutLosingTheirReceipt() = runBlocking {
        val file = context.getDatabasePath("sessions.db").apply { parentFile!!.mkdirs() }
        val task = dev.androidagent.core.SessionAgentTask("t", "r", "parent", "Child", "Exact task", EngineKind.CODEX,
            sessionId = "child", createdAt = 2)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,thread TEXT,engine TEXT NOT NULL DEFAULT 'CODEX',parked TEXT,catch_up INTEGER,title_pending INTEGER NOT NULL DEFAULT 0,parent_session TEXT REFERENCES sessions(id) ON DELETE SET NULL)")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,role TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,attachments TEXT NOT NULL)")
            db.execSQL("CREATE TABLE run_queue(position INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE agent_tasks(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions(id,title,created,updated) VALUES('parent','Parent',1,1)")
            db.execSQL("INSERT INTO sessions(id,title,created,updated,parent_session,thread) VALUES('child','Child',2,2,'parent','child-thread')")
            db.execSQL("INSERT INTO agent_tasks VALUES(?,?)", arrayOf("t", kotlinx.serialization.json.Json.encodeToString(dev.androidagent.core.SessionAgentTask.serializer(), task)))
            db.version = 8
        }
        val store = LocalSessionStore(context)
        assertEquals("parent", store.getSession("child")!!.parentSessionId)
        assertEquals("child-thread", store.getSession("child")!!.engineThreadId)
        assertEquals(task, store.loadAgentTasks().single())
        store.setModelChoice("child", "model-a", "high")
        assertEquals("high", LocalSessionStore(context).getSession("child")!!.reasoningEffort)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(9, it.version) }
    }

    @Test fun persistentMikeSchemaSixWithoutDraftTableOpensWithoutLosingHistory() = runBlocking {
        // The Nothing's 1113 build used v6 for is_mike, before the separate
        // dev branch used the same version for composer_drafts.
        val file = context.getDatabasePath("sessions.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,thread TEXT,engine TEXT NOT NULL DEFAULT 'CODEX',parked TEXT,catch_up INTEGER,title_pending INTEGER NOT NULL DEFAULT 0,is_mike INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX mike_main_chat ON sessions(is_mike) WHERE is_mike=1")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,role TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,attachments TEXT NOT NULL)")
            db.execSQL("CREATE INDEX message_session ON messages(session,created)")
            db.execSQL("CREATE TABLE run_queue(position INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions(id,title,created,updated,thread,engine,is_mike) VALUES(?,?,?,?,?,?,?)", arrayOf<Any>(OLD_ID, "Saved Mike", 1L, 2L, "old-thread", "CLAUDE", 1))
            db.execSQL("INSERT INTO messages VALUES('m1',?,'user','keep my history',1,'complete','[]')", arrayOf(OLD_ID))
            db.execSQL("INSERT INTO run_queue VALUES(0,?)", arrayOf("{\"sessionId\":\"$OLD_ID\",\"prompt\":\"keep queued work\"}"))
            db.version = 6
        }
        val keptFile = java.io.File(context.filesDir, "sessions/$OLD_ID/workspace/keep.txt").apply {
            parentFile!!.mkdirs(); writeText("untouched")
        }

        val store = LocalSessionStore(context)
        // Opening the main chat calls this immediately, even with no draft.
        assertNull(store.composerDraft(OLD_ID))
        assertEquals("Saved Mike", store.getSession(OLD_ID)!!.title)
        assertEquals(EngineKind.CLAUDE, store.getSession(OLD_ID)!!.engine)
        assertEquals("old-thread", store.getSession(OLD_ID)!!.engineThreadId)
        assertEquals(listOf("keep my history"), store.messages(OLD_ID).first().map { it.text })
        assertEquals("keep queued work", store.loadQueuedTurns().single().prompt)
        val draft = "  שלום\nkeep this exact  "
        store.saveComposerDraft(OLD_ID, draft)
        assertEquals(draft, LocalSessionStore(context).composerDraft(OLD_ID))
        assertEquals("untouched", keptFile.readText())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(9, db.version)
            db.rawQuery("SELECT is_mike FROM sessions WHERE id=?", arrayOf(OLD_ID)).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='mike_main_chat'", null).use { assertTrue(it.moveToFirst()) }
        }
    }

    @Test fun schemaSixWithExistingDraftsKeepsThemDuringUpgrade() = runBlocking {
        val previous = LocalSessionStore(context)
        val chat = previous.createSession(EngineKind.CLAUDE)
        previous.append(ChatMessage("kept", chat.id, "user", "existing history", 10))
        previous.saveComposerDraft(chat.id, "  existing draft\nשלום  ")
        // The other shipped v6 variant already contains composer_drafts.
        val file = context.getDatabasePath("sessions.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 6 }
        val upgraded = LocalSessionStore(context)
        assertEquals("  existing draft\nשלום  ", upgraded.composerDraft(chat.id))
        assertEquals(listOf("existing history"), upgraded.messages(chat.id).first().map { it.text })
        assertEquals(EngineKind.CLAUDE, upgraded.getSession(chat.id)!!.engine)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(9, it.version) }
    }

    @Test fun draftsSurviveReopenKeepExactWhitespaceAndStayUnsent() = runBlocking {
        val store = LocalSessionStore(context)
        val first = store.createSession()
        val second = store.createSession()
        val text = "  שלום\nreview this exactly  "
        store.saveComposerDraft(first.id, text)
        store.saveComposerDraft(second.id, "Other")
        val reopened = LocalSessionStore(context)
        assertEquals(text, reopened.composerDraft(first.id))
        assertEquals("Other", reopened.composerDraft(second.id))
        assertTrue(reopened.messages(first.id).first().isEmpty())
        reopened.saveComposerDraft(first.id, "")
        assertNull(store.composerDraft(first.id))
        assertEquals("Other", store.composerDraft(second.id))
    }

    @Test
    fun aNewChatRunsOnCodexByDefault() = runBlocking {
        val store = LocalSessionStore(context)
        val session = store.createSession()

        assertEquals(EngineKind.CODEX, session.engine)
        assertEquals(EngineKind.CODEX, LocalSessionStore(context).getSession(session.id)!!.engine)
    }

    @Test
    fun theEngineOfAChatSurvivesAReopen() = runBlocking {
        val claude = LocalSessionStore(context).createSession(EngineKind.CLAUDE)
        val codex = LocalSessionStore(context).createSession(EngineKind.CODEX)

        val reopened = LocalSessionStore(context)
        assertEquals(EngineKind.CLAUDE, claude.engine)
        assertEquals(EngineKind.CLAUDE, reopened.getSession(claude.id)!!.engine)
        assertEquals(EngineKind.CODEX, reopened.getSession(codex.id)!!.engine)
        assertEquals(EngineKind.CLAUDE, reopened.sessions.value.first { it.id == claude.id }.engine)
    }

    @Test
    fun chatsFromSchemaTwoAreMigratedInPlaceAndReadAsCodex() = runBlocking {
        // The exact v2 schema a phone has before this release.
        val file = context.getDatabasePath("sessions.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,thread TEXT)")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,role TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,attachments TEXT NOT NULL)")
            db.execSQL("CREATE INDEX message_session ON messages(session,created)")
            db.execSQL("CREATE TABLE run_queue(position INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions(id,title,created,updated,thread) VALUES(?,?,?,?,?)", arrayOf<Any>(OLD_ID, "Old chat", 1L, 2L, "thread-1"))
            db.execSQL("INSERT INTO messages VALUES('m1',?,'user','hello',1,'complete','[]')", arrayOf(OLD_ID))
            db.version = 2
        }

        val store = LocalSessionStore(context)
        val old = store.getSession(OLD_ID)!!

        assertEquals(EngineKind.CODEX, old.engine)
        assertEquals("Old chat", old.title)
        assertEquals("thread-1", old.engineThreadId)
        assertEquals(listOf("hello"), store.messages(OLD_ID).first().map { it.text })
        assertEquals(EngineKind.CLAUDE, store.createSession(EngineKind.CLAUDE).engine)
        assertFalse(old.titlePending)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(9, it.version) }
    }

    @Test fun provisionalAndChosenTitlesSurviveAReopenAndManualNamesAreProtected() = runBlocking {
        val store = LocalSessionStore(context)
        val chat = store.createSession()
        assertTrue(chat.titlePending)
        assertTrue(store.setAutomaticTitle(chat.id, "First request", complete = false))
        val reopened = LocalSessionStore(context)
        assertEquals("First request", reopened.getSession(chat.id)!!.title)
        assertTrue(reopened.getSession(chat.id)!!.titlePending)
        assertTrue(reopened.setAutomaticTitle(chat.id, "A short topic", complete = true))
        assertFalse(LocalSessionStore(context).getSession(chat.id)!!.titlePending)
        assertFalse(reopened.setAutomaticTitle(chat.id, "A later topic", complete = true))
        store.rename(chat.id, "שם שבחרתי")
        assertFalse(reopened.setAutomaticTitle(chat.id, "An automatic override", complete = true))
        assertEquals("שם שבחרתי", LocalSessionStore(context).getSession(chat.id)!!.title)
    }

    @Test
    fun aChatKeepsOneThreadPerEngineAcrossSwitchesAndReopens() = runBlocking {
        val store = LocalSessionStore(context)
        val chat = store.createSession(EngineKind.CODEX)
        store.setThread(chat.id, "codex-thread")
        store.append(ChatMessage("m1", chat.id, "user", "hello", 10))

        store.setEngine(chat.id, EngineKind.CLAUDE)
        val onClaude = LocalSessionStore(context).getSession(chat.id)!!
        assertEquals(EngineKind.CLAUDE, onClaude.engine)
        assertNull(onClaude.engineThreadId)
        assertEquals("codex-thread", onClaude.parked[EngineKind.CODEX]?.threadId)
        // Claude has no thread in this chat yet, so it has seen nothing.
        assertEquals(0L, onClaude.catchUpFrom)

        store.setThread(chat.id, "claude-thread")
        store.markCaughtUp(chat.id)
        assertNull(store.getSession(chat.id)!!.catchUpFrom)

        store.setEngine(chat.id, EngineKind.CODEX)
        val back = LocalSessionStore(context).getSession(chat.id)!!
        assertEquals(EngineKind.CODEX, back.engine)
        assertEquals("codex-thread", back.engineThreadId)
        assertEquals("claude-thread", back.parked[EngineKind.CLAUDE]?.threadId)
        // Codex missed what was said while the chat ran on Claude.
        assertEquals(onClaude.parked[EngineKind.CODEX]?.seenUntil, back.catchUpFrom)
    }

    @Test
    fun anEmptyChatChangesEngineWithNothingToCatchUp() = runBlocking {
        val store = LocalSessionStore(context)
        val chat = store.createSession(EngineKind.CODEX)

        store.setEngine(chat.id, EngineKind.CLAUDE)

        val moved = store.getSession(chat.id)!!
        assertEquals(EngineKind.CLAUDE, moved.engine)
        assertNull(moved.catchUpFrom)
        assertEquals(emptyMap<EngineKind, Any>(), moved.parked)
    }

    @Test
    fun aFileAnOlderBuildOpenedInBetweenIsUpgradedAgainWithoutLosingChats() = runBlocking {
        // A newer build wrote the engine column, then an older build opened
        // the file and set its version back, keeping the column.
        val created = LocalSessionStore(context).createSession(EngineKind.CLAUDE)
        val file = context.getDatabasePath("sessions.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }

        val store = LocalSessionStore(context)

        assertEquals(EngineKind.CLAUDE, store.getSession(created.id)!!.engine)
        store.setEngine(created.id, EngineKind.CODEX)
        assertEquals(EngineKind.CODEX, store.getSession(created.id)!!.engine)
    }

    @Test
    fun anUnknownStoredEngineFallsBackToCodex() = runBlocking {
        val session = LocalSessionStore(context).createSession(EngineKind.CLAUDE)
        SQLiteDatabase.openDatabase(context.getDatabasePath("sessions.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE sessions SET engine='GEMINI' WHERE id=?", arrayOf(session.id))
        }

        assertEquals(EngineKind.CODEX, LocalSessionStore(context).getSession(session.id)!!.engine)
    }

    private companion object {
        const val OLD_ID = "0f8c3a52-6f7e-4d8a-9b1c-2d3e4f5a6b7c"
    }
}
