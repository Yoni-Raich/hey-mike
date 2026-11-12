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

import dev.androidagent.core.UsageWindowPlanner.GRACE_MS
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsageWindowsTest {

    @get:Rule val folder = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val nowSeconds = now / 1000L
    private val hour = 3_600_000L

    private fun codex(id: String = "a", vararg limits: UsageLimit) = WindowTarget(id, EngineKind.CODEX, "$id@example.com", limits.toList())
    private fun fiveHour(resetsInSeconds: Long?, used: Double = 10.0, name: String = "codex · primary") =
        UsageLimit(name, used, resetsInSeconds?.let { nowSeconds + it }, 300L)
    private fun weekly(resetsInSeconds: Long, used: Double, name: String = "codex · secondary") =
        UsageLimit(name, used, nowSeconds + resetsInSeconds, 10_080L)

    @Test fun aRunningWindowWaitsForItsReset() {
        val target = codex(limits = arrayOf(fiveHour(resetsInSeconds = 7200), weekly(86_400, 40.0)))
        assertEquals(now + 2 * hour + GRACE_MS, UsageWindowPlanner.wakeAt(target, null, now))
        val plan = UsageWindowPlanner.plan(listOf(target), emptyMap(), now)
        assertTrue(plan.due.isEmpty())
        assertEquals(now + 2 * hour + GRACE_MS, plan.nextAtMillis)
    }

    @Test fun aWindowThatResetIsDue() {
        val target = codex(limits = arrayOf(fiveHour(resetsInSeconds = -600)))
        val plan = UsageWindowPlanner.plan(listOf(target), emptyMap(), now)
        assertEquals(listOf(target), plan.due)
        assertNull(plan.nextAtMillis)
    }

    @Test fun theResetItselfWaitsForTheGraceMinute() {
        val target = codex(limits = arrayOf(fiveHour(resetsInSeconds = -10)))
        val wake = UsageWindowPlanner.wakeAt(target, null, now)
        assertEquals(now - 10_000L + GRACE_MS, wake)
        assertTrue(wake > now)
    }

    @Test fun anAccountNeverReadIsDue() {
        assertTrue(UsageWindowPlanner.wakeAt(codex(), null, now) <= now)
        // A window the plan reports as not started has no reset time.
        assertTrue(UsageWindowPlanner.wakeAt(codex(limits = arrayOf(fiveHour(null, used = 0.0))), null, now) <= now)
    }

    @Test fun aSuccessSinceTheResetWaitsOneWindowEvenWithoutANewReading() {
        val target = codex(limits = arrayOf(fiveHour(resetsInSeconds = -3600)))
        val tried = WindowAttempt(now - 1000, ok = true)
        assertEquals(now - 1000 + 5 * hour, UsageWindowPlanner.wakeAt(target, tried, now))
    }

    @Test fun aFailureBacksOffAndAnOlderAttemptDoesNotCount() {
        val target = codex(limits = arrayOf(fiveHour(resetsInSeconds = -3600)))
        val failed = WindowAttempt(now - 1000, ok = false, failures = 2)
        assertEquals(now - 1000 + 20 * 60_000L, UsageWindowPlanner.wakeAt(target, failed, now))
        val beforeReset = WindowAttempt(now - 2 * hour, ok = false, failures = 5)
        assertTrue(UsageWindowPlanner.wakeAt(target, beforeReset, now) <= now)
    }

    @Test fun retriesGrowAndStopGrowingAtThreeHours() {
        assertEquals(10 * 60_000L, UsageWindowPlanner.retryDelay(1))
        assertEquals(40 * 60_000L, UsageWindowPlanner.retryDelay(3))
        assertEquals(3 * hour, UsageWindowPlanner.retryDelay(9))
        assertEquals(3 * hour, UsageWindowPlanner.retryDelay(400))
    }

    @Test fun aUsedUpWeeklyLimitHoldsUntilItResets() {
        val target = codex(limits = arrayOf(fiveHour(resetsInSeconds = -3600), weekly(86_400, 100.0)))
        assertEquals(now + 24 * hour + GRACE_MS, UsageWindowPlanner.wakeAt(target, null, now))
    }

    @Test fun aUsedUpLimitOfAnotherModelDoesNotHold() {
        val target = codex(
            limits = arrayOf(
                fiveHour(resetsInSeconds = -3600),
                weekly(86_400, 100.0, name = "codex_bengalfox · secondary"),
            ),
        )
        assertTrue(UsageWindowPlanner.wakeAt(target, null, now) <= now)
        val claude = WindowTarget(
            "claude", EngineKind.CLAUDE, "me",
            listOf(fiveHour(-60, name = "5-hour"), weekly(86_400, 100.0, name = "weekly · Opus")),
        )
        assertTrue(UsageWindowPlanner.wakeAt(claude, null, now) <= now)
        val claudeWeekly = claude.copy(limits = claude.limits + weekly(3600, 100.0, name = "weekly"))
        assertEquals(now + hour + GRACE_MS, UsageWindowPlanner.wakeAt(claudeWeekly, null, now))
    }

    @Test fun thePlansOwnWindowWinsOverAModelWindow() {
        val model = fiveHour(resetsInSeconds = -3600, name = "codex_bengalfox · primary")
        val plan = fiveHour(resetsInSeconds = 3600)
        assertEquals(plan, UsageWindowPlanner.shortWindow(listOf(model, plan, weekly(1, 1.0))))
        assertEquals(model, UsageWindowPlanner.shortWindow(listOf(model, weekly(1, 1.0, name = "x · secondary"))))
        assertNull(UsageWindowPlanner.shortWindow(listOf(weekly(1, 1.0))))
    }

    @Test fun theNextWakeIsTheEarliestOfAllAccounts() {
        val soon = codex("a", fiveHour(resetsInSeconds = 600))
        val later = codex("b", fiveHour(resetsInSeconds = 6000))
        val due = codex("c", fiveHour(resetsInSeconds = -6000))
        val plan = UsageWindowPlanner.plan(listOf(later, due, soon), emptyMap(), now)
        assertEquals(listOf(due), plan.due)
        assertEquals(now + 600_000L + GRACE_MS, plan.nextAtMillis)
    }

    @Test fun theJournalCountsFailuresAndForgetsRemovedAccounts() {
        var clock = now
        val journal = UsageWindowJournal(folder.root.resolve("accounts/usage-windows.json")) { clock }
        assertFalse(journal.enabled())
        journal.setEnabled(true)
        assertTrue(journal.enabled())
        journal.record("a", ok = false, message = "offline", known = listOf("a", "b"))
        clock += 1000
        journal.record("a", ok = false, message = "offline", known = listOf("a", "b"))
        journal.record("b", ok = true, message = null, known = listOf("a", "b"))
        assertEquals(2, journal.attempts().getValue("a").failures)
        assertEquals(now + 1000, journal.attempts().getValue("a").atMillis)
        journal.record("a", ok = true, message = null, known = listOf("a"))
        assertEquals(0, journal.attempts().getValue("a").failures)
        assertEquals(setOf("a"), journal.attempts().keys)
    }

    @Test fun turningItOnAgainStartsClean() {
        val journal = UsageWindowJournal(folder.root.resolve("w.json")) { now }
        journal.setEnabled(true)
        journal.record("a", ok = false, message = "signed out", known = listOf("a"))
        journal.setEnabled(false)
        assertFalse(journal.enabled())
        assertEquals(1, journal.attempts().size)
        journal.setEnabled(true)
        assertTrue(journal.attempts().isEmpty())
    }

    @Test fun theSettingsLineSaysWhatHappensNext() {
        val zone = java.time.ZoneOffset.UTC
        // 2027-01-15T08:00:00Z
        val morning = 1_800_000_000_000L - (1_800_000_000_000L % 86_400_000L) + 8 * hour
        val running = WindowTarget("a", EngineKind.CODEX, "a", listOf(UsageLimit("codex · primary", 1.0, morning / 1000 + 3 * 3600, 300L)))
        val started = UsageWindowJournal(folder.root.resolve("x.json")) { morning - hour }.run {
            record("a", ok = true, message = null, known = listOf("a"))
            status(listOf(running), morning).single()
        }
        assertEquals("Started 07:00 · next start 11:01", started.line(morning, zone, busy = false))

        val idle = WindowTarget("b", EngineKind.CLAUDE, "b", emptyList())
        val due = WindowStatus(idle, null, UsageWindowPlanner.wakeAt(idle, null, morning))
        assertEquals("Starts now", due.line(morning, zone, busy = false))
        assertEquals("Starting now…", due.line(morning, zone, busy = true))

        val failed = WindowAttempt(morning - 60_000L, ok = false, failures = 1, message = "offline")
        val failing = WindowStatus(idle, failed, UsageWindowPlanner.wakeAt(idle, failed, morning))
        assertTrue(failing.failing)
        assertEquals("Could not start: offline · next try 08:09", failing.line(morning, zone, busy = false))

        val tomorrow = WindowStatus(running, null, morning + 20 * hour)
        assertTrue(tomorrow.line(morning, zone, busy = false).startsWith("Next start "))
        assertTrue(tomorrow.line(morning, zone, busy = false).endsWith(" 04:00"))
    }

    @Test fun aDamagedJournalReadsAsOff() {
        val file = folder.root.resolve("w.json").apply { writeText("{not json") }
        val journal = UsageWindowJournal(file) { now }
        assertFalse(journal.enabled())
        assertTrue(journal.attempts().isEmpty())
    }
}
