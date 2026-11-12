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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One account whose short usage window Mike can start: a saved Codex sign-in or the Claude one. */
data class WindowTarget(
    val id: String,
    val engine: EngineKind,
    val label: String,
    /** The last quota reading of this account; empty when it was never read. */
    val limits: List<UsageLimit>,
)

/** The last time Mike tried to start one account's window, and how it went. */
@Serializable
data class WindowAttempt(
    val atMillis: Long,
    val ok: Boolean,
    /** Failures in a row, for the back-off. Zero after a success. */
    val failures: Int = 0,
    val message: String? = null,
)

/** Which accounts to start now, and when to look again. */
data class WindowPlan(val due: List<WindowTarget>, val nextAtMillis: Long?)

/**
 * When an account's short usage window should be started.
 *
 * A plan's 5-hour window does not start when the last one ends: it starts at
 * the first message after that. An account nobody writes to sits with no
 * window running, and the next five hours begin only when the person comes
 * back. Starting it the minute the old one ends means the next reset comes
 * five hours after that minute instead of five hours after whenever they
 * return.
 *
 * Pure: it reads readings and attempts and decides. Nothing here sends a
 * message or writes a file.
 */
object UsageWindowPlanner {

    /** After the reset time, so a clock a little ahead of the server does not start the old window. */
    const val GRACE_MS = 60_000L

    /** The window a reading has no length for: the plans this is for all have five hours. */
    const val DEFAULT_WINDOW_MINUTES = 300L

    private const val FIRST_RETRY_MS = 10L * 60_000L
    private const val LONGEST_RETRY_MS = 3L * 60L * 60_000L
    private const val LONGEST_SHORT_WINDOW_MINUTES = 24L * 60L

    fun plan(targets: List<WindowTarget>, attempts: Map<String, WindowAttempt>, nowMillis: Long): WindowPlan {
        val wakes = targets.associateWith { wakeAt(it, attempts[it.id], nowMillis) }
        val due = wakes.filterValues { it <= nowMillis }.keys.toList()
        val next = wakes.values.filter { it > nowMillis }.minOrNull()
        return WindowPlan(due, next)
    }

    /**
     * When [target] next needs Mike: at or before [nowMillis] means now.
     *
     * - A window that is running needs nothing until it resets.
     * - A limit that is used up (the weekly one, say) holds everything until
     *   it resets: a message then would only fail.
     * - A window that has reset, or was never read, is due, unless Mike
     *   already tried since that reset: then a success waits one window (the
     *   reading after it may have failed) and a failure backs off.
     */
    fun wakeAt(target: WindowTarget, attempt: WindowAttempt?, nowMillis: Long): Long {
        val short = shortWindow(target.limits)
        blockedUntil(target.limits, short, nowMillis)?.let { return it * 1000L + GRACE_MS }
        val resetsAt = short?.resetsAt?.takeIf { it > 0L }?.times(1000L)
        if (resetsAt != null && resetsAt > nowMillis) return resetsAt + GRACE_MS
        val since = resetsAt ?: 0L
        if (attempt != null && attempt.atMillis >= since) {
            return if (attempt.ok) {
                attempt.atMillis + (short?.windowMinutes ?: DEFAULT_WINDOW_MINUTES) * 60_000L
            } else {
                attempt.atMillis + retryDelay(attempt.failures)
            }
        }
        return since + GRACE_MS
    }

    /**
     * The window to keep running: the shortest one of a day or less. A Codex
     * account can carry limits for single models beside the plan's own; the
     * plan's are named "Codex · …" and are the ones a message to the
     * default model starts.
     */
    fun shortWindow(limits: List<UsageLimit>): UsageLimit? {
        val short = limits.filter { (it.windowMinutes ?: 0L) in 1L..LONGEST_SHORT_WINDOW_MINUTES }
        val plan = short.filter { it.name.startsWith(CODEX_PLAN_PREFIX, ignoreCase = true) }
        return plan.ifEmpty { short }.minByOrNull { it.windowMinutes ?: Long.MAX_VALUE }
    }

