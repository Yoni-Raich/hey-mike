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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
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
private val EyePaused = Color(0xFF4D998F)

/** A member's colour, from its hue: same lightness and calm, so the team reads as one family. */
internal fun memberColor(hue: Float): Color = Color.hsv(((hue % 360f) + 360f) % 360f, 0.34f, 0.86f)

/**
 * Mike's face: a sphere of dots with two eyes.
 *
 * The sphere never spins. Its dots sit on an even lattice and only breathe.
 * Each eye is a fixed ring of dots on the surface that moves with the gaze;
 * the eyes push nearby sphere dots outward through a soft spring, so the dots
 * crowd around them and settle back, and a dot under an eye fades instead of
 * racing across it. Moods: thinking ripples the surface and scans, working
 * narrows the eyes and sends one moon per running task, waiting turns amber
 * with wide eyes, a hop and a pulse ring, done bends the eyes into happy
 * arches, paused shows sleepy slits and holds still. Reduced motion draws one
 * still frame.
 *
 * @param tint a team member's own colour; the Manager uses the mood's.
 * @param satellites running tasks, drawn as moons while working (at most four).
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
        else -> tint ?: EyeTeal
    }
    val color by animateColorAsState(base, tween(500), label = "mike-eye-color")
    val density = LocalDensity.current
    val sizePx = remember { mutableIntStateOf(0) }
    val count = latticeSize(with(density) { (sizePx.intValue / 2).toDp() })
    val first = remember(count) { lattices[count] ?: fibonacci(count) }
    val lattice by produceState(first, count) { value = withContext(Dispatchers.Default) { relaxedLattice(count) } }
    val orb = remember(lattice) { Orb(lattice) }
    val time = remember { mutableFloatStateOf(0f) }
    val currentMood by rememberUpdatedState(mood)
    val moving = animate && animationsEnabled() && mood != EyeMood.PAUSED
    LaunchedEffect(moving, orb) {
        if (!moving) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = if (previous == 0L) 0.016f else (now - previous).coerceIn(0L, 50_000_000L) / 1_000_000_000f
                previous = now
                orb.step(time.floatValue + dt, dt, currentMood, snap = false)
                time.floatValue += dt
            }
        }
    }
    Canvas(modifier.onSizeChanged { sizePx.intValue = min(it.width, it.height) }
        .semantics { contentDescription = description ?: "Mike, ${mood.label}" }) {
        val t = time.floatValue
        if (!moving) orb.step(t, 0f, mood, snap = true)
        orb.draw(this, t, color, mood, satellites.coerceIn(1, 4))
    }
}

/** Fewer, larger dots for a small avatar so the eyes still read. */
private fun latticeSize(radius: Dp): Int = when {
    radius < 22.dp -> 200
    radius < 45.dp -> 520
    else -> 1100
}

private fun fibonacci(count: Int) = FloatArray(count * 3).also { points ->
    for (index in 0 until count) {
        val y = 1f - 2f * (index + 0.5f) / count
        val r = sqrt(1f - y * y)
        val angle = index * 2.399963f
        points[index * 3] = cos(angle) * r + sin(index * 12.9898f) * 0.02f
        points[index * 3 + 1] = y
        points[index * 3 + 2] = sin(angle) * r
    }
}

private val lattices = ConcurrentHashMap<Int, FloatArray>()

/** An even lattice, relaxed once per size and kept for the process: no spiral seams. */
private fun relaxedLattice(count: Int): FloatArray = lattices.getOrPut(count) {
    val p = fibonacci(count)
    val s0 = 1.12f * sqrt(4f * PI.toFloat() / count)
    val f = FloatArray(count * 3)
    repeat(60) {
        f.fill(0f)
        for (i in 0 until count) for (j in i + 1 until count) {
            val dx = p[i * 3] - p[j * 3]; if (dx > s0 || dx < -s0) continue
            val dy = p[i * 3 + 1] - p[j * 3 + 1]; if (dy > s0 || dy < -s0) continue
            val dz = p[i * 3 + 2] - p[j * 3 + 2]; if (dz > s0 || dz < -s0) continue
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= s0 * s0 || d2 < 1e-9f) continue
            val d = sqrt(d2); val push = (s0 - d) / d * 0.5f
            f[i * 3] += dx * push; f[i * 3 + 1] += dy * push; f[i * 3 + 2] += dz * push
            f[j * 3] -= dx * push; f[j * 3 + 1] -= dy * push; f[j * 3 + 2] -= dz * push
        }
        for (i in 0 until count) {
            val x = p[i * 3] + f[i * 3] * 0.3f; val y = p[i * 3 + 1] + f[i * 3 + 1] * 0.3f; val z = p[i * 3 + 2] + f[i * 3 + 2] * 0.3f
            val l = sqrt(x * x + y * y + z * z)
            p[i * 3] = x / l; p[i * 3 + 1] = y / l; p[i * 3 + 2] = z / l
        }
    }
    p
}

