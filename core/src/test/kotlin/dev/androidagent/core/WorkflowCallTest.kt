/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Workflow v2: generic `call` steps against a registered tool, with captured
 * outputs feeding later steps.
 *
 * The fake below answers device tools the way a backend does, so these
 * exercise dispatch, substitution and resume rather than any one backend.
 */
class WorkflowCallTest {

    @get:Rule val temp = TemporaryFolder()

    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var revoked = false
    private var confirmations = mutableListOf<WorkflowConfirmation>()
    private var confirmAnswer = WorkflowConfirmationOutcome.ALLOWED
    private val replies = mutableMapOf<String, String>()
    private val failures = mutableMapOf<String, String>()
    private var invokeOverride: ((String, JsonObject) -> ToolResult)? = null

    private val registry = WorkflowCallRegistry(
        mapOf(
            "recall_capability" to WorkflowCallMetadata("recall_capability", commits = false),
            "remember_capability" to WorkflowCallMetadata("remember_capability", commits = true),
            "device_status" to WorkflowCallMetadata("device_status", commits = false),
            "mixed_tool" to WorkflowCallMetadata(
                "mixed_tool",
                commits = true,
                readOnlyOperations = setOf("read", "describe"),
            ),
        ),
    )

    private fun runner(
        registry: WorkflowCallRegistry = this.registry,
        confirm: (suspend (WorkflowConfirmation) -> WorkflowConfirmationOutcome)? = null,
        nowMs: (() -> Long)? = null,
    ) = WorkflowRunner(
        invokeTool = { name, args -> invoke(name, args) },
        isRevoked = { revoked },
        confirm = confirm ?: { request -> confirmations += request; confirmAnswer },
        callRegistry = registry,
        nowMs = nowMs ?: System::currentTimeMillis,
    )

    private fun invoke(name: String, args: JsonObject): ToolResult {
        invokeOverride?.let { return it(name, args) }
        calls += name to args
        failures[name]?.let { return ToolResult(it, success = false) }
        replies[name]?.let { return ToolResult(it) }
        return when (name) {
            "read_ui" -> ToolResult(
                buildJsonObject {
                    put("ok", true)
                    put("observationId", "ui-1")
                    put("activePackage", "com.example.app")
                    put("nodes", buildJsonArray { })
                }.toString(),
            )
            "wait_for_change" -> ToolResult("{\"ok\":true}")
            else -> ToolResult("{\"ok\":true,\"tool\":\"$name\"}")
        }
    }

    private fun definition(vararg steps: String, id: String = "calls", pkg: String = "com.example.app") =
        WorkflowDefinition.parse(
            Json.parseToJsonElement(
                """{"id":"$id","package":"$pkg","steps":[${steps.joinToString(",")}]}""",
            ).jsonObject,
        )

    private fun parse(result: ToolResult): JsonObject = Json.parseToJsonElement(result.text).jsonObject

    // ---- v1 compatibility ----

    @Test fun existingActionsAndBehaviorStayUnchanged() {
        assertTrue(WorkflowAction.WIRE_NAMES.containsAll(listOf("open_app", "tap", "type_text", "call")))
        assertEquals(WorkflowAction.TAP, WorkflowAction.from("tap_node"))
        val definition = WorkflowDefinition.parse(
            Json.parseToJsonElement(
                """{"id":"w","package":"com.example.app","steps":[
                   {"id":"a","action":"tap_node","target":{"text":"Go"}},
                   {"id":"b","action":"read_ui"}]}""",
            ).jsonObject,
        )
        assertEquals(listOf(WorkflowAction.TAP, WorkflowAction.OBSERVE), definition.steps.map { it.action })
        // An empty registry changes nothing for definitions without calls.
        val result = runBlocking {
            runner(WorkflowCallRegistry.EMPTY).run(
                WorkflowDefinition.parse(
                    Json.parseToJsonElement(
                        """{"id":"w","package":"com.example.app","steps":[{"id":"a","action":"observe"}]}""",
                    ).jsonObject,
                ),
                WorkflowRunner.Options(),
            )
        }
        assertTrue(result.text, result.success)
    }

