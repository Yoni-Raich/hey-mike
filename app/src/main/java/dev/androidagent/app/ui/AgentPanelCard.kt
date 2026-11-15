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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.androidagent.core.AgentPanel
import dev.androidagent.core.ButtonStyle
import dev.androidagent.core.FieldKind
import dev.androidagent.core.PanelBlock
import dev.androidagent.core.PanelButton
import dev.androidagent.core.PanelControl
import dev.androidagent.core.Tone

internal object PanelInk {
    val Fill = Color(0xFF161A19)
    val FillTop = Color(0xFF1B2B28)
    val Edge = Color(0xFF2C4A44)
    val Inset = Color(0xFF101312)
    val InsetEdge = Color(0xFF2A2F2E)
    val Ink = Color(0xFFEDEDED)
    val Muted = Color(0xFF9AA5A2)
    val Teal = Color(0xFF83D9CA)
    val OnTeal = Color(0xFF003731)
    val TealSoft = Color(0xFF174E47)
    val Warn = Color(0xFFF6B86A)
    val Bad = Color(0xFFFFB4AB)
    val BadFill = Color(0xFF3A2220)
}

private fun Tone.ink(): Color = when (this) {
    Tone.NEUTRAL -> PanelInk.Ink
    Tone.GOOD -> PanelInk.Teal
    Tone.WARN -> PanelInk.Warn
    Tone.BAD -> PanelInk.Bad
}

private fun Tone.icon(): ImageVector = when (this) {
    Tone.NEUTRAL -> Icons.Outlined.Info
    Tone.GOOD -> Icons.Outlined.CheckCircle
    Tone.WARN -> Icons.Outlined.WarningAmber
    Tone.BAD -> Icons.Outlined.ErrorOutline
}

/**
 * A panel Mike built with `show_ui`, pinned above the composer like a question.
 *
 * The blocks come from a closed set the app knows how to draw; a `control`
 * block is the app's own live settings, the same switches as in Settings.
 * The buttons stay below the scrolling body so they are always in reach.
 * What the user fills in goes back to Mike only when a button is tapped; the
 * close button answers with no button, so Mike knows to stop waiting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentPanelCard(
    panel: AgentPanel,
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    val values = remember(panel.id) {
        mutableStateMapOf<String, String>().apply { panel.inputs.forEach { put(it.id, it.initial) } }
    }
    val missing = panel.inputs.filter { it.required && values[it.id].isNullOrBlank() }
    fun answer(button: String?) = actions.onAnswerPanel(panel.id, button, values.toMap())
    val shown = remember(panel.id) { MutableTransitionState(false).apply { targetState = true } }
    val live = panel.actions.isNotEmpty()
    // Runs of action blocks become one grid of tiles; everything else stays a row of its own.
    val segments = remember(panel.id) {
        buildList<List<PanelBlock>> {
            panel.blocks.forEach { block ->
                val last = lastOrNull()
                if (block is PanelBlock.Action && last?.firstOrNull() is PanelBlock.Action) set(size - 1, last + block)
                else add(listOf(block))
            }
        }
    }

    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(tween(260)) +
            scaleIn(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            expandVertically(tween(280), expandFrom = Alignment.Bottom),
        modifier = modifier,
    ) {
        GlowFrame(shape = RoundedCornerShape(26.dp), lively = live) {
            Column(
                Modifier
                    .background(Brush.verticalGradient(listOf(PanelInk.FillTop, PanelInk.Fill, PanelInk.Fill)))
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            ) {
                PanelHeader(panel.title, live = live, onClose = { answer(null) })
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val lastInput = panel.inputs.lastOrNull { it is PanelBlock.Field }?.id
                    segments.forEachIndexed { index, segment ->
                        StaggerIn(panel.id, index) {
                            val block = segment.first()
                            when (block) {
                                is PanelBlock.Action -> PanelActionGrid(
                                    actions = segment.filterIsInstance<PanelBlock.Action>(),
                                    states = state.runState.panelActions,
                                ) { action, turnOn -> actions.onPanelAction(panel.id, action.id, turnOn) }
                                is PanelBlock.Text -> PanelText(block)
                                is PanelBlock.Status -> PanelStatus(block)
                                is PanelBlock.Field -> PanelField(block, values[block.id].orEmpty(), last = block.id == lastInput) { values[block.id] = it }
                                is PanelBlock.Toggle -> PanelToggle(block, values[block.id] == "true") { values[block.id] = it.toString() }
                                is PanelBlock.Choice -> PanelChoice(block, values[block.id].orEmpty()) { values[block.id] = it }
                                is PanelBlock.Control -> PanelControlBlock(block.control, state, actions)
                            }
                        }
                    }
                }
                HorizontalDivider(color = PanelInk.Edge.copy(alpha = 0.6f))
                PanelButtons(panel.buttons, blocked = missing.isNotEmpty(), onTap = ::answer)
            }
        }
    }
}

/**
 * A card whose edge is a slow ring of light turning around it, the sign that
 * Mike is waiting on this panel. A panel of live controls turns a little
 * faster and brighter.
 */
