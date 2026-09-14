package dev.androidagent.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runner against a fake phone.
 *
 * [FakeScreen] answers `read_ui` the way a backend does and moves when an
 * action lands on a node, so these exercise the thing that matters: the runner
 * resolves what a step *means* against whatever is on screen at that moment.
 */
class WorkflowRunnerTest {

    /** One node as a backend would emit it. */
    private data class Node(
        val id: String,
        val text: String? = null,
        val contentDescription: String? = null,
        val resourceId: String? = null,
        val className: String? = null,
        val bounds: List<Int> = listOf(0, 0, 100, 100),
        val clickable: Boolean = true,
        val scrollable: Boolean = false,
        val checkable: Boolean = false,
        val checked: Boolean = false,
        /** Bounds of the clickable row a non-clickable label sits in. */
        val ancestorBounds: List<Int>? = null,
        /** Null for the app in front; set for a window above it, like the status bar. */
        val packageName: String? = null,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("nodeId", id)
            text?.let { put("text", it) }
            contentDescription?.let { put("contentDescription", it) }
            resourceId?.let { put("resourceId", it) }
            className?.let { put("class", it) }
            packageName?.let { put("package", it) }
            put("bounds", buildJsonArray { bounds.forEach { value -> add(value) } })
            put("enabled", true)
            put("clickable", clickable)
            put("scrollable", scrollable)
            put("focused", false)
            if (checkable) {
                put("checkable", true)
                put("checked", checked)
            }
            ancestorBounds?.let { box ->
                put("clickableAncestor", buildJsonObject {
                    put("nodeId", "$id-row")
                    put("bounds", buildJsonArray { box.forEach { value -> add(value) } })
                })
            }
        }
    }

    private data class Page(val activePackage: String, val nodes: List<Node>)

    /**
     * A phone with one screen at a time. Tapping a node whose id is a key in
     * [transitions] moves to that screen, which is what makes a multi-step
     * workflow a real test rather than five independent calls.
     */
    private inner class FakeScreen(
        var page: Page,
        val transitions: Map<String, Page> = emptyMap(),
    )

    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var revoked = false
    private var observation = 0
    private var serviceable = mutableSetOf(
        "read_ui", "tap_node", "set_text", "scroll_node", "wait_for_change",
        "tap", "type_text", "key", "open_app", "swipe", "screenshot",
    )
    private lateinit var phone: FakeScreen
    private var confirmations = mutableListOf<WorkflowConfirmation>()
    private var confirmAnswer = WorkflowConfirmationOutcome.ALLOWED

    /** When set, an unfiltered read_ui returns only this many nodes, the way a paged reply does. */
    private var pageSize: Int? = null

    /** The nodes one read_ui call returns, honouring the filters a backend does. */
    private fun readNodes(args: JsonObject): Pair<List<Node>, Boolean> {
        val all = phone.page.nodes
        fun field(key: String) = args[key]?.jsonPrimitive?.contentOrNull
        val pkg = field("package")
        val text = field("text")
        val resourceId = field("resourceId")
        val className = field("class")
        if (pkg == null && text == null && resourceId == null && className == null) {
            val size = pageSize ?: return all to false
            return all.take(size) to (all.size > size)
        }
        val matching = all.filter { node ->
            (pkg == null || (node.packageName ?: phone.page.activePackage) == pkg) &&
                (text == null || listOfNotNull(node.text, node.contentDescription).any { it.contains(text, ignoreCase = true) }) &&
                (resourceId == null || node.resourceId?.contains(resourceId, ignoreCase = true) == true) &&
                (className == null || node.className?.contains(className, ignoreCase = true) == true)
        }
        // Scoping to a package still pages; only a query naming the element
        // is narrow enough to arrive whole.
        val size = pageSize
        if (text == null && resourceId == null && className == null && size != null) {
            return matching.take(size) to (matching.size > size)
        }
        return matching to false
    }

    private fun runner() = WorkflowRunner(
        invokeTool = { name, args -> invoke(name, args) },
        isRevoked = { revoked },
        confirm = { request -> confirmations += request; confirmAnswer },
    )

    private fun invoke(name: String, args: JsonObject): ToolResult {
        calls += name to args
        if (name !in serviceable) {
            return ToolResult(
                buildJsonObject {
                    put("ok", false)
                    put("errorType", "a11y_unavailable")
                    put("message", "no backend serves $name")
                }.toString(),
                success = false,
            )
        }
        return when (name) {
            "read_ui" -> {
                val (nodes, truncated) = readNodes(args)
                ToolResult(
                    buildJsonObject {
                        put("ok", true)
                        put("observationId", "ui-${++observation}")
                        put("activePackage", phone.page.activePackage)
                        put("truncated", truncated)
                        put("nodes", buildJsonArray { nodes.forEach { add(it.toJson()) } })
                    }.toString(),
                )
            }
            "tap_node" -> {
                val nodeId = args["nodeId"]!!.jsonPrimitive.content
                phone.transitions[nodeId]?.let { phone.page = it }
                ToolResult("Tapped $nodeId")
            }
            "scroll_node" -> {
                // Scrolling a list brings new rows into view, which is the
                // whole reason a step would ask to scroll into view.
                val nodeId = args["nodeId"]!!.jsonPrimitive.content
                phone.transitions[nodeId]?.let { phone.page = it }
                ToolResult("Scrolled $nodeId")
            }
            "tap" -> {
                // A coordinate tap finds whatever box contains the point.
                val x = args["x"]!!.jsonPrimitive.int()
                val y = args["y"]!!.jsonPrimitive.int()
                val hit = phone.page.nodes.firstOrNull { node ->
                    x >= node.bounds[0] && x <= node.bounds[2] && y >= node.bounds[1] && y <= node.bounds[3]
                }
                hit?.let { node -> phone.transitions[node.id]?.let { phone.page = it } }
                ToolResult("Tapped $x,$y")
            }
            "set_text", "type_text" -> {
                val typed = args["text"]!!.jsonPrimitive.content
                phone.transitions["text:$typed"]?.let { phone.page = it }
                ToolResult("{\"typed\":${typed.length},\"verified\":true}")
            }
            "open_app" -> {
                val pkg = args["package"]!!.jsonPrimitive.content
                phone.transitions["open:$pkg"]?.let { phone.page = it }
                ToolResult("Opened $pkg")
            }
            "screenshot" -> ToolResult("Screenshot captured", imageBase64 = "AAAA")
            else -> ToolResult("$name ok")
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()

    private fun definition(vararg steps: String, id: String = "test", pkg: String = "com.android.settings") =
        WorkflowDefinition.parse(
            Json.parseToJsonElement(
                """{"id":"$id","package":"$pkg","steps":[${steps.joinToString(",")}]}""",
            ).jsonObject,
        )

    private fun parse(result: ToolResult): JsonObject = Json.parseToJsonElement(result.text).jsonObject

    private fun statuses(result: ToolResult): List<Pair<String, String>> =
        parse(result)["steps"]!!.jsonArray.map { element ->
            val step = element.jsonObject
            step["id"]!!.jsonPrimitive.content to step["status"]!!.jsonPrimitive.content
        }

    // ---- the acceptance case ----

    @Test fun fiveStepsRunInOneCallAndEveryOneIsVerified() {
        // The whole point of the feature: one call, five consecutive steps, no
        // model turn in between, each checked before the next one starts.
        val home = Page("com.android.launcher", listOf(Node("n0", text = "Home")))
        val settings = Page(
            "com.android.settings",
            listOf(Node("n1", text = "Search settings", resourceId = "com.android.settings:id/search_action_bar")),
        )
        val searching = Page(
            "com.android.settings",
            listOf(Node("n2", className = "android.widget.EditText", resourceId = "com.android.settings:id/search_src_text")),
        )
        val results = Page(
            "com.android.settings",
            listOf(Node("n3", text = "Wireless debugging")),
        )
        val detail = Page(
            "com.android.settings",
            listOf(
                Node("n4", text = "Wireless debugging"),
                Node("n5", resourceId = "com.android.settings:id/switch_widget", className = "android.widget.Switch", checkable = true, checked = false),
            ),
        )
        val enabled = Page(
            "com.android.settings",
            listOf(
                Node("n4", text = "Wireless debugging"),
                Node("n5", resourceId = "com.android.settings:id/switch_widget", className = "android.widget.Switch", checkable = true, checked = true),
            ),
        )
        phone = FakeScreen(
            home,
            mapOf(
                "open:com.android.settings" to settings,
                "n1" to searching,
                "text:Wireless debugging" to results,
                "n3" to detail,
                "n5" to enabled,
            ),
        )
        val workflow = definition(
            """{"id":"open_settings","action":"open_app","verify":{"package":"com.android.settings"}}""",
            """{"id":"open_search","action":"tap","target":{"resourceId":"com.android.settings:id/search_action_bar","text":"Search settings"},"verify":{"present":{"className":"EditText"}}}""",
            """{"id":"search","action":"type_text","text":"Wireless debugging","target":{"className":"EditText"},"verify":{"present":{"text":"Wireless debugging"}}}""",
            """{"id":"open_result","action":"tap","target":{"text":"Wireless debugging"},"verify":{"present":{"resourceId":"switch_widget"}}}""",
            """{"id":"enable","action":"tap","target":{"resourceId":"com.android.settings:id/switch_widget"},"requiresConfirmation":true,"skipIfVerified":true,"verify":{"checked":true}}""",
        )

        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }

        assertTrue(result.text, result.success)
        val json = parse(result)
        assertEquals(5, json["ranSteps"]!!.jsonPrimitive.intOrNull)
        assertTrue(json["steps"]!!.jsonArray.all { it.jsonObject["verified"]!!.jsonPrimitive.booleanOrNull == true })
        // Exactly one turn of the model's: the tool was entered once.
        assertEquals(1, confirmations.size)
        assertEquals("enable", confirmations.single().stepId)
    }

    // ---- selectors resolve live ----

    @Test fun aMovedRowIsStillFoundBecauseNothingPositionalIsStored() {
        phone = FakeScreen(
            Page(
                "com.android.settings",
                listOf(
                    Node("n0", text = "Somewhere else", bounds = listOf(0, 0, 100, 100)),
                    // Same label, nowhere near where it was when this was written.
                    Node("n1", text = "Wireless debugging", bounds = listOf(0, 1800, 1080, 1900)),
                ),
            ),
        )
        val workflow = definition("""{"id":"open","action":"tap","target":{"text":"Wireless debugging"}}""")
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertEquals("n1", calls.first { it.first == "tap_node" }.second["nodeId"]!!.jsonPrimitive.content)
    }

    @Test fun aRenamedViewIdStillResolvesThroughTheLabel() {
        // The resilience claim: criteria are scored, not ANDed, so losing one
        // of them does not lose the node.
        phone = FakeScreen(
            Page(
                "com.android.settings",
                listOf(Node("n1", text = "Search settings", resourceId = "com.android.settings:id/renamed_in_v14")),
            ),
        )
        val workflow = definition(
            """{"id":"open","action":"tap","target":{"resourceId":"com.android.settings:id/search_action_bar","text":"Search settings"}}""",
        )
        assertTrue(runBlocking { runner().run(workflow, WorkflowRunner.Options()) }.success)
    }

    // ---- what a real Settings screen did to the shipped workflows ----

    @Test fun aSwitchThatAnswersOnlyTheClassIsSomeOtherSwitch() {
        // Developer options on a real phone: the switch the step means is not
        // on screen, and a different one is. Tapping it would flip a setting
        // nobody asked about.
        phone = FakeScreen(
            Page(
                "com.android.settings",
                listOf(
                    Node("n1", text = "Use developer options", className = "android.widget.TextView", clickable = false),
                    Node("n2", resourceId = "com.android.settings:id/switchWidget", className = "android.widget.Switch", checkable = true, checked = true),
                ),
            ),
        )
        val workflow = definition(
            """{"id":"enable","action":"tap","target":{"text":"Wireless debugging","className":"Switch"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        assertEquals("target_not_found", parse(result)["errorType"]!!.jsonPrimitive.content)
        assertTrue(calls.none { it.first == "tap_node" || it.first == "tap" })
    }

    @Test fun theSwitchOutranksTheRowTitleThatCarriesTheSameLabel() {
        // As a real Developer options row reports it: the title is a TextView,
        // the switch carries the label as its contentDescription.
        phone = FakeScreen(
            Page(
                "com.android.settings",
                listOf(
                    Node("n1", text = "Wireless debugging", resourceId = "android:id/title", className = "android.widget.TextView", clickable = false, ancestorBounds = listOf(0, 2115, 1080, 2329)),
                    Node("n2", contentDescription = "Wireless debugging", resourceId = "com.android.settings:id/switchWidget", className = "android.widget.Switch", checkable = true),
                ),
            ),
        )
        val workflow = definition(
            """{"id":"enable","action":"tap","target":{"text":"Wireless debugging","className":"Switch"}}""",
        )
        assertTrue(runBlocking { runner().run(workflow, WorkflowRunner.Options()) }.success)
        assertEquals("n2", calls.first { it.first == "tap_node" }.second["nodeId"]!!.jsonPrimitive.content)
    }

    @Test fun theSearchFieldHoldingTheQueryDoesNotStealTheTapFromTheResult() {
        // After typing, the search field's own text equals the result's title,
        // and the field is clickable while the title is not.
        phone = FakeScreen(
            Page(
                "com.android.settings.intelligence",
                listOf(
                    Node("n1", text = "Wireless debugging", resourceId = "android:id/search_src_text", className = "android.widget.AutoCompleteTextView"),
                    Node("n2", text = "Wireless debugging", resourceId = "android:id/title", className = "android.widget.TextView", clickable = false, ancestorBounds = listOf(0, 294, 1080, 558)),
                ),
            ),
        )
        val workflow = definition(
            """{"id":"open_result","action":"tap","target":{"resourceId":"android:id/title","text":"Wireless debugging","exact":true,"clickable":true}}""",
        )
        assertTrue(runBlocking { runner().run(workflow, WorkflowRunner.Options()) }.success)
        assertEquals("n2", calls.first { it.first == "tap_node" }.second["nodeId"]!!.jsonPrimitive.content)
    }

    @Test fun aWidgetIdIsFoundWhicheverWayTheLayoutSpellsIt() {
        phone = FakeScreen(
            Page("com.android.settings", listOf(Node("n1", resourceId = "com.android.settings:id/switchWidget", className = "android.widget.Switch"))),
        )
        val workflow = definition("""{"id":"tap","action":"tap","target":{"resourceId":"switch_widget"}}""")
        assertTrue(runBlocking { runner().run(workflow, WorkflowRunner.Options()) }.success)
    }

    @Test fun aShortWordInsideTheWantedTextDoesNotSatisfyACondition() {
        // "Off" is inside "Turn off now"; it does not mean the button is there.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Go"), Node("n2", text = "Off"))))
        val workflow = definition(
            """{"id":"go","action":"tap","target":{"text":"Go"},"verify":{"present":{"text":"Turn off now"},"timeoutMs":500}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        assertEquals("verification_failed", parse(result)["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun aPageFilledByTheStatusBarIsReadAgainForTheAppInFront() {
        // On a real phone the first page of Settings search was all systemui.
        pageSize = 2
        phone = FakeScreen(
            Page(
                "com.android.settings.intelligence",
                listOf(
                    Node("s1", resourceId = "com.android.systemui:id/status_bar", clickable = false, packageName = "com.android.systemui"),
                    Node("s2", text = "13:01", resourceId = "com.android.systemui:id/clock", clickable = false, packageName = "com.android.systemui"),
                    Node("n1", text = "Search…", resourceId = "android:id/search_src_text", className = "android.widget.AutoCompleteTextView"),
                ),
            ),
        )
        val workflow = definition(
            """{"id":"check","action":"observe","verify":{"present":{"resourceId":"android:id/search_src_text"},"timeoutMs":500}}""",
            """{"id":"type","action":"type_text","text":"Wireless debugging","target":{"resourceId":"android:id/search_src_text","className":"EditText"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertEquals("n1", calls.first { it.first == "set_text" }.second["nodeId"]!!.jsonPrimitive.content)
    }

    @Test fun aConditionAboutAnElementOnPageTwoStillHolds() {
        // A scoped read can itself be paged; the condition looks past it.
        pageSize = 1
        phone = FakeScreen(
            Page(
                "com.android.settings",
                listOf(Node("n0", text = "Header", clickable = false), Node("n1", text = "Arrived", clickable = false)),
            ),
        )
        val workflow = definition(
            """{"id":"check","action":"observe","verify":{"present":{"text":"Arrived"},"timeoutMs":500}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
    }

    @Test fun afterAYesTheAppIsWaitedForRatherThanRelaunchedOntoItsHomePage() {
        // On a phone, open_app after the approval reset Settings from Developer
        // options to its home page, and the switch was never found.
        val developerOptions = Page(
            "com.android.settings",
            listOf(Node("n1", contentDescription = "Wireless debugging", className = "android.widget.Switch", checkable = true)),
        )
        val approval = Page("dev.androidagent.app", emptyList())
        phone = FakeScreen(developerOptions, mapOf("open:com.android.settings" to Page("com.android.settings", listOf(Node("h", text = "Network and internet")))))
        var readsUntilBack = 0
        val runner = WorkflowRunner(
            invokeTool = { name, args ->
                if (name == "read_ui" && phone.page === approval && --readsUntilBack <= 0) phone.page = developerOptions
                invoke(name, args)
            },
            isRevoked = { revoked },
            confirm = { phone.page = approval; readsUntilBack = 2; WorkflowConfirmationOutcome.ALLOWED },
        )
        val workflow = definition(
            """{"id":"enable","action":"tap","target":{"text":"Wireless debugging","className":"Switch"},"requiresConfirmation":true}""",
        )
        val result = runBlocking { runner.run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertTrue("Settings must not be relaunched", calls.none { it.first == "open_app" })
        assertEquals("n1", calls.first { it.first == "tap_node" }.second["nodeId"]!!.jsonPrimitive.content)
    }

    @Test fun anExactSelectorRefusesANodeThatOnlyPartlyMatches() {
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Delete everything"))))
        val workflow = definition("""{"id":"tap","action":"tap","target":{"text":"Delete","exact":true}}""")
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.success)
        assertEquals("target_not_found", parse(result)["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun theNodeIdAndObservationIdAlwaysComeFromTheSameFreshRead() {
        // A handle from an older observation is refused by the backend, so the
        // pair the runner sends has to come out of one reply.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Go"))))
        val workflow = definition(
            """{"id":"a","action":"tap","target":{"text":"Go"}}""",
            """{"id":"b","action":"tap","target":{"text":"Go"}}""",
        )
        runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        val reads = calls.count { it.first == "read_ui" }
        val taps = calls.filter { it.first == "tap_node" }
        assertEquals(2, taps.size)
        // Two different observations, never the same id twice.
        assertEquals(2, taps.map { it.second["observationId"]!!.jsonPrimitive.content }.distinct().size)
        assertTrue("each tap needs its own read", reads >= taps.size)
    }

    // ---- falling back when a backend is missing ----

    @Test fun withoutNodeAddressingItTapsTheNodesCurrentCentreInstead() {
        // ADB alone serves no tap_node. The coordinates are computed from the
        // observation taken moments earlier, never from the definition.
        serviceable.remove("tap_node")
        phone = FakeScreen(
            Page("com.example", listOf(Node("n1", text = "Go", bounds = listOf(100, 200, 300, 400)))),
            mapOf("n1" to Page("com.example", listOf(Node("n2", text = "Arrived")))),
        )
        val workflow = definition("""{"id":"go","action":"tap","target":{"text":"Go"},"verify":{"present":{"text":"Arrived"}}}""")
        assertTrue(runBlocking { runner().run(workflow, WorkflowRunner.Options()) }.success)
        val tap = calls.first { it.first == "tap" }.second
        assertEquals(200, tap["x"]!!.jsonPrimitive.intOrNull)
        assertEquals(300, tap["y"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun aToolThatRanAndFailedIsNotRetriedDownAnotherPath() {
        // The difference that matters: "no backend served this" may be retried,
        // "the backend did it and it did not take" may not, or a tap doubles.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Go"))))
        val runner = WorkflowRunner(
            invokeTool = { name, args ->
                calls += name to args
                if (name == "tap_node") ToolResult("{\"ok\":false,\"errorType\":\"node_gone\"}", success = false)
                else invoke(name, args)
            },
            isRevoked = { revoked },
        )
        val result = runBlocking { runner.run(definition("""{"id":"go","action":"tap","target":{"text":"Go"}}"""), WorkflowRunner.Options()) }
        assertFalse(result.success)
        assertTrue(calls.none { it.first == "tap" })
        assertTrue(parse(result)["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull == true)
    }

    // ---- verification ----

    @Test fun aStepWhoseConditionNeverHoldsFailsEvenThoughTheTapLanded() {
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Go"))))
        val workflow = definition(
            """{"id":"go","action":"tap","target":{"text":"Go"},"verify":{"present":{"text":"Arrived"},"timeoutMs":500}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.success)
        val json = parse(result)
        assertEquals("verification_failed", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("go", json["failedStep"]!!.jsonPrimitive.content)
        assertTrue(json["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test fun aSwitchIsVerifiedByItsStateAndNotByHavingBeenTapped() {
        phone = FakeScreen(
            Page("com.example", listOf(Node("n1", text = "Bluetooth", checkable = true, checked = false))),
            // The tap lands but the switch does not move, as a disabled one does not.
            emptyMap(),
        )
        val workflow = definition(
            """{"id":"on","action":"tap","target":{"text":"Bluetooth"},"verify":{"checked":true,"timeoutMs":500}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.success)
        assertTrue(parse(result)["message"]!!.jsonPrimitive.content.contains("it is off"))
    }

    @Test fun aStepWhoseConditionAlreadyHoldsIsSkippedRatherThanRepeated() {
        // Re-running "turn it on" on something already on turns it off. This is
        // what makes resuming a failed run safe.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Bluetooth", checkable = true, checked = true))))
        val workflow = definition(
            """{"id":"on","action":"tap","target":{"text":"Bluetooth"},"skipIfVerified":true,"verify":{"checked":true}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.success)
        assertEquals(listOf("on" to "skipped"), statuses(result))
        assertTrue(calls.none { it.first == "tap_node" || it.first == "tap" })
    }

    @Test fun nobodyIsAskedToAllowSomethingThatHasAlreadyHappened() {
        // The switch is already on, so there is nothing to allow. Asking first
        // and then skipping would train the user to wave approvals through.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Wireless debugging", checkable = true, checked = true))))
        val workflow = definition(
            """{"id":"enable","action":"tap","target":{"text":"Wireless debugging"},
                "requiresConfirmation":true,"skipIfVerified":true,"verify":{"checked":true}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertEquals(listOf("enable" to "skipped"), statuses(result))
        assertTrue("the user must not be asked", confirmations.isEmpty())
    }

    @Test fun aScrollWithNoTargetFindsTheListOnTheScreenItself() {
        phone = FakeScreen(
            Page(
                "com.example",
                listOf(
                    Node("n0", text = "Header", clickable = false),
                    Node("n1", className = "androidx.recyclerview.widget.RecyclerView", clickable = false, scrollable = true),
                ),
            ),
        )
        val workflow = definition("""{"id":"down","action":"scroll","arguments":{"direction":"forward"}}""")
        assertTrue(runBlocking { runner().run(workflow, WorkflowRunner.Options()) }.success)
        assertEquals("n1", calls.first { it.first == "scroll_node" }.second["nodeId"]!!.jsonPrimitive.content)
    }

    @Test fun aScreenWithNothingScrollableStillGetsAGestureMeasuredOffThatScreen() {
        // A map or a canvas has no scrollable node and never will, and a swipe
        // computed for some other phone's resolution misses.
        serviceable.remove("scroll_node")
        phone = FakeScreen(
            Page("com.example", listOf(Node("n0", text = "Canvas", bounds = listOf(0, 0, 720, 1600), clickable = false))),
        )
        val workflow = definition("""{"id":"down","action":"scroll"}""")
        assertTrue(runBlocking { runner().run(workflow, WorkflowRunner.Options()) }.success)
        val swipe = calls.first { it.first == "swipe" }.second
        assertEquals(360, swipe["x1"]!!.jsonPrimitive.intOrNull)
        assertEquals(1120, swipe["y1"]!!.jsonPrimitive.intOrNull)
        assertEquals(480, swipe["y2"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun aTargetOffScreenIsScrolledToWhenTheStepAsksForIt() {
        val top = Page(
            "com.example",
            listOf(Node("list", className = "ScrollView", clickable = false, scrollable = true), Node("n0", text = "First")),
        )
        val bottom = Page(
            "com.example",
            listOf(Node("list", className = "ScrollView", clickable = false, scrollable = true), Node("n9", text = "Developer options")),
        )
        phone = FakeScreen(top, mapOf("list" to bottom))
        val workflow = definition(
            """{"id":"open","action":"tap","target":{"text":"Developer options","scrollIntoView":true}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertTrue(calls.any { it.first == "scroll_node" })
        assertEquals("n9", calls.last { it.first == "tap_node" }.second["nodeId"]!!.jsonPrimitive.content)
    }

    @Test fun anOptionalStepWithNothingToDoIsSkippedAndTheRunContinues() {
        phone = FakeScreen(
            Page("com.example", listOf(Node("n1", text = "Continue"))),
            mapOf("n1" to Page("com.example", listOf(Node("n2", text = "Done")))),
        )
        val workflow = definition(
            """{"id":"dismiss","action":"tap","target":{"text":"Not now"},"optional":true}""",
            """{"id":"go","action":"tap","target":{"text":"Continue"},"verify":{"present":{"text":"Done"}}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertEquals(listOf("dismiss" to "skipped", "go" to "done"), statuses(result))
    }

    // ---- failure reporting and resuming ----

    @Test fun aFailureNamesTheStepTheCompletedPrefixAndHowToResume() {
        phone = FakeScreen(
            Page("com.example", listOf(Node("n1", text = "First"))),
            mapOf("n1" to Page("com.example", listOf(Node("n2", text = "Second")))),
        )
        val workflow = definition(
            """{"id":"one","action":"tap","target":{"text":"First"}}""",
            """{"id":"two","action":"tap","target":{"text":"Nothing like this"}}""",
            """{"id":"three","action":"tap","target":{"text":"Second"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.success)
        val json = parse(result)
        assertEquals("two", json["failedStep"]!!.jsonPrimitive.content)
        assertEquals(1, json["failedStepIndex"]!!.jsonPrimitive.intOrNull)
        assertEquals(listOf("one" to "done"), statuses(result))
        val resume = json["resume"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("two", resume["startAt"]!!.jsonPrimitive.content)
        assertEquals("resume", resume["mode"]!!.jsonPrimitive.content)
        // What was on screen instead, so the model does not have to read it again.
        assertTrue(json["screen"]!!.jsonObject["visible"]!!.jsonArray.isNotEmpty())
    }

    @Test fun resumingSkipsTheStepsBeforeItWithoutRunningThem() {
        phone = FakeScreen(
            Page("com.example", listOf(Node("n2", text = "Second"))),
        )
        val workflow = definition(
            """{"id":"one","action":"tap","target":{"text":"First"}}""",
            """{"id":"two","action":"tap","target":{"text":"Second"}}""",
        )
        val result = runBlocking {
            runner().run(workflow, WorkflowRunner.Options(mode = "resume", startAt = "two"))
        }
        assertTrue(result.text, result.success)
        assertEquals(listOf("one" to "skipped", "two" to "done"), statuses(result))
    }

    @Test fun anUnknownResumePointIsRefusedBeforeAnythingRuns() {
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "First"))))
        val result = runBlocking {
            runner().run(
                definition("""{"id":"one","action":"tap","target":{"text":"First"}}"""),
                WorkflowRunner.Options(startAt = "nope"),
            )
        }
        assertFalse(result.success)
        assertEquals("unknown_step", parse(result)["errorType"]!!.jsonPrimitive.content)
        assertTrue(calls.isEmpty())
    }

    @Test fun aFailureCanCarryAScreenshotWhenOneWasAskedFor() {
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "First"))))
        val result = runBlocking {
            runner().run(
                definition("""{"id":"one","action":"tap","target":{"text":"Missing"}}"""),
                WorkflowRunner.Options(screenshotOnFailure = true),
            )
        }
        assertFalse(result.success)
        assertEquals("AAAA", result.imageBase64)
    }

    // ---- safety ----

    @Test fun aSensitiveStepDoesNotRunWhenTheUserSaysNo() {
        confirmAnswer = WorkflowConfirmationOutcome.DENIED
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Pay"))))
        val workflow = definition("""{"id":"pay","action":"tap","target":{"text":"Pay"},"requiresConfirmation":true}""")
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.success)
        assertEquals("confirmation_denied", parse(result)["errorType"]!!.jsonPrimitive.content)
        assertTrue("nothing may be tapped", calls.none { it.first == "tap_node" || it.first == "tap" })
    }

    @Test fun aSensitiveStepDoesNotRunWhenNothingCanAskTheUser() {
        // The default host cannot ask. Refusing is the safe answer; running the
        // step anyway would be the feature quietly removing its own gate.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Pay"))))
        val runner = WorkflowRunner(invokeTool = { name, args -> invoke(name, args) }, isRevoked = { revoked })
        val workflow = definition("""{"id":"pay","action":"tap","target":{"text":"Pay"},"requiresConfirmation":true}""")
        val result = runBlocking { runner.run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.success)
        assertEquals("confirmation_unavailable", parse(result)["errorType"]!!.jsonPrimitive.content)
        assertTrue(calls.none { it.first == "tap_node" || it.first == "tap" })
    }

    @Test fun theUserIsAskedBeforeAnythingIsResolvedBecauseAskingMovesTheScreen() {
        // Asking raises Hey Mike over the app, so a handle read beforehand
        // would be stale. Nothing may touch the screen until the answer is in.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Pay"))))
        var callsWhenAsked = -1
        val runner = WorkflowRunner(
            invokeTool = { name, args -> invoke(name, args) },
            isRevoked = { revoked },
            confirm = { callsWhenAsked = calls.size; WorkflowConfirmationOutcome.DENIED },
        )
        runBlocking {
            runner.run(
                definition("""{"id":"pay","action":"tap","target":{"text":"Pay"},"requiresConfirmation":true}"""),
                WorkflowRunner.Options(),
            )
        }
        assertEquals(0, callsWhenAsked)
    }

    @Test fun stopAbortsBetweenStepsAndReportsWhatHadAlreadyRun() {
        phone = FakeScreen(
            Page("com.example", listOf(Node("n1", text = "First"), Node("n2", text = "Second"))),
        )
        val runner = WorkflowRunner(
            invokeTool = { name, args ->
                if (name == "tap_node") revoked = true // Stop lands during the first step
                invoke(name, args)
            },
            isRevoked = { revoked },
        )
        val workflow = definition(
            """{"id":"one","action":"tap","target":{"text":"First"}}""",
            """{"id":"two","action":"tap","target":{"text":"Second"}}""",
        )
        val result = runBlocking { runner.run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.success)
        assertEquals("stopped", parse(result)["errorType"]!!.jsonPrimitive.content)
        assertEquals(1, calls.count { it.first == "tap_node" })
    }

    @Test fun aRunThatOutgrowsItsBudgetStopsCleanlyAndSaysWhereToResume() {
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Go"))))
        val workflow = definition(
            """{"id":"one","action":"tap","target":{"text":"Go"}}""",
            """{"id":"two","action":"tap","target":{"text":"Go"}}""",
        )
        var clock = 0L
        val runner = WorkflowRunner(
            invokeTool = { name, args -> clock += 4_000L; invoke(name, args) },
            isRevoked = { revoked },
            nowMs = { clock },
        )
        val result = runBlocking { runner.run(workflow, WorkflowRunner.Options(totalBudgetMs = 6_000L)) }
        assertFalse(result.success)
        val json = parse(result)
        assertEquals("budget_exhausted", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("two", json["failedStep"]!!.jsonPrimitive.content)
        assertEquals(listOf("one" to "done"), statuses(result))
    }

    @Test fun timeSpentWaitingForTheUserDoesNotCountAgainstTheRunsBudget() {
        // Otherwise a user who takes twenty seconds to tap Allow loses the run.
        phone = FakeScreen(Page("com.example", listOf(Node("n1", text = "Go"))))
        var clock = 0L
        val runner = WorkflowRunner(
            invokeTool = { name, args -> clock += 200L; invoke(name, args) },
            isRevoked = { revoked },
            confirm = { clock += 60_000L; WorkflowConfirmationOutcome.ALLOWED },
            nowMs = { clock },
        )
        val workflow = definition(
            """{"id":"ask","action":"tap","target":{"text":"Go"},"requiresConfirmation":true}""",
            """{"id":"after","action":"tap","target":{"text":"Go"}}""",
        )
        val result = runBlocking { runner.run(workflow, WorkflowRunner.Options(totalBudgetMs = 10_000L)) }
        assertTrue(result.text, result.success)
        assertEquals(listOf("ask" to "done", "after" to "done"), statuses(result))
    }
}
