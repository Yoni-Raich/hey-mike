package dev.androidagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolRefusalTest {
    @Test fun theTextKeepsItsKeyOrderAndEscaping() {
        assertEquals(
            """{"ok":false,"errorType":"denied","message":"No \"x\" here"}""",
            toolErrorJson("denied", "No \"x\" here"),
        )
    }

    @Test fun aRefusalIsAnUnsuccessfulResultWithThatText() {
        val result = toolRefusal("bad_mode", "Choose a mode.")
        assertFalse(result.success)
        assertEquals(toolErrorJson("bad_mode", "Choose a mode."), result.text)
        assertEquals(null, result.imageBase64)
        assertEquals(emptyList<String>(), result.attachmentPaths)
    }
}
