package dev.androidagent.app.ui

import dev.androidagent.core.AgentPanels
import dev.androidagent.core.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the chat draws for a panel that closed with nobody answering it. */
class ExpiredPanelNoteTest {
    private fun message(role: String, state: String) = ChatMessage("m", "chat", role, "Sign in to the shop", 1L, state)

    @Test fun anExpiredPanelReadsAsNotSent() {
        assertEquals("Expired, not sent.", expiredPanelNote(message("assistant", AgentPanels.EXPIRED)))
    }

    @Test fun anExpiredPanelWithTapsSaysTheyStayDone() {
        assertEquals(
            "Expired, not sent. Taps already made stay done.",
            expiredPanelNote(message("assistant", AgentPanels.EXPIRED_AFTER_TAPS)),
        )
    }

    @Test fun aHebrewTitleMirrorsTheReceipt() {
        assertTrue(expiredReceiptRtl("\u05e8\u05e9\u05d9\u05de\u05d4 \u05e7\u05e6\u05e8\u05d4"))
        assertTrue(expiredReceiptRtl("Pushups \u05e1\u05e4\u05d9\u05e8\u05d4"))
        assertFalse(expiredReceiptRtl("Sign in to the shop"))
    }

    @Test fun otherMessagesAreNoReceipt() {
        assertNull(expiredPanelNote(message("assistant", "complete")))
        assertNull(expiredPanelNote(message("assistant", "streaming")))
        assertNull(expiredPanelNote(message("user", AgentPanels.EXPIRED)))
    }
}
