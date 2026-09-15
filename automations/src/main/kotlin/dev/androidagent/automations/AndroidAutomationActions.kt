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

package dev.androidagent.automations

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.androidagent.core.AgentCoordinator
import dev.androidagent.core.AutomationActionResult
import dev.androidagent.core.AutomationActions
import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.QueuedTurn
import dev.androidagent.core.SessionRunQueue
import dev.androidagent.core.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The six things a rule can do, as this phone serves them.
 *
 * Two rules shape every method here.
 *
 * **A rule takes the device the way a turn does.** `run_workflow` and
 * `open_intent` go through [AgentCoordinator.runAutomation], which claims the
 * same exclusive ownership a person's run claims, shows the same control card
 * and answers the same Stop. It refuses rather than queues when the phone is
 * busy, and the refusal is reported — a rule that fires while you are mid-task
 * should say so, not fight you for the screen.
 *
 * **A rule is not a way around a gate.** The intent goes out through the same
 * composite gateway the model calls, so it meets the same policy and the same
 * approval card. Nothing here reaches a device tool directly.
 */
class AndroidAutomationActions(
    private val context: Context,
    private val coordinator: () -> AgentCoordinator,
    private val tools: () -> DeviceToolGateway,
    private val queue: () -> SessionRunQueue,
    private val sessions: SessionStore,
    /**
     * Opens the app from a rule's notification. Not the rule's own chat yet —
     * deep-linking to one chat needs a selection path the chat screen does not
     * expose, so that waits for the settings screen rather than being faked.
     */
    private val openAppIntent: () -> Intent?,
    /** Opens the app straight into a voice conversation, as the assist gesture does. */
    private val voiceIntent: () -> Intent?,
) : AutomationActions {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun runWorkflow(workflow: String, parameters: JsonObject): AutomationActionResult {
        if (workflow.isBlank()) return AutomationActionResult.failed("no workflow was named")
        val arguments = buildJsonObject {
            put("mode", "run")
            put("workflow", workflow)
            if (parameters.isNotEmpty()) put("parameters", parameters)
        }
        return device("Automation · $workflow") { tools().invoke("workflow_runner", arguments) }
    }

    override suspend fun openIntent(arguments: JsonObject): AutomationActionResult =
        device("Automation · open") { tools().invoke("open_intent", arguments) }

    /**
     * Claim the phone, run one tool call, release.
     *
     * A null claim means another run owns the device. That is reported as a
     * failure rather than retried: by the time the phone is free the rule's
     * moment has usually passed, and the guard would refuse a second attempt
     * anyway.
     */
    private suspend fun device(label: String, call: suspend () -> dev.androidagent.core.ToolResult): AutomationActionResult {
        val workspace = File(context.filesDir, "automations").apply { mkdirs() }
        val result = coordinator().runAutomation(label, workspace) {
            runCatching { call() }
        } ?: return AutomationActionResult.failed(
            "the phone was busy with another run, so this did not start",
        )
        val toolResult = result.getOrElse { failure ->
            return AutomationActionResult.failed(failure.message ?: "the tool call failed", committed = true)
        }
        return if (toolResult.success) {
            AutomationActionResult.ok(toolResult.text.take(MAX_DETAIL))
        } else {
            // A workflow reports its own committed prefix; a failure here is
            // never described as undone.
            AutomationActionResult.failed(toolResult.text.take(MAX_DETAIL), committed = true)
        }
    }

    override suspend fun notify(title: String?, text: String): AutomationActionResult {
        ensureChannel()
        // Tapping it opens the app. The record of what the rule has been doing
        // is in its own chat, which the user still has to pick by hand.
        val open = openAppIntent()?.let { intent ->
            PendingIntent.getActivity(
                context,
                intent.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title ?: "Hey Mike")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .apply { open?.let(::setContentIntent) }
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(ids.incrementAndGet(), notification)
            AutomationActionResult.ok()
        }.getOrElse { AutomationActionResult.failed(it.message ?: "the notification could not be posted") }
    }

    /**
     * Queue a turn in the rule's own chat.
     *
     * Its own chat rather than the one in front of you: a rule that fires at
     * 3am should not appear in the middle of a conversation you were having,
     * and a chat per rule is also the readable record of what that rule has
     * been doing. The deadline comes from the rule; the queue drops the turn
     * rather than running it long after its moment.
     */
    override suspend fun agentTurn(prompt: String, ruleId: String, validUntil: Long): AutomationActionResult {
        if (prompt.isBlank()) return AutomationActionResult.failed("the prompt was empty")
        val sessionId = sessionFor(ruleId) ?: return AutomationActionResult.failed("no chat could be opened for the rule")
        return runCatching {
            queue().submit(
                QueuedTurn(sessionId = sessionId, prompt = prompt, validUntil = validUntil),
            )
            AutomationActionResult.ok("queued in the \"$ruleId\" chat")
        }.getOrElse { AutomationActionResult.failed(it.message ?: "the turn could not be queued") }
    }

    /**
     * The chat a rule writes into, created once and remembered.
     *
     * Re-created if the user deleted it: a rule whose chat is gone should start
     * a new one rather than fail every night from then on.
     */
    private suspend fun sessionFor(ruleId: String): String? {
        prefs.getString(ruleId, null)?.let { existing ->
            if (runCatching { sessions.getSession(existing) }.getOrNull() != null) return existing
        }
        return runCatching {
            val created = sessions.createSession()
            runCatching { sessions.rename(created.id, "Rule · $ruleId") }
            prefs.edit().putString(ruleId, created.id).apply()
            created.id
        }.getOrNull()
    }

    override suspend fun voiceCall(opening: String): AutomationActionResult {
        val intent = voiceIntent() ?: return AutomationActionResult.failed("voice is not wired on this host")
        return runCatching {
            context.startActivity(intent)
            AutomationActionResult.ok()
        }.getOrElse { AutomationActionResult.failed(it.message ?: "the voice screen could not be opened") }
    }

    /**
     * Put a yes/no question up and wait for the answer.
     *
     * A high-priority notification with two buttons, because a rule fires when
     * the app is not in front. No answer inside [ASK_TIMEOUT_MS] is a no: a
     * question nobody saw must not become a yes by default, which is the whole
     * reason the gate exists.
     */
    override suspend fun ask(question: String): Boolean {
        ensureChannel()
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<Boolean>()
        pending[id] = answer
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Hey Mike")
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(0, "Yes", answerIntent(id, true))
            .addAction(0, "No", answerIntent(id, false))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            withTimeoutOrNull(ASK_TIMEOUT_MS) { answer.await() } ?: false
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS was refused, so there is no way to ask. False
            // is the safe answer, and canAsk() keeps it from being read as a no.
            false
        } finally {
            pending.remove(id)
            runCatching { NotificationManagerCompat.from(context).cancel(id) }
        }
    }

    /** False when notifications are blocked: a gate nothing can show is not a gate. */
    override fun canAsk(): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    private fun answerIntent(id: Int, allow: Boolean): PendingIntent {
        val intent = Intent(context, AutomationAnswerReceiver::class.java)
            .setAction(if (allow) AutomationAnswerReceiver.ACTION_YES else AutomationAnswerReceiver.ACTION_NO)
            .putExtra(AutomationAnswerReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            if (allow) id else -id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Automations", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "What your standing rules did, and anything they need you to answer."
                },
            )
        }
    }

    internal companion object {
        const val CHANNEL = "automations"
        const val PREFS = "automation_chats"
        const val ASK_TIMEOUT_MS = 5L * 60 * 1_000
        private const val MAX_DETAIL = 400

        /** Answers in flight, keyed by notification id. Process-local by design. */
        val pending = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
        val ids = AtomicInteger(9_000)
    }
}

/** Carries a tap on Yes or No back to the [AutomationActions.ask] that is waiting. */
class AutomationAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        AndroidAutomationActions.pending.remove(id)?.complete(action == ACTION_YES)
    }

    companion object {
        const val ACTION_YES = "dev.androidagent.automations.ANSWER_YES"
        const val ACTION_NO = "dev.androidagent.automations.ANSWER_NO"
        const val EXTRA_ID = "id"
    }
}
