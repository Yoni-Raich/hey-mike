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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `act_plan`: one observation, one call.
 *
 * The behaviour these cover is the reason the tool exists — a plan the model
 * wrote from one `read_ui` runs without a turn per action — and the two ways it
 * could go wrong: a plan that carries ids instead of labels (stale by the second
 * step) and a plan that half ran (the prefix must never be repeated).
 */
class ActPlanTest {

    @get:Rule val temp = TemporaryFolder()

    /** Every call the plan dispatched at the device, in order. */
    private val calls = mutableListOf<Pair<String, JsonObject>>()

    private var activePackage: String? = "com.whatsapp"

    /** Tool names that must report failure, for the half-ran cases. */
    private var failing: Set<String> = emptySet()

    /** Fail `read_ui` from this call onwards; the first one is the app lookup. */
    private var readFailsFromCall: Int = Int.MAX_VALUE

    /** Runs before each dispatch, so a test can land Stop mid-plan. */
    private var onCall: (String) -> Unit = {}

    private val router = object : DeviceToolGateway {
        override val definitions: List<ToolDefinition> = emptyList()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = true
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
            calls += name to arguments
            onCall(name)
            if (name in failing) return ToolResult("$name refused", success = false)
            return when (name) {
                "read_ui" ->
                    if (calls.count { it.first == "read_ui" } >= readFailsFromCall) {
                        throw ToolNotServiceable("a11y_unavailable", "The accessibility service is not running.")
                    } else {
                        ToolResult(screen())
                    }
                else -> ToolResult("$name ok")
            }
        }
    }

    /** A chat: a message field and a Send button, the sequence the model can see at once. */
    private fun screen(): String = buildJsonObject {
        put("ok", true)
        put("observationId", "ui-7")
        activePackage?.let { put("activePackage", it) }
        put(
            "nodes",
            buildJsonArray {
                addJsonObject {
                    put("nodeId", "n4")
                    put("text", "Message")
                    put("class", "android.widget.EditText")
                    put("clickable", true)
                    put("enabled", true)
                }
                addJsonObject {
                    put("nodeId", "n9")
                    put("contentDescription", "Send")
                    put("class", "android.widget.ImageButton")
                    put("clickable", true)
                    put("enabled", true)
                }
            },
        )
    }.toString()

    private fun gateway() = WorkflowToolGateway(WorkflowStore(temp.newFolder()), { router }, library = null)
        .also { it.beginRun("run-1", temp.newFolder()) }

    private fun plan(arguments: JsonObject): JsonObject =
        Json.parseToJsonElement(runBlocking { gateway().invoke("act_plan", arguments) }.text).jsonObject

    /** Focus the field, type, press Send: three model turns today, one call here. */
    private fun sendSteps(): JsonArray = buildJsonArray {
        addJsonObject {
            put("id", "focus")
            put("action", "tap")
            putJsonObject("target") { put("class", "EditText") }
        }
        addJsonObject {
            put("id", "write")
            put("action", "type_text")
            putJsonObject("target") { put("class", "EditText") }
            put("text", "on my way")
        }
        addJsonObject {
            put("id", "send")
            put("action", "tap")
            putJsonObject("target") { put("contentDescription", "Send") }
        }
    }

    private fun backStep(): JsonArray = buildJsonArray {
        addJsonObject { put("action", "key"); putJsonObject("arguments") { put("keycode", "BACK") } }
    }

    private fun dispatched(): List<String> = calls.map { it.first }

    // ---- the whole point ----

    @Test fun oneCallRunsTheWholeSequenceTheObservationAlreadyShowed() {
        val json = plan(buildJsonObject { put("steps", sendSteps()) })
        assertTrue(json.toString(), json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, json["ranSteps"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            listOf("focus", "write", "send"),
            json["steps"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        )
        // Each step acted on the node it resolved, in order, by handle.
        assertEquals(listOf("tap_node", "set_text", "tap_node"), dispatched().filter { it != "read_ui" && it != "wait_for_change" })
        assertEquals("on my way", calls.single { it.first == "set_text" }.second["text"]!!.jsonPrimitive.content)
    }

    @Test fun theReplyEndsOnTheScreenItLandedOn() {
        // Without this the call that saved three round trips costs one back to
        // find out where it ended up.
        val json = plan(buildJsonObject { put("steps", sendSteps()) })
        val observation = json["observation"]!!.jsonObject
        assertEquals("ui-7", observation["observationId"]!!.jsonPrimitive.content)
        // The model never saw the runner's own reads, so "unchanged since
        // revision N" would name a list it does not hold.
        assertTrue(calls.last().let { it.first == "read_ui" && it.second["force"]!!.jsonPrimitive.content.toBoolean() })
    }

    @Test fun theTrailingObservationCanBeDeclined() {
        val json = plan(buildJsonObject { put("steps", sendSteps()); put("observe", false) })
        assertNull(json["observation"])
    }

    @Test fun aReadThatFailsAfterwardsDoesNotUnreportTheStepsThatRan() {
        // The step ran. A reply that dropped the observation silently would
        // read as if it had not, and the model would press BACK twice.
        readFailsFromCall = 2
        val json = plan(buildJsonObject { put("steps", backStep()) })
        assertTrue(json.toString(), json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, json["ranSteps"]!!.jsonPrimitive.content.toInt())
        val observation = json["observation"]!!.jsonObject
        assertFalse(observation["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(observation["note"]!!.jsonPrimitive.content.contains("Do not repeat them"))
    }

    @Test fun stopReportsWhatRanAndReadsNothingMore() {
        // Stop revokes new calls before it interrupts, so a stopped plan does
        // not go back to the phone for a closing observation.
        val gateway = gateway()
        onCall = { name -> if (name == "wait_for_change") gateway.revoke() }
        val json = Json.parseToJsonElement(
            runBlocking { gateway.invoke("act_plan", buildJsonObject { put("steps", sendSteps()) }) }.text,
        ).jsonObject
        assertEquals("stopped", json["errorType"]!!.jsonPrimitive.content)
        assertEquals(1, json["ranSteps"]!!.jsonPrimitive.content.toInt())
        assertNull(json["observation"])
    }

    // ---- what a plan may not carry ----

    @Test fun aTargetNamedByNodeIdIsRefusedWithTheFieldsToUseInstead() {
        // A nodeId belongs to one observation and the runner re-reads the screen
        // before every step, so a plan carrying ids is stale by the second one.
        val json = plan(
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        addJsonObject { put("action", "tap"); putJsonObject("target") { put("nodeId", "n9") } }
                    },
                )
            },
        )
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("plan_positional", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.contains("contentDescription"))
        assertTrue(json["nothingRan"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(emptyList<String>(), dispatched())
    }

    @Test fun anObservationIdOnTheCallIsRefusedRatherThanIgnored() {
        val json = plan(buildJsonObject { put("steps", sendSteps()); put("observationId", "ui-7") })
        assertEquals("plan_positional", json["errorType"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), dispatched())
    }

    @Test fun aPlanLongerThanOneScreenJustifiesPointsAtASavedWorkflow() {
        val json = plan(
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        repeat(9) { add(buildJsonObject { put("action", "key"); putJsonObject("arguments") { put("keycode", "BACK") } }) }
                    },
                )
            },
        )
        assertEquals("plan_too_long", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.contains("workflow_runner"))
        assertEquals(emptyList<String>(), dispatched())
    }

    @Test fun aMalformedPlanIsRefusedBeforeThePhoneIsTouched() {
        for (steps in listOf(
            buildJsonArray { },
            buildJsonArray { addJsonObject { put("action", "fly") } },
            buildJsonArray { addJsonObject { put("action", "tap") } },
            buildJsonArray { addJsonObject { put("action", "type_text"); putJsonObject("target") { put("class", "EditText") } } },
        )) {
            val json = plan(buildJsonObject { put("steps", steps) })
            assertFalse(json.toString(), json["ok"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(json.toString(), json["nothingRan"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(steps.toString(), emptyList<String>(), dispatched().filter { it != "read_ui" })
        }
    }

    @Test fun noStepsAtAllSaysWhatAPlanLooksLike() {
        val json = plan(buildJsonObject { })
        assertEquals("plan_steps_required", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.contains("\"action\":\"tap\""))
    }

    @Test fun theExampleTheToolHandsOutIsItselfRunnable() {
        // An example that drifts from what the validator accepts is how a
        // caller writes a call that cannot run. The refusal, the tool
        // description and this test all read the same array.
        val json = plan(buildJsonObject { put("steps", PLAN_EXAMPLE_STEPS) })
        assertTrue(json.toString(), json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, json["ranSteps"]!!.jsonPrimitive.content.toInt())
        val definition = gateway().definitions.single { it.name == "act_plan" }
        assertTrue(definition.description.contains(PLAN_EXAMPLE_STEPS.toString()))
    }

    // ---- a plan that half ran ----

    @Test fun aFailingStepStopsThePlanAndNamesEverythingThatAlreadyRan() {
        failing = setOf("tap_node", "tap")
        val json = plan(buildJsonObject { put("steps", sendSteps()) })
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("focus", json["failedStep"]!!.jsonPrimitive.content)
        assertEquals(0, json["ranSteps"]!!.jsonPrimitive.content.toInt())
        assertFalse(dispatched().contains("set_text"))
    }

    @Test fun theFailureResumesByResendingThePlanNotByAWorkflowName() {
        // There is no name in the library to resume by, so the resume block has
        // to hand back arguments the model can actually call.
        failing = setOf("tap_node", "tap")
        val json = plan(buildJsonObject { put("steps", sendSteps()) })
        val resume = json["resume"]!!.jsonObject
        assertEquals("act_plan", resume["tool"]!!.jsonPrimitive.content)
        assertEquals("focus", resume["arguments"]!!.jsonObject["startAt"]!!.jsonPrimitive.content)
        assertNull(resume["arguments"]!!.jsonObject["workflow"])
        assertTrue(resume["note"]!!.jsonPrimitive.content.contains("same steps"))
    }

    @Test fun resumingSkipsTheStepsThatAlreadyRanInsteadOfRepeatingThem() {
        val json = plan(buildJsonObject { put("steps", sendSteps()); put("startAt", "send") })
        assertTrue(json.toString(), json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("resume", json["mode"]!!.jsonPrimitive.content)
        assertEquals(2, json["skippedSteps"]!!.jsonPrimitive.content.toInt())
        // The typing already happened; a resume that retyped it would double it.
        assertFalse(dispatched().contains("set_text"))
    }

    @Test fun aStartAtThatNamesNoStepIsRefusedWithTheStepsThatExist() {
        val json = plan(buildJsonObject { put("steps", sendSteps()); put("startAt", "nope") })
        assertEquals("plan_start_unknown", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.contains("focus, write, send"))
        // Reading which app is in front is the only thing that happened: no
        // step of a refused plan reaches the phone.
        assertEquals(listOf("read_ui"), dispatched())
    }

    // ---- which app ----

    @Test fun theAppInFrontIsReadRatherThanAskedFor() {
        val json = plan(buildJsonObject { put("steps", sendSteps()) })
        assertEquals("com.whatsapp", json["package"]!!.jsonPrimitive.content)
    }

    @Test fun anUnreadableScreenAsksForThePackageAndRunsNothing() {
        activePackage = null
        val json = plan(buildJsonObject { put("steps", sendSteps()) })
        assertEquals("plan_package_required", json["errorType"]!!.jsonPrimitive.content)
        assertEquals(listOf("read_ui"), dispatched())
    }

    @Test fun aPlanThatOpensAnAppTakesThePackageFromThatStep() {
        activePackage = null
        val json = plan(
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        addJsonObject {
                            put("action", "open_app")
                            putJsonObject("arguments") { put("package", "com.android.settings") }
                        }
                    },
                )
            },
        )
        assertEquals("com.android.settings", json["package"]!!.jsonPrimitive.content)
    }

    // ---- the shape the schema promises ----

    @Test fun theSchemaSpellsOutAStepSoItIsNotSentAsQuotedJson() {
        // A bare {"type":"array"} renders as an array of strings, and a model
        // then sends each step as JSON text and is refused for it.
        val steps = gateway().definitions.single { it.name == "act_plan" }
            .inputSchema["properties"]!!.jsonObject["steps"]!!.jsonObject
        assertEquals("array", steps["type"]!!.jsonPrimitive.content)
        val step = steps["items"]!!.jsonObject
        assertEquals("object", step["type"]!!.jsonPrimitive.content)
        val fields = step["properties"]!!.jsonObject
        assertTrue(fields.keys.containsAll(setOf("id", "action", "target", "text", "verify", "arguments", "optional")))
        // The actions are named, not left to be guessed from prose.
        val actions = fields["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(actions.containsAll(listOf("tap", "type_text", "observe", "open_intent")))
        // And the target's fields are the ones a read_ui reply carries.
        assertTrue(fields["target"]!!.jsonObject["properties"]!!.jsonObject.keys.containsAll(setOf("text", "contentDescription", "resourceId", "class")))
    }

    @Test fun theOlderStepListsAlsoSayWhatAStepIs() {
        val names = listOf("run_workflow", "save_workflow")
        for (name in names) {
            val steps = gateway().definitions.single { it.name == name }
                .inputSchema["properties"]!!.jsonObject["steps"]!!.jsonObject
            assertEquals(name, "object", steps["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(name, steps["items"]!!.jsonObject["properties"]!!.jsonObject.containsKey("tool"))
        }
    }

    @Test fun aStepTheModelQuotedAsJsonStillRuns() {
        // Verbatim from the bug report: the schema used to advertise a bare
        // array, so this arrived. Refusing it costs a round trip to learn one
        // thing about quoting.
        val json = plan(
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add("""{"id":"focus","action":"tap","target":{"class":"android.widget.EditText"}}""")
                        add("""{"id":"write","action":"type_text","target":{"class":"EditText"},"text":"on my way"}""")
                    },
                )
            },
        )
        assertTrue(json.toString(), json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("focus", "write"),
            json["steps"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        )
        assertEquals(listOf("tap_node", "set_text"), dispatched().filter { it == "tap_node" || it == "set_text" })
    }

    @Test fun aStepThatIsNeitherAnObjectNorJsonSaysSoWithItsPosition() {
        for (bad in listOf(JsonPrimitive("tap the send button"), JsonPrimitive(7))) {
            calls.clear()
            val json = plan(
                buildJsonObject {
                    put("steps", buildJsonArray { addJsonObject { put("action", "key"); putJsonObject("arguments") { put("keycode", "BACK") } }; add(bad) })
                },
            )
            assertEquals("plan_invalid", json["errorType"]!!.jsonPrimitive.content)
            assertTrue(json.toString(), json["message"]!!.jsonPrimitive.content.startsWith("Step 1 is not a step object"))
            assertTrue(json["nothingRan"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(emptyList<String>(), dispatched().filter { it != "read_ui" })
        }
    }

    // ---- how it is offered ----

    @Test fun thePlanToolIsAlwaysAdvertisedAndShowsTheControlBanner() {
        // It needs no library: its steps come from the model's own reading.
        val gateway = gateway()
        val definition = gateway.definitions.single { it.name == "act_plan" }
        assertTrue(definition.inputSchema["properties"]!!.jsonObject.keys.containsAll(setOf("steps", "startAt", "observe")))
        assertTrue(definition.description.contains("NOT by nodeId"))
        assertTrue(gateway.needsControl("act_plan"))
        assertTrue(gateway.definitions.none { it.name == "workflow_runner" })
    }
}
