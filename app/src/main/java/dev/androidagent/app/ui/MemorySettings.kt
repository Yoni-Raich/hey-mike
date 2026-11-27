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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.MemoryConsolidationAlarm
import dev.androidagent.core.MemoryBackup
import dev.androidagent.core.UserMemorySnapshot
import dev.androidagent.core.UserMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun memoryHubSummary(): String {
    val graph = (LocalContext.current.applicationContext as? AgentApplication)?.graph
        ?: return "Personal facts shared across every engine"
    val snapshot by graph.userMemory.state.collectAsState()
    val busy by graph.memoryConsolidation.busy.collectAsState()
    return memoryHubSummaryText(snapshot, busy)
}

internal fun memoryHubSummaryText(snapshot: UserMemorySnapshot, busy: Boolean): String = when {
    busy -> "Updating from recent chats…"
    !snapshot.config.enabled -> "Off"
    !snapshot.hasContent && snapshot.config.autoLearn -> "Empty · learns nightly"
    !snapshot.hasContent -> "Empty"
    snapshot.config.autoLearn -> "${snapshot.charCount} chars · learns nightly"
    else -> "${snapshot.charCount} chars"
}

@Composable
internal fun MemorySettings(onOpenExactAlarmSettings: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as AgentApplication).graph
    val snapshot by graph.userMemory.state.collectAsState()
    val busy by graph.memoryConsolidation.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val exactAlarms = remember(snapshot.config.autoLearn) { MemoryConsolidationAlarm.canScheduleExact(context) }
    val nextRun = remember(snapshot.config) { graph.memoryConsolidation.nextRunAtMillis() }
    var statusNote by remember { mutableStateOf<String?>(null) }

    MemorySettingsPage(
        snapshot = snapshot,
        busy = busy,
        exactAlarms = exactAlarms,
        nextRunAtMillis = nextRun,
        statusNote = statusNote,
        onToggleEnabled = { on ->
            scope.launch(Dispatchers.IO) {
                graph.userMemory.setEnabled(on)
                graph.memoryConsolidation.rearm()
            }
        },
        onSaveContent = { text ->
            scope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching { graph.userMemory.write(text, createBackup = true) }.exceptionOrNull()?.message
                }
                statusNote = error ?: "Saved."
            }
        },
        onClear = {
            scope.launch {
                withContext(Dispatchers.IO) { graph.userMemory.clear() }
                statusNote = "Cleared (previous version saved in backups)."
            }
        },
        onToggleAutoLearn = { on ->
            scope.launch(Dispatchers.IO) {
                graph.userMemory.setAutoLearn(on)
                graph.memoryConsolidation.rearm()
            }
        },
        onChangeNightlyHour = { hour ->
            scope.launch(Dispatchers.IO) {
                graph.userMemory.setNightlyHour(hour)
                graph.memoryConsolidation.rearm()
            }
        },
        onRunNow = {
            statusNote = null
            graph.memoryConsolidation.startNow()
        },
        onRestoreBackup = { id ->
            scope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching { graph.userMemory.restoreBackup(id) }.exceptionOrNull()?.message
                }
                statusNote = error ?: "Restored backup."
            }
        },
        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
    )
}

