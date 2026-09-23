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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.core.TokenUsage
import dev.androidagent.core.UsageSummary
import dev.androidagent.core.UsageWindow

// Quota lived only as text inside settings, so the number that decides whether
// the next run will work at all was three taps away. This puts it in the top
// bar as one ring, and the breakdown one tap behind it.

private val QuotaFree = Color(0xFF22C55E)
private val QuotaWarn = Color(0xFFF59E0B)
private val QuotaFull = Color(0xFFEF4444)

/** Green while there is room, amber around half, red as the window fills. */
fun quotaColor(fraction: Float): Color {
    val value = fraction.coerceIn(0f, 1f)
    return if (value <= 0.5f) {
        lerp(QuotaFree, QuotaWarn, value / 0.5f)
    } else {
        lerp(QuotaWarn, QuotaFull, (value - 0.5f) / 0.5f)
    }
}

/**
 * The ring in the top bar. It shows the fullest quota window, because that is
 * the one that will stop the next run; tapping it opens the breakdown.
 */
@Composable
fun UsageMeter(
    windows: List<UsageWindow>,
    usage: TokenUsage?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val primary = remember(windows) { UsageSummary.primary(windows) }
    if (primary?.fraction == null && windows.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val fraction by animateFloatAsState(
        targetValue = primary?.fraction ?: 0f,
        animationSpec = tween(durationMillis = 600),
        label = "usage-meter",
    )
    val spoken = remember(primary) { UsageSummary.spoken(primary) }

    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { open = true }
                .semantics { contentDescription = "$spoken. Open usage details" },
            contentAlignment = Alignment.Center,
        ) {
            QuotaRing(fraction = fraction, known = primary?.fraction != null, diameter = 22.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Usage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (windows.isEmpty()) {
                    Text(
                        "Account quota is not available for this account yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                windows.forEach { window -> UsageWindowRow(window) }
                usage?.let {
                    Text(
                        "${it.total} tokens · ${it.input} in · ${it.output} out · ${it.cachedInput} cached",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRefresh() }, enabled = !refreshing) {
                    LoadingButtonContent(
                        loading = refreshing,
                        icon = Icons.Outlined.Refresh,
                        label = "Refresh usage",
                        loadingLabel = "Refreshing…",
                    )
                }
            }
        }
    }
}

/** One quota window as a labelled bar. Shared by the popup and the settings page. */
@Composable
fun UsageWindowRow(window: UsageWindow) {
    val fraction = window.fraction
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                window.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                fraction?.let { "${((1f - it) * 100).toInt()}% left" } ?: "Unavailable",
                style = MaterialTheme.typography.labelMedium,
                color = fraction?.let { quotaColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QuotaBar(fraction)
        window.resetText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuotaBar(fraction: Float?) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val value by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = 600),
        label = "usage-bar",
    )
    val color = quotaColor(value)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val radius = size.height / 2f
        drawRoundRect(
            color = track,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
        )
        if (fraction != null && value > 0f) {
            drawRoundRect(
                color = color,
                size = Size(width = size.width * value.coerceIn(0f, 1f), height = size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            )
        }
    }
}

@Composable
private fun QuotaRing(fraction: Float, known: Boolean, diameter: androidx.compose.ui.unit.Dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = if (known) quotaColor(fraction) else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(Modifier.size(diameter)) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        if (known) {
            drawArc(
                color = color,
                // From twelve o'clock, so a full ring reads as a full window.
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun UsageMeterSpacer() {
    Spacer(Modifier.width(4.dp))
}