    /**
     * The latest reset, in epoch seconds, of a limit of the same plan that is
     * used up and has not reset yet. Limits of one model ("weekly · Opus")
     * do not hold a message to another.
     */
    fun blockedUntil(limits: List<UsageLimit>, short: UsageLimit?, nowMillis: Long): Long? {
        val group = short?.name?.let(::group)
        return limits.filter { limit ->
            val full = (limit.usedPercent ?: 0.0) >= 100.0
            val resetsAt = limit.resetsAt ?: 0L
            full && resetsAt * 1000L > nowMillis && group(limit.name) == group
        }.maxOfOrNull { it.resetsAt!! }
    }

    /** 10 minutes, then doubling, at most 3 hours: an offline phone retries soon, a signed-out account rarely. */
    fun retryDelay(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, 10)
        return (FIRST_RETRY_MS shl doublings).coerceAtMost(LONGEST_RETRY_MS)
    }

    /** "Codex · primary" belongs to "Codex"; Claude's "5-hour" and "weekly" have no group, "weekly · Opus" has one. */
    private fun group(name: String): String? = name.substringBefore(" · ", missingDelimiterValue = "").ifEmpty { null }

    private const val CODEX_PLAN_PREFIX = "Codex · "
}

/** What the settings show for one account. */
data class WindowStatus(val target: WindowTarget, val attempt: WindowAttempt?, val nextAtMillis: Long?) {
    /** The last try failed and the next one waits for the back-off. */
    val failing: Boolean
        get() = attempt != null && !attempt.ok &&
            nextAtMillis == attempt.atMillis + UsageWindowPlanner.retryDelay(attempt.failures)

    /** "Started 06:41 · next 11:42", "Could not start: … · next try 07:10", "Starting now". */
    fun line(nowMillis: Long, zone: ZoneId, busy: Boolean): String {
        val next = nextAtMillis
        if (next == null || next <= nowMillis) return if (busy) "Starting now…" else "Starts now"
        if (failing) return "Could not start: ${attempt?.message ?: "unknown error"} · next try ${time(next, nowMillis, zone)}"
        val started = attempt?.takeIf { it.ok }?.let { "Started ${time(it.atMillis, nowMillis, zone)} · " }.orEmpty()
        return "${started}next start ${time(next, nowMillis, zone)}".replaceFirstChar { it.uppercase() }
    }

    private fun time(atMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val pattern = if (at.toLocalDate() == today) "HH:mm" else "EEE HH:mm"
        return at.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    }
}

/**
 * Whether Mike starts usage windows by itself, and its last try for each
 * account. Holds times and short messages only, never credentials.
 */
class UsageWindowJournal(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun enabled(): Boolean = synchronized(lock) { read().enabled }

    fun setEnabled(value: Boolean) = synchronized(lock) {
        val stored = read()
        // A fresh start: old failures belong to the last time it was on.
        write(if (value && !stored.enabled) Book(true) else stored.copy(enabled = value))
    }

    fun attempts(): Map<String, WindowAttempt> = synchronized(lock) { read().attempts }

    /** Keep how starting [id]'s window went. Accounts not in [known] are dropped on the way. */
    fun record(id: String, ok: Boolean, message: String?, known: Collection<String>) = synchronized(lock) {
        val stored = read()
        val failures = if (ok) 0 else (stored.attempts[id]?.takeIf { !it.ok }?.failures ?: 0) + 1
        val kept = stored.attempts.filterKeys { it != id && it in known }
        write(stored.copy(attempts = kept + (id to WindowAttempt(clock(), ok, failures, message?.take(MAX_MESSAGE)))))
    }

    fun status(targets: List<WindowTarget>, nowMillis: Long = clock()): List<WindowStatus> {
        val attempts = attempts()
        return targets.map { target ->
            val attempt = attempts[target.id]
            WindowStatus(target, attempt, UsageWindowPlanner.wakeAt(target, attempt, nowMillis))
        }
    }

    private fun read(): Book = runCatching {
        if (file.isFile) json.decodeFromString(Book.serializer(), file.readText()) else Book()
    }.getOrDefault(Book())

    private fun write(book: Book) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return
        val temp = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            temp.writeText(json.encodeToString(Book.serializer(), book))
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
    private data class Book(val enabled: Boolean = false, val attempts: Map<String, WindowAttempt> = emptyMap())

    private companion object {
        const val MAX_MESSAGE = 300
    }
}
