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

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.androidagent.core.RunPhase
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

// The waiting indicator for agent runs. The phone comes apart into three
// plates, hangs open while a scan line passes through it, then snaps back
// together — and the gear train on the middle plate never stops turning.
// Colour, gear speed and cycle length come from the run phase, so one glyph
// says "thinking", "running a tool" and "controlling your screen".

private const val DRIVE_TEETH = 9
private const val IDLER_TEETH = 6

// Direction from the drive wheel to the idler.
private val MESH_ANGLE = (PI / 5).toFloat()

// Reused from the composer's voice accent so "hands on your screen" reads as
// the same family of blue everywhere in the app.
private val ControlColor = Color(0xFF69A7FF)

private data class PulseStyle(
    val color: Color,
    // Turns per second of the drive wheel.
    val gearSpeed: Float,
    // Seconds for one take-apart-and-reassemble cycle.
    val cycleSeconds: Float,
    val intensity: Float,
    val animated: Boolean = true,
)

private class PulseGeometry(
    val bodyLeft: Float,
    val bodyWidth: Float,
    val topTop: Float,
    val topHeight: Float,
    val midTop: Float,
    val midHeight: Float,
    val bottomTop: Float,
    val bottomHeight: Float,
    val travel: Float,
    val line: Float,
    val detailed: Boolean,
    val glass: Path,
    val chassis: Path,
    // The area a tool animation plays in, roughly the phone's screen.
    val screen: Rect,
    val driveCenter: Offset,
    val drivePitch: Float,
    val idlerCenter: Offset,
    val idlerPitch: Float,
)

@Composable
fun AgentPulse(
    modifier: Modifier = Modifier,
    phase: RunPhase = RunPhase.THINKING,
    controlling: Boolean = false,
    tool: String? = null,
) {
    val style = pulseStyle(phase, controlling)
    val moving = style.animated && animationsEnabled()
    val motion = remember(tool, phase) {
        if (phase == RunPhase.TOOL || phase == RunPhase.CONTROLLING) toolMotion(tool) else null
    }
    val color by animateColorAsState(style.color, tween(durationMillis = 420), label = "agent-pulse-color")
    val gearSpeed by animateFloatAsState(style.gearSpeed, tween(durationMillis = 620), label = "agent-pulse-gear-speed")
    val intensity by animateFloatAsState(style.intensity, tween(durationMillis = 420), label = "agent-pulse-intensity")
    // 1 while the mechanism is on show, 0 while a tool owns the screen. The
    // two states cross-fade instead of cutting, so a run of quick tool calls
    // does not strobe.
    val openness by animateFloatAsState(
        targetValue = if (motion == null) 1f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "agent-pulse-openness",
    )
    val clock = rememberPulseClock(gearSpeed = gearSpeed, cycleSeconds = style.cycleSeconds, moving = moving)

    Box(
        modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
            .drawWithCache {
                val geometry = pulseGeometry(size, 26.dp.toPx())
                onDrawBehind { drawPulse(geometry, clock, color, intensity, openness, motion) }
            },
    )
}

/**
 * What the phone's screen should be doing while a given tool runs. Names come
 * from the device tool gateway; act_and_observe is already unwrapped to the
 * action it carries by the time it reaches the UI.
 */
private enum class ToolMotion { TAP, SWIPE, TYPE, LOOK, LAUNCH, PUSH, PULL, SHELL }

private fun toolMotion(tool: String?): ToolMotion? =
    when (tool?.trim()?.lowercase()?.replace(' ', '_')) {
        "tap" -> ToolMotion.TAP
        "swipe" -> ToolMotion.SWIPE
        "type_text", "key" -> ToolMotion.TYPE
        "read_ui", "screenshot", "device_status" -> ToolMotion.LOOK
        "open_app", "open_intent", "resolve_intent" -> ToolMotion.LAUNCH
        // A workflow or a plan drives the screen through taps; it reads as tapping.
        "run_workflow", "workflow_runner", "act_plan" -> ToolMotion.TAP
        "push_file", "install_apk" -> ToolMotion.PUSH
        "pull_file" -> ToolMotion.PULL
        "shell" -> ToolMotion.SHELL
        else -> null
    }

