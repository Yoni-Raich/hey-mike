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

package dev.androidagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.androidagent.app.ui.summary
import dev.androidagent.core.AgentRuns
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RunPhase
import dev.androidagent.core.RunState
import dev.androidagent.core.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Which chats get which notification. Pure, so it can be tested without a phone. */
internal object SessionNotices {
    enum class Ending { DONE, FAILED }

    /**
     * The conversations at work and what each is doing, by the chat the user
     * knows: a delegated chat counts as its source. [hidden] chats work
     * backstage and have no screen to open.
     */
    fun running(states: Map<String, RunState>, owner: (String) -> String, hidden: (String) -> Boolean): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        states.filter { (chat, state) -> state.active && !hidden(chat) }
            // The chat's own run names the work better than a child's does.
            .entries.sortedBy { (chat, _) -> if (owner(chat) == chat) 0 else 1 }
            .forEach { (chat, state) -> result.getOrPut(owner(chat)) { line(state) } }
        return result
    }

    private fun line(state: RunState): String = when {
        state.approval != null -> "Waiting for your approval"
        state.question != null -> "Waiting for your answer"
        else -> state.status.ifBlank { "Working" }
    }

    /** Every active approval, keyed by request so concurrent requests are retained. */
    fun approvalsDue(
        states: Map<String, RunState>,
        owner: (String) -> String,
    ): Map<String, Pair<String, EngineEvent.Approval>> {
        val result = LinkedHashMap<String, Pair<String, EngineEvent.Approval>>()
        states.forEach { (chat, state) ->
            val approval = state.approval?.takeIf { state.active } ?: return@forEach
            result[approval.requestId] = owner(chat) to approval
        }
        return result
    }

    /**
     * How a run ended, when that is news. A run the user stopped, one that was
     * interrupted and a voice conversation that ended are not: the user was there.
     */
    fun ending(outcome: RunState): Ending? = when {
        outcome.phase == RunPhase.ERROR -> Ending.FAILED
        outcome.phase == RunPhase.IDLE && outcome.status == "Ready" -> Ending.DONE
        else -> null
    }

    /** One line of a long reply, for a notification. */
    fun preview(text: String, limit: Int = 400): String =
        text.trim().replace(Regex("\\n{2,}"), "\n").let { if (it.length <= limit) it else it.take(limit - 1).trimEnd() + "…" }
}

/**
 * The chats' side of the notification shade: one row under "Mike is working"
 * for each conversation at work, a notification when a chat needs an approval
 * and its card is not on screen, and one when a chat finishes while the user
 * is somewhere else. Each opens its chat when tapped.
 */
class SessionNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runs: AgentRuns,
    private val sessions: SessionStore,
    private val appInFront: StateFlow<Boolean>,
    private val openChat: StateFlow<String?>,
    /** The chat a delegated chat's work is shown in. */
    private val owner: (String) -> String = { it },
    /** Backstage chats: they finish and work without telling anyone. */
    private val hidden: (String) -> Boolean = { false },
) {
    private val manager = NotificationManagerCompat.from(context)
    private val approvalsUp = mutableMapOf<String, String>()
    private val finishedUp = mutableSetOf<String>()
    private val runningUp = mutableMapOf<String, String>()
    private val endingsSeen = mutableSetOf<String>()

    /** The conversations at work, for the "Mike is working" notification that groups them. */
    val running: Flow<Map<String, String>> =
        runs.sessionStates.map { SessionNotices.running(it, owner, hidden) }.distinctUntilChanged().conflate()

    init {
        scope.launch {
            runs.sessionStates.map { states ->
                SessionNotices.approvalsDue(states, owner)
            }.distinctUntilChanged().collectLatest { due ->
                // Asking raises the app where it can. Give its card a moment to be
                // on screen before deciding nobody can see it.
                if (due.keys.any { it !in approvalsUp }) delay(APPROVAL_SETTLE_MS)
                showApprovals(due)
            }
        }
        scope.launch {
            runs.outcomes.collect { outcomes ->
                outcomes.forEach { (chat, outcome) ->
                    if (!endingsSeen.add(chat + ":" + (outcome.runId ?: outcome.status))) return@forEach
                    val ending = SessionNotices.ending(outcome) ?: return@forEach
                    scope.launch { announce(chat, outcome, ending) }
                }
            }
        }
        // A finished chat stops being news once it is opened or runs again.
        scope.launch {
            combine(runs.sessionStates, appInFront, openChat) { states, front, open ->
                states.filterValues { it.active }.keys + listOfNotNull(open.takeIf { front })
            }.distinctUntilChanged().collect { seen ->
                synchronized(finishedUp) { finishedUp.filter { it in seen }.also { finishedUp.removeAll(it.toSet()) } }
                    .forEach { chat -> runCatching { manager.cancel(chat, FINISHED_ID) } }
            }
        }
    }

    // region approvals

    private fun showApprovals(due: Map<String, Pair<String, EngineEvent.Approval>>) {
        approvalsUp.filter { (request, _) -> request !in due }.forEach { (request, _) ->
            runCatching { manager.cancel(request, APPROVAL_ID) }
            approvalsUp.remove(request)
        }
        due.forEach { (request, item) ->
            val (chat, approval) = item
            if (request in approvalsUp) return@forEach
            approvalsUp[request] = chat
            post(approval.requestId, APPROVAL_ID) { approvalNotification(chat, approval) }
        }
    }

    private fun approvalNotification(chat: String, approval: EngineEvent.Approval): Notification {
        ensureChannel(APPROVALS, "Approvals for Mike", "What Mike needs your OK for before he goes on.")
        val summary = approval.summary()
        val lines = summary.lines.joinToString("\n") { (label, value) -> "$label: ${SessionNotices.preview(value, 240)}" }
        return NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(summary.headline)
            .setContentText(summary.lines.firstOrNull()?.let { (label, value) -> "$label: $value" } ?: "Mike is waiting for your approval")
            .setSubText(title(chat))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.ifBlank { "Mike is waiting for your approval" }))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(open(chat, approval.requestId.hashCode()))
            .addAction(0, "Deny", answer(approval.requestId, allow = false))
            .addAction(0, "Allow", answer(approval.requestId, allow = true))
            .build()
    }

    private fun answer(request: String, allow: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, (request + ":" + allow).hashCode(),
        Intent(context, SessionActionReceiver::class.java).setAction(SessionActionReceiver.ACTION_APPROVAL)
            .putExtra(SessionActionReceiver.EXTRA_REQUEST, request).putExtra(SessionActionReceiver.EXTRA_ALLOW, allow),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // endregion
    // region finished

    private suspend fun announce(chat: String, outcome: RunState, ending: SessionNotices.Ending) {
        val session = sessions.getSession(chat) ?: return
        // Allow the final assistant message to be persisted before reading it.
        delay(FINISH_SETTLE_MS)
        val last = runCatching {
            sessions.messages(chat).first().lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text
        }.getOrNull()
        val failed = ending == SessionNotices.Ending.FAILED
        val body = when {
            failed -> outcome.status.ifBlank { "The run stopped with an error." }
            last != null -> SessionNotices.preview(last)
            else -> "Mike finished."
        }
        ensureChannel(FINISHED, "Finished chats", "Mike finished working in a chat you are not looking at.")
        val notification = NotificationCompat.Builder(context, FINISHED)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(session.title.ifBlank { "Mike" })
            .setContentText(body.lineSequence().first())
            .setSubText(if (failed) "Stopped with an error" else "Mike finished")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open(chat, ("done:$chat").hashCode()))
            .build()
        synchronized(finishedUp) { finishedUp += chat }
        post(chat, FINISHED_ID) { notification }
    }

    // endregion
    // region running

    /**
     * One silent row per conversation at work. They sit in [GROUP], under the
     * service's "Mike is working" notification, which is the group's summary.
     */
    fun showRunning(running: Map<String, String>) {
        runningUp.keys.filter { it !in running }.forEach { chat ->
            runCatching { manager.cancel(chat, RUNNING_ID) }
            runningUp.remove(chat)
        }
        running.forEach { (chat, status) ->
            val name = title(chat)
            val shown = "$name\n$status"
            if (runningUp[chat] == shown) return@forEach
            runningUp[chat] = shown
            post(chat, RUNNING_ID) {
                NotificationCompat.Builder(context, AgentService.CHANNEL)
                    .setSmallIcon(R.drawable.ic_agent_working)
                    .setContentTitle(name)
                    .setContentText(status)
                    .setGroup(GROUP)
                    .setSortKey(chat)
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setContentIntent(open(chat, ("run:$chat").hashCode()))
                    .addAction(
                        0, "Stop",
                        PendingIntent.getBroadcast(
                            context, ("stop:$chat").hashCode(),
                            Intent(context, SessionActionReceiver::class.java).setAction(SessionActionReceiver.ACTION_STOP)
                                .putExtra(SessionActionReceiver.EXTRA_CHAT, chat),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                    .build()
            }
        }
    }

    /** "Chat name: what it is doing", for the summary's own lines. */
    fun runningLines(running: Map<String, String>): List<String> = running.map { (chat, status) -> "${title(chat)}: $status" }

    // endregion

    private fun title(chat: String): String =
        sessions.sessions.value.firstOrNull { it.id == chat }?.title?.takeIf { it.isNotBlank() } ?: "Mike"

    private fun open(chat: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(QuestionNotifier.EXTRA_OPEN_CHAT, chat),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private inline fun post(tag: String, id: Int, build: () -> Notification) {
        if (!manager.areNotificationsEnabled()) return
        try { manager.notify(tag, id, build()) } catch (denied: SecurityException) { /* Notifications were just turned off. */ }
    }

    private fun ensureChannel(id: String, name: String, about: String) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            system.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply { description = about },
            )
        }
    }

    companion object {
        const val APPROVALS = "approvals"
        const val FINISHED = "finished"
        /** The group "Mike is working" summarises. */
        const val GROUP = "dev.androidagent.app.RUNNING"
        internal const val APPROVAL_ID = 7_302
        internal const val FINISHED_ID = 7_303
        internal const val RUNNING_ID = 7_304
        private const val APPROVAL_SETTLE_MS = 700L
        private const val FINISH_SETTLE_MS = 1_500L
    }
}

/** A button on one of [SessionNotifier]'s notifications: answer an approval, or stop a chat. */
class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        when (intent?.action) {
            ACTION_APPROVAL -> {
                val request = intent.getStringExtra(EXTRA_REQUEST) ?: return
                val waiting = graph.coordinator.sessionStates.value.values.any { it.active && it.approval?.requestId == request }
                if (waiting) graph.coordinator.approve(request, intent.getBooleanExtra(EXTRA_ALLOW, false))
                else Toast.makeText(context, "Mike is no longer waiting for this approval.", Toast.LENGTH_SHORT).show()
                runCatching { NotificationManagerCompat.from(context).cancel(request, SessionNotifier.APPROVAL_ID) }
            }
            ACTION_STOP -> intent.getStringExtra(EXTRA_CHAT)?.let(graph::stopChat)
        }
    }

    companion object {
        const val ACTION_APPROVAL = "dev.androidagent.app.ANSWER_APPROVAL"
        const val ACTION_STOP = "dev.androidagent.app.STOP_CHAT"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_CHAT = "chat"
    }
}
