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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The last quota Codex reported for one saved account, and when it did. */
data class AccountUsage(
    val accountId: String,
    val limits: List<UsageLimit>,
    val readAtMillis: Long,
    /** The resets this account had banked at its last reading; null when never read. */
    val resetCredits: ResetCredits? = null,
)

/**
 * The last quota reading of every saved account.
 *
 * Codex has one sign-in live at a time, so only that account's quota can be
 * read. Each reading is kept here under the account it belongs to, which lets
 * the home screen widget show every account: the live one as it is now, the
 * others as they were when they were last in use.
 *
 * The file holds percentages, reset times and banked-reset expiry dates
 * only, never credentials.
 */
class AccountUsageBook(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun all(): Map<String, AccountUsage> = synchronized(lock) {
        read().associate { it.accountId to it.toUsage() }
    }

    /**
     * Keep [limits] as the latest reading of [accountId]. Readings of accounts
     * that are not in [saved] any more are dropped on the way, so a removed
     * account does not linger on the widget.
     *
     * [resetCredits] null means "not said this time": the account's last
     * known banked resets stay as they were.
     */
    fun record(
        accountId: String,
        limits: List<UsageLimit>,
        saved: Collection<String>,
        resetCredits: ResetCredits? = null,
    ) = synchronized(lock) {
        val current = read()
        val previous = current.firstOrNull { it.accountId == accountId }
        // An empty list is "not known yet", not "no quota": keep the last real reading.
        if (limits.isEmpty() && (resetCredits == null || previous == null)) return@synchronized
        val kept = current.filter { it.accountId != accountId && it.accountId in saved }
        val windows = if (limits.isEmpty()) previous!!.windows else limits.map(Window::from)
        val readAt = if (limits.isEmpty()) previous!!.readAt else clock()
        val bank = resetCredits?.let(Bank::from) ?: previous?.bank
        write(kept + Entry(accountId, readAt, windows, bank))
    }

    /** Drop the saved reading of [accountId], such as when a saved computer is removed. */
    fun remove(accountId: String) = synchronized(lock) {
        val current = read()
        val kept = current.filter { it.accountId != accountId }
        if (kept.size != current.size) write(kept)
    }

    private fun read(): List<Entry> = runCatching {
        if (file.isFile) json.decodeFromString(Book.serializer(), file.readText()).accounts else emptyList()
    }.getOrDefault(emptyList())

    private fun write(entries: List<Entry>) {
        AtomicFiles.writeText(file, json.encodeToString(Book.serializer(), Book(entries)))
    }

    @Serializable
    private data class Book(val accounts: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val accountId: String, val readAt: Long, val windows: List<Window>, val bank: Bank? = null) {
        fun toUsage() = AccountUsage(accountId, windows.map { it.toLimit() }, readAt, bank?.toCredits())
    }

    @Serializable
    private data class Bank(val available: Int = 0, val credits: List<Credit> = emptyList()) {
        fun toCredits() = ResetCredits(available, credits.map { ResetCredit(it.id, it.title, it.grantedAt, it.expiresAt) })

        companion object {
            fun from(value: ResetCredits) = Bank(value.available, value.credits.map { Credit(it.id, it.title, it.grantedAt, it.expiresAt) })
        }
    }

    @Serializable
    private data class Credit(val id: String = "", val title: String? = null, val grantedAt: Long? = null, val expiresAt: Long? = null)

    @Serializable
    private data class Window(val name: String, val usedPercent: Double? = null, val resetsAt: Long? = null, val windowMinutes: Long? = null) {
        fun toLimit() = UsageLimit(name, usedPercent, resetsAt, windowMinutes)

        companion object {
            fun from(limit: UsageLimit) = Window(limit.name, limit.usedPercent, limit.resetsAt, limit.windowMinutes)
        }
    }
}

/** One account as the widget draws it. */
data class AccountUsageRow(
    val accountId: String,
    /** The part of the email before the "@": the widget has room for a word, not an address. */
    val name: String,
    val live: Boolean,
    /** The fullest window now, or null when this account has never been read. */
    val window: UsageWindow?,
    /** "Updated 3h ago" for an account that is not live; null for the live one. */
    val readText: String?,
    /** Which engine the account signs in to. */
    val engine: EngineKind = EngineKind.CODEX,
    /** Banked resets not yet expired; null when the engine has none or never said. */
    val resetCredits: ResetCredits? = null,
)

/** The signed-in Claude account and the last quota Claude reported for it. */
data class ClaudeAccountUsage(val label: String, val reading: UsageReading?)

/** One saved computer's last Antigravity quota reading, for the home screen widget. */
data class AntigravityAccountUsage(
    val computerId: String,
    val label: String,
    val reading: UsageReading,
    val resetCredits: ResetCredits? = null,
)

/** Pure shaping for the usage widget, kept here so it is tested without Android. */
object AccountUsageOverview {

    /** Id of the Claude row. A Codex account id is a generated one, so it cannot be this. */
    const val CLAUDE_ID = "claude"
    const val ANTIGRAVITY_PREFIX = "antigravity:"

    /**
     * One row per saved Codex account, then the Claude account when one is
     * signed in, then any computer whose Antigravity quota has been read.
     */
    fun rows(
        vault: AccountVaultState,
        readings: Map<String, AccountUsage>,
        nowMillis: Long,
        claude: ClaudeAccountUsage? = null,
        antigravity: List<AntigravityAccountUsage> = emptyList(),
    ): List<AccountUsageRow> =
        codexRows(vault, readings, nowMillis) +
            listOfNotNull(claude?.let { claudeRow(it, nowMillis) }) +
            antigravity.map { antigravityRow(it, nowMillis) }

