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

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.MikeMemory
import dev.androidagent.core.MikeTask
import dev.androidagent.core.MikeTaskStatus
import dev.androidagent.core.RunPhase
import java.util.UUID

// The neutral ground the status sheet uses: a dark sheet, tiles one step
// lighter. Teal means Mike is working, amber means he needs the person, and
// nothing else is coloured.
internal val MikeSheetFill = Color(0xFF1B1B1B)
private val MikeTile = Color(0xFF262626)
private val MikeTileRaised = Color(0xFF363636)
private val MikeLine = Color(0xFF333333)
private val MikeInk = Color(0xFFE6E6E6)
private val MikeMuted = Color(0xFF8F8F8F)
private val MikeFaint = Color(0xFF6B6B6B)
private val MikeNeedsFill = Color(0xFF241E14)
private val MikeNeedsLine = Color(0xFF4A3A24)

private const val NEW_MEMORY = "memory/new"
private const val MEMORY = "memory:"
private const val NEW_TASK = "task/new"
private const val TASK = "task:"

/** Finished tasks shown before "Show all": the list is for what is still open. */
private const val DONE_SHOWN = 3
/** How much of the screen the sheet's content takes; the drag handle sits above it. */
private const val SHEET_HEIGHT = 0.88f
private const val MEMORY_LIMIT = 1200
private const val TITLE_LIMIT = 100
private const val INSTRUCTION_LIMIT = 8000

