package dev.androidagent.app.ui

import android.graphics.Paint
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.androidagent.core.VoicePhase
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The voice-mode visualiser: a thousand points on a slowly turning sphere.
// Its latitude bands swell with the voice; it is teal while you talk and
// blue while Codex answers. On the way into voice mode it lifts off the
// composer's voice button and grows into the middle of the screen, and on
// the way out it shrinks back into that button.

internal val VoiceTeal = Color(0xFF83D9CA)
internal val VoiceBlue = Color(0xFF69A7FF)

/** The composer's voice button, which the sphere grows out of. */
internal val VoiceButtonBlue = Color(0xFF2F80ED)

private const val POINT_COUNT = 1000
private const val BANDS = 12

// Points are drawn in depth slices, far to near, so a frame is a handful of
// batched point draws instead of a thousand circles.
private const val DEPTH_SLICES = 8
private val StageSize = 360.dp
private val DockSize = 42.dp

/** Frame-to-frame state. Plain fields: the draw pass reads them after `frame` ticks. */
private class SphereMotion {
    var seconds = 0f
    var level = 0f
    val bands = FloatArray(BANDS)
    // 1 while you talk, 0 while Codex talks; the colour follows it.
    var listen = 0f
    // 1 while the call connects: a faster spin in the voice button's blue.
    var connecting = 1f
    var spin = 0f
    // The launch ripple, 0..1 after each entry into voice mode.
    var wave = 1f
    private var wasShown = false

    fun step(dt: Float, phase: VoicePhase, muted: Boolean, input: Float, shown: Boolean) {
        seconds += dt
        if (shown && !wasShown) {
            wave = 0f
            listen = 0f
            connecting = 1f
        }
        wasShown = shown
        wave = min(1f, wave + dt / 0.9f)
        val target = when {
            !shown -> 0f
            phase == VoicePhase.STARTING -> 0.09f + 0.06f * sin(seconds * 3.2f)
            phase == VoicePhase.LISTENING && muted -> 0f
            phase == VoicePhase.LISTENING || phase == VoicePhase.SPEAKING -> input.coerceIn(0f, 1f)
            else -> 0f
        }
        level += (target - level) * approach(dt, if (target > level) 26f else 8f)

        // One loudness value, spread into a moving spectrum. Codex's voice
        // sits a little lower and wider than the user's.
        val speaking = phase == VoicePhase.SPEAKING
        val peak = if (speaking) 4.6f else 3.2f
        val width = if (speaking) 3.6f else 3f
        for (band in 0 until BANDS) {
            val shape = exp(-((band - peak) / width).pow(2))
            val flutter = 0.5f + 0.5f * sin(seconds * (5.3f + band * 1.7f) + band * 2.1f) *
                sin(seconds * (3.1f + band * 0.9f) + band)
            val goal = level * shape * (0.4f + 0.6f * flutter)
            bands[band] += (goal - bands[band]) * approach(dt, if (goal > bands[band]) 30f else 10f)
        }

        val listenGoal = when (phase) {
            VoicePhase.SPEAKING -> 0f
            VoicePhase.LISTENING -> 1f
            else -> listen
        }
        listen += (listenGoal - listen) * approach(dt, 5f)
        connecting += ((if (phase == VoicePhase.STARTING) 1f else 0f) - connecting) * approach(dt, 4f)
        spin += dt * (0.22f + 1.3f * connecting + 0.5f * level)
    }

    fun band(position: Float): Float {
        val at = position.coerceIn(0f, 1f) * (BANDS - 1)
        val low = at.toInt().coerceAtMost(BANDS - 1)
        val high = min(BANDS - 1, low + 1)
        return bands[low] + (bands[high] - bands[low]) * (at - low)
    }
}

private class SphereBuffers {
    // x, y, z of each point on the unit sphere, spread by the golden angle.
    val points = FloatArray(POINT_COUNT * 3).also { points ->
        for (index in 0 until POINT_COUNT) {
            val y = 1f - index / (POINT_COUNT - 1f) * 2f
            val radius = sqrt(1f - y * y)
            val angle = index * 2.399963f
            points[index * 3] = cos(angle) * radius
            points[index * 3 + 1] = y
            points[index * 3 + 2] = sin(angle) * radius
        }
    }
    val slices = Array(DEPTH_SLICES) { FloatArray(POINT_COUNT * 2) }
    val counts = IntArray(DEPTH_SLICES)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
}

