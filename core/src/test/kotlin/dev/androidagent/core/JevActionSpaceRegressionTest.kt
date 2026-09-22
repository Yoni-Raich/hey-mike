package dev.androidagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regressions for the action space Jev is asked to choose from: a tap aimed at
 * a label its platform node refuses, a progress value the control already
 * holds, and the shape of the question itself.
 */
class JevActionSpaceRegressionTest {
    private val state = MutableStateFlow(JevProviderState(enabled = true, tokenConfigured = true))

    @Test
    fun `labelled child taps its clickable ancestor, not itself`() = runBlocking {
        val router = FakeRouter(MutableList(4) { labelInsideClickableRow() })
        val provider = ScriptedProvider(state, mutableListOf("T1", "DONE"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open the Tasks sub-screen") })

        val tapped = router.actions.single().second["nodeId"]?.jsonPrimitive?.content
        // n5 is a plain TextView: performAction(ACTION_CLICK) returns false on
        // device, tapNode falls back to a coordinate tap, and the coordinate
        // lands under Hey Mike's own overlay. n3 is the row that accepts a click.
        assertEquals("n3", tapped)
    }

    @Test
    fun `progress targets never include a no-op value`() = runBlocking {
        val router = FakeRouter(MutableList(2) { sliderAt(5.0) })
        val provider = QuestionCapturingProvider(state)
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Set the volume slider to 5") })

        val offered = provider.questions.filter { it.name == "action" }.flatMap { it.criteria.values }
        assertTrue(
            "no-op progress action was offered: $offered",
            offered.none { it.contains("from 5.0 to 5.0") },
        )
    }

    @Test
    fun `every concrete action is one choice in a single question`() = runBlocking {
        val router = FakeRouter(MutableList(2) { listWithScrollableRow() })
        val provider = QuestionCapturingProvider(state)
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open Wi-Fi") })

        // One question over comparable offers, not "TAP vs SCROLL_DOWN" decided
        // before anyone knows which tap or which region.
        val question = provider.questions.single { it.name == "action" }
        assertEquals(listOf("action"), provider.questions.map { it.name })
        val labels = question.criteria.values
        assertTrue("no tap offered: $labels", labels.any { it == "Tap Wi-Fi" })
        assertTrue("no scroll offered: $labels", labels.any { it.startsWith("Scroll down in") })
        assertTrue("controls missing: ${question.criteria.keys}", question.criteria.keys.containsAll(
            listOf("BACK", "HOME", "WAIT", "DONE", "BLOCKED"),
        ))
    }

    @Test
    fun `a screen past the choice budget keeps its named controls`() = runBlocking {
        val router = FakeRouter(MutableList(2) { crowdedScreen(named = 150, anonymous = 150) })
        val provider = QuestionCapturingProvider(state)
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open settings") })

        val question = provider.questions.single { it.name == "action" }
        // One flat question means one 255-choice ceiling for everything: 250
        // taps out of the 300 the screen affords, plus the five controls.
        assertEquals(255, question.criteria.size)
        assertTrue(question.criteria.keys.contains("DONE"))
        // An anonymous container is what gets dropped, never a named control.
        assertEquals(150, question.criteria.values.count { it.startsWith("Tap named-") })
    }

    /** A label whose only clickable target is an ancestor absent from the node list. */
    private fun labelInsideClickableRow(): ToolResult = screenOf("row-screen") {
        add(buildJsonObject {
            put("nodeId", "n5")
            put("text", "Tasks Sub-Screen")
            put("class", "android.widget.TextView")
            put("enabled", true)
            put("clickable", false)
            put("scrollable", false)
            put("focused", false)
            put("clickableAncestor", buildJsonObject {
                put("nodeId", "n3")
                put("bounds", buildJsonArray { listOf(660, 150, 960, 226).forEach { add(it) } })
                put("class", "android.widget.LinearLayout")
            })
        })
    }

    private fun listWithScrollableRow(): ToolResult = screenOf("list-screen") {
        add(buildJsonObject {
            put("nodeId", "n1")
            put("text", "Settings list")
            put("class", "androidx.recyclerview.widget.RecyclerView")
            put("bounds", buildJsonArray { listOf(0, 200, 1080, 2000).forEach { add(it) } })
            put("enabled", true)
            put("clickable", false)
            put("scrollable", true)
            put("focused", false)
        })
        add(buildJsonObject {
            put("nodeId", "n2")
            put("text", "Wi-Fi")
            put("enabled", true)
            put("clickable", true)
            put("scrollable", false)
            put("focused", false)
        })
    }

    private fun crowdedScreen(named: Int, anonymous: Int): ToolResult = screenOf("crowded-screen") {
        repeat(named) { index ->
            add(buildJsonObject {
                put("nodeId", "named$index")
                put("text", "named-$index")
                put("enabled", true)
                put("clickable", true)
                put("scrollable", false)
                put("focused", false)
            })
        }
        repeat(anonymous) { index ->
            add(buildJsonObject {
                put("nodeId", "blank$index")
                put("class", "android.widget.FrameLayout")
                put("enabled", true)
                put("clickable", true)
                put("scrollable", false)
                put("focused", false)
            })
        }
    }

    private fun sliderAt(current: Double): ToolResult = screenOf("slider-screen") {
        add(buildJsonObject {
            put("nodeId", "n107")
            put("text", "Volume Slider")
            put("enabled", true)
            put("clickable", false)
            put("scrollable", false)
            put("focused", false)
            put("range", buildJsonObject {
                put("min", 0.0)
                put("max", 100.0)
                put("current", current)
                put("type", "int")
            })
            put("actions", buildJsonArray { add("SET_PROGRESS") })
        })
    }

    private fun screenOf(digest: String, nodes: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("observationId", "ui-1")
            put("screenDigest", digest)
            put("activePackage", "com.example.androidgym")
            put("truncated", false)
            put("nodes", buildJsonArray(nodes))
        }.toString(),
    )

    /** Answers the one flat action question with a scripted choice per step. */
    private class ScriptedProvider(
        override val state: MutableStateFlow<JevProviderState>,
        private val script: MutableList<String>,
    ) : JevDecisionProvider {
        override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse =
            JevDecisionResponse(
                mapOf("action" to choice(request.questions.first { it.name == "action" }, script.removeAt(0))),
                "jev-test",
            )
    }

    private class QuestionCapturingProvider(
        override val state: MutableStateFlow<JevProviderState>,
    ) : JevDecisionProvider {
        val questions = mutableListOf<JevChoiceQuestion>()
        override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
            questions += request.questions
            return JevDecisionResponse(
                mapOf("action" to choice(request.questions.first { it.name == "action" }, "DONE")),
                "jev-test",
            )
        }
    }

    private class FakeRouter(private val screens: MutableList<ToolResult>) : DeviceToolGateway {
        val actions = mutableListOf<Pair<String, JsonObject>>()
        override val definitions = emptyList<ToolDefinition>()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult = when (name) {
            "apps_settings" -> ToolResult("{\"ok\":true,\"items\":[]}")
            "read_ui" -> screens.removeAt(0)
            else -> ToolResult("ok").also { actions += name to arguments }
        }
    }

    companion object {
        private fun choice(question: JevChoiceQuestion, selected: String): JevDecision = JevDecision(
            type = "choice",
            choice = selected,
            confidence = 1.0,
            probabilities = question.criteria.keys.associateWith { if (it == selected) 1.0 else 0.0 },
        )
    }
}
