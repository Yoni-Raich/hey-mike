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

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import dev.androidagent.core.ActiveNotification
import dev.androidagent.core.MediaSessionAccess
import dev.androidagent.core.MediaSessionInfo
import dev.androidagent.core.NotificationAccess
import dev.androidagent.core.NotificationButton
import dev.androidagent.core.NotificationOutcome

/**
 * [NotificationAccess] and [MediaSessionAccess] over the user-granted
 * [AutomationNotificationListener].
 *
 * Reads are live on every call and nothing is cached. The personal-profile
 * fence and the own-package drop that guard rule delivery guard these reads
 * too. Replies and button presses fire the notification's own PendingIntent,
 * exactly as the shade would; whether that sends a message is decided by the
 * caller's approval gate, not here.
 */
class AndroidNotificationAccess(context: Context) : NotificationAccess, MediaSessionAccess {
    private val context: Context = context.applicationContext ?: context
    private val component = ComponentName(this.context, AutomationNotificationListener::class.java)

    override fun granted(): Boolean = AutomationNotificationListener.isEnabled(context)

    override fun connected(): Boolean = AutomationNotificationListener.live != null

    private fun listener(): AutomationNotificationListener? = AutomationNotificationListener.live

    private fun visible(): List<StatusBarNotification> {
        val live = listener() ?: return emptyList()
        val all = runCatching { live.activeNotifications?.toList() }.getOrNull().orEmpty()
        return all.filter { sbn ->
            sbn.packageName != context.packageName &&
                PersonalNotificationPolicy.allows(context, sbn) &&
                (sbn.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY == 0
        }
    }

    override fun active(packageName: String?, limit: Int): List<ActiveNotification> =
        visible()
            .asSequence()
            .filter { packageName == null || it.packageName == packageName }
            .sortedByDescending { it.postTime }
            .take(limit)
            .map(::describe)
            .toList()

    override fun find(key: String): ActiveNotification? = sbn(key)?.let(::describe)

    private fun sbn(key: String): StatusBarNotification? = visible().firstOrNull { it.key == key }

    private fun describe(sbn: StatusBarNotification): ActiveNotification {
        val n = sbn.notification
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ""
        // Messaging apps put the newest lines in EXTRA_MESSAGES; EXTRA_TEXT
        // often only says "3 new messages".
        val lines = messageLines(extras)
        val text = lines.ifEmpty {
            listOfNotNull(
                extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            )
        }.joinToString("\n")
        val buttons = n.actions.orEmpty().mapIndexed { index, action ->
            NotificationButton(
                index = index,
                title = action.title?.toString().orEmpty(),
                acceptsText = action.remoteInputs?.any { it.allowFreeFormInput } == true,
            )
        }
        return ActiveNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAtMs = sbn.postTime,
            category = n.category,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            buttons = buttons,
        )
    }

    private fun messageLines(extras: Bundle?): List<String> {
        @Suppress("DEPRECATION")
        val raw = extras?.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return raw.takeLast(MAX_MESSAGE_LINES).mapNotNull { item ->
            val bundle = item as? Bundle ?: return@mapNotNull null
            val body = bundle.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val sender = bundle.getCharSequence("sender")?.toString()
            if (sender.isNullOrBlank()) body else "$sender: $body"
        }
    }

