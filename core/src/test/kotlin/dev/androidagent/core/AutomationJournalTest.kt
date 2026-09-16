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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationJournalTest {

    @get:Rule val temp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = ZonedDateTime.parse("2026-09-15T21:40:00+03:00[Asia/Jerusalem]")

    private fun file() = File(temp.root, "automations/${AutomationJournal.FILE_NAME}")
    private fun journal() = AutomationJournal(file(), zone)

    @Test fun aFireSurvivesTheProcessThatRecordedIt() {
        // The alarm that woke the phone is long gone by the time anyone
        // notices the same thing was posted twice.
        journal().record("post", now)
        assertEquals(now.toInstant().toEpochMilli(), journal().lastFiredAt("post"))
        assertEquals(1, journal().firedOn("post", now.toLocalDate()))
    }

    @Test fun aRuleThatNeverFiredHasNoHistory() {
        assertNull(journal().lastFiredAt("ghost"))
        assertEquals(0, journal().firedOn("ghost", now.toLocalDate()))
    }

    @Test fun firesAreCountedPerLocalDay() {
        val journal = journal()
        journal.record("post", now)
        journal.record("post", now.minusHours(3))
        journal.record("post", now.minusDays(1))
        assertEquals(2, journal.firedOn("post", now.toLocalDate()))
        assertEquals(1, journal.firedOn("post", now.minusDays(1).toLocalDate()))
    }

    @Test fun oldEntriesAreDroppedSoTheFileStaysBounded() {
        val journal = journal()
        journal.record("post", now.minusDays(30))
        journal.record("post", now)
        assertEquals(now.toInstant().toEpochMilli(), journal.lastFiredAt("post"))
        assertEquals(0, journal.firedOn("post", now.minusDays(30).toLocalDate()))
    }

    @Test fun onlyTimestampsAreKept() {
        // A log of what the notification said would quietly become the most
        // sensitive file on the device.
        journal().record("dad-after-seven", now)
        val text = file().readText()
        assertTrue(text.contains("dad-after-seven"))
        assertEquals(setOf("version", "fires"), Regex("\"(version|fires)\"").findAll(text).map { it.groupValues[1] }.toSet())
    }

    @Test fun aCorruptJournalReadsAsEmptyRatherThanThrowing() {
        // A rule that cannot run because its journal is unreadable is worse
        // than one that fires once more than it should.
        file().parentFile.mkdirs()
        file().writeText("{ not json")
        assertNull(journal().lastFiredAt("post"))
        journal().record("post", now)
        assertEquals(1, journal().firedOn("post", now.toLocalDate()))
    }

    @Test fun trackedNamesTheRulesWithHistory() {
        val journal = journal()
        journal.record("a", now)
        journal.record("b", now)
        assertEquals(setOf("a", "b"), journal.tracked())
    }
}
