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
        assertTrue(nodes.any { it.jsonObject["text"]?.jsonPrimitive?.content == "Display" })
        assertTrue(nodes.any { it.jsonObject["text"]?.jsonPrimitive?.content == "Dark theme" })
        val toggle = nodes.single { "checked" in it.jsonObject }.jsonObject
        assertTrue(toggle["checked"]!!.jsonPrimitive.boolean)
        assertEquals(bounds(700, 300, 1000, 400), toggle["bounds"])
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
        }, "Open Display settings directly", "ok", "OPEN_INTENT")

        ledger.questions().forEach { assertEquals(setOf("PENDING", JevTaskLedger.SATISFIED), it.criteria.keys) }
        val screen = ledger.state()["evidence"]!!.jsonArray.last().jsonObject["screen"]!!.jsonPrimitive.content
        assertTrue(screen.startsWith("app com.android.settings after \"Open Display settings directly\":"))
        assertTrue(screen.contains("Display"))
        assertTrue(screen.contains("Dark theme=on"))

        val scopes = mapOf(0 to "HISTORY", 1 to "CURRENT", 2 to "INVARIANT")
        val witnesses = mapOf(0 to "E2", 1 to "E2", 2 to "ACTIONS")
        val done = ledger.apply(mapOf(0 to JevTaskLedger.SATISFIED, 1 to JevTaskLedger.SATISFIED, 2 to JevTaskLedger.SATISFIED), scopes, witnesses)
        assertTrue(done)
        val statuses = ledger.state()["requirements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("E2"), statuses[1]["evidenceIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(statuses[2]["actionHistory"]!!.jsonPrimitive.boolean)
        assertTrue(statuses[2]["evidenceIds"]!!.jsonArray.isEmpty())

        assertFalse(ledger.apply(mapOf(0 to JevTaskLedger.SATISFIED, 1 to "PENDING", 2 to JevTaskLedger.SATISFIED), scopes, witnesses))
    }

    @Test
    fun `prohibition uses action history and refuses a truncated trace`() {
        val ledger = JevTaskLedger(listOf("Do not change settings", "Open Display without changing settings"))
        ledger.observe("display", buildJsonObject {
            put("activePackage", "com.android.settings")
            put("nodes", buildJsonArray { add(buildJsonObject { put("text", "Display") }) })
        }, "Open Display settings directly", "ok", "OPEN_INTENT")
        val choices = mapOf(0 to JevTaskLedger.SATISFIED, 1 to JevTaskLedger.SATISFIED)
        val scopes = mapOf(0 to "INVARIANT", 1 to "COMPOSITE")
        val questions = ledger.witnessQuestions(choices, scopes)
        assertTrue("ACTIONS" in questions[0].criteria)
        assertTrue("SCREEN_AND_ACTIONS" in questions[1].criteria)
        assertTrue("MULTIPLE_AND_ACTIONS" in questions[1].criteria)
        assertEquals(JevTaskLedger.SATISFIED, ledger.actionInvariantVerdict(0, "INVARIANT", "ACTIONS"))
        assertFalse(ledger.proofQuestions(scopes, mapOf(0 to "ACTIONS")).any { it.name == "proof_0" })
        assertTrue(ledger.apply(choices, scopes, mapOf(0 to "ACTIONS", 1 to "SCREEN_AND_ACTIONS")))
        val proof = ledger.state()["requirements"]!!.jsonArray[1].jsonObject
        assertEquals(listOf("E1"), proof["evidenceIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(proof["actionHistory"]!!.jsonPrimitive.boolean)

        val risky = JevTaskLedger(listOf("No setting is toggled or modified"))
        risky.observe("display", buildJsonObject { put("nodes", buildJsonArray { }) }, "Tap Dark theme", "ok", "TAP")
        assertEquals("PENDING", risky.actionInvariantVerdict(0, "INVARIANT", "ACTIONS"))
        assertFalse(risky.apply(mapOf(0 to JevTaskLedger.SATISFIED), mapOf(0 to "INVARIANT"), mapOf(0 to "ACTIONS")))

        repeat(81) { index -> ledger.observe("screen-$index", buildJsonObject { put("nodes", buildJsonArray { }) }, "Scroll $index", "ok") }
        assertTrue(ledger.state()["actionsTakenTruncated"]!!.jsonPrimitive.boolean)
        assertFalse("ACTIONS" in ledger.witnessQuestions(choices, scopes)[0].criteria)
        assertFalse(ledger.apply(choices, scopes, mapOf(0 to "ACTIONS", 1 to "SCREEN_AND_ACTIONS")))
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

    @Test
    fun `terminal ledger gives statuses but not the full audit evidence`() {
        val ledger = JevTaskLedger(listOf("Complete Task #3", "Long requirement " + "x".repeat(1900)))
        repeat(20) { step -> ledger.observe("screen-$step", buildJsonObject {
            put("nodes", buildJsonArray { repeat(30) { add(buildJsonObject { put("text", "Row $it " + "y".repeat(150)) }) } })
        }, "Tap row $step", "ok") }

        val result = ledger.resultState()
        assertEquals(2, result["requirements"]!!.jsonArray.size)
        assertEquals("pending", result["requirements"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(result["requirements"]!!.jsonArray[1].jsonObject["requirementTruncated"]!!.jsonPrimitive.boolean)
        assertEquals(20, result["actionCount"]!!.jsonPrimitive.int)
        assertFalse("evidence" in result)
        assertFalse("actionsTaken" in result)
        assertTrue(result.toString().length < ledger.state().toString().length / 5)
    }

    private fun bounds(vararg values: Int) = buildJsonArray { values.forEach { add(it) } }
}
