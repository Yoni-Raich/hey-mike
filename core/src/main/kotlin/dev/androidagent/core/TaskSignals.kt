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

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the notification listener hands messages to the task board. [wants]
 * is asked with the package name alone, before any title or text is read, so
 * an app the user excluded is never read at all.
 */
interface TaskSignalSink {
    val ready: Boolean get() = true
    fun wants(app: String): Boolean
    fun onMessage(message: CapturedMessage)
}

/** One message as a notification shows it, before the inbox decides to keep it. */
data class CapturedMessage(
    val app: String,
    val conversation: String?,
    val sender: String?,
    val text: String,
    val at: Long,
    val group: Boolean,
    /** The notification's own key, so its later updates are recognised. */
    val notificationKey: String? = null,
    /** Set by the platform reader for messaging style or a known mail/message source. */
    val fromPerson: Boolean = false,
)

/**
 * What never enters the inbox. Verification codes and payment confirmations
 * are dropped before storage: a code in a model's context is a risk, and it is
 * never a task. This is not a setting.
 */
object SignalFilter {
    /** Apps whose notifications are codes, banking or sign-in prompts. */
    private val NEVER_APPS = setOf(
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
        "com.twofasapp", "org.fedorahosted.freeotp", "com.lastpass.authenticator",
        "com.google.android.gms", "com.android.systemui", "android", "com.android.phone",
        "com.android.providers.downloads", "com.google.android.apps.messaging.otp",
    )

    private val CODE_WORDS = Regex(
        "(?i)(verification|security|login|log-in|sign[- ]?in|one[- ]?time|confirmation|auth(entication)?|access)\\s*(code|pin|password)" +
            "|\\botp\\b|\\b2fa\\b|passcode|קוד\\s*(אימות|אבטחה|כניסה|חד[- ]?פעמי|זיהוי)|סיסמה\\s*חד[- ]?פעמית|קוד\\s*ה?אימות",
    )
    private val PAYMENT_WORDS = Regex(
        "(?i)(card ending|charged|transaction (approved|declined)|payment (approved|confirmation)|cvv)" +
            "|חיוב\\s*(בכרטיס|אושר)|עסקה\\s*(אושרה|בכרטיס)|אישור\\s*עסקה",
    )
    private val DIGITS = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    fun drop(app: String, text: String, ownPackage: String): Boolean {
        if (app == ownPackage || app in NEVER_APPS) return true
        if (CODE_WORDS.containsMatchIn(text) && DIGITS.containsMatchIn(text)) return true
        if (PAYMENT_WORDS.containsMatchIn(text)) return true
        return false
    }

    /** A short source label from a package name, for the card. */
    fun sourceOf(app: String): String = when {
        app.startsWith("com.whatsapp") -> "whatsapp"
        app.contains("gmail") || app.contains(".email") || app.contains("outlook") || app.contains("mail") -> "email"
        app.contains("telegram") || app.contains("messaging") || app.contains("signal") || app.contains("mms") -> "message"
        else -> "notification"
    }
}

class SignalInboxFullException : IllegalStateException("Task capture is waiting for space in the inbox.")

/**
 * New things for discovery to look at, kept until a pass has used them.
 *
 * Text is the most sensitive thing on the board, so it lives here briefly: a
 * successful pass deletes its batch; a batch that keeps failing is deleted
 * after [MAX_AGE_MS] anyway.
 */
