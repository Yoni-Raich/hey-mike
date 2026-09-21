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

import android.content.Context
import android.os.PowerManager
import dev.androidagent.core.KeepAwakePolicy
import dev.androidagent.core.RunState
import dev.androidagent.core.VoiceState

/** Small seam that makes the service-owned screen lock lifecycle testable. */
internal interface ScreenWakeLock {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

internal class ConversationKeepAwakeController(
    private val screenWakeLock: ScreenWakeLock,
) {
    fun update(run: RunState, voice: VoiceState) {
        setHeld(KeepAwakePolicy.shouldKeepAwake(run, voice))
    }

    fun release() {
        setHeld(false)
    }

    private fun setHeld(shouldHold: Boolean) {
        if (shouldHold) {
            if (!screenWakeLock.isHeld) screenWakeLock.acquire()
        } else if (screenWakeLock.isHeld) {
            screenWakeLock.release()
        }
    }
}

/** Screen wake lock used only while the foreground service owns an active run. */
internal class AndroidScreenWakeLock(context: Context) : ScreenWakeLock {
    @Suppress("DEPRECATION")
    private val wakeLock = (context.getSystemService(PowerManager::class.java)
        ?: error("Power manager is unavailable"))
        .newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "${context.packageName}:Conversation",
        )
        .apply { setReferenceCounted(false) }

    override val isHeld: Boolean
        get() = wakeLock.isHeld

    override fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}
