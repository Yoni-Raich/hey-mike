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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The new-chat choice of where Mike works, in the side panel's own language:
// a serif question, one card of rows, a monogram for each place. It was a
// centered headline over stock filter chips of every width in a ragged flow,
// with the selected one in the system's green.

private val PlaceGround = Color(0xFF141413)
private val PlaceLine = Color(0xFF232220)
private val PlaceTile = Color(0xFF1F1E1C)
private val PlaceRaised = Color(0xFF252422)
private val PlaceLight = Color(0xFFECEBE6)
private val PlaceInk = Color(0xFF141413)
private val PlaceMuted = Color(0xFF8E8C85)
private val PlaceAmber = Color(0xFFE8C9A0)
private val PlaceTeal = Color(0xFF7FC8B6)

/** This phone, or a recent project on a computer, or a folder of the user's choosing. */
@Composable
internal fun WhereMikeWorks(state: AgentUiState, binding: dev.androidagent.remote.RemoteBinding?, actions: AgentUiActions) {
    val sections = PcChats.sections(
        state.computers, state.defaultComputerId, state.computerProjects, state.remoteBindings, state.sessions, state.pcThreads,
    )
    val recent = PcChats.recentProjects(sections)
    val labels = state.computers.associate { it.id to it.label }
    val target = state.defaultComputerId ?: state.computers.first().id
    val targetLabel = labels[target].orEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp)) {
        Text(
            "Where should Mike work?",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 30.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.4).sp,
        )
        Text(
            "You can change this until you send the first message.",
            Modifier.padding(top = 10.dp),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = PlaceMuted,
        )
        Surface(
            modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = PlaceGround,
            border = BorderStroke(1.dp, PlaceLine),
        ) {
            Column {
                PlaceRow(
                    title = "This phone",
                    subtitle = "Ask, plan, or act on your phone",
                    selected = binding == null,
                    choice = true,
                    onClick = { actions.onMoveNewChat(null, null) },
                ) { ink -> Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, Modifier.size(18.dp), tint = ink) }
                recent.forEach { project ->
                    HorizontalDivider(color = PlaceLine)
                    val computer = labels[project.computerId].orEmpty()
                    PlaceRow(
                        title = project.name,
                        subtitle = computer,
                        selected = binding != null && binding.computerId == project.computerId &&
                            PcChats.pathKey(binding.cwd) == PcChats.pathKey(project.path),
                        choice = true,
                        onClick = { actions.onMoveNewChat(project.computerId, project.path) },
                    ) { ink -> Text(monogram(computer), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ink) }
                }
                HorizontalDivider(color = PlaceLine)
                PlaceRow(
                    title = if (recent.isEmpty()) "Pick a folder" else "Another folder",
                    subtitle = if (recent.isEmpty()) "On $targetLabel" else "Pick one on $targetLabel",
                    selected = false,
                    choice = false,
                    accent = true,
                    onClick = { actions.onNewProject(target) },
                ) { ink -> Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, Modifier.size(18.dp), tint = ink) }
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, Modifier.size(14.dp), tint = PlaceMuted)
            Spacer(Modifier.width(8.dp))
            Text("On a computer, Mike can still use this phone.", fontSize = 12.sp, lineHeight = 16.sp, color = PlaceMuted)
        }
    }
}

/**
 * One place. The chosen one is lifted, its badge inverted and a check at the
 * end, the way the panel marks the open chat: color is kept for meaning.
 */
@Composable
private fun PlaceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    choice: Boolean,
    accent: Boolean = false,
    onClick: () -> Unit,
    badge: @Composable (ink: Color) -> Unit,
) {
    val badgeInk = if (selected) PlaceInk else if (accent) PlaceAmber else PlaceLight
    Surface(
        onClick = onClick,
        color = if (selected) PlaceRaised else Color.Transparent,
        contentColor = if (accent) PlaceAmber else PlaceLight,
        modifier = Modifier.fillMaxWidth().semantics { if (choice) { role = Role.RadioButton; this.selected = selected } },
    ) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(36.dp)
                    .then(if (accent) Modifier.border(1.dp, PlaceMuted, CircleShape) else Modifier.background(if (selected) PlaceLight else PlaceTile, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { badge(badgeInk) }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = if (selected || accent) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = PlaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", Modifier.size(22.dp), tint = PlaceTeal)
        }
    }
}
