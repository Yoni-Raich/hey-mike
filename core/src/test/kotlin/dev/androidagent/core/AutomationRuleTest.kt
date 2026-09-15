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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationRuleTest {

    private fun parse(json: String): AutomationRule =
        AutomationRule.parse(Json.parseToJsonElement(json.trimIndent()).jsonObject)

    private fun refusal(json: String): String {
        try {
            parse(json)
        } catch (invalid: AutomationFormatException) {
            return invalid.message
        }
        fail("expected the rule to be refused")
        error("unreachable")
    }

    @Test fun aRuleReadsAsWhenIfThen() {
        val rule = parse(
            """
            {"id":"dad-after-seven","description":"Tell Dad I cannot talk",
             "when":{"type":"notification","package":"com.whatsapp","from":"Dad"},
             "if":[{"type":"time_between","after":"19:00","before":"07:00"}],
             "then":[{"type":"agent_turn","prompt":"Reply on WhatsApp: I cannot talk now."}]}
            """,
        )
        assertEquals("dad-after-seven", rule.id)
        assertEquals(AutomationTriggerKind.NOTIFICATION, rule.trigger.kind)
        assertEquals(1, rule.conditions.size)
        assertEquals(AutomationActionKind.AGENT_TURN, rule.actions.single().kind)
        assertTrue(rule.enabled)
    }

    @Test fun attentionIsDerivedFromTheActionsAndNotDeclarable() {
        // The host trusts this value to decide whether it may fire something
        // while the phone is in a pocket, so an author cannot write it down.
        val quiet = parse(
            """
            {"id":"post","when":{"type":"schedule","at":"19:00"},
             "attention":"user",
             "then":[{"type":"run_workflow","workflow":"post-to-facebook"}]}
            """,
        )
        assertEquals(AutomationAttention.NONE, quiet.attention)

        val loud = parse(
            """
            {"id":"morning","when":{"type":"schedule","at":"07:00"},
             "then":[{"type":"notify","text":"Morning"},{"type":"voice_call","opening":"Ready?"}]}
            """,
        )
        assertEquals(AutomationAttention.USER, loud.attention)
    }

    @Test fun onlyTheFieldsAnActionInterpolatesAreExported() {
        // The privacy contract: matching on the body of a message does not
        // export the body of that message.
        val rule = parse(
            """
            {"id":"dad","when":{"type":"notification","package":"com.whatsapp"},
             "if":[{"type":"text","field":"notification.text","contains":"dinner"}],
             "then":[{"type":"agent_turn","prompt":"Reply to {{notification.title}} that I am busy."}]}
            """,
        )
        assertEquals(setOf("notification.title"), rule.exportedFields)
        assertFalse("notification.text" in rule.exportedFields)
    }

    @Test fun aPlaceholderTheTriggerCannotProvideIsRefusedWhereItIsWritten() {
        // Otherwise it reaches the model as the literal braces, at 19:00, on a
        // phone nobody is looking at.
        val message = refusal(
            """
            {"id":"bad","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"agent_turn","prompt":"Reply to {{notification.title}}"}]}
            """,
        )
        assertTrue(message, message.contains("notification.title"))
        assertTrue(message, message.contains("schedule"))
    }

    @Test fun theAmbientClockFieldsAreAlwaysAvailable() {
        val rule = parse(
            """
            {"id":"ok","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"notify","text":"It is {{now.time}} on {{now.day}}"}]}
            """,
        )
        assertEquals(setOf("now.time", "now.day"), rule.exportedFields)
    }

    @Test fun aNotificationTriggerMustNameItsApp() {
        // No package means every notification on the phone, which is both
        // never what was meant and the widest possible read of the user.
        val message = refusal("""{"id":"any","when":{"type":"notification"},"then":[{"type":"notify","text":"hi"}]}""")
        assertTrue(message, message.contains("package"))
    }

    @Test fun aRuleWithNoActionsIsRefused() {
        val message = refusal("""{"id":"empty","when":{"type":"schedule","at":"19:00"},"then":[]}""")
        assertTrue(message, message.contains("no actions"))
    }

    @Test fun anUnknownActionNamesTheOnesThatExist() {
        val message = refusal(
            """{"id":"x","when":{"type":"manual"},"then":[{"type":"send_sms","text":"hi"}]}""",
        )
        assertTrue(message, message.contains("run_workflow"))
        assertTrue(message, message.contains("agent_turn"))
    }

    @Test fun anActionMissingItsOneRequiredFieldIsRefused() {
        val message = refusal("""{"id":"x","when":{"type":"manual"},"then":[{"type":"run_workflow"}]}""")
        assertTrue(message, message.contains("workflow"))
    }

    @Test fun aRuleSurvivesARoundTripThroughItsOwnFormat() {
        val original = parse(
            """
            {"id":"home-arrival","description":"Lights when I get home",
             "when":{"type":"place","place":"home","transition":"enter"},
             "if":[{"type":"time_between","after":"17:00"},{"type":"day_of_week","days":["mon","fri"]}],
             "then":[{"type":"run_workflow","workflow":"evening-lights"}],
             "guard":{"cooldownMinutes":30,"maxPerDay":3}}
            """,
        )
        val again = AutomationRule.parse(original.toJson())
        assertEquals(original.toJson(), again.toJson())
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), again.conditions[1].days)
        assertEquals(30L * 60_000L, again.guard.cooldownMs)
        assertEquals(3, again.guard.maxPerDay)
    }

    @Test fun bindingFillsPlaceholdersAndLeavesTheRestAlone() {
        val rule = parse(
            """
            {"id":"x","when":{"type":"notification","package":"com.whatsapp"},
             "then":[{"type":"agent_turn","prompt":"{{notification.title}} said it","requiresApproval":true}]}
            """,
        )
        val bound = rule.actions.single().bind(mapOf("notification.title" to "Dad"))
        assertEquals("Dad said it", bound.raw.str("prompt"))
        assertTrue(bound.requiresApproval)
    }

    @Test fun anUnfilledPlaceholderBecomesAGapRatherThanBraces() {
        val rule = parse(
            """
            {"id":"x","when":{"type":"notification","package":"com.whatsapp"},
             "then":[{"type":"notify","text":"from {{notification.title}}"}]}
            """,
        )
        // Read raw: the shared `str` reader trims, and what matters here is
        // that no "{{" survives into the text the user would be shown.
        val text = (rule.actions.single().bind(emptyMap()).raw["text"] as JsonPrimitive).content
        assertEquals("from ", text)
    }

    @Test fun aGuardIsClampedRatherThanTrusted() {
        val rule = parse(
            """
            {"id":"x","when":{"type":"manual"},"then":[{"type":"notify","text":"hi"}],
             "guard":{"cooldownMinutes":99999,"maxPerDay":100000}}
            """,
        )
        assertEquals(AutomationGuard.MAX_COOLDOWN_MS, rule.guard.cooldownMs)
        assertEquals(AutomationGuard.MAX_PER_DAY_CEILING, rule.guard.maxPerDay)
    }

    @Test fun aDefaultGuardIsNotWrittenBackOut() {
        val rule = parse("""{"id":"x","when":{"type":"manual"},"then":[{"type":"notify","text":"hi"}]}""")
        assertNull(rule.toJson()["guard"])
    }

    // --- schedules ---

    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun at(text: String) = ZonedDateTime.parse(text + "+03:00[Asia/Jerusalem]")

    private fun schedule(json: String) =
        AutomationSchedule.parse(Json.parseToJsonElement(json).jsonObject, "x")

    @Test fun aDailyTimeIsDueOnlyInItsOwnMinute() {
        val daily = schedule("""{"at":"19:00"}""")
        assertTrue(daily.isDue(at("2026-09-15T19:00:41")))
        assertFalse(daily.isDue(at("2026-09-15T18:59:59")))
        assertFalse(daily.isDue(at("2026-09-15T19:01:00")))
    }

    @Test fun theNextRunSkipsToTomorrowOnceTodaysTimeHasPassed() {
        val daily = schedule("""{"at":"19:00"}""")
        assertEquals(at("2026-09-15T19:00:00"), daily.nextRunAt(at("2026-09-15T08:00:00")))
        assertEquals(at("2026-09-16T19:00:00"), daily.nextRunAt(at("2026-09-15T19:00:00")))
    }

    @Test fun theNextRunLandsOnTheNextDayTheRuleNames() {
        // 2026-09-15 is a Tuesday.
        val weekly = schedule("""{"at":"07:30","days":["sun","fri"]}""")
        assertEquals(at("2026-09-18T07:30:00"), weekly.nextRunAt(at("2026-09-15T12:00:00")))
        assertFalse(weekly.isDue(at("2026-09-15T07:30:00")))
        assertTrue(weekly.isDue(at("2026-09-18T07:30:00")))
    }

    @Test fun anIntervalShorterThanTheAlarmsCanServeIsRefused() {
        // Below this Android's alarm batching makes the stated interval a
        // fiction and the battery cost stops being invisible.
        try {
            schedule("""{"everyMinutes":5}""")
            fail("expected a refusal")
        } catch (invalid: AutomationFormatException) {
            assertTrue(invalid.message, invalid.message.contains("15"))
        }
    }

    @Test fun aScheduleTakesATimeOrAnInterval_neverBoth() {
        try {
            schedule("""{"at":"19:00","everyMinutes":30}""")
            fail("expected a refusal")
        } catch (invalid: AutomationFormatException) {
            assertTrue(invalid.message, invalid.message.contains("not both"))
        }
    }

    @Test fun aMalformedClockSaysWhatAClockLooksLike() {
        val message = refusal(
            """{"id":"x","when":{"type":"schedule","at":"7pm"},"then":[{"type":"notify","text":"hi"}]}""",
        )
        assertTrue(message, message.contains("19:00"))
    }
}
