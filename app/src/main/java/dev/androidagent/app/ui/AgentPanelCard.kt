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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.AgentPanel
import dev.androidagent.core.ButtonStyle
import dev.androidagent.core.FieldKind
import dev.androidagent.core.PanelActionState
import dev.androidagent.core.PanelBlock
import dev.androidagent.core.PanelButton
import dev.androidagent.core.PanelControl
import dev.androidagent.core.Tone
import kotlinx.coroutines.delay

@Composable
private fun Tone.color(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    Tone.GOOD -> MaterialTheme.colorScheme.secondary
    Tone.WARN -> RuleBlocked
    Tone.BAD -> MaterialTheme.colorScheme.error
}

private fun Tone.icon(): ImageVector = when (this) {
    Tone.NEUTRAL -> Icons.Outlined.Info
    Tone.GOOD -> Icons.Outlined.CheckCircle
    Tone.WARN -> Icons.Outlined.WarningAmber
    Tone.BAD -> Icons.Outlined.ErrorOutline
}

private fun Tone.filledIcon(): ImageVector = when (this) {
    Tone.NEUTRAL -> Icons.Filled.Info
    Tone.GOOD -> Icons.Filled.CheckCircle
    Tone.WARN -> Icons.Filled.Warning
    Tone.BAD -> Icons.Filled.Error
}

/** "A", "A and B", "A, B and C", "A, B and 2 more". */
internal fun panelMissingHint(labels: List<String>): String {
    val names = when {
        labels.size <= 1 -> labels.joinToString()
        labels.size <= 3 -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        else -> labels.take(2).joinToString(", ") + " and ${labels.size - 2} more"
    }
    return "Fill in $names to continue"
}

/**
 * A panel Mike built with `show_ui`, pinned above the composer like a question.
 *
 * The blocks come from a closed set the app knows how to draw; a `control`
 * block is the app's own live settings, the same switches as in Settings.
 * The buttons stay below the scrolling body so they are always in reach.
 * What the user fills in goes back to Mike only when a button is tapped; the
 * close button answers with no button, so Mike knows to stop waiting.
 *
 * It is drawn in the app's own theme. At rest nothing moves; the only
 * looping motion is the ring on a tile whose call is running.
 */
