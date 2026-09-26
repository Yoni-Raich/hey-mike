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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek

class AutomationEditorTest {

    @get:Rule val temp = TemporaryFolder()

    private fun rule(json: String) = AutomationRule.parse(Json.parseToJsonElement(json.trimIndent()).jsonObject)

    private val evening = rule(
        """
        {"id":"evening-post","description":"Post the evening update",
         "when":{"type":"schedule","at":"19:00","days":["sun","mon"]},
         "if":[{"type":"time_between","after":"18:00","before":"23:00"}],
         "then":[{"type":"run_workflow","workflow":"post-update","parameters":{"caption":"Good evening"}},
                 {"type":"notify","title":"Done","text":"Posted."}]}
        """,
    )

    private fun field(rule: AutomationRule, key: String) = AutomationEditor.fields(rule).single { it.key == key }

    /** [rule] after saving [values] through the same path the screen uses. */
    private fun saved(rule: AutomationRule, values: Map<String, String>): AutomationRule {
        val library = AutomationLibrary(File(temp.root, "automations"))
        library.save(rule)
        return library.update(rule.id, AutomationEditor.changes(rule, values))
    }

    @Test fun aRuleBecomesPlainValuesNotJson() {
        val fields = AutomationEditor.fields(evening)
        assertEquals("19:00", field(evening, "when.at").value)
        assertEquals(AutomationEditor.FieldType.TIME, field(evening, "when.at").type)
        assertEquals("mon,sun", field(evening, "when.days").value)
        assertEquals("18:00", field(evening, "if.0.after").value)
        assertEquals("Good evening", field(evening, "then.0.parameters.caption").value)
        assertEquals("Posted.", field(evening, "then.1.text").value)
        assertEquals("20", field(evening, "guard.maxPerDay").value)
        // Nothing on the screen is a brace.
        assertTrue(fields.none { it.value.contains('{') })
    }

    @Test fun eachValueIsGroupedUnderTheConditionOrActionItBelongsTo() {
        assertEquals("Between these times", field(evening, "if.0.before").group)
        assertEquals("Run the \"post-update\" workflow", field(evening, "then.0.parameters.caption").group)
        // Which workflow runs is an id, not a value to type over.
        assertTrue(AutomationEditor.fields(evening).none { it.key == "then.0.workflow" })
        assertEquals("Send me a notification", field(evening, "then.1.title").group)
        assertEquals(AutomationEditor.Section.LIMITS, field(evening, "guard.cooldownMinutes").section)
    }

    @Test fun savingWithoutChangingAnythingChangesNothing() {
        val values = AutomationEditor.fields(evening).associate { it.key to it.value }
        assertTrue(AutomationEditor.changes(evening, values).isEmpty())
    }

