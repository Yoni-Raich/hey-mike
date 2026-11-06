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

package dev.androidagent.app.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.core.widget.Block
import dev.androidagent.core.widget.ButtonAction
import dev.androidagent.core.widget.Stat
import dev.androidagent.core.widget.Tone
import dev.androidagent.core.widget.WidgetFormat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** The blocks of a widget, skipping the ones whose `when` is false. */
@Composable
internal fun WidgetBlocks(blocks: List<Block>, ctx: WidgetCtx) {
    for (block in blocks) {
        if (ctx.visible(block)) WidgetBlock(block, ctx)
    }
}

@Composable
internal fun WidgetBlock(block: Block, ctx: WidgetCtx) {
    when (block) {
        is Block.Heading -> Text(
            block.text.render(ctx.env),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
        )
        is Block.TextLine -> Text(
            block.text.render(ctx.env),
            style = MaterialTheme.typography.bodyMedium,
            color = toneColor(block.tone),
        )
        is Block.Stats -> StatsBlock(block, ctx)
        is Block.Slider -> SliderBlock(block, ctx)
        is Block.Choice -> ChoiceBlock(block, ctx)
        is Block.Toggle -> ToggleBlock(block, ctx)
        is Block.TextInput -> TextInputBlock(block, ctx)
        is Block.Chart -> ChartBlock(block, ctx)
        is Block.Gauge -> GaugeBlock(block, ctx)
        is Block.Progress -> ProgressBlock(block, ctx)
        is Block.Table -> TableBlock(block, ctx)
        is Block.Dots -> DotsBlock(block, ctx)
        is Block.Card -> CardBlock(block, ctx)
        is Block.Row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (child in block.children) {
                if (ctx.visible(child)) Box(Modifier.weight(1f)) { WidgetBlock(child, ctx) }
            }
        }
        is Block.Button -> ButtonBlock(block, ctx)
        is Block.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is Block.Unsupported -> Text(
            "Unsupported block: ${block.type.ifBlank { "?" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.NORMAL -> MaterialTheme.colorScheme.onSurface
    Tone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.GOOD -> GoodColor
    Tone.WARN -> WarnColor
    Tone.BAD -> BadColor
}

internal val GoodColor = Color(0xFF22C55E)
internal val WarnColor = Color(0xFFF97316)
internal val BadColor = Color(0xFFEF4444)

@Composable
private fun StatsBlock(block: Block.Stats, ctx: WidgetCtx) {
    if (block.items.isEmpty()) return
    val perRow = if (block.items.size == 4) 2 else minOf(3, block.items.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowItems in block.items.chunked(perRow)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (stat in rowItems) StatCard(stat, ctx, Modifier.weight(1f))
                // A short last row keeps the same card width as the rows above it.
                repeat(perRow - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCard(stat: Stat, ctx: WidgetCtx, modifier: Modifier) {
    val target = stat.value.eval(ctx.env)
    val shown by animateFloatAsState(target.toFloat(), tween(450), label = "stat")
    val accent = stat.color?.let(::Color) ?: MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                widgetIcon(stat.icon)?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp), tint = accent) }
                Text(
                    stat.label.render(ctx.env),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                measured(shown.toDouble(), stat.format, stat.unit),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (stat.color != null) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            stat.delta?.eval(ctx.env)?.let { delta ->
                val sign = if (delta > 0) "▲ " else if (delta < 0) "▼ " else ""
                Text(
                    sign + WidgetFormat.format(abs(delta), stat.format),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (delta > 0) GoodColor else if (delta < 0) BadColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            stat.caption?.render(ctx.env)?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SliderBlock(block: Block.Slider, ctx: WidgetCtx) {
    val low = block.min.eval(ctx.env)
    val high = block.max.eval(ctx.env).let { if (it > low) it else low + 1.0 }
    val current = ((ctx.state[block.id] as? Double) ?: low).coerceIn(low, high)
    val step = if (block.step > 0) block.step else if (high - low >= 20) 1.0 else 0.0
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(block.label.render(ctx.env), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                measured(current, block.format, block.unit),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // No tick marks: the value is snapped here, so the thumb steps without a dotted track.
        Slider(
            value = current.toFloat(),
            onValueChange = { raw ->
                val snapped = if (step > 0) low + ((raw - low) / step).roundToInt() * step else raw.toDouble()
                ctx.set(block.id, snapped.coerceIn(low, high))
            },
            valueRange = low.toFloat()..high.toFloat(),
        )
    }
}

@Composable
private fun ChoiceBlock(block: Block.Choice, ctx: WidgetCtx) {
    if (block.options.isEmpty()) return
    val current = ctx.state[block.id]?.let { WidgetFormat.plain(it) }
    val selected = block.options.firstOrNull { WidgetFormat.plain(it.value) == current } ?: block.options.first()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.label?.render(ctx.env)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (block.tabs) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in block.options) {
                    FilterChip(
                        selected = option === selected,
                        onClick = { ctx.set(block.id, option.value) },
                        label = { Text(option.label.render(ctx.env)) },
                    )
                }
            }
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(selected.label.render(ctx.env))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (option in block.options) {
                        DropdownMenuItem(
                            text = { Text(option.label.render(ctx.env)) },
                            onClick = {
                                ctx.set(block.id, option.value)
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleBlock(block: Block.Toggle, ctx: WidgetCtx) {
    val on = ((ctx.state[block.id] as? Double) ?: 0.0) != 0.0
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = { ctx.set(block.id, if (it) 1.0 else 0.0) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(block.label.render(ctx.env), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun TextInputBlock(block: Block.TextInput, ctx: WidgetCtx) {
    OutlinedTextField(
        value = ctx.state[block.id] as? String ?: "",
        onValueChange = { ctx.set(block.id, it.take(MAX_INPUT_CHARS)) },
        modifier = Modifier.fillMaxWidth(),
        label = block.label.render(ctx.env).takeIf { it.isNotBlank() }?.let { text -> { Text(text) } },
        placeholder = block.hint.takeIf { it.isNotBlank() }?.let { text -> { Text(text) } },
        singleLine = true,
    )
}

private const val MAX_INPUT_CHARS = 500

@Composable
private fun ProgressBlock(block: Block.Progress, ctx: WidgetCtx) {
    val value = block.value.eval(ctx.env)
    val limit = block.max.eval(ctx.env).takeIf { it > 0 } ?: 100.0
    val fraction by animateFloatAsState((value / limit).coerceIn(0.0, 1.0).toFloat(), tween(450), label = "progress")
    val color = block.color?.let(::Color) ?: MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(block.label.render(ctx.env), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                measured(value, block.format, block.unit),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun TableBlock(block: Block.Table, ctx: WidgetCtx) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
    ) {
        if (block.columns.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                for (column in block.columns) {
                    Text(
                        column.render(ctx.env),
                        Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        block.rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth()) {
                for (cell in row) {
                    Text(
                        cell.render(ctx.env),
                        Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (index < block.rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        block.caption?.render(ctx.env)?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CardBlock(block: Block.Card, ctx: WidgetCtx) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val title = block.title?.render(ctx.env).orEmpty()
            val subtitle = block.subtitle?.render(ctx.env).orEmpty()
            if (title.isNotBlank() || subtitle.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (title.isNotBlank()) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            WidgetBlocks(block.children, ctx)
        }
    }
}

@Composable
private fun ButtonBlock(block: Block.Button, ctx: WidgetCtx) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) {
        if (done) {
            delay(CONFIRM_MS)
            done = false
        }
    }
    val label = block.label.render(ctx.env)
    val icon = if (done) Icons.Filled.Check else if (block.action == ButtonAction.COPY) Icons.Filled.ContentCopy else Icons.AutoMirrored.Filled.Send
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 2)
    }
    when (block.action) {
        ButtonAction.COPY -> OutlinedButton(
            onClick = {
                ctx.copy(ctx.snapshot())
                done = true
            },
        ) { content() }
        ButtonAction.ASK -> FilledTonalButton(
            enabled = ctx.ask != null,
            onClick = {
                ctx.ask?.invoke(askText(block, ctx))
                done = true
            },
        ) { content() }
    }
}

private const val CONFIRM_MS = 1_800L

/** What an "ask" button sends: its words, then the widget's numbers as JSON. */
private fun askText(block: Block.Button, ctx: WidgetCtx): String {
    val lead = (block.prompt ?: block.label).render(ctx.env).trim()
    return lead + "\n\n```json\n" + ctx.snapshot() + "\n```"
}
