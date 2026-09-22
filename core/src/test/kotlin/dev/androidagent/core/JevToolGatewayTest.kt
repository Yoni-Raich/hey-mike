package dev.androidagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JevToolGatewayTest {
    private val state = MutableStateFlow(JevProviderState(enabled = true, tokenConfigured = true))

    @Test
    fun `one tool call runs multiple Jev decisions and executes through device gateway`() = runBlocking {
        val router = FakeRouter(
            mutableListOf(
                screen("ui-1", "Network & internet", clickable = true),
                screen("ui-2", "Network & internet", clickable = true),
                screen("ui-3", "Wi-Fi", clickable = false),
                screen("ui-4", "Wi-Fi", clickable = false),
            ),
        )
        val provider = ScriptedProvider(state, mutableListOf("TAP" to "T1", "DONE" to null))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Open Wi-Fi"))

        val json = Json.parseToJsonElement(result.text).jsonObject
        assertTrue(result.success)
        assertEquals("done_visible", json["status"]?.jsonPrimitive?.content)
        assertEquals(false, json["verified"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(2, provider.calls)
        assertEquals(listOf("tap_node"), router.actions.map { it.first })
        assertTrue(gateway.needsControl("jev_run_ui_task"))
    }

    @Test
    fun `selected progress head executes semantic set progress`() = runBlocking {
        val router = FakeRouter(
            mutableListOf(
                slider("ui-1", 20.0),
                slider("ui-2", 20.0),
                slider("ui-3", 75.0),
                slider("ui-4", 75.0),
            ),
        )
        val provider = ScriptedProvider(state, mutableListOf("SET_PROGRESS" to "P1", "DONE" to null))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Set volume to 75%"))

        assertTrue(result.success)
        assertEquals("set_progress", router.actions.single().first)
        assertEquals(75.0, router.actions.single().second["value"]?.jsonPrimitive?.doubleOrNull ?: -1.0, 0.001)
    }

    @Test
    fun `malformed unused speculative head cannot block done`() = runBlocking {
        val router = FakeRouter(mutableListOf(screen("ui-1", "Done", false), screen("ui-2", "Done", false)))
        val provider = object : JevDecisionProvider {
            override val state = this@JevToolGatewayTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                val operation = choice(request.questions.first { it.name == "operation" }, "DONE")
                return JevDecisionResponse(
                    answers = mapOf(
                        "operation" to operation,
                        "tap_target" to JevDecision("choice", "invented", 1.0, mapOf("invented" to 1.0)),
                    ),
                    model = "jev-test",
                )
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Already done"))

        assertTrue(result.success)
        assertTrue(router.actions.isEmpty())
    }

    @Test
    fun `failed mutation is reported once and never retried`() = runBlocking {
        val router = FakeRouter(
            mutableListOf(
                screen("ui-1", "Submit", true),
                screen("ui-2", "Submit", true),
            ),
            actionResult = ToolResult("transport result unknown", success = false),
        )
        val provider = ScriptedProvider(state, mutableListOf("TAP" to "T1"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Tap Submit"))

        assertFalse(result.success)
        assertEquals("uncertain_mutation", Json.parseToJsonElement(result.text).jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals(1, router.actions.size)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `disabled provider refuses before reading device`() = runBlocking {
        state.value = JevProviderState(enabled = false, tokenConfigured = true)
        val router = FakeRouter(mutableListOf())
        val provider = ScriptedProvider(state, mutableListOf())
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val error = runCatching { gateway.invoke("jev_run_ui_task", requestJson("Open Settings")) }.exceptionOrNull()

        assertTrue(error is ToolNotServiceable)
        assertEquals(0, provider.calls)
        assertEquals(0, router.reads)
    }

    private fun requestJson(goal: String) = buildJsonObject { put("goal", goal) }

    private fun screen(id: String, text: String, clickable: Boolean): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("observationId", id)
            put("screenDigest", "screen:$text:$clickable")
            put("activePackage", "com.example")
            put("truncated", false)
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("nodeId", "n1")
                    put("text", text)
                    put("enabled", true)
                    put("clickable", clickable)
                    put("scrollable", false)
                    put("focused", false)
                })
            })
        }.toString(),
    )

    private fun slider(id: String, current: Double): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("observationId", id)
            put("screenDigest", "slider:$current")
            put("activePackage", "com.example")
            put("truncated", false)
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("nodeId", "n7")
                    put("text", "Volume Slider: ${current.toInt()}%")
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
        var calls = 0
        override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
            calls++
            val (operation, target) = script.removeAt(0)
            val answers = mutableMapOf<String, JevDecision>()
            answers["operation"] = choice(request.questions.first { it.name == "operation" }, operation)
            val head = when {
                operation == "TAP" -> "tap_target"
                operation == "SET_PROGRESS" -> "progress_target"
                operation.startsWith("SCROLL_") -> "scroll_target"
                operation == "TYPE_TEXT" -> "text_value"
                operation == "OPEN_APP" -> "app_target"
                else -> null
            }
            if (head != null) answers[head] = choice(request.questions.first { it.name == head }, checkNotNull(target))
            return JevDecisionResponse(answers, "jev-test")
        }
    }

    private class FakeRouter(
        private val screens: MutableList<ToolResult>,
        private val actionResult: ToolResult = ToolResult("ok"),
    ) : DeviceToolGateway {
        var reads = 0
        val actions = mutableListOf<Pair<String, JsonObject>>()
        override val definitions = emptyList<ToolDefinition>()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult = when (name) {
            "apps_settings" -> ToolResult("{\"ok\":true,\"items\":[]}")
            "read_ui" -> screens.removeAt(0).also { reads++ }
            else -> actionResult.also { actions += name to arguments }
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
