package dev.androidagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JevResultProjectionTest {
    @Test
    fun `terminal screen is compact and cannot be used as a stale action address`() {
        val screen = buildJsonObject {
            put("activePackage", "com.example.androidgym")
            put("screenDigest", "digest-1")
            put("nodes", buildJsonArray {
                repeat(250) { index -> add(buildJsonObject {
                    put("nodeId", "n$index")
                    put("package", "com.example.androidgym")
                    put("text", "Row $index " + "x".repeat(200))
                    put("class", "android.widget.TextView")
                }) }
                add(buildJsonObject {
                    put("nodeId", "n250")
                    put("package", "com.example.androidgym")
                    put("text", "private exact note")
                    put("hintText", "Agent Notes")
                    put("editable", true)
                    put("class", "android.widget.EditText")
                })
            })
        }
        val projected = JevResultProjection.observation(JevObservation("ui-1", screen, "digest-1"))

        assertTrue(projected["summaryOnly"]!!.jsonPrimitive.boolean)
        assertTrue(projected["partial"]!!.jsonPrimitive.boolean)
        assertEquals(251, projected["totalNodes"]!!.jsonPrimitive.int)
        assertEquals(16, projected["nodes"]!!.jsonArray.size)
        assertTrue(projected.toString().length < 4000)
        assertFalse(projected.toString().contains("nodeId"))
        assertFalse(projected.toString().contains("private exact note"))
        assertTrue(projected.toString().contains("Agent Notes"))
    }

    @Test
    fun `terminal history keeps the last actions and their total count stays separate`() {
        val entries = (0 until 40).map { step -> JevHistoryEntry("TAP", "Tap $step " + "x".repeat(500),
            outcome = "ok " + "y".repeat(500)) }
        val result = JevResultProjection.history(entries)

        assertEquals(12, result.size)
        assertTrue(result.first().jsonObject["label"]!!.jsonPrimitive.content.startsWith("Tap 28"))
        assertTrue(result.toString().length < 5000)
    }
}
