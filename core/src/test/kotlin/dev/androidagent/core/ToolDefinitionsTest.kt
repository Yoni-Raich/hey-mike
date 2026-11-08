package dev.androidagent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDefinitionsTest {
    @Test fun scalarsObjectsAndSpelledOutSchemasKeepTheirExactShape() {
        val items = buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) }
        val tool = toolDefinition(
            "open_intent",
            "Open it.",
            mapOf("action" to "string", "extras" to "object", "steps" to "array"),
            listOf("action"),
            structured = mapOf("steps" to items),
        )

        assertEquals("open_intent", tool.name)
        assertEquals("Open it.", tool.description)
        assertEquals(
            """{"type":"object","properties":{"action":{"type":"string"},""" +
                """"extras":{"type":"object","additionalProperties":true},""" +
                """"steps":{"type":"array","items":{"type":"string"}}},""" +
                """"description":"Open it.","required":["action"]}""",
            tool.inputSchema.toString(),
        )
    }

    @Test fun aToolWithNoArgumentsStillHasAnObjectSchema() {
        assertEquals(
            """{"type":"object","properties":{},"description":"Read.","required":[]}""",
            toolDefinition("screenshot", "Read.", emptyMap(), emptyList()).inputSchema.toString(),
        )
    }
}
