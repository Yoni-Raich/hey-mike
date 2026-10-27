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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.CompactResult
import dev.androidagent.core.RunPhase
import dev.androidagent.core.TokenUsage
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** Tokens the way the CLI writes them: `911`, `7.0k`, `1.2m`. */
internal fun formatTokens(tokens: Long): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 1_000_000 -> String.format(Locale.ROOT, "%.1fk", tokens / 1_000.0)
    else -> String.format(Locale.ROOT, "%.1fm", tokens / 1_000_000.0)
}

/** How long something took, to the second: `12s`, `1m 05s`. */
internal fun elapsedLabel(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) / 1000).toInt()
    return if (seconds < 60) "${seconds}s" else String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60)
}

/** The line left in the chat once a compaction is done: what shrank, and how long it took. */
internal fun compactedNote(result: CompactResult?, elapsedMillis: Long): String {
    val parts = mutableListOf("Chat compacted")
    val before = result?.tokensBefore
    val after = result?.tokensAfter
    if (before != null && after != null && before > 0 && after <= before) {
        val saved = ((before - after) * 100.0 / before).roundToInt()
        parts += "${formatTokens(before)} → ${formatTokens(after)} tokens (−$saved%)"
    } else parts += "Token reduction not reported"
    parts += elapsedLabel(elapsedMillis)
    return parts.joinToString(" · ")
}

/**
 * Shown under the last line for as long as a compaction runs, so the chat is
 * never silent about it: a moving bar, the orb, and a clock.
 */
@Composable
internal fun CompactingCard(startedAt: Long, modifier: Modifier = Modifier) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgentOrb(Modifier.size(28.dp), phase = RunPhase.THINKING)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Compacting the chat…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Summarizing the conversation to free up context",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(elapsedLabel(now - startedAt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** How full the chat's context is, from the engine's own counts. */
internal data class ContextFill(val tokens: Long, val window: Long?) {
    val fraction: Float? get() = window?.takeIf { it > 0 }?.let { (tokens.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    val percent: Int? get() = fraction?.let { (it * 100).roundToInt() }
    /** `412.0k · 41%`, or only the tokens while the window is not known yet. */
    val label: String get() = formatTokens(tokens) + (percent?.let { " · $it%" } ?: "")
    val spoken: String get() = "Context: ${formatTokens(tokens)} tokens" +
        (window?.let { " of ${formatTokens(it)}" } ?: "") + (percent?.let { ", $it% full" } ?: "")
}

/** Null until the chat's first model call has reported its size. */
internal fun contextFill(usage: TokenUsage?): ContextFill? = usage?.context?.let { ContextFill(it, usage.contextWindow) }

/**
 * The chat's context size beside the composer. A long chat reads its whole
 * context on every call, so a full context spends the usage quota faster.
 */
@Composable
internal fun ContextChip(fill: ContextFill, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fraction = fill.fraction
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${fill.spoken}. Open context details" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (fraction != null) {
                drawArc(quotaColor(fraction), -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(fill.label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
    }
}

/**
 * The chip's details: the size and how full the window is, what it costs, and
 * the one thing to do about it. Compacting is offered while the chat is idle.
 */
@Composable
internal fun ContextMenu(
    fill: ContextFill,
    usage: TokenUsage?,
    canCompact: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCompact: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(min = 240.dp, max = 300.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Context", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatTokens(fill.tokens) + (fill.window?.let { " / ${formatTokens(it)}" } ?: ""),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                fill.percent?.let { Text("$it% full", fontSize = 13.sp, color = quotaColor(fill.fraction ?: 0f), fontWeight = FontWeight.Medium) }
            }
            fill.fraction?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = quotaColor(it),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Text(
                "Every reply reads the whole context again, so a fuller chat spends your usage faster.",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            usage?.takeIf { it.total > 0 }?.let {
                Text(
                    "Since the chat began: ${formatTokens(it.input)} read · ${formatTokens(it.output)} written · ${formatTokens(it.cachedInput)} from cache",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { onDismiss(); onCompact() }, enabled = canCompact, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Compress, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Compact")
            }
            if (!canCompact) {
                Text(
                    "Available between replies.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal data class ContextCategory(val name: String, val tokens: String, val percent: Double?)

/** What `/context` answers: how full the window is, and where it went. */
internal data class ContextUsage(
    val model: String?,
    val used: String,
    val window: String,
    val percent: Double,
    val categories: List<ContextCategory>,
)

private val CONTEXT_TOKENS = Regex("""\*\*Tokens:\*\*\s*(\S+)\s*/\s*(\S+)\s*\(([\d.]+)%\)""")
private val CONTEXT_MODEL = Regex("""\*\*Model:\*\*\s*(.+)""")

/**
 * Reads the CLI's own `/context` answer, or null for any other text. The
 * answer is markdown the CLI writes; only its first table is a list of parts.
 */
internal fun parseContextUsage(text: String): ContextUsage? {
    if (!text.trimStart().startsWith("## Context Usage")) return null
    val tokens = CONTEXT_TOKENS.find(text) ?: return null
    val percent = tokens.groupValues[3].toDoubleOrNull() ?: return null
    val model = CONTEXT_MODEL.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    val categories = mutableListOf<ContextCategory>()
    var inCategories = false
    for (line in text.lineSequence().map { it.trim() }) {
        if (line.startsWith("#")) {
            if (inCategories) break
            inCategories = line.contains("by category", ignoreCase = true)
            continue
        }
        if (!inCategories || !line.startsWith("|")) continue
        val cells = line.trim('|').split('|').map { it.trim() }
        if (cells.size < 3 || cells[0].equals("Category", ignoreCase = true) || cells[0].all { it == '-' || it == ':' }) continue
        categories += ContextCategory(cells[0], cells[1], cells[2].removeSuffix("%").toDoubleOrNull())
    }
    return ContextUsage(model, tokens.groupValues[1], tokens.groupValues[2], percent, categories)
}

private val ContextWarm = Color(0xFFF2B155)

/**
 * `/context` at a glance: the size of the window and how full it is. The parts
 * it is made of wait behind Details, since the whole table is rarely wanted.
 */
@Composable
internal fun ContextUsageCard(usage: ContextUsage, modifier: Modifier = Modifier) {
    var open by remember(usage) { mutableStateOf(false) }
    val tint = when {
        usage.percent >= 85 -> MaterialTheme.colorScheme.error
        usage.percent >= 60 -> ContextWarm
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Context window", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${usage.used} / ${usage.window}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${usage.percent.roundToInt()}% used", fontSize = 14.sp, color = tint, fontWeight = FontWeight.Medium)
            }
            LinearProgressIndicator(
                progress = { (usage.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = tint,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    usage.model.orEmpty(),
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (usage.categories.isNotEmpty()) {
                    TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
                }
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    usage.categories.forEach { category ->
                        val dim = category.name.startsWith("Free space") || category.name.startsWith("Autocompact")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                category.name,
                                Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(category.tokens, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                category.percent?.let { String.format(Locale.ROOT, "%.1f%%", it) }.orEmpty(),
                                Modifier.width(48.dp),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
