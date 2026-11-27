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

    private fun bank(vararg expiresIn: Long) = ResetCredits(
        expiresIn.size,
        expiresIn.mapIndexed { i, seconds -> ResetCredit("c$i", "Full reset (Weekly + 5 hr)", nowSeconds - 86_400L, nowSeconds + seconds) },
    )

    @Test fun bankedResetsSurviveANewBookAndAReadingWithoutThem() {
        book().record("work", listOf(weekly(40.0)), saved = listOf("work"), resetCredits = bank(3_600L, 86_400L))
        // A later update that only moved the windows keeps the count.
        clock += 60_000L
        book().record("work", listOf(weekly(50.0)), saved = listOf("work"))
        val read = book().all()["work"]
        assertEquals(50.0, read?.limits?.single()?.usedPercent)
        assertEquals(2, read?.resetCredits?.available)
        assertEquals(nowSeconds + 3_600L, read?.resetCredits?.credits?.first()?.expiresAt)
    }

    @Test fun bankedResetsAloneUpdateAKnownAccountWithoutTouchingItsWindows() {
        book().record("work", listOf(weekly(40.0)), saved = listOf("work"))
        clock += 60_000L
        book().record("work", emptyList(), saved = listOf("work"), resetCredits = bank(3_600L))
        val read = book().all()["work"]
        assertEquals(nowMillis, read?.readAtMillis)
        assertEquals(40.0, read?.limits?.single()?.usedPercent)
        assertEquals(1, read?.resetCredits?.available)
        // With nothing known yet, resets alone do not create a reading.
        book().record("home", emptyList(), saved = listOf("work", "home"), resetCredits = bank(3_600L))
        assertNull(book().all()["home"])
    }

    @Test fun anEmptyBankReplacesTheOldCount() {
        book().record("work", listOf(weekly(40.0)), saved = listOf("work"), resetCredits = bank(3_600L))
        book().record("work", listOf(weekly(40.0)), saved = listOf("work"), resetCredits = ResetCredits(0))
        assertEquals(0, book().all()["work"]?.resetCredits?.available)
    }

    @Test fun expiredResetsDropOutOfTheCountAndTheRow() {
        val credits = bank(-60L, 4L * 86_400L + 3_600L)
        assertEquals(1, credits.current(nowSeconds).available)
        val vault = AccountVaultState(listOf(work), activeId = "work")
        val usage = AccountUsage("work", listOf(weekly(10.0)), nowMillis, credits)
        val row = AccountUsageOverview.rows(vault, mapOf("work" to usage), nowMillis).single()
        assertEquals(1, row.resetCredits?.available)
        assertEquals("1 reset · expires in 4d 1h", AccountUsageOverview.resetCreditsText(row.resetCredits, nowSeconds))
    }

    @Test fun resetTextSaysTheCountAndTheSoonestExpiry() {
        assertEquals("3 resets · next expires in 2h 0m", AccountUsageOverview.resetCreditsText(bank(86_400L, 7_200L, 900_000L), nowSeconds))
        assertNull(AccountUsageOverview.resetCreditsText(ResetCredits(0), nowSeconds))
        assertNull(AccountUsageOverview.resetCreditsText(null, nowSeconds))
        assertEquals("2 resets", AccountUsageOverview.resetCreditsText(ResetCredits(2), nowSeconds))
        val line = AccountUsageOverview.resetCreditLine(
            ResetCredit("c", "Full reset (Weekly + 5 hr)", expiresAt = 1_796_163_015L), 1_796_163_015L - 3_600L, java.time.ZoneId.of("Asia/Jerusalem"),
        )
        assertEquals("Full reset (Weekly + 5 hr) · expires Wed 2 Dec, 00:10 (in 1h 0m)", line)
    }

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

    @Test fun theClaudeAccountIsTheLastRowWithItsOwnReading() {
        val vault = AccountVaultState(listOf(work, home), activeId = "home")
        val claude = ClaudeAccountUsage("yoni@example.com", UsageReading(listOf(weekly(18.0)), nowMillis - 2 * 3_600_000L))
        val rows = AccountUsageOverview.rows(vault, emptyMap(), nowMillis, claude)
        assertEquals(listOf("home", "work", "yoni"), rows.map { it.name })
        val row = rows.last()
        assertEquals(EngineKind.CLAUDE, row.engine)
        assertEquals(AccountUsageOverview.CLAUDE_ID, row.accountId)
        assertFalse(row.live)
        assertEquals("Updated 2h ago", row.readText)
        assertEquals(0.18f, row.window?.fraction ?: -1f, 0.001f)
        assertTrue(rows.dropLast(1).all { it.engine == EngineKind.CODEX })
    }

    @Test fun claudeAloneStillMakesARow() {
        val claude = ClaudeAccountUsage("yoni@example.com", null)
        val row = AccountUsageOverview.rows(AccountVaultState(), emptyMap(), nowMillis, claude).single()
        assertNull("signed in, never read", row.window)
        assertNull(row.readText)
        assertEquals("yoni", row.name)
    }

    @Test fun aClaudeWindowThatResetSinceTheReadingIsEmptyAgain() {
        val claude = ClaudeAccountUsage("yoni@example.com", UsageReading(listOf(weekly(95.0, resetsIn = -60)), nowMillis - 86_400_000L))
        val row = AccountUsageOverview.rows(AccountVaultState(), emptyMap(), nowMillis, claude).single()
        assertEquals(0f, row.window?.fraction ?: -1f, 0.001f)
    }

    @Test fun noClaudeAccountAddsNoRow() {
        val vault = AccountVaultState(listOf(work), activeId = "work")
        assertEquals(1, AccountUsageOverview.rows(vault, emptyMap(), nowMillis, claude = null).size)
    }

    @Test fun removingAnEntryDropsItFromDisk() {
        val book = book()
        book.record("pc", listOf(weekly(25.0)), saved = listOf("pc", "server"))
        book.record("server", listOf(weekly(60.0)), saved = listOf("pc", "server"))
        book.remove("pc")
        assertEquals(setOf("server"), book.all().keys)
    }

    @Test fun antigravityRowCarriesItsBankedResetsWithoutExpiredOnes() {
        val vault = AccountVaultState(listOf(work), activeId = "work")
        val bank = ResetCredits(
            2,
            listOf(ResetCredit("gone", expiresAt = nowSeconds - 1), ResetCredit("kept", expiresAt = nowSeconds + 7_200)),
        )
        val agy = listOf(
            AntigravityAccountUsage("pc-1", "Pc", UsageReading(listOf(UsageLimit("Gemini · 5-hour", 10.0, nowSeconds + 900, 300)), nowMillis), bank),
        )
        val row = AccountUsageOverview.rows(vault, emptyMap(), nowMillis, antigravity = agy).last()
        assertEquals(1, row.resetCredits?.available)
        assertEquals("kept", row.resetCredits?.credits?.single()?.id)
    }

    @Test fun antigravityComputersAppearWithTheirOwnReadingsAndResets() {
        val vault = AccountVaultState(listOf(work), activeId = "work")
        val agy = listOf(
            AntigravityAccountUsage(
                computerId = "pc-1",
                label = "Pc",
                reading = UsageReading(
                    listOf(
                        UsageLimit("Gemini · 5-hour", 87.0, nowSeconds + 900, 300),
                        UsageLimit("Gemini · Weekly", 23.0, nowSeconds + 600_000, 10_080),
                    ),
                    nowMillis - 5 * 60_000L,
                ),
            ),
            AntigravityAccountUsage(
                computerId = "srv-1",
                label = "Server",
                reading = UsageReading(
                    listOf(UsageLimit("Gemini · 5-hour", 95.0, nowSeconds - 60, 300)),
                    nowMillis - 3 * 3_600_000L,
                ),
            ),
        )
        val rows = AccountUsageOverview.rows(vault, emptyMap(), nowMillis, claude = null, antigravity = agy)
        assertEquals(listOf("work", "Pc", "Server"), rows.map { it.name })
        val pc = rows[1]
        assertEquals(EngineKind.ANTIGRAVITY, pc.engine)
        assertEquals(AccountUsageOverview.ANTIGRAVITY_PREFIX + "pc-1", pc.accountId)
        assertFalse(pc.live)
        assertEquals("Updated 5m ago", pc.readText)
        assertEquals(0.87f, pc.window?.fraction ?: -1f, 0.001f)
        val server = rows[2]
        assertEquals(EngineKind.ANTIGRAVITY, server.engine)
        assertEquals(0f, server.window?.fraction ?: -1f, 0.001f)
    }
}
