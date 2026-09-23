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

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.SecretRedactor

private val GroupBorder = Color(0xFF262626)
private val StepInk = Color(0xFF8F8F8F)
private val StepLine = Color(0xFF2E2E2E)

/**
 * A run of device actions as one row: "5 actions on your phone". It opens to
 * the steps, and each step opens to what the tool returned. While the run is
 * still acting it is open and says so; after that it is closed until tapped.
 */
@Composable
internal fun DeviceActionsRow(row: ActionsRow) {
    // Null until the user taps: then their choice wins over the live default.
    var choice by rememberSaveable(row.key) { mutableStateOf<Boolean?>(null) }
    val expanded = choice ?: row.live
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(240), label = "actions-chevron")
    val label = actionsLabel(row.steps.size, row.live)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, GroupBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize(tween(240))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { choice = !expanded }
                    .heightIn(min = 44.dp)
                    .padding(start = 14.dp, end = 12.dp)
                    .semantics {
                        contentDescription = if (expanded) "$label. Hide steps" else "$label. Show steps"
                        if (row.live) liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (row.live) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        StatusDot(color = MaterialTheme.colorScheme.secondary, size = 8.dp, pulsing = true)
                    }
                } else {
                    Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp), tint = StepInk)
                }
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(chevron), tint = StepInk)
            }
            if (expanded) {
                Column(Modifier.padding(start = 22.dp, end = 14.dp, bottom = 10.dp)) {
                    row.steps.forEach { step -> ActionStep(step) }
                }
            }
        }
    }
}

@Composable
private fun ActionStep(step: ChatMessage) {
    val detail = remember(step.text) { SecretRedactor.redact(step.text.substringAfter(':', "").trim()) }
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val canOpen = detail.isNotBlank() || step.attachmentPaths.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(StepLine, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .padding(start = 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (canOpen) Modifier.clickable { open = !open } else Modifier)
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(toolStepLabel(toolNameOf(step)), fontSize = 13.sp, lineHeight = 18.sp, color = StepInk)
        }
        if (open) {
            if (detail.isNotBlank()) {
                SelectionContainer {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            InlineImages(step.attachmentPaths)
        }
    }
}
