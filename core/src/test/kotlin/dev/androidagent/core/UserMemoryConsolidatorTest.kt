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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class UserMemoryConsolidatorTest {
    private val zone = ZoneId.of("UTC")

    @Test fun buildDigestFiltersAutomationsSubagentsOldMessagesAndRedactsSecrets() = runBlocking {
        val store = FakeStore()
        val normal = ChatSession("c1", "Weekend plans", createdAt = 100, updatedAt = 5_000, hasMessages = true, engine = EngineKind.CLAUDE)
        val older = ChatSession("c2", "Old chat", createdAt = 100, updatedAt = 900, hasMessages = true)
        val ruleByTitle = ChatSession("c3", "Rule · Morning weather", createdAt = 100, updatedAt = 6_000, hasMessages = true)
        val ruleById = ChatSession("c4", "Quiet rule", createdAt = 100, updatedAt = 6_500, hasMessages = true)
        val child = ChatSession("c5", "Child helper", createdAt = 100, updatedAt = 7_000, hasMessages = true, parentSessionId = "c1")
        val assistantOnly = ChatSession("c6", "System notice", createdAt = 100, updatedAt = 8_000, hasMessages = true)
        store.sessions.value = listOf(normal, older, ruleByTitle, ruleById, child, assistantOnly)

        val hugeCode = "```kotlin\n" + "val x = 1\n".repeat(40) + "```"
        store.append(ChatMessage("m0", "c1", "user", "Before watermark", createdAt = 500))
        store.append(ChatMessage("m1", "c1", "user", "We keep kosher and password: TopSecret999\n$hugeCode", createdAt = 4_000))
        store.append(ChatMessage("m2", "c1", "tool", "tap ok", createdAt = 4_500))
        store.append(ChatMessage("m3", "c1", "assistant", "Noted.", createdAt = 5_000))
        store.append(ChatMessage("m4", "c3", "user", "Automation prompt", createdAt = 6_000))
        store.append(ChatMessage("m5", "c4", "user", "Excluded rule prompt", createdAt = 6_500))
        store.append(ChatMessage("m6", "c5", "user", "Subagent prompt", createdAt = 7_000))
        store.append(ChatMessage("m7", "c6", "assistant", "Notice only", createdAt = 8_000))

        val digest = UserMemoryConsolidator.buildDigest(
            sessions = store,
            sinceMillis = 1_000L,
            excludedSessionIds = setOf("c4"),
            zone = zone,
        )
        assertFalse(digest.isEmpty)
        assertEquals(1, digest.chatCount)
        assertEquals(2, digest.messageCount)
        assertEquals(5_000L, digest.latestMessageAt)
        assertTrue(digest.text.contains("Weekend plans (claude"))
        assertTrue(digest.text.contains("We keep kosher"))
        assertTrue(digest.text.contains("[code omitted]"))
        assertFalse(digest.text.contains("TopSecret999"))
        assertFalse(digest.text.contains("Before watermark"))
        assertFalse(digest.text.contains("tap ok"))
        assertFalse(digest.text.contains("Automation prompt"))
        assertFalse(digest.text.contains("Excluded rule prompt"))
        assertFalse(digest.text.contains("Subagent prompt"))
        assertFalse(digest.text.contains("Notice only"))
    }

    @Test fun parseOutputHandlesNoChangesFencesRefusalsAndWipeProtection() {
        assertEquals(ConsolidationOutput.NoChanges, UserMemoryConsolidator.parseOutput("NO_CHANGES", ""))
        assertEquals(ConsolidationOutput.NoChanges, UserMemoryConsolidator.parseOutput("`no_changes`", "existing"))
        assertEquals(ConsolidationOutput.NoChanges, UserMemoryConsolidator.parseOutput("```\nNO_CHANGES\n```", "existing"))

        val fenced = UserMemoryConsolidator.parseOutput("```markdown\n## About\n- Lives in Tel Aviv\n```", "")
        assertTrue(fenced is ConsolidationOutput.Updated)
        assertEquals("## About\n- Lives in Tel Aviv", (fenced as ConsolidationOutput.Updated).profile)

        val refused = UserMemoryConsolidator.parseOutput("I cannot modify personal data.", "")
        assertTrue(refused is ConsolidationOutput.Invalid)

        val existing = "## About\n- " + "durable fact ".repeat(10)
        val wipe = UserMemoryConsolidator.parseOutput("- ok", existing)
        assertTrue(wipe is ConsolidationOutput.Invalid)

        val oversize = UserMemoryConsolidator.parseOutput("x".repeat(UserMemoryStore.MAX_PROFILE_CHARS + 5), "")
        assertTrue(oversize is ConsolidationOutput.Invalid)
    }

    @Test fun nextRunAtMillisFollowsScheduleAndBackoff() {
        fun utc(day: Int, hour: Int, min: Int = 0) =
            ZonedDateTime.of(2026, 11, day, hour, min, 0, 0, zone).toInstant().toEpochMilli()

        val disabled = UserMemoryConfig(enabled = false, autoLearn = true, nightlyHour = 3)
        assertNull(UserMemoryConsolidator.nextRunAtMillis(disabled, utc(25, 1), zone))

        val manualOnly = UserMemoryConfig(enabled = true, autoLearn = false, nightlyHour = 3)
        assertNull(UserMemoryConsolidator.nextRunAtMillis(manualOnly, utc(25, 1), zone))

        // Before 03:00 -> schedule today at 03:00.
        val base = UserMemoryConfig(enabled = true, autoLearn = true, nightlyHour = 3)
        assertEquals(utc(25, 3), UserMemoryConsolidator.nextRunAtMillis(base, utc(25, 1, 30), zone))

        // After 03:00 with no run today -> due now.
        assertEquals(utc(25, 4, 15), UserMemoryConsolidator.nextRunAtMillis(base, utc(25, 4, 15), zone))

        // Already succeeded today -> tomorrow at 03:00.
        val succeeded = base.copy(lastRunAt = utc(25, 3, 5), lastRunOk = true)
        assertEquals(utc(26, 3), UserMemoryConsolidator.nextRunAtMillis(succeeded, utc(25, 10), zone))

        // Failed today at 03:05 -> retry at 05:05.
        val failed = base.copy(lastRunAt = utc(25, 3, 5), lastRunOk = false)
        assertEquals(utc(25, 5, 5), UserMemoryConsolidator.nextRunAtMillis(failed, utc(25, 3, 10), zone))

        // Failed late at 02:00 next morning -> capped at next slot.
        val failedLate = base.copy(lastRunAt = utc(25, 23, 30), lastRunOk = false)
        assertEquals(utc(26, 1, 30), UserMemoryConsolidator.nextRunAtMillis(failedLate, utc(25, 23, 35), zone))
    }

    private class FakeStore : SessionStore {
        override val sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        private val history = mutableListOf<ChatMessage>()
        private val flows = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()
        override suspend fun createSession(engine: EngineKind) = error("unused")
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flows.getOrPut(sessionId) { MutableStateFlow(history.filter { it.sessionId == sessionId }) }
        override suspend fun append(message: ChatMessage) {
            history += message
            flows.getOrPut(message.sessionId) { MutableStateFlow(emptyList()) }.value = history.filter { it.sessionId == message.sessionId }
        }
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(sessionId)
    }
}