/**
 * What Mike remembers and what he is working on.
 *
 * Two levels in one sheet, as Settings and the rules sheet do it: the two
 * lists, and one page for a memory or a task. Editing happens on a page, not
 * in a dialog over the sheet, so there is room for the keyboard and for the
 * reason a save was refused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MikeStateSheet(state: AgentUiState, actions: AgentUiActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onCloseMikeState,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Back walks the page first; the sheet's own dispatcher would close it all.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MikeSheetFill,
        // Named, because the fill is not in the colour scheme and Compose then
        // cannot tell which text colour goes on it: the title came out black.
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("mike-state-sheet"),
    ) {
        // The height belongs to the content. A height on the sheet itself is
        // also the height its anchors are measured in, which pinned it to the
        // top of the screen, under the status bar, with the chat showing below.
        MikePages(state, actions, Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT).navigationBarsPadding())
    }
}

/** The sheet's content, apart from its window so it can be shown and checked on its own. */
@Composable
internal fun MikePages(state: AgentUiState, actions: AgentUiActions, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(state.mikePanel ?: MikePanel.MEMORY) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val mike = state.mike
    fun memoryOf(target: String?) = target?.takeIf { it.startsWith(MEMORY) }?.removePrefix(MEMORY)?.let { key -> mike.memories.firstOrNull { it.key == key } }
    fun taskOf(target: String?) = target?.takeIf { it.startsWith(TASK) }?.removePrefix(TASK)?.let { id -> mike.tasks.firstOrNull { it.id == id } }
    // Something forgotten or removed from under its page leaves nothing to show.
    val page = route?.takeIf { it == NEW_MEMORY || it == NEW_TASK || memoryOf(it) != null || taskOf(it) != null }
    val back: () -> Unit = { if (page != null) route = null else actions.onCloseMikeState() }
    val animate = animationsEnabled()
    val now = remember(mike.revision, page, tab) { System.currentTimeMillis() }
    val working = mike.tasks.any { it.group() == MikeTaskGroup.WORKING }

    BackHandler { back() }
    Column(modifier.testTag("mike-pages").padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = if (page == null) "Close Mike" else "Back to Mike")
            }
            Text(
                when {
                    page == NEW_MEMORY -> "New memory"
                    page == NEW_TASK -> "New task"
                    memoryOf(page) != null -> "Memory"
                    else -> taskOf(page)?.title ?: "Mike"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (page == null) {
                AgentOrb(
                    Modifier.padding(end = 4.dp).size(40.dp),
                    phase = if (working) RunPhase.THINKING else RunPhase.IDLE,
                    idleColor = MikeInk,
                )
            }
        }
        state.mikeError?.let { Notice(it) }
        state.mikeActionError?.let { Notice(it) }

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (!animate) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else if (targetState == null) {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                }
            },
            label = "mike-route",
            modifier = Modifier.weight(1f),
        ) { target ->
            val memory = memoryOf(target)
            val task = taskOf(target)
            when {
                target == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelSwitch(
                        tab = tab,
                        memories = mike.memories.size,
                        tasks = mike.tasks.count { it.group() != MikeTaskGroup.DONE },
                        attention = mikeGlance(mike).attention,
                        onChoose = { tab = it },
                    )
                    Crossfade(tab, animationSpec = tween(if (animate) 160 else 0), label = "mike-tab", modifier = Modifier.weight(1f)) { shown ->
                        when (shown) {
                            MikePanel.MEMORY -> MemoryList(state, now, onAdd = { route = NEW_MEMORY }, onOpen = { route = MEMORY + it.key })
                            MikePanel.TASKS -> TaskList(state, actions, now, onAdd = { route = NEW_TASK }, onOpen = { route = TASK + it.id })
                        }
                    }
                }
                target == NEW_MEMORY || memory != null -> MemoryPage(
                    original = memory,
                    source = memory?.let { memorySource(it, state.sessions) + " · " + ago(it.updatedAt, now) },
                    onSave = { text, kind ->
                        // A new memory has no key of its own; a correction keeps the one it had.
                        actions.onSaveMikeMemory(memory?.key ?: ("note." + UUID.randomUUID()), text, kind, memory?.revision ?: 0)
                        route = null
                    },
                    onForget = { memory?.let { actions.onForgetMikeMemory(it.key, it.revision) }; route = null },
                    onCancel = { route = null },
                )
                target == NEW_TASK -> NewTaskPage(
                    onCreate = { title, instruction, start ->
                        actions.onCreateMikeTask(title, instruction, start)
                        tab = MikePanel.TASKS
                        route = null
                    },
                    onCancel = { route = null },
                )
                task != null -> TaskPage(
                    task = task,
                    // While the list is still loading, a chat not in it is not a chat that is gone.
                    chatExists = state.isLoadingSessions || state.sessions.any { it.id == task.sessionId },
                    held = mike.paused,
                    now = now,
                    actions = actions,
                    onOpenChat = { actions.onCloseMikeState(); actions.onSelectSession(task.sessionId) },
                    onRemoved = { route = null },
                )
                // The page's subject went away mid-transition.
                else -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

/** Memory or Tasks: a two-part switch with each side's count, and a dot when a task needs the person. */
@Composable
private fun PanelSwitch(tab: MikePanel, memories: Int, tasks: Int, attention: Boolean, onChoose: (MikePanel) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MikeTile, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Segment("Memory", memories, tab == MikePanel.MEMORY, alert = false, Modifier.weight(1f).testTag("mike-tab-memory")) { onChoose(MikePanel.MEMORY) }
            Segment("Tasks", tasks, tab == MikePanel.TASKS, alert = attention, Modifier.weight(1f).testTag("mike-tab-tasks")) { onChoose(MikePanel.TASKS) }
        }
    }
}

@Composable
private fun Segment(label: String, count: Int, selected: Boolean, alert: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) MikeTileRaised else Color.Transparent, label = "mike-segment")
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = fill,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MikeMuted,
        modifier = modifier.semantics { role = Role.Tab },
    ) {
        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            if (count > 0) {
                Spacer(Modifier.width(8.dp))
                Text("$count", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MikeMuted)
            }
            if (alert) {
                Spacer(Modifier.width(7.dp))
                StatusDot(RuleBlocked, size = 6.dp)
            }
        }
    }
}

