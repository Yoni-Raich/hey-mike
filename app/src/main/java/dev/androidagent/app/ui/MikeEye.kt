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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

internal val EyeTeal = Color(0xFF4EEEDC)
internal val EyeAmber = Color(0xFFF6B86A)
private val EyeRest = Color(0xFF55E8D7)
private val EyePaused = Color(0xFF4D998F)

/** A member's colour, from its hue: same lightness and calm, so the team reads as one family. */
internal fun memberColor(hue: Float): Color = Color.hsv(((hue % 360f) + 360f) % 360f, 0.34f, 0.86f)

private const val SPHERE_POINTS = 620
private const val SMALL_POINTS = 120
private const val TILT = 0.36f

/**
 * A living sphere of light with two luminous eyes. Motion is quiet and the
 * face stays legible at avatar sizes. Reduced motion draws a still frame.
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
        val tiny = radius < 38f * density
        drawGlow(center, radius, color, mood)
        drawSphere(if (tiny) small else sphere, center, radius, color, t, mood, tiny)
        drawFace(center, radius, color, t, mood, tiny)
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
    val dot = radius * if (tiny) 0.032f else 0.012f
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
        val alpha = (0.14f + 0.86f * depth) * dim
        val pointColor = lerp(color, Color.White, depth * depth * 0.5f)
        drawCircle(pointColor.copy(alpha = alpha), dot * (0.55f + 1.0f * depth), Offset(px, py))
    }
}

private fun DrawScope.drawFace(center: Offset, radius: Float, color: Color, t: Float, mood: EyeMood, tiny: Boolean) {
    val blinkPhase = t % 7.3f
    // Do not start a still frame with closed eyes.
    val blink = if (mood == EyeMood.IDLE && blinkPhase in 3.8f..3.98f)
        1f - sin((blinkPhase - 3.8f) / 0.18f * PI.toFloat()) * 0.88f else 1f
    val eyeWidth = radius * 0.42f
    val eyeHeight = radius * 0.69f
    val eyeY = center.y + radius * 0.06f
    for (side in listOf(-1f, 1f)) {
        val eye = Offset(center.x + side * radius * 0.265f, eyeY)
        scale(1f, blink, eye) {
            if (!tiny) {
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.7f), color.copy(alpha = 0.17f), Color.Transparent), eye, radius * 0.48f), radius * 0.48f, eye)
            }
            val topLeft = Offset(eye.x - eyeWidth / 2f, eye.y - eyeHeight / 2f)
            val eyeSize = Size(eyeWidth, eyeHeight)
            drawOval(Brush.verticalGradient(listOf(Color(0xFF073D38), Color(0xFF000907), Color(0xFF032D28)),
                startY = topLeft.y, endY = topLeft.y + eyeHeight), topLeft, eyeSize)
            drawOval(color.copy(alpha = if (mood == EyeMood.PAUSED) 0.7f else 0.95f), topLeft, eyeSize,
                style = Stroke(width = radius * if (tiny) 0.035f else 0.017f))
            if (!tiny) {
                // Light beads follow the inner rim, leaving a dark, glossy pupil.
                for (i in 0 until 26) {
                    val angle = i * 2f * PI.toFloat() / 26f
                    val p = Offset(eye.x + cos(angle) * eyeWidth * 0.38f, eye.y + sin(angle) * eyeHeight * 0.40f)
                    drawCircle(color.copy(alpha = 0.4f + 0.45f * ((sin(angle) + 1f) / 2f)), radius * 0.014f, p)
                }
                for (i in 0 until 22) {
                    val angle = i * PI.toFloat() / 21f
                    val p = Offset(eye.x + cos(angle) * eyeWidth * 0.27f, eye.y + sin(angle) * eyeHeight * 0.29f)
                    drawCircle(color.copy(alpha = 0.6f), radius * 0.010f, p)
                }
            }
            val glint = Offset(eye.x + eyeWidth * 0.08f, eye.y - eyeHeight * 0.22f)
            if (!tiny) drawCircle(color.copy(alpha = 0.35f), radius * 0.075f, glint)
            drawCircle(Color.White.copy(alpha = if (mood == EyeMood.PAUSED) 0.7f else 1f), radius * if (tiny) 0.035f else 0.042f, glint)
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
