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

import java.time.ZoneId
import java.util.UUID

/** The phone side of alerts. The app implements it with Android notifications. */
interface AlertSink {
    fun show(alert: MikeAlert)
    fun cancel(alertId: String)
    /** One summary notification for these items. */
    fun digest(alerts: List<MikeAlert>)
}

/** A model's or the app's request to tell the user something. */
data class AlertInput(
    val level: AlertLevel,
    val title: String,
    val body: String,
    val whyNow: String? = null,
    val app: String? = null,
    val conversation: String? = null,
    val sender: String? = null,
    val topic: String? = null,
    val group: Boolean = false,
    val signalId: String? = null,
    val signalRevision: Long? = null,
    val taskId: String? = null,
    val actor: String,
    val runId: String? = null,
)

data class AlertOutcome(val alert: MikeAlert, val decision: AlertDecision?, val duplicate: Boolean)

/**
 * Alerts, profile and context in one place, owned by the Manager desk.
 * [raise] is the only way anything reaches the user as an alert.
 */
class ProactiveDesk(
    val profile: OwnerProfileStore,
    val book: AlertBook,
    private val sink: AlertSink,
    val broker: ContextBroker? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun raise(input: AlertInput): AlertOutcome {
        book.existing(input.signalId, input.signalRevision, input.taskId)?.let { return AlertOutcome(it, null, duplicate = true) }
        val at = now()
        val match = profile.match(input.app, input.conversation, input.sender, input.topic, input.group)
        val decision = AlertGate.decide(AlertRequest(input.level, input.app, input.conversation, input.sender, input.topic),
            profile.profile.alerts, match, book.countsToday(at, zone()), book.lastUrgent(input.app, input.conversation), at, zone())
        val deliverAt = if (decision.level == AlertLevel.DIGEST) profile.profile.alerts.nextDigestAt(at, zone()) ?: at else at
        val draft = MikeAlert(
            id = UUID.randomUUID().toString(), level = decision.level, requested = input.level, reasons = decision.reasons,
            title = input.title.trim().take(80), body = input.body.trim().take(300), whyNow = input.whyNow?.trim()?.take(200),
            app = input.app, conversation = input.conversation, sender = input.sender, topic = input.topic,
            signalId = input.signalId, signalRevision = input.signalRevision, taskId = input.taskId,
            actor = input.actor, runId = input.runId, createdAt = at, deliverAt = deliverAt,
            state = if (decision.level == AlertLevel.NONE) AlertState.SUPPRESSED else AlertState.PENDING,
            profileEntryIds = match.entries.map { it.id },
        )
        val record = book.record(draft)
        if (record.duplicate) return AlertOutcome(record.alert, null, duplicate = true)
        record.replaced?.let { sink.cancel(it.id) }
        if (decision.level >= AlertLevel.NOTICE) deliver(record.alert, at)
        if (match.entries.isNotEmpty()) profile.touch(match.entries.map { it.id })
        return AlertOutcome(book.get(draft.id) ?: draft, decision, duplicate = false)
    }

    private fun deliver(alert: MikeAlert, at: Long) {
        sink.show(alert)
        book.markShown(alert.id, at)
    }

    /** Run on the desk's tick: snoozes come back through the gate, due digest items go out together. */
    @Synchronized
    fun tick() {
        val at = now()
        book.dueSnoozed(at).forEach { alert ->
            val match = profile.match(alert.app, alert.conversation, alert.sender, alert.topic)
            val decision = AlertGate.decide(AlertRequest(alert.requested, alert.app, alert.conversation, alert.sender, alert.topic),
                profile.profile.alerts, match, book.countsToday(at, zone()), book.lastUrgent(alert.app, alert.conversation), at, zone())
            when {
                decision.level == AlertLevel.NONE -> book.suppress(alert.id)
                decision.level == AlertLevel.DIGEST -> book.requeue(alert.id, AlertLevel.DIGEST, decision.reasons, profile.profile.alerts.nextDigestAt(at, zone()) ?: at)
                else -> {
                    book.requeue(alert.id, decision.level, decision.reasons + "snoozed", at)
                    book.get(alert.id)?.let { deliver(it, at) }
                }
            }
        }
        val due = book.dueDigest(at)
        if (due.isNotEmpty()) {
            sink.digest(due)
            book.markDigested(due.map { it.id }, at)
        }
    }

    /** The next time [tick] has work, for the desk's alarm. Null when nothing waits. */
    fun nextWakeAt(): Long? = (book.pendingDigest().map { it.deliverAt } + book.alerts.filter { it.state == AlertState.SNOOZED }.map { it.deliverAt }).minOrNull()

    /** A button on an alert. Returns false for an unknown alert. */
    @Synchronized
    fun onFeedback(alertId: String, action: FeedbackAction): Boolean {
        val event = book.feedback(alertId, action) ?: return false
        if (action == FeedbackAction.NOT_IMPORTANT || action == FeedbackAction.LATER) sink.cancel(alertId)
        if (event.action == FeedbackAction.NOT_IMPORTANT) autoLearn()
        return true
    }

    /** Three "not important" on one conversation without an open teaches a learned -1, once. */
    private fun autoLearn() {
        val at = now()
        book.autoLearnCandidates(at).forEach { (app, conversation) ->
            val evidence = book.feedbackIds(app, conversation, FeedbackAction.NOT_IMPORTANT).takeLast(6)
            try {
                profile.learn(ProfileKind.CONVERSATION, ProfileKeys.conversation(app, conversation), conversation, -1,
                    "Marked not important ${evidence.size} times in 14 days", evidence, runId = AUTO_RUN)
            } catch (_: IllegalArgumentException) {
                // The owner already decided this conversation.
            }
            book.markAutoLearned(app, conversation, at)
        }
    }

    /** "Send a test alert" on the Alerts page. It skips the budgets and quiet hours on purpose and is not counted. */
    fun test(title: String, body: String) {
        val at = now()
        sink.show(MikeAlert(UUID.randomUUID().toString(), AlertLevel.NOTICE, AlertLevel.NOTICE, listOf("test"), title, body,
            actor = "app", createdAt = at, deliverAt = at, state = AlertState.SHOWN, shownAt = at))
    }

    companion object { const val AUTO_RUN = "auto-feedback" }
}
