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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.androidagent.core.PanelAccent
import dev.androidagent.core.PanelActionState
import dev.androidagent.core.PanelBlock
import kotlin.math.roundToInt

/** Each accent as a pair: the lit colour and a deep shade it fades into. */
internal fun PanelAccent.glow(): Pair<Color, Color> = when (this) {
    PanelAccent.TEAL -> Color(0xFF6FF2DA) to Color(0xFF0E5F55)
    PanelAccent.AMBER -> Color(0xFFFFD27A) to Color(0xFF7A4A08)
    PanelAccent.VIOLET -> Color(0xFFC6A8FF) to Color(0xFF45288A)
    PanelAccent.ROSE -> Color(0xFFFF9EB8) to Color(0xFF7D2141)
    PanelAccent.SKY -> Color(0xFF8FD3FF) to Color(0xFF124F7D)
    PanelAccent.LIME -> Color(0xFFC9F57A) to Color(0xFF3F6410)
}

private fun tileIcon(name: String, on: Boolean): ImageVector = when (name) {
    "flashlight" -> if (on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff
    "wifi" -> Icons.Rounded.Wifi
    "bluetooth" -> Icons.Rounded.Bluetooth
    "volume" -> Icons.Rounded.VolumeUp
    "mute" -> Icons.Rounded.VolumeOff
    "brightness" -> Icons.Rounded.LightMode
    "moon" -> Icons.Rounded.DarkMode
    "music" -> Icons.Rounded.MusicNote
    "play" -> Icons.Rounded.PlayArrow
    "pause" -> Icons.Rounded.Pause
    "next" -> Icons.Rounded.SkipNext
    "alarm" -> Icons.Rounded.Alarm
    "timer" -> Icons.Rounded.Timer
    "phone" -> Icons.Rounded.Phone
    "message" -> Icons.Rounded.Sms
    "location" -> Icons.Rounded.LocationOn
    "camera" -> Icons.Rounded.PhotoCamera
    "home" -> Icons.Rounded.Home
    "lock" -> Icons.Rounded.Lock
    "power" -> Icons.Rounded.PowerSettingsNew
    "star" -> Icons.Rounded.Star
    "heart" -> Icons.Rounded.Favorite
    "light" -> Icons.Rounded.Lightbulb
    "fan" -> Icons.Rounded.Air
    "tv" -> Icons.Rounded.Tv
    "car" -> Icons.Rounded.DirectionsCar
    "rotate" -> Icons.Rounded.ScreenRotation
    "airplane" -> Icons.Rounded.AirplanemodeActive
    "battery" -> Icons.Rounded.BatteryFull
    "bell" -> Icons.Rounded.Notifications
    "sparkle" -> Icons.Rounded.AutoAwesome
    else -> Icons.Rounded.Bolt
}

/**
 * The panel's action tiles, two to a row. A tap runs the tile's tool call in
 * the app at once; the tile shows it working, then lights up, or shakes and
 * says why it failed.
 */
@Composable
internal fun PanelActionGrid(
    actions: List<PanelBlock.Action>,
    states: Map<String, PanelActionState>,
    onRun: (PanelBlock.Action, Boolean) -> Unit,
) {
    val busy = states.values.any { it.running }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    val haptics = LocalHapticFeedback.current
    val (lit, deep) = action.accent.glow()
    val on = state.on == true
    // A button lights up briefly after each run that worked; a switch stays lit while on.
    val flash = remember(action.id) { Animatable(0f) }
    LaunchedEffect(state.runs) {
        if (state.runs == 0) return@LaunchedEffect
        if (state.ok == true) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            flash.snapTo(1f)
            flash.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    val shake = remember(action.id) { Animatable(0f) }
    LaunchedEffect(state.runs, state.ok) {
        if (state.runs > 0 && state.ok == false) {
            shake.animateTo(0f, keyframes {
                durationMillis = 420
                -14f at 60; 12f at 130; -9f at 200; 6f at 270; -3f at 340; 0f at 420
            })
        }
    }
    val litness by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "lit",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val halo by pulse.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo",
    )
    val spin by pulse.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spin",
    )
    val glow = (litness + flash.value).coerceIn(0f, 1f)
    val border by animateColorAsState(
        when {
            state.ok == false && state.runs > 0 -> PanelInk.Bad.copy(alpha = 0.7f)
            glow > 0.05f -> lit.copy(alpha = 0.35f + 0.5f * glow)
            else -> PanelInk.InsetEdge
        },
        tween(300), label = "border",
    )
    val shape = RoundedCornerShape(22.dp)
    val stateWord = when {
        state.running -> "Working…"
        action.switch -> if (on) "On" else "Off"
        state.ok == true && flash.value > 0.01f -> "Done"
        else -> "Tap to run"
    }
    Column(
        modifier
            .offset { IntOffset(shake.value.roundToInt(), 0) }
            .scale(pressScale)
            .heightIn(min = 124.dp)
            .clip(shape)
            .drawBehind {
                drawRect(PanelInk.Inset)
                if (glow > 0f) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(lit.copy(alpha = 0.55f * glow), deep.copy(alpha = 0.45f * glow), Color.Transparent),
                            center = Offset(size.width * 0.25f, size.height * 0.2f),
                            radius = size.maxDimension * (0.9f + 0.15f * halo),
                        ),
                    )
                }
            }
            .border(1.2.dp, border, shape)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(color = lit),
                enabled = !locked,
                role = if (action.switch) Role.Switch else Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRun(action, !on)
            }
            .semantics {
                contentDescription = action.label
                stateDescription = stateWord
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                // The orb: a soft halo while lit, a turning ring while the call runs.
                Canvas(Modifier.fillMaxSize()) {
                    if (glow > 0f) drawCircle(lit.copy(alpha = 0.28f * glow * halo), radius = size.minDimension / 2f)
                    if (state.running) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(Color.Transparent, lit)),
                            startAngle = spin, sweepAngle = 280f, useCenter = false,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                val iconScale by animateFloatAsState(
                    targetValue = if (on) 1.12f else 1f,
                    animationSpec = spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow),
                    label = "icon",
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                        .clip(CircleShape)
                        .background(if (glow > 0.05f) lit.copy(alpha = 0.18f + 0.7f * glow) else PanelInk.TealSoft.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = tileIcon(action.icon, on),
                        transitionSpec = { (scaleIn(spring(0.4f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                        label = "glyph",
                    ) { glyph ->
                        Icon(
                            glyph, contentDescription = null,
                            tint = if (glow > 0.5f) deep else lit,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (action.switch) Knob(on = on, lit = lit, deep = deep)
            else AnimatedVisibility(state.ok == true && flash.value > 0.01f, enter = scaleIn(spring(0.4f)) + fadeIn(), exit = fadeOut()) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = lit, modifier = Modifier.size(22.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                action.label,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = PanelInk.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            AnimatedContent(targetState = stateWord, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "word") { word ->
                Text(word, style = MaterialTheme.typography.labelMedium, color = if (glow > 0.3f) lit else PanelInk.Muted)
            }
            if (action.detail.isNotBlank()) {
                Text(action.detail, style = MaterialTheme.typography.bodySmall, color = PanelInk.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // What a tap really does, so a label alone cannot hide it.
            Text(
                action.callFor(!on).caption,
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = PanelInk.Muted.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(
            visible = state.ok == false && state.message.isNotBlank() && !state.running,
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = PanelInk.Bad, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                Spacer(Modifier.width(4.dp))
                Text(state.message, style = MaterialTheme.typography.labelSmall, color = PanelInk.Bad, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A small pill switch that slides with a spring and glows when on. */
@Composable
private fun Knob(on: Boolean, lit: Color, deep: Color) {
    val travel by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "knob",
    )
    val track by animateColorAsState(if (on) lit else PanelInk.Fill, tween(250), label = "track")
    Box(
        Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, if (on) lit else PanelInk.InsetEdge, CircleShape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset((travel * 16.dp.toPx()).roundToInt(), 0) }
                .size(18.dp)
                .clip(CircleShape)
                .background(if (on) deep else PanelInk.Muted),
        )
    }
}
