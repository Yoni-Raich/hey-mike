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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSummaryTest {

    @Test fun theLineNamesEveryBucketOfALongRun() {
        val line = RunSummary.line(
            RunMetrics(firstResponseMs = 2_400, totalMs = 107_000, toolCalls = 12, toolMs = 31_000, approvalMs = 18_000),
        )!!
        assertEquals(
            "Run summary: 1m 47s total - 58s thinking, 31s on the phone across 12 calls, " +
                "18s waiting for you. First reply after 2.4s.",
            line,
        )
    }

    @Test fun waitingIsNamedOnlyWhenSomeoneWasAsked() {
        val line = RunSummary.line(RunMetrics(firstResponseMs = null, totalMs = 9_000, toolCalls = 1, toolMs = 2_000))!!
        // An empty bucket reads as a delay that happened, so it is left out.
        assertTrue(line, !line.contains("waiting"))
        assertTrue(line, line.contains("1 call"))
        // Nothing to say about a first reply that was never measured.
        assertTrue(line, !line.contains("First reply"))
        assertTrue(line, line.contains("7.0s thinking"))
    }

    @Test fun aRunThatTouchedNothingGetsNoLine() {
        // One bucket is not a breakdown, and a line under every short answer
        // teaches the user to skip it.
        assertNull(RunSummary.line(RunMetrics(firstResponseMs = 800, totalMs = 3_200, toolCalls = 0, toolMs = 0)))
        assertNull(RunSummary.line(RunMetrics(firstResponseMs = null, totalMs = 0, toolCalls = 4, toolMs = 0)))
    }

    @Test fun thinkingNeverGoesNegativeWhenTheBucketsOverrunTheClock() {
        // Rounding and a clock read at slightly different moments must not
        // produce "-1s thinking".
        val metrics = RunMetrics(firstResponseMs = null, totalMs = 1_000, toolCalls = 1, toolMs = 900, approvalMs = 400)
        assertEquals(0L, metrics.thinkingMs)
        assertTrue(RunSummary.line(metrics)!!.contains("0ms thinking"))
    }

    @Test fun durationsReadTheWayAPersonWouldSayThem() {
        assertEquals("0ms", RunSummary.duration(0))
        assertEquals("840ms", RunSummary.duration(840))
        assertEquals("1.2s", RunSummary.duration(1_240))
        assertEquals("9.9s", RunSummary.duration(9_940))
        assertEquals("12s", RunSummary.duration(12_400))
        assertEquals("1m", RunSummary.duration(60_000))
        assertEquals("1m 47s", RunSummary.duration(106_600))
        assertEquals("2m 30s", RunSummary.duration(150_000))
    }
}
