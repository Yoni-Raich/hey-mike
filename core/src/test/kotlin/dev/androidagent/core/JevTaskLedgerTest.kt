package dev.androidagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JevTaskLedgerTest {
    @Test
    fun `status bar wrappers cannot displace screen labels and switch values`() {
        val ledger = JevTaskLedger(listOf("Open Display"))
        val observation = buildJsonObject {
            put("activePackage", "com.android.settings")
            put("nodes", buildJsonArray {
                repeat(40) { add(buildJsonObject {
                    put("resourceId", "com.android.systemui:id/wrapper_$it")
                    put("windowType", "system")
                }) }
                add(buildJsonObject { put("text", "Display"); put("bounds", bounds(0, 100, 300, 200)) })
                add(buildJsonObject { put("text", "Dark theme"); put("bounds", bounds(0, 300, 600, 400)) })
                add(buildJsonObject { put("checked", true); put("bounds", bounds(700, 300, 1000, 400)) })
            })
        }
        ledger.observe("screen", observation, null, null)
        val facts = ledger.state()["evidence"]!!.jsonArray.single().jsonObject["facts"]!!.jsonObject
        val nodes = facts["nodes"]!!.jsonArray
        assertEquals(3, nodes.size)
        assertEquals("Display", nodes[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("Dark theme", nodes[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(nodes[2].jsonObject["checked"]!!.jsonPrimitive.boolean)
        assertEquals(bounds(700, 300, 1000, 400), nodes[2].jsonObject["bounds"])
        assertFalse(facts["partial"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `omitted meaningful evidence remains marked partial`() {
        val ledger = JevTaskLedger(listOf("Read the page"))
        ledger.observe("screen", buildJsonObject {
            put("nodes", buildJsonArray { repeat(100) { add(buildJsonObject {
                put("text", "A long meaningful label $it " + "x".repeat(100))
            }) } })
        }, null, null)
        val facts = ledger.state()["evidence"]!!.jsonArray.single().jsonObject["facts"]!!.jsonObject
        assertTrue(facts["partial"]!!.jsonPrimitive.boolean)
    }

    private fun bounds(vararg values: Int) = buildJsonArray { values.forEach { add(it) } }
}
