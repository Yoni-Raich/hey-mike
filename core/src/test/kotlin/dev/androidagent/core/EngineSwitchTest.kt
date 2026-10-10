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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSwitchTest {
    private val chat = ChatSession("c", "Chat", 1, 2, engineThreadId = "codex-1", hasMessages = true, engine = EngineKind.CODEX)

    @Test fun anEngineChangeClearsIncompatibleModelSettingsButKeepsTheParentLink() {
        val child = chat.copy(parentSessionId = "parent", model = "model-a", reasoningEffort = "high")
        val moved = EngineSwitch.switch(child, EngineKind.CLAUDE, 100)
        assertEquals("parent", moved.parentSessionId)
        assertNull(moved.model)
        assertNull(moved.reasoningEffort)
        assertSame(child, EngineSwitch.switch(child, EngineKind.CODEX, 100))
    }

    @Test
    fun switchingToTheSameEngineChangesNothing() {
        assertSame(chat, EngineSwitch.switch(chat, EngineKind.CODEX, now = 100))
    }

    @Test
    fun theFirstMoveParksTheThreadAndOwesTheWholeChat() {
        val moved = EngineSwitch.switch(chat, EngineKind.CLAUDE, now = 100)

        assertEquals(EngineKind.CLAUDE, moved.engine)
        assertNull(moved.engineThreadId)
        assertEquals(ParkedThread("codex-1", seenUntil = 100), moved.parked[EngineKind.CODEX])
        assertEquals(0L, moved.catchUpFrom)
    }

    @Test
    fun movingBackBringsTheThreadBackAndOwesOnlyWhatItMissed() {
        val onClaude = EngineSwitch.switch(chat, EngineKind.CLAUDE, now = 100).copy(engineThreadId = "claude-1", catchUpFrom = null)

        val back = EngineSwitch.switch(onClaude, EngineKind.CODEX, now = 200)

        assertEquals("codex-1", back.engineThreadId)
        assertEquals(100L, back.catchUpFrom)
        assertEquals(mapOf(EngineKind.CLAUDE to ParkedThread("claude-1", seenUntil = 200)), back.parked)
    }

    @Test
    fun anEngineLeftBeforeItCaughtUpStillOwesFromWhereItWas() {
        // Codex -> Claude -> Codex, and Codex is left again before any turn ran.
        val onClaude = EngineSwitch.switch(chat, EngineKind.CLAUDE, now = 100).copy(engineThreadId = "claude-1", catchUpFrom = null)
        val back = EngineSwitch.switch(onClaude, EngineKind.CODEX, now = 200)

        val again = EngineSwitch.switch(back, EngineKind.CLAUDE, now = 300)
        val codexLater = EngineSwitch.switch(again, EngineKind.CODEX, now = 400)

        // Claude was up to date at 200. Codex never caught up, so it still owes from 100.
        assertEquals(200L, again.catchUpFrom)
        assertEquals(100L, codexLater.catchUpFrom)
    }

    @Test
    fun anEngineThatNeverGotAThreadIsNotParked() {
        val neverRan = EngineSwitch.switch(chat, EngineKind.CLAUDE, now = 100)

        val back = EngineSwitch.switch(neverRan, EngineKind.CODEX, now = 200)

        assertEquals(emptyMap<EngineKind, ParkedThread>(), back.parked)
        assertEquals("codex-1", back.engineThreadId)
    }

    @Test
    fun aChatNobodyWroteInOwesNothing() {
        val blank = ChatSession("c", "New chat", 1, 1, hasMessages = false)

        val moved = EngineSwitch.switch(blank, EngineKind.CLAUDE, now = 100)

        assertNull(moved.catchUpFrom)
        assertTrue(moved.parked.isEmpty())
    }

    @Test
    fun aThreadIsFoundOnTheEngineThatOwnsIt() {
        val onClaude = EngineSwitch.switch(chat, EngineKind.CLAUDE, now = 100).copy(engineThreadId = "claude-1")

        assertEquals(EngineKind.CLAUDE, EngineSwitch.engineOf(onClaude, "claude-1"))
        assertEquals(EngineKind.CODEX, EngineSwitch.engineOf(onClaude, "codex-1"))
        assertNull(EngineSwitch.engineOf(onClaude, "someone-else"))
    }
}

class ChatHandoffTest {
    private fun message(role: String, text: String, at: Long, attachments: List<String> = emptyList()) =
        ChatMessage("m$at", "c", role, text, at, attachmentPaths = attachments)

    @Test
    fun nothingToCarryOverGivesNoText() {
        assertNull(ChatHandoff.build(emptyList()))
        assertNull(ChatHandoff.build(listOf(message("note", "Switched to Claude", 1), message("system", "Run failed", 2))))
    }

    @Test
    fun whatWasSaidAndDoneIsCarriedInOrder() {
        val text = ChatHandoff.build(
            listOf(
                message("user", "Open my alarms", 1),
                message("tool", "open_intent: opened the clock\nsecond line", 2),
                message("assistant", "Your alarms are open.", 3),
                message("note", "Chat compacted", 4),
                message("user", "Look at this", 5, attachments = listOf("/chat/attachments/a.png")),
            ),
        )!!

        val body = text.substringAfter("\n\n").substringBefore("[End of earlier messages]")
        assertEquals(
            "User: Open my alarms\n" +
                "(Mike ran open_intent: opened the clock second line)\n" +
                "Mike: Your alarms are open.\n" +
                "User: Look at this [attached: /chat/attachments/a.png]\n",
            body,
        )
        assertTrue(text.startsWith("[Earlier in this chat]\n"))
        assertTrue(text.endsWith("[End of earlier messages]\n\n"))
        assertTrue("already carried out" in text)
    }

    @Test
    fun aLongChatKeepsItsNewestMessages() {
        val messages = (1..50L).map { message(if (it % 2 == 1L) "user" else "assistant", "message $it " + "x".repeat(200), it) }

        val text = ChatHandoff.build(messages, maxChars = 2_000)!!

        assertTrue("message 50 " in text)
        assertTrue("message 1 " !in text)
        assertTrue(Regex("""\((\d+) earlier messages are left out\.\)""").containsMatchIn(text))
    }

    @Test
    fun oneHugeMessageIsCutNotDropped() {
        val text = ChatHandoff.build(listOf(message("user", "y".repeat(100_000), 1)), maxChars = 2_000)!!

        assertTrue("User: yyy" in text)
        assertTrue(text.length < 6_000)
    }
}
