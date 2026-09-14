package dev.androidagent.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkflowEngineTest {

    @get:Rule val temp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private var revoked = false
    private var failOn: String? = null
    private var throwOn: String? = null
    private var hangOn: String? = null

    private fun engine() = WorkflowEngine(
        invokeTool = { name, _ ->
            calls += name
            when {
                name == hangOn -> {
                    kotlinx.coroutines.delay(60_000)
                    ToolResult("unreachable")
                }
                name == throwOn -> throw IllegalStateException("$name blew up")
                name == failOn -> ToolResult("$name refused", success = false)
                else -> ToolResult("$name ok")
            }
        },
        store = WorkflowStore(temp.root),
        isRevoked = { revoked },
    )

    private fun steps(vararg tools: String): JsonObject = buildJsonObject {
        put(
            "steps",
            buildJsonArray {
                for (tool in tools) add(buildJsonObject { put("tool", tool) })
            },
        )
    }

    private fun parse(result: ToolResult): JsonObject =
        Json.parseToJsonElement(result.text).jsonObject

    @Test fun everyStepRunsInOrderAndTheWholeThingReportsOnce() {
        val result = runBlocking { engine().run(steps("open_app", "wait_for_change", "tap_node")) }
        assertTrue(result.success)
        assertEquals(listOf("open_app", "wait_for_change", "tap_node"), calls)
        val json = parse(result)
        assertEquals(3, json["completedSteps"]!!.jsonArray.size)
    }

    @Test fun aFailedStepStopsTheSequenceAndNothingAfterItRuns() {
        failOn = "tap_node"
        val result = runBlocking { engine().run(steps("open_app", "tap_node", "type_text")) }
        assertFalse(result.success)
        assertEquals(listOf("open_app", "tap_node"), calls)
        val json = parse(result)
        assertEquals(1, json["failedAtStep"]!!.jsonPrimitive.intOrNull)
        assertEquals("step_failed", json["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun theCompletedPrefixIsReportedSoTheModelResumesRatherThanRestarts() {
        // The whole safety argument: a committed step must never be repeated
        // because something later failed.
        failOn = "type_text"
        val result = runBlocking { engine().run(steps("open_app", "tap_node", "type_text")) }
        val done = parse(result)["completedSteps"]!!.jsonArray.map { it.jsonObject["tool"]!!.jsonPrimitive.content }
        assertEquals(listOf("open_app", "tap_node"), done)
    }

    @Test fun aFailedCommittingStepIsFlaggedAsPossiblyAlreadyDone() {
        throwOn = "tap"
        val json = parse(runBlocking { engine().run(steps("tap")) })
        assertTrue(json["lastCommittedStepMayHaveRun"]!!.jsonPrimitive.booleanOrNull == true)
        assertTrue(json["remedy"]!!.jsonPrimitive.content.contains("may already have changed"))
    }

    @Test fun aFailedReadOnlyStepIsNotFlaggedAsCommitted() {
        throwOn = "read_ui"
        val json = parse(runBlocking { engine().run(steps("read_ui")) })
        assertFalse(json["lastCommittedStepMayHaveRun"]!!.jsonPrimitive.booleanOrNull == true)
        assertTrue(json["remedy"]!!.jsonPrimitive.content.contains("Nothing was committed"))
    }

    @Test fun stopAbortsBetweenSteps() {
        val engine = WorkflowEngine(
            invokeTool = { name, _ ->
                calls += name
                revoked = true // stop lands while the first step is running
                ToolResult("$name ok")
            },
            store = WorkflowStore(temp.root),
            isRevoked = { revoked },
        )
        val result = runBlocking { engine.run(steps("tap", "type_text", "key")) }
        assertFalse(result.success)
        assertEquals(listOf("tap"), calls)
        assertEquals("stopped", parse(result)["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun aNewChatMessageStopsBeforeTheNextCommittingStep() {
        var allowed = true
        val guarded = WorkflowEngine(
            invokeTool = { name, _ ->
                calls += name
                allowed = false
                ToolResult("$name ok")
            },
            store = WorkflowStore(temp.root),
            isRevoked = { false },
            canDispatchAction = { allowed },
        )

        val result = runBlocking { guarded.run(steps("read_ui", "tap", "type_text")) }

        assertFalse(result.success)
        assertEquals(listOf("read_ui"), calls)
        assertEquals("chat_context_changed", parse(result)["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun aToolOutsideTheAllowedSetIsRefusedBeforeItRuns() {
        // A workflow that could run any tool would be a second agent loop with
        // none of the coordinator's guarantees.
        for (tool in listOf("shell", "install_apk", "pull_file", "remember_capability", "run_workflow", "open_intent")) {
            calls.clear()
            val result = runBlocking { engine().run(steps(tool)) }
            assertFalse("$tool should be refused", result.success)
            assertEquals("tool_not_allowed", parse(result)["errorType"]!!.jsonPrimitive.content)
            assertTrue("$tool must not be invoked", calls.isEmpty())
        }
    }

    @Test fun theEntireWorkflowIsValidatedBeforeAnyStepRuns() {
        val result = runBlocking { engine().run(steps("open_app", "shell")) }
        assertFalse(result.success)
        assertTrue(calls.isEmpty())
        assertEquals(1, parse(result)["failedAtStep"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun malformedArgumentsAreRejectedBeforeAnyStepRuns() {
        val arguments = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject { put("tool", "open_app") })
                add(buildJsonObject { put("tool", "read_ui"); put("arguments", JsonArray(emptyList())) })
            })
        }
        val result = runBlocking { engine().run(arguments) }
        assertFalse(result.success)
        assertTrue(calls.isEmpty())
        assertEquals("malformed_step", parse(result)["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun screenshotMediaIsReturnedToTheCaller() {
        val engine = WorkflowEngine(
            invokeTool = { _, _ -> ToolResult("captured", imageBase64 = "image-data", attachmentPaths = listOf("shot.png")) },
            store = WorkflowStore(temp.root),
            isRevoked = { false },
        )
        val result = runBlocking { engine.run(steps("screenshot")) }
        assertEquals("image-data", result.imageBase64)
        assertEquals(listOf("shot.png"), result.attachmentPaths)
    }

    @Test fun completedScreenshotMediaSurvivesALaterFailure() {
        val engine = WorkflowEngine(
            invokeTool = { name, _ ->
                if (name == "screenshot") {
                    ToolResult("captured", imageBase64 = "image-data", attachmentPaths = listOf("shot.png"))
                } else {
                    ToolResult("failed", success = false)
                }
            },
            store = WorkflowStore(temp.root),
            isRevoked = { false },
        )
        val result = runBlocking { engine.run(steps("screenshot", "tap")) }
        assertFalse(result.success)
        assertEquals("image-data", result.imageBase64)
        assertEquals(listOf("shot.png"), result.attachmentPaths)
    }

    @Test fun savingAForbiddenWorkflowIsRejected() {
        val save = buildJsonObject {
            put("name", "unsafe")
            put("package", "com.example")
            put("steps", buildJsonArray { add(buildJsonObject { put("tool", "open_intent") }) })
        }
        assertThrows(IllegalArgumentException::class.java) { engine().save(save) }
    }

    @Test fun aStepThatHangsIsBoundedRatherThanHoldingTheLockForever() {
        hangOn = "wait_for_change"
        val arguments = buildJsonObject {
            put(
                "steps",
                buildJsonArray {
                    add(buildJsonObject { put("tool", "wait_for_change"); put("timeoutMs", 600) })
                },
            )
        }
        val result = runBlocking { engine().run(arguments) }
        assertFalse(result.success)
        assertEquals("step_timeout", parse(result)["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun theTotalBudgetIsClampedSoStopStaysResponsive() {
        val arguments = buildJsonObject {
            put("steps", buildJsonArray { add(buildJsonObject { put("tool", "read_ui") }) })
            put("totalBudgetMs", 10 * 60 * 1000)
        }
        // Clamping is internal; the observable claim is that a huge budget is
        // accepted rather than rejected, and the run still completes promptly.
        val result = runBlocking { engine().run(arguments) }
        assertTrue(result.success)
    }

    @Test fun anEmptyOrOversizedWorkflowIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine().run(buildJsonObject { put("steps", JsonArray(emptyList())) }) }
        }
        val tooMany = Array(WorkflowEngine.MAX_STEPS + 1) { "read_ui" }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine().run(steps(*tooMany)) }
        }
    }

    @Test fun aMissingStepsArgumentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine().run(buildJsonObject { }) }
        }
    }

    @Test fun aMalformedStepIsReportedRatherThanSkipped() {
        val arguments = buildJsonObject {
            put(
                "steps",
                buildJsonArray {
                    add(buildJsonObject { put("tool", "read_ui") })
                    add(buildJsonObject { put("notATool", "x") })
                },
            )
        }
        val result = runBlocking { engine().run(arguments) }
        assertFalse(result.success)
        assertEquals("malformed_step", parse(result)["errorType"]!!.jsonPrimitive.content)
        assertEquals(1, parse(result)["failedAtStep"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun savingAndListingRoundTripsAcrossInstances() {
        val save = buildJsonObject {
            put("name", "send a message")
            put("package", "com.whatsapp")
            put("description", "open, search, tap, type, send")
            put("steps", buildJsonArray { add(buildJsonObject { put("tool", "open_app") }) })
        }
        assertTrue(runBlocking { engine().save(save) }.success)

        // A different engine over the same directory is what a later chat is.
        val listed = parse(engine().list(buildJsonObject { }))
        assertEquals(1, listed["count"]!!.jsonPrimitive.intOrNull)
        assertEquals(
            "send a message",
            listed["workflows"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test fun listingIsFilteredByPackage() {
        for (pkg in listOf("com.whatsapp", "com.android.chrome")) {
            runBlocking {
                engine().save(
                    buildJsonObject {
                        put("name", "flow for $pkg")
                        put("package", pkg)
                        put("steps", buildJsonArray { add(buildJsonObject { put("tool", "open_app") }) })
                    },
                )
            }
        }
        assertEquals(2, parse(engine().list(buildJsonObject { }))["count"]!!.jsonPrimitive.intOrNull)
        val filtered = parse(engine().list(buildJsonObject { put("package", "com.whatsapp") }))
        assertEquals(1, filtered["count"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun anEmptyListingTellsTheModelWhatToDoInstead() {
        val listed = parse(engine().list(buildJsonObject { }))
        assertEquals(0, listed["count"]!!.jsonPrimitive.intOrNull)
        assertTrue(listed["hint"]!!.jsonPrimitive.content.contains("save_workflow"))
    }

    @Test fun resavingUnderTheSameNameRefinesRatherThanDuplicates() {
        repeat(2) { round ->
            runBlocking {
                engine().save(
                    buildJsonObject {
                        put("name", "send a message")
                        put("package", "com.whatsapp")
                        put("description", "round $round")
                        put("steps", buildJsonArray { add(buildJsonObject { put("tool", "open_app") }) })
                    },
                )
            }
        }
        val listed = parse(engine().list(buildJsonObject { }))
        assertEquals(1, listed["count"]!!.jsonPrimitive.intOrNull)
        assertEquals(
            "round 1",
            listed["workflows"]!!.jsonArray.single().jsonObject["description"]!!.jsonPrimitive.content,
        )
    }
}
