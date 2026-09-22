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
 * Regressions for two action-space faults seen driving a real app: a tap aimed
 * at a label its platform node refuses, and a progress value the control
 * already holds. Both ended the whole run before they were fixed.
 */
class JevActionSpaceRegressionTest {
    private val state = MutableStateFlow(JevProviderState(enabled = true, tokenConfigured = true))

    @Test
    fun `labelled child taps its clickable ancestor, not itself`() = runBlocking {
        val router = FakeRouter(MutableList(4) { labelInsideClickableRow() })
        val provider = ScriptedProvider(state, mutableListOf("TAP" to "T1", "DONE" to null))
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

        val offered = provider.questions
            .filter { it.name == "progress_target" }
            .flatMap { it.criteria.values }
        assertTrue(
            "no-op progress action was offered: $offered",
            offered.none { it.contains("from 5.0 to 5.0") },
        )
    }

    /** A label whose only clickable target is an ancestor absent from the node list. */
    private fun labelInsideClickableRow(): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("observationId", "ui-1")
            put("screenDigest", "row-screen")
            put("activePackage", "com.example.androidgym")
            put("truncated", false)
            put("nodes", buildJsonArray {
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
            })
        }.toString(),
    )

    private fun sliderAt(current: Double): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("observationId", "ui-1")
            put("screenDigest", "slider-screen")
            put("activePackage", "com.example.androidgym")
            put("truncated", false)
            put("nodes", buildJsonArray {
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
            })
        }.toString(),
    )

    private class ScriptedProvider(
        override val state: MutableStateFlow<JevProviderState>,
        private val script: MutableList<Pair<String, String?>>,
    ) : JevDecisionProvider {
        override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
            val (operation, target) = script.removeAt(0)
            val answers = mutableMapOf<String, JevDecision>()
            answers["operation"] = choice(request.questions.first { it.name == "operation" }, operation)
            val head = when (operation) {
                "TAP" -> "tap_target"
                "SET_PROGRESS" -> "progress_target"
                else -> null
            }
            if (head != null) answers[head] = choice(request.questions.first { it.name == head }, checkNotNull(target))
            return JevDecisionResponse(answers, "jev-test")
        }
    }

    private class QuestionCapturingProvider(
        override val state: MutableStateFlow<JevProviderState>,
    ) : JevDecisionProvider {
        val questions = mutableListOf<JevChoiceQuestion>()
        override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
            questions += request.questions
            return JevDecisionResponse(
                mapOf("operation" to choice(request.questions.first { it.name == "operation" }, "DONE")),
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