// Mood parameters, eased toward each mood's targets.
private const val WOB = 0; private const val AMP = 1; private const val PITCH = 2; private const val OPEN = 3
private const val SMILE = 4; private const val SCAN = 5; private const val SAT = 6; private const val HOP = 7
private const val RING = 8; private const val DIM = 9; private const val SHIMMER = 10; private const val GLOW = 11

private fun targets(mood: EyeMood): FloatArray = when (mood) {
    EyeMood.IDLE -> floatArrayOf(0.45f, 0.3f, 0.06f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0.5f)
    EyeMood.THINKING -> floatArrayOf(1f, 0.08f, 0.16f, 0.9f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 0.55f)
    EyeMood.WORKING -> floatArrayOf(0.6f, 0.05f, -0.12f, 0.62f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0.6f)
    EyeMood.NEEDS -> floatArrayOf(0.8f, 0.03f, 0.05f, 1.12f, 0f, 0f, 0f, 1f, 1f, 1f, 0f, 0.85f)
    EyeMood.DONE -> floatArrayOf(0.6f, 0.08f, 0.03f, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 0.65f)
    EyeMood.PAUSED -> floatArrayOf(0.12f, 0f, -0.08f, 0.3f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0.15f)
}

/** One eye on the unit sphere: its centre, a tangent frame, and its half width and height (radians). */
private class Eye {
    val c = FloatArray(3); val e = FloatArray(3); val nn = FloatArray(3)
    var w = 0f; var h = 0f

    /** The edge's distance from the centre toward (cs, sn), in bent space: a pill shape. */
    fun edge(cs: Float, sn: Float): Float = ((abs(cs) / w).pow(2.4f) + (abs(sn) / h).pow(2.4f)).pow(-1f / 2.4f)
}

private class Orb(private val lattice: FloatArray) {
    private val n = lattice.size / 3
    private val off = FloatArray(n * 3)
    private val vel = FloatArray(n * 3)
    private val pos = FloatArray(n * 3)
    private val alpha = FloatArray(n) { 1f }
    private val p = targets(EyeMood.IDLE)
    private val eyes = arrayOf(Eye(), Eye())
    private val rim = max(12, (30f * sqrt(n / 1300f)).roundToInt())
    private val rings = arrayOf(FloatArray(rim * 4), FloatArray(rim * 4))
    private val contour = FloatArray(73 * 2)
    private val length = FloatArray(73)
    private var primed = false
    /** Dots and rings are drawn larger on a coarse lattice, so every size keeps the same look. */
    private val dotScale = sqrt(1300f / n)

