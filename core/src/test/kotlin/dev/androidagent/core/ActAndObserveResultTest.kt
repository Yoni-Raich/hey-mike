package dev.androidagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActAndObserveResultTest {
    @Test fun aJsonObservationIsEmbeddedAsJson() {
        val result = actAndObserveResult(ToolResult("tapped"), ToolResult("""{"nodes":[1]}"""))
        assertTrue(result.success)
        assertEquals(
            """{"actionCompleted":true,"actionResult":"tapped","observationSucceeded":true,"observation":{"nodes":[1]}}""",
            result.text,
        )
    }

    @Test fun aTextObservationIsEmbeddedAsAStringAndKeepsItsFailure() {
        val result = actAndObserveResult(ToolResult("ok"), ToolResult("not json {", success = false))
        assertFalse(result.success)
        assertEquals(
            """{"actionCompleted":true,"actionResult":"ok","observationSucceeded":false,"observation":"not json {"}""",
            result.text,
        )
    }

    @Test fun aThrownObservationTellsTheModelNotToRepeat() {
        val result = actAndObserveObservationFailure(IllegalStateException("gone"))
        assertFalse(result.success)
        assertEquals("Observation failed: gone. Do not repeat the completed action.", result.text)
    }
}