    @Test fun aCallStepParsesAndSurvivesARoundTrip() {
        val definition = definition(
            """{"id":"find","action":"call","tool":"recall_capability",
                 "arguments":{"package":"com.example.app","query":"timer"},
                 "output":"found","requiresConfirmation":true}""",
        )
        val step = definition.steps.single()
        assertEquals(WorkflowAction.CALL, step.action)
        assertEquals("recall_capability", step.callTool)
        assertEquals("found", step.output)
        assertTrue(step.requiresConfirmation)
        val stepAgain = WorkflowStep.parse(Json.parseToJsonElement(step.toJson().toString()).jsonObject, 0, "w")
        assertEquals(step, stepAgain)
        val again = WorkflowDefinition.parse(Json.parseToJsonElement(definition.toJson().toString()).jsonObject)
        assertEquals(definition.copy(source = null), again.copy(source = null))
        val outline = definition.outline().toString()
        assertTrue(outline, outline.contains("recall_capability"))
        assertTrue(outline, outline.contains("found"))
    }

    @Test fun malformedCallStepsAreRefusedWhereTheyAreWritten() {
        fun message(step: String): String {
            val error = runCatching { definition(step) }.exceptionOrNull()
            assertTrue("$error", error is WorkflowFormatException)
            return error!!.message!!
        }
        assertTrue(message("""{"id":"s","action":"call"}""").contains("needs \"tool\""))
        assertTrue(message("""{"id":"s","action":"call","tool":"shell"}""").contains("cannot run inside a workflow"))
        assertTrue(message("""{"id":"s","action":"call","tool":"run_workflow"}""").contains("cannot run inside"))
        // A plan runs on this same runner: a call step reaching it would nest
        // one run inside another and lose the budget and the ledger.
        assertTrue(message("""{"id":"s","action":"call","tool":"act_plan"}""").contains("cannot run inside"))
        assertTrue(message("""{"id":"s","action":"call","tool":"recall_capability","output":"9lives"}""").contains("usable output name"))
        assertTrue(message("""{"id":"s","action":"call","tool":"recall_capability","target":{"text":"x"}}""").contains("not a \"target\""))
        assertTrue(message("""{"id":"s","action":"tap","target":{"text":"x"},"tool":"recall_capability"}""").contains("belongs on a \"call\""))
        assertTrue(message("""{"id":"s","action":"tap","target":{"text":"x"},"output":"found"}""").contains("belongs on a \"call\""))
    }

    // ---- registry ----

