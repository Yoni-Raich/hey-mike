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
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.androidagent.core.CapturedMessage

/**
 * Messages out of one notification, for the task board.
 *
 * A messaging notification (WhatsApp, Telegram, SMS) carries the last few
 * messages of one conversation, each with its sender and time, and reposts
 * the same lines as new ones arrive; reading them one by one lets the inbox
 * store each line once. Anything else (mail, an app's alert) is one message:
 * the title is who or what it is from, the longest text is what it says.
 */
internal object TaskNotificationReader {
    fun messages(posted: StatusBarNotification): List<CapturedMessage> {
        val notification = posted.notification ?: return emptyList()
        val app = posted.packageName
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) }.getOrNull()
        if (style != null && style.messages.isNotEmpty()) {
            val title = style.conversationTitle?.toString()
            val group = style.isGroupConversation
            val me = style.user.name?.toString()
            return style.messages.takeLast(MAX_PER_NOTIFICATION).mapNotNull { message ->
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                val sender = message.person?.name?.toString() ?: me ?: "You"
                CapturedMessage(
                    app = app,
                    conversation = title ?: sender,
                    sender = if (message.person == null) "You" else sender,
                    text = text,
                    at = message.timestamp.takeIf { it > 0 } ?: posted.postTime,
                    group = group,
                    notificationKey = posted.key,
                )
            }
        }
        val extras = notification.extras ?: return emptyList()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeLast(MAX_PER_NOTIFICATION)?.joinToString("\n"),
        ).map { it.toString().trim() }.maxByOrNull { it.length }.orEmpty()
        val body = listOf(title, text).filter { it.isNotEmpty() }.distinct().joinToString(": ")
        if (body.isEmpty()) return emptyList()
        return listOf(CapturedMessage(app, title.ifEmpty { null }, title.ifEmpty { null }, body, posted.postTime, group = false,
            notificationKey = posted.key))
    }

    private const val MAX_PER_NOTIFICATION = 12
}
