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
}