@Composable
private fun GlowFrame(shape: RoundedCornerShape, lively: Boolean, content: @Composable () -> Unit) {
    val turn = rememberInfiniteTransition(label = "frame")
    val angle by turn.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (lively) 4_200 else 7_000, easing = LinearEasing)),
        label = "angle",
    )
    val ring = remember(lively) {
        Brush.sweepGradient(
            listOf(
                PanelInk.Edge, PanelInk.Teal.copy(alpha = if (lively) 0.95f else 0.7f), Color(0xFFB59CFF).copy(alpha = 0.8f),
                PanelInk.Edge, PanelInk.Edge, PanelInk.Teal.copy(alpha = 0.5f), PanelInk.Edge,
            ),
        )
    }
    Surface(shape = shape, color = Color.Transparent, shadowElevation = 10.dp, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .clip(shape)
                .drawBehind {
                    rotate(angle) {
                        drawCircle(ring, radius = size.maxDimension, center = center)
                    }
                }
                .padding(1.6.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(PanelInk.Fill),
        ) { content() }
    }
}

/** Each part of the panel rises into place a beat after the one above it. */
@Composable
private fun StaggerIn(key: String, index: Int, content: @Composable () -> Unit) {
    val appear = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(70L + 55L * index.coerceAtMost(8))
        appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = appear.value.coerceIn(0f, 1f)
            translationY = (1f - appear.value) * 18.dp.toPx()
        },
    ) { content() }
}

@Composable
private fun PanelHeader(title: String, live: Boolean, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(PanelInk.TealSoft, Color(0xFF2A2350))))
                    .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BreathingOrb()
                Spacer(Modifier.width(7.dp))
                Text(
                    if (live) "Live controls" else "Mike needs you",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = PanelInk.Teal,
                )
            }
            // It holds up the run, so a screen reader hears it at once, as for a question.
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = PanelInk.Ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.Top)) {
            Icon(Icons.Outlined.Close, contentDescription = "Close without answering", tint = PanelInk.Muted)
        }
    }
}

/** Mike's small light: it breathes while he waits. */
@Composable
private fun BreathingOrb() {
    val breath = rememberInfiniteTransition(label = "orb")
    val grow by breath.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "grow",
    )
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(PanelInk.Teal.copy(alpha = 0.25f * grow), radius = size.minDimension / 2f * grow)
            drawCircle(
                Brush.radialGradient(listOf(Color.White, PanelInk.Teal), radius = size.minDimension / 3f),
                radius = size.minDimension / 4.2f,
            )
        }
    }
}

@Composable
private fun PanelText(block: PanelBlock.Text) {
    if (block.tone == Tone.NEUTRAL) {
        Text(block.text, style = MaterialTheme.typography.bodyMedium, color = PanelInk.Ink.copy(alpha = 0.88f))
        return
    }
    val ink = block.tone.ink()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ink.copy(alpha = 0.10f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(block.tone.icon(), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(block.text, style = MaterialTheme.typography.bodyMedium, color = PanelInk.Ink)
    }
}

@Composable
private fun PanelStatus(block: PanelBlock.Status) {
    val ink = block.tone.ink()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelInk.Inset)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(block.label, style = MaterialTheme.typography.bodyMedium, color = PanelInk.Muted, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(CircleShape)
                .background(ink.copy(alpha = if (block.tone == Tone.NEUTRAL) 0.08f else 0.14f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (block.tone != Tone.NEUTRAL) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(ink))
                Spacer(Modifier.width(6.dp))
            }
            Text(block.value.ifBlank { "—" }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink)
        }
    }
}

