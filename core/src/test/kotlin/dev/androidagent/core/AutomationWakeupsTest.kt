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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AutomationWakeupsTest {

    private fun at(text: String) = ZonedDateTime.parse("$text+03:00[Asia/Jerusalem]")

    private fun rule(json: String) = AutomationRule.parse(Json.parseToJsonElement(json.trimIndent()).jsonObject)

    private fun scheduled(id: String, at: String, enabled: Boolean = true) = rule(
        """
        {"id":"$id","enabled":$enabled,"when":{"type":"schedule","at":"$at"},
         "then":[{"type":"notify","text":"x"}]}
        """,
    )

    @Test fun oneAlarmServesEveryScheduledRule() {
        // Android charges for each wake-up and caps how many exact alarms an
        // app holds, so the host arms the earliest and re-checks on landing.
        val rules = listOf(scheduled("evening", "19:00"), scheduled("morning", "07:00"), scheduled("noon", "12:00"))
        assertEquals(at("2026-09-15T12:00:00"), AutomationWakeups.nextRunAt(rules, at("2026-09-15T08:00:00")))
        assertEquals(at("2026-09-15T19:00:00"), AutomationWakeups.nextRunAt(rules, at("2026-09-15T12:00:00")))
        assertEquals(at("2026-09-16T07:00:00"), AutomationWakeups.nextRunAt(rules, at("2026-09-15T19:00:00")))
    }

    @Test fun aDisabledRuleDoesNotHoldAnAlarm() {
        val rules = listOf(scheduled("off", "07:00", enabled = false), scheduled("on", "19:00"))
        assertEquals(at("2026-09-15T19:00:00"), AutomationWakeups.nextRunAt(rules, at("2026-09-15T08:00:00")))
    }

    @Test fun rulesWithNoClockNeedNoAlarmAtAll() {
        val rules = listOf(
            rule("""{"id":"n","when":{"type":"notification","package":"com.whatsapp"},"then":[{"type":"notify","text":"x"}]}"""),
            rule("""{"id":"m","when":{"type":"manual"},"then":[{"type":"notify","text":"x"}]}"""),
        )
        assertNull(AutomationWakeups.nextRunAt(rules, at("2026-09-15T08:00:00")))
        assertNull(AutomationWakeups.nextRunAt(emptyList(), at("2026-09-15T08:00:00")))
    }

    @Test fun anIntervalRuleWakesAtItsInterval() {
        val every = rule(
            """{"id":"e","when":{"type":"schedule","everyMinutes":30},"then":[{"type":"notify","text":"x"}]}""",
        )
        assertEquals(at("2026-09-15T08:30:00"), AutomationWakeups.nextRunAt(listOf(every), at("2026-09-15T08:00:00")))
    }

    @Test fun theListenerOnlySeesPackagesAnEnabledRuleNames() {
        // Checked before a title or a body is read, so an app no rule names is
        // dropped without being looked at.
        val rules = listOf(
            rule("""{"id":"a","when":{"type":"notification","package":"com.whatsapp"},"then":[{"type":"notify","text":"x"}]}"""),
            rule("""{"id":"b","when":{"type":"notification","package":"com.Instagram.Android"},"then":[{"type":"notify","text":"x"}]}"""),
            rule("""{"id":"c","enabled":false,"when":{"type":"notification","package":"com.bank.app"},"then":[{"type":"notify","text":"x"}]}"""),
            scheduled("d", "19:00"),
        )
        assertEquals(setOf("com.whatsapp", "com.instagram.android"), AutomationWakeups.watchedPackages(rules))
    }

    @Test fun noNotificationRuleMeansTheListenerHasNothingToDo() {
        assertTrue(AutomationWakeups.watchedPackages(listOf(scheduled("a", "19:00"))).isEmpty())
    }
}