    @Test fun movingTheTimeChangesOnlyTheTime() {
        val changes = AutomationEditor.changes(evening, mapOf("when.at" to "20:30"))
        assertEquals(setOf("when"), changes.keys)
        val after = saved(evening, mapOf("when.at" to "20:30"))
        assertEquals(java.time.LocalTime.of(20, 30), after.trigger.schedule!!.at)
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), after.trigger.schedule!!.days)
        assertEquals(2, after.actions.size)
    }

    @Test fun aTimeWrittenLooselyIsSavedTidily() {
        val after = saved(evening, mapOf("when.at" to "7:5"))
        assertEquals(java.time.LocalTime.of(7, 5), after.trigger.schedule!!.at)
    }

    @Test fun clearingTheDaysMeansEveryDay() {
        val after = saved(evening, mapOf("when.days" to ""))
        assertTrue(after.trigger.schedule!!.days.isEmpty())
    }

    @Test fun textInsideAnActionAndAConditionIsEdited() {
        val after = saved(
            evening,
            mapOf(
                "then.0.parameters.caption" to "Good night",
                "then.1.text" to "All posted.",
                "if.0.before" to "22:00",
            ),
        )
        val parameters = after.actions[0].raw["parameters"] as JsonObject
        assertEquals("Good night", parameters.str("caption"))
        assertEquals("post-update", after.actions[0].raw.str("workflow"))
        assertEquals("All posted.", after.actions[1].raw.str("text"))
        assertEquals(java.time.LocalTime.of(22, 0), after.conditions[0].before)
        assertEquals(java.time.LocalTime.of(18, 0), after.conditions[0].after)
    }

    @Test fun anEmptiedOptionalTextIsRemovedNotSavedEmpty() {
        val after = saved(evening, mapOf("then.1.title" to ""))
        assertNull(after.actions[1].raw.str("title"))
    }

    @Test fun theAskFirstSwitchRoundTrips() {
        val on = saved(evening, mapOf("then.1.requiresApproval" to "true"))
        assertTrue(on.actions[1].requiresApproval)
        val off = saved(on, mapOf("then.1.requiresApproval" to "false"))
        assertFalse(off.actions[1].requiresApproval)
    }

    @Test fun limitsAreWrittenAsOneGuardAndKeepTheOthers() {
        val after = saved(evening, mapOf("guard.maxPerDay" to "3", "guard.cooldownMinutes" to "10"))
        assertEquals(3, after.guard.maxPerDay)
        assertEquals(10 * 60_000L, after.guard.cooldownMs)
        assertEquals(AutomationGuard.DEFAULT_VALID_FOR_MS, after.guard.validForMs)
    }

    @Test fun anUntouchedSubMinuteCooldownKeepsItsExactValue() {
        val quick = rule(
            """
            {"id":"quick","when":{"type":"manual"},"then":[{"type":"notify","text":"x"}],
             "guard":{"cooldownMs":30000}}
            """,
        )
        val after = saved(quick, mapOf("guard.maxPerDay" to "5"))
        assertEquals(30_000L, after.guard.cooldownMs)
        assertEquals(5, after.guard.maxPerDay)
    }

    @Test fun aNotificationRuleOffersTheSenderAndTheMessageTest() {
        val dad = rule(
            """
            {"id":"dad","when":{"type":"notification","package":"com.whatsapp","from":"Dad"},
             "if":[{"type":"text","field":"notification.text","contains":"dinner"}],
             "then":[{"type":"agent_turn","prompt":"Tell {{notification.title}} I am on my way."}]}
            """,
        )
        assertEquals("Dad", field(dad, "when.from").value)
        assertEquals("Contains", field(dad, "if.0.contains").label)
        val after = saved(dad, mapOf("when.from" to "Mom", "if.0.contains" to "lunch"))
        assertEquals("Mom", after.trigger.from)
        assertEquals("lunch", after.conditions.single().contains)
        assertEquals("notification.text", after.conditions.single().field)
    }

    @Test fun eachFieldSaysWhatIsWrongWithABadValue() {
        assertNotNull(AutomationEditor.problem(field(evening, "when.at"), "25:00"))
        assertNotNull(AutomationEditor.problem(field(evening, "when.at"), ""))
        assertNull(AutomationEditor.problem(field(evening, "when.at"), "8:30"))
        assertNotNull(AutomationEditor.problem(field(evening, "guard.maxPerDay"), "0"))
        assertNotNull(AutomationEditor.problem(field(evening, "guard.maxPerDay"), "many"))
        assertNull(AutomationEditor.problem(field(evening, "when.days"), ""))
        assertNull(AutomationEditor.problem(field(evening, "then.1.title"), ""))
        assertNotNull(AutomationEditor.problem(field(evening, "then.1.text"), " "))
    }

    @Test fun anIntervalIsANumberWithItsLimits() {
        val every = rule("""{"id":"every","when":{"type":"schedule","everyMinutes":30},"then":[{"type":"notify","text":"x"}]}""")
        val interval = field(every, "when.everyMinutes")
        assertEquals(AutomationEditor.FieldType.NUMBER, interval.type)
        assertNotNull(AutomationEditor.problem(interval, "5"))
        assertEquals(60, saved(every, mapOf("when.everyMinutes" to "60")).trigger.schedule!!.everyMinutes)
    }
}
