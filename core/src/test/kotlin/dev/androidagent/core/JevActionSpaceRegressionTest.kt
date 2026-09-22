package dev.androidagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
    fun `an app beyond the first fifty is offered by package name`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val router = object : DeviceToolGateway by FakeRouter(MutableList(2) { listWithScrollableRow() }) {
            override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
                if (name == "read_ui") return listWithScrollableRow()
                assertEquals("apps_settings", name)
                val offset = arguments.getValue("offset").jsonPrimitive.content.toInt()
                offsets += offset
                return ToolResult(buildJsonObject {
                    put("items", buildJsonArray {
                        if (offset == 0) repeat(50) { index -> add(buildJsonObject {
                            put("package", "example.app$index"); put("label", "App $index")
                        }) } else add(buildJsonObject {
                            put("package", "com.android.settings"); put("label", "Localized settings")
                        })
                    })
                }.toString())
            }
        }
        val provider = QuestionCapturingProvider(state)
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run", File("."))
        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open com.android.settings") })
        assertEquals(listOf(0, 50), offsets)
        assertTrue(provider.questions.single().criteria.values.any { it.contains("com.android.settings") })
    }

    @Test
    fun `home no-op is removed and a gesture remains available without another orchestrator call`() = runBlocking {
        val screen = listWithScrollableRow()
        val router = FakeRouter(MutableList(4) { screen })
        var calls = 0
        val provider = object : JevDecisionProvider {
            override val state = this@JevActionSpaceRegressionTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                if (request.questions.none { it.name == "action" }) return auditAnswer(request)
                val question = request.questions.first { it.name == "action" }
                val selected = if (calls++ == 0) "HOME" else {
                    assertTrue("no-op HOME remains offered", "HOME" !in question.criteria)
                    assertTrue("drawer swipe missing", "GUP" in question.criteria)
                    "DONE"
                }
                return JevDecisionResponse(mapOf("action" to choice(question, selected)), "test")
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run", File("."))
        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open Settings") })
        assertEquals(2, calls)
        assertEquals(1, router.actions.size)
    }

    @Test
    fun `tall scroll region keeps horizontal navigation and bounded swipe options`() = runBlocking {
        val router = FakeRouter(MutableList(2) { listWithScrollableRow() })
        val provider = QuestionCapturingProvider(state)
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run", File("."))
        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open another launcher page") })
        val choices = provider.questions.single().criteria
        assertTrue(choices.values.any { it.startsWith("Scroll left") })
        assertTrue("GUP" in choices)
        assertTrue(choices.size <= 255)
    }

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
    fun `adb text fallback carries the observed field focus point`() {
        val observation = JevObservation(
            observationId = "ui-1",
            json = buildJsonObject {
                put("activePackage", "com.example")
                put("nodes", buildJsonArray {
                    add(buildJsonObject {
                        put("nodeId", "n7")
                        put("text", "Notes")
                        put("class", "android.widget.EditText")
                        put("bounds", buildJsonArray { listOf(40, 200, 1040, 320).forEach { add(it) } })
                        put("enabled", true)
                        put("editable", true)
                        put("focused", false)
                    })
                })
            },
            fingerprint = "adb:screen",
        )
        val catalog = JevActionCatalog.build(
            goal = "Enter \"hello\" in Notes",
            observation = observation,
            texts = listOf("hello"),
            apps = emptyList(),
            ready = setOf("read_ui", "tap", "type_text", "key"),
            attempted = emptySet(),
            requestedPage = 0,
            requestedTextPage = 0,
        )
        val actionQuestion = catalog.request(
            "Enter \"hello\" in Notes", observation, emptyList(), "supplied",
            buildJsonObject { }, buildJsonArray { },
        ).questions.single()
        val selected = catalog.select(
            JevDecisionResponse(mapOf("action" to choice(actionQuestion, "TYPE")), "test"),
        )
        val textQuestion = catalog.textRequest(selected, "Enter \"hello\" in Notes").questions.single()
        val typed = catalog.selectText(
            selected,
            JevDecisionResponse(mapOf("text_value" to choice(textQuestion, "V1")), "test"),
        )

        assertEquals("type_text", typed.action?.tool)
        assertEquals("n7", typed.action?.arguments?.get("nodeId")?.jsonPrimitive?.content)
        assertEquals(540, typed.action?.arguments?.get("x")?.jsonPrimitive?.content?.toInt())
        assertEquals(260, typed.action?.arguments?.get("y")?.jsonPrimitive?.content?.toInt())
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
        assertTrue("no tap offered: $labels", labels.any { it.startsWith("Tap Wi-Fi") })
        assertTrue("no scroll offered: $labels", labels.any { it.startsWith("Scroll down in") })
        assertTrue("controls missing: ${question.criteria.keys}", question.criteria.keys.containsAll(
            listOf("BACK", "HOME", "WAIT", "DONE", "BLOCKED"),
        ))
    }

    @Test
    fun `a screen past the choice budget exposes every control through pages`() = runBlocking {
        val router = FakeRouter(MutableList(2) { crowdedScreen(named = 150, anonymous = 150) })
        val taps = mutableSetOf<String>()
        val provider = object : JevDecisionProvider {
            override val state = this@JevActionSpaceRegressionTest.state
            override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
                if (request.questions.none { it.name == "action" }) return auditAnswer(request)
                val question = request.questions.single { it.name == "action" }
                assertTrue(question.criteria.size <= 255)
                taps += question.criteria.values.filter { it.startsWith("Tap ") }
                val page = request.state.getValue("actionPage").jsonPrimitive.content.toInt()
                val pages = request.state.getValue("actionPages").jsonPrimitive.content.toInt()
                return JevDecisionResponse(mapOf("action" to choice(question, if (page < pages) "MORE_ACTIONS" else "DONE")), "test")
            }
        }
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        gateway.invoke("jev_run_ui_task", buildJsonObject { put("goal", "Open settings") })

        assertEquals(300, taps.size)
        assertEquals(150, taps.count { it.startsWith("Tap named-") })
        assertTrue(router.actions.isEmpty())
    }

    @Test
    fun `a settings goal offers the one-step route, most specific first`() {
        val labels = deepLinkOffers(
            goal = "Open the display settings and turn on dark theme",
            ready = DEFAULT_READY + "open_intent",
        )
        // Quick settings, a launcher, a gear and a scroll used to be the only
        // way in. The named screen leads; the root stays as the fallback for a
        // phone whose Settings does not declare the narrower action.
        assertEquals(
            listOf("android.settings.DISPLAY_SETTINGS", "android.settings.SETTINGS"),
            labels.mapNotNull { label -> SETTINGS_ACTION.find(label)?.value },
        )
    }

    @Test
    fun `no deep link is offered when the backend cannot launch one`() {
        val labels = deepLinkOffers(
            goal = "Open the display settings and turn on dark theme",
            ready = DEFAULT_READY,
        )
        assertTrue(labels.toString(), labels.none { SETTINGS_ACTION.containsMatchIn(it) })
    }

    @Test
    fun `a deep link this phone does not handle costs one step, not the run`() = runBlocking {
        val router = FakeRouter(
            MutableList(2) { listWithScrollableRow() },
            ready = DEFAULT_READY + "open_intent",
            refuse = setOf("open_intent"),
        )
        val provider = ScriptedProvider(state, mutableListOf("I1", "DONE"))
        val gateway = JevToolGateway(provider) { router }
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke(
            "jev_run_ui_task",
            buildJsonObject { put("goal", "Open the bluetooth settings") },
        )

        // A refused settings intent only failed to navigate. Treating it as a
        // mutation that may have landed would end the goal over a destination
        // this phone simply does not declare.
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(json.toString(), "done_visible", json.getValue("status").jsonPrimitive.content)
        assertEquals("open_intent", router.actions.single().first)
        assertEquals(
            "android.settings.BLUETOOTH_SETTINGS",
            router.actions.single().second.getValue("action").jsonPrimitive.content,
        )
    }

    @Test
    fun `a compose field is named by its own subtree, not by its class`() {
        // Shapes taken from AndroidGym on the phone: both fields report as
        // android.widget.EditText, the notes field carries no name of its own,
        // and the dropdown's own text is its value rather than its name.
        val observation = JevObservation(
            observationId = "ui-1",
            json = buildJsonObject {
                put("activePackage", "com.example.androidgym")
                put("nodes", buildJsonArray {
                    add(editText("n6", text = "Urgent", bounds = listOf(42, 1449, 1038, 1617)))
                    add(plainNode("n7", contentDescription = "Priority Dropdown", bounds = listOf(84, 1449, 371, 1491)))
                    add(editText("n10", text = null, bounds = listOf(42, 1961, 1038, 2129)))
                    add(plainNode("n11", contentDescription = "Agent Notes Field", bounds = listOf(42, 1961, 1038, 2129)))
                    add(plainNode("n12", text = "Enter Benchmark Report Notes", bounds = listOf(84, 2024, 735, 2087)))
                })
            },
            fingerprint = "a11y:gym",
        )
        val goal = "Enter the notes and submit them"
        val catalog = JevActionCatalog.build(
            goal = goal, observation = observation, texts = listOf("JevTest123"), apps = emptyList(),
            ready = DEFAULT_READY, attempted = emptySet(), requestedPage = 0, requestedTextPage = 0,
        )

        val offers = catalog.request(goal, observation, emptyList(), "supplied", buildJsonObject { }, buildJsonArray { })
            .questions.single().criteria.values
        // "Replace field android.widget.EditText" twice on one screen is a
        // choice Jev cannot make. It answered NONE and the run returned
        // needs_input with the field on screen.
        assertTrue(offers.toString(), offers.any { it.startsWith("Replace field Agent Notes Field [n10]") })
        assertTrue(offers.toString(), offers.none { it.contains("Replace field android.widget.EditText") })
    }

    private fun editText(nodeId: String, text: String?, bounds: List<Int>) = buildJsonObject {
        put("nodeId", nodeId)
        text?.let { put("text", it) }
        put("class", "android.widget.EditText")
        put("bounds", buildJsonArray { bounds.forEach { add(it) } })
        put("enabled", true)
        put("clickable", true)
        put("editable", true)
        put("focused", false)
    }

    private fun plainNode(
        nodeId: String,
        text: String? = null,
        contentDescription: String? = null,
        bounds: List<Int>,
    ) = buildJsonObject {
        put("nodeId", nodeId)
        text?.let { put("text", it) }
        contentDescription?.let { put("contentDescription", it) }
        put("class", "android.view.View")
        put("bounds", buildJsonArray { bounds.forEach { add(it) } })
        put("enabled", true)
        put("clickable", false)
        put("focused", false)
    }

    private fun deepLinkOffers(goal: String, ready: Set<String>): Collection<String> {
        val observation = JevObservation(
            observationId = "ui-1",
            json = buildJsonObject {
                put("activePackage", "com.example")
                put("nodes", buildJsonArray { })
            },
            fingerprint = "a11y:screen",
        )
        val catalog = JevActionCatalog.build(
            goal = goal, observation = observation, texts = emptyList(), apps = emptyList(),
            ready = ready, attempted = emptySet(), requestedPage = 0, requestedTextPage = 0,
        )
        return catalog.request(goal, observation, emptyList(), "goal", buildJsonObject { }, buildJsonArray { })
            .questions.single().criteria.values
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
            if (request.questions.none { it.name == "action" }) auditAnswer(request) else JevDecisionResponse(
                mapOf("action" to choice(request.questions.first { it.name == "action" }, script.removeAt(0))),
                "jev-test",
            )
    }

    private class QuestionCapturingProvider(
        override val state: MutableStateFlow<JevProviderState>,
    ) : JevDecisionProvider {
        val questions = mutableListOf<JevChoiceQuestion>()
        override suspend fun choose(request: JevDecisionRequest): JevDecisionResponse {
            if (request.questions.none { it.name == "action" }) return auditAnswer(request)
            questions += request.questions
            return JevDecisionResponse(
                mapOf("action" to choice(request.questions.first { it.name == "action" }, "DONE")),
                "jev-test",
            )
        }
    }

    private class FakeRouter(
        private val screens: MutableList<ToolResult>,
        private val ready: Set<String> = DEFAULT_READY,
        /** Tools this phone dispatches nothing for, as a missing handler would. */
        private val refuse: Set<String> = emptySet(),
    ) : DeviceToolGateway {
        val actions = mutableListOf<Pair<String, JsonObject>>()
        override val definitions = emptyList<ToolDefinition>()
        override fun readyTools() = ready
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult = when (name) {
            "apps_settings" -> ToolResult("{\"ok\":true,\"items\":[]}")
            "read_ui" -> if (screens.size > 1) screens.removeAt(0) else screens.first()
            in refuse -> ToolResult("nothing on this device handles that", success = false,
                dispatch = ToolDispatch.NOT_DISPATCHED).also { actions += name to arguments }
            else -> ToolResult("ok").also { actions += name to arguments }
        }
    }

    companion object {
        private val DEFAULT_READY = setOf(
            "read_ui", "apps_settings", "tap_node", "scroll_node", "set_progress",
            "set_text", "key", "swipe", "open_app",
        )
        private val SETTINGS_ACTION = Regex("android\\.settings\\.[A-Z_]+")

        private fun auditAnswer(request: JevDecisionRequest) = JevDecisionResponse(
            request.questions.associate { it.name to choice(it, if ("COMPLETE" in it.criteria) "COMPLETE" else it.criteria.keys.last()) },
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
