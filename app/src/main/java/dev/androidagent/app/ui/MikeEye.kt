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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the Manager's face shows. Teal is alive and well; amber is "something waits for you". */
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
private val EyePaused = Color(0xFF4D998F)
private val EyeLavender = Color(0xFFB9A2FF)
private val EyeGreen = Color(0xFF7DE08A)

private val HeadColor = Color(0xFFECEAE4)
private val PadColor = Color(0xFFCFCCC4)
private val VisorColor = Color(0xFF14161B)

/** A member's colour, from its hue: same lightness and calm, so the team reads as one family. */
internal fun memberColor(hue: Float): Color = Color.hsv(((hue % 360f) + 360f) % 360f, 0.34f, 0.86f)

/**
 * Mike's face: a smooth ceramic robot head with a dark visor and two glowing eyes.
 *
 * Flat shapes only. The head floats and breathes; the eyes blink and glance. The eyes take
 * the mood's colour. Thinking tilts the head, lifts the gaze and narrows the eyes; working
 * is a quick bouncing stride with pulsing eyes and one light on the chin per running task;
 * waiting turns amber with wide eyes, a hop and an outline that pulses outward; done
 * squints into happy lines with a hop; paused shows sleepy slits and holds still. Reduced
 * motion draws one still frame.
 *
 * @param tint a team member's own colour for the eyes; the Manager uses the mood's.
 * @param satellites running tasks, one chin light each while working (at most four).
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
        EyeMood.THINKING -> tint ?: EyeLavender
        EyeMood.DONE -> tint ?: EyeGreen
        else -> tint ?: EyeTeal
    }
    val color by animateColorAsState(base, tween(500), label = "mike-eye-color")
    val face = remember { MikeFace() }
    val frame = remember { mutableFloatStateOf(0f) }
    val currentMood by rememberUpdatedState(mood)
    val moving = animate && animationsEnabled() && mood != EyeMood.PAUSED
    LaunchedEffect(moving, face) {
        if (!moving) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else (now - previous).coerceIn(0L, 50_000_000L) / 1_000_000_000f
                previous = now
                face.step(dt, currentMood, snap = false)
                frame.floatValue = face.time
            }
        }
    }
    Canvas(modifier.semantics { contentDescription = description ?: "Mike, ${mood.label}" }) {
        frame.floatValue
        if (!moving) face.step(0f, mood, snap = true)
        face.draw(this, color, chinLights(mood, satellites))
    }
}

/** One chin light per running task while working, at most four. Other moods show none. */
internal fun chinLights(mood: EyeMood, satellites: Int): Int =
    if (mood == EyeMood.WORKING) satellites.coerceIn(1, MAX_CHIN_LIGHTS) else 0

private const val MAX_CHIN_LIGHTS = 4

// The eyes' centres and their two soft halos, in design units.
private val EYE_X = floatArrayOf(82f, 118f)
private val HALO_GROW = floatArrayOf(12f, 6f)
private val HALO_ALPHA = floatArrayOf(0.07f, 0.14f)

/** Smooth 0..1 ramp between two phases of a cycle. */
internal fun smoothStep(from: Float, to: Float, x: Float): Float {
    val s = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return s * s * (3f - 2f * s)
}

/** How far the head leaps at [phase] (0..1 of the 2.6 s cycle): a big hop, then a small one, in design units. */
internal fun hopLift(phase: Float): Float = when {
    phase < 0.6f -> 0f
    phase < 0.84f -> 14f * sin((phase - 0.6f) / 0.24f * PI.toFloat())
    else -> 4f * sin((phase - 0.84f) / 0.16f * PI.toFloat())
}

/** The idle glance at [phase] of the 7 s cycle: right, then left, then back to centre. */
internal fun glanceX(phase: Float): Float =
    6f * smoothStep(0.22f, 0.28f, phase) - 12f * smoothStep(0.44f, 0.5f, phase) + 6f * smoothStep(0.7f, 0.76f, phase)

