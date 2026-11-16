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

import android.app.NotificationManager
import android.content.Context
import dev.androidagent.app.panels.SavedPanelNotifications

/**
 * Notification channels from earlier builds. Android keeps a channel's first
 * sound and importance, so a channel made silent got a new id, and the old one
 * must go or it keeps its sound in the phone's settings. Deleted first thing in
 * [AgentApplication.onCreate], on every start of the process (an activity, a
 * widget tap, a receiver, an alarm), before anything else is built.
 */
object RetiredChannels {
    val IDS: List<String> = (SavedPanelNotifications.RETIRED_CHANNELS + QuestionNotifier.RETIRED_CHANNELS).distinct()

    /** Deletes every id in [IDS] that still exists; [exists] and [delete] are the phone's channel calls. */
    fun retire(exists: (String) -> Boolean, delete: (String) -> Unit): List<String> =
        IDS.filter { id -> runCatching { exists(id) }.getOrDefault(true) }
            .onEach { id -> runCatching { delete(id) } }

    fun retire(context: Context): List<String> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return retire(exists = { manager.getNotificationChannel(it) != null }, delete = manager::deleteNotificationChannel)
    }
}
