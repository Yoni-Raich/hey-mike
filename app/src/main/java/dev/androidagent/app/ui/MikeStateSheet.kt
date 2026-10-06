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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androidagent.core.*
import java.util.UUID

@Composable
internal fun MikeHomeCard(state: AgentUiState, onOpen: () -> Unit, onManage: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(4.dp).testTag("mike-home")) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Mike", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Your ongoing conversation", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Outlined.ChevronRight, "Open Mike")
            }
            TextButton(onClick = onManage, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).testTag("mike-manage")) {
                val open = state.mike.tasks.count { it.status !in MikeStateStore.TERMINAL }
                Text("${state.mike.memories.size} memories · $open open tasks")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MikeStateSheet(state: AgentUiState, actions: AgentUiActions) {
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<MikeMemory?>(null) }
    var addingMemory by remember { mutableStateOf(false) }
    var addingTask by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<MikeMemory?>(null) }
    var retrying by remember { mutableStateOf<MikeTask?>(null) }
    ModalBottomSheet(onDismissRequest = actions.onCloseMikeState, modifier = Modifier.testTag("mike-state-sheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp)) {
            Text("Mike", style = MaterialTheme.typography.headlineSmall)
            Text("What I remember and what I'm working on", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            state.mikeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Memory") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Tasks") })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tab == 0) "Saved across all your chats" else if (state.mike.paused) "Future task work is paused" else "Results return to Mike", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { if (tab == 0) addingMemory = true else addingTask = true }) { Text(if (tab == 0) "Add memory" else "New task") }
            }
            if (tab == 1 && state.mike.paused) FilledTonalButton(onClick = actions.onResumeMike) { Text("Resume scheduled tasks") }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (tab == 0) {
                    if (state.mike.memories.isEmpty()) item { EmptyMike("Tell Mike something to remember, or add it here. You can correct or remove it any time.") }
                    items(state.mike.memories.sortedByDescending { it.updatedAt }, key = { it.key }) { memory ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(memory.kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                Text(memory.text, modifier = Modifier.padding(top = 6.dp))
                                Row {
                                    TextButton(onClick = { editing = memory }) { Text("Correct") }
                                    TextButton(onClick = { forgetting = memory }) { Text("Forget") }
                                }
                            }
                        }
                    }
                } else {
                    if (state.mike.tasks.isEmpty()) item { EmptyMike("Give Mike a task here or in your conversation. Its progress stays here after you close the app.") }
                    items(state.mike.tasks.sortedByDescending { it.updatedAt }, key = { it.id }) { task ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(task.title, style = MaterialTheme.typography.titleMedium)
                                Text(task.status.readable(), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                                Text(task.result.ifBlank { task.nextStep.ifBlank { task.instruction } }.take(700), style = MaterialTheme.typography.bodyMedium)
                                Row {
                                    TextButton(onClick = { actions.onCloseMikeState(); actions.onSelectSession(task.sessionId) }) { Text("Open chat") }
                                    if (!task.turnActive && task.status !in setOf(MikeTaskStatus.DONE, MikeTaskStatus.RUNNING, MikeTaskStatus.QUEUED, MikeTaskStatus.UNKNOWN)) TextButton(onClick = { actions.onRunMikeTask(task.id, false) }) { Text(if (task.status == MikeTaskStatus.READY) "Run" else "Continue") }
                                    if (task.status in setOf(MikeTaskStatus.RUNNING, MikeTaskStatus.QUEUED, MikeTaskStatus.WAITING)) TextButton(onClick = { actions.onPauseMikeTask(task.id) }) { Text("Pause") }
                                    if (task.status == MikeTaskStatus.UNKNOWN) TextButton(onClick = { retrying = task }) { Text("Review retry") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (addingMemory || editing != null) {
        val original = editing
        var text by remember(original) { mutableStateOf(original?.text.orEmpty()) }
        val key = remember(original) { original?.key ?: "note." + UUID.randomUUID().toString() }
        AlertDialog(onDismissRequest = { addingMemory = false; editing = null }, title = { Text(if (original == null) "Remember this" else "Correct memory") }, text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What should Mike remember?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton(enabled = text.isNotBlank() && text.length <= 1200, onClick = { actions.onSaveMikeMemory(key, text, original?.kind ?: "fact", original?.revision ?: 0); addingMemory = false; editing = null }) { Text("Save") } }, dismissButton = { TextButton(onClick = { addingMemory = false; editing = null }) { Text("Cancel") } })
    }
    if (addingTask) {
        var title by remember { mutableStateOf("") }
        var instruction by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addingTask = false }, title = { Text("New task") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(instruction, { instruction = it }, label = { Text("What should Mike do?") }, minLines = 3)
                Text("Saved first. Tap Run when you are ready.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = title.isNotBlank() && title.length <= 100 && instruction.isNotBlank() && instruction.length <= 8000, onClick = { actions.onCreateMikeTask(title, instruction); addingTask = false }) { Text("Save task") } }, dismissButton = { TextButton(onClick = { addingTask = false }) { Text("Cancel") } })
    }
    forgetting?.let { memory -> AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("Forget this memory?") }, text = { Text(memory.text) }, confirmButton = { TextButton(onClick = { actions.onForgetMikeMemory(memory.key, memory.revision); forgetting = null }) { Text("Forget") } }, dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } }) }
    retrying?.let { task -> AlertDialog(onDismissRequest = { retrying = null }, title = { Text("Check what already happened") }, text = { Text("This task stopped before its result was saved. Read its chat first. Retrying may repeat actions that already completed.") }, confirmButton = { TextButton(onClick = { actions.onRunMikeTask(task.id, true); retrying = null }) { Text("Checked — retry") } }, dismissButton = { TextButton(onClick = { retrying = null; actions.onCloseMikeState(); actions.onSelectSession(task.sessionId) }) { Text("Open task chat") } }) }
}

@Composable
private fun EmptyMike(text: String) { Text(text, modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

private fun MikeTaskStatus.readable() = when (this) {
    MikeTaskStatus.READY -> "Ready to start"
    MikeTaskStatus.QUEUED -> "Queued"
    MikeTaskStatus.RUNNING -> "Working"
    MikeTaskStatus.WAITING -> "Waiting"
    MikeTaskStatus.PAUSED -> "Paused"
    MikeTaskStatus.DONE -> "Done"
    MikeTaskStatus.FAILED -> "Needs attention"
    MikeTaskStatus.UNKNOWN -> "Result needs checking"
}
