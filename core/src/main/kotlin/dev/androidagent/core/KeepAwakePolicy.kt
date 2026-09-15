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

/**
 * Decides whether the device screen must stay awake for an ongoing conversation.
 *
 * The foreground activity applies `FLAG_KEEP_SCREEN_ON` while this returns
 * true. The foreground service uses a screen wake lock for the same state when
 * the activity is hidden. Neither path writes system settings, so the user's
 * display timeout is preserved.
 *
 * The inputs reuse the existing `active` definitions: [RunState.active] stays
 * true through STOPPING and drops on IDLE/ERROR, and [VoiceState.active] stays
 * true through STOPPING and drops on IDLE/ERROR. That keeps the screen awake
 * for typed runs, realtime voice, and typed text sent while voice is active.
 */
object KeepAwakePolicy {
    fun shouldKeepAwake(run: RunState, voice: VoiceState): Boolean =
        run.active || voice.active
}
