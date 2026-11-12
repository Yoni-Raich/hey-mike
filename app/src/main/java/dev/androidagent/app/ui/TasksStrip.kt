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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import dev.androidagent.app.TaskDeskState
import dev.androidagent.core.MemberKind
import dev.androidagent.core.TaskCategory
import dev.androidagent.core.TaskStatus

private val StripTeal = Color(0xFF83D9CA)
private val StripAmber = Color(0xFFF6B86A)
private val StripBlocked = Color(0xFF241E14)
private val StripBlockedLine = Color(0xFF4A3A24)
private val StripLight = Color(0xFFECEBE6)
private val StripMuted = Color(0xFF8E8C85)

/** Whether the Scout is reading right now, for the eye and the strip's sentence. */
internal fun scoutBusy(state: AgentUiState): Boolean {
    val desk = state.taskDesk
    val scout = desk.members.firstOrNull { it.kind == MemberKind.SCOUT } ?: return false
    return desk.activity.containsKey(scout.id) || scout.sessionId?.let { state.runs[it]?.active } == true
}

/**
 * The board as a glance, at the top of the chat library.
 *
 * Like the standing-rules strip it may not grow: three chips at most and one
 * sentence, however many cards exist. It is one button, so a screen reader
 * hears one sentence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TasksStrip(state: AgentUiState, onOpen: () -> Unit) {
    val desk = state.taskDesk
    if (!desk.ready) return
    val tasks = desk.tasks
    val waiting = tasks.count { it.status == TaskStatus.PROPOSED && it.category == TaskCategory.TASK }
    val asking = tasks.filter { it.status == TaskStatus.WAITING }
    val running = tasks.filter { it.status == TaskStatus.RUNNING || it.status == TaskStatus.APPROVED }
    val check = tasks.filter { it.status == TaskStatus.REVIEW || (it.category == TaskCategory.REPORT && it.status == TaskStatus.PROPOSED) }
    val busy = scoutBusy(state)
    val mood = managerMood(desk, busy)
    val urgent = tasks.filter { it.status == TaskStatus.PROPOSED && it.category == TaskCategory.TASK }
        .minByOrNull { it.usefulUntil ?: Long.MAX_VALUE }
    val (sentence, tone) = when {
        asking.isNotEmpty() -> taskText("Mike is waiting for you", "מייק מחכה לך") to StripAmber
        !desk.settings.discoveryOn && tasks.none { !it.status.closed } -> taskText("Discovery is off", "חיפוש משימות ברקע כבוי") to StripAmber
        busy -> taskText("Mike is reading what came in…", "מייק עובר על מה שהגיע…") to StripTeal
        running.isNotEmpty() -> taskText("Mike is working on ${running.first().title}", "מייק עובד על ${running.first().title}") to StripTeal
        urgent != null -> taskText("Next: ${urgent.title}", "הבא בתור: ${urgent.title}") to StripLight
        check.isNotEmpty() -> taskText("Mike finished: ${check.first().title}", "מייק סיים: ${check.first().title}") to StripTeal
        else -> taskText("All quiet", "הכול שקט") to StripMuted
    }
    val chips = buildList {
        if (asking.isNotEmpty()) add(taskText("${asking.size} need your answer", "${asking.size} מחכות לתשובה") to StripAmber)
        if (waiting > 0) add(taskText("$waiting waiting", "$waiting חדשות") to StripAmber)
        if (running.isNotEmpty()) add(taskText("${running.size} running", "${running.size} בביצוע") to StripTeal)
        if (check.isNotEmpty()) add(taskText("${check.size} to check", "${check.size} לבדיקה") to StripLight)
        if (!desk.settings.discoveryOn && isEmpty()) add(taskText("Paused", "מושהה") to StripAmber)
    }.take(3)
    val blocked = asking.isNotEmpty()
    Surface(
        onClick = onOpen,
        color = if (blocked) StripBlocked else Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (blocked) StripBlockedLine else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = taskText("Tasks", "משימות") + ". " + chips.joinToString(", ") { it.first } + ". " + sentence
            role = Role.Button
            onClick(label = taskText("Open the board", "פתיחת לוח המשימות")) { onOpen(); true }
        },
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikeEye(mood, Modifier.size(30.dp), satellites = running.size)
                Text(taskText("Tasks", "משימות"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = StripLight)
                Icon(Icons.Outlined.ChevronRight, null,
                    modifier = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Modifier.scale(-1f, 1f) else Modifier,
                    tint = StripMuted)
            }
            if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (label, color) ->
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
                Text(sentence, style = MaterialTheme.typography.bodySmall, color = if (tone == StripLight) StripMuted else tone, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The Manager as one pinned chat in the library: its eye, its name, what waits. */