private fun CacheDrawScope.pulseGeometry(
    size: Size,
    detailThreshold: Float,
): PulseGeometry {
    val extent = size.minDimension
    val line = (extent * 0.055f).coerceAtLeast(1.1f)
    val bodyHeight = extent * 0.74f - line
    val bodyWidth = bodyHeight * 0.60f
    val left = (size.width - bodyWidth) / 2f
    val top = (size.height - bodyHeight) / 2f
    val corner = CornerRadius(bodyWidth * 0.30f)
    val flat = CornerRadius.Zero

    val topHeight = bodyHeight * 0.28f
    val midHeight = bodyHeight * 0.44f
    val bottomHeight = bodyHeight - topHeight - midHeight
    val midTop = top + topHeight
    val bottomTop = midTop + midHeight

    val glass = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, top, left + bodyWidth, top + topHeight),
                topLeft = corner, topRight = corner, bottomRight = flat, bottomLeft = flat,
            ),
        )
    }
    val chassis = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, bottomTop, left + bodyWidth, bottomTop + bottomHeight),
                topLeft = flat, topRight = flat, bottomRight = corner, bottomLeft = corner,
            ),
        )
    }

    val drivePitch = bodyWidth * 0.26f
    val idlerPitch = drivePitch * IDLER_TEETH / DRIVE_TEETH
    val drive = Offset(left + bodyWidth * 0.32f, midTop + midHeight * 0.40f)
    val reach = drivePitch + idlerPitch
    val idler = drive + Offset(cos(MESH_ANGLE) * reach, sin(MESH_ANGLE) * reach)

    return PulseGeometry(
        bodyLeft = left,
        bodyWidth = bodyWidth,
        topTop = top,
        topHeight = topHeight,
        midTop = midTop,
        midHeight = midHeight,
        bottomTop = bottomTop,
        bottomHeight = bottomHeight,
        travel = extent * 0.11f,
        line = line,
        detailed = extent >= detailThreshold,
        glass = glass,
        chassis = chassis,
        screen = Rect(
            left = left + bodyWidth * 0.14f,
            top = top + bodyHeight * 0.13f,
            right = left + bodyWidth * 0.86f,
            bottom = top + bodyHeight * 0.87f,
        ),
        driveCenter = drive,
        drivePitch = drivePitch,
        idlerCenter = idler,
        idlerPitch = idlerPitch,
    )
}

