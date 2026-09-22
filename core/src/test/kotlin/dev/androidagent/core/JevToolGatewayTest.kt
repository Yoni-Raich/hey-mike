package dev.androidagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val provider = ScriptedProvider(state, mutableListOf("T1", "DONE"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Open Wi-Fi"))

        val json = Json.parseToJsonElement(result.text).jsonObject
        assertTrue(result.success)
        assertEquals("done_visible", json["status"]?.jsonPrimitive?.content)
        assertEquals(false, json["verified"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(3, provider.calls) // two navigation decisions plus completion audit
        assertEquals(listOf("tap_node"), router.actions.map { it.first })
        assertTrue(gateway.needsControl("jev_run_ui_task"))
    }

    @Test
    fun `a progress choice executes a semantic set progress`() = runBlocking {
        val router = FakeRouter(
            mutableListOf(
                slider("ui-1", 20.0),
                slider("ui-2", 20.0),
                slider("ui-3", 75.0),
                slider("ui-4", 75.0),
            ),
        )
        val provider = ScriptedProvider(state, mutableListOf("P1", "DONE"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Set volume to 75%"))

        assertTrue(result.success)
        assertEquals("set_progress", router.actions.single().first)
        assertEquals(75.0, router.actions.single().second["value"]?.jsonPrimitive?.doubleOrNull ?: -1.0, 0.001)
    }

    @Test
    fun `an answer to a question that was not asked cannot block done`() = runBlocking {
        val router = FakeRouter(mutableListOf(screen("ui-1", "Done", false), screen("ui-2", "Done", false)))
        val provider = object : JevDecisionProvider {
            override val state = this@JevToolGatewayTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                if (request.questions.none { it.name == "action" }) return auditAnswer(request)
                val action = choice(request.questions.first { it.name == "action" }, "DONE")
                return JevDecisionResponse(
                    answers = mapOf(
                        "action" to action,
                        "text_value" to JevDecision("choice", "invented", 1.0, mapOf("invented" to 1.0)),
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
    fun `refusal on an unchanged screen is recorded and the run continues`() = runBlocking {
        val router = FakeRouter(
            MutableList(4) { screen("ui-1", "Submit", true) },
            actionResult = ToolResult("Node n1 rejected the text; nothing was typed.", success = false, dispatch = ToolDispatch.NOT_DISPATCHED),
        )
        val provider = ScriptedProvider(state, mutableListOf("T1", "DONE"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Tap Submit"))

        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("done_visible", json["status"]?.jsonPrimitive?.content)
        // The refusal did not end the run, and Jev was asked again.
        assertEquals(3, provider.calls)
        assertEquals(1, router.actions.size)
        val refused = json["history"]?.jsonArray.orEmpty().single().jsonObject
        assertEquals(true, refused["refused"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `refusal that changed the screen stays uncertain and is never retried`() = runBlocking {
        val router = FakeRouter(
            mutableListOf(
                screen("ui-1", "Submit", true),
                screen("ui-2", "Submit", true),
                screen("ui-3", "Submitted", true),
            ),
            actionResult = ToolResult("transport result unknown", success = false),
        )
        val provider = ScriptedProvider(state, mutableListOf("T1"))
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

    @Test
    fun `a step limit hands back a token that continues the same goal`() = runBlocking {
        val router = FakeRouter(MutableList(8) { screen("ui-1", "Submit", true) })
        val provider = ScriptedProvider(state, mutableListOf("T1", "BACK", "DONE"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val first = Json.parseToJsonElement(
            gateway.invoke(
                "jev_run_ui_task",
                buildJsonObject { put("goal", "Submit the form twice"); put("maxSteps", 1) },
            ).text,
        ).jsonObject

        assertEquals("step_limit", first["status"]?.jsonPrimitive?.content)
        assertEquals(1, first["steps"]?.jsonPrimitive?.content?.toInt())
        val token = first["continuation"]?.jsonObject?.get("token")?.jsonPrimitive?.content
        assertNotNull("a budget limit must be resumable", token)

        // The token carries the goal and the history, so the second call does
        // not restart blind: it still reports the step the first call executed.
        val second = Json.parseToJsonElement(
            gateway.invoke("jev_run_ui_task", buildJsonObject { put("resume", token!!) }).text,
        ).jsonObject

        assertEquals("done_visible", second["status"]?.jsonPrimitive?.content)
        assertEquals(1, second["steps"]?.jsonPrimitive?.content?.toInt())
        assertEquals(2, second["segment"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `a resume token cannot be replayed or invented`() = runBlocking {
        val router = FakeRouter(MutableList(8) { screen("ui-1", "Submit", true) })
        val provider = ScriptedProvider(state, mutableListOf("T1", "BACK", "DONE"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val first = Json.parseToJsonElement(
            gateway.invoke(
                "jev_run_ui_task",
                buildJsonObject { put("goal", "Submit the form twice"); put("maxSteps", 1) },
            ).text,
        ).jsonObject
        val token = checkNotNull(first["continuation"]?.jsonObject?.get("token")?.jsonPrimitive?.content)
        gateway.invoke("jev_run_ui_task", buildJsonObject { put("resume", token) })

        // Replaying a spent token would silently repeat work the run already did.
        val replay = runCatching {
            gateway.invoke("jev_run_ui_task", buildJsonObject { put("resume", token) })
        }.exceptionOrNull()
        val invented = runCatching {
            gateway.invoke("jev_run_ui_task", buildJsonObject { put("resume", "jev-resume-999") })
        }.exceptionOrNull()

        assertTrue("replayed token was accepted", replay is IllegalArgumentException)
        assertTrue("invented token was accepted", invented is IllegalArgumentException)
    }

    @Test
    fun `wait is withdrawn on a screen that two waits did not change`() = runBlocking {
        val router = FakeRouter(mutableListOf(screen("ui-1", "Submit", true)))
        val offered = mutableListOf<Boolean>()
        val script = mutableListOf("WAIT", "WAIT", "T1", "DONE")
        val provider = object : JevDecisionProvider {
            override val state = this@JevToolGatewayTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                val question = request.questions.firstOrNull { it.name == "action" } ?: return auditAnswer(request)
                offered += "WAIT" in question.criteria
                return JevDecisionResponse(mapOf("action" to choice(question, script.removeAt(0))), "jev-test")
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Tap Submit"))

        assertEquals("done_visible", Json.parseToJsonElement(result.text).jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals(listOf(true, true, false), offered.take(3))
    }

    @Test
    fun `a rejected done is not offered again on the same screen`() = runBlocking {
        val router = FakeRouter(mutableListOf(screen("ui-1", "Internet", false)))
        val doneOffered = mutableListOf<Boolean>()
        val provider = object : JevDecisionProvider {
            override val state = this@JevToolGatewayTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                val question = request.questions.firstOrNull { it.name == "action" }
                    ?: return JevDecisionResponse(request.questions.associate { it.name to choice(it, "PENDING") }, "jev-test")
                val canFinish = "DONE" in question.criteria
                doneOffered += canFinish
                return JevDecisionResponse(mapOf("action" to choice(question, if (canFinish) "DONE" else "BLOCKED")), "jev-test")
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Open the Wi-Fi page"))

        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(listOf(true, false, false), doneOffered)
        assertEquals("incomplete", json["status"]?.jsonPrimitive?.content)
        assertTrue(json.containsKey("taskLedger"))
    }

    @Test
    fun `a package scopes the goal to that app`() = runBlocking {
        val router = FakeRouter(mutableListOf(screen("ui-1", "Done", false)))
        var seenGoal: String? = null
        val provider = object : JevDecisionProvider {
            override val state = this@JevToolGatewayTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                val question = request.questions.firstOrNull { it.name == "action" } ?: return auditAnswer(request)
                seenGoal = request.state["goal"]?.jsonPrimitive?.content
                return JevDecisionResponse(mapOf("action" to choice(question, "DONE")), "jev-test")
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        gateway.invoke("jev_run_ui_task", buildJsonObject {
            put("goal", "Open the app and set Volume to 75%")
            put("package", "com.example.androidgym")
        })

        assertEquals("In the app com.example.androidgym: Open the app and set Volume to 75%", seenGoal)
    }

    @Test
    fun `an unknown argument is refused instead of dropped`() = runBlocking {
        val gateway = JevToolGateway(ScriptedProvider(state, mutableListOf())) { FakeRouter(mutableListOf(screen("ui-1", "Done", false))) }
        gateway.beginRun("run-1", File("."))

        val failure = runCatching {
            gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open the app"); put("app", "Gym") })
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("app"))
    }

    @Test
    fun `an unnamed checkbox is named by its row and a toggle loop stops`() = runBlocking {
        var checked = false
        val router = object : DeviceToolGateway by FakeRouter(mutableListOf(screen("ui-1", "x", false))) {
            var taps = 0
            override suspend fun invoke(name: String, arguments: JsonObject): ToolResult = when (name) {
                "apps_settings" -> ToolResult("{\"ok\":true,\"items\":[]}")
                "read_ui" -> ToolResult(buildJsonObject {
                    put("ok", true); put("observationId", "ui-$checked"); put("screenDigest", "row:$checked")
                    put("activePackage", "com.example"); put("truncated", false)
                    put("nodes", buildJsonArray {
                        add(buildJsonObject {
                            put("nodeId", "n1"); put("class", "android.widget.CheckBox"); put("enabled", true)
                            put("clickable", true); put("checkable", true); put("checked", checked)
                            put("bounds", buildJsonArray { add(40); add(500); add(160); add(620) })
                        })
                        add(buildJsonObject {
                            put("nodeId", "n2"); put("text", "Task #3: Validate System Telemetry"); put("enabled", true)
                            put("bounds", buildJsonArray { add(200); add(510); add(900); add(560) })
                        })
                    })
                }.toString())
                else -> { taps++; checked = !checked; ToolResult("ok") }
            }
        }
        val labels = mutableListOf<String>()
        val provider = object : JevDecisionProvider {
            override val state = this@JevToolGatewayTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                val question = request.questions.firstOrNull { it.name == "action" } ?: return auditAnswer(request)
                val tap = question.criteria.entries.firstOrNull { it.value.startsWith("Tap ") }
                tap?.let { labels += it.value }
                return JevDecisionResponse(mapOf("action" to choice(question, tap?.key ?: "BLOCKED")), "jev-test")
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", requestJson("Complete Task #3"))

        assertEquals("Tap Task #3: Validate System Telemetry (currently off) [n1]", labels.first())
        // The state in the label is what stops a real run; this is the backstop:
        // a control whose screen transition repeats is withdrawn.
        val perControl = Json.parseToJsonElement(result.text).jsonObject["history"]!!.jsonArray
            .map { it.jsonObject["label"]!!.jsonPrimitive.content }
            .filter { it.startsWith("Tap ") }
            .groupingBy { it.substringAfterLast('[') }.eachCount()
        assertTrue(perControl.toString(), perControl.values.all { it <= 4 })
    }

    @Test
    fun `none of the supplied values withdraws that field instead of ending the run`() = runBlocking {
        val field = ToolResult(buildJsonObject {
            put("ok", true); put("observationId", "ui-1"); put("screenDigest", "notes"); put("activePackage", "com.example")
            put("truncated", false)
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("nodeId", "n1"); put("text", "JevTest123"); put("class", "android.widget.EditText")
                    put("enabled", true); put("editable", true); put("clickable", true); put("focused", false)
                })
            })
        }.toString())
        val router = FakeRouter(mutableListOf(field))
        var typeOffers = 0
        val provider = object : JevDecisionProvider {
            override val state = this@JevToolGatewayTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                request.questions.firstOrNull { it.name == "text_value" }?.let {
                    return JevDecisionResponse(mapOf("text_value" to choice(it, "NONE")), "jev-test")
                }
                val question = request.questions.firstOrNull { it.name == "action" } ?: return auditAnswer(request)
                val type = question.criteria.entries.firstOrNull { it.value.startsWith("Replace field") }
                if (type != null) typeOffers++
                return JevDecisionResponse(mapOf("action" to choice(question, type?.key ?: "DONE")), "jev-test")
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_run_ui_task", buildJsonObject {
            put("goal", "Enter JevTest123 in Notes")
            put("texts", buildJsonArray { add("JevTest123") })
        })

        assertEquals("done_visible", Json.parseToJsonElement(result.text).jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals(1, typeOffers)
        assertTrue(router.actions.isEmpty())
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

    /** Answers the one flat action question with a scripted choice per step. */
    private class ScriptedProvider(
        override val state: MutableStateFlow<JevProviderState>,
        private val script: MutableList<String>,
    ) : JevDecisionProvider {
        var calls = 0
        override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
            calls++
            if (request.questions.none { it.name == "action" }) return auditAnswer(request)
            val question = request.questions.first { it.name == "action" }
            return JevDecisionResponse(mapOf("action" to choice(question, script.removeAt(0))), "jev-test")
        }
    }

    private class FakeRouter(
        private val screens: MutableList<ToolResult>,
        private val actionResult: ToolResult = ToolResult("ok"),
    ) : DeviceToolGateway {
        var reads = 0
        val actions = mutableListOf<Pair<String, JsonObject>>()
        override val definitions = emptyList<ToolDefinition>()
        override fun readyTools() = setOf("read_ui", "apps_settings", "tap_node", "scroll_node", "set_progress", "set_text", "key", "swipe", "open_app")
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult = when (name) {
            "apps_settings" -> ToolResult("{\"ok\":true,\"items\":[]}")
            "read_ui" -> (if (screens.size > 1) screens.removeAt(0) else screens.first()).also { reads++ }
            else -> actionResult.also { actions += name to arguments }
        }
    }

    companion object {
        private fun auditAnswer(request: JevDecisionRequest) = JevDecisionResponse(
            request.questions.associate { it.name to choice(it, JevTaskLedger.SATISFIED) },
            "jev-test",
        )
        private fun choice(question: JevChoiceQuestion, selected: String): JevDecision = JevDecision(
            type = "choice",
            choice = selected,
            confidence = 1.0,
            probabilities = question.criteria.keys.associateWith { if (it == selected) 1.0 else 0.0 },
        )
    }
}
