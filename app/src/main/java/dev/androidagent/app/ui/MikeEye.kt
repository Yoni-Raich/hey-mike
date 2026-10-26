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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What the Manager's eye shows. Teal is alive and well; amber is "something waits for you". */
internal enum class EyeMood(val label: String) {
    IDLE("resting"),
    THINKING("reading new messages"),
    WORKING("working on tasks"),
    NEEDS("waiting for you"),
    DONE("finished"),
    PAUSED("collection is off"),
}

internal val EyeTeal = Color(0xFF83D9CA)
internal val EyeAmber = Color(0xFFF6B86A)
private val EyeRest = Color(0xFF7FA9A1)
private val EyePaused = Color(0xFF5E5C57)

/** A member's colour, from its hue: same lightness and calm, so the team reads as one family. */
internal fun memberColor(hue: Float): Color = Color.hsv(((hue % 360f) + 360f) % 360f, 0.34f, 0.86f)

private const val SPHERE_POINTS = 170
private const val SMALL_POINTS = 64
private const val DISK_PARTICLES = 64
private const val TILT = 0.36f

/**
 * Mike's face: the dot sphere of [AgentOrb] with a black hole for an eye.
 *
 * An event horizon with a thin photon ring sits in the middle; an accretion
 * disk of the sphere's own dots turns around it. Each mood changes one thing:
 * thinking widens and speeds the disk, working sends a satellite around the
 * sphere per running task, waiting turns it amber with a second breathing
 * ring, done settles the disk into one ring and a single wave, paused closes
 * the eye and stops all motion. Reduced motion draws one still frame.
 *
 * @param tint a team member's own colour; the Manager uses the mood's.
 * @param satellites running tasks, drawn while working (at most four).
 */
@Composable
internal fun MikeEye(
    mood: EyeMood,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    satellites: Int = 0,
    animate: Boolean = true,
    description: String? = null,
) {
    val base = when (mood) {
        EyeMood.NEEDS -> EyeAmber
        EyeMood.PAUSED -> EyePaused
        EyeMood.IDLE -> tint ?: EyeRest
        else -> tint ?: EyeTeal
    }
    val color by animateColorAsState(base, tween(500), label = "mike-eye-color")
    val time = remember { mutableFloatStateOf(0f) }
    val moving = animate && animationsEnabled() && mood != EyeMood.PAUSED
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else (now - previous).coerceIn(0L, 50_000_000L) / 1_000_000_000f
                previous = now
                time.floatValue += dt
            }
        }
    }
    val sphere = remember { fibonacci(SPHERE_POINTS) }
    val small = remember { fibonacci(SMALL_POINTS) }
    Canvas(modifier.semantics { contentDescription = description ?: "Mike, ${mood.label}" }) {
        val t = time.floatValue
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val tiny = radius < 26f * density
        drawGlow(center, radius, color, mood)
        drawSphere(if (tiny) small else sphere, center, radius, color, t, mood, tiny)
        drawEye(center, radius, color, t, mood, tiny)
        if (mood == EyeMood.WORKING && !tiny) drawSatellites(center, radius, color, t, satellites.coerceIn(1, 4))
        if (mood == EyeMood.DONE && !tiny) drawWave(center, radius, color, t)
    }
}

private fun fibonacci(count: Int) = FloatArray(count * 3).also { points ->
    for (index in 0 until count) {
        val y = 1f - index / (count - 1f) * 2f
        val r = sqrt(1f - y * y)
        val angle = index * 2.399963f
        points[index * 3] = cos(angle) * r
        points[index * 3 + 1] = y
        points[index * 3 + 2] = sin(angle) * r
    }
}

private fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color, mood: EyeMood) {
    val strength = when (mood) { EyeMood.PAUSED -> 0.05f; EyeMood.NEEDS -> 0.32f; EyeMood.IDLE -> 0.14f; else -> 0.22f }
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = strength * 0.35f), Color.Transparent),
            center, radius * 1.05f),
        radius * 1.05f, center,
    )
}

private fun DrawScope.drawSphere(points: FloatArray, center: Offset, radius: Float, color: Color, t: Float, mood: EyeMood, tiny: Boolean) {
    val spin = t * when (mood) { EyeMood.THINKING -> 0.32f; EyeMood.WORKING -> 0.24f; EyeMood.PAUSED -> 0f; else -> 0.1f }
    val cs = cos(spin); val sn = sin(spin)
    val ct = cos(TILT); val st = sin(TILT)
    val clear = radius * 0.36f
    val dot = radius * if (tiny) 0.05f else 0.022f
    val dim = if (mood == EyeMood.PAUSED) 0.45f else 1f
    for (i in points.indices step 3) {
        val x0 = points[i]; val y0 = points[i + 1]; val z0 = points[i + 2]
        val x = x0 * cs - z0 * sn
        val z1 = x0 * sn + z0 * cs
        val y = y0 * ct - z1 * st
        val z = y0 * st + z1 * ct
        val px = center.x + x * radius * 0.86f
        val py = center.y + y * radius * 0.86f
        val depth = (z + 1f) / 2f
        // The front face leaves room for the eye.
        val dx = px - center.x; val dy = py - center.y
        if (z > 0f && dx * dx + dy * dy < clear * clear) continue
        val alpha = (0.12f + 0.78f * depth) * dim
        drawCircle(color.copy(alpha = alpha), dot * (0.55f + 0.75f * depth), Offset(px, py))
    }
}

