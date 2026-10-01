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

package dev.androidagent.app.ui

import dev.androidagent.core.RunPhase
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The orb's motion, kept free of Compose so the "when may it stop drawing"
// rule can be tested. A frame loop that never stops costs a full core on a
// phone that is doing nothing (measured 2026-09-30), so at rest the orb glides
// to a stop and the loop ends; a run starts it again.

internal const val ORB_BANDS = 8

/** How hard the orb churns at rest, and in the error state that follows a failed run. */
internal const val ORB_REST_ACTIVITY = 0.08f

// How fast the pace eases out at rest (about 2.5 s to a stop) and back in.
private const val PACE_OUT_RATE = 1.8f
private const val PACE_IN_RATE = 8f

// Below these the picture no longer changes visibly at orb sizes.
private const val PACE_STILL = 0.01f
private const val LEVEL_STILL = 0.004f

/**
 * Whether the run is at rest: nothing to show but readiness. Acting on the
 * screen is never rest, whatever the phase says.
 */
internal fun runAtRest(phase: RunPhase, controlling: Boolean): Boolean =
    !controlling && (phase == RunPhase.IDLE || phase == RunPhase.ERROR)

internal class OrbMotion {
    var seconds = 0f
        private set
    var level = 0.2f
        private set
    var spin = 0f
        private set
    val bands = FloatArray(ORB_BANDS)

    /**
     * 1 while the orb churns, easing to 0 at rest. It scales the orb's clock,
     * so the globe slows to a halt instead of freezing mid-turn.
     */
    var pace = 1f
        private set

    // The furthest any band still is from where it is heading.
    private var bandGap = 1f

    fun step(dt: Float, target: Float, resting: Boolean = false) {
        pace += ((if (resting) 0f else 1f) - pace) * (1f - exp(-dt * if (resting) PACE_OUT_RATE else PACE_IN_RATE))
        val moved = dt * pace
        seconds += moved
        level += (target - level) * (1f - exp(-dt * if (target > level) 20f else 6f))
        var gap = 0f
        for (band in 0 until ORB_BANDS) {
            val shape = exp(-((band - 3.4f) / 3f).pow(2))
            val flutter = 0.5f + 0.5f * sin(seconds * (4.3f + band * 1.7f) + band * 2.1f) *
                sin(seconds * (2.9f + band * 0.9f) + band)
            val goal = level * shape * (0.4f + 0.6f * flutter)
            bands[band] += (goal - bands[band]) * (1f - exp(-dt * if (goal > bands[band]) 24f else 8f))
            gap = max(gap, abs(goal - bands[band]))
        }
        bandGap = gap
        spin += moved * (0.35f + 0.9f * level)
    }

    /** True once another step at rest would no longer change the picture. */
    fun settled(target: Float): Boolean =
        pace < PACE_STILL && abs(level - target) < LEVEL_STILL && bandGap < LEVEL_STILL

    fun band(position: Float): Float {
        val at = position.coerceIn(0f, 1f) * (ORB_BANDS - 1)
        val low = at.toInt().coerceAtMost(ORB_BANDS - 1)
        val high = min(ORB_BANDS - 1, low + 1)
        return bands[low] + (bands[high] - bands[low]) * (at - low)
    }
}

/**
 * Whether the orb needs another frame. Never with system animations off (it
 * is drawn still), always while a run is live, and at rest only until it has
 * settled; after that nothing invalidates it until the phase changes.
 */
internal fun orbNeedsFrames(animations: Boolean, resting: Boolean, motion: OrbMotion, target: Float): Boolean =
    animations && (!resting || !motion.settled(target))
