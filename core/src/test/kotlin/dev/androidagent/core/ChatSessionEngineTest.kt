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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ChatSessionEngineTest {

    @Test
    fun aSessionRunsOnCodexUnlessToldOtherwise() {
        assertEquals(EngineKind.CODEX, ChatSession("a", "A", 0, 0).engine)
    }

    @Test
    fun aSessionSavedBeforeEnginesExistedReadsAsCodex() {
        val old = """{"id":"a","title":"A","createdAt":0,"updatedAt":0}"""
        assertEquals(EngineKind.CODEX, Json.decodeFromString<ChatSession>(old).engine)
    }

    @Test
    fun theOldCreateSessionMakesACodexChat() = runTest {
        val store = RecordingStore()
        store.createSession()
        assertEquals(listOf(EngineKind.CODEX), store.created)
        store.createSession(EngineKind.CLAUDE)
        assertEquals(listOf(EngineKind.CODEX, EngineKind.CLAUDE), store.created)
    }

    private class RecordingStore : SessionStore {
        val created = mutableListOf<EngineKind>()
        override val sessions = MutableStateFlow(emptyList<ChatSession>())
        override suspend fun createSession(engine: EngineKind): ChatSession {
            created += engine
            return ChatSession("a", "A", 0, 0, engine = engine)
        }
        override suspend fun getSession(id: String): ChatSession? = null
        override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(sessionId)
    }
}
