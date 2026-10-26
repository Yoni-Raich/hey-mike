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
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.androidagent.core.AutomationEvent
import dev.androidagent.core.AutomationWakeups
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.*

/**
 * The one trigger that reads content, and the one that is fenced.
 *
 * `AgentAccessibilityService` deliberately reads none of the events it
 * receives — "the text that changed, and none of it is ours to look at". A
 * notification rule cannot keep that promise whole, so it is narrowed rather
 * than abandoned, and the narrowing is enforced here in the order the checks
 * are written:
 *
 * 1. our own notifications are dropped first, so a rule's `notify` can never
 *    trigger the rule that posted it,
 * 2. ongoing notifications (the foreground-service card, a media player) are
 *    dropped: they are status, not events, and they repost constantly,
 * 3. **the package is checked before the title or the body is touched.** An app
 *    no enabled rule names is dropped without ever being read, unless an
 *    enabled rule explicitly chooses all apps. With no notification rules,
 *    `watched` is empty and this service reads nothing at all,
 * 4. only then are the title and text extracted, and they travel no further
 *    than the evaluator unless a rule's own actions interpolate them.
 *
 * Rules store nothing. There is no notification log and no tool that can ask
 * for one: a record of everything that arrived on the phone would quietly
 * become the most sensitive file on it.
 *
 * The task board is the one deliberate exception, and it is narrowed the same
 * way: the board's sink is asked with the package name alone, before anything
 * is read, so an excluded app is never read; verification codes and payment
 * confirmations are dropped before storage; and the text it keeps lives only
 * until a discovery pass has used it. No tool lists what it collected.
 */
class AutomationNotificationListener : NotificationListenerService() {
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onListenerConnected() {
        super.onListenerConnected()
        taskScope.launch {
            repeat(50) {
                val board = (applicationContext as? AutomationHostOwner)?.taskSignals
                if (board?.ready == true) {
                    // Restore collection after process death. Never replay automation actions.
                    runCatching { activeNotifications.orEmpty().forEach { posted ->
                        val notification = posted.notification
                        if (posted.packageName != packageName && notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) == 0 && board.wants(posted.packageName)) {
                            TaskNotificationReader.messages(posted).forEach(board::onMessage)
                        }
                    } }
                    return@launch
                }
                delay(200)
            }
        }
    }

    override fun onDestroy() { taskScope.cancel(); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName == packageName) return
        val notification = posted.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val host = automationHost()
        val watched = host?.watchedNotificationPackages().orEmpty()
        val ruleWatched = host != null && watched.isNotEmpty() && AutomationWakeups.isPackageWatched(posted.packageName, watched)
        val board = (applicationContext as? AutomationHostOwner)?.taskSignals
        val boardWants = board != null && runCatching { board.wants(posted.packageName) }.getOrDefault(false)
        if (!ruleWatched && !boardWants) return

        // Only now is anything read.
        if (ruleWatched) {
            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            host!!.onEvent(
                AutomationEvent.Notification(
                    packageName = posted.packageName,
                    title = title,
                    text = text,
                    at = ZonedDateTime.now(ZoneId.systemDefault()),
                ),
            )
        }
        if (boardWants) runCatching { TaskNotificationReader.messages(posted).forEach(board!!::onMessage) }
    }

    /** Dismissals are not events any rule can name, so they are not delivered. */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        /**
         * Whether the user has granted this listener in Settings.
         *
         * No app can grant it for itself, so this only reads the setting; a
         * rule whose trigger needs it is saved and reported dormant until the
         * user has been sent to that screen.
         */
        fun isEnabled(context: Context): Boolean = runCatching {
            val expected = ComponentName(context, AutomationNotificationListener::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            enabled?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        }.getOrDefault(false)

        /** The screen that grants it. */
        fun settingsIntent(): android.content.Intent =
            android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
