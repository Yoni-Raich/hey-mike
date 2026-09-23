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

import org.junit.Assert.*
import org.junit.Test

class UsageSummaryTest {

    private val now = 1_800_000_000L

    private fun limit(name: String, percent: Double?, resetsInSeconds: Long?, windowMinutes: Long?) =
        UsageLimit(name, percent, resetsInSeconds?.let { now + it }, windowMinutes)

    @Test fun windowsAreOrderedShortestFirst() {
        val windows = UsageSummary.windows(
            listOf(
                limit("weekly", 20.0, 3600, 60 * 24 * 7),
                limit("hourly", 40.0, 600, 60),
            ),
            now,
        )
        assertEquals(listOf("Hourly", "Weekly"), windows.map { it.label })
    }

    @Test fun theFullestWindowIsTheOneShown() {
        // The meter has one ring, and the window closest to full is the one
        // that will actually stop the next run.
        val windows = UsageSummary.windows(
            listOf(limit("hourly", 12.0, 600, 60), limit("weekly", 88.0, 60000, 60 * 24 * 7)),
            now,
        )
        assertEquals("Weekly", UsageSummary.primary(windows)?.label)
    }

    @Test fun windowsWithoutAPercentageCannotBePrimary() {
        val windows = UsageSummary.windows(
            listOf(limit("unknown", null, null, 60), limit("weekly", 5.0, 600, 60 * 24 * 7)),
            now,
        )
        assertEquals("Weekly", UsageSummary.primary(windows)?.label)
        assertNull(UsageSummary.primary(UsageSummary.windows(listOf(limit("x", null, null, 60)), now)))
    }

    @Test fun percentagesAreClampedIntoTheRingsRange() {
        val windows = UsageSummary.windows(listOf(limit("over", 140.0, null, 60), limit("under", -5.0, null, 60)), now)
        assertEquals(1f, windows[0].fraction)
        assertEquals(0f, windows[1].fraction)
    }

    @Test fun resetTextIsCoarseAndNeverNegative() {
        assertEquals("Resets in 45m", UsageSummary.resetText(now + 45 * 60, now))
        assertEquals("Resets in 2h 14m", UsageSummary.resetText(now + (2 * 60 + 14) * 60, now))
        assertEquals("Resets in 3d 4h", UsageSummary.resetText(now + ((3 * 24 + 4) * 60) * 60, now))
        assertEquals("Resetting now", UsageSummary.resetText(now - 10, now))
        assertNull(UsageSummary.resetText(null, now))
        assertNull(UsageSummary.resetText(0L, now))
    }

    @Test fun aWindowUnderAMinuteStillReadsAsAMinute() {
        // "Resets in 0m" would look broken; the floor is one minute.
        assertEquals("Resets in 1m", UsageSummary.resetText(now + 20, now))
    }

    @Test fun labelsFallBackToTheReportedNameWhenTheWindowIsUnknown() {
        assertEquals("Special plan", UsageSummary.label(UsageLimit("Special plan", 10.0)))
        assertEquals("Usage", UsageSummary.label(UsageLimit("", 10.0)))
        assertEquals("Hourly", UsageSummary.label(UsageLimit("x", 10.0, windowMinutes = 60)))
        assertEquals("Weekly", UsageSummary.label(UsageLimit("x", 10.0, windowMinutes = 60 * 24 * 7)))
        assertEquals("30-day", UsageSummary.label(UsageLimit("x", 10.0, windowMinutes = 60 * 24 * 30)))
    }

    @Test fun twoWindowsThatRoundToTheSameNameAreSpelledOut() {
        // Seen on a real account: two limits both landing in the weekly band,
        // which drew two rows labelled "Weekly" with different numbers.
        val windows = UsageSummary.windows(
            listOf(
                limit("code", 44.0, 3600, 60 * 24 * 7),
                limit("chat", 56.0, 3600, 60 * 24 * 5),
            ),
            now,
        )
        assertEquals(listOf("Chat", "Code"), windows.map { it.label })
    }

    @Test fun twoWindowsOfTheSameLengthFallBackToTheirNames() {
        // Seen on a real account: two seven-day limits with different reset
        // times. Only the engine's name tells them apart.
        val windows = UsageSummary.windows(
            listOf(
                limit("gpt-5", 44.0, 3600, 60 * 24 * 7),
                limit("codex", 56.0, 7200, 60 * 24 * 7),
            ),
            now,
        )
        assertEquals(listOf("Gpt-5", "Codex"), windows.map { it.label })
    }

    @Test fun namelessCollidingWindowsFallBackToTheirLength() {
        val windows = UsageSummary.windows(
            listOf(limit("", 44.0, 3600, 60 * 24 * 7), limit("", 56.0, 3600, 60 * 24 * 5)),
            now,
        )
        assertEquals(listOf("5-day", "7-day"), windows.map { it.label })
    }

    @Test fun aUniqueWindowKeepsItsFriendlyName() {
        val windows = UsageSummary.windows(
            listOf(limit("a", 10.0, 600, 60), limit("b", 20.0, 600, 60 * 24 * 7)),
            now,
        )
        assertEquals(listOf("Hourly", "Weekly"), windows.map { it.label })
    }

    @Test fun theSpokenFormReportsWhatIsLeftNotWhatIsUsed() {
        val windows = UsageSummary.windows(listOf(limit("hourly", 74.0, 3600, 60)), now)
        val spoken = UsageSummary.spoken(UsageSummary.primary(windows))
        assertTrue(spoken, spoken.contains("26% left"))
        assertTrue(spoken, spoken.contains("Resets in 1h 0m"))
        assertEquals("Usage unavailable", UsageSummary.spoken(null))
    }
}