@Composable
internal fun AgentPanelCard(
    panel: AgentPanel,
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    val motion = animationsEnabled()
    val values = remember(panel.id) {
        // A live slider's value is the one the phone has now, so a redrawn card does not jump back to the start.
        mutableStateMapOf<String, String>().apply {
            panel.inputs.forEach { put(it.id, state.runState.panelActions[it.id]?.value ?: it.initial) }
        }
    }
    val missing = panel.inputs.filter { it.required && values[it.id].isNullOrBlank() }
    fun answer(button: String?) = actions.onAnswerPanel(panel.id, button, values.toMap())
    val shown = remember(panel.id) { MutableTransitionState(!motion).apply { targetState = true } }
    val live = panel.actions.isNotEmpty()
    // Runs of action tiles become one grid, runs of status rows one card; everything else stays a row of its own.
    val segments = remember(panel.id) {
        buildList<List<PanelBlock>> {
            panel.blocks.forEach { block ->
                val last = lastOrNull()
                val joins = (block is PanelBlock.Action && last?.firstOrNull() is PanelBlock.Action) ||
                    (block is PanelBlock.Status && last?.firstOrNull() is PanelBlock.Status)
                if (joins) set(size - 1, last!! + block) else add(listOf(block))
            }
        }
    }

    AnimatedVisibility(
        visibleState = shown,
        enter = if (motion) fadeIn(tween(200)) + expandVertically(tween(240), expandFrom = Alignment.Bottom) else EnterTransition.None,
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(if (motion) Modifier.animateContentSize(spring(stiffness = 400f)) else Modifier) {
                PanelHeader(panel.title, live = live, onClose = { answer(null) })
                Column(
                    modifier = Modifier
                        // Header and buttons stay; the body gives up height when the screen is short.
                        .weight(1f, fill = false)
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val lastInput = panel.inputs.lastOrNull { it is PanelBlock.Field }?.id
                    segments.forEachIndexed { index, segment ->
                        EnterOnce(panel.id, index, motion) {
                            when (val block = segment.first()) {
                                is PanelBlock.Action -> PanelActionGrid(
                                    actions = segment.filterIsInstance<PanelBlock.Action>(),
                                    states = state.runState.panelActions,
                                ) { action, turnOn -> actions.onPanelAction(panel.id, action.id, turnOn) }
                                is PanelBlock.Status -> PanelStatusGroup(segment.filterIsInstance<PanelBlock.Status>())
                                is PanelBlock.Text -> PanelText(block)
                                is PanelBlock.Field -> PanelField(block, values[block.id].orEmpty(), last = block.id == lastInput) { values[block.id] = it }
                                is PanelBlock.Toggle -> PanelToggle(block, values[block.id] == "true") { values[block.id] = it.toString() }
                                is PanelBlock.Choice -> PanelChoice(block, values[block.id].orEmpty(), motion) { values[block.id] = it }
                                is PanelBlock.Control -> PanelControlBlock(block.control, state, actions)
                                is PanelBlock.Slider -> PanelSlider(
                                    block,
                                    applied = state.runState.panelActions[block.id],
                                    busy = state.runState.panelActions.values.any { it.running },
                                    onSet = { values[block.id] = it },
                                ) { value -> actions.onPanelSlider(panel.id, block.id, value) }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                PanelButtons(panel.buttons, missing = missing.map { it.label }, onTap = ::answer)
            }
        }
    }
}

/** Each part of the panel fades up a beat after the one above it, once, when the panel opens. */
@Composable
private fun EnterOnce(key: String, index: Int, motion: Boolean, content: @Composable () -> Unit) {
    val appear = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(key) {
        if (appear.value >= 1f) return@LaunchedEffect
        delay(30L * index.coerceAtMost(10))
        appear.animateTo(1f, tween(220))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 8.dp.toPx()
        },
    ) { content() }
}

@Composable
private fun PanelHeader(title: String, live: Boolean, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.secondary))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (live) "Live controls" else "Mike needs you",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.Medium,
                    color = scheme.secondary,
                )
            }
            // It holds up the run, so a screen reader hears it at once, as for a question.
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp),
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Close without answering", tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PanelText(block: PanelBlock.Text) {
    val scheme = MaterialTheme.colorScheme
    if (block.tone == Tone.NEUTRAL) {
        Text(block.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface.copy(alpha = 0.88f))
        return
    }
    val ink = block.tone.color()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ink.copy(alpha = 0.10f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(block.tone.icon(), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(block.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

/** Status rows that follow each other share one card, split by thin lines. */
@Composable
private fun PanelStatusGroup(rows: List<PanelBlock.Status>) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(panelInsetColor()),
    ) {
        rows.forEachIndexed { index, block ->
            if (index > 0) HorizontalDivider(color = panelTileColor(), modifier = Modifier.padding(horizontal = 16.dp))
            val ink = block.tone.color()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(block.label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                if (block.tone != Tone.NEUTRAL) {
                    Icon(block.tone.filledIcon(), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(block.value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ink)
            }
        }
    }
}

@Composable
internal fun panelFieldColors() = MaterialTheme.colorScheme.run {
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = secondary,
        unfocusedBorderColor = outlineVariant,
        focusedLabelColor = secondary,
        unfocusedLabelColor = onSurfaceVariant,
        cursorColor = secondary,
        focusedTextColor = onSurface,
        unfocusedTextColor = onSurface,
        focusedContainerColor = panelInsetColor(),
        unfocusedContainerColor = panelInsetColor(),
        focusedPlaceholderColor = onSurfaceVariant.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = onSurfaceVariant.copy(alpha = 0.6f),
        focusedSupportingTextColor = onSurfaceVariant,
        unfocusedSupportingTextColor = onSurfaceVariant,
    )
}

/** Set by hand: the app theme leaves the colours a default switch reads for "off" unset. */
@Composable
internal fun panelSwitchColors() = MaterialTheme.colorScheme.run {
    SwitchDefaults.colors(
        checkedThumbColor = onSecondary,
        checkedTrackColor = secondary,
        checkedBorderColor = secondary,
        checkedIconColor = secondary,
        uncheckedThumbColor = onSurfaceVariant,
        uncheckedTrackColor = panelInsetColor(),
        uncheckedBorderColor = outlineVariant,
    )
}

/** A field's name, set above it. */
@Composable
private fun FieldLabel(label: String, required: Boolean) {
    Text(
        if (required) "$label *" else label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

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
    Column {
        FieldLabel(block.label, block.required)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
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
                { RevealButton(reveal) { reveal = !reveal } }
            },
            supportingText = if (secret) {
                { Text("Goes to Mike only. The chat shows it as ••••.") }
            } else null,
            // The label sits above the field, so a screen reader gets the name here.
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = block.label },
        )
    }
}