    @Test fun anUnregisteredToolFailsBeforeAnythingIsDispatched() {
        val workflow = definition("""{"id":"s","action":"call","tool":"phone_dial","arguments":{"number":"123"}}""")
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        val json = parse(result)
        assertEquals("unknown_tool", json["errorType"]!!.jsonPrimitive.content)
        assertEquals(false, json["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull)
        assertTrue("Registered tools: recall_capability", json["message"]!!.jsonPrimitive.content.contains("recall_capability"))
        assertTrue("nothing may be dispatched", calls.isEmpty())
    }

    @Test fun aCallNeverAsksOnItsOwnButAnExplicitConfirmationStillApplies() {
        // Approval lives in the called tool: the registry carries no approval
        // flag, so a call without requiresConfirmation runs without asking.
        val workflow = definition("""{"id":"s","action":"call","tool":"remember_capability"}""")
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertTrue("nobody may be asked", confirmations.isEmpty())
        assertTrue(calls.any { it.first == "remember_capability" })

        // An explicit requiresConfirmation on a UI sequence still stops first.
        val guarded = definition(
            """{"id":"s","action":"call","tool":"remember_capability","requiresConfirmation":true}""",
        )
        val refused = WorkflowRunner(
            invokeTool = { name, args -> invoke(name, args) },
            isRevoked = { revoked },
            callRegistry = registry,
        )
        val denied = runBlocking { refused.run(guarded, WorkflowRunner.Options()) }
        assertFalse(denied.text, denied.success)
        assertEquals("confirmation_unavailable", parse(denied)["errorType"]!!.jsonPrimitive.content)

        confirmAnswer = WorkflowConfirmationOutcome.ALLOWED
        val allowed = runBlocking { runner().run(guarded, WorkflowRunner.Options()) }
        assertTrue(allowed.text, allowed.success)
        assertEquals("call", confirmations.single().action)
        assertTrue(confirmations.single().summary.contains("remember_capability"))
    }

    @Test fun aRegistryKeyMustMatchItsMetadataName() {
        val error = runCatching {
            WorkflowCallRegistry(mapOf("a" to WorkflowCallMetadata("b")))
        }.exceptionOrNull()
        assertTrue("$error", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("\"a\""))
        assertTrue(error.message!!.contains("\"b\""))
        // A matching map still builds.
        assertEquals(setOf("recall_capability"), WorkflowCallRegistry(mapOf("recall_capability" to WorkflowCallMetadata("recall_capability"))).names)
    }

    @Test fun callsDoNotWaitForTheScreenUnlessTheDefinitionSaysSo() {
        val defaulted = definition("""{"id":"s","action":"call","tool":"recall_capability"}""").steps.single()
        assertFalse(defaulted.waitForChange)
        val explicit = definition(
            """{"id":"s","action":"call","tool":"recall_capability","waitForChange":true}""",
        ).steps.single()
        assertTrue(explicit.waitForChange)
        // Existing actions keep their own defaults.
        val tap = definition("""{"id":"s","action":"tap","target":{"text":"Go"}}""").steps.single()
        assertTrue(tap.waitForChange)
        val roundTripped = WorkflowDefinition.parse(
            Json.parseToJsonElement(
                definition("""{"id":"s","action":"call","tool":"recall_capability","waitForChange":true}""")
                    .toJson().toString(),
            ).jsonObject,
        )
        assertTrue(roundTripped.steps.single().waitForChange)

        val result = runBlocking {
            runner().run(
                definition("""{"id":"s","action":"call","tool":"recall_capability"}"""),
                WorkflowRunner.Options(),
            )
        }
        assertTrue(result.text, result.success)
        assertTrue("an API-only call settles nothing", calls.none { it.first == "wait_for_change" })

        calls.clear()
        val waited = runBlocking {
            runner().run(
                definition("""{"id":"s","action":"call","tool":"recall_capability","waitForChange":true}"""),
                WorkflowRunner.Options(),
            )
        }
        assertTrue(waited.text, waited.success)
        assertTrue(calls.any { it.first == "wait_for_change" })
    }

    // ---- substitution ----

    @Test fun parametersSubstituteRecursivelyAndKeepTheirType() {
        val definition = WorkflowDefinition.parse(
            Json.parseToJsonElement(
                """{"id":"w","package":"com.example.app",
                   "parameters":{"count":{"type":"integer"},"label":{"type":"string"}},
                   "steps":[{"id":"s","action":"call","tool":"remember_capability",
                     "arguments":{"n":"{{count}}","msg":"id-{{count}}-{{label}}",
                       "deep":{"list":["{{count}}",true,"x{{label}}"]}}}]}""",
            ).jsonObject,
        )
        val bound = definition.bind(Json.parseToJsonElement("""{"count":7,"label":"hi"}""").jsonObject)
        val result = runBlocking { runner().run(bound, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        val args = calls.single { it.first == "remember_capability" }.second
        val n = args["n"]!!.jsonPrimitive
        assertFalse(n.isString)
        assertEquals("7", n.content)
        assertEquals("id-7-hi", args["msg"]!!.jsonPrimitive.content)
        val list = args["deep"]!!.jsonObject["list"]!!.jsonArray
        assertFalse(list[0].jsonPrimitive.isString)
        assertEquals("7", list[0].jsonPrimitive.content)
        assertEquals("xhi", list[2].jsonPrimitive.content)
    }

    @Test fun aCapturedOutputFeedsTheNextCallWithItsType() {
        replies["recall_capability"] = """{"items":[{"id":7,"name":"Wifi"}]}"""
        val workflow = definition(
            """{"id":"first","action":"call","tool":"recall_capability",
                 "arguments":{"package":"com.example.app"},"output":"found"}""",
            """{"id":"second","action":"call","tool":"remember_capability",
                 "arguments":{"id":"{{outputs.found.items.0.id}}",
                   "label":"saw {{outputs.found.items.0.name}} twice: {{outputs.found.items.0.name}}"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        val args = calls.single { it.first == "remember_capability" }.second
        val id = args["id"]!!.jsonPrimitive
        assertFalse("a whole placeholder keeps its type", id.isString)
        assertEquals("7", id.content)
        assertEquals("saw Wifi twice: Wifi", args["label"]!!.jsonPrimitive.content)
        val json = parse(result)
        assertEquals(7, json["outputs"]!!.jsonObject["found"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.intOrNull)
        val notes = json["steps"]!!.jsonArray.map { it.jsonObject["note"]?.jsonPrimitive?.contentOrNull }
        assertTrue(notes.any { it == "captured output \"found\"" })
    }

    @Test fun plainTextResultsCaptureAsText() {
        replies["device_status"] = "Accessibility: connected"
        val workflow = definition(
            """{"id":"first","action":"call","tool":"device_status","output":"status"}""",
            """{"id":"second","action":"call","tool":"remember_capability",
                 "arguments":{"note":"was: {{outputs.status}}"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertTrue(result.text, result.success)
        assertEquals(
            "was: Accessibility: connected",
            calls.single { it.first == "remember_capability" }.second["note"]!!.jsonPrimitive.content,
        )
    }

    @Test fun aMissingOutputFailsClearlyBeforeDispatch() {
        val workflow = definition(
            """{"id":"first","action":"call","tool":"recall_capability","output":"found"}""",
            """{"id":"second","action":"call","tool":"remember_capability",
                 "arguments":{"id":"{{outputs.nope}}"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        val json = parse(result)
        assertEquals("unknown_output", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.contains("\"nope\""))
        assertEquals(false, json["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull)
        assertTrue("the second call must not dispatch", calls.none { it.first == "remember_capability" })
        // The first call committed, so its output is still handed back for a resume.
        assertTrue(parse(result)["outputs"]!!.jsonObject.containsKey("found"))
    }

    // ---- operation-aware commit reporting ----

    @Test fun aReadOnlyOperationFailureReportsNothingCommitted() {
        failures["mixed_tool"] = """{"ok":false,"errorType":"refused"}"""
        fun committed(argsJson: String): Boolean {
            calls.clear()
            val workflow = definition(
                """{"id":"s","action":"call","tool":"mixed_tool","arguments":$argsJson}""",
            )
            val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
            assertFalse(result.text, result.success)
            return parse(result)["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull == true
        }
        assertFalse(committed("""{"operation":"read"}"""))
        assertFalse(committed("""{"operation":"describe"}"""))
        assertTrue("a writing operation stays conservative", committed("""{"operation":"delete"}"""))
        assertTrue("a missing operation stays conservative", committed("""{}"""))
        assertTrue("a non-string operation stays conservative", committed("""{"operation":42}"""))
    }

    @Test fun aNonCommittingToolNeverReportsCommittedWhateverTheOperation() {
        failures["recall_capability"] = """{"ok":false,"errorType":"refused"}"""
        val workflow = definition(
            """{"id":"s","action":"call","tool":"recall_capability","arguments":{"operation":"delete"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        assertEquals(false, parse(result)["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test fun anOutputSubstitutedOperationIsClassifiedFromTheResolvedArgs() {
        var next = "read"
        invokeOverride = { name, args ->
            calls += name to args
            if (name == "mixed_tool" && calls.count { it.first == "mixed_tool" } == 1) {
                ToolResult("""{"next":"$next"}""")
            } else {
                ToolResult("""{"ok":false,"errorType":"refused"}""", success = false)
            }
        }
        val workflow = definition(
            """{"id":"first","action":"call","tool":"mixed_tool","output":"plan"}""",
            """{"id":"second","action":"call","tool":"mixed_tool",
                 "arguments":{"operation":"{{outputs.plan.next}}"}}""",
        )
        fun committed(): Boolean {
            calls.clear()
            val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
            assertFalse(result.text, result.success)
            return parse(result)["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull == true
        }
        assertFalse("resolved to a read-only operation", committed())
        next = "delete"
        assertTrue("resolved to a writing operation", committed())
        assertEquals(
            "the dispatched args really carried the resolved value",
            "delete",
            calls.last { it.first == "mixed_tool" }.second["operation"]!!.jsonPrimitive.content,
        )
    }

    // ---- stop, budget, resume, caps ----

    @Test fun stopAbortsBetweenCallsAndReportsTheCommittedPrefix() {
        invokeOverride = { name, args ->
            calls += name to args
            if (name == "remember_capability") revoked = true
            ToolResult("{\"ok\":true}")
        }
        val workflow = definition(
            """{"id":"one","action":"call","tool":"remember_capability","output":"first"}""",
            """{"id":"two","action":"call","tool":"remember_capability"}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        assertEquals("stopped", parse(result)["errorType"]!!.jsonPrimitive.content)
        assertEquals(1, calls.count { it.first == "remember_capability" })
    }

    @Test fun aRunThatOutgrowsItsBudgetNeverDispatchesTheNextCall() {
        var clock = 0L
        invokeOverride = { name, args ->
            clock += 4_000L
            calls += name to args
            ToolResult("{\"ok\":true}")
        }
        val workflow = definition(
            """{"id":"one","action":"call","tool":"remember_capability"}""",
            """{"id":"two","action":"call","tool":"remember_capability"}""",
        )
        val result = runBlocking {
            runner(nowMs = { clock }).run(workflow, WorkflowRunner.Options(totalBudgetMs = 5_000L))
        }
        assertFalse(result.text, result.success)
        val json = parse(result)
        assertEquals("budget_exhausted", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("two", json["failedStep"]!!.jsonPrimitive.content)
        assertEquals(1, calls.count { it.first == "remember_capability" })
    }

    @Test fun resumeNeverRerunsTheCommittedPrefixAndReusesItsOutputs() {
        val workflow = definition(
            """{"id":"first","action":"call","tool":"remember_capability","output":"first"}""",
            """{"id":"second","action":"call","tool":"remember_capability",
                 "arguments":{"v":"{{outputs.first.code}}"}}""",
        )
        var secondFails = true
        invokeOverride = { name, args ->
            calls += name to args
            if (name == "remember_capability" && calls.count { it.first == "remember_capability" } == 1) {
                ToolResult("""{"code":"A123"}""")
            } else if (secondFails) {
                ToolResult("""{"ok":false,"errorType":"refused"}""", success = false)
            } else {
                ToolResult("""{"ok":true}""")
            }
        }
        val failed = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(failed.text, failed.success)
        val failedJson = parse(failed)
        assertEquals("second", failedJson["failedStep"]!!.jsonPrimitive.content)
        assertEquals(true, failedJson["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull)
        val resumeArgs = failedJson["resume"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("second", resumeArgs["startAt"]!!.jsonPrimitive.content)
        assertEquals("A123", resumeArgs["outputs"]!!.jsonObject["first"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        calls.clear()
        secondFails = false
        val resumed = runBlocking {
            runner().run(
                workflow,
                WorkflowRunner.Options(
                    mode = "resume",
                    startAt = resumeArgs["startAt"]!!.jsonPrimitive.content,
                    outputs = resumeArgs["outputs"]!!.jsonObject,
                ),
            )
        }
        assertTrue(resumed.text, resumed.success)
        val retried = calls.filter { it.first == "remember_capability" }
        assertEquals("the committed prefix must not re-run", 1, retried.size)
        assertEquals("A123", retried.single().second["v"]!!.jsonPrimitive.content)
        val statuses = parse(resumed)["steps"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["status"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("first" to "skipped", "second" to "done"), statuses)
    }

    @Test fun anOversizedResultFailsInsteadOfStoringTruncatedJson() {
        replies["remember_capability"] = "\"" + "x".repeat(WorkflowCallRegistry.MAX_CAPTURED_OUTPUT_CHARS + 1) + "\""
        val workflow = definition(
            """{"id":"s","action":"call","tool":"remember_capability","output":"huge"}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        val json = parse(result)
        assertEquals("output_too_large", json["errorType"]!!.jsonPrimitive.content)
        assertEquals(true, json["stepMayAlreadyHaveRun"]!!.jsonPrimitive.booleanOrNull)
        assertFalse("nothing oversized may be persisted", json.containsKey("outputs"))
    }

    // ---- gateway wiring ----

    private val routerCalls = mutableListOf<Pair<String, JsonObject>>()

    private val router = object : DeviceToolGateway {
        override val definitions: List<ToolDefinition> = emptyList()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
            routerCalls += name to arguments
            return ToolResult("{\"ok\":true,\"recall\":\"${arguments["query"]?.jsonPrimitive?.contentOrNull}\"}")
        }
    }

    private fun callLibrary(): WorkflowLibrary {
        val dir = File(temp.root, "call-definitions").apply { mkdirs() }
        File(dir, "lookup.json").writeText(
            """{"id":"lookup","package":"com.example.app","parameters":{"q":{"type":"string"}},
               "steps":[{"id":"find","action":"call","tool":"recall_capability",
                 "arguments":{"query":"{{q}}"},"output":"found"}]}""",
        )
        File(dir, "chain.json").writeText(
            """{"id":"chain","package":"com.example.app",
               "steps":[{"id":"first","action":"call","tool":"recall_capability",
                 "arguments":{"package":"com.example.app"},"output":"first"},
                {"id":"second","action":"call","tool":"recall_capability",
                 "arguments":{"v":"{{outputs.first.code}}"}}]}""",
        )
        return WorkflowLibrary(dir)
    }

    private fun gateway(registry: WorkflowCallRegistry = this.registry) =
        WorkflowToolGateway(WorkflowStore(temp.newFolder()), { router }, callLibrary(), callRegistry = registry)
            .also { it.beginRun("run-1", temp.newFolder()) }

    @Test fun theGatewayDispatchesCallsThroughItsRegistry() {
        val result = runBlocking {
            gateway().invoke(
                "workflow_runner",
                Json.parseToJsonElement("""{"workflow":"lookup","mode":"run","params":{"q":"wifi"}}""").jsonObject,
            )
        }
        assertTrue(result.text, result.success)
        assertEquals("wifi", routerCalls.single { it.first == "recall_capability" }.second["query"]!!.jsonPrimitive.content)
        assertTrue(parse(result)["outputs"]!!.jsonObject.containsKey("found"))
    }

    @Test fun theGatewayRefusesUnregisteredToolsWithoutDispatching() {
        val result = runBlocking {
            gateway(WorkflowCallRegistry.EMPTY).invoke(
                "workflow_runner",
                Json.parseToJsonElement("""{"workflow":"lookup","mode":"run","params":{"q":"wifi"}}""").jsonObject,
            )
        }
        assertFalse(result.text, result.success)
        assertEquals("unknown_tool", Json.parseToJsonElement(result.text).jsonObject["errorType"]!!.jsonPrimitive.content)
        assertTrue(routerCalls.isEmpty())
    }

    @Test fun theGatewayRefusesOutputsOfTheWrongShape() {
        val result = runBlocking {
            gateway().invoke(
                "workflow_runner",
                Json.parseToJsonElement("""{"workflow":"lookup","params":{"q":"wifi"},"outputs":["found"]}""").jsonObject,
            )
        }
        assertFalse(result.text, result.success)
        assertEquals(
            "workflow_outputs_invalid",
            Json.parseToJsonElement(result.text).jsonObject["errorType"]!!.jsonPrimitive.content,
        )
    }

    @Test fun resumeOutputsRideOnlyInAResume() {
        // Non-empty outputs outside a resume are refused before anything runs.
        val refused = runBlocking {
            gateway().invoke(
                "workflow_runner",
                Json.parseToJsonElement(
                    """{"workflow":"lookup","mode":"run","params":{"q":"wifi"},
                        "outputs":{"first":{"code":"A123"}}}""",
                ).jsonObject,
            )
        }
        assertFalse(refused.text, refused.success)
        assertEquals(
            "workflow_outputs_invalid",
            Json.parseToJsonElement(refused.text).jsonObject["errorType"]!!.jsonPrimitive.content,
        )
        assertTrue(routerCalls.isEmpty())

        // Empty outputs change nothing.
        val fresh = runBlocking {
            gateway().invoke(
                "workflow_runner",
                Json.parseToJsonElement(
                    """{"workflow":"lookup","mode":"run","params":{"q":"wifi"},"outputs":{}}""",
                ).jsonObject,
            )
        }
        assertTrue(fresh.text, fresh.success)
    }

    @Test fun theGatewayBoundsResumeOutputsBeforeAnythingRuns() {
        fun resumeWith(outputs: JsonObject): ToolResult = runBlocking {
            gateway().invoke(
                "workflow_runner",
                buildJsonObject {
                    put("workflow", "chain")
                    put("mode", "resume")
                    put("startAt", "second")
                    put("outputs", outputs)
                },
            )
        }
        fun errorType(result: ToolResult): String =
            Json.parseToJsonElement(result.text).jsonObject["errorType"]!!.jsonPrimitive.content

        val tooMany = buildJsonObject { repeat(WorkflowCallRegistry.MAX_OUTPUT_BINDINGS + 1) { put("o$it", it) } }
        assertEquals("workflow_outputs_invalid", errorType(resumeWith(tooMany)))

        val badName = buildJsonObject { put("9lives", 1) }
        assertEquals("workflow_outputs_invalid", errorType(resumeWith(badName)))

        val big = "x".repeat(WorkflowCallRegistry.MAX_CAPTURED_OUTPUT_CHARS + 1)
        assertEquals("workflow_outputs_invalid", errorType(resumeWith(buildJsonObject { put("big", big) })))

        // Each binding fits its own limit, but together they pass the total.
        val third = "y".repeat(WorkflowCallRegistry.MAX_TOTAL_OUTPUT_CHARS / 3)
        val heavy = buildJsonObject { put("a", third); put("b", third); put("c", third) }
        assertEquals("workflow_outputs_invalid", errorType(resumeWith(heavy)))

        assertTrue("nothing may run for rejected resume state", routerCalls.isEmpty())

        // A bounded resume is forwarded to the resumed steps.
        val ok = resumeWith(buildJsonObject { put("first", buildJsonObject { put("code", "A123") }) })
        assertTrue(ok.text, ok.success)
        val retried = routerCalls.filter { it.first == "recall_capability" }
        assertEquals("the committed prefix must not re-run", 1, retried.size)
        assertEquals("A123", retried.single().second["v"]!!.jsonPrimitive.content)
    }

    @Test fun aResumeWithoutAStartingStepIsRefusedBeforeAnythingRuns() {
        fun resumeWithoutStartAt(extra: String): ToolResult = runBlocking {
            gateway().invoke(
                "workflow_runner",
                Json.parseToJsonElement("""{"workflow":"chain","mode":"resume"$extra}""").jsonObject,
            )
        }
        for (result in listOf(resumeWithoutStartAt(""), resumeWithoutStartAt(""","startAt":"   """"))) {
            assertFalse(result.text, result.success)
            val json = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("workflow_start_required", json["errorType"]!!.jsonPrimitive.content)
            assertEquals(true, json["nothingRan"]!!.jsonPrimitive.booleanOrNull)
        }
        assertTrue("nothing may be dispatched", routerCalls.isEmpty())
    }

    @Test fun captureRefusesAnOutputThatWouldOverflowTheTotal() {
        // Each result fits its own limit; the three together do not fit 16000.
        val payload = "\"" + "v" + "x".repeat(6_000) + "\""
        invokeOverride = { name, args ->
            calls += name to args
            ToolResult(payload)
        }
        val workflow = definition(
            """{"id":"one","action":"call","tool":"recall_capability","output":"o1"}""",
            """{"id":"two","action":"call","tool":"recall_capability","output":"o2"}""",
            """{"id":"three","action":"call","tool":"recall_capability","output":"o3"}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        val json = parse(result)
        assertEquals("output_too_large", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("three", json["failedStep"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.contains("together"))
        // Nothing partial is stored: the reply carries only what fit.
        val outputs = json["outputs"]!!.jsonObject
        assertTrue(outputs.containsKey("o1"))
        assertTrue(outputs.containsKey("o2"))
        assertFalse(outputs.containsKey("o3"))
        assertTrue("the overflowing total stays resumable", outputs.toString().length <= WorkflowCallRegistry.MAX_TOTAL_OUTPUT_CHARS)
    }

    @Test fun aMultiOutputResumeWithinTheTotalIsAccepted() {
        val ok = runBlocking {
            gateway().invoke(
                "workflow_runner",
                buildJsonObject {
                    put("workflow", "chain")
                    put("mode", "resume")
                    put("startAt", "second")
                    put(
                        "outputs",
                        buildJsonObject {
                            put("first", buildJsonObject { put("code", "A123") })
                            put("extra", buildJsonObject { put("n", 1) })
                        },
                    )
                },
            )
        }
        assertTrue(ok.text, ok.success)
        assertEquals("A123", routerCalls.single { it.first == "recall_capability" }.second["v"]!!.jsonPrimitive.content)
    }

    @Test fun aCallOutlineAndSummaryShowWhatTheCallWillDo() {
        val entry = definition(
            """{"id":"find","action":"call","tool":"recall_capability",
                 "arguments":{"package":"com.example.app","query":"timer"},"output":"found"}""",
        ).outline().single().jsonObject
        assertEquals("recall_capability", entry["tool"]!!.jsonPrimitive.content)
        assertEquals("timer", entry["arguments"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals("found", entry["output"]!!.jsonPrimitive.content)

        // The failure names the same arguments a review would show.
        val workflow = definition(
            """{"id":"dial","action":"call","tool":"phone_dial","arguments":{"number":"123"}}""",
        )
        val result = runBlocking { runner().run(workflow, WorkflowRunner.Options()) }
        assertFalse(result.text, result.success)
        assertTrue(parse(result)["failedStepDoes"]!!.jsonPrimitive.content.contains("\"number\":\"123\""))
    }
}