    fun step(t: Float, dt: Float, mood: EyeMood, snap: Boolean) {
        val target = targets(mood)
        val k = if (snap || !primed) 1f else 1f - exp(-dt * 4f)
        for (i in p.indices) p[i] += (target[i] - p[i]) * k
        placeEyes(t)
        val bend = 4.2f * p[SMILE]; val lift = 0.07f * p[SMILE]
        val fade = if (snap || !primed) 1f else 1f - exp(-dt * 12f)
        val h = min(dt, 0.033f)
        for (i in 0 until n) {
            val x = lattice[i * 3]; val y = lattice[i * 3 + 1]; val z = lattice[i * 3 + 2]
            var a = 1f; var tx = 0f; var ty = 0f; var tz = 0f
            for (eye in eyes) {
                if (x * eye.c[0] + y * eye.c[1] + z * eye.c[2] < 0.6f) continue
                val du = x * eye.e[0] + z * eye.e[2]
                val dv = x * eye.nn[0] + y * eye.nn[1] + z * eye.nn[2]
                val dvb = dv + bend * du * du - lift
                val rho = sqrt(du * du + dvb * dvb)
                val cs = if (rho > 1e-5f) du / rho else 1f
                val sn = if (rho > 1e-5f) dvb / rho else 0f
                val rb = eye.edge(cs, sn)
                // Under the eye a dot fades; it never races across to the other side.
                val s = ((rho - rb + 0.012f) / 0.03f).coerceIn(0f, 1f)
                a = min(a, s * s * (3f - 2f * s))
                if (rho < rb * 0.7f) continue
                // Around the eye a dot is pushed outward, strongest at the ring.
                val push = 0.1f * exp(-max(0f, rho - rb) / 0.11f) * min(1f, (rho - rb * 0.7f) / (rb * 0.3f))
                val du2 = du + push * cs
                val dv2 = dvb + push * sn - bend * du2 * du2 + lift
                val ga = du2 - du; val gb = dv2 - dv
                tx += ga * eye.e[0] + gb * eye.nn[0]; ty += gb * eye.nn[1]; tz += ga * eye.e[2] + gb * eye.nn[2]
            }
            val o = i * 3
            if (snap || !primed) {
                off[o] = tx; off[o + 1] = ty; off[o + 2] = tz
                vel[o] = 0f; vel[o + 1] = 0f; vel[o + 2] = 0f
            } else {
                // A soft spring: dots lag the eye a little and settle back.
                vel[o] += ((tx - off[o]) * 55f - vel[o] * 9f) * h; off[o] += vel[o] * h
                vel[o + 1] += ((ty - off[o + 1]) * 55f - vel[o + 1] * 9f) * h; off[o + 1] += vel[o + 1] * h
                vel[o + 2] += ((tz - off[o + 2]) * 55f - vel[o + 2] * 9f) * h; off[o + 2] += vel[o + 2] * h
            }
            val px = x + off[o]; val py = y + off[o + 1]; val pz = z + off[o + 2]
            val l = sqrt(px * px + py * py + pz * pz)
            pos[o] = px / l; pos[o + 1] = py / l; pos[o + 2] = pz / l
            alpha[i] += (a - alpha[i]) * fade
        }
        for (index in eyes.indices) placeRing(eyes[index], rings[index], bend, lift)
        primed = true
    }

    private fun placeEyes(t: Float) {
        val bp = t % 4.6f
        var blink = if (bp > 4.15f && bp < 4.6f) 1f - sin((bp - 4.15f) / 0.45f * PI.toFloat()).pow(2) * 0.92f else 1f
        blink = 1f - (1f - blink) * max(0f, 1f - p[SMILE])
        val yaw = p[AMP] * sin(t * 0.45f) * cos(t * 0.17f + 1f) + p[SCAN] * 0.26f * sin(t * 1.7f) + p[SMILE] * 0.06f * sin(t * 4.4f)
        val pitch = p[PITCH] + p[SCAN] * 0.05f * sin(t * 3.1f) + 0.025f * sin(t * 0.6f)
        var height = max(0.02f, 0.39f * p[OPEN] * blink)
        height += (0.04f - height) * p[SMILE]
        val width = 0.175f + 0.075f * p[SMILE]
        val cp = cos(pitch); val sp = sin(pitch)
        for ((index, eye) in eyes.withIndex()) {
            val yw = yaw + (if (index == 0) -0.32f else 0.32f)
            eye.c[0] = sin(yw) * cp; eye.c[1] = sp; eye.c[2] = cos(yw) * cp
            eye.e[0] = cos(yw); eye.e[1] = 0f; eye.e[2] = -sin(yw)
            eye.nn[0] = -sin(yw) * sp; eye.nn[1] = cp; eye.nn[2] = -cos(yw) * sp
            eye.w = width; eye.h = height
        }
    }

    /** A fixed number of dots spaced evenly along the eye's edge. They move only with the eye. */
    private fun placeRing(eye: Eye, out: FloatArray, bend: Float, lift: Float) {
        val m = 72
        for (k in 0..m) {
            val th = k / m.toFloat() * 2f * PI.toFloat()
            val cs = cos(th); val sn = sin(th)
            val rb = eye.edge(cs, sn) + 0.012f
            val du = rb * cs
            contour[k * 2] = du; contour[k * 2 + 1] = rb * sn - bend * du * du + lift
            length[k] = if (k == 0) 0f else length[k - 1] +
                hypot(contour[k * 2] - contour[k * 2 - 2], contour[k * 2 + 1] - contour[k * 2 - 1])
        }
        val total = length[m]
        // When the ring squeezes shut, crowded dots dim so the lid reads as one soft line.
        val crowd = min(1f, total / rim / 0.05f)
        var seg = 0
        for (j in 0 until rim) {
            val want = (j + 0.5f) / rim * total
            while (seg < m - 1 && length[seg + 1] < want) seg++
            val f = (want - length[seg]) / max(1e-6f, length[seg + 1] - length[seg])
            val u = contour[seg * 2] + (contour[seg * 2 + 2] - contour[seg * 2]) * f
            val v = contour[seg * 2 + 1] + (contour[seg * 2 + 3] - contour[seg * 2 + 1]) * f
            val x = eye.c[0] + u * eye.e[0] + v * eye.nn[0]
            val y = eye.c[1] + v * eye.nn[1]
            val z = eye.c[2] + u * eye.e[2] + v * eye.nn[2]
            val l = sqrt(x * x + y * y + z * z)
            out[j * 4] = x / l; out[j * 4 + 1] = y / l; out[j * 4 + 2] = z / l; out[j * 4 + 3] = crowd
        }
    }

