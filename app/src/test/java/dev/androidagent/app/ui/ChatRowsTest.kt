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

package dev.androidagent.app.ui

import dev.androidagent.core.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {
    private fun message(id: String, role: String, text: String = id) = ChatMessage(id, "s", role, text, 0L)

    @Test fun backToBackToolMessagesFoldIntoOneRow() {
        val rows = chatRows(
            listOf(
                message("u1", "user"),
                message("t1", "tool", "open_app: ok"),
                message("t2", "tool", "read_ui: {}"),
                message("t3", "tool", "tap: ok"),
                message("a1", "assistant"),
                message("t4", "tool", "read_ui: {}"),
            ),
            running = false,
        )
        assertEquals(listOf("u1", "actions-t1", "a1", "actions-t4"), rows.map { it.key })
        assertEquals(3, (rows[1] as ActionsRow).steps.size)
        assertFalse((rows[3] as ActionsRow).live)
    }

    @Test fun onlyATrailingGroupIsLive() {
        val messages = listOf(message("t1", "tool"), message("a1", "assistant"), message("t2", "tool"))
        val rows = chatRows(messages, running = true)
        assertFalse((rows[0] as ActionsRow).live)
        assertTrue((rows[2] as ActionsRow).live)
    }

    @Test fun systemMessagesStayOnTheirOwn() {
        val rows = chatRows(listOf(message("s1", "system"), message("t1", "tool")), running = false)
        assertTrue(rows[0] is MessageRow)
        assertTrue(rows[1] is ActionsRow)
    }

    @Test fun labelsCountTheActions() {
        assertEquals("1 action on your phone", actionsLabel(1, live = false))
        assertEquals("5 actions on your phone", actionsLabel(5, live = false))
        assertEquals("Working on your phone · 3", actionsLabel(3, live = true))
        assertEquals("Working on your phone", actionsLabel(0, live = true))
    }

    @Test fun toolsReadAsWhatHappened() {
        assertEquals("read_ui", toolNameOf(message("t", "tool", "read_ui: {\"nodes\":[]}")))
        assertEquals("Read the screen", toolStepLabel("read_ui"))
        assertEquals("Opened a link", toolStepLabel("open_intent"))
        assertEquals("Ran a workflow", toolStepLabel("run_workflow"))
        assertEquals("Ran a workflow", toolStepLabel("workflow_runner"))
        // A tool the map does not know still reads as words, not as its id.
        assertEquals("Remember capability", toolStepLabel("remember_capability"))
        assertEquals("Reading the screen", runStatusLabel("read ui"))
        assertEquals("Waiting for approval", runStatusLabel("Waiting for approval"))
    }

    @Test
    fun workflowSuggestionsAreOfferedOnlyAfterARunThatOperatedThePhone() {
        fun tool(id: String, name: String) = message(id, "tool", "$name: ok")
        fun reply(state: String = "complete") = ChatMessage("r", "s", "assistant", "Done", 0L, state)
        val ask = message("u", "user", "turn on wireless debugging")
        val operated = listOf(ask, tool("1", "open_app"), tool("2", "tap_node"), tool("3", "read_ui"),
            tool("4", "set_text"), tool("5", "tap"), reply())
        assertTrue(offersWorkflowSuggestion(operated, running = false))
        // Not while the run is going, and not before the reply is finished.
        assertFalse(offersWorkflowSuggestion(operated, running = true))
        assertFalse(offersWorkflowSuggestion(operated.dropLast(1) + reply("streaming"), running = false))
        // Reading the screen is not a sequence worth saving.
        val onlyLooked = listOf(ask, tool("1", "read_ui"), tool("2", "read_ui"), tool("3", "screenshot"),
            tool("4", "read_ui"), reply())
        assertFalse(offersWorkflowSuggestion(onlyLooked, running = false))
        // The answer to the suggestion itself does not offer another one.
        val suggested = operated + message("u2", "user", SUGGEST_WORKFLOWS_PROMPT) +
            (1..5).map { tool("s$it", "tap") } + reply()
        assertFalse(offersWorkflowSuggestion(suggested, running = false))
    }

    @Test fun anyHebrewLetterMakesItsLineRightToLeft() {
        assertEquals("‏Yoni Raich (את/ה)\nTop Secret", withRtlLines("Yoni Raich (את/ה)\nTop Secret"))
        assertEquals("plain", withRtlLines("plain"))
        assertEquals("‏שלום", withRtlLines("‏שלום"))
    }

    @Test fun theComposerMarksEachHebrewLineWhereItStarts() {
        assertEquals(listOf(0, 3), rtlLineStarts("הי\nמה נשמע?\nCan you make it do"))
        assertEquals(listOf(6), rtlLineStarts("hello\nYoni (את/ה)"))
        assertEquals(emptyList<Int>(), rtlLineStarts("plain\ntext"))
    }

    @Test fun aHebrewListKeepsItsLatinItemsOnTheSameSide() {
        val list = "פתחתי:\n\n1. דנה כהן\n2. Noa Levi (עבודה)\n3. Team Standup\n\nThen more"
        assertEquals(
            "פתחתי:\n\n1. דנה כהן\n2. Noa Levi (עבודה)\n3. ‏Team Standup\n\nThen more",
            keepListsTogether(list),
        )
    }

    @Test fun latinListsAndCodeBlocksAreLeftAlone() {
        assertEquals("- one\n- two", keepListsTogether("- one\n- two"))
        val code = "```\n- שלום\n- hello\n```"
        assertEquals(code, keepListsTogether(code))
    }

    @Test fun aComputersStepsFoldIntoOneRowOfTheirOwn() {
        val rows = chatRows(
            listOf(
                message("u1", "user"),
                message("r1", "remote_activity", "Command execution\n\nls"),
                message("r2", "remote_activity", "Thinking\n\n"),
                message("r3", "remote_activity", "Command execution\n\ncat a"),
                message("a1", "assistant"),
            ),
            running = false,
        )
        assertEquals(listOf("u1", "remote-r1", "a1"), rows.map { it.key })
        assertEquals(3, (rows[1] as RemoteActivityRow).steps.size)
        assertFalse((rows[1] as RemoteActivityRow).live)
    }

    @Test fun whatRanOnThePhoneAndOnTheComputerNeverMerge() {
        val rows = chatRows(
            listOf(
                message("t1", "tool", "read_ui: ok"),
                message("r1", "remote_activity", "Command execution\n\nls"),
                message("r2", "remote_activity", "Command execution\n\ncat a"),
                message("t2", "tool", "tap: ok"),
            ),
            running = true,
        )
        assertEquals(listOf("actions-t1", "remote-r1", "actions-t2"), rows.map { it.key })
        assertFalse((rows[0] as ActionsRow).live)
        assertFalse((rows[1] as RemoteActivityRow).live)
        assertTrue((rows[2] as ActionsRow).live)
    }

    @Test fun onlyATrailingComputerGroupIsLive() {
        val messages = listOf(message("r1", "remote_activity"), message("a1", "assistant"), message("r2", "remote_activity"))
        val rows = chatRows(messages, running = true)
        assertFalse((rows[0] as RemoteActivityRow).live)
        assertTrue((rows[2] as RemoteActivityRow).live)
    }

    @Test fun aHebrewWordStaysOnTheLineOfItsNumber() {
        val nbsp = " "
        assertEquals(
            "בפרק${nbsp}2 של ריצ'ר, עונה${nbsp}1: קובץ",
            bindNumbersToLabels("בפרק 2 של ריצ'ר, עונה 1: קובץ"),
        )
        // Only after an RTL letter: Latin and digits keep their ordinary spaces.
        assertEquals("season 1 and episode 2", bindNumbersToLabels("season 1 and episode 2"))
        assertEquals("שלום world 5", bindNumbersToLabels("שלום world 5"))
    }

    @Test fun codeKeepsItsSpacesWhenNumbersAreBound() {
        val nbsp = " "
        assertEquals("עונה${nbsp}1 `run 2` x 3", bindNumbersToLabels("עונה 1 `run 2` x 3"))
        // A word right before a code span, and the span itself.
        assertEquals("עונה `עונה 1`", bindNumbersToLabels("עונה `עונה 1`"))
        val fenced = "```\nעונה 1\n```\nעונה${nbsp}2"
        assertEquals(fenced, bindNumbersToLabels("```\nעונה 1\n```\nעונה 2"))
    }

    @Test fun theLastLineDecidesWhichSideATextEndsOn() {
        assertTrue(endsRtl("Done.\nהכול מוכן"))
        assertTrue(endsRtl("הכול מוכן\n\n"))
        assertFalse(endsRtl("הכול מוכן\nDone."))
        assertFalse(endsRtl(""))
    }

    @Test fun aLongPressOnAnyAgentBlockCopiesItsWholeTurn() {
        val messages = listOf(
            message("u1", "user", "ask"),
            message("a1", "assistant", "first block"),
            message("r1", "remote_activity", "Command execution\n\nls"),
            message("a2", "assistant", "final block"),
            message("u2", "user", "again"),
            message("a3", "assistant", "reply"),
        )
        val copies = turnCopyTexts(messages)
        assertEquals(setOf("a1", "a2", "a3"), copies.keys)
        assertEquals("first block\n\nfinal block", copies["a1"])
        assertEquals("first block\n\nfinal block", copies["a2"])
        assertEquals("reply", copies["a3"])
        val rows = chatRows(messages, running = false).filterIsInstance<MessageRow>()
        assertEquals(listOf("a1", "a2", "a3"), rows.filter { it.copyText != null }.map { it.message.id })
    }

    @Test fun aReplyStillStreamingCopiesWhatIsThereSoFar() {
        val streaming = ChatMessage("a1", "s", "assistant", "partial", 0L, "streaming")
        assertEquals("partial", turnCopyTexts(listOf(message("u", "user"), streaming))["a1"])
    }

    @Test fun memoryUpdatesDoNotClaimToControlThePhone() {
        val row = chatRows(listOf(message("m", "tool", "mike_memory: saved")), running = false).single() as ActionsRow
        assertEquals("1 Mike update", actionsLabel(row))
        assertEquals("Memory", toolStepLabel("mike_memory"))
        val mixed = chatRows(listOf(message("m", "tool", "mike_memory: saved"), message("t", "tool", "tap: ok")), running = false).single() as ActionsRow
        assertEquals("2 actions on your phone", actionsLabel(mixed))
    }
}
