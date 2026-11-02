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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.androidagent.app.ui.containsRtl
import dev.androidagent.core.widget.Block
import dev.androidagent.core.widget.StateEnv
import dev.androidagent.core.widget.WidgetEnv
import dev.androidagent.core.widget.WidgetSpec

/**
 * What a widget's blocks read and change. A new one is made at every change of
 * the widget's state, so a block that reads it redraws with the new numbers.
 */
internal class WidgetCtx(
    val spec: WidgetSpec,
    val state: Map<String, Any>,
    val set: (String, Any) -> Unit,
    val ask: ((String) -> Unit)?,
    val copy: (String) -> Unit,
) {
    val env: WidgetEnv = StateEnv(state)

    fun snapshot(): String = spec.snapshotText(env, state)

    fun visible(block: Block): Boolean = block.visible?.truthy(env) != false
}

/**
 * A live widget: the model's `mike-widget` block drawn natively, with its
 * inputs, charts and buttons working on the phone and no network.
 *
 * [stateKey] ties the widget's inputs to its place in the chat, so they
 * survive scrolling away and a rotation. [onAsk] sends text back to the chat
 * as the user's next message; null disables the "ask" buttons.
 */
@Composable
internal fun WidgetView(
    spec: WidgetSpec,
    stateKey: String,
    onAsk: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var state by rememberSaveable(stateKey) { mutableStateOf<Map<String, Any>>(LinkedHashMap(spec.initialState())) }
    val clipboard = LocalClipboardManager.current
    val ctx = remember(spec, state, onAsk) {
        WidgetCtx(
            spec = spec,
            state = state,
            set = { id, value -> state = LinkedHashMap(state).also { it[id] = value } },
            ask = onAsk,
            copy = { clipboard.setText(AnnotatedString(it)) },
        )
    }
    val rtl = spec.rtl ?: remember(spec, state) { containsRtl(widgetSample(ctx)) }
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val title = spec.title?.render(ctx.env).orEmpty()
                val subtitle = spec.subtitle?.render(ctx.env).orEmpty()
                if (title.isNotBlank() || subtitle.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (title.isNotBlank()) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                WidgetBlocks(spec.body, ctx)
            }
        }
    }
}

/** A few of the widget's words, to decide its direction when it does not say. */
private fun widgetSample(ctx: WidgetCtx): String {
    val out = StringBuilder()
    ctx.spec.title?.render(ctx.env)?.let(out::append)
    ctx.spec.subtitle?.render(ctx.env)?.let(out::append)
    for (block in ctx.spec.allBlocks()) {
        if (out.length > 400) break
        when (block) {
            is Block.Heading -> out.append(block.text.render(ctx.env))
            is Block.TextLine -> out.append(block.text.render(ctx.env))
            is Block.Card -> block.title?.render(ctx.env)?.let(out::append)
            is Block.Slider -> out.append(block.label.render(ctx.env))
            is Block.Toggle -> out.append(block.label.render(ctx.env))
            is Block.Button -> out.append(block.label.render(ctx.env))
            is Block.Stats -> block.items.forEach { out.append(it.label.render(ctx.env)) }
            else -> Unit
        }
    }
    return out.toString()
}

/** The placeholder shown while the model is still writing a widget. */
@Composable
internal fun WidgetSkeleton(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "widget-skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Building an interactive widget" },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Building widget…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { SkeletonBar(Modifier.weight(1f).height(54.dp), pulse) }
            }
            SkeletonBar(Modifier.fillMaxWidth().height(120.dp), pulse)
            SkeletonBar(Modifier.fillMaxWidth(0.6f).height(14.dp), pulse)
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier, alpha: Float) {
    Surface(
        modifier = modifier.alpha(alpha),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        content = {},
    )
}

/** A widget block that could not be drawn is shown as the code it is, with the reason. */
@Composable
internal fun WidgetBroken(source: String, reason: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "This widget could not be drawn: $reason",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                source.take(MAX_BROKEN_CHARS),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private const val MAX_BROKEN_CHARS = 4_000
