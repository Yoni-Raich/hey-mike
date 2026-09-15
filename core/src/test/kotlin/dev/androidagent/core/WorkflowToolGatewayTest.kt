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
    private val arguments = mutableListOf<Pair<String, JsonObject>>()

    private val router = object : DeviceToolGateway {
        override val definitions: List<ToolDefinition> = emptyList()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
            calls += name
            this@WorkflowToolGatewayTest.arguments += name to arguments
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

    // ---- parameters ----

    private fun timerLibrary(): WorkflowLibrary {
        val dir = File(temp.root, "timers").apply { mkdirs() }
        File(dir, "timer.json").writeText(
            """{"id":"timer","package":"com.google.android.deskclock","description":"Start a timer.",
               "parameters":{"seconds":{"type":"integer","min":1,"max":86400},
                             "label":{"type":"string","default":"Hey Mike"}},
               "steps":[{"id":"start","action":"open_intent","waitForChange":false,
                         "arguments":{"action":"android.intent.action.SET_TIMER",
                           "extras":{"android.intent.extra.alarm.LENGTH":"{{seconds}}",
                                     "android.intent.extra.alarm.MESSAGE":"{{label}} ({{seconds}}s)",
                                     "android.intent.extra.alarm.SKIP_UI":true}}}]}""",
        )
        return WorkflowLibrary(dir)
    }

    private fun runTimer(params: String?): ToolResult = runBlocking {
        gateway(timerLibrary()).invoke(
            "workflow_runner",
            Json.parseToJsonElement(
                """{"workflow":"timer","mode":"run"${params?.let { ",\"params\":$it" } ?: ""}}""",
            ).jsonObject,
        )
    }

    @Test fun oneWorkflowRunsWithWhateverValueTheCallerGivesIt() {
        // A 10-minute timer and a 5-minute timer are one definition, not two.
        val result = runTimer("""{"seconds":600}""")
        assertTrue(result.text, result.success)
        val intent = arguments.single { it.first == "open_intent" }.second
        val extras = intent["extras"]!!.jsonObject
        // A whole placeholder keeps the value's type, so the clock reads an int.
        assertEquals("600", extras["android.intent.extra.alarm.LENGTH"]!!.jsonPrimitive.content)
        assertFalse(extras["android.intent.extra.alarm.LENGTH"]!!.jsonPrimitive.isString)
        // Inside longer text it is spliced in, and a default fills what was not given.
        assertEquals("Hey Mike (600s)", extras["android.intent.extra.alarm.MESSAGE"]!!.jsonPrimitive.content)
    }

    @Test fun aNumberTheModelQuotedIsStillANumber() {
        assertTrue(runTimer("""{"seconds":"300"}""").success)
        val extras = arguments.single { it.first == "open_intent" }.second["extras"]!!.jsonObject
        assertFalse(extras["android.intent.extra.alarm.LENGTH"]!!.jsonPrimitive.isString)
    }

    @Test fun aMissingWrongOrUnknownValueIsRefusedBeforeThePhoneIsTouched() {
        for ((params, why) in listOf(
            null to "is required",
            """{"seconds":0}""" to "at least 1",
            """{"seconds":"ten"}""" to "whole number",
            """{"seconds":60,"volume":3}""" to "no parameter \"volume\"",
        )) {
            calls.clear()
            val result = runTimer(params)
            assertFalse(result.text, result.success)
            val json = parse(result)
            assertEquals("workflow_params_invalid", json["errorType"]!!.jsonPrimitive.content)
            assertTrue(result.text, json["message"]!!.jsonPrimitive.content.contains(why))
            assertTrue("nothing may run for $params", calls.isEmpty())
        }
    }

    @Test fun anEmptyPackageListingDoesNotClaimNothingIsInstalled() {
        val result = runBlocking {
            gateway(timerLibrary()).invoke(
                "workflow_runner",
                buildJsonObject { put("mode", "list"); put("package", "com.google.android.apps.tasks") },
            )
        }
        val hint = parse(result)["hint"]!!.jsonPrimitive.content
        assertTrue(hint, hint.contains("Other workflows are installed"))
    }

    @Test fun theListingSaysWhatValuesAWorkflowTakes() {
        val result = runBlocking {
            gateway(timerLibrary()).invoke("workflow_runner", buildJsonObject { put("mode", "list") })
        }
        val entry = parse(result)["workflows"]!!.jsonArray.single().jsonObject
        assertEquals("integer", entry["parameters"]!!.jsonObject["seconds"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

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
