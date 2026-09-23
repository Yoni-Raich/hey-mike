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

class AccountUsageBookTest {

    @get:Rule val folder = TemporaryFolder()

    private val nowMillis = 1_800_000_000_000L
    private val nowSeconds = nowMillis / 1000L
    private var clock = nowMillis

    private fun book() = AccountUsageBook(folder.root.resolve("accounts/usage.json")) { clock }

    private fun weekly(percent: Double, resetsIn: Long = 3600) =
        UsageLimit("weekly", percent, nowSeconds + resetsIn, 60L * 24L * 7L)

    private val work = SavedAccount("work", "work@example.com", 1)
    private val home = SavedAccount("home", "home@example.com", 2)

    @Test fun readingsSurviveANewBook() {
        book().record("work", listOf(weekly(40.0)), saved = listOf("work"))
        val read = book().all()["work"]
        assertEquals(40.0, read?.limits?.single()?.usedPercent)
        assertEquals(nowMillis, read?.readAtMillis)
    }

    @Test fun eachAccountKeepsItsOwnReading() {
        val book = book()
        book.record("work", listOf(weekly(40.0)), saved = listOf("work", "home"))
        clock += 60_000L
        book.record("home", listOf(weekly(90.0)), saved = listOf("work", "home"))
        val all = book.all()
        assertEquals(40.0, all["work"]?.limits?.single()?.usedPercent)
        assertEquals(90.0, all["home"]?.limits?.single()?.usedPercent)
    }

    @Test fun anEmptyReadingKeepsTheLastRealOne() {
        // A switch clears the live quota before the new one arrives.
        val book = book()
        book.record("work", listOf(weekly(40.0)), saved = listOf("work"))
        book.record("work", emptyList(), saved = listOf("work"))
        assertEquals(40.0, book.all()["work"]?.limits?.single()?.usedPercent)
    }

    @Test fun removedAccountsAreDropped() {
        val book = book()
        book.record("work", listOf(weekly(40.0)), saved = listOf("work", "home"))
        book.record("home", listOf(weekly(10.0)), saved = listOf("home"))
        assertEquals(setOf("home"), book.all().keys)
    }

    @Test fun aDamagedFileReadsAsEmpty() {
        val file = folder.root.resolve("accounts/usage.json")
        file.parentFile.mkdirs()
        file.writeText("{not json")
        assertTrue(book().all().isEmpty())
    }

    @Test fun theLiveAccountComesFirstAndHasNoAge() {
        val vault = AccountVaultState(listOf(work, home), activeId = "home")
        val readings = mapOf(
            "work" to AccountUsage("work", listOf(weekly(40.0)), nowMillis - 3 * 3_600_000L),
            "home" to AccountUsage("home", listOf(weekly(90.0)), nowMillis),
        )
        val rows = AccountUsageOverview.rows(vault, readings, nowMillis)
        assertEquals(listOf("home", "work"), rows.map { it.name })
        assertTrue(rows[0].live)
        assertNull(rows[0].readText)
        assertEquals("Updated 3h ago", rows[1].readText)
        assertEquals(0.4f, rows[1].window?.fraction ?: -1f, 0.001f)
    }

    @Test fun aWindowThatResetSinceTheReadingIsEmptyAgain() {
        val vault = AccountVaultState(listOf(work, home), activeId = "home")
        val readings = mapOf("work" to AccountUsage("work", listOf(weekly(95.0, resetsIn = -60)), nowMillis - 86_400_000L))
        val row = AccountUsageOverview.rows(vault, readings, nowMillis).first { it.accountId == "work" }
        assertEquals(0f, row.window?.fraction ?: -1f, 0.001f)
        assertNull(row.window?.resetText)
    }

    @Test fun theFullestWindowIsShown() {
        val vault = AccountVaultState(listOf(work), activeId = "work")
        val hourly = UsageLimit("hourly", 70.0, nowSeconds + 600, 60)
        val readings = mapOf("work" to AccountUsage("work", listOf(weekly(20.0), hourly), nowMillis))
        assertEquals("Hourly", AccountUsageOverview.rows(vault, readings, nowMillis).single().window?.label)
    }

    @Test fun anAccountNeverReadHasNoWindow() {
        val rows = AccountUsageOverview.rows(AccountVaultState(listOf(work), activeId = null), emptyMap(), nowMillis)
        assertNull(rows.single().window)
        assertNull(rows.single().readText)
    }

    @Test fun namesAreShortened() {
        assertEquals("yoni", AccountUsageOverview.shortName("yoni@example.com"))
        assertEquals("Codex account", AccountUsageOverview.shortName("Codex account"))
        assertEquals("Account", AccountUsageOverview.shortName("  "))
    }

    @Test fun ageIsCoarse() {
        assertEquals("Updated just now", AccountUsageOverview.readText(nowMillis - 10_000L, nowMillis))
        assertEquals("Updated 5m ago", AccountUsageOverview.readText(nowMillis - 5 * 60_000L, nowMillis))
        assertEquals("Updated 2d ago", AccountUsageOverview.readText(nowMillis - 2 * 86_400_000L, nowMillis))
    }
}
