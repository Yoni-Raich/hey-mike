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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.androidagent.core.RunPhase
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The agent's live indicator in the chat: the voice-mode sphere shrunk to a
// few hundred points, the same one the floating controls show. It churns
// harder the busier the run is and takes the colour of its state: teal while
// working, blue while acting on the screen, amber while stopping, red on error.

private val OrbWorking = Color(0xFF83D9CA)
private val OrbControlling = Color(0xFF69A7FF)
private val OrbStopping = Color(0xFFF6B86A)
private val OrbError = Color(0xFFFFB4AB)
private val OrbIdle = Color(0xFF8F8F8F)

private const val ORB_POINTS = 220
private const val ORB_BANDS = 8

// Tilt towards the viewer, so the poles read as a globe rather than a disc.
private const val ORB_TILT = 0.38f

private class OrbMotion {
    var seconds = 0f
    var level = 0.2f
    var spin = 0f
    val bands = FloatArray(ORB_BANDS)

    fun step(dt: Float, target: Float) {
        seconds += dt
        level += (target - level) * (1f - exp(-dt * if (target > level) 20f else 6f))
        for (band in 0 until ORB_BANDS) {
            val shape = exp(-((band - 3.4f) / 3f).pow(2))
            val flutter = 0.5f + 0.5f * sin(seconds * (4.3f + band * 1.7f) + band * 2.1f) *
                sin(seconds * (2.9f + band * 0.9f) + band)
            val goal = level * shape * (0.4f + 0.6f * flutter)
            bands[band] += (goal - bands[band]) * (1f - exp(-dt * if (goal > bands[band]) 24f else 8f))
        }
        spin += dt * (0.35f + 0.9f * level)
    }

    fun band(position: Float): Float {
        val at = position.coerceIn(0f, 1f) * (ORB_BANDS - 1)
        val low = at.toInt().coerceAtMost(ORB_BANDS - 1)
        val high = min(ORB_BANDS - 1, low + 1)
        return bands[low] + (bands[high] - bands[low]) * (at - low)
    }
}

@Composable
internal fun AgentOrb(
    modifier: Modifier = Modifier,
    phase: RunPhase = RunPhase.THINKING,
    controlling: Boolean = false,
    // At rest the top bar tints it by whether the agent can reach the phone.
    idleColor: Color = OrbIdle,
) {
    val color by animateColorAsState(
        when {
            phase == RunPhase.ERROR -> OrbError
            phase == RunPhase.STOPPING -> OrbStopping
            controlling || phase == RunPhase.CONTROLLING -> OrbControlling
            phase == RunPhase.IDLE -> idleColor
            else -> OrbWorking
        },
        tween(400),
        label = "agent-orb-color",
    )
    // How hard it churns: busiest while acting on the screen.
    val activity by rememberUpdatedState(
        when {
            phase == RunPhase.ERROR || phase == RunPhase.IDLE -> 0.08f
            phase == RunPhase.STOPPING -> 0.2f
            controlling || phase == RunPhase.CONTROLLING -> 0.42f
            phase == RunPhase.TOOL -> 0.34f
            else -> 0.26f
        },
    )
    val motion = remember { OrbMotion() }
    val points = remember {
        FloatArray(ORB_POINTS * 3).also { points ->
            for (index in 0 until ORB_POINTS) {
                val y = 1f - index / (ORB_POINTS - 1f) * 2f
                val radius = sqrt(1f - y * y)
                val angle = index * 2.399963f
                points[index * 3] = cos(angle) * radius
                points[index * 3 + 1] = y
                points[index * 3 + 2] = sin(angle) * radius
            }
        }
    }
    val frame = remember { mutableLongStateOf(0L) }
    val moving = animationsEnabled()
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else (now - previous).coerceIn(0L, 50_000_000L) / 1_000_000_000f
                previous = now
                motion.step(dt, activity)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier) {
        frame.longValue
        val size = min(size.width, size.height)
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = size * 0.33f
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = 0.2f + 0.3f * motion.level),
                1f to Color.Transparent,
                center = center,
                radius = size * 0.5f,
            ),
            radius = size * 0.5f,
            center = center,
        )
        val cosY = cos(motion.spin)
        val sinY = sin(motion.spin)
        val cosX = cos(ORB_TILT)
        val sinX = sin(ORB_TILT)
        val unit = size / 40f
        for (index in 0 until ORB_POINTS) {
            val x = points[index * 3]
            val y = points[index * 3 + 1]
            val z = points[index * 3 + 2]
            val band = motion.band(1f - abs(y))
            val wobble = sin(3.1f * x + motion.seconds * 1.9f) * sin(2.7f * y - motion.seconds * 1.3f) *
                sin(3.3f * z + motion.seconds * 2.2f)
            val push = 1f + 0.06f * wobble * (0.4f + motion.level) + 0.3f * band * (0.55f + 0.45f * wobble)
            val px = x * push
            val py = y * push
            val pz = z * push
            val turnedX = px * cosY + pz * sinY
            val turnedZ = -px * sinY + pz * cosY
            val tiltedY = py * cosX - turnedZ * sinX
            val tiltedZ = py * sinX + turnedZ * cosX
            val perspective = 2.6f / (2.6f - tiltedZ)
            val depth = ((tiltedZ + 1f) / 2f).coerceIn(0f, 1f)
            drawCircle(
                color = lerp(color, Color.White, (motion.level * 0.6f * depth).coerceIn(0f, 1f))
                    .copy(alpha = (0.1f + 0.9f * depth * depth).coerceIn(0f, 1f)),
                radius = (0.35f + 0.75f * depth) * unit * (1f + 0.3f * motion.level),
                center = Offset(center.x + turnedX * radius * perspective, center.y + tiltedY * radius * perspective),
            )
        }
    }
}
