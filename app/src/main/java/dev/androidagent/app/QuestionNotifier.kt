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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dev.androidagent.core.AgentRuns
import dev.androidagent.core.SessionStore
import dev.androidagent.core.RunState
import dev.androidagent.core.UserQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Which questions need a notification: every waiting one whose card is not on screen. */
internal object QuestionNotices {
    fun waiting(states: Map<String, RunState>, owner: (String) -> String, order: (String) -> Int): Map<String, UserQuestion> {
        val waiting = linkedMapOf<String, UserQuestion>()
        states.entries.sortedWith(compareBy({ owner(it.key) != it.key }, { order(it.key) })).forEach { (chat, run) ->
            run.question?.let { waiting.putIfAbsent(owner(chat), it) }
        }
        return waiting
    }
    /** @return chat id to question, for the questions the user cannot see in the app right now. */
    fun due(waiting: Map<String, UserQuestion>, appInFront: Boolean, openChat: String?): Map<String, UserQuestion> =
        waiting.filterKeys { chat -> !(appInFront && chat == openChat) }

    /** The question as the notification shows it: options numbered, so "2" is an answer. */
    fun body(question: UserQuestion): String =
        if (question.options.isEmpty()) question.question
        else question.question + "\n" + question.options.mapIndexed { i, option -> "${i + 1}. $option" }.joinToString("\n")
}

/**
 * Sends a chat's `ask_user` question to the notification shade when its card
 * is not on screen, so the user can answer without opening the app: a button
 * for each of up to two options, and a reply field that takes an option, its
 * number, or the user's own words.
 *
 * With notifications blocked there is nowhere to ask, so the app comes to the
 * front on the asking chat instead, as it does for an approval.
 */
class QuestionNotifier(
    private val context: Context,
    scope: CoroutineScope,
    runs: AgentRuns,
    private val sessions: SessionStore,
    appInFront: StateFlow<Boolean>,
    openChat: StateFlow<String?>,
    /** A delegated question belongs on its source screen, without changing its gate id. */
    private val conversationOwner: (String) -> String = { it },
    private val conversationOrder: (String) -> Int = { 0 },
) {
    /** Question ids with a notification up, or already raised in the app. */
    private val posted = mutableSetOf<String>()

    init {
        scope.launch {
            combine(runs.sessionStates, appInFront, openChat) { states, front, open ->
                val waiting = QuestionNotices.waiting(states, conversationOwner, conversationOrder)
                val visible = if (front && open != null) runs.sourceState(open).question?.id else null
                QuestionNotices.due(waiting, front, open).filterValues { it.id != visible }
            }.distinctUntilChanged().collect { due -> show(due) }
        }
    }

    private fun show(due: Map<String, UserQuestion>) {
        val manager = NotificationManagerCompat.from(context)
        val ids = due.values.map { it.id }.toSet()
        (posted - ids).forEach { gone -> runCatching { manager.cancel(gone, NOTIFICATION_ID) } }
        posted.retainAll(ids)
        due.forEach { (chat, question) ->
            if (!posted.add(question.id)) return@forEach
            val notified = manager.areNotificationsEnabled() && try {
                manager.notify(question.id, NOTIFICATION_ID, notification(chat, question))
                true
            } catch (denied: SecurityException) {
                false
            }
            if (!notified) runCatching { context.startActivity(openChatIntent(chat)) }
        }
    }

    private fun notification(chat: String, question: UserQuestion): android.app.Notification {
        ensureChannel()
        val title = sessions.sessions.value.firstOrNull { it.id == chat }?.title?.takeIf { it.isNotBlank() } ?: "Mike"
        val mike = Person.Builder().setName("Mike").build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(title)
            .addMessage(QuestionNotices.body(question), System.currentTimeMillis(), mike)
        val reply = RemoteInput.Builder(KEY_REPLY)
            .setLabel(if (question.options.isEmpty()) "Your answer" else "An option, its number, or your own words")
            .setChoices(question.options.takeIf { it.isNotEmpty() }?.map<String, CharSequence> { it }?.toTypedArray())
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(question.question)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, question.id.hashCode(), openChatIntent(chat),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        // Android shows three actions at most; the reply field covers every option.
        if (question.options.size <= MAX_OPTION_BUTTONS) {
            question.options.forEachIndexed { index, option ->
                builder.addAction(0, option, answerIntent(question.id, index, option, mutable = false))
            }
        }
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Reply", answerIntent(question.id, REPLY_SLOT, null, mutable = true))
                .addRemoteInput(reply)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build(),
        )
        return builder.build()
    }

    private fun answerIntent(questionId: String, slot: Int, answer: String?, mutable: Boolean): PendingIntent {
        val intent = Intent(context, QuestionAnswerReceiver::class.java)
            .setAction(QuestionAnswerReceiver.ACTION_ANSWER)
            .putExtra(QuestionAnswerReceiver.EXTRA_QUESTION, questionId)
        answer?.let { intent.putExtra(QuestionAnswerReceiver.EXTRA_ANSWER, it) }
        // The system writes the typed reply into the intent, so that one must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (questionId + ":" + slot).hashCode(), intent, flags)
    }

    private fun openChatIntent(chat: String): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_CHAT, chat)

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Questions from Mike", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "What Mike needs you to answer before he can go on."
                },
            )
        }
    }

    companion object {
        const val CHANNEL = "questions"
        const val EXTRA_OPEN_CHAT = "dev.androidagent.app.extra.OPEN_CHAT"
        internal const val KEY_REPLY = "reply"
        /** One per question; the question's id is the tag. */
        internal const val NOTIFICATION_ID = 7_301
        private const val MAX_OPTION_BUTTONS = 2
        private const val REPLY_SLOT = -1
    }
}

/** Carries an answer given in the notification back to the chat that asked. */
class QuestionAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ANSWER) return
        val question = intent.getStringExtra(EXTRA_QUESTION) ?: return
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(QuestionNotifier.KEY_REPLY)?.toString()
        val answer = typed ?: intent.getStringExtra(EXTRA_ANSWER) ?: return
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        val taken = graph.coordinator.answerQuestion(question, answer)
        // Answered or not, the notification is done: a reply field left
        // spinning reads as "still sending".
        runCatching { NotificationManagerCompat.from(context).cancel(question, QuestionNotifier.NOTIFICATION_ID) }
        if (!taken) Toast.makeText(context, "Mike is no longer waiting for this answer.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_ANSWER = "dev.androidagent.app.ANSWER_QUESTION"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
    }
}