internal fun glanceY(phase: Float): Float =
    -smoothStep(0.22f, 0.28f, phase) + 2f * smoothStep(0.44f, 0.5f, phase) - smoothStep(0.7f, 0.76f, phase)

// Mood parameters, eased toward each mood's targets.
private const val OPEN = 0; private const val WIDE = 1; private const val GLANCE = 2; private const val LOOKUP = 3
private const val TILT = 4; private const val WORK = 5; private const val HOP = 6; private const val RING = 7
private const val DIM = 8; private const val GLOW = 9

private fun targets(mood: EyeMood): FloatArray = when (mood) {
    EyeMood.IDLE -> floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0.5f)
    EyeMood.THINKING -> floatArrayOf(0.6f, 1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0.55f)
    EyeMood.WORKING -> floatArrayOf(1f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0.6f)
    EyeMood.NEEDS -> floatArrayOf(1.15f, 1.1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 0.85f)
    EyeMood.DONE -> floatArrayOf(0.2f, 1.5f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0.65f)
    EyeMood.PAUSED -> floatArrayOf(0.1f, 1.3f, 0f, 0f, 0f, 0f, 0f, 0f, 0.45f, 0.1f)
}

/**
 * The robot head on a 200-unit design square: head (36,40)-(164,146), visor inside it, eyes on
 * the visor's centre line. Everything is drawn in those units and scaled to the canvas.
 */
private class MikeFace {
    private val p = targets(EyeMood.IDLE)
    private var primed = false
    var time = 0f
        private set
    private var bobPhase = 0f
    private var runPhase = 0f
    private var pulsePhase = 0f
    private val highlight = Path().apply { moveTo(64f, 75f); quadraticTo(80f, 67f, 100f, 67f) }

    fun step(dt: Float, mood: EyeMood, snap: Boolean) {
        val target = targets(mood)
        val k = if (snap || !primed) 1f else 1f - exp(-dt * 5f)
        for (i in p.indices) p[i] += (target[i] - p[i]) * k
        time += dt
        // Phases accumulate, so changing speed between moods never makes the motion jump.
        val bobHz = 1f / 4.2f + p[WORK] * (1f / 0.7f - 1f / 4.2f) + p[LOOKUP] * (1f / 3.4f - 1f / 4.2f)
        bobPhase += dt * bobHz * 2f * PI.toFloat()
        runPhase += dt * 2f * PI.toFloat() / 0.7f
        pulsePhase += dt * 2f * PI.toFloat() / 1f
        primed = true
    }

