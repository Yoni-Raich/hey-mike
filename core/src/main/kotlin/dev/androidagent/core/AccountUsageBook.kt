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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The last quota Codex reported for one saved account, and when it did. */
data class AccountUsage(val accountId: String, val limits: List<UsageLimit>, val readAtMillis: Long)

/**
 * The last quota reading of every saved account.
 *
 * Codex has one sign-in live at a time, so only that account's quota can be
 * read. Each reading is kept here under the account it belongs to, which lets
 * the home screen widget show every account: the live one as it is now, the
 * others as they were when they were last in use.
 *
 * The file holds percentages and reset times only, never credentials.
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
     */
    fun record(accountId: String, limits: List<UsageLimit>, saved: Collection<String>) = synchronized(lock) {
        // An empty list is "not known yet", not "no quota": keep the last real reading.
        if (limits.isEmpty()) return@synchronized
        val kept = read().filter { it.accountId != accountId && it.accountId in saved }
        write(kept + Entry(accountId, clock(), limits.map(Window::from)))
    }

    private fun read(): List<Entry> = runCatching {
        if (file.isFile) json.decodeFromString(Book.serializer(), file.readText()).accounts else emptyList()
    }.getOrDefault(emptyList())

    private fun write(entries: List<Entry>) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return
        val temp = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            temp.writeText(json.encodeToString(Book.serializer(), Book(entries)))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    @Serializable
    private data class Book(val accounts: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val accountId: String, val readAt: Long, val windows: List<Window>) {
        fun toUsage() = AccountUsage(accountId, windows.map { it.toLimit() }, readAt)
    }

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
)

/** Pure shaping for the usage widget, kept here so it is tested without Android. */
object AccountUsageOverview {

    fun rows(vault: AccountVaultState, readings: Map<String, AccountUsage>, nowMillis: Long): List<AccountUsageRow> {
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