private fun DrawScope.drawEye(center: Offset, radius: Float, color: Color, t: Float, mood: EyeMood, tiny: Boolean) {
    val horizon = radius * when (mood) { EyeMood.NEEDS -> 0.17f; EyeMood.THINKING -> 0.15f; else -> 0.14f }
    if (mood == EyeMood.PAUSED) {
        // The eye is closed: a lid line, no light.
        drawLine(color.copy(alpha = 0.8f), Offset(center.x - horizon * 1.25f, center.y), Offset(center.x + horizon * 1.25f, center.y),
            strokeWidth = radius * 0.03f)
        return
    }
    // A rare blink while resting: a quick squash of the whole eye.
    val blinkPhase = (t % 7.3f)
    val blink = if (mood == EyeMood.IDLE && blinkPhase < 0.18f) 1f - sin(blinkPhase / 0.18f * PI.toFloat()) * 0.9f else 1f
    scale(1f, blink, center) {
        if (!tiny) drawDisk(center, radius, horizon, color, t, mood, front = false)
        drawCircle(Color.Black, horizon, center)
        drawCircle(color.copy(alpha = 0.95f), horizon * 1.08f, center, style = Stroke(width = radius * if (tiny) 0.05f else 0.022f))
        if (mood == EyeMood.NEEDS) {
            val breath = 0.5f + 0.5f * sin(t * 2.2f)
            drawCircle(color.copy(alpha = 0.25f + 0.35f * breath), horizon * (1.45f + 0.18f * breath), center,
                style = Stroke(width = radius * 0.014f))
        }
        if (!tiny) drawDisk(center, radius, horizon, color, t, mood, front = true)
    }
}

/** The accretion disk, in two halves so the horizon hides the far side. */
private fun DrawScope.drawDisk(center: Offset, radius: Float, horizon: Float, color: Color, t: Float, mood: EyeMood, front: Boolean) {
    val flat = when (mood) { EyeMood.THINKING -> 0.42f; EyeMood.DONE -> 0.12f; EyeMood.NEEDS -> 0.3f; else -> 0.24f }
    val speed = when (mood) { EyeMood.THINKING -> 1.6f; EyeMood.WORKING -> 0.9f; EyeMood.DONE -> 0.25f; else -> 0.45f }
    val inner = horizon * 1.35f
    val outer = radius * if (mood == EyeMood.THINKING) 0.5f else 0.4f
    rotate(-12f, center) {
        for (index in 0 until DISK_PARTICLES) {
            val lane = (index * 7 % DISK_PARTICLES) / DISK_PARTICLES.toFloat()
            val r = inner + (outer - inner) * if (mood == EyeMood.DONE) 0.35f else lane
            val angle = index * (2f * PI.toFloat() / DISK_PARTICLES) + t * speed * (1.3f - lane * 0.6f)
            val behind = sin(angle) < 0f
            if (behind == front) continue
            val x = center.x + cos(angle) * r
            val y = center.y + sin(angle) * r * flat
            val dx = x - center.x; val dy = y - center.y
            if (behind && dx * dx + dy * dy < horizon * horizon * 1.1f) continue
            val glow = 1f - lane * 0.7f
            val alpha = (if (behind) 0.35f else 0.9f) * glow
            drawCircle(color.copy(alpha = alpha), radius * (0.012f + 0.014f * glow), Offset(x, y))
        }
    }
    if (front && mood != EyeMood.DONE) {
        // A faint streak where the disk crosses in front of the horizon.
        rotate(-12f, center) {
            drawLine(color.copy(alpha = 0.55f), Offset(center.x - outer * 0.95f, center.y), Offset(center.x + outer * 0.95f, center.y),
                strokeWidth = radius * 0.012f)
        }
    }
}

private fun DrawScope.drawSatellites(center: Offset, radius: Float, color: Color, t: Float, count: Int) {
    for (index in 0 until count) {
        val angle = t * (0.5f + index * 0.13f) + index * (2f * PI.toFloat() / count)
        val orbit = radius * 0.98f
        val x = center.x + cos(angle) * orbit
        val y = center.y + sin(angle) * orbit * 0.38f - radius * 0.1f
        val near = sin(angle) > 0f
        drawCircle(color.copy(alpha = if (near) 0.95f else 0.4f), radius * if (near) 0.04f else 0.028f, Offset(x, y))
    }
}

private fun DrawScope.drawWave(center: Offset, radius: Float, color: Color, t: Float) {
    val phase = (t % 3.2f) / 3.2f
    if (phase > 0.6f) return
    val p = phase / 0.6f
    drawCircle(color.copy(alpha = (1f - p) * 0.45f), radius * (0.3f + 0.65f * p), center, style = Stroke(width = radius * 0.012f))
}

/** The small status ring around a member's avatar on the team page. */
internal fun DrawScope.statusArc(color: Color, sweep: Float, stroke: Float) {
    val inset = stroke / 2f
    drawArc(color, -90f, sweep, useCenter = false, topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke), style = Stroke(width = stroke))
}

/** The eye's mood from the board's state, for the Manager. */
internal fun managerMood(desk: dev.androidagent.app.TaskDeskState, scoutBusy: Boolean): EyeMood {
    val tasks = desk.tasks
    return when {
        !desk.settings.discoveryOn && tasks.none { !it.status.closed } -> EyeMood.PAUSED
        tasks.any { it.status == dev.androidagent.core.TaskStatus.WAITING } -> EyeMood.NEEDS
        tasks.any { it.status == dev.androidagent.core.TaskStatus.PROPOSED } -> EyeMood.NEEDS
        tasks.any { it.status == dev.androidagent.core.TaskStatus.RUNNING || it.status == dev.androidagent.core.TaskStatus.APPROVED } -> EyeMood.WORKING
        scoutBusy -> EyeMood.THINKING
        tasks.any { it.status == dev.androidagent.core.TaskStatus.REVIEW } -> EyeMood.DONE
        !desk.settings.discoveryOn -> EyeMood.PAUSED
        else -> EyeMood.IDLE
    }
}
