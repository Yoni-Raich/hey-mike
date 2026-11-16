package dev.androidagent.app.ui

import dev.androidagent.core.AgentPanels
import dev.androidagent.core.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun otherMessagesAreNoReceipt() {
        assertNull(expiredPanelNote(message("assistant", "complete")))
        assertNull(expiredPanelNote(message("assistant", "streaming")))
        assertNull(expiredPanelNote(message("user", AgentPanels.EXPIRED)))
    }
}