/**
 * Full-screen canvas for the sphere and its flight. [dock] and [stage] are in
 * this canvas's coordinates; [flight] runs 0 (docked in the voice button) to 1
 * (on stage). [level] is polled once per frame.
 */
@Composable
internal fun VoiceSphere(
    modifier: Modifier,
    flight: State<Float>,
    shown: Boolean,
    phase: VoicePhase,
    muted: Boolean,
    level: () -> Float,
    dock: () -> Offset,
    stage: () -> Rect,
    /** The flight starts at the power button's edge rather than the voice button. */
    fromEdge: Boolean = false,
) {
    val motion = remember { SphereMotion() }
    val buffers = remember { SphereBuffers() }
    val frame = remember { mutableLongStateOf(0L) }
    val moving = animationsEnabled()
    val currentPhase by rememberUpdatedState(phase)
    val currentMuted by rememberUpdatedState(muted)
    val currentShown by rememberUpdatedState(shown)
    val currentLevel by rememberUpdatedState(level)

    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                // Cap the step so a dropped frame cannot jump the sphere.
                val dt = if (previous == 0L) 0.016f else (now - previous).coerceIn(0L, 50_000_000L) / 1_000_000_000f
                previous = now
                motion.step(dt, currentPhase, currentMuted, currentLevel(), currentShown)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier) {
        frame.longValue
        drawVoiceScene(motion, buffers, flight.value, dock(), stage(), fromEdge)
    }
}