private fun DrawScope.drawPulse(
    geometry: PulseGeometry,
    clock: PulseClock,
    color: Color,
    intensity: Float,
    openness: Float,
    motion: ToolMotion?,
) {
    val alpha = intensity.coerceIn(0f, 1f)
    if (alpha <= 0f) return
    val open = openness.coerceIn(0f, 1f)
    val cycle = clock.cycle.floatValue
    val apart = separation(cycle) * open
    val lift = geometry.travel * apart
    val plateAlpha = (0.55f + 0.45f * apart) * alpha
    val left = geometry.bodyLeft
    val width = geometry.bodyWidth

    // The rig that holds the plates together while they hang open.
    if (apart > 0.02f) {
        val rail = left + width * 0.5f
        val railColor = color.copy(alpha = 0.35f * apart * alpha)
        drawLine(railColor, Offset(rail, geometry.topTop + geometry.topHeight - lift), Offset(rail, geometry.midTop),
            strokeWidth = geometry.line * 0.6f)
        drawLine(railColor, Offset(rail, geometry.midTop + geometry.midHeight), Offset(rail, geometry.bottomTop + lift),
            strokeWidth = geometry.line * 0.6f)
    }

    // Front glass.
    translate(top = -lift) {
        drawPath(geometry.glass, color = color.copy(alpha = plateAlpha), style = Stroke(width = geometry.line))
        if (geometry.detailed) {
            val notchY = geometry.topTop + geometry.topHeight * 0.24f
            drawLine(
                color = color.copy(alpha = (0.5f + 0.5f * apart) * alpha),
                start = Offset(left + width * 0.36f, notchY),
                end = Offset(left + width * 0.64f, notchY),
                strokeWidth = geometry.line * 0.8f,
                cap = StrokeCap.Round,
            )
        }
    }

    // Logic board: two short rails and the gear train itself.
    val boardAlpha = (0.3f + 0.35f * apart) * alpha
    for (railX in listOf(left, left + width)) {
        drawLine(
            color = color.copy(alpha = boardAlpha),
            start = Offset(railX, geometry.midTop + geometry.midHeight * 0.18f),
            end = Offset(railX, geometry.midTop + geometry.midHeight * 0.82f),
            strokeWidth = geometry.line * 0.8f,
            cap = StrokeCap.Round,
        )
    }
    if (motion != null && open < 1f) {
        drawToolMotion(motion, geometry, clock.seconds.floatValue, color, alpha * (1f - open))
    }
    val driveAngle = clock.turns.floatValue * 2f * PI.toFloat()
    // Rolling contact: the idler turns the other way, faster by the tooth
    // ratio, offset by half a tooth so the teeth fall into each other's gaps.
    val idlerAngle = MESH_ANGLE + PI.toFloat() -
        (DRIVE_TEETH.toFloat() / IDLER_TEETH) * (driveAngle - MESH_ANGLE) +
        PI.toFloat() / IDLER_TEETH
    drawGear(geometry.driveCenter, geometry.drivePitch, DRIVE_TEETH, driveAngle, color, alpha * open, geometry.line * 0.9f)
    drawGear(geometry.idlerCenter, geometry.idlerPitch, IDLER_TEETH, idlerAngle, color, alpha * open * 0.8f, geometry.line * 0.9f)

    // Chassis and battery.
    translate(top = lift) {
        drawPath(geometry.chassis, color = color.copy(alpha = plateAlpha), style = Stroke(width = geometry.line))
        if (geometry.detailed) {
            drawRect(
                color = color.copy(alpha = (0.35f + 0.4f * apart) * alpha),
                topLeft = Offset(left + width * 0.22f, geometry.bottomTop + geometry.bottomHeight * 0.34f),
                size = Size(width * 0.56f, maxOf(geometry.bottomHeight * 0.26f, geometry.line)),
            )
        }
    }

    // A scan line runs through the open stack.
    if (apart > 0.6f) {
        val sweep = clock.scan.floatValue
        val from = geometry.topTop - lift
        val to = geometry.bottomTop + geometry.bottomHeight + lift
        val y = from + sweep * (to - from)
        val strength = (apart - 0.6f) / 0.4f * alpha
        drawLine(
            brush = Brush.horizontalGradient(
                0f to color.copy(alpha = 0f),
                0.5f to color.copy(alpha = 0.75f * strength),
                1f to color.copy(alpha = 0f),
                startX = left - width * 0.45f,
                endX = left + width * 1.45f,
            ),
            start = Offset(left - width * 0.45f, y),
            end = Offset(left + width * 1.45f, y),
            strokeWidth = geometry.line * 0.7f,
        )
    }

    // The seams light up for a moment when the plates meet again.
    val flash = snapFlash(cycle) * open
    if (flash > 0.01f) {
        val flashColor = Color.White.copy(alpha = 0.55f * flash * alpha)
        for (seamY in listOf(geometry.midTop, geometry.midTop + geometry.midHeight)) {
            drawLine(flashColor, Offset(left + width * 0.06f, seamY), Offset(left + width * 0.94f, seamY),
                strokeWidth = geometry.line * 0.7f, cap = StrokeCap.Round)
        }
    }
}

