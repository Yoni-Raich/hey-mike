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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.MikeChatNote
import dev.androidagent.core.MikeTaskStatus

// A task leaves three kinds of text in a chat that nobody typed: the brief
// its own chat starts from, the line Mike's conversation keeps when it
// settles, and the result handed to Mike to talk about. They were shown as
// they are stored, so the conversation held a "user" bubble of JSON and
// instructions to the model. Here each is drawn as what it means.

private val NoteLine = Color(0xFF2A2A2A)
private val NoteFill = Color(0xFF141414)
private val NoteMuted = Color(0xFF8F8F8F)
private const val FOLDED_LINES = 4

@Composable
internal fun MikeChatNoteRow(row: MikeNoteRow, state: AgentUiState, actions: AgentUiActions) {
    when (val note = row.note) {
        is MikeChatNote.Brief ->
            if (row.repeated) CommandNote(if (note.nextStep.isBlank()) "Mike picked this up again" else "Picked up again: " + note.nextStep.take(140))
            else TaskBrief(note, row.key)
        is MikeChatNote.Handoff -> CommandNote("Mike is reading how “" + note.task.title + "” went")
        is MikeChatNote.Report -> {
            // The line names its task by title only; the newest task of that name is the one it was about.
            val chat = state.mike.tasks.filter { it.title == note.title }.maxByOrNull { it.updatedAt }?.sessionId
                ?.takeIf { id -> state.sessions.any { it.id == id } }
            TaskReport(note, row.key, onOpen = chat?.let { id -> { actions.onSelectSession(id) } })
        }
    }
}

/** What a task was asked, at the top of its own chat. */
@Composable
private fun TaskBrief(note: MikeChatNote.Brief, key: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.Transparent, border = BorderStroke(1.dp, NoteLine), modifier = Modifier.fillMaxWidth().testTag("mike-task-brief")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Task", NoteMuted)
            Folded(note.instruction, key, MaterialTheme.colorScheme.onSurface)
            if (note.nextStep.isNotBlank()) {
                Text("Next: " + note.nextStep, fontSize = 13.sp, lineHeight = 19.sp, color = NoteMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** How a task settled, in Mike's conversation: its name, where it stands, and the way to its chat. */
@Composable
private fun TaskReport(note: MikeChatNote.Report, key: String, onOpen: (() -> Unit)?) {
    val accent = when (note.status) {
        MikeTaskStatus.DONE, MikeTaskStatus.RUNNING, MikeTaskStatus.QUEUED -> RuleReady
        MikeTaskStatus.FAILED, MikeTaskStatus.UNKNOWN -> RuleBlocked
        else -> NoteMuted
    }
    Surface(shape = RoundedCornerShape(18.dp), color = NoteFill, border = BorderStroke(1.dp, NoteLine), modifier = Modifier.fillMaxWidth().testTag("mike-task-report")) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (onOpen != null) 4.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(accent, size = 7.dp)
                Caption("Task · " + note.status.word(), accent)
            }
            Text(note.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            if (note.detail.isNotBlank()) Folded(note.detail, key, MaterialTheme.colorScheme.onSurfaceVariant)
            onOpen?.let { open ->
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = open).heightIn(min = 44.dp).padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Open its chat", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = NoteMuted)
                }
            }
        }
    }
}

private fun MikeTaskStatus.word(): String = when (this) {
    MikeTaskStatus.DONE -> "done"
    MikeTaskStatus.WAITING -> "waiting"
    MikeTaskStatus.PAUSED -> "paused"
    MikeTaskStatus.FAILED -> "did not finish"
    MikeTaskStatus.UNKNOWN -> "interrupted"
    MikeTaskStatus.RUNNING -> "working"
    MikeTaskStatus.QUEUED -> "starting"
    MikeTaskStatus.READY -> "ready"
}

@Composable
private fun Caption(text: String, color: Color) {
    Text(text.uppercase(), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = color)
}

/** Long text folded to a few lines, opened by a tap; a result can run to pages. */
@Composable
private fun Folded(text: String, key: String, color: Color) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    var overflows by remember(key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SelectionContainer {
            Text(
                text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = color,
                maxLines = if (open) Int.MAX_VALUE else FOLDED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!open) overflows = it.hasVisualOverflow },
            )
        }
        if (overflows || open) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = !open }.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
                Text(if (open) "Show less" else "Show all", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NoteMuted)
            }
        }
    }
}