    fun draw(scope: DrawScope, color: Color, lights: Int) = with(scope) {
        val t = time
        val s = min(size.width, size.height) / 200f * 1.1f
        val ox = size.width / 2f - 100f * s
        val oy = size.height / 2f - 112f * s
        val lift = 0.5f - 0.5f * cos(bobPhase)
        val hop = hopLift((t % 2.6f) / 2.6f) * p[HOP]
        val dim = p[DIM]
        val bodyAlpha = 0.55f + 0.45f * dim

        withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
            // Shadow shrinks as the head rises.
            val shrink = 1f - 0.16f * lift - 0.012f * hop
            drawOval(
                Color.Black.copy(alpha = 0.4f * (1f - 0.35f * lift)),
                Offset(100f - 46f * shrink, 177f), Size(92f * shrink, 10f),
            )

            val breathe = 0.5f - 0.5f * cos(t * 2f * PI.toFloat() / 5f)
            val run = sin(runPhase) * p[WORK]
            val scaleX = 1f + 0.015f * breathe + 0.05f * run
            val scaleY = 1f + 0.03f * breathe - 0.055f * run
            val tilt = p[TILT] * (-1f + 5f * sin(t * 2f * PI.toFloat() / 3.2f))
            val pivot = Offset(100f, 146f)
            withTransform({ translate(0f, -5f * lift - hop); scale(scaleX, scaleY, pivot); rotate(tilt, pivot) }) {
                drawRing(color)
                drawRoundRect(PadColor.copy(alpha = bodyAlpha), Offset(26f, 84f), Size(16f, 30f), CornerRadius(8f))
                drawRoundRect(PadColor.copy(alpha = bodyAlpha), Offset(158f, 84f), Size(16f, 30f), CornerRadius(8f))
                drawRoundRect(HeadColor.copy(alpha = bodyAlpha), Offset(36f, 40f), Size(128f, 106f), CornerRadius(42f))
                drawRoundRect(VisorColor, Offset(50f, 60f), Size(100f, 62f), CornerRadius(28f))
                drawPath(highlight, Color.White.copy(alpha = 0.14f), style = Stroke(4f, cap = StrokeCap.Round))
                drawEyes(color, t)
                drawLights(color, lights, t)
            }
        }
    }

    /** The head's outline pulsing outward: "something waits for you". */
    private fun DrawScope.drawRing(color: Color) {
        if (p[RING] <= 0.02f) return
        val phase = (time % 1.8f) / 1.8f
        val e = 30f * phase
        drawRoundRect(
            color.copy(alpha = 0.5f * (1f - phase) * p[RING]),
            Offset(36f - e, 40f - e), Size(128f + 2f * e, 106f + 2f * e), CornerRadius(42f + e),
            style = Stroke(2.5f),
        )
    }

    private fun DrawScope.drawEyes(color: Color, t: Float) {
        val blinkPhase = t % 4.6f
        var blink = if (blinkPhase > 4.15f) 1f - sin((blinkPhase - 4.15f) / 0.45f * PI.toFloat()).let { it * it } * 0.92f else 1f
        blink = 1f - (1f - blink) * (1f - p[WORK])
        val glance = (t % 7f) / 7f
        val sway = cos(t * 2f * PI.toFloat() / 3.2f)
        val lx = p[GLANCE] * glanceX(glance) + p[LOOKUP] * (0.5f + 5.5f * sway)
        val ly = p[GLANCE] * glanceY(glance) + p[LOOKUP] * -7.5f + 2f * p[WORK]
        val pulse = 0.5f + 0.5f * sin(pulsePhase)
        val alpha = (p[DIM] * (1f - 0.45f * p[WORK] * (1f - pulse))).coerceIn(0f, 1f)
        val w = 12f * p[WIDE] * (1f + p[WORK] * (0.1f - 0.15f * pulse))
        val h = max(2.8f, 28f * p[OPEN] * blink * (1f + p[WORK] * (-0.2f + 0.3f * pulse)))
        val glow = p[GLOW] * p[DIM]
        for (cx in EYE_X) {
            val x = cx + lx; val y = 91f + ly
            for (halo in HALO_GROW.indices) {
                val grow = HALO_GROW[halo]
                drawRoundRect(
                    color.copy(alpha = HALO_ALPHA[halo] * glow), Offset(x - w / 2f - grow / 2f, y - h / 2f - grow / 2f),
                    Size(w + grow, h + grow), CornerRadius(min(w, h) / 2f + grow / 2f),
                )
            }
            drawRoundRect(color.copy(alpha = alpha), Offset(x - w / 2f, y - h / 2f), Size(w, h), CornerRadius(min(w, h) / 2f))
        }
    }

    /** One short light per running task along the chin, pulsing out of step with its neighbours. */
    private fun DrawScope.drawLights(color: Color, count: Int, t: Float) {
        if (count == 0 || p[WORK] <= 0.02f) return
        val span = count * 14f + (count - 1) * 6f
        val left = 100f - span / 2f
        for (i in 0 until count) {
            val glowing = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 1.2f - i * 1.4f)
            drawRoundRect(
                color.copy(alpha = (0.35f + 0.65f * glowing) * p[WORK]),
                Offset(left + i * 20f, 131f), Size(14f, 5f), CornerRadius(2.5f),
            )
        }
    }
}

/** The small status ring around a member's avatar on the team page. */
internal fun DrawScope.statusArc(color: Color, sweep: Float, stroke: Float) {
    val inset = stroke / 2f
    drawArc(color, -90f, sweep, useCenter = false, topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke), style = Stroke(width = stroke))
}

/** The face's mood from the board's state, for the Manager. */
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