// Each tool gets its own small film playing on the phone's screen: you can
// tell a tap from a swipe from a file transfer without reading the label.
private fun DrawScope.drawToolMotion(
    motion: ToolMotion,
    geometry: PulseGeometry,
    seconds: Float,
    color: Color,
    alpha: Float,
) {
    if (alpha <= 0.01f) return
    val screen = geometry.screen
    val line = geometry.line * 0.8f
    when (motion) {
        ToolMotion.TAP -> {
            val beat = 1.2f
            val step = (seconds / beat).toInt()
            val u = (seconds / beat).mod(1f)
            // Three fixed spots, cycled, so the taps look aimed rather than random.
            val spots = listOf(Offset(0.5f, 0.42f), Offset(0.32f, 0.68f), Offset(0.68f, 0.3f))
            val spot = spots[step.mod(spots.size)]
            val center = Offset(screen.left + screen.width * spot.x, screen.top + screen.height * spot.y)
            val radius = screen.width * (0.08f + 0.42f * easeOutCubic(u))
            drawCircle(color.copy(alpha = alpha * (1f - u)), radius, center, style = Stroke(width = line))
            drawCircle(color.copy(alpha = alpha * (1f - u * 2f).coerceAtLeast(0f)), screen.width * 0.09f, center)
        }

        ToolMotion.SWIPE -> {
            val u = (seconds / 1.4f).mod(1f)
            val travel = easeInOutCubic((u / 0.72f).coerceIn(0f, 1f))
            val start = Offset(screen.left + screen.width * 0.28f, screen.bottom - screen.height * 0.16f)
            val end = Offset(screen.left + screen.width * 0.72f, screen.top + screen.height * 0.16f)
            val fade = if (u > 0.72f) 1f - fraction(u, 0.72f, 1f) else 1f
            for (dot in 0 until 7) {
                val behind = (travel - dot * 0.075f).coerceAtLeast(0f)
                val point = Offset(
                    start.x + (end.x - start.x) * behind,
                    start.y + (end.y - start.y) * behind,
                )
                val strength = (1f - dot / 7f)
                drawCircle(
                    color = color.copy(alpha = alpha * fade * strength * strength),
                    radius = line * (0.5f + 0.6f * strength),
                    center = point,
                )
            }
        }

        ToolMotion.TYPE, ToolMotion.SHELL -> {
            val prompt = motion == ToolMotion.SHELL
            val rows = 3
            val rowGap = screen.height / (rows + 1f)
            val beat = 0.85f
            val filled = (seconds / beat).toInt().mod(rows + 1)
            val growing = (seconds / beat).mod(1f)
            val textLeft = if (prompt) screen.left + screen.width * 0.26f else screen.left
            for (row in 0 until rows) {
                val y = screen.top + rowGap * (row + 1)
                if (prompt) {
                    // The ">" that marks a shell line.
                    val tip = screen.left + screen.width * 0.16f
                    val arm = screen.width * 0.1f
                    drawLine(color.copy(alpha = alpha * 0.75f), Offset(tip - arm, y - arm), Offset(tip, y), line * 0.8f, StrokeCap.Round)
                    drawLine(color.copy(alpha = alpha * 0.75f), Offset(tip, y), Offset(tip - arm, y + arm), line * 0.8f, StrokeCap.Round)
                }
                val full = screen.right - textLeft
                val width = when {
                    row < filled -> full * (if (row == rows - 1) 0.62f else 0.86f)
                    row == filled -> full * 0.86f * growing
                    else -> 0f
                }
                if (width > 0f) {
                    drawLine(
                        color = color.copy(alpha = alpha * if (row < filled) 0.55f else 0.95f),
                        start = Offset(textLeft, y),
                        end = Offset(textLeft + width, y),
                        strokeWidth = line,
                        cap = StrokeCap.Round,
                    )
                }
                if (row == filled && seconds.mod(0.6f) < 0.35f) {
                    drawRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(textLeft + width + line * 0.6f, y - rowGap * 0.28f),
                        size = Size(line * 0.9f, rowGap * 0.56f),
                    )
                }
            }
        }

        ToolMotion.LOOK -> {
            // Boxes on the screen light up as the reader passes over them.
            val boxes = listOf(0.16f to 0.34f, 0.42f to 0.26f, 0.62f to 0.5f)
            val u = (seconds / 1.6f).mod(1f)
            val scanY = screen.top + screen.height * u
            for ((offset, widthFraction) in boxes) {
                val y = screen.top + screen.height * offset
                val lit = 1f - ((scanY - y) / (screen.height * 0.22f)).absoluteValue.coerceIn(0f, 1f)
                drawLine(
                    color = color.copy(alpha = alpha * (0.28f + 0.72f * lit)),
                    start = Offset(screen.left, y + screen.height * 0.09f),
                    end = Offset(screen.left + screen.width * widthFraction, y + screen.height * 0.09f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to color.copy(alpha = 0f),
                    0.5f to color.copy(alpha = alpha),
                    1f to color.copy(alpha = 0f),
                    startX = screen.left,
                    endX = screen.right,
                ),
                start = Offset(screen.left, scanY),
                end = Offset(screen.right, scanY),
                strokeWidth = line * 0.8f,
            )
        }

        ToolMotion.LAUNCH -> {
            // An app tile blooming out of the middle of the screen, twice over.
            for (wave in 0 until 2) {
                val u = (seconds / 1.6f + wave * 0.5f).mod(1f)
                val grow = easeOutCubic(u)
                val side = screen.minDimension * (0.22f + 0.78f * grow)
                val center = screen.center
                val rect = Rect(
                    left = center.x - side / 2f,
                    top = center.y - side / 2f,
                    right = center.x + side / 2f,
                    bottom = center.y + side / 2f,
                )
                val tile = Path().apply {
                    addRoundRect(RoundRect(rect, CornerRadius(side * 0.26f)))
                }
                drawPath(tile, color = color.copy(alpha = alpha * (1f - u) * 0.9f), style = Stroke(width = line))
            }
        }

        ToolMotion.PUSH, ToolMotion.PULL -> {
            // Chevrons carrying a file out of the phone, or into it.
            val up = motion == ToolMotion.PUSH
            for (index in 0 until 3) {
                val u = (seconds / 1.2f + index / 3f).mod(1f)
                val along = if (up) 1f - u else u
                val y = screen.top + screen.height * (0.12f + 0.76f * along)
                val fade = (1f - (u - 0.5f).absoluteValue * 2f).coerceIn(0f, 1f)
                val arm = screen.width * 0.2f
                val tipY = if (up) y - arm * 0.5f else y + arm * 0.5f
                val center = screen.left + screen.width * 0.5f
                drawLine(color.copy(alpha = alpha * fade), Offset(center - arm, y), Offset(center, tipY), line, StrokeCap.Round)
                drawLine(color.copy(alpha = alpha * fade), Offset(center, tipY), Offset(center + arm, y), line, StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.drawGear(
    center: Offset,
    pitch: Float,
    teeth: Int,
    angle: Float,
    color: Color,
    alpha: Float,
    line: Float,
) {
    if (alpha <= 0f || pitch <= 0f) return
    val root = pitch * 0.60f
    val tint = color.copy(alpha = alpha.coerceIn(0f, 1f))
    drawCircle(color = tint, radius = root, center = center, style = Stroke(width = line))
    for (tooth in 0 until teeth) {
        val at = angle + tooth * 2f * PI.toFloat() / teeth
        val direction = Offset(cos(at), sin(at))
        drawLine(
            color = tint,
            start = center + direction * root,
            end = center + direction * pitch,
            strokeWidth = line,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(color = tint, radius = maxOf(line * 0.55f, pitch * 0.14f), center = center)
}

// 0 while assembled, 1 while fully apart.
private fun separation(cycle: Float): Float = when {
    cycle < 0.18f -> 0f
    cycle < 0.38f -> easeOutCubic(fraction(cycle, 0.18f, 0.38f))
    cycle < 0.62f -> 1f
    cycle < 0.84f -> 1f - easeInOutCubic(fraction(cycle, 0.62f, 0.84f))
    else -> 0f
}

private fun snapFlash(cycle: Float): Float =
    if (cycle < 0.84f) 0f else 1f - fraction(cycle, 0.84f, 0.96f)

private fun fraction(value: Float, from: Float, to: Float): Float =
    ((value - from) / (to - from)).coerceIn(0f, 1f)

private fun easeOutCubic(t: Float): Float {
    val inverted = 1f - t
    return 1f - inverted * inverted * inverted
}

private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else {
        val shifted = -2f * t + 2f
        1f - shifted * shifted * shifted / 2f
    }

private class PulseClock(
    val turns: androidx.compose.runtime.MutableFloatState,
    val cycle: androidx.compose.runtime.MutableFloatState,
    val scan: androidx.compose.runtime.MutableFloatState,
    // Free-running seconds the tool animations time themselves against.
    val seconds: androidx.compose.runtime.MutableFloatState,
)

@Composable
private fun rememberPulseClock(gearSpeed: Float, cycleSeconds: Float, moving: Boolean): PulseClock {
    val clock = remember {
        PulseClock(
            mutableFloatStateOf(0f),
            mutableFloatStateOf(0f),
            mutableFloatStateOf(0f),
            mutableFloatStateOf(0f),
        )
    }
    val speed = rememberUpdatedState(gearSpeed)
    val period = rememberUpdatedState(cycleSeconds.coerceAtLeast(0.5f))
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (previous != 0L) {
                    // Cap the step so a dropped frame cannot jump the mechanism.
                    val elapsed = (now - previous).coerceIn(0L, 64_000_000L) / 1_000_000_000f
                    clock.turns.floatValue = (clock.turns.floatValue + elapsed * speed.value).mod(1f)
                    clock.cycle.floatValue = (clock.cycle.floatValue + elapsed / period.value).mod(1f)
                    clock.scan.floatValue = (clock.scan.floatValue + elapsed * 0.9f).mod(1f)
                    clock.seconds.floatValue = (clock.seconds.floatValue + elapsed).mod(600f)
                }
                previous = now
            }
        }
    }
    return clock
}

@Composable
private fun pulseStyle(phase: RunPhase, controlling: Boolean): PulseStyle {
    val scheme = MaterialTheme.colorScheme
    if (controlling) return PulseStyle(ControlColor, gearSpeed = 0.42f, cycleSeconds = 3.2f, intensity = 1f)
    return when (phase) {
        RunPhase.STARTING -> PulseStyle(scheme.onSurfaceVariant, gearSpeed = 0.16f, cycleSeconds = 6f, intensity = 0.75f)
        RunPhase.THINKING -> PulseStyle(scheme.secondary, gearSpeed = 0.22f, cycleSeconds = 5.2f, intensity = 1f)
        RunPhase.TOOL -> PulseStyle(scheme.primary, gearSpeed = 0.34f, cycleSeconds = 4f, intensity = 1f)
        RunPhase.CONTROLLING -> PulseStyle(ControlColor, gearSpeed = 0.42f, cycleSeconds = 3.2f, intensity = 1f)
        RunPhase.STOPPING -> PulseStyle(scheme.onSurfaceVariant, gearSpeed = 0.1f, cycleSeconds = 6.5f, intensity = 0.6f)
        RunPhase.ERROR -> PulseStyle(scheme.error, gearSpeed = 0f, cycleSeconds = 6f, intensity = 0.85f, animated = false)
        RunPhase.IDLE -> PulseStyle(scheme.onSurfaceVariant, gearSpeed = 0f, cycleSeconds = 6f, intensity = 0.5f, animated = false)
    }
}

@Composable
internal fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
}
