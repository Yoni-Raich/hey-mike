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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.androidagent.core.SetupImportance
import dev.androidagent.core.SetupState

// Small pieces the screen used to hand-roll in several places. Keeping one
// copy of each is what makes "green means done" mean the same thing
// everywhere in the app.

/**
 * The readiness marker. [pulsing] is for states that are actively changing, so
 * a connecting or preparing step does not read as finished.
 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp, pulsing: Boolean = false) {
    val alpha = if (pulsing && animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "status-dot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
            label = "status-dot-alpha",
        ).value
    } else {
        1f
    }
    Box(modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

/** Teal when done, white while working, red when the system blocked it, grey when untouched. */
@Composable
fun readinessColor(state: SetupState): Color = when (state) {
    SetupState.DONE -> MaterialTheme.colorScheme.secondary
    SetupState.WORKING -> MaterialTheme.colorScheme.primary
    SetupState.BLOCKED -> MaterialTheme.colorScheme.error
    SetupState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Spoken form of a readiness state, so the dot is not the only carrier of meaning. */
fun readinessWord(state: SetupState): String = when (state) {
    SetupState.DONE -> "Done"
    SetupState.WORKING -> "In progress"
    SetupState.BLOCKED -> "Needs attention"
    SetupState.PENDING -> "Not set up"
}

/**
 * Button content that swaps its icon for a spinner while it works. Every
 * button that can be busy used to spell this out itself, with different
 * spacing each time.
 */
@Composable
fun RowScope.LoadingButtonContent(
    loading: Boolean,
    icon: ImageVector,
    label: String,
    loadingLabel: String = label,
    spinnerColor: Color? = null,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = spinnerColor ?: MaterialTheme.colorScheme.primary,
        )
    } else {
        Icon(icon, contentDescription = null)
    }
    Spacer(Modifier.width(8.dp))
    Text(if (loading) loadingLabel else label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** One line of the settings hub: marker, what it is, where it stands, and a way in. */
@Composable
fun SettingsHubRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    state: SetupState? = null,
    importance: SetupImportance = SetupImportance.REQUIRED,
) {
    val spoken = state?.let { "${readinessWord(it)}. " }.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics { contentDescription = "$title. $spoken$summary" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state != null) {
            StatusDot(
                color = readinessColor(state),
                pulsing = state == SetupState.WORKING,
            )
        }
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == SetupState.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (importance != SetupImportance.REQUIRED && state != null && state != SetupState.DONE) {
            Text(
                "Optional",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
