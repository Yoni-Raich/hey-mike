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

    private var changes = 0

    private fun gateway(
        supported: Set<AutomationTriggerKind> = AutomationTriggerKind.entries.toSet(),
        canFire: Boolean = true,
        deviceState: () -> Map<String, String> = { emptyMap() },
        deviceSignals: Set<String> = AutomationDeviceStates.values.keys,
        snapshot: (() -> AutomationDeviceSnapshot)? = null,
    ) = AutomationToolGateway(
        library = library(),
        history = history,
        zone = { zone },
        now = { now },
        supportedTriggers = { supported },
        fireNow = if (canFire) ({ id -> fired += id }) else null,
        onChanged = { changes++ },
        deviceState = deviceState,
        supportedDeviceStates = { deviceSignals },
        liveDeviceSnapshot = snapshot,
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

    private val headphonesRule = """{"id":"headphones","when":{"type":"notification","package":"*"},
        "if":[{"type":"device_state","state":"bluetooth_headphones","equals":"connected"}],
        "then":[{"type":"voice_call","opening":"New notification"}]}"""

    @Test fun signalsDiscoversCurrentIdentifiersWithoutChangingOrFiringARule() {
        val device = AutomationConnection("bluetooth_headphones", "AA:BB:CC:DD:EE:01", "My buds", setOf("hfp", "a2dp"))
        val wifi = AutomationConnection("wifi", ssid = " My Home ", bssid = "AA:BB:CC:DD:EE:99")
        val gateway = gateway(snapshot = { AutomationDeviceSnapshot(mapOf("bluetooth_headphones" to "connected", "wifi" to "connected"), listOf(device, wifi)) })
        val reply = call(gateway, """{"mode":"signals"}""")
        val connections = reply["connections"]!!.jsonArray
        assertEquals(device, AutomationConnection.parse(connections[0].jsonObject))
        assertEquals(wifi, AutomationConnection.parse(connections[1].jsonObject))
        assertTrue(library().all().isEmpty())
        assertTrue(fired.isEmpty())
        assertEquals(0, changes)
    }

    @Test fun dryRunReadsFreshRealSignalsUnlessSimulationIsExplicit() {
        var state = "connected"
        val gateway = gateway(deviceState = { mapOf("bluetooth_headphones" to state) })
        create(gateway, headphonesRule)
        val test = """{"mode":"test","rule":"headphones","event":{"type":"notification","package":"com.example"}}"""
        val connected = call(gateway, test)
        assertEquals("live", connected.str("deviceStateSource"))
        assertEquals("1", connected["firing"]!!.jsonPrimitive.content)
        state = "disconnected"
        assertEquals("0", call(gateway, test)["firing"]!!.jsonPrimitive.content)
        val simulated = call(gateway, test.dropLast(1) + """, "deviceState":{"bluetooth_headphones":"connected"}}""")
        assertEquals("simulated", simulated.str("deviceStateSource"))
        assertEquals("1", simulated["firing"]!!.jsonPrimitive.content)
        assertTrue(fired.isEmpty())
        assertEquals("0", call(gateway, test)["firing"]!!.jsonPrimitive.content)
    }

    @Test fun missingBluetoothPermissionIsReportedDormant() {
        val gateway = gateway(deviceSignals = setOf("power", "screen"))
        val reply = create(gateway, headphonesRule)
        assertTrue(reply["dormant"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(reply.str("dormantBecause")!!.contains("BLUETOOTH_CONNECT"))
    }

    @Test fun invalidDeviceStateUpdateDoesNotChangeSavedRule() {
        val gateway = gateway()
        create(gateway, headphonesRule)
        val before = library().get("headphones")!!.toJson()
        val result = call(gateway, """{"mode":"update","rule":"headphones", "changes":{
            "if":[{"type":"device_state","state":"fictional","equals":"connected"}]}}""")
        assertFalse(result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(before, library().get("headphones")!!.toJson())
    }

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

    @Test fun creatingAnAllAppsVoiceRuleAndDryRunningItKeepsContextAndDisclosure() {
        val gateway = gateway()
        val reply = create(gateway,
            """{"id":"all-notifications","when":{"type":"notification","package":"*"},
             "then":[{"type":"voice_call","opening":"Hi, Yoni, you have a new notification.",
             "context":"{{notification.package}}: {{notification.title}}: {{notification.text}}"}]}""",
        )
        assertTrue(reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("a notification from any app", reply["summary"]!!.jsonObject.str("when"))
        assertEquals(setOf("notification.package", "notification.title", "notification.text"),
            reply["summary"]!!.jsonObject["sendsToTheModel"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("*", library().get("all-notifications")!!.trigger.packageName)
        for (pkg in listOf("com.whatsapp", "com.example.mail")) {
            val test = call(gateway,
                """{"mode":"test","rule":"all-notifications","event":{"type":"notification",
                 "package":"$pkg","title":"Dad","text":"Meet at eight"}}""",
            )
            assertEquals(1, test["firing"]!!.jsonPrimitive.content.toInt())
            val action = test["outcomes"]!!.jsonArray.single().jsonObject["then"]!!.jsonArray.single().jsonObject
            assertEquals("$pkg: Dad: Meet at eight", action.str("context"))
        }
        assertEquals(0, history.firedOn("all-notifications", now.toLocalDate()))
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

    @Test fun createRefusesToOverwriteARuleUnlessAskedTo() {
        val gateway = gateway()
        create(gateway)
        val again = create(gateway)
        assertFalse(again["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("rule_exists", again["errorType"]!!.jsonPrimitive.content)
        assertTrue(again["message"]!!.jsonPrimitive.content.contains("update"))

        val replaced = call(gateway, """{"mode":"create","replace":true,"rule":${dadRule.trimIndent()}}""")
        assertTrue(replaced["replaced"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun updateChangesOnlyWhatItNamesAndShowsBeforeAndAfter() {
        val gateway = gateway()
        create(gateway)
        val reply = call(
            gateway,
            """{"mode":"update","rule":"dad-after-seven",
                "changes":{"if":[{"type":"time_between","after":"20:00","before":"07:00"}]}}""",
        )
        assertTrue(reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("if", reply["changed"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(reply["before"]!!.jsonObject["if"].toString().contains("19:00"))
        assertTrue(reply["after"]!!.jsonObject["if"].toString().contains("20:00"))
        // The trigger and the action are untouched.
        val saved = library().get("dad-after-seven")!!
        assertEquals("com.whatsapp", saved.trigger.packageName)
        assertEquals(AutomationActionKind.AGENT_TURN, saved.actions.single().kind)
    }

    @Test fun anInvalidUpdateIsRefusedAndTheRuleIsUnchanged() {
        val gateway = gateway()
        create(gateway)
        val reply = call(gateway, """{"mode":"update","rule":"dad-after-seven","changes":{"when":{"type":"notification"}}}""")
        assertFalse(reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("com.whatsapp", library().get("dad-after-seven")!!.trigger.packageName)
    }

    @Test fun updateWithoutChangesSaysWhatItNeeds() {
        val gateway = gateway()
        create(gateway)
        val reply = call(gateway, """{"mode":"update","rule":"dad-after-seven"}""")
        assertEquals("changes_required", reply["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun aNearMissNameIsNeverDeletedOnlySuggested() {
        val gateway = gateway()
        create(gateway)
        val reply = call(gateway, """{"mode":"delete","rule":"dad"}""")
        assertFalse(reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("rule_id_inexact", reply["errorType"]!!.jsonPrimitive.content)
        assertTrue(reply["message"]!!.jsonPrimitive.content.contains("dad-after-seven"))
        assertEquals(1, library().all().size)
    }

    @Test fun everyChangeTellsTheHostSoItCanReArm() {
        val gateway = gateway()
        create(gateway)
        call(gateway, """{"mode":"update","rule":"dad-after-seven","changes":{"description":"x"}}""")
        call(gateway, """{"mode":"disable","rule":"dad-after-seven"}""")
        call(gateway, """{"mode":"enable","rule":"dad-after-seven"}""")
        call(gateway, """{"mode":"delete","rule":"dad-after-seven"}""")
        assertEquals(5, changes)
        // Reading and dry runs change nothing.
        call(gateway, """{"mode":"list"}""")
        assertEquals(5, changes)
    }
}
