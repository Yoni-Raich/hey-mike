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
import dev.androidagent.core.EngineKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSessionStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

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
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(3, it.version) }
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