@Composable
private fun MemoryList(state: AgentUiState, now: Long, onAdd: () -> Unit, onOpen: (MikeMemory) -> Unit) {
    val memories = state.mike.memories
    val groups = remember(memories) {
        MikeMemoryKind.entries.map { kind -> kind to memories.filter { MikeMemoryKind.of(it.kind) == kind }.sortedByDescending { it.updatedAt } }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        item(key = "add") { AddTile("Tell Mike something to remember", Modifier.testTag("mike-add-memory"), onAdd) }
        if (memories.isEmpty()) item(key = "empty") {
            Empty(
                Icons.Outlined.Lightbulb, "Nothing saved yet",
                "Say “remember that…” in any chat, or add it here. Mike reads what is saved at the start of every chat.",
            )
        }
        groups.forEach { (kind, group) ->
            if (group.isEmpty()) return@forEach
            item(key = "head-${kind.id}") { GroupHeading(kind.title, MikeMuted, group.size) }
            items(group, key = { "memory-" + it.key }) { memory ->
                MemoryCard(memory, memorySource(memory, state.sessions) + " · " + ago(memory.updatedAt, now)) { onOpen(memory) }
            }
        }
        if (memories.isNotEmpty()) item(key = "note") {
            Text(
                "Mike reads these at the start of every chat, on this phone and on your computers. He also saves on his own as you talk; anything he wrote says where.",
                style = MaterialTheme.typography.bodySmall,
                color = MikeFaint,
                modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
private fun MemoryCard(memory: MikeMemory, source: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MikeTile, modifier = Modifier.fillMaxWidth().testTag("mike-memory-" + memory.key)) {
        Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(memory.text, style = MaterialTheme.typography.bodyMedium, color = MikeInk, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text(source, fontSize = 12.sp, lineHeight = 16.sp, color = MikeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // The whole card opens it; the pencil only says so.
            Icon(Icons.Outlined.Edit, contentDescription = "Edit", modifier = Modifier.padding(top = 2.dp).size(16.dp), tint = MikeFaint)
        }
    }
}

/** A memory's own page: its words, its kind, and the way to forget it. */
@Composable
private fun MemoryPage(
    original: MikeMemory?,
    source: String?,
    onSave: (text: String, kind: String) -> Unit,
    onForget: () -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable(original?.key) { mutableStateOf(original?.text.orEmpty()) }
    var kind by rememberSaveable(original?.key) { mutableStateOf(MikeMemoryKind.of(original?.kind ?: "fact")) }
    var confirmForget by rememberSaveable(original?.key) { mutableStateOf(false) }
    val tooLong = text.length > MEMORY_LIMIT
    val changed = original == null || text.trim() != original.text || kind.id != original.kind

    Page {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What should Mike remember?") },
            isError = tooLong,
            supportingText = { Text("${text.length} / $MEMORY_LIMIT") },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth().testTag("mike-memory-text"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MikeMemoryKind.entries.forEach { option ->
                FilterChip(
                    selected = kind == option,
                    onClick = { kind = option },
                    label = { Text(option.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MikeTileRaised,
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        labelColor = MikeMuted,
                    ),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = kind == option, borderColor = MikeLine),
                )
            }
        }
        Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MikeMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            Button(
                onClick = { onSave(text.trim(), kind.id) },
                enabled = text.isNotBlank() && !tooLong && changed,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Save") }
        }
        if (original != null) {
            HorizontalDivider(color = MikeLine)
            source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MikeMuted) }
            OutlinedButton(
                onClick = { confirmForget = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Forget this")
            }
        }
    }
    if (confirmForget && original != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this?") },
            text = { Text("Mike stops using it in every chat. It cannot be brought back.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmForget = false; onForget() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun TaskList(state: AgentUiState, actions: AgentUiActions, now: Long, onAdd: () -> Unit, onOpen: (MikeTask) -> Unit) {
    val tasks = state.mike.tasks
    var allDone by rememberSaveable { mutableStateOf(false) }
    val groups = remember(tasks) {
        MikeTaskGroup.entries.map { group -> group to tasks.filter { it.group() == group }.sortedByDescending { it.updatedAt } }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        // Stop holds every task, and until now only a line of small print said so.
        if (state.mike.paused) item(key = "held") { HeldCard(actions.onResumeMike) }
        item(key = "add") { AddTile("Give Mike a task", Modifier.testTag("mike-add-task"), onAdd) }
        if (tasks.isEmpty()) item(key = "empty") {
            Empty(
                Icons.Outlined.TaskAlt, "No tasks yet",
                "Hand Mike something that takes a while. He works on it in a chat of its own, can wait and come back to it, and tells you here how it went.",
            )
        }
        groups.forEach { (group, all) ->
            if (all.isEmpty()) return@forEach
            val folded = group == MikeTaskGroup.DONE && !allDone && all.size > DONE_SHOWN
            item(key = "head-${group.name}") { GroupHeading(group.title, group.accent(), all.size) }
            items(if (folded) all.take(DONE_SHOWN) else all, key = { "task-" + it.id }) { task ->
                TaskCard(
                    task = task,
                    now = now,
                    chatExists = state.isLoadingSessions || state.sessions.any { it.id == task.sessionId },
                    onOpen = { onOpen(task) },
                    onRun = { actions.onRunMikeTask(task.id, false) },
                    onPause = { actions.onPauseMikeTask(task.id) },
                )
            }
            if (group == MikeTaskGroup.DONE && all.size > DONE_SHOWN) item(key = "done-more") {
                TextButton(onClick = { allDone = !allDone }) { Text(if (allDone) "Show fewer" else "Show all ${all.size}", color = MikeMuted) }
            }
        }
    }
}

private fun MikeTaskGroup.accent(): Color = when (this) {
    MikeTaskGroup.NEEDS_YOU -> RuleBlocked
    MikeTaskGroup.WORKING -> RuleReady
    else -> MikeMuted
}

@Composable
private fun TaskCard(task: MikeTask, now: Long, chatExists: Boolean, onOpen: () -> Unit, onRun: () -> Unit, onPause: () -> Unit) {
    val group = task.group()
    val needsYou = group == MikeTaskGroup.NEEDS_YOU
    val snippet = task.snippet()
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = if (needsYou) MikeNeedsFill else MikeTile,
        border = if (needsYou) BorderStroke(1.dp, MikeNeedsLine) else null,
        modifier = Modifier.fillMaxWidth().testTag("mike-task-" + task.id),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 6.dp)) {
                    StatusDot(if (group == MikeTaskGroup.DONE) MikeFaint else group.accent(), size = 8.dp, pulsing = group == MikeTaskGroup.WORKING)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MikeInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (chatExists) task.statusLine(now) else "Its chat was deleted",
                        fontSize = 12.sp, lineHeight = 16.sp,
                        color = when (group) {
                            MikeTaskGroup.NEEDS_YOU -> RuleBlocked
                            MikeTaskGroup.WORKING -> RuleReady
                            else -> MikeMuted
                        },
                    )
                    if (snippet.isNotBlank()) {
                        Text(snippet, fontSize = 12.sp, lineHeight = 17.sp, color = MikeFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            // One tap for the step that is safe to take from a list. A task that
            // was interrupted has to be read first, so it only opens.
            when {
                !chatExists -> Chevron()
                group == MikeTaskGroup.READY -> FilledTonalIconButton(
                    onClick = onRun,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MikeTileRaised, contentColor = MikeInk),
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Outlined.PlayArrow, contentDescription = task.runLabel() + ": " + task.title, modifier = Modifier.size(20.dp)) }
                group == MikeTaskGroup.WORKING -> IconButton(onClick = onPause, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Pause, contentDescription = "Pause: " + task.title, modifier = Modifier.size(20.dp), tint = MikeMuted)
                }
                else -> Chevron()
            }
        }
    }
}

@Composable
private fun Chevron() {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MikeFaint)
    }
}