    override fun reply(key: String, buttonIndex: Int?, text: String): NotificationOutcome {
        val sbn = sbn(key) ?: return gone()
        val actions = sbn.notification.actions.orEmpty()
        val index = buttonIndex
            ?: actions.indexOfFirst { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val action = actions.getOrNull(index)
            ?: return NotificationOutcome.Failed("no_reply_button", "This notification has no reply field.")
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
        if (inputs.isEmpty()) {
            return NotificationOutcome.Failed("no_reply_button", "Button $index has no text field.")
        }
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), fill, results)
        return fire(action.actionIntent, fill)
    }

    override fun press(key: String, buttonIndex: Int?): NotificationOutcome {
        val sbn = sbn(key) ?: return gone()
        val pending = if (buttonIndex == null) {
            sbn.notification.contentIntent
                ?: return NotificationOutcome.Failed("no_action", "Tapping this notification does nothing.")
        } else {
            val action = sbn.notification.actions.orEmpty().getOrNull(buttonIndex)
                ?: return NotificationOutcome.Failed("invalid_argument", "No button $buttonIndex on this notification.")
            if (action.remoteInputs?.any { it.allowFreeFormInput } == true) {
                return NotificationOutcome.Failed("needs_text", "Button $buttonIndex is a reply field. Use notification_reply.")
            }
            action.actionIntent
                ?: return NotificationOutcome.Failed("no_action", "Button $buttonIndex does nothing.")
        }
        return fire(pending, null)
    }

    override fun dismiss(key: String): NotificationOutcome {
        val live = listener() ?: return unbound()
        val sbn = sbn(key) ?: return gone()
        if (!sbn.isClearable) {
            return NotificationOutcome.Failed("not_clearable", "Android does not let this notification be dismissed.")
        }
        return runCatching { live.cancelNotification(sbn.key) }
            .fold({ NotificationOutcome.Done }, { NotificationOutcome.Failed("dismiss_failed", it.message ?: "Dismiss failed.") })
    }

    private fun fire(pending: PendingIntent?, fill: Intent?): NotificationOutcome {
        if (pending == null) return NotificationOutcome.Failed("no_action", "That button does nothing.")
        return try {
            // From the background Android 14+ only lets the sender's own
            // background-start allowance travel with the intent when asked.
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            pending.send(context, 0, fill, null, null, null, options)
            NotificationOutcome.Done
        } catch (_: PendingIntent.CanceledException) {
            NotificationOutcome.Failed("notification_gone", "The app withdrew that action. Read the notifications again.")
        }
    }

    private fun gone(): NotificationOutcome =
        if (listener() == null) unbound()
        else NotificationOutcome.Failed("not_found", "No such notification on the phone now. List them again.")

    private fun unbound(): NotificationOutcome =
        if (!granted()) NotificationOutcome.Failed("listener_not_granted", "Notification access is off for Hey Mike.")
        else NotificationOutcome.Failed("listener_not_bound", "Android has not connected the notification listener yet. Try again shortly.")

    // ---- media sessions ----

    private fun controllers(): List<MediaController> {
        if (!granted()) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return runCatching { manager.getActiveSessions(component) }.getOrNull().orEmpty()
    }

    override fun sessions(): List<MediaSessionInfo> = controllers().map { controller ->
        val metadata = controller.metadata
        val playback = controller.playbackState
        MediaSessionInfo(
            packageName = controller.packageName,
            state = stateName(playback?.state),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = playback?.position?.takeIf { it >= 0 },
            actions = actionNames(playback?.actions ?: 0L),
        )
    }

    override fun control(packageName: String?, command: String, positionMs: Long?): NotificationOutcome {
        if (!granted()) return NotificationOutcome.Failed("listener_not_granted", "Media control needs notification access for Hey Mike.")
        val all = controllers()
        val controller = if (packageName != null) {
            all.firstOrNull { it.packageName == packageName }
        } else {
            all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        } ?: return NotificationOutcome.Failed("no_session", "No media app is active" + (packageName?.let { " for $it" } ?: "") + ".")
        val controls = controller.transportControls
        when (command) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "stop" -> controls.stop()
            "seek" -> controls.seekTo(positionMs ?: return NotificationOutcome.Failed("invalid_argument", "seek needs positionMs."))
            else -> return NotificationOutcome.Failed("invalid_argument", "Unknown media command \"$command\".")
        }
        return NotificationOutcome.Done
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_NONE, null -> "none"
        else -> "other"
    }

    private fun actionNames(actions: Long): List<String> = buildList {
        if (actions and PlaybackState.ACTION_PLAY != 0L) add("play")
        if (actions and PlaybackState.ACTION_PAUSE != 0L) add("pause")
        if (actions and PlaybackState.ACTION_PLAY_PAUSE != 0L) add("toggle")
        if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add("next")
        if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add("previous")
        if (actions and PlaybackState.ACTION_STOP != 0L) add("stop")
        if (actions and PlaybackState.ACTION_SEEK_TO != 0L) add("seek")
    }

    private companion object {
        const val MAX_MESSAGE_LINES = 8
    }
}
