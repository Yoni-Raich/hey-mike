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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationToolGatewayTest {

    @get:Rule val temp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = ZonedDateTime.parse("2026-09-15T21:40:00+03:00[Asia/Jerusalem]")
    private val history = InMemoryAutomationHistory(zone)

    private fun library() = AutomationLibrary(File(temp.root, "automations"))

    private val fired = mutableListOf<String>()
    private val performed = mutableListOf<AutomationAction>()
    private var performResult = AutomationActionResult.ok("done")

    private fun gateway(
        supported: Set<AutomationTriggerKind> = AutomationTriggerKind.entries.toSet(),
        canFire: Boolean = true,
        canPerform: Boolean = true,
    ) = AutomationToolGateway(
        library = library(),
        history = history,
        zone = { zone },
        now = { now },
        supportedTriggers = { supported },
        fireNow = if (canFire) ({ id -> fired += id }) else null,
        performNow = if (canPerform) ({ action -> performed += action; performResult }) else null,
    ).also { it.beginRun("run", temp.root) }

    private fun call(gateway: AutomationToolGateway, json: String): JsonObject = runBlocking {
        val result = gateway.invoke("automation_rule", Json.parseToJsonElement(json.trimIndent()).jsonObject)
        Json.parseToJsonElement(result.text).jsonObject
    }

    private val dadRule = """
        {"id":"dad-after-seven",
         "when":{"type":"notification","package":"com.whatsapp","from":"Dad"},
         "if":[{"type":"time_between","after":"19:00","before":"07:00"}],
         "then":[{"type":"agent_turn","prompt":"Tell {{notification.title}} I cannot talk."}]}
    """

    private fun create(gateway: AutomationToolGateway, rule: String = dadRule) =
        call(gateway, """{"mode":"create","rule":${rule.trimIndent()}}""")

    @Test fun creatingSavesTheRuleAndSaysWhatItWillDo() {
        val gateway = gateway()
        val reply = create(gateway)
        assertEquals(true, reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("dad-after-seven", reply["rule"]!!.jsonPrimitive.content)
        val summary = reply["summary"]!!.jsonObject
        assertEquals("model", summary["attention"]!!.jsonPrimitive.content)
        // The reply names what leaves the phone, so the model can repeat it.
        assertEquals("notification.title", summary["sendsToTheModel"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun aRuleWhoseTriggerThisPhoneCannotServeIsSavedAndReportedDormant() {
        // The point of failure is when it is written, not the first night it
        // quietly does not fire.
        val gateway = gateway(supported = setOf(AutomationTriggerKind.SCHEDULE))
        val reply = create(gateway)
        assertTrue(reply["dormant"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(reply["dormantBecause"]!!.jsonPrimitive.content.contains("permission"))
        assertEquals(1, library().all().size)
    }

    @Test fun aMalformedRuleIsRefusedWithTheReasonAndNothingIsWritten() {
        val reply = create(gateway(), """{"id":"x","when":{"type":"notification"},"then":[{"type":"notify","text":"x"}]}""")
        assertFalse(reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("automation_invalid", reply["errorType"]!!.jsonPrimitive.content)
        assertTrue(reply["message"]!!.jsonPrimitive.content.contains("package"))
        assertTrue(library().all().isEmpty())
    }

    @Test fun listingIsEmptyWithAHintRatherThanABareZero() {
        val reply = call(gateway(), """{"mode":"list"}""")
        assertEquals(0, reply["count"]!!.jsonPrimitive.content.toInt())
        assertTrue(reply["hint"]!!.jsonPrimitive.content.contains("workflow"))
    }

    @Test fun listingNamesTheRulesThisPhoneCannotServe() {
        val gateway = gateway(supported = setOf(AutomationTriggerKind.SCHEDULE))
        create(gateway)
        val reply = call(gateway, """{"mode":"list"}""")
        assertEquals("dad-after-seven", reply["dormant"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun describingAScheduledRuleSaysWhenItNextRuns() {
        val gateway = gateway()
        create(gateway, """{"id":"post","when":{"type":"schedule","at":"07:00"},"then":[{"type":"notify","text":"x"}]}""")
        val reply = call(gateway, """{"mode":"describe","rule":"post"}""")
        assertTrue(reply["nextRunAt"]!!.jsonPrimitive.content.startsWith("2026-09-16T07:00"))
        assertEquals(0, reply["firedToday"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun anUnknownRuleComesBackWithTheOnesThatExist() {
        val gateway = gateway()
        create(gateway)
        val reply = call(gateway, """{"mode":"describe","rule":"ghost"}""")
        assertEquals("rule_not_found", reply["errorType"]!!.jsonPrimitive.content)
        assertTrue(reply["message"]!!.jsonPrimitive.content.contains("dad-after-seven"))
    }

    @Test fun disablingAndEnablingRoundTrips() {
        val gateway = gateway()
        create(gateway)
        assertFalse(call(gateway, """{"mode":"disable","rule":"dad-after-seven"}""")["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(call(gateway, """{"mode":"enable","rule":"dad-after-seven"}""")["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun deletingRemovesIt() {
        val gateway = gateway()
        create(gateway)
        assertTrue(call(gateway, """{"mode":"delete","rule":"dad-after-seven"}""")["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(library().all().isEmpty())
    }

    @Test fun aDryRunShowsTheRuleFiringWithTheRealPrompt() {
        val gateway = gateway()
        create(gateway)
        val reply = call(
            gateway,
            """
            {"mode":"test","rule":"dad-after-seven","now":"2026-09-15T21:40",
             "event":{"type":"notification","package":"com.whatsapp","title":"Dad","text":"call me"}}
            """,
        )
        assertEquals(1, reply["firing"]!!.jsonPrimitive.content.toInt())
        val outcome = reply["outcomes"]!!.jsonArray.single().jsonObject
        assertTrue(outcome["fires"]!!.jsonPrimitive.content.toBoolean())
        val action = outcome["then"]!!.jsonArray.single().jsonObject
        assertEquals("Tell Dad I cannot talk.", action["prompt"]!!.jsonPrimitive.content)
    }

    @Test fun aDryRunAtTheWrongHourNamesTheClauseThatStoppedIt() {
        // "Why did nothing happen when Dad messaged" has to have an answer.
        val gateway = gateway()
        create(gateway)
        val reply = call(
            gateway,
            """
            {"mode":"test","rule":"dad-after-seven","now":"2026-09-15T12:00",
             "event":{"type":"notification","package":"com.whatsapp","title":"Dad","text":"call me"}}
            """,
        )
        assertEquals(0, reply["firing"]!!.jsonPrimitive.content.toInt())
        val outcome = reply["outcomes"]!!.jsonArray.single().jsonObject
        assertEquals("condition_failed", outcome["why"]!!.jsonPrimitive.content)
        assertTrue(outcome["detail"]!!.jsonPrimitive.content.contains("19:00"))
    }

    @Test fun aDryRunRecordsNothing() {
        val gateway = gateway()
        create(gateway)
        repeat(3) {
            call(
                gateway,
                """
                {"mode":"test","now":"2026-09-15T21:40",
                 "event":{"type":"notification","package":"com.whatsapp","title":"Dad","text":"hi"}}
                """,
            )
        }
        assertEquals(0, history.firedOn("dad-after-seven", now.toLocalDate()))
    }

    @Test fun aDryRunWithNoRuleNamedReportsEveryRule() {
        val gateway = gateway()
        create(gateway)
        create(gateway, """{"id":"other","when":{"type":"schedule","at":"07:00"},"then":[{"type":"notify","text":"x"}]}""")
        val reply = call(
            gateway,
            """
            {"mode":"test","now":"2026-09-15T21:40",
             "event":{"type":"notification","package":"com.whatsapp","title":"Dad","text":"hi"}}
            """,
        )
        assertEquals(2, reply["outcomes"]!!.jsonArray.size)
        assertEquals(1, reply["firing"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun aDryRunCanSayTheUserIsUnreachable() {
        val gateway = gateway()
        create(
            gateway,
            """{"id":"call","when":{"type":"schedule","at":"21:40"},"then":[{"type":"voice_call","opening":"hi"}]}""",
        )
        val reply = call(
            gateway,
            """{"mode":"test","rule":"call","now":"2026-09-15T21:40","event":{"type":"schedule"},"userReachable":false}""",
        )
        val outcome = reply["outcomes"]!!.jsonArray.single().jsonObject
        assertEquals("needs_you", outcome["why"]!!.jsonPrimitive.content)
        assertTrue(outcome["tryAgainLater"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun aTestWithNoEventSaysWhatAnEventLooksLike() {
        val reply = call(gateway(), """{"mode":"test","rule":"x"}""")
        assertEquals("event_required", reply["errorType"]!!.jsonPrimitive.content)
        assertTrue(reply["message"]!!.jsonPrimitive.content.contains("notification"))
    }

    @Test fun runFiresTheRuleAndSaysWhatItWillDo() {
        val gateway = gateway()
        create(gateway)
        val reply = call(gateway, """{"mode":"run","rule":"dad-after-seven"}""")
        assertTrue(reply["started"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("dad-after-seven"), fired)
        assertEquals("model", reply["attention"]!!.jsonPrimitive.content)
        assertTrue(reply["willDo"]!!.jsonArray.single().jsonPrimitive.content.contains("agent_turn"))
    }

    @Test fun runRefusesADisabledRuleRatherThanFiringIt() {
        val gateway = gateway()
        create(gateway)
        call(gateway, """{"mode":"disable","rule":"dad-after-seven"}""")
        val reply = call(gateway, """{"mode":"run","rule":"dad-after-seven"}""")
        assertEquals("rule_disabled", reply["errorType"]!!.jsonPrimitive.content)
        assertTrue(fired.isEmpty())
    }

    @Test fun runOnAHostWithNoFiringPathSaysSoRatherThanDoingNothing() {
        val gateway = gateway(canFire = false)
        create(gateway)
        val reply = call(gateway, """{"mode":"run","rule":"dad-after-seven"}""")
        assertEquals("run_unavailable", reply["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun runOnAnUnknownRuleFiresNothing() {
        val gateway = gateway()
        create(gateway)
        assertEquals(
            "rule_not_found",
            call(gateway, """{"mode":"run","rule":"ghost"}""")["errorType"]!!.jsonPrimitive.content,
        )
        assertTrue(fired.isEmpty())
    }

    @Test fun anUnknownModeNamesTheRealOnes() {
        val reply = call(gateway(), """{"mode":"fire"}""")
        assertEquals("unknown_mode", reply["errorType"]!!.jsonPrimitive.content)
        assertTrue(reply["message"]!!.jsonPrimitive.content.contains("create"))
    }

    @Test fun theGatewayOperatesNoDeviceAndShowsNoControlBanner() {
        val gateway = gateway()
        assertFalse(gateway.deviceBackendLive())
        assertFalse(gateway.needsControl("automation_rule"))
    }

    @Test fun aStoppedRunRefusesEveryMode() {
        val gateway = gateway()
        gateway.revoke()
        val failure = runCatching { runBlocking { gateway.invoke("automation_rule", JsonObject(emptyMap())) } }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
    }

    @Test fun theStatusLineCountsWhatIsOnOffAndDormant() {
        val gateway = gateway(supported = setOf(AutomationTriggerKind.SCHEDULE))
        create(gateway)
        create(gateway, """{"id":"post","when":{"type":"schedule","at":"07:00"},"then":[{"type":"notify","text":"x"}]}""")
        call(gateway, """{"mode":"disable","rule":"post"}""")
        val line = gateway.statusLine()
        assertTrue(line, line.contains("1 on"))
        assertTrue(line, line.contains("1 off"))
        assertTrue(line, line.contains("dormant"))
    }

    // ---- do: one action, no rule ----

    @Test fun doPerformsTheActionWithoutWritingARule() {
        val gateway = gateway()
        val body = call(gateway, """{"mode":"do","action":{"type":"notify","text":"the kettle boiled"}}""")
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, performed.size)
        assertEquals(AutomationActionKind.NOTIFY, performed.single().kind)
        assertEquals("the kettle boiled", performed.single().raw["text"]!!.jsonPrimitive.content)
        // Nothing was saved, and the reply says so rather than leaving the
        // model to assume a rule now exists.
        assertFalse(body["recorded"]!!.jsonPrimitive.boolean)
        val listed = call(gateway, """{"mode":"list"}""")
        assertEquals(0, listed["rules"]!!.jsonArray.size)
    }

    @Test fun doReportsAFailureAsAFailure() {
        performResult = AutomationActionResult.failed("nothing handles that intent")
        val body = call(gateway(), """{"mode":"do","action":{"type":"open_intent","action":"android.intent.action.NOPE"}}""")
        assertFalse(body["ok"]!!.jsonPrimitive.boolean)
        assertTrue(body["detail"]!!.jsonPrimitive.content.contains("nothing handles"))
    }

    @Test fun doNeverClaimsAFailedActionWasUndone() {
        performResult = AutomationActionResult.failed("the workflow stopped at step 3", committed = true)
        val body = call(gateway(), """{"mode":"do","action":{"type":"run_workflow","workflow":"evening-post"}}""")
        assertFalse(body["ok"]!!.jsonPrimitive.boolean)
        assertTrue(body["mayHaveRun"]!!.jsonPrimitive.boolean)
    }

    @Test fun doRefusesAnActionWithAPlaceholderItCannotFill() {
        val body = call(gateway(), """{"mode":"do","action":{"type":"notify","text":"from {{notification.title}}"}}""")
        assertEquals("placeholder_without_event", body["errorType"]!!.jsonPrimitive.content)
        assertTrue(performed.isEmpty())
    }

    @Test fun doRefusesAgentTurnBecauseTheCallerIsOne() {
        val body = call(gateway(), """{"mode":"do","action":{"type":"agent_turn","prompt":"check the inbox"}}""")
        assertEquals("agent_turn_not_one_off", body["errorType"]!!.jsonPrimitive.content)
        assertTrue(performed.isEmpty())
    }

    @Test fun doRefusesRequiresApprovalRatherThanImplyingASecondGate() {
        val body = call(
            gateway(),
            """{"mode":"do","action":{"type":"notify","text":"hi","requiresApproval":true}}""",
        )
        assertEquals("approval_not_applicable", body["errorType"]!!.jsonPrimitive.content)
        assertTrue(performed.isEmpty())
    }

    @Test fun doRefusesAnUnknownActionKindWithTheWholeList() {
        val body = call(gateway(), """{"mode":"do","action":{"type":"send_sms","text":"hi"}}""")
        assertEquals("automation_invalid", body["errorType"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("notify"))
        assertTrue(performed.isEmpty())
    }

    @Test fun doRefusesAnActionMissingTheFieldItsKindNeeds() {
        val body = call(gateway(), """{"mode":"do","action":{"type":"notify"}}""")
        assertEquals("automation_invalid", body["errorType"]!!.jsonPrimitive.content)
        assertTrue(performed.isEmpty())
    }

    @Test fun doWithoutAnActionSaysWhatItNeeds() {
        val body = call(gateway(), """{"mode":"do"}""")
        assertEquals("missing_action", body["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun aHostWithNoActionPathRefusesRatherThanDoingNothing() {
        val body = call(gateway(canPerform = false), """{"mode":"do","action":{"type":"notify","text":"hi"}}""")
        assertEquals("do_unavailable", body["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun doIsNotARuleFiringSoNothingCountsAgainstAQuota() {
        val gateway = gateway()
        call(gateway, dadRule.trimIndent().let { """{"mode":"create","rule":$it}""" })
        call(gateway, """{"mode":"do","action":{"type":"notify","text":"one off"}}""")
        val described = call(gateway, """{"mode":"describe","rule":"dad-after-seven"}""")
        // The rule's own history is untouched by an action that was never its.
        assertFalse(described.toString().contains("\"today\":1"))
        assertTrue(fired.isEmpty())
    }
}
