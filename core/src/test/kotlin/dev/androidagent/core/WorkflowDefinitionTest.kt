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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDefinitionTest {

    private fun parse(json: String) = WorkflowDefinition.parse(Json.parseToJsonElement(json).jsonObject)

    private fun failure(json: String): String =
        runCatching { parse(json) }.exceptionOrNull().let { error ->
            assertTrue("expected a typed refusal, got $error", error is WorkflowFormatException)
            error!!.message!!
        }

    @Test fun aPlaceholderNothingDeclaresIsRefusedWhereItIsWritten() {
        // It would otherwise reach the phone as the literal text "{{minutes}}".
        val message = failure(
            """{"id":"t","package":"com.example.app","steps":[{"id":"s","action":"type_text","text":"{{minutes}}"}]}""",
        )
        assertTrue(message, message.contains("{{minutes}}"))
    }

    @Test fun anIntentStepNeedsSomethingToLaunch() {
        val message = failure(
            """{"id":"t","package":"com.example.app","steps":[{"id":"s","action":"open_intent","arguments":{"package":"com.example.app"}}]}""",
        )
        assertTrue(message, message.contains("arguments.action or arguments.uri"))
    }

    @Test fun aBoundDefinitionIsParsedAgainSoAValueCannotBreakTheFormat() {
        val definition = parse(
            """{"id":"t","package":"com.example.app","parameters":{"what":{"type":"string"}},
                "steps":[{"id":"s","action":"type_text","text":"{{what}}"}]}""",
        )
        val bound = definition.bind(Json.parseToJsonElement("""{"what":"hello"}""").jsonObject)
        assertEquals("hello", bound.steps.single().text)
        assertEquals(definition.parameters, bound.parameters)
        val tooLong = runCatching {
            definition.bind(Json.parseToJsonElement("""{"what":"${"x".repeat(WorkflowStep.MAX_TEXT_CHARS + 1)}"}""").jsonObject)
        }.exceptionOrNull()
        assertTrue("$tooLong", tooLong is WorkflowFormatException)
    }

    @Test fun theSpecExampleParsesAsWritten() {
        // The definition from the feature request, unchanged.
        val definition = parse(
            """
            {
              "id": "wireless-debugging",
              "version": 1,
              "package": "com.android.settings",
              "steps": [
                {"id":"open_settings","action":"open_app","arguments":{"package":"com.android.settings"},
                 "verify":{"package":"com.android.settings"}},
                {"id":"open_search","action":"tap",
                 "target":{"resourceId":"com.android.settings:id/search_bar_title","text":"Search settings"}},
                {"id":"search","action":"type_text","text":"Wireless debugging"},
                {"id":"open_result","action":"tap","target":{"text":"Wireless debugging"}},
                {"id":"enable","action":"tap","target":{"resourceId":"com.android.settings:id/switchWidget"},
                 "requiresConfirmation":true}
              ]
            }
            """,
        )
        assertEquals("wireless-debugging", definition.id)
        assertEquals(5, definition.steps.size)
        assertEquals(WorkflowAction.OPEN_APP, definition.steps[0].action)
        assertEquals("com.android.settings", definition.steps[0].verify!!.packageName)
        assertEquals("Search settings", definition.steps[1].target!!.text)
        assertTrue(definition.steps.last().requiresConfirmation)
        assertEquals(0, definition.stepIndex("open_settings"))
        assertEquals(4, definition.stepIndex("enable"))
        assertNull(definition.stepIndex("nope"))
    }

    @Test fun nothingPositionalCanBeWrittenIntoADefinition() {
        // Coordinates and node handles are what made the old mechanism break
        // on any screen but the one it was recorded on, so there is no field
        // for them: they are read off the target and dropped.
        val step = parse(
            """{"id":"w","package":"com.example.app","steps":[
                 {"id":"s","action":"tap","target":{"text":"Go","nodeId":"n7","observationId":"ui-3","x":100,"y":200}}]}""",
        ).steps.single()
        val written = step.toJson().toString()
        assertFalse(written, written.contains("nodeId"))
        assertFalse(written, written.contains("observationId"))
        assertFalse(written, written.contains("\"x\""))
    }

    @Test fun aShorthandVerifyBodyReadsAsWhatIsOnScreen() {
        val steps = parse(
            """{"id":"w","package":"com.example.app","steps":[
                 {"id":"a","action":"observe","verify":{"text":"Done"}},
                 {"id":"b","action":"observe","verify":{"package":"com.example.app"}},
                 {"id":"c","action":"observe","verify":{"absent":{"text":"Cancel"}}}]}""",
        ).steps
        assertEquals("Done", steps[0].verify!!.present!!.text)
        assertNull(steps[0].verify!!.packageName)
        // A package on its own stays a package check, not a node search.
        assertEquals("com.example.app", steps[1].verify!!.packageName)
        assertNull(steps[1].verify!!.present)
        assertEquals("Cancel", steps[2].verify!!.absent!!.text)
    }

    @Test fun anActionThatNeedsATargetIsRefusedWithoutOne() {
        val message = failure("""{"id":"w","package":"com.example.app","steps":[{"id":"s","action":"tap"}]}""")
        assertTrue(message, message.contains("needs a \"target\""))
    }

    @Test fun anEmptyTargetIsRefusedBecauseItWouldMatchTheWholeScreen() {
        val message = failure(
            """{"id":"w","package":"com.example.app","steps":[{"id":"s","action":"tap","target":{"exact":true}}]}""",
        )
        assertTrue(message, message.contains("names no selector field"))
    }

    @Test fun anUnknownActionNamesTheOnesThatExist() {
        val message = failure(
            """{"id":"w","package":"com.example.app","steps":[{"id":"s","action":"shell","arguments":{"command":"rm -rf /"}}]}""",
        )
        assertTrue(message, message.contains("is not a workflow action"))
        assertTrue(message, message.contains("open_app"))
    }

    @Test fun twoStepsCannotShareAnIdBecauseResumingNamesOne() {
        val message = failure(
            """{"id":"w","package":"com.example.app","steps":[
                 {"id":"same","action":"observe"},{"id":"same","action":"observe"}]}""",
        )
        assertTrue(message, message.contains("more than once"))
    }

    @Test fun aBadPackageOrIdIsRefusedBeforeTheFileIsEverRun() {
        assertTrue(failure("""{"id":"w","package":"notapackage","steps":[{"id":"s","action":"observe"}]}""").contains("package name"))
        assertTrue(failure("""{"id":"Not An Id","package":"com.example.app","steps":[{"id":"s","action":"observe"}]}""").contains("usable workflow id"))
        assertTrue(failure("""{"package":"com.example.app","steps":[{"id":"s","action":"observe"}]}""").contains("\"id\" is required"))
        assertTrue(failure("""{"id":"w","package":"com.example.app","steps":[]}""").contains("no steps"))
    }

    @Test fun aStepLongerThanTheLimitIsRefusedRatherThanTruncated() {
        val steps = (0..WorkflowDefinition.MAX_STEPS).joinToString(",") { """{"id":"s$it","action":"observe"}""" }
        assertTrue(failure("""{"id":"w","package":"com.example.app","steps":[$steps]}""").contains("the limit is"))
    }

    @Test fun aliasesFromTheDeviceToolListNameTheSameActions() {
        val definition = parse(
            """{"id":"w","package":"com.example.app","steps":[
                 {"id":"a","action":"tap_node","target":{"text":"Go"}},
                 {"id":"b","action":"set_text","text":"hi"},
                 {"id":"c","action":"scroll_node"},
                 {"id":"d","action":"wait_for_change"},
                 {"id":"e","action":"read_ui"},
                 {"id":"f","action":"back"}]}""",
        )
        assertEquals(
            listOf(
                WorkflowAction.TAP, WorkflowAction.TYPE_TEXT, WorkflowAction.SCROLL,
                WorkflowAction.WAIT, WorkflowAction.OBSERVE, WorkflowAction.KEY,
            ),
            definition.steps.map { it.action },
        )
        assertEquals("BACK", definition.steps.last().arguments.str("keycode"))
    }

    @Test fun aDefinitionSurvivesARoundTripThroughItsOwnJson() {
        // The recorder writes what the parser reads; anything lost here would
        // be silently dropped from every saved workflow.
        val original = parse(
            """{"id":"w","version":3,"package":"com.example.app","description":"does a thing","steps":[
                 {"id":"a","action":"tap","target":{"resourceId":"x","text":"Go","exact":true,"index":2,
                  "clickable":true,"scrollIntoView":true},
                  "verify":{"present":{"text":"Done"},"checked":true,"timeoutMs":7000},
                  "requiresConfirmation":true,"skipIfVerified":true,"optional":true,"timeoutMs":4000},
                 {"id":"b","action":"type_text","text":"hello","submit":true,"waitForChange":false}]}""",
        )
        val again = WorkflowDefinition.parse(Json.parseToJsonElement(original.toJson().toString()).jsonObject)
        assertEquals(original.copy(source = null), again.copy(source = null))
    }

    @Test fun theOutlineIsReadableWithoutRunningAnything() {
        val outline = parse(
            """{"id":"w","package":"com.example.app","steps":[
                 {"id":"pay","action":"tap","target":{"text":"Pay"},"requiresConfirmation":true}]}""",
        ).outline().toString()
        assertTrue(outline, outline.contains("\"requiresConfirmation\":true"))
        assertTrue(outline, outline.contains("text=\\\"Pay\\\""))
    }

    @Test fun aVerificationThatAssertsNothingIsRefusedRatherThanIgnored() {
        // A `verify` block that checks nothing is a step that reports success
        // for anything. Saying so beats dropping it and looking verified.
        val message = failure(
            """{"id":"w","package":"com.example.app","steps":[{"id":"s","action":"observe","verify":{"timeoutMs":900}}]}""",
        )
        assertTrue(message, message.contains("names no condition"))
    }

    @Test fun timeoutsAreClampedRatherThanTakenAtFaceValue() {
        val step = parse(
            """{"id":"w","package":"com.example.app","steps":[
                 {"id":"s","action":"observe","timeoutMs":9999999,"verify":{"text":"x","timeoutMs":9999999}}]}""",
        ).steps.single()
        assertEquals(WorkflowStep.MAX_STEP_MS, step.timeoutMs)
        assertEquals(WorkflowVerification.MAX_TIMEOUT_MS, step.verify!!.timeoutMs)
    }
}
