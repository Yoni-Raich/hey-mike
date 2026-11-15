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

package dev.androidagent.app.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.androidagent.app.MainActivity
import dev.androidagent.app.R
import dev.androidagent.app.assist.AssistLaunch

/** A Quick Settings tile: one tap from the shade into a voice conversation with Mike. */
class MikeTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = AssistLaunch.voiceIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Launcher long-press shortcuts. Dynamic, because the dev flavor's package differs from prod. */
object LauncherShortcuts {
    fun publish(context: Context) {
        val voice = ShortcutInfoCompat.Builder(context, "voice")
            .setShortLabel("Talk to Mike")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_agent))
            .setIntent(AssistLaunch.voiceIntent(context).setAction(Intent.ACTION_VIEW))
            .build()
        val chat = ShortcutInfoCompat.Builder(context, "new_chat")
            .setShortLabel("New chat")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_agent))
            .setIntent(
                Intent(context, MainActivity::class.java).setAction(EntryActions.ACTION_NEW_CHAT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, listOf(chat, voice)) }
    }
}
