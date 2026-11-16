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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.PanelAccent
import dev.androidagent.core.PanelActionState
import dev.androidagent.core.PanelBlock
import kotlinx.coroutines.delay

/** Each accent as a tonal pair: the tile's container when on, and the colour on it. */
internal fun PanelAccent.tonal(): Pair<Color, Color> = when (this) {
    PanelAccent.TEAL -> Color(0xFF164E46) to Color(0xFFA1F2E1)
    PanelAccent.AMBER -> Color(0xFF4D3A0E) to Color(0xFFFFDEA1)
    PanelAccent.VIOLET -> Color(0xFF3B2F5E) to Color(0xFFE3D6FF)
    PanelAccent.ROSE -> Color(0xFF5A1F33) to Color(0xFFFFD9E1)
    PanelAccent.SKY -> Color(0xFF12395A) to Color(0xFFCDE5FF)
    PanelAccent.LIME -> Color(0xFF2E4211) to Color(0xFFD4F19E)
}

/** A tile at rest: a step above the panel's own surface. */
@Composable
internal fun panelTileColor(): Color = MaterialTheme.colorScheme.run { lerp(surface, surfaceVariant, 0.6f) }

/** Wells inside the panel, such as fields and grouped rows: a step below its surface. */
@Composable
internal fun panelInsetColor(): Color = MaterialTheme.colorScheme.run { lerp(background, surface, 0.72f) }

