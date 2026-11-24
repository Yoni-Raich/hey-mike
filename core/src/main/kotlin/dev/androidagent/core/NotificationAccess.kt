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

/** One button on a notification. [acceptsText] marks an inline reply field (RemoteInput). */
data class NotificationButton(
    val index: Int,
    val title: String,
    val acceptsText: Boolean,
)

/**
 * A notification that is on the phone right now, as the listener sees it.
 *
 * Nothing here is stored: every list is read live from the system, so a
 * dismissed notification is gone from the next answer.
 */
data class ActiveNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String?,
    val postedAtMs: Long,
    val category: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
    val buttons: List<NotificationButton>,
) {
    val replyButton: NotificationButton? get() = buttons.firstOrNull { it.acceptsText }
}

/** Outcome of acting on a notification, worded for the model. */
sealed interface NotificationOutcome {
    data object Done : NotificationOutcome
    data class Failed(val errorType: String, val message: String) : NotificationOutcome
}

/**
 * Live notifications through the user-granted listener.
 *
 * Implemented by the automations module, which owns the listener. Everything
 * returns a typed failure, never a raw exception, when the listener is not
 * granted or not bound.
 */
interface NotificationAccess {
    /** The user granted the listener in Settings. */
    fun granted(): Boolean

    /** The system has bound the listener, so live reads work. */
    fun connected(): Boolean

    /** Notifications from the personal profile, newest first, own notifications excluded. */
    fun active(packageName: String?, limit: Int): List<ActiveNotification>

    fun find(key: String): ActiveNotification?

    /** Fill the reply field of button [buttonIndex] (or the first reply button) and send it. */
    fun reply(key: String, buttonIndex: Int?, text: String): NotificationOutcome

    /** Press button [buttonIndex], or the notification body itself when null. */
    fun press(key: String, buttonIndex: Int?): NotificationOutcome

    fun dismiss(key: String): NotificationOutcome
}

/** Now-playing state of one media session. */
data class MediaSessionInfo(
    val packageName: String,
    val state: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    val actions: List<String>,
)

/**
 * Active media sessions (any player app). Android only lists them to a
 * granted notification listener, so this shares [NotificationAccess]'s grant.
 */
interface MediaSessionAccess {
    fun sessions(): List<MediaSessionInfo>

    /** [command]: play, pause, toggle, next, previous, stop, seek (with [positionMs]). */
    fun control(packageName: String?, command: String, positionMs: Long?): NotificationOutcome
}

/** Hosts without a listener (tests, other builds). */
object NoNotificationAccess : NotificationAccess, MediaSessionAccess {
    private val missing = NotificationOutcome.Failed("listener_unavailable", "Notification access is not available in this build.")
    override fun granted() = false
    override fun connected() = false
    override fun active(packageName: String?, limit: Int) = emptyList<ActiveNotification>()
    override fun find(key: String): ActiveNotification? = null
    override fun reply(key: String, buttonIndex: Int?, text: String) = missing
    override fun press(key: String, buttonIndex: Int?) = missing
    override fun dismiss(key: String) = missing
    override fun sessions() = emptyList<MediaSessionInfo>()
    override fun control(packageName: String?, command: String, positionMs: Long?) = missing
}