    /** Breath and a slow organic wobble, like the voice sphere: radius only, never rotation. */
    private fun breath(x: Float, y: Float, z: Float, t: Float): Float =
        1f + 0.02f * sin(t * 1.3f) +
            p[WOB] * (0.028f * sin(2.7f * x + t * 1.7f) * cos(2.1f * y - t * 1.3f) + 0.018f * sin(3.3f * z + 1.9f * y + t * 2.2f))

    fun draw(scope: DrawScope, t: Float, color: Color, mood: EyeMood, moons: Int) = with(scope) {
        val half = min(size.width, size.height) / 2f
        val r = half * 0.8f
        val unit = r / 118f
        var hop = 0f
        if (p[HOP] > 0.02f) {
            val phase = (t % 2.8f) / 2.8f
            if (phase < 0.25f) hop = -10f * unit * sin(phase / 0.25f * PI.toFloat()) * p[HOP]
        }
        val cx = size.width / 2f; val cy = size.height / 2f + hop
        val center = Offset(cx, cy)
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.2f * p[GLOW] + 0.04f), color.copy(alpha = 0.05f * p[GLOW]), Color.Transparent),
                center, half),
            half, center,
        )
        if (p[RING] > 0.02f) {
            val period = if (mood == EyeMood.DONE) 2.4f else 1.8f
            val phase = (t % period) / period
            drawCircle(color.copy(alpha = 0.5f * (1f - phase) * p[RING]), r * (0.95f + 0.3f * phase), center, style = Stroke(width = 2f * unit))
        }
        fun moon(front: Boolean) {
            if (p[SAT] <= 0.02f) return
            for (m in 0 until moons) {
                val ph = t * 1.4f + m * 2f * PI.toFloat() / moons
                val depth = sin(ph)
                if ((depth >= 0f) != front) continue
                drawCircle(color.copy(alpha = p[SAT] * (0.5f + 0.45f * depth)), (5f + 1.5f * depth) * unit,
                    Offset(cx + r * 1.16f * cos(ph), cy + r * 1.16f * depth * 0.28f + 10f * unit))
            }
        }
        moon(front = false)
        val dim = p[DIM]
        val back = color.copy(alpha = 0.12f * dim)
        for (i in 0 until n) {
            val z = pos[i * 3 + 2]
            if (z >= 0f) continue
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val b = breath(x, y, z, t)
            drawCircle(back, 0.8f * unit * dotScale, Offset(cx + r * b * x, cy - r * b * y))
        }
        for (i in 0 until n) {
            val z = pos[i * 3 + 2]; val a = alpha[i]
            if (z < 0f || a < 0.02f) continue
            var o = (0.32f + 0.6f * z) * a
            if (p[SHIMMER] > 0.01f) o *= 1f + p[SHIMMER] * 0.6f * sin(t * 3.2f + i * 0.7f)
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val b = breath(x, y, z, t)
            drawCircle(color.copy(alpha = (o * dim).coerceIn(0f, 1f)), (0.75f + 1.25f * z) * (0.5f + 0.5f * a) * unit * dotScale,
                Offset(cx + r * b * x, cy - r * b * y))
        }
        for (ring in rings) for (j in 0 until rim) {
            val z = ring[j * 4 + 2]
            if (z <= 0f) continue
            val crowd = ring[j * 4 + 3]
            var o = (0.55f + 0.45f * z) * (0.45f + 0.55f * crowd)
            if (p[SHIMMER] > 0.01f) o *= 1f + p[SHIMMER] * 0.4f * sin(t * 3.2f + j * 1.3f)
            val x = ring[j * 4]; val y = ring[j * 4 + 1]; val b = breath(x, y, z, t)
            drawCircle(color.copy(alpha = (o * dim).coerceIn(0f, 1f)), (0.95f + 1.35f * z) * (0.7f + 0.3f * crowd) * unit * dotScale,
                Offset(cx + r * b * x, cy - r * b * y))
        }
        moon(front = true)
    }
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