@Composable
private fun panelFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PanelInk.Teal,
    unfocusedBorderColor = PanelInk.InsetEdge,
    focusedLabelColor = PanelInk.Teal,
    unfocusedLabelColor = PanelInk.Muted,
    cursorColor = PanelInk.Teal,
    focusedTextColor = PanelInk.Ink,
    unfocusedTextColor = PanelInk.Ink,
    focusedContainerColor = PanelInk.Inset,
    unfocusedContainerColor = PanelInk.Inset,
    focusedPlaceholderColor = PanelInk.Muted.copy(alpha = 0.6f),
    unfocusedPlaceholderColor = PanelInk.Muted.copy(alpha = 0.6f),
)

private fun FieldKind.keyboard(): KeyboardType = when (this) {
    FieldKind.TEXT, FieldKind.MULTILINE -> KeyboardType.Text
    FieldKind.EMAIL -> KeyboardType.Email
    FieldKind.SECRET -> KeyboardType.Password
    FieldKind.NUMBER -> KeyboardType.Number
    FieldKind.URL -> KeyboardType.Uri
    FieldKind.PHONE -> KeyboardType.Phone
}

@Composable
private fun PanelField(block: PanelBlock.Field, value: String, last: Boolean, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var reveal by rememberSaveable(block.id) { mutableStateOf(false) }
    val secret = block.kind == FieldKind.SECRET
    val multiline = block.kind == FieldKind.MULTILINE
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (block.required) block.label + " *" else block.label) },
        placeholder = block.placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        shape = RoundedCornerShape(14.dp),
        colors = panelFieldColors(),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = block.kind.keyboard(),
            imeAction = if (multiline) ImeAction.Default else if (last) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = block.kind == FieldKind.TEXT || multiline,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focus.moveFocus(FocusDirection.Down) },
            onDone = { focus.clearFocus() },
        ),
        trailingIcon = if (!secret) null else {
            {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (reveal) "Hide" else "Show",
                        tint = PanelInk.Muted,
                    )
                }
            }
        },
        supportingText = if (secret) {
            { Text("Goes to Mike only. The chat shows it as ••••.", color = PanelInk.Muted) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PanelToggle(block: PanelBlock.Toggle, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelInk.Inset)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(block.label, style = MaterialTheme.typography.bodyLarge, color = PanelInk.Ink)
            if (block.detail.isNotBlank()) {
                Text(block.detail, style = MaterialTheme.typography.bodySmall, color = PanelInk.Muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PanelInk.OnTeal,
                checkedTrackColor = PanelInk.Teal,
                uncheckedThumbColor = PanelInk.Muted,
                uncheckedTrackColor = PanelInk.Fill,
                uncheckedBorderColor = PanelInk.InsetEdge,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelChoice(block: PanelBlock.Choice, value: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (block.required) block.label + " *" else block.label,
            style = MaterialTheme.typography.labelLarge,
            color = PanelInk.Muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.options.forEach { option ->
                val picked = option == value
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (picked) PanelInk.Teal else PanelInk.Inset)
                        .border(1.dp, if (picked) PanelInk.Teal else PanelInk.InsetEdge, CircleShape)
                        .selectable(selected = picked, role = Role.RadioButton, onClick = { onPick(option) })
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (picked) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = PanelInk.OnTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        option,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (picked) PanelInk.OnTeal else PanelInk.Ink,
                    )
                }
            }
        }
    }
}

/** The app's own controls, live: what the user changes here takes effect at once, as in Settings. */
@Composable
private fun PanelControlBlock(control: PanelControl, state: AgentUiState, actions: AgentUiActions) {
    val heading = panelSettingsTitle(control) ?: ("Add a computer" to Icons.Outlined.Computer)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelInk.Inset)
            .border(1.dp, PanelInk.InsetEdge, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(PanelInk.TealSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(heading.second, contentDescription = null, tint = PanelInk.Teal, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(heading.first, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = PanelInk.Ink)
        }
        if (control == PanelControl.ADD_COMPUTER) AddComputerForm(state, actions)
        else PanelSettingsControl(control, state, actions)
    }
}

/**
 * A short form for a new computer. The password is saved on the phone with
 * the computer and never goes to Mike; the panel's answer only says a
 * computer was added.
 */
