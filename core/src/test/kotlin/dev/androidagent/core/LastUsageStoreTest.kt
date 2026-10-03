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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LastUsageStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val nowMillis = 1_800_000_000_000L
    private val nowSeconds = nowMillis / 1000L
    private var clock = nowMillis

    private fun store() = LastUsageStore(folder.root.resolve("claude-usage.json")) { clock }

    private val fiveHour = UsageLimit("5-hour", 12.5, nowSeconds + 3_600, 300L)
    private val weekly = UsageLimit("weekly", 40.0, nowSeconds + 3 * 86_400, 10_080L)

    @Test fun aReadingSurvivesARestartWithItsTime() {
        store().save(listOf(fiveHour, weekly))
        clock += 60_000L
        val read = store().read()!!
        assertEquals(listOf(fiveHour, weekly), read.limits)
        assertEquals("the time it was seen, not the time it was read", nowMillis, read.readAtMillis)
    }

    @Test fun anExpiredWindowIsNotShownAsCurrent() {
        store().save(listOf(fiveHour, weekly))
        // Two hours on: the 5-hour window has reset, the weekly one has not.
        val read = store().read(nowMillis + 2 * 3_600_000L)!!
        assertEquals(listOf(weekly), read.limits)
        // At the reset second itself the window counts as over.
        assertEquals(listOf(weekly), store().read((fiveHour.resetsAt!!) * 1000L)!!.limits)
    }

    @Test fun nothingIsLeftOnceEveryWindowHasReset() {
        store().save(listOf(fiveHour, weekly))
        assertNull(store().read(nowMillis + 4 * 86_400_000L))
    }

    @Test fun aWindowWithoutAResetTimeIsKept() {
        val unknown = UsageLimit("weekly", 10.0, null, 10_080L)
        store().save(listOf(unknown))
        assertEquals(listOf(unknown), store().read(nowMillis + 30 * 86_400_000L)!!.limits)
    }

    @Test fun anEmptyReadingKeepsTheLastOne() {
        store().save(listOf(weekly))
        store().save(emptyList())
        assertEquals(listOf(weekly), store().read()!!.limits)
    }

    @Test fun clearForgetsTheReading() {
        store().save(listOf(weekly))
        store().clear()
        assertNull(store().read())
    }

    @Test fun aDamagedFileReadsAsNothing() {
        folder.root.resolve("claude-usage.json").writeText("{not json")
        assertNull(store().read())
        store().save(listOf(weekly))
        assertEquals(listOf(weekly), store().read()!!.limits)
    }

    @Test fun theSavedReadingKeepsAWindowThatHasReset() {
        store().save(listOf(fiveHour, weekly))
        clock += 2 * 3_600_000L
        // read() leaves the 5-hour window out; the widget gets it whole and shapes it itself.
        assertEquals(listOf(fiveHour, weekly), store().readSaved()!!.limits)
        assertNull(LastUsageStore(folder.root.resolve("none.json")).readSaved())
    }

    @Test fun theAccountNameSurvivesARestartAndGoesOnSignOut() {
        assertNull(store().account())
        store().saveAccount("  yoni@example.com ")
        assertEquals("yoni@example.com", store().account())
        store().clearAccount()
        assertNull(store().account())
        store().saveAccount("yoni@example.com")
        store().clear()
        assertNull("a sign-out takes the name with the reading", store().account())
    }

    @Test fun aBlankAccountNameIsNotSaved() {
        store().saveAccount("   ")
        assertNull(store().account())
    }
}
