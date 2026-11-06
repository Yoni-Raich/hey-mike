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

package dev.androidagent.core.proactive

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class AlertState { PENDING, SHOWN, OPENED, NOT_IMPORTANT, SNOOZED, DISMISSED, DIGESTED, REPLACED, SUPPRESSED }

@Serializable
enum class FeedbackAction {
    OPEN, NOT_IMPORTANT, LATER, DISMISS;
    val wire: String get() = name.lowercase()
}

/** One thing Mike decided to tell the user, and what became of it. No message text beyond the alert's own title and line. */
@Serializable
data class MikeAlert(
    val id: String,
    val level: AlertLevel,
    val requested: AlertLevel,
    val reasons: List<String> = emptyList(),
    val title: String,
    val body: String,
    val whyNow: String? = null,
    val app: String? = null,
    val conversation: String? = null,
    val sender: String? = null,
    val topic: String? = null,
    val signalId: String? = null,
    val signalRevision: Long? = null,
    val taskId: String? = null,
    val actor: String,
    val runId: String? = null,
    val createdAt: Long,
    /** When it should reach the phone: now, the next digest, or the end of a snooze. */
    val deliverAt: Long,
    val state: AlertState = AlertState.PENDING,
    val shownAt: Long? = null,
    val profileEntryIds: List<String> = emptyList(),
)

@Serializable
data class AttentionFeedback(
    val id: String,
    val alertId: String,
    val action: FeedbackAction,
    val at: Long,
    val level: AlertLevel,
    val app: String? = null,
    val conversation: String? = null,
    val sender: String? = null,
    val topic: String? = null,
)

@Serializable
private data class AlertBookFile(
    val version: Int = 1,
    val alerts: List<MikeAlert> = emptyList(),
    val feedback: List<AttentionFeedback> = emptyList(),
    /** Conversations already turned into a learned entry by the deterministic rule, so it does not fire twice. */
    val autoLearned: Map<String, Long> = emptyMap(),
)

/** Per-source counts for the Coach: what Mike raised and what the user did with it. */
data class AttentionStats(val key: String, val label: String, val alerts: Int, val opened: Int, val notImportant: Int, val ignored: Int)

/** Result of [AlertBook.record]: the stored alert, and an older live alert it replaced, for the notifier to cancel. */
data class AlertRecord(val alert: MikeAlert, val replaced: MikeAlert?, val duplicate: Boolean)

/**
 * The alert history: dedupe, today's counts, the digest queue, snoozes and
 * the user's feedback. Small JSON file with one writer; 14 days kept.
 */