@Composable
internal fun MemorySettingsPage(
    snapshot: UserMemorySnapshot,
    busy: Boolean,
    exactAlarms: Boolean,
    nextRunAtMillis: Long?,
    statusNote: String?,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveContent: (String) -> Unit,
    onClear: () -> Unit,
    onToggleAutoLearn: (Boolean) -> Unit,
    onChangeNightlyHour: (Int) -> Unit,
    onRunNow: () -> Unit,
    onRestoreBackup: (String) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(snapshot.content) }
    var lastLoaded by rememberSaveable { mutableStateOf(snapshot.content) }
    LaunchedEffect(snapshot.content) {
        if (snapshot.content != lastLoaded) {
            draft = snapshot.content
            lastLoaded = snapshot.content
        }
    }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf<String?>(null) }

    val dirty = draft.trim() != snapshot.content.trim()
    val overLimit = draft.length > UserMemoryStore.MAX_PROFILE_CHARS

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Explanation(
            "Saved on this phone and shared with every engine — Codex, Claude, computers (including Antigravity) " +
                "and Voice. Mike treats this as quoted background data about you, never as instructions. " +
                "Passwords, tokens and codes are automatically stripped.",
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Remember about me", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (snapshot.config.enabled) "Included at the start of new chats and voice" else "Paused · nothing is sent to chats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = snapshot.config.enabled, onCheckedChange = onToggleEnabled)
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Profile (Markdown)") },
                    placeholder = { Text("Facts about you, communication style, family/people context, ongoing projects…") },
                    minLines = 6,
                    maxLines = 14,
                    isError = overLimit,
                    supportingText = {
                        Text(
                            "${draft.length} / ${UserMemoryStore.MAX_PROFILE_CHARS} chars",
                            color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                statusNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSaveContent(draft) },
                        enabled = dirty && !overLimit,
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                    if (dirty) {
                        OutlinedButton(onClick = { draft = snapshot.content }) { Text("Revert") }
                    }
                    if (snapshot.hasContent) {
                        OutlinedButton(
                            onClick = { confirmClear = true },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Learn from chats nightly", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "One short background turn reads new chats (skipping automations and subagents) without cluttering your chat list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = snapshot.config.autoLearn,
                        enabled = snapshot.config.enabled,
                        onCheckedChange = onToggleAutoLearn,
                    )
                }

                if (snapshot.config.enabled && snapshot.config.autoLearn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Nightly time", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onChangeNightlyHour((snapshot.config.nightlyHour + 23) % 24) }) {
                                Text("−")
                            }
                            Text(
                                String.format(Locale.ENGLISH, "%02d:00", snapshot.config.nightlyHour),
                                fontWeight = FontWeight.Medium,
                            )
                            OutlinedButton(onClick = { onChangeNightlyHour((snapshot.config.nightlyHour + 1) % 24) }) {
                                Text("+")
                            }
                        }
                    }
                }

                val lastSummary = formatLastRun(snapshot)
                if (lastSummary != null) {
                    Text(
                        lastSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (snapshot.config.lastRunOk == false) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (nextRunAtMillis != null && snapshot.config.enabled && snapshot.config.autoLearn) {
                    Text(
                        "Next automatic update: ${formatTimestamp(nextRunAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (snapshot.config.enabled && snapshot.config.autoLearn && !exactAlarms) {
                    Text(
                        "Alarms & reminders is off, so Android may delay the nightly run under Doze.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenExactAlarmSettings) { Text("Allow exact alarms") }
                }

                OutlinedButton(
                    onClick = onRunNow,
                    enabled = !busy && snapshot.config.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Updating from recent chats…")
                    } else {
                        Text("Update from recent chats now")
                    }
                }
            }
        }

        if (snapshot.backups.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic backups", style = MaterialTheme.typography.titleMedium)
                    Explanation("Saved automatically before every rewrite or clear. Restore any snapshot in one tap.")
                    snapshot.backups.forEach { backup ->
                        BackupRow(backup) { confirmRestore = backup.id }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear user memory?") },
            text = { Text("This clears the profile for future chats. A backup is saved first so you can restore it.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    confirmRestore?.let { backupId ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("Your current profile will be backed up first before replacing it.") },
            confirmButton = {
                TextButton(onClick = { confirmRestore = null; onRestoreBackup(backupId) }) {
                    Text("Restore")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BackupRow(backup: MemoryBackup, onRestore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${formatTimestamp(backup.createdAt)} · ${backup.sizeChars} chars",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (backup.preview.isNotEmpty()) {
                Text(
                    backup.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        TextButton(onClick = onRestore) { Text("Restore") }
    }
}

private fun formatLastRun(snapshot: UserMemorySnapshot): String? {
    val at = snapshot.config.lastRunAt ?: return null
    val summary = snapshot.config.lastRunSummary?.takeIf { it.isNotBlank() } ?: return "Last run: ${formatTimestamp(at)}"
    return "Last run (${formatTimestamp(at)}): $summary"
}

private fun formatTimestamp(epochMillis: Long): String = runCatching {
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH))
}.getOrDefault("")
