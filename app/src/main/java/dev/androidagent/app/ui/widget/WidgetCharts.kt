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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.sp
import dev.androidagent.core.widget.Block
import dev.androidagent.core.widget.ChartData
import dev.androidagent.core.widget.ChartKind
import dev.androidagent.core.widget.ScopedEnv
import dev.androidagent.core.widget.WidgetFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val CHART_MS = 420

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartBlock(block: Block.Chart, ctx: WidgetCtx) {
    val data = remember(block, ctx.state) { block.evaluate(ctx.env) }
    var kindName by rememberSaveable { mutableStateOf(block.kinds.first().name) }
    val kind = block.kinds.firstOrNull { it.name == kindName } ?: block.kinds.first()
    var picked by remember(data.labels.size) { mutableIntStateOf(-1) }
    val round = kind == ChartKind.PIE || kind == ChartKind.DONUT
    val shown = rememberAnimatedValues(data.series.map { s -> s.values.map { it.toFloat() } })
    val yMin = block.yMin?.eval(ctx.env)
    val yMax = block.yMax?.eval(ctx.env)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val title = block.title?.render(ctx.env).orEmpty()
        if (title.isNotBlank() || block.kinds.size > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (title.isNotBlank()) {
                    Text(
                        title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (block.kinds.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in block.kinds) {
                            FilterChip(
                                selected = option == kind,
                                onClick = { kindName = option.name },
                                label = { Text(ChartWords.kind(option, rtl), style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
        }
        if (data.series.isEmpty() || data.labels.isEmpty()) {
            Text(ChartWords.noData(rtl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (kind != ChartKind.SPARK) {
                // A minimum height, so touching a point does not push the chart down.
                Readout(data, picked, round, block, rtl, Modifier.fillMaxWidth().heightIn(min = 18.dp))
            }
            // Axes and bars are drawn left to right, as numbers are read, in any language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val summary = ChartWords.summary(kind, data.labels.size, data.series.joinToString { it.name.ifBlank { "values" } }, rtl)
                val modifier = Modifier.fillMaxWidth().semantics { contentDescription = summary }
                when (kind) {
                    ChartKind.BAR, ChartKind.LINE, ChartKind.AREA ->
                        CartesianChart(block, kind, data, shown, picked, { picked = it }, yMin, yMax, modifier.height(block.height.dp))
                    ChartKind.PIE, ChartKind.DONUT ->
                        PieChart(data, shown.firstOrNull().orEmpty(), kind == ChartKind.DONUT, block, picked, { picked = it }, rtl, modifier)
                    ChartKind.SPARK ->
                        Sparkline(data, shown, yMin, yMax, modifier.height(minOf(block.height, SPARK_HEIGHT).dp))
                }
            }
        }
    }
}

private const val SPARK_HEIGHT = 56

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the touched point says; with nothing touched, the key to the colors. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readout(data: ChartData, picked: Int, round: Boolean, block: Block.Chart, rtl: Boolean, modifier: Modifier) {
    if (picked !in data.labels.indices) {
        if (!round && data.series.size > 1) {
            FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in data.series) LegendItem(Color(s.color), s.name.ifBlank { "values" })
            }
            return
        }
        val idle = when {
            round -> ChartWords.total(rtl) + " " + measured(data.series.first().values.sumOf { max(it, 0.0) }, block.format, block.unit)
            else -> data.series[0].name
        }
        if (idle.isNotBlank()) {
            Text(
                idle,
                modifier,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(data.labels[picked], style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        if (round) {
            val values = data.series.first().values
            val total = values.sumOf { max(it, 0.0) }
            val value = values[picked]
            val percent = if (total > 0) (max(value, 0.0) / total * 100).roundToInt() else 0
            Text(
                "${measured(value, block.format, block.unit)} · $percent%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (s in data.series) {
                val value = s.values.getOrNull(picked) ?: continue
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(Color(s.color), CircleShape))
                    Text(
                        (if (s.name.isBlank()) "" else s.name + " ") + measured(value, block.format, block.unit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The values to draw: they glide from what was shown to the new numbers, so a
 * slider drag or a new range moves the chart instead of replacing it.
 */
@Composable
private fun rememberAnimatedValues(target: List<List<Float>>): List<List<Float>> {
    val progress = remember { Animatable(1f) }
    var from by remember { mutableStateOf(target.map { s -> s.map { 0f } }) }
    var to by remember { mutableStateOf(from) }
    LaunchedEffect(target) {
        from = blend(from, to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(CHART_MS))
    }
    return blend(from, to, progress.value)
}

private fun blend(from: List<List<Float>>, to: List<List<Float>>, t: Float): List<List<Float>> =
    to.mapIndexed { si, series ->
        series.mapIndexed { pi, end ->
            val start = from.getOrNull(si)?.getOrNull(pi) ?: 0f
            start + (end - start) * t
        }
    }

private class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

@Composable
private fun CartesianChart(
    block: Block.Chart,
    kind: ChartKind,
    data: ChartData,
    shown: List<List<Float>>,
    picked: Int,
    onPick: (Int) -> Unit,
    yMin: Double?,
    yMax: Double?,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val count = data.labels.size
    val stacked = block.stacked && kind == ChartKind.BAR

    val scale = remember(data, kind, yMin, yMax, stacked) {
        val all = data.series.flatMap { it.values }
        val tops = if (stacked) {
            List(count) { i -> data.series.sumOf { max(it.values.getOrElse(i) { 0.0 }, 0.0) } }
        } else all
        val lo = min(0.0, all.minOrNull() ?: 0.0)
        val hi = tops.maxOrNull() ?: 1.0
        val floor = when {
            yMin != null -> yMin
            kind == ChartKind.LINE && lo == 0.0 && (all.minOrNull() ?: 0.0) > 0.5 * hi -> all.minOrNull() ?: 0.0
            else -> lo
        }
        WidgetFormat.niceScale(floor, yMax ?: hi)
    }
    val tickLabels = remember(scale) { scale.ticks.map { WidgetFormat.format(it, if (scale.step < 1) "1" else "compact") } }
    val xEvery = remember(count) { max(1, ceil(count / 7.0).toInt()) }

    val latestPicked by rememberUpdatedState(picked)
    val latestLabels by rememberUpdatedState(tickLabels)
    val latestPick by rememberUpdatedState(onPick)
    Canvas(
        modifier
            .pointerInput(count) {
                detectTapGestures { offset ->
                    val inset = leftInset(latestLabels, measurer, labelStyle, density.density)
                    val index = nearestIndex(offset.x, size.width.toFloat(), count, inset, density.density)
                    latestPick(if (index == latestPicked) -1 else index)
                }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures { change, _ ->
                    val inset = leftInset(latestLabels, measurer, labelStyle, density.density)
                    latestPick(nearestIndex(change.position.x, size.width.toFloat(), count, inset, density.density))
                }
            },
    ) {
        val plot = plotArea(tickLabels, measurer, labelStyle, density.density)
        val span = (scale.max - scale.min).takeIf { it > 0 } ?: 1.0
        fun yOf(v: Double) = (plot.bottom - ((v - scale.min) / span).toFloat() * plot.height)
        val slot = plot.width / count

        // Gridlines and the left-hand numbers.
        scale.ticks.forEachIndexed { index, tick ->
            val y = yOf(tick)
            drawLine(grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
            val layout = measurer.measure(tickLabels[index], labelStyle)
            drawText(layout, color = ink, topLeft = Offset(plot.left - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f))
        }
        // The bottom labels, thinned so they never overlap.
        for (i in 0 until count step xEvery) {
            val layout = measurer.measure(data.labels[i], labelStyle.copy(color = ink), maxLines = 1, overflow = TextOverflow.Clip)
            val x = plot.left + slot * (i + 0.5f) - layout.size.width / 2f
            drawText(layout, topLeft = Offset(x.coerceIn(0f, size.width - layout.size.width), plot.bottom + 4.dp.toPx()))
        }
        if (picked in 0 until count) {
            val x = plot.left + slot * (picked + 0.5f)
            drawLine(ink, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1.dp.toPx())
        }

        when (kind) {
            ChartKind.BAR -> drawBars(data, shown, stacked, plot, slot, ::yOf, picked)
            else -> drawLines(data, shown, kind == ChartKind.AREA, plot, slot, ::yOf, picked)
        }
    }
}

private fun leftInset(tickLabels: List<String>, measurer: TextMeasurer, style: TextStyle, density: Float): Float =
    (tickLabels.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0) + 10 * density

private fun DrawScope.plotArea(
    tickLabels: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    density: Float,
): Plot {
    val labelHeight = measurer.measure("0", style).size.height
    return Plot(
        left = leftInset(tickLabels, measurer, style, density),
        top = 6 * density,
        right = size.width - 4 * density,
        bottom = size.height - labelHeight - 8 * density,
    )
}

private fun nearestIndex(x: Float, width: Float, count: Int, inset: Float, density: Float): Int {
    val slot = (width - 4 * density - inset) / count
    return ((x - inset) / slot).toInt().coerceIn(0, count - 1)
}

private fun DrawScope.drawBars(
    data: ChartData,
    shown: List<List<Float>>,
    stacked: Boolean,
    plot: Plot,
    slot: Float,
    yOf: (Double) -> Float,
    picked: Int,
) {
    val series = data.series.size
    val group = slot * 0.72f
    val barWidth = if (stacked) group else group / series
    val corner = min(4.dp.toPx(), barWidth / 2f)
    val base = yOf(0.0)
    for (i in data.labels.indices) {
        var stackTop = 0f
        for (si in 0 until series) {
            val value = shown.getOrNull(si)?.getOrNull(i) ?: 0f
            val x = plot.left + slot * i + (slot - group) / 2f + if (stacked) 0f else barWidth * si
            val color = Color(data.series[si].color).copy(alpha = if (picked < 0 || picked == i) 1f else 0.45f)
            val top = if (stacked) yOf((stackTop + max(value, 0f)).toDouble()) else yOf(value.toDouble())
            val bottom = if (stacked) yOf(stackTop.toDouble()) else base
            if (stacked) stackTop += max(value, 0f)
            val y = min(top, bottom)
            val h = abs(bottom - top)
            if (h > 0.5f) {
                drawRoundRect(
                    color,
                    topLeft = Offset(x + if (stacked) 0f else 1.dp.toPx(), y),
                    size = Size(max(barWidth - if (stacked) 0f else 2.dp.toPx(), 1f), h),
                    cornerRadius = CornerRadius(corner),
                )
            }
        }
    }
}

private fun DrawScope.drawLines(
    data: ChartData,
    shown: List<List<Float>>,
    area: Boolean,
    plot: Plot,
    slot: Float,
    yOf: (Double) -> Float,
    picked: Int,
) {
    val count = data.labels.size
    for (si in data.series.indices) {
        val color = Color(data.series[si].color)
        val values = shown.getOrNull(si) ?: continue
        val xs = FloatArray(count) { plot.left + slot * (it + 0.5f) }
        val ys = FloatArray(count) { yOf((values.getOrNull(it) ?: 0f).toDouble()) }
        val path = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until count) {
                // Level control points make a smooth curve that never overshoots a point.
                val mid = (xs[i - 1] + xs[i]) / 2f
                cubicTo(mid, ys[i - 1], mid, ys[i], xs[i], ys[i])
            }
        }
        if (area) {
            val fill = Path().apply {
                addPath(path)
                lineTo(xs[count - 1], plot.bottom)
                lineTo(xs[0], plot.bottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0f)), startY = plot.top, endY = plot.bottom))
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (count <= 31) {
            for (i in 0 until count) drawCircle(color, if (i == picked) 5.dp.toPx() else 2.5.dp.toPx(), Offset(xs[i], ys[i]))
        } else if (picked in 0 until count) {
            drawCircle(color, 5.dp.toPx(), Offset(xs[picked], ys[picked]))
        }
    }
}

@Composable
private fun Sparkline(data: ChartData, shown: List<List<Float>>, yMin: Double?, yMax: Double?, modifier: Modifier) {
    Canvas(modifier) {
        val all = data.series.flatMap { it.values }
        val lo = yMin ?: all.minOrNull() ?: 0.0
        val hi = (yMax ?: all.maxOrNull() ?: 1.0).let { if (it > lo) it else lo + 1.0 }
        val count = data.labels.size
        val pad = 4.dp.toPx()
        for (si in data.series.indices) {
            val values = shown.getOrNull(si) ?: continue
            val color = Color(data.series[si].color)
            val path = Path()
            for (i in 0 until count) {
                val x = if (count == 1) size.width / 2f else pad + (size.width - 2 * pad) * i / (count - 1)
                val y = size.height - pad - ((values.getOrElse(i) { 0f } - lo) / (hi - lo)).toFloat() * (size.height - 2 * pad)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieChart(
    data: ChartData,
    values: List<Float>,
    donut: Boolean,
    block: Block.Chart,
    picked: Int,
    onPick: (Int) -> Unit,
    rtl: Boolean,
    modifier: Modifier,
) {
    val series = data.series.first()
    val total = values.sumOf { max(it, 0f).toDouble() }.toFloat()
    val colors = remember(series) {
        List(data.labels.size) { i -> Color(series.pointColors?.getOrNull(i) ?: WidgetFormat.PALETTE[i % WidgetFormat.PALETTE.size]) }
    }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(CHART_MS * 2)) }
    val latestValues by rememberUpdatedState(values)
    val latestPicked by rememberUpdatedState(picked)
    val latestPick by rememberUpdatedState(onPick)
    BoxWithConstraints(modifier) {
        // Beside the pie when there is room for the names, under it when there is not.
        val stacked = maxWidth < PIE_STACK_BELOW
        val pieSize = minOf(block.height.dp, PIE_MAX, maxWidth)
        val pie: @Composable () -> Unit = {
            Canvas(
                Modifier
                    .size(pieSize)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val current = latestValues
                            val sum = current.sumOf { max(it, 0f).toDouble() }.toFloat()
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val radius = min(size.width, size.height) / 2f
                            if (dx * dx + dy * dy > radius * radius || sum <= 0f) {
                                latestPick(-1)
                                return@detectTapGestures
                            }
                            var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f
                            if (angle < 0) angle += 360f
                            var edge = 0f
                            var hit = -1
                            for (i in current.indices) {
                                edge += max(current[i], 0f) / sum * 360f
                                if (angle <= edge) {
                                    hit = i
                                    break
                                }
                            }
                            latestPick(if (hit == latestPicked) -1 else hit)
                        }
                    },
            ) {
                val diameter = min(size.width, size.height) - 12.dp.toPx()
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                if (total <= 0f) {
                    drawCircle(Color.Gray.copy(alpha = 0.2f), diameter / 2f, style = Stroke(if (donut) diameter * 0.2f else 2.dp.toPx()))
                    return@Canvas
                }
                var start = -90f
                for (i in values.indices) {
                    val sweep = max(values[i], 0f) / total * 360f * grow.value
                    val lift = if (i == picked) 6.dp.toPx() else 0f
                    val mid = Math.toRadians((start + sweep / 2f).toDouble())
                    val shift = Offset((cos(mid) * lift).toFloat(), (sin(mid) * lift).toFloat())
                    val gap = if (sweep > 2f && values.size > 1) 1.2f else 0f
                    if (donut) {
                        val thickness = diameter * 0.2f
                        drawArc(
                            colors[i], start + gap / 2f, max(sweep - gap, 0.1f), useCenter = false,
                            topLeft = topLeft + shift + Offset(thickness / 2f, thickness / 2f),
                            size = Size(diameter - thickness, diameter - thickness),
                            style = Stroke(thickness),
                        )
                    } else {
                        drawArc(colors[i], start + gap / 2f, max(sweep - gap, 0.1f), useCenter = true, topLeft = topLeft + shift, size = Size(diameter, diameter), style = Fill)
                    }
                    start += sweep
                }
            }
        }
        val legend: @Composable (Modifier) -> Unit = { legendModifier ->
            // Names are text, not a drawing: they read in the widget's own direction.
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                val share = { i: Int -> if (total > 0f) (max(values.getOrElse(i) { 0f }, 0f) / total * 100).roundToInt() else 0 }
                if (stacked) {
                    FlowRow(
                        legendModifier,
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (i in data.labels.indices) PieLegendItem(colors[i], data.labels[i].ifBlank { "${i + 1}" }, share(i), fill = false)
                    }
                } else {
                    Column(legendModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in data.labels.indices) {
                            PieLegendItem(colors[i], data.labels[i].ifBlank { "${i + 1}" }, share(i), fill = true)
                        }
                    }
                }
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pie()
                legend(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pie()
                legend(Modifier.weight(1f))
            }
        }
    }
}

/** A color dot, the name, and its share. With [fill] the share sits at the far end. */
@Composable
private fun PieLegendItem(color: Color, label: String, share: Int, fill: Boolean) {
    Row(if (fill) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(
            label,
            if (fill) Modifier.weight(1f) else Modifier,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$share%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val PIE_MAX: Dp = 150.dp
private val PIE_STACK_BELOW: Dp = 280.dp

@Composable
internal fun GaugeBlock(block: Block.Gauge, ctx: WidgetCtx) {
    val low = block.min.eval(ctx.env)
    val high = block.max.eval(ctx.env).let { if (it > low) it else low + 1.0 }
    val value = block.value.eval(ctx.env)
    val fraction by animateFloatAsState(((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat(), tween(CHART_MS + 200), label = "gauge")
    val color = block.color?.let(::Color) ?: Color(WidgetFormat.PALETTE[0])
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val label = block.label.render(ctx.env)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "$label ${measured(value, block.format, block.unit)}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(GAUGE_SIZE).height(GAUGE_SIZE * 0.82f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(GAUGE_SIZE)) {
                val stroke = 12.dp.toPx()
                val side = size.minDimension - stroke
                val topLeft = Offset((size.width - side) / 2f, stroke / 2f)
                drawArc(track, 150f, 240f, useCenter = false, topLeft = topLeft, size = Size(side, side), style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0.001f) {
                    drawArc(color, 150f, 240f * fraction, useCenter = false, topLeft = topLeft, size = Size(side, side), style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text(
                measured(value, block.format, block.unit),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium)
        block.caption?.render(ctx.env)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val GAUGE_SIZE: Dp = 124.dp

/**
 * A grid of dots whose size and brightness come from an expression, drawn per
 * frame when the expression reads the time `t`. Frames stop with the screen,
 * and stand still when the phone has animations turned off.
 */
@Composable
internal fun DotsBlock(block: Block.Dots, ctx: WidgetCtx) {
    val context = LocalContext.current
    val still = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var seconds by remember { mutableFloatStateOf(0f) }
    if (block.animated && !still) {
        LaunchedEffect(block) {
            val begin = withFrameNanos { it }
            var last = begin
            while (true) {
                val now = withFrameNanos { it }
                // About thirty frames a second is smooth for dots and spares the battery.
                if (now - last >= FRAME_NANOS) {
                    seconds = (now - begin) / 1_000_000_000f
                    last = now
                }
            }
        }
    }
    val base = ctx.env
    val color = Color(block.color ?: WidgetFormat.PALETTE[0])
    val title = block.title?.render(ctx.env).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(block.height.dp)
                    .semantics { contentDescription = title.ifBlank { "Animated dot pattern" } },
            ) {
                val cell = min(size.width / block.cols, size.height / block.rows)
                val left = (size.width - cell * block.cols) / 2f
                val top = (size.height - cell * block.rows) / 2f
                val scope = ScopedEnv(base)
                scope["t"] = seconds.toDouble()
                scope["cols"] = block.cols.toDouble()
                scope["rows"] = block.rows.toDouble()
                for (j in 0 until block.rows) {
                    for (i in 0 until block.cols) {
                        scope["i"] = i.toDouble()
                        scope["j"] = j.toDouble()
                        scope["x"] = if (block.cols > 1) i / (block.cols - 1.0) else 0.5
                        scope["y"] = if (block.rows > 1) j / (block.rows - 1.0) else 0.5
                        val level = block.intensity.number(scope).coerceIn(0.0, 1.0).toFloat()
                        val radius = cell / 2f * (0.18f + 0.82f * level) * 0.92f
                        val center = Offset(left + cell * (i + 0.5f), top + cell * (j + 0.5f))
                        val tint = color.copy(alpha = 0.22f + 0.78f * level)
                        if (block.square) {
                            drawRect(tint, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
                        } else {
                            drawCircle(tint, radius, center)
                        }
                    }
                }
            }
        }
    }
}

private const val FRAME_NANOS = 33_000_000L