/**
 * One task: where it stands, what it was asked, what came of it, and the step
 * that moves it on. An interrupted task explains itself here rather than in a
 * dialog, because the answer is to go and read its chat.
 */
@Composable
private fun TaskPage(
    task: MikeTask,
    chatExists: Boolean,
    held: Boolean,
    now: Long,
    actions: AgentUiActions,
    onOpenChat: () -> Unit,
    onRemoved: () -> Unit,
) {
    val group = task.group()
    var confirmRemove by rememberSaveable(task.id) { mutableStateOf(false) }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (group == MikeTaskGroup.DONE) MikeFaint else group.accent(), size = 8.dp, pulsing = group == MikeTaskGroup.WORKING)
            Text(
                (if (chatExists) task.statusLine(now) else "Its chat was deleted") + " · " + ago(task.updatedAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = if (group == MikeTaskGroup.NEEDS_YOU || group == MikeTaskGroup.WORKING) group.accent() else MikeMuted,
            )
        }

        when {
            !chatExists -> Explained(
                "Mike cannot continue this",
                "The chat this task ran in was deleted. What it had noted is kept below until you remove it.",
            )
            task.status == MikeTaskStatus.UNKNOWN -> Explained(
                "Check what already happened",
                "The app stopped while Mike was working on this, so nobody knows how far it got. Read its chat first: starting again may repeat something that already went through.",
            ) {
                OutlinedButton(onClick = onOpenChat, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Open its chat") }
                Button(onClick = { actions.onRunMikeTask(task.id, true) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mike-task-retry")) { Text("I checked, retry") }
            }
            task.status == MikeTaskStatus.FAILED -> Explained(
                "It did not finish",
                "The last attempt ended with an error. Nothing that already happened was undone.",
            )
            held && group != MikeTaskGroup.DONE -> Explained(
                "Tasks are on hold",
                "You pressed Stop, so nothing starts by itself. You can still start this one from here.",
            )
        }

        Section("What Mike was asked", task.instruction)
        // An interrupted task's next step is the app's own warning, already given above.
        if (task.nextStep.isNotBlank() && group != MikeTaskGroup.DONE && task.status != MikeTaskStatus.UNKNOWN) Section("Next step", task.nextStep)
        if (task.result.isNotBlank()) Section(if (task.status == MikeTaskStatus.FAILED) "What went wrong" else "Result", task.result)

        if (chatExists && task.status == MikeTaskStatus.WAITING && task.canRun()) {
            // Waiting is the plan, so neither way out of it is the loud one.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { actions.onRunMikeTask(task.id, false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mike-task-run")) { Text(task.runLabel()) }
                OutlinedButton(onClick = { actions.onPauseMikeTask(task.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mike-task-pause")) { Text("Stop waiting") }
            }
        } else {
            if (chatExists && task.canRun()) {
                Button(onClick = { actions.onRunMikeTask(task.id, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mike-task-run")) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(task.runLabel())
                }
            }
            if (chatExists && task.canPause()) {
                OutlinedButton(onClick = { actions.onPauseMikeTask(task.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mike-task-pause")) {
                    Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pause")
                }
            }
        }
        if (task.status == MikeTaskStatus.WAITING && task.wakeAt != null) {
            Text("Android may hold a wake back by a few minutes when the phone is asleep.", style = MaterialTheme.typography.bodySmall, color = MikeFaint)
        }

        HorizontalDivider(color = MikeLine)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chatExists && task.status != MikeTaskStatus.UNKNOWN) {
                OutlinedButton(onClick = onOpenChat, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open chat")
                }
            }
            OutlinedButton(
                onClick = { confirmRemove = true },
                enabled = task.canRemove(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Remove")
            }
        }
        if (!task.canRemove()) Text("Pause it first to remove it.", style = MaterialTheme.typography.bodySmall, color = MikeFaint)
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “" + task.title + "”?") },
            text = { Text(if (chatExists) "It leaves Mike's tasks. Its chat stays with your other chats." else "It leaves Mike's tasks, with what it had noted.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemove = false; actions.onRemoveMikeTask(task.id); onRemoved() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewTaskPage(onCreate: (title: String, instruction: String, start: Boolean) -> Unit, onCancel: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("") }
    val valid = title.isNotBlank() && title.length <= TITLE_LIMIT && instruction.isNotBlank() && instruction.length <= INSTRUCTION_LIMIT
    Page {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Name") },
            singleLine = true,
            isError = title.length > TITLE_LIMIT,
            supportingText = if (title.length > TITLE_LIMIT) ({ Text("${title.length} / $TITLE_LIMIT") }) else null,
            modifier = Modifier.fillMaxWidth().testTag("mike-task-title"),
        )
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            label = { Text("What should Mike do?") },
            placeholder = { Text("e.g. Check the delivery page each morning until the parcel arrives, then tell me.") },
            isError = instruction.length > INSTRUCTION_LIMIT,
            minLines = 5,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth().testTag("mike-task-instruction"),
        )
        Text(
            "Mike works on it in a chat of its own and tells you in your conversation how it went. He can wait for a time and pick it up again.",
            style = MaterialTheme.typography.bodySmall,
            color = MikeMuted,
        )
        Button(onClick = { onCreate(title.trim(), instruction.trim(), true) }, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start now")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            OutlinedButton(onClick = { onCreate(title.trim(), instruction.trim(), false) }, enabled = valid, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Save for later") }
        }
    }
}

/** A page under the header: one scrolling column that stays above the keyboard. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        content()
        Spacer(Modifier.height(20.dp))
    }
}

/** The way to add one, drawn as an empty field: it reads as "write here", which a button does not. */
@Composable
private fun AddTile(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MikeLine),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MikeMuted)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MikeMuted)
        }
    }
}