    private fun antigravityRow(entry: AntigravityAccountUsage, nowMillis: Long): AccountUsageRow {
        val windows = UsageSummary.windows(sinceReset(entry.reading.limits, nowMillis / 1000L), nowMillis / 1000L)
        return AccountUsageRow(
            accountId = ANTIGRAVITY_PREFIX + entry.computerId,
            name = shortName(entry.label),
            live = false,
            window = UsageSummary.primary(windows) ?: windows.firstOrNull(),
            readText = readText(entry.reading.readAtMillis, nowMillis),
            engine = EngineKind.ANTIGRAVITY,
            resetCredits = entry.resetCredits?.current(nowMillis / 1000L),
        )
    }

    private fun claudeRow(claude: ClaudeAccountUsage, nowMillis: Long): AccountUsageRow {
        val reading = claude.reading
        val windows = reading?.let { UsageSummary.windows(sinceReset(it.limits, nowMillis / 1000L), nowMillis / 1000L) }.orEmpty()
        return AccountUsageRow(
            accountId = CLAUDE_ID,
            name = shortName(claude.label),
            live = false,
            window = UsageSummary.primary(windows) ?: windows.firstOrNull(),
            readText = reading?.let { readText(it.readAtMillis, nowMillis) },
            engine = EngineKind.CLAUDE,
        )
    }

    private fun codexRows(vault: AccountVaultState, readings: Map<String, AccountUsage>, nowMillis: Long): List<AccountUsageRow> {
        val nowSeconds = nowMillis / 1000L
        // The live account first: it is the one the next run will spend.
        return vault.accounts.sortedByDescending { it.id == vault.activeId }.map { account ->
            val live = account.id == vault.activeId
            val reading = readings[account.id]
            val windows = reading?.let { UsageSummary.windows(sinceReset(it.limits, nowSeconds), nowSeconds) }.orEmpty()
            AccountUsageRow(
                accountId = account.id,
                name = shortName(account.label),
                live = live,
                window = UsageSummary.primary(windows) ?: windows.firstOrNull(),
                readText = if (live || reading == null) null else readText(reading.readAtMillis, nowMillis),
                resetCredits = reading?.resetCredits?.current(nowSeconds),
            )
        }
    }

    /**
     * A reading of an account that is not live grows old. Once a window's
     * reset time has passed, what it said is no longer true: the window is
     * empty again, whatever it read then.
     */
    fun sinceReset(limits: List<UsageLimit>, nowSeconds: Long): List<UsageLimit> = limits.map { limit ->
        val resetsAt = limit.resetsAt
        if (resetsAt != null && resetsAt > 0L && resetsAt <= nowSeconds && limit.usedPercent != null) {
            limit.copy(usedPercent = 0.0, resetsAt = null)
        } else {
            limit
        }
    }

    /**
     * "3 resets · next expires in 4d", "1 reset · expires in 6h", or null
     * when nothing is banked. Short enough for the widget and a list row.
     */
    fun resetCreditsText(credits: ResetCredits?, nowSeconds: Long): String? {
        val current = credits?.current(nowSeconds) ?: return null
        if (current.available <= 0) return null
        val count = if (current.available == 1) "1 reset" else "${current.available} resets"
        val expires = current.nextToExpire?.expiresAt?.let { untilText(it, nowSeconds) } ?: return count
        return if (current.available == 1) "$count · expires in $expires" else "$count · next expires in $expires"
    }

    /**
     * One banked reset on its own line: what it resets and when it is lost,
     * as "Full reset (Weekly + 5 hr) · expires Wed 2 Dec, 00:10 (in 4d 11h)".
     */
    fun resetCreditLine(credit: ResetCredit, nowSeconds: Long, zone: java.time.ZoneId): String {
        val title = credit.title?.trim()?.ifBlank { null } ?: "Rate limit reset"
        val expires = credit.expiresAt ?: return "$title · no expiry date"
        val at = java.time.Instant.ofEpochSecond(expires).atZone(zone)
        val date = at.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.ENGLISH))
        return "$title · expires $date (in ${untilText(expires, nowSeconds)})"
    }

    /** "4d 3h", "5h 12m", "40m": time left until [atSeconds], never negative. */
    fun untilText(atSeconds: Long, nowSeconds: Long): String {
        val minutes = ((atSeconds - nowSeconds) / 60L).coerceAtLeast(0L)
        val days = minutes / (60L * 24L)
        val hours = (minutes / 60L) % 24L
        return when {
            days > 0L -> if (hours > 0L) "${days}d ${hours}h" else "${days}d"
            minutes >= 60L -> "${minutes / 60L}h ${minutes % 60L}m"
            else -> "${minutes.coerceAtLeast(1L)}m"
        }
    }

    fun shortName(label: String): String {
        val trimmed = label.trim()
        val local = trimmed.substringBefore('@')
        return local.ifBlank { trimmed }.ifBlank { "Account" }
    }

    fun readText(readAtMillis: Long, nowMillis: Long): String {
        val minutes = ((nowMillis - readAtMillis) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1L -> "Updated just now"
            minutes < 60L -> "Updated ${minutes}m ago"
            minutes < 60L * 24L -> "Updated ${minutes / 60L}h ago"
            else -> "Updated ${minutes / (60L * 24L)}d ago"
        }
    }
}