@Composable
internal fun ManagerLibraryRow(state: AgentUiState, selected: Boolean, onOpen: () -> Unit) {
    val desk = state.taskDesk
    val manager = desk.managerSessionId ?: return
    val mood = managerMood(desk, scoutBusy(state))
    val waiting = desk.tasks.count { it.status == TaskStatus.PROPOSED }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onOpen).heightIn(min = 56.dp).padding(horizontal = 12.dp)
            .semantics { contentDescription = taskText("Mike, Manager chat", "מייק, שיחת המנהל") },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MikeEye(mood, Modifier.size(32.dp), animate = false)
        Column(Modifier.weight(1f)) {
            Text(taskText("Mike · Manager", "מייק · המנהל"), style = MaterialTheme.typography.bodyLarge, color = StripLight)
            Text(if (waiting > 0) taskText("$waiting waiting for you", "$waiting משימות מחכות לך") else taskText("Talk about the board", "לדבר על המשימות"), style = MaterialTheme.typography.labelMedium,
                color = if (waiting > 0) StripAmber else StripMuted)
        }
        if (state.runs[manager]?.active == true) Box(Modifier.size(8.dp).clip(CircleShape).background(StripTeal))
    }
}

/**
 * The top of the Manager's chat, and of a member's: who you are talking to,
 * what it is doing, and the team as a row of faces. A faint aura takes the
 * eye's colour, the way voice mode tints the screen.
 */
@Composable
internal fun BoardChatPresence(state: AgentUiState, actions: AgentUiActions) {
    val desk = state.taskDesk
    val chat = state.activeSessionId ?: return
    val member = desk.members.firstOrNull { it.sessionId == chat } ?: return
    val manager = member.kind == MemberKind.MANAGER
    val busy = state.runs[chat]?.active == true
    val mood = if (manager) managerMood(desk, scoutBusy(state)) else memberMood(member, desk, busy)
    val aura = (if (mood == EyeMood.NEEDS) StripAmber else StripTeal).copy(alpha = if (mood == EyeMood.PAUSED) 0f else 0.09f)
    val waiting = desk.tasks.count { it.status == TaskStatus.PROPOSED && (manager || it.assignee == member.id) }
    val running = desk.tasks.count { it.status == TaskStatus.RUNNING && (manager || it.assignee == member.id) }
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(aura, Color.Transparent)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MikeEye(mood, Modifier.size(64.dp), tint = if (manager) null else memberColor(member.hue), satellites = running)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (manager) taskText("Mike · Manager", "מייק · המנהל") else member.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val line = when {
                    waiting > 0 -> taskText("$waiting waiting for your decision", "$waiting מחכות להחלטה שלך")
                    running > 0 -> taskText("Working on $running", "$running משימות בביצוע")
                    mood == EyeMood.THINKING -> taskText("Reading what came in", "עובר על מה שהגיע")
                    mood == EyeMood.PAUSED -> taskText("Discovery is off", "חיפוש משימות כבוי")
                    else -> member.role
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (waiting > 0) StripAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (manager) TeamFaces(state) { actions.onOpenTaskBoard("team") }
            }
            Surface(onClick = { actions.onOpenTaskBoard(if (manager) null else "member:${member.id}") }, shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = if (manager) "Open the board" else "Open ${member.name}'s page" }) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Checklist, null)
                    if (manager && waiting > 0) Box(Modifier.align(Alignment.TopEnd).offset((-6).dp, 6.dp).size(10.dp).clip(CircleShape).background(StripAmber))
                }
            }
        }
    }
}

/** The team as overlapping small faces, whoever needs you first. */
@Composable
private fun TeamFaces(state: AgentUiState, onOpen: () -> Unit) {
    val desk = state.taskDesk
    val members = desk.members.filter { it.kind != MemberKind.MANAGER }
    if (members.isEmpty()) return
    val ranked = members.map { it to memberMood(it, desk, desk.activity.containsKey(it.id) || it.sessionId?.let { s -> state.runs[s]?.active } == true) }
        .sortedBy { (_, mood) -> when (mood) { EyeMood.NEEDS -> 0; EyeMood.WORKING, EyeMood.THINKING -> 1; else -> 2 } }
    Row(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen).heightIn(min = 48.dp).padding(end = 6.dp)
        .semantics(mergeDescendants = true) { contentDescription = taskText("Mike's team, ${members.size} members", "הצוות של מייק, ${members.size} חברים") },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ranked.take(4).forEach { (member, mood) ->
            MikeEye(mood, Modifier.size(24.dp), tint = memberColor(member.hue), animate = false,
                description = member.name)
        }
        Text(taskText("Team", "הצוות"), Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
