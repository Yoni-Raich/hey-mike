package dev.androidagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolRefusalTest {
    @Test fun aRefusalKeepsItsKeyOrderAndEscaping() {
        val result = toolRefusal("denied", "No \"x\" here")
        assertFalse(result.success)
        assertEquals("""{"ok":false,"errorType":"denied","message":"No \"x\" here"}""", result.text)
    }

    @Test fun theDefaultApprovalRefusalsKeepTheirFormerLiterals() {
        // A11yDeviceTools used to spell these out by hand.
        assertEquals(
            "{\"ok\":false,\"errorType\":\"approval_unavailable\",\"message\":\"This intent needs approval in the app.\"}",
            toolRefusal("approval_unavailable", "This intent needs approval in the app.").text,
        )
        assertEquals(
            "{\"ok\":false,\"errorType\":\"approval_unavailable\",\"message\":\"Sending needs approval in the app. Nothing was sent.\"}",
            toolRefusal("approval_unavailable", "Sending needs approval in the app. Nothing was sent.").text,
        )
    }
}