@Composable
private fun ColumnScope.AddComputerForm(state: AgentUiState, actions: AgentUiActions) {
    var label by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current

    saved?.let { name ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelInk.Teal.copy(alpha = 0.12f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = PanelInk.Teal, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("$name is saved. Tap Done so Mike can connect.", style = MaterialTheme.typography.bodyMedium, color = PanelInk.Ink)
        }
        return
    }
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus() })
    @Composable fun field(value: String, label: String, kind: KeyboardType, last: Boolean = false, hint: String = "", onChange: (String) -> Unit) =
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = hint.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = panelFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = kind, imeAction = if (last) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = next,
            modifier = Modifier.fillMaxWidth(),
        )
    field(label, "Name", KeyboardType.Text, hint = "Home PC") { label = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { field(host, "Address", KeyboardType.Uri, hint = "192.168.1.20") { host = it.trim() } }
        Box(Modifier.width(92.dp)) { field(port, "Port", KeyboardType.Number) { port = it.filter(Char::isDigit).take(5) } }
    }
    field(user, "User name", KeyboardType.Text) { user = it.trim() }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = panelFieldColors(),
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = next,
        trailingIcon = {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (reveal) "Hide" else "Show", tint = PanelInk.Muted)
            }
        },
        supportingText = { Text("Stays on this phone. Mike never sees it.", color = PanelInk.Muted) },
        modifier = Modifier.fillMaxWidth(),
    )
    val ready = host.isNotBlank() && user.isNotBlank() && port.isNotBlank()
    Button(
        onClick = {
            val name = label.ifBlank { host }
            actions.onSaveComputer(
                ComputerDraft(label = name, host = host, port = port, user = user, password = password, isDefault = state.computers.isEmpty()),
            )
            password = ""
            saved = name
            focus.clearFocus()
        },
        enabled = ready,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PanelInk.Teal, contentColor = PanelInk.OnTeal),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Save computer", fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PanelButtons(buttons: List<PanelButton>, blocked: Boolean, onTap: (String) -> Unit) {
    // Cancel goes last and quietest; the main action sits where the thumb is.
    val ordered = buttons.sortedBy { if (it.style == ButtonStyle.CANCEL) 1 else 0 }
    val stacked = buttons.size > 2
    @Composable fun one(button: PanelButton, mod: Modifier) {
        val needsInput = blocked && button.style != ButtonStyle.CANCEL
        val shape = RoundedCornerShape(14.dp)
        val tall = mod.heightIn(min = 48.dp)
        when (button.style) {
            ButtonStyle.PRIMARY -> Button(
                onClick = { onTap(button.id) }, enabled = !needsInput, shape = shape, modifier = tall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PanelInk.Teal, contentColor = PanelInk.OnTeal,
                    disabledContainerColor = PanelInk.TealSoft.copy(alpha = 0.5f), disabledContentColor = PanelInk.Muted,
                ),
            ) { Text(button.label, fontWeight = FontWeight.SemiBold) }
            ButtonStyle.SECONDARY -> OutlinedButton(
                onClick = { onTap(button.id) }, enabled = !needsInput, shape = shape, modifier = tall,
                border = BorderStroke(1.dp, PanelInk.Teal.copy(alpha = if (needsInput) 0.25f else 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PanelInk.Teal),
            ) { Text(button.label) }
            ButtonStyle.DANGER -> Button(
                onClick = { onTap(button.id) }, enabled = !needsInput, shape = shape, modifier = tall,
                colors = ButtonDefaults.buttonColors(containerColor = PanelInk.BadFill, contentColor = PanelInk.Bad),
            ) { Text(button.label, fontWeight = FontWeight.SemiBold) }
            ButtonStyle.CANCEL -> TextButton(
                onClick = { onTap(button.id) }, shape = shape, modifier = tall,
                colors = ButtonDefaults.textButtonColors(contentColor = PanelInk.Muted),
            ) { Text(button.label) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (blocked) {
            Text(
                "Fill in the fields marked * to go on",
                style = MaterialTheme.typography.labelMedium,
                color = PanelInk.Muted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (stacked) {
            ordered.forEach { one(it, Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ordered.forEach { one(it, Modifier.weight(1f)) }
            }
        }
    }
}