@Composable
private fun RevealButton(reveal: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
        Icon(
            if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = if (reveal) "Hide" else "Show",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PanelToggle(block: PanelBlock.Toggle, on: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(block.label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            if (block.detail.isNotBlank()) {
                Text(block.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = null, colors = panelSwitchColors())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelChoice(block: PanelBlock.Choice, value: String, motion: Boolean, onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        FieldLabel(block.label, block.required)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.options.forEach { option ->
                val picked = option == value
                val corner by animateDpAsState(
                    if (picked) 24.dp else 14.dp,
                    if (motion) spring(dampingRatio = 0.6f, stiffness = 400f) else snap(),
                    label = "chip",
                )
                val fill by animateColorAsState(
                    if (picked) scheme.secondaryContainer else Color.Transparent,
                    if (motion) tween(280) else snap(), label = "chipFill",
                )
                val shape = RoundedCornerShape(corner)
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .background(fill)
                        .border(1.dp, if (picked) Color.Transparent else scheme.outlineVariant, shape)
                        .selectable(selected = picked, role = Role.RadioButton, onClick = { onPick(option) })
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (picked) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        option,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (picked) scheme.onSecondaryContainer else scheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * A number on a track. Its value goes back with the form; a live slider also
 * runs its call when the user lets go, and springs back to the last value
 * that worked if the call fails. It holds still while any panel call runs,
 * so one release is one call.
 */
@Composable
private fun PanelSlider(
    block: PanelBlock.Slider,
    applied: PanelActionState?,
    busy: Boolean,
    onSet: (String) -> Unit,
    onRelease: (Double) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var position by remember(block) { mutableFloatStateOf((applied?.value?.toDoubleOrNull() ?: block.start).toFloat()) }
    val running = applied?.running == true
    // A failed live call leaves the device where it was, so the thumb goes back there too.
    LaunchedEffect(applied?.runs) {
        if (block.live && applied?.ok == false) {
            val back = applied.value?.toDoubleOrNull() ?: block.start
            position = back.toFloat()
            onSet(block.format(back))
        }
    }
    val shown = block.format(block.snap(position.toDouble()))
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FieldLabel(block.label, required = false) }
            if (running) {
                RunningRing(scheme.secondary, spin = true, size = 18.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(shown, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = {
                val snapped = block.snap(position.toDouble())
                position = snapped.toFloat()
                onSet(block.format(snapped))
                if (block.live) onRelease(snapped)
            },
            valueRange = block.min.toFloat()..block.max.toFloat(),
            // Ticks only where they can be told apart; a fine track snaps on release instead.
            steps = if (block.intervals in 2..20) block.intervals - 1 else 0,
            enabled = !busy,
            colors = SliderDefaults.colors(
                thumbColor = scheme.secondary,
                activeTrackColor = scheme.secondary,
                inactiveTrackColor = scheme.outlineVariant,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledThumbColor = scheme.onSurface.copy(alpha = 0.38f),
                disabledActiveTrackColor = scheme.onSurface.copy(alpha = 0.24f),
                disabledInactiveTrackColor = scheme.outlineVariant.copy(alpha = 0.5f),
                disabledActiveTickColor = Color.Transparent,
                disabledInactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = block.label
                    stateDescription = if (running) "$shown, working" else shown
                },
        )
        if (block.detail.isNotBlank()) {
            Text(block.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        block.callAt(block.snap(position.toDouble()))?.let { call ->
            PanelCallCaption("${call.caption} · $shown", scheme.onSurfaceVariant, Modifier.fillMaxWidth())
        }
        if (block.live && applied?.ok == false && applied.message.isNotBlank()) {
            Text(
                applied.message,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The app's own controls, live: what the user changes here takes effect at once, as in Settings. */
@Composable
private fun PanelControlBlock(control: PanelControl, state: AgentUiState, actions: AgentUiActions) {
    val scheme = MaterialTheme.colorScheme
    val heading = panelSettingsTitle(control) ?: ("Add a computer" to Icons.Outlined.Computer)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.38f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(heading.second, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(heading.first, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
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
    val scheme = MaterialTheme.colorScheme
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.secondary.copy(alpha = 0.12f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("$name is saved. Tap Done so Mike can connect.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        }
        return
    }
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus() })
    @Composable fun field(value: String, label: String, kind: KeyboardType, last: Boolean = false, hint: String = "", onChange: (String) -> Unit) =
        Column {
            FieldLabel(label, required = false)
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                placeholder = hint.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = panelFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = kind, imeAction = if (last) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = next,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    field(label, "Name", KeyboardType.Text, hint = "Home PC") { label = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { field(host, "Address", KeyboardType.Uri, hint = "192.168.1.20") { host = it.trim() } }
        Box(Modifier.width(92.dp)) { field(port, "Port", KeyboardType.Number) { port = it.filter(Char::isDigit).take(5) } }
    }
    field(user, "User name", KeyboardType.Text) { user = it.trim() }
    Column {
        FieldLabel("Password", required = false)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = panelFieldColors(),
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = next,
            trailingIcon = { RevealButton(reveal) { reveal = !reveal } },
            supportingText = { Text("Stays on this phone. Mike never sees it.") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Password" },
        )
    }
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
        shape = RoundedCornerShape(24.dp),
        colors = primaryPillColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Save computer", fontWeight = FontWeight.Medium) }
}

@Composable
private fun primaryPillColors() = MaterialTheme.colorScheme.run {
    ButtonDefaults.buttonColors(
        containerColor = primary,
        contentColor = onPrimary,
        disabledContainerColor = lerp(surface, outlineVariant, 0.55f),
        disabledContentColor = onSurfaceVariant.copy(alpha = 0.62f),
    )
}

private fun lerp(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelButtons(buttons: List<PanelButton>, missing: List<String>, onTap: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val blocked = missing.isNotEmpty()
    // Buttons sit at the end edge, the main one last, where the thumb is; cancel goes first and quietest.
    val ordered = buttons.sortedBy {
        when (it.style) {
            ButtonStyle.CANCEL -> 0
            ButtonStyle.SECONDARY -> 1
            ButtonStyle.DANGER -> 2
            ButtonStyle.PRIMARY -> 3
        }
    }
    val pill = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocked) {
            Text(
                panelMissingHint(missing),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ordered.forEach { button ->
                val needsInput = blocked && button.style != ButtonStyle.CANCEL
                val tall = Modifier.heightIn(min = 48.dp)
                when (button.style) {
                    ButtonStyle.PRIMARY -> Button(
                        onClick = { onTap(button.id) }, enabled = !needsInput, shape = pill, modifier = tall,
                        colors = primaryPillColors(),
                    ) { Text(button.label, fontWeight = FontWeight.Medium) }
                    ButtonStyle.SECONDARY -> OutlinedButton(
                        onClick = { onTap(button.id) }, enabled = !needsInput, shape = pill, modifier = tall,
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface),
                    ) { Text(button.label) }
                    ButtonStyle.DANGER -> Button(
                        onClick = { onTap(button.id) }, enabled = !needsInput, shape = pill, modifier = tall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.errorContainer, contentColor = scheme.error,
                            disabledContainerColor = lerp(scheme.surface, scheme.outlineVariant, 0.55f),
                            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.62f),
                        ),
                    ) { Text(button.label, fontWeight = FontWeight.Medium) }
                    ButtonStyle.CANCEL -> TextButton(
                        onClick = { onTap(button.id) }, shape = pill, modifier = tall,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                    ) { Text(button.label) }
                }
            }
        }
    }
}
