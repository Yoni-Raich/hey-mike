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

    @Test
    fun `every action taken outlives the evidence window`() {
        val ledger = JevTaskLedger(listOf("Open Display", "Do not change any settings"))
        repeat(20) { step ->
            ledger.observe("screen-$step", buildJsonObject {
                put("nodes", buildJsonArray { add(buildJsonObject { put("text", "Screen $step") }) })
            }, "Tap item $step", "ok")
        }
        val state = ledger.state()
        assertEquals(12, state["evidence"]!!.jsonArray.size)
        val actions = state["actionsTaken"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals((0 until 20).map { "Tap item $it" }, actions)
        assertFalse("actionsTakenTruncated" in state)
    }

    @Test
    fun `each requirement is one yes or no judgment over described screens`() {
        val ledger = JevTaskLedger(listOf("Reach Display", "Display settings page is visible", "Do not change settings"))
        ledger.observe("chat", buildJsonObject {
            put("nodes", buildJsonArray { add(buildJsonObject { put("text", "Message") }) })
        }, null, null)
        ledger.observe("display", buildJsonObject {
            put("activePackage", "com.android.settings")
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("contentDescription", "Display") })
                add(buildJsonObject { put("contentDescription", "Dark theme"); put("checked", true) })
            })
        }, "Open Display settings directly", "ok")

        ledger.questions().forEach { assertEquals(setOf("PENDING", JevTaskLedger.SATISFIED), it.criteria.keys) }
        val screen = ledger.state()["evidence"]!!.jsonArray.last().jsonObject["screen"]!!.jsonPrimitive.content
        assertEquals("app com.android.settings after \"Open Display settings directly\": Display · Dark theme=on", screen)

        val done = ledger.apply(mapOf(0 to JevTaskLedger.SATISFIED, 1 to JevTaskLedger.SATISFIED, 2 to JevTaskLedger.SATISFIED))
        assertTrue(done)
        val statuses = ledger.state()["requirements"]!!.jsonArray.map { it.jsonObject }
        assertEquals("E2", statuses[1]["evidence"]!!.jsonPrimitive.content)

        assertFalse(ledger.apply(mapOf(0 to JevTaskLedger.SATISFIED, 1 to "PENDING", 2 to JevTaskLedger.SATISFIED)))
    }

    @Test
    fun `an unnamed checkbox carries the label of its row`() {
        val ledger = JevTaskLedger(listOf("Complete Task #3"))
        ledger.observe("tasks", buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("checkable", true); put("checked", true); put("bounds", bounds(40, 500, 160, 620)) })
                add(buildJsonObject { put("text", "Task #3: Validate System Telemetry"); put("bounds", bounds(200, 510, 900, 560)) })
            })
        }, null, null)
        val evidence = ledger.state()["evidence"]!!.jsonArray.single().jsonObject
        val box = evidence["facts"]!!.jsonObject["nodes"]!!.jsonArray.first().jsonObject
        assertEquals("Task #3: Validate System Telemetry", box["label"]!!.jsonPrimitive.content)
        assertTrue(evidence["screen"]!!.jsonPrimitive.content.contains("Task #3: Validate System Telemetry=on"))
    }

    @Test
    fun `an action decision gets a bounded ledger view without raw evidence`() {
        val ledger = JevTaskLedger(listOf("Read every page"))
        repeat(20) { step ->
            ledger.observe("screen-$step", buildJsonObject {
                put("nodes", buildJsonArray { repeat(40) { add(buildJsonObject { put("text", "Row $step-$it " + "x".repeat(40)) }) } })
            }, "Scroll $step", "ok")
        }
        val view = ledger.decisionState()
        assertEquals(4, view["recentScreens"]!!.jsonArray.size)
        assertFalse("facts" in view.toString())
        assertTrue(view.toString().length < ledger.state().toString().length / 5)
    }

    private fun bounds(vararg values: Int) = buildJsonArray { values.forEach { add(it) } }
}