class SignalInbox(
    private val store: TaskBoardStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<TaskSignal>>(emptyList())
    val pending: StateFlow<List<TaskSignal>> = mutable.asStateFlow()
    @Volatile private var loaded = false

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        mutable.value = store.loadSignals().sortedBy { it.capturedAt }
        loaded = true
    }
    suspend fun refresh() = lock.withLock { mutable.value = store.loadSignals().sortedBy { it.capturedAt } }

    /**
     * Keep [message] if the user's settings allow it. Returns the stored
     * signal, or null when it was filtered, excluded or already stored.
     */
    suspend fun capture(message: CapturedMessage, settings: TaskBoardSettings, ownPackage: String): TaskSignal? =
        captureResult(message, settings, ownPackage).signal

    data class CaptureResult(val signal: TaskSignal? = null, val lostPeople: Int = 0)

    suspend fun captureResult(message: CapturedMessage, settings: TaskBoardSettings, ownPackage: String): CaptureResult {
        if (!settings.discoveryOn) return CaptureResult()
        val text = message.text.trim()
        if (text.isEmpty() || message.app in settings.excludedApps) return CaptureResult()
        if (message.group && !settings.includeGroups) return CaptureResult()
        message.conversation?.let { if ("${message.app}|$it" in settings.excludedConversations) return CaptureResult() }
        if (SignalFilter.drop(message.app, "${message.conversation.orEmpty()} $text", ownPackage)) return CaptureResult()
        return lock.withLock {
            val clock = now()
            val line = SignalRows.lineId(message, text)
            val previous = mutable.value.firstOrNull { SignalRows.keyOf(it) == SignalRows.keyOf(message) }
            val repeated = store.seenSignal(line, clock - DEDUPE_MS)
            val distinctId = idOf("source-text", message.app, text)
            val distinct = !store.seenSignal(distinctId, clock - SourceStat.RETENTION_MS)
            val row = if (repeated) null else SignalRows.merge(previous, message, text, clock)
            if (row != null && previous == null && !SignalRows.canOpen(mutable.value, row.lane)) throw SignalInboxFullException()
            val lost = if (message.fromPerson && row != null) row.omitted - (previous?.omitted ?: 0) else 0
            val source = "notification-capture"
            val cursor = store.sourceCursor(source)
            val fresh = store.capturePage(source, cursor, cursor + 1, listOfNotNull(row), listOf(line, distinctId),
                stats = listOf(SourceStat.base(message.app, clock).copy(received = 1, distinct = if (distinct) 1 else 0,
                    fromPeople = if (message.fromPerson) 1 else 0, lostPeople = lost)))
            mergePending(fresh)
            CaptureResult(fresh.firstOrNull(), lost)
        }
    }

    /** Feed cursor and row updates are committed together; replay cannot create extra work. */
    suspend fun captureChats(source: String, expected: Long, changes: List<SignalRows.ChatCapture>, startAt: Long,
        startSequence: Long? = null): Long = lock.withLock {
        val rows = mutable.value.associateBy { it.id }.toMutableMap()
        val changed = linkedMapOf<String, TaskSignal>()
        val events = mutableListOf<TaskEvent>()
        val receipts = mutableListOf<String>()
        var through = expected
        for (capture in changes) {
            val message = capture.change.message
            if (startSequence != null && capture.change.sequence <= startSequence) { through = capture.change.sequence; continue }
            val receipt = idOf("chat-change", capture.change.sequence)
            if (store.seenSignal(receipt, now() - DEDUPE_MS)) { through = capture.change.sequence; continue }
            val entry = SignalRows.chatEntry(message, capture.ordinary, capture.ownerChat, capture.taskResult,
                startAt, now(), MAX_AGE_MS, freshRevision = startSequence != null)
            val prior = rows.values.firstOrNull { it.sessionId == message.sessionId }
            val next = SignalRows.chatRow(prior, capture, entry, now())
            if (next != null && prior == null && !SignalRows.canOpen(rows.values, next.lane)) break
            if (next != null) { rows[next.id] = next; changed[next.id] = next }
            if (entry != SignalRows.ChatEntry.NONE) receipts += receipt
            if (entry == SignalRows.ChatEntry.OWNER) {
                events += TaskEvent("feedback:message:${message.id}:${capture.change.sequence}", "owner:${message.sessionId}",
                    0, "feedback", "user", message.createdAt, note = SignalRows.clip(message.text, MAX_TEXT),
                    sourceRefs = listOf(TaskSourceRef("chat", sessionId = message.sessionId, verified = true)))
            }
            through = capture.change.sequence
        }
        if (through > expected) {
            val fresh = store.capturePage(source, expected, through, changed.values.toList(), receipts, events)
            mergePending(fresh)
        }
        through
    }

    suspend fun migrate(startAt: Long, isTaskChat: (String) -> Boolean) = lock.withLock {
        val migration = SignalRows.migrate(mutable.value, now(), startAt, isTaskChat)
        if (migration.remove.isNotEmpty()) {
            store.replaceSignals(migration.remove, migration.rows, migration.receipts)
            mutable.value = store.loadSignals().sortedBy { it.capturedAt }
        }
    }

    private fun mergePending(rows: List<TaskSignal>) {
        val ids = rows.map { it.id }.toSet()
        mutable.value = (mutable.value.filterNot { it.id in ids } + rows).sortedBy { it.capturedAt }
    }

    /** A signal the app made itself: a chat that went quiet, a calendar check, the first backfill. */
    suspend fun add(signal: TaskSignal): TaskSignal? = lock.withLock {
        if (signal.lane == SignalLane.BACKGROUND && !SignalRows.canOpen(mutable.value, signal.lane))
            throw SignalInboxFullException()
        // Reject duplicates before evicting a real pending message. The store
        // retains only an id/time receipt after raw text deletion.
        if (!store.insertSignal(signal)) return@withLock null
        mutable.value = (mutable.value + signal).sortedBy { it.capturedAt }
        signal
    }

    /** For repeatable wakeups only. Full storage must not evict pending input. */
    suspend fun tryAdd(signal: TaskSignal): TaskSignal? = try {
        add(signal)
    } catch (_: SignalInboxFullException) {
        null
    }

    /** The pass used these: their text goes now. */
    suspend fun processed(ids: Collection<String>) = lock.withLock {
        store.deleteSignals(ids)
        mutable.value = mutable.value.filterNot { it.id in ids }
    }

    /** The pass did not finish: keep them for the next one, and count the try. */
    suspend fun failed(ids: Collection<String>) = lock.withLock {
        val changed = mutable.value.filter { it.id in ids }.map { it.copy(attempts = it.attempts + 1) }
        store.saveSignals(changed)
        val map = changed.associateBy { it.id }
        mutable.value = mutable.value.map { map[it.id] ?: it }
    }

    /** Drop what is too old to matter, and everything when discovery is turned off. */
    suspend fun prune(discoveryOn: Boolean): Int = lock.withLock {
        val clock = now()
        val gone = mutable.value.filter { !discoveryOn || clock - it.touchedAt > MAX_AGE_MS || it.attempts >= MAX_ATTEMPTS }.map { it.id }
        if (gone.isNotEmpty()) {
            store.deleteSignals(gone)
            mutable.value = mutable.value.filterNot { it.id in gone }
        }
        gone.size
    }

    suspend fun clear() = lock.withLock {
        store.deleteSignals(mutable.value.map { it.id })
        mutable.value = emptyList()
    }
    suspend fun pruneSources(settings: TaskBoardSettings) = lock.withLock {
        val gone = mutable.value.filterNot { TaskSourcePolicy.allows(TaskSourcePolicy.of(it), settings) }
        store.deleteSignals(gone.map { it.id })
        val allowed = mutable.value - gone.toSet()
        val changed = allowed.mapNotNull { row ->
            val kept = row.items.filterNot { "${row.app}|${it.title}" in settings.excludedConversations }
            if (kept == row.items) null else row.copy(items = kept, revision = row.revision + 1)
        }
        store.saveSignals(changed)
        mutable.value = allowed
        mergePending(changed)
    }
    suspend fun capturePage(source: String, expected: Long, through: Long, signals: List<TaskSignal>) = lock.withLock {
        val fresh = store.capturePage(source, expected, through, signals)
        mergePending(fresh)
    }

    companion object {
        const val MAX_TEXT = 2_000
        const val MAX_PENDING = 400
        const val RESERVED_PEOPLE_ROWS = 100
        const val MAX_ATTEMPTS = 4
        const val MAX_AGE_MS = 72 * 3_600_000L
        const val DEDUPE_MS = 7 * 24 * 3_600_000L
        const val MAX_RECEIPTS = 20_000

        fun idOf(vararg parts: Any?): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001") { it?.toString().orEmpty() }.toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Calendar callbacks only mark work dirty; the board's tick writes it when space is available. */
class CalendarSignalBuffer {
    private val revision = AtomicLong()
    private var capturedRevision = 0L // Only the serialized board tick calls capture.

    fun changed() { revision.incrementAndGet() }

    suspend fun capture(inbox: SignalInbox, now: Long, lastCheckAt: Long?, enabled: Boolean): TaskSignal? {
        if (!enabled) return null
        val observed = revision.get()
        val changed = observed != capturedRevision
        if (!changed && now - (lastCheckAt ?: 0) <= POLL_INTERVAL_MS) return null
        if (inbox.pending.value.any { it.source == "calendar" }) return null
        val signal = inbox.tryAdd(TaskSignal(
            id = SignalInbox.idOf("calendar", now / 60_000), source = "calendar",
            text = "Read the next 48 hours of the calendar before updating related tasks.",
            at = now, capturedAt = now,
            lane = SignalLane.PEOPLE, reason = SignalRows.CALENDAR_CHANGE,
        )) ?: return null
        // A callback during the write has a newer revision and remains pending.
        capturedRevision = observed
        return signal
    }

    companion object { const val POLL_INTERVAL_MS = 6 * 3_600_000L }
}

/**
 * A look at new mail every few hours, through an account connector the user
 * switched on. The row names the time to read from; the pass reads with the
 * connector's read-only tools. Nothing is read here.
 */
object MailCheck {
    const val INTERVAL_MS = 3 * 3_600_000L
    const val FIRST_LOOKBACK_MS = 24 * 3_600_000L
    /** No mail checks before this local hour. */
    const val WAKE_HOUR = 7

    suspend fun capture(inbox: SignalInbox, now: Long, lastCheckAt: Long?, enabled: Boolean, zone: java.time.ZoneId): TaskSignal? {
        if (!enabled) return null
        if (lastCheckAt != null && now - lastCheckAt < INTERVAL_MS) return null
        if (java.time.Instant.ofEpochMilli(now).atZone(zone).hour < WAKE_HOUR) return null
        if (inbox.pending.value.any { it.reason == SignalRows.MAIL_CHECK }) return null
        val since = maxOf(lastCheckAt ?: 0, now - FIRST_LOOKBACK_MS)
        val local = java.time.Instant.ofEpochMilli(since).atZone(zone).toOffsetDateTime().withNano(0)
        return inbox.tryAdd(TaskSignal(
            id = SignalInbox.idOf("mail-check", now / 60_000), source = "email", app = dev.androidagent.core.proactive.ContextRouter.GMAIL_APP,
            text = "Mail check: read the inbox for mail received since $local (epoch seconds ${since / 1000}).",
            at = now, capturedAt = now, sourceVerified = true,
            lane = SignalLane.PEOPLE, reason = SignalRows.MAIL_CHECK,
        ))
    }
}

/**
 * When discovery should think. Collect all the time, think in small batches:
 * a conversation is ready after a quiet spell, so a burst of messages becomes
 * one pass. People and owner feedback have their own place ahead of background.
 */
object DiscoverySchedule {
    /** Display a paused board after midnight without resetting its durable receipts. */
    fun settingsForDay(settings: TaskBoardSettings, day: String): TaskBoardSettings = settings.copy(
        passesToday = if (settings.passDay == day) settings.passesToday else 0,
        backgroundPassesToday = if (settings.passDay == day) settings.backgroundPassesToday else 0,
        managerReviewsToday = if (settings.managerReviewDay == day) settings.managerReviewsToday else 0,
    )

    data class Plan(val due: Boolean, val batch: List<TaskSignal>, val nextCheckAt: Long?, val backgroundLimited: Boolean = false)

    const val MIN_INTERVAL_MS = 20 * 60_000L
    const val MAX_WAIT_MS = 60 * 60_000L
    const val MAX_BATCH = 30
    const val MAX_BATCH_CHARS = 24_000

    fun quietFor(signal: TaskSignal): Long = when {
        signal.lane == SignalLane.IMMEDIATE -> 0L
        signal.source in setOf("chat", "calendar", "backfill") -> 0L
        signal.source in setOf("whatsapp", "message") && !signal.group -> 10 * 60_000L
        else -> 30 * 60_000L
    }

    private fun conversation(signal: TaskSignal) = if (signal.lane != null) SignalRows.keyOf(signal) else signal.sessionId?.let { "chat|$it" }
        ?: "${signal.app}|${signal.conversation ?: signal.sender ?: signal.id}"

    fun plan(
        pending: List<TaskSignal>,
        now: Long,
        lastPassAt: Long?,
        passesToday: Int,
        dailyCap: Int,
        busy: Boolean,
        minIntervalMs: Long = MIN_INTERVAL_MS,
        backgroundPassesToday: Int = 0,
    ): Plan {
        if (pending.isEmpty()) return Plan(false, emptyList(), null)
        val groups = pending.groupBy(::conversation)
        val readyAt = groups.mapValues { (_, signals) ->
            val quiet = signals.maxOf { it.touchedAt } + signals.maxOf(::quietFor)
            val capped = signals.minOf { it.capturedAt } + MAX_WAIT_MS
            maxOf(minOf(quiet, capped), signals.maxOf { it.notBefore })
        }
        val priority = pending.filter { it.lane != SignalLane.BACKGROUND }
        val limited = backgroundPassesToday >= dailyCap / 2
        val eligible = if (limited) priority else pending
        val ready = groups.filterKeys { readyAt.getValue(it) <= now }.values.flatten().filter { it in eligible }
            .sortedWith(compareBy<TaskSignal> { it.lane?.ordinal ?: 1 }.thenBy { it.capturedAt })
        val interval = if (ready.any { it.lane == SignalLane.IMMEDIATE }) minOf(minIntervalMs, 60_000L) else minIntervalMs
        val intervalOpen = lastPassAt?.let { it + interval } ?: Long.MIN_VALUE
        val earliest = maxOf(readyAt.values.min(), intervalOpen)
        if (passesToday >= dailyCap) return Plan(false, emptyList(), null)
        if (limited && eligible.isEmpty()) return Plan(false, emptyList(), null, backgroundLimited = true)
        if (busy || ready.isEmpty() || now < intervalOpen) return Plan(false, emptyList(), maxOf(earliest, now + 60_000))
        val batch = mutableListOf<TaskSignal>()
        var chars = 0
        val lane = ready.first().lane
        for (signal in ready.filter { if (lane == SignalLane.BACKGROUND) it.lane == SignalLane.BACKGROUND else it.lane != SignalLane.BACKGROUND }) {
            val size = if (signal.sessionId != null && signal.text.isEmpty()) SignalRows.MAX_ROW_CHARS else signal.chars
            if (batch.size >= MAX_BATCH || (batch.isNotEmpty() && chars + size > MAX_BATCH_CHARS)) break
            batch += signal; chars += size
        }
        return Plan(true, batch, null)
    }
}