class AlertBook(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var state: AlertBookFile = load()

    private fun load(): AlertBookFile = try {
        if (file.exists()) json.decodeFromString(AlertBookFile.serializer(), file.readText()).takeIf { it.version == 1 } ?: AlertBookFile()
        else AlertBookFile()
    } catch (_: Exception) { AlertBookFile() }

    @Synchronized
    private fun save(next: AlertBookFile) {
        val cutoff = now() - KEEP_MS
        val pruned = next.copy(
            alerts = next.alerts.filter { it.createdAt >= cutoff || it.state == AlertState.PENDING || it.state == AlertState.SNOOZED }.takeLast(MAX_ALERTS),
            feedback = next.feedback.filter { it.at >= cutoff - KEEP_MS }.takeLast(MAX_FEEDBACK),
            autoLearned = next.autoLearned.filterValues { it >= cutoff - KEEP_MS },
        )
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(AlertBookFile.serializer(), pruned))
        if (!temp.renameTo(file)) { file.delete(); check(temp.renameTo(file)) { "Could not save alerts." } }
        state = pruned
    }

    val alerts: List<MikeAlert> get() = state.alerts
    val feedback: List<AttentionFeedback> get() = state.feedback

    fun get(id: String): MikeAlert? = state.alerts.firstOrNull { it.id == id }

    /** Alerts that reached the phone today, by level. Digest items do not count against the budgets. */
    fun countsToday(at: Long, zone: ZoneId): AlertCounts {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val shown = state.alerts.filter { a -> a.shownAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == day } == true }
        return AlertCounts(urgent = shown.count { it.level == AlertLevel.URGENT }, notice = shown.count { it.level == AlertLevel.NOTICE })
    }

    fun lastUrgent(app: String?, conversation: String?): Long? {
        if (conversation.isNullOrBlank()) return null
        return state.alerts.filter { it.level == AlertLevel.URGENT && it.app == app && it.conversation == conversation }
            .mapNotNull { it.shownAt }.maxOrNull()
    }

    /** The alert already raised for this signal revision or card, if any. A retried pass must not alert twice. */
    fun existing(signalId: String?, signalRevision: Long?, taskId: String?): MikeAlert? = state.alerts.lastOrNull { a ->
        (signalId != null && a.signalId == signalId && a.signalRevision == signalRevision) ||
            (signalId == null && taskId != null && a.taskId == taskId && a.state in LIVE)
    }

    @Synchronized
    fun record(draft: MikeAlert): AlertRecord {
        existing(draft.signalId, draft.signalRevision, draft.taskId)?.let { return AlertRecord(it, null, duplicate = true) }
        val live = draft.conversation?.takeIf { draft.level >= AlertLevel.NOTICE }?.let { c ->
            state.alerts.lastOrNull { it.app == draft.app && it.conversation == c && it.state == AlertState.SHOWN && it.level >= AlertLevel.NOTICE }
        }
        val replacedAlerts = state.alerts.map { if (it === live) it.copy(state = AlertState.REPLACED) else it }
        save(state.copy(alerts = replacedAlerts + draft))
        return AlertRecord(draft, live?.copy(state = AlertState.REPLACED), duplicate = false)
    }

    @Synchronized
    fun markShown(id: String, at: Long = now()) = update(id) { it.copy(state = AlertState.SHOWN, shownAt = at) }

    @Synchronized
    fun markDigested(ids: Collection<String>, at: Long = now()) {
        if (ids.isEmpty()) return
        save(state.copy(alerts = state.alerts.map { if (it.id in ids) it.copy(state = AlertState.DIGESTED, shownAt = at) else it }))
    }

    /** Digest items whose time has come. */
    fun dueDigest(at: Long): List<MikeAlert> = state.alerts.filter { it.level == AlertLevel.DIGEST && it.state == AlertState.PENDING && it.deliverAt <= at }

    /** Snoozed alerts whose hour is up. They go back through the gate. */
    fun dueSnoozed(at: Long): List<MikeAlert> = state.alerts.filter { it.state == AlertState.SNOOZED && it.deliverAt <= at }

    fun pendingDigest(): List<MikeAlert> = state.alerts.filter { it.level == AlertLevel.DIGEST && it.state == AlertState.PENDING }

    @Synchronized
    fun requeue(id: String, level: AlertLevel, reasons: List<String>, deliverAt: Long) =
        update(id) { it.copy(level = level, reasons = (it.reasons + reasons).distinct().takeLast(8), deliverAt = deliverAt, state = AlertState.PENDING, shownAt = null) }

    @Synchronized
    fun suppress(id: String) = update(id) { it.copy(state = AlertState.SUPPRESSED) }

    /** The user's answer on an alert. Returns null for an unknown alert. */
    @Synchronized
    fun feedback(alertId: String, action: FeedbackAction, at: Long = now(), snoozeMs: Long = SNOOZE_MS): AttentionFeedback? {
        val alert = get(alertId) ?: return null
        val next = when (action) {
            FeedbackAction.OPEN -> alert.copy(state = AlertState.OPENED)
            FeedbackAction.NOT_IMPORTANT -> alert.copy(state = AlertState.NOT_IMPORTANT)
            FeedbackAction.LATER -> alert.copy(state = AlertState.SNOOZED, deliverAt = at + snoozeMs)
            FeedbackAction.DISMISS -> if (alert.state == AlertState.SHOWN) alert.copy(state = AlertState.DISMISSED) else alert
        }
        val event = AttentionFeedback(UUID.randomUUID().toString(), alertId, action, at, alert.level, alert.app, alert.conversation, alert.sender, alert.topic)
        save(state.copy(alerts = state.alerts.map { if (it.id == alertId) next else it }, feedback = state.feedback + event))
        return event
    }

    /**
     * The deterministic rule that needs no model: three "not important" on the
     * same conversation within 14 days and none opened gives a learned -1.
     * Returns "app|conversation" keys not already handled.
     */
    fun autoLearnCandidates(at: Long): List<Pair<String, String>> {
        val since = at - KEEP_MS
        val recent = state.feedback.filter { it.at >= since && it.app != null && !it.conversation.isNullOrBlank() }
        return recent.groupBy { it.app!! to it.conversation!! }.filter { (key, events) ->
            events.count { it.action == FeedbackAction.NOT_IMPORTANT } >= 3 &&
                events.none { it.action == FeedbackAction.OPEN } &&
                "${key.first}|${key.second}" !in state.autoLearned
        }.keys.toList()
    }

    @Synchronized
    fun markAutoLearned(app: String, conversation: String, at: Long = now()) =
        save(state.copy(autoLearned = state.autoLearned + ("$app|$conversation" to at)))

    fun feedbackIds(app: String, conversation: String, action: FeedbackAction): List<String> =
        state.feedback.filter { it.app == app && it.conversation == conversation && it.action == action }.map { it.id }

    /** What the Coach sees: per conversation, what was raised and how the user answered. */
    fun stats(since: Long, limit: Int = 20): List<AttentionStats> =
        state.alerts.filter { it.createdAt >= since && it.state != AlertState.SUPPRESSED }
            .groupBy { "${it.app.orEmpty()}|${it.conversation ?: it.sender ?: it.topic.orEmpty()}" }
            .map { (key, list) ->
                AttentionStats(key, list.last().conversation ?: list.last().sender ?: list.last().title, list.size,
                    opened = list.count { it.state == AlertState.OPENED },
                    notImportant = list.count { it.state == AlertState.NOT_IMPORTANT },
                    ignored = list.count { it.state == AlertState.DISMISSED || it.state == AlertState.DIGESTED })
            }.sortedByDescending { it.alerts }.take(limit)

    fun recentFeedback(since: Long, limit: Int = 40): List<AttentionFeedback> = state.feedback.filter { it.at >= since }.takeLast(limit)

    /** "Delete collected input": the history goes, pending deliveries with it. */
    @Synchronized
    fun clear() = save(AlertBookFile())

    private fun update(id: String, change: (MikeAlert) -> MikeAlert) {
        if (state.alerts.none { it.id == id }) return
        save(state.copy(alerts = state.alerts.map { if (it.id == id) change(it) else it }))
    }

    companion object {
        const val KEEP_MS = 14L * 24 * 3_600_000
        const val SNOOZE_MS = 3_600_000L
        const val MAX_ALERTS = 500
        const val MAX_FEEDBACK = 800
        private val LIVE = setOf(AlertState.PENDING, AlertState.SHOWN, AlertState.SNOOZED)
    }
}
