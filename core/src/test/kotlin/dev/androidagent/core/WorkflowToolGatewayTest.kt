package dev.androidagent.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkflowToolGatewayTest {

    @get:Rule val temp = TemporaryFolder()

    private val calls = mutableListOf<String>()

    private val router = object : DeviceToolGateway {
        override val definitions: List<ToolDefinition> = emptyList()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
            calls += name
            return if (name == "read_ui") {
                ToolResult(
                    buildJsonObject {
                        put("ok", true)
                        put("observationId", "ui-1")
                        put("activePackage", "com.android.settings")
                        put("nodes", buildJsonArray { })
                    }.toString(),
                )
            } else {
                ToolResult("$name ok")
            }
        }
    }

    private fun library(): WorkflowLibrary {
        val dir = File(temp.root, "definitions").apply { mkdirs() }
        File(dir, "wireless-debugging.json").writeText(
            """{"id":"wireless-debugging","package":"com.android.settings","description":"turn it on",
               "steps":[{"id":"open","action":"open_app","verify":{"package":"com.android.settings"}},
                        {"id":"enable","action":"tap","target":{"text":"Wireless debugging"},
                         "requiresConfirmation":true}]}""",
        )
        return WorkflowLibrary(dir)
    }

    private fun gateway(library: WorkflowLibrary? = library()) =
        WorkflowToolGateway(WorkflowStore(temp.newFolder()), { router }, library).also {
            it.beginRun("run-1", temp.newFolder())
        }

    private fun parse(result: ToolResult): JsonObject = Json.parseToJsonElement(result.text).jsonObject

    @Test fun theRunnerIsAdvertisedAsOneCallWithARunnableWorkflow() {
        val definition = gateway().definitions.single { it.name == "workflow_runner" }
        val properties = definition.inputSchema["properties"]!!.jsonObject
        assertTrue(properties.keys.containsAll(setOf("workflow", "mode", "startAt")))
        // Nothing is required: mode="list" with no arguments has to work, or
        // the model cannot discover what it may run.
        assertTrue(definition.inputSchema["required"]!!.jsonArray.isEmpty())
    }

    @Test fun withoutALibraryTheToolIsNotAdvertisedAtAll() {
        // Codex binds the tool list once per thread, so advertising a tool that
        // can only ever refuse would cost the model a turn to find that out.
        val names = gateway(library = null).definitions.map { it.name }
        assertFalse(names.contains("workflow_runner"))
        assertTrue(names.contains("run_workflow"))
    }

    @Test fun listingNamesEveryWorkflowAndWhichOnesStopToAsk() {
        val json = parse(runBlocking { gateway().invoke("workflow_runner", buildJsonObject { put("mode", "list") }) })
        val entry = json["workflows"]!!.jsonArray.single().jsonObject
        assertEquals("wireless-debugging", entry["workflow"]!!.jsonPrimitive.content)
        assertEquals("turn it on", entry["description"]!!.jsonPrimitive.content)
        assertTrue(entry["asksBeforeSensitiveSteps"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("nothing may be dispatched", calls.isEmpty())
    }

    @Test fun describePrintsTheStepsAndRunsNothing() {
        val json = parse(
            runBlocking {
                gateway().invoke(
                    "workflow_runner",
                    buildJsonObject { put("workflow", "wireless-debugging"); put("mode", "describe") },
                )
            },
        )
        assertEquals(2, json["steps"]!!.jsonArray.size)
        assertEquals("open", json["steps"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(calls.isEmpty())
    }

    @Test fun anUnknownWorkflowIsRefusedWithTheNamesThatExist() {
        val json = parse(
            runBlocking {
                gateway().invoke("workflow_runner", buildJsonObject { put("workflow", "nope") })
            },
        )
        assertEquals("workflow_not_found", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.contains("wireless-debugging"))
        assertTrue(calls.isEmpty())
    }

    @Test fun anUnknownModeIsRefusedBeforeThePhoneIsTouched() {
        val json = parse(
            runBlocking {
                gateway().invoke(
                    "workflow_runner",
                    buildJsonObject { put("workflow", "wireless-debugging"); put("mode", "delete") },
                )
            },
        )
        assertEquals("unknown_mode", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(calls.isEmpty())
    }

    @Test fun aRunningWorkflowShowsTheControlBannerAndListingDoesNot() {
        val gateway = gateway()
        assertTrue(gateway.needsControl("workflow_runner"))
        assertTrue(gateway.needsControl("run_workflow"))
        assertFalse(gateway.needsControl("list_workflows"))
    }

    @Test fun stopRefusesTheToolWithoutTouchingTheDevice() {
        val gateway = gateway()
        gateway.revoke()
        runCatching { runBlocking { gateway.invoke("workflow_runner", buildJsonObject { put("mode", "list") }) } }
            .onSuccess { error("a revoked gateway must refuse") }
            .onFailure { assertTrue(it is IllegalStateException) }
        assertTrue(calls.isEmpty())
    }

    @Test fun theStatusLineCountsRunnableWorkflowsSeparatelyFromSavedSequences() {
        assertEquals("Workflows: 1 runnable", gateway().statusLine())
        assertEquals("Workflows: none saved", gateway(library = null).statusLine())
    }

    @Test fun aSensitiveStepIsRefusedWhenTheHostWiredNoWayToAsk() {
        // The default confirm hook. A host that cannot ask must never have a
        // step marked requiresConfirmation run on its behalf.
        val result = runBlocking {
            gateway().invoke(
                "workflow_runner",
                buildJsonObject { put("workflow", "wireless-debugging"); put("startAt", "enable") },
            )
        }
        assertFalse(result.success)
        assertEquals("confirmation_unavailable", parse(result)["errorType"]!!.jsonPrimitive.content)
    }
}