@Composable
private fun GroupHeading(title: String, accent: Color, count: Int) {
    Row(Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = accent)
        Text("$count", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MikeFaint)
        HorizontalDivider(color = MikeLine.copy(alpha = 0.7f))
    }
}

/** A labelled block of the task's own words, selectable so a result can be copied. */
@Composable
private fun Section(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MikeMuted, modifier = Modifier.padding(start = 2.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = MikeTile, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium, color = MikeInk, modifier = Modifier.padding(14.dp)) }
        }
    }
}

/** Why a task needs the person, with the way out beside the reason. */
@Composable
private fun Explained(title: String, detail: String, buttons: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(14.dp), color = MikeNeedsFill, border = BorderStroke(1.dp, MikeNeedsLine), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = RuleBlocked)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MikeInk.copy(alpha = 0.82f))
            }
            buttons?.let { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = it) }
        }
    }
}

@Composable
private fun HeldCard(onResume: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MikeNeedsFill, border = BorderStroke(1.dp, MikeNeedsLine), modifier = Modifier.fillMaxWidth().testTag("mike-held")) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PauseCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = RuleBlocked)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Tasks are on hold", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = RuleBlocked)
                Text("You pressed Stop. Nothing waiting starts by itself until you resume.", fontSize = 12.sp, lineHeight = 16.sp, color = MikeInk.copy(alpha = 0.78f))
            }
            Spacer(Modifier.width(10.dp))
            Surface(onClick = onResume, shape = RoundedCornerShape(17.dp), color = Color(0xFFF2F2F2)) {
                Text("Resume", color = Color(0xFF111111), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun Notice(message: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp), tint = MaterialTheme.colorScheme.error)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun Empty(icon: ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).border(1.dp, MikeLine, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MikeMuted, modifier = Modifier.size(22.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = MikeInk, modifier = Modifier.padding(top = 14.dp), textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MikeMuted, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
    }
}

/** "5 min. ago", "Yesterday", "Oct 4": short, and in the phone's own language. */
private fun ago(then: Long, now: Long): String =
    if (now - then < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(then, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