/** Icons whose meaning has a direction, such as "next", are mirrored in right-to-left layouts. */
private fun tileIcon(name: String, on: Boolean): Pair<ImageVector, Boolean> = when (name) {
    "flashlight" -> (if (on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff) to false
    "wifi" -> Icons.Rounded.Wifi to false
    "bluetooth" -> Icons.Rounded.Bluetooth to false
    "volume" -> Icons.AutoMirrored.Rounded.VolumeUp to false
    "mute" -> Icons.AutoMirrored.Rounded.VolumeOff to false
    "brightness" -> Icons.Rounded.LightMode to false
    "moon" -> Icons.Rounded.DarkMode to false
    "music" -> Icons.Rounded.MusicNote to false
    "play" -> Icons.Rounded.PlayArrow to true
    "pause" -> Icons.Rounded.Pause to false
    "next" -> Icons.Rounded.SkipNext to true
    "alarm" -> Icons.Rounded.Alarm to false
    "timer" -> Icons.Rounded.Timer to false
    "phone" -> Icons.Rounded.Phone to false
    "message" -> Icons.Rounded.Sms to false
    "location" -> Icons.Rounded.LocationOn to false
    "camera" -> Icons.Rounded.PhotoCamera to false
    "home" -> Icons.Rounded.Home to false
    "lock" -> Icons.Rounded.Lock to false
    "power" -> Icons.Rounded.PowerSettingsNew to false
    "star" -> Icons.Rounded.Star to false
    "heart" -> Icons.Rounded.Favorite to false
    "light" -> Icons.Rounded.Lightbulb to false
    "fan" -> Icons.Rounded.Air to false
    "tv" -> Icons.Rounded.Tv to false
    "car" -> Icons.Rounded.DirectionsCar to false
    "rotate" -> Icons.Rounded.ScreenRotation to false
    "airplane" -> Icons.Rounded.AirplanemodeActive to false
    "battery" -> Icons.Rounded.BatteryFull to false
    "bell" -> Icons.Rounded.Notifications to false
    "sparkle" -> Icons.Rounded.AutoAwesome to false
    else -> Icons.Rounded.Bolt to false
}

/** The real call under a tile or slider, in monospace. It reads left to right in every language. */
@Composable
internal fun PanelCallCaption(caption: String, color: Color, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Text(
        caption,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textDirection = TextDirection.Ltr,
            textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
        ),
        color = color,
        // Two lines: a tile is half the card wide, and the arguments are the point.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The panel's action tiles, two to a row. A tap runs the tile's tool call in
 * the app at once; the tile shows it working, then shows the result, or
 * shakes and says why it failed.
 */
@Composable
internal fun PanelActionGrid(
    actions: List<PanelBlock.Action>,
    states: Map<String, PanelActionState>,
    onRun: (PanelBlock.Action, Boolean) -> Unit,
) {
    val busy = states.values.any { it.running }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    ActionTile(
                        action = action,
                        state = states[action.id] ?: PanelActionState(on = if (action.switch) action.on else null),
                        locked = busy,
                        onRun = onRun,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionTile(
    action: PanelBlock.Action,
    state: PanelActionState,
    locked: Boolean,
    onRun: (PanelBlock.Action, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = animationsEnabled()
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = action.accent.tonal()
    val on = state.on == true
    val failed = state.ok == false && state.runs > 0 && !state.running

    // A one-shot tile shows a check for a moment after each run that worked.
    var done by remember(action.id) { mutableStateOf(false) }
    LaunchedEffect(state.runs) {
        if (state.runs == 0 || state.ok != true) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!action.switch) {
            done = true
            delay(1_200)
            done = false
        }
    }
    val shake = remember(action.id) { Animatable(0f) }
    LaunchedEffect(state.runs, state.ok) {
        if (motion && state.runs > 0 && state.ok == false) {
            shake.animateTo(0f, keyframes {
                durationMillis = 360
                -8f at 50; 7f at 110; -5f at 170; 4f at 230; -2f at 290; 0f at 360
            })
        }
    }
    val lit = on || done
    fun <T> pick(spec: AnimationSpec<T>): AnimationSpec<T> = if (motion) spec else snap()
    val corner by animateDpAsState(
        targetValue = if (on) 32.dp else 20.dp,
        animationSpec = pick(spring(dampingRatio = 0.6f, stiffness = 400f)),
        label = "corner",
    )
    val background by animateColorAsState(
        when {
            failed -> scheme.errorContainer
            lit -> container
            else -> panelTileColor()
        },
        pick(tween(280)), label = "tile",
    )
    val circle by animateColorAsState(
        when {
            failed -> lerp(scheme.errorContainer, scheme.error, 0.22f)
            lit -> onContainer
            else -> scheme.outlineVariant
        },
        pick(tween(280)), label = "circle",
    )
    val glyph by animateColorAsState(
        when {
            failed -> scheme.error
            lit -> container
            else -> scheme.onSurface
        },
        pick(tween(280)), label = "glyph",
    )
    val text = if (lit) onContainer else scheme.onSurface
    val quiet = if (lit) onContainer else scheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.96f else 1f, pick(spring(stiffness = 600f)), label = "press")
    val pop by animateFloatAsState(if (done) 1.08f else 1f, pick(spring(dampingRatio = 0.5f, stiffness = 500f)), label = "pop")
    val word = when {
        state.running -> "Working…"
        failed -> "Failed"
        done -> "Done"
        action.switch -> if (on) "On" else "Off"
        else -> "Tap to run"
    }
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .heightIn(min = 104.dp)
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(color = text),
                enabled = !locked,
                role = if (action.switch) Role.Switch else Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRun(action, !on)
            }
            .semantics {
                contentDescription = action.label
                stateDescription = word
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (state.running) RunningRing(color = if (lit) onContainer else scheme.secondary, spin = motion)
            val (icon, directional) = when {
                failed -> Icons.Rounded.ErrorOutline to false
                done -> Icons.Rounded.Check to false
                else -> tileIcon(action.icon, on)
            }
            val mirror = directional && LocalLayoutDirection.current == LayoutDirection.Rtl
            Box(
                Modifier
                    .size(40.dp)
                    .graphicsLayer { scaleX = pop; scaleY = pop }
                    .clip(CircleShape)
                    .background(circle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null, tint = glyph,
                    modifier = Modifier.size(22.dp).graphicsLayer { if (mirror) scaleX = -1f },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                action.label,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), fontWeight = FontWeight.Medium,
                color = if (failed) scheme.onErrorContainer else text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(word, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = if (failed) scheme.error else quiet)
            if (action.detail.isNotBlank()) {
                Text(action.detail, style = MaterialTheme.typography.bodySmall, color = quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (failed && state.message.isNotBlank()) {
                Text(
                    state.message, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = scheme.onErrorContainer, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            // What a tap really does, so a label alone cannot hide it.
            PanelCallCaption(
                action.callFor(!on)?.caption ?: action.caption,
                color = if (lit) onContainer.copy(alpha = 0.6f) else scheme.onSurfaceVariant.copy(alpha = 0.82f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A ring around the tile's icon while its call runs: the panel's only looping
 * motion. With animations off it stays still.
 */
@Composable
internal fun RunningRing(color: Color, spin: Boolean, size: Dp = 48.dp) {
    val angle = if (spin) {
        val turn = rememberInfiniteTransition(label = "ring")
        val value by turn.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "angle",
        )
        value
    } else 0f
    Canvas(Modifier.size(size)) {
        drawArc(
            color = color, startAngle = angle - 90f, sweepAngle = 270f, useCenter = false,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