private fun DrawScope.drawVoiceScene(
    motion: SphereMotion,
    buffers: SphereBuffers,
    flight: Float,
    dock: Offset,
    stage: Rect,
    fromEdge: Boolean,
) {
    val progress = flight.coerceIn(0f, 1f)
    if (progress <= 0.001f && motion.wave >= 1f) return
    val dockAt = if (dock.isSpecified) dock else Offset(size.width - 43.dp.toPx(), size.height - 39.dp.toPx())
    val stageAt = if (stage.isEmpty) Offset(size.width / 2f, size.height * 0.4f) else stage.center
    val full = min(StageSize.toPx(), if (stage.isEmpty) size.width * 0.92f else min(stage.width, stage.height) * 0.92f)
    // Different easings on x and y bend the flight into an arc.
    val center = Offset(
        lerp(dockAt.x, stageAt.x, easeInOutCubic(progress)),
        lerp(dockAt.y, stageAt.y, easeOutQuart(progress)),
    )
    val diameter = lerp(DockSize.toPx(), full, easeOutBack(progress))
    val appear = (progress / 0.22f).coerceIn(0f, 1f)
    val base = lerp(lerp(VoiceBlue, VoiceTeal, motion.listen), VoiceButtonBlue, 0.75f * motion.connecting)

    // The whole screen takes on the voice: black first, then a tint that
    // follows the sphere and brightens as it gets louder.
    val veil = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)
    if (veil > 0f) drawRect(Color.Black.copy(alpha = veil))
    drawRect(
        Brush.radialGradient(
            0f to base.copy(alpha = (0.1f + 0.16f * motion.level) * appear),
            0.55f to base.copy(alpha = 0.03f * appear),
            1f to Color.Transparent,
            center = center,
            radius = max(size.width, size.height) * 0.9f,
        ),
    )

    // From the power button: the edge it sits on lights up first, as if the
    // press came through the glass.
    if (fromEdge && motion.wave < 1f) {
        val glow = (1f - motion.wave).pow(1.4f) * min(1f, motion.wave * 8f + 0.35f)
        val reach = 150.dp.toPx()
        scale(scaleX = 0.4f, scaleY = 1f, pivot = dockAt) {
            drawCircle(
                Brush.radialGradient(
                    0f to VoiceBlue.copy(alpha = 0.95f * glow),
                    0.45f to VoiceButtonBlue.copy(alpha = 0.45f * glow),
                    1f to Color.Transparent,
                    center = dockAt,
                    radius = reach,
                ),
                radius = reach,
                center = dockAt,
            )
        }
    }
    // Two rings leave the voice button (or the power button) as it launches.
    if (motion.wave < 1f) {
        for (ring in 0 until 2) {
            val spread = motion.wave * 1.15f - ring * 0.15f
            if (spread <= 0f || spread >= 1f) continue
            drawCircle(
                color = VoiceButtonBlue.copy(alpha = 0.55f * (1f - spread) * (1f - spread)),
                radius = 21.dp.toPx() + easeOutQuart(spread) * (if (fromEdge) 520.dp else 260.dp).toPx(),
                center = dockAt,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
    // Hand-off: the button's own disc, fading as the sphere takes over.
    if (progress > 0.001f && progress < 0.35f) {
        drawCircle(
            color = VoiceButtonBlue.copy(alpha = (1f - progress / 0.35f).pow(1.5f)),
            radius = min(diameter / 2f, 21.dp.toPx() + progress * 60.dp.toPx()),
            center = center,
        )
    }
    if (progress <= 0.001f) return
    drawSphere(motion, buffers, center, diameter, appear, base)
}

private fun DrawScope.drawSphere(
    motion: SphereMotion,
    buffers: SphereBuffers,
    center: Offset,
    diameter: Float,
    appear: Float,
    base: Color,
) {
    val radius = diameter * 0.3f
    val glowRadius = radius * 1.6f
    drawCircle(
        Brush.radialGradient(
            0f to base.copy(alpha = (0.12f + 0.3f * motion.level) * appear),
            1f to Color.Transparent,
            center = center,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = center,
    )

    val seconds = motion.seconds
    val cosY = cos(motion.spin)
    val sinY = sin(motion.spin)
    val cosX = cos(TILT)
    val sinX = sin(TILT)
    val shrink = 1f - 0.25f * motion.connecting
    val points = buffers.points
    val counts = buffers.counts
    counts.fill(0)
    for (index in 0 until POINT_COUNT) {
        val x = points[index * 3]
        val y = points[index * 3 + 1]
        val z = points[index * 3 + 2]
        val band = motion.band(1f - abs(y))
        val wobble = sin(3.1f * x + seconds * 1.9f) * sin(2.7f * y - seconds * 1.3f) * sin(3.3f * z + seconds * 2.2f)
        val push = (1f + 0.05f * wobble * (0.4f + motion.level) + 0.34f * band * (0.55f + 0.45f * wobble)) * shrink
        val px = x * push
        val py = y * push
        val pz = z * push
        val turnedX = px * cosY + pz * sinY
        val turnedZ = -px * sinY + pz * cosY
        val tiltedY = py * cosX - turnedZ * sinX
        val tiltedZ = py * sinX + turnedZ * cosX
        val perspective = 2.8f / (2.8f - tiltedZ)
        val depth = ((tiltedZ + 1f) / 2f).coerceIn(0f, 1f)
        val slice = (depth * DEPTH_SLICES).toInt().coerceAtMost(DEPTH_SLICES - 1)
        val count = counts[slice]
        val target = buffers.slices[slice]
        target[count * 2] = center.x + turnedX * radius * perspective
        target[count * 2 + 1] = center.y + tiltedY * radius * perspective
        counts[slice] = count + 1
    }

    val unit = max(0.35f, diameter / StageSize.toPx()) * density
    val paint = buffers.paint
    drawIntoCanvas { canvas ->
        for (slice in 0 until DEPTH_SLICES) {
            val count = counts[slice]
            if (count == 0) continue
            val depth = (slice + 0.5f) / DEPTH_SLICES
            val dot = (0.55f + 1.35f * depth) * (1f + 0.4f * motion.level) * 0.9f * unit
            paint.strokeWidth = dot * 2f
            paint.color = lerp(base, Color.White, (motion.level * 0.8f * depth).coerceIn(0f, 1f))
                .copy(alpha = ((0.07f + 0.88f * depth * depth) * appear).coerceIn(0f, 1f))
                .toArgb()
            canvas.nativeCanvas.drawPoints(buffers.slices[slice], 0, count * 2, paint)
        }
    }
}

// Tilt towards the viewer, so the poles read as a globe rather than a disc.
private const val TILT = 0.38f

private fun approach(dt: Float, rate: Float): Float = 1f - exp(-dt * rate)

private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

private fun easeOutQuart(t: Float): Float = 1f - (1f - t).pow(4)

// Overshoots slightly before settling, so the sphere lands instead of stopping.
private fun easeOutBack(t: Float): Float {
    val overshoot = 1.2f
    val shifted = t - 1f
    return 1f + (overshoot + 1f) * shifted * shifted * shifted + overshoot * shifted * shifted
}
