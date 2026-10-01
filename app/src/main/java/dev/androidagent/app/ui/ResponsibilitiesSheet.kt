/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androidagent.core.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResponsibilitiesSheet(state: AgentUiState, actions: AgentUiActions) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.responsibilities.responsibilities.firstOrNull { it.id == selectedId }
    fun back() {
        error = null
        if (creating) creating = false else if (selectedId != null) selectedId = null else actions.onCloseResponsibilities()
    }
    ModalBottomSheet(
        onDismissRequest = actions.onCloseResponsibilities,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.fillMaxHeight(0.94f),
    ) {
        BackHandler { back() }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = { back() }) { Text(if (selected != null || creating) "Back" else "Close") }
            Text(selected?.title ?: "Responsibilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.responsibilityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                creating -> ResponsibilityDraft(state, actions) { message ->
                    error = message
                    if (message == null) creating = false
                }
                selected != null -> {
                    Text(selected.goal)
                    Text(selected.state.label(), style = MaterialTheme.typography.labelLarge)
                    state.responsibilityHolds[selected.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Reviewed rules", fontWeight = FontWeight.SemiBold)
                    selected.ruleIds.forEach { id ->
                        val summary = state.automations.overview.summaries.firstOrNull { it.id == id }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(summary?.name ?: id, fontWeight = FontWeight.SemiBold)
                                if (summary == null) Text("This rule is missing.", color = MaterialTheme.colorScheme.error)
                                else {
                                    Text(summary.trigger)
                                    summary.actions.forEach { Text(it) }
                                    Text(summary.detail, style = MaterialTheme.typography.bodySmall)
                                    if (summary.sends.isNotBlank()) Text(summary.sends, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Text("Each rule keeps its own permissions and approvals. Editing a rule holds this responsibility until you review it again.",
                        style = MaterialTheme.typography.bodySmall)
                    if (selected.state == ResponsibilityState.NEEDS_ATTENTION) {
                        Text("A run may have changed something before it stopped. Check the result before allowing more work.")
                        Button(onClick = { error = actions.onResponsibilityState(selected.id, "acknowledge") }) {
                            Text("I checked the result · keep paused")
                        }
                    } else if (selected.state != ResponsibilityState.COMPLETED) {
                        if (selected.state != ResponsibilityState.ACTIVE) {
                            Button(onClick = { error = actions.onResponsibilityState(selected.id, "activate") }) {
                                Text("Activate reviewed rules")
                            }
                        } else {
                            OutlinedButton(onClick = { error = actions.onResponsibilityState(selected.id, "pause") }) { Text("Pause") }
                            // Also serves as a fresh review after editing a bound rule.
                            TextButton(onClick = { error = actions.onResponsibilityState(selected.id, "activate") }) { Text("Review and activate again") }
                        }
                        TextButton(onClick = { error = actions.onResponsibilityState(selected.id, "complete") }) { Text("Mark completed") }
                    }
                    HorizontalDivider()
                    Text("What Mike remembers here", fontWeight = FontWeight.SemiBold)
                    Text("Only notes you save here. Clearing them makes Mike forget these notes.", style = MaterialTheme.typography.bodySmall)
                    var notes by rememberSaveable(selected.id, selected.notes) { mutableStateOf(selected.notes) }
                    OutlinedTextField(notes, onValueChange = { if (it.length <= 2000) notes = it },
                        label = { Text("Your notes") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { error = actions.onResponsibilityNotes(selected.id, notes) }, enabled = notes != selected.notes) { Text("Save notes") }
                    HorizontalDivider()
                    Text("Recent activity", fontWeight = FontWeight.SemiBold)
                    val history = state.responsibilities.activity.filter { it.responsibilityId == selected.id }.takeLast(30).reversed()
                    if (history.isEmpty()) Text("No rule has run for this responsibility yet.")
                    history.forEach { item ->
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.at)),
                            style = MaterialTheme.typography.labelSmall)
                        Text("${item.ruleId} · ${item.status.label()} · ${item.completedActions}/${item.totalActions} actions")
                    }
                    Text("Queued turns continue in the rule's chat. Activity here records dispatch, not the model's final result.",
                        style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("What Mike is taking care of across chats.")
                    Text("Responsibilities use this phone's existing rules. They can wait while the phone, runtime or permissions are unavailable.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { creating = true; error = null }, enabled = state.responsibilityError == null) { Text("New responsibility") }
                    state.responsibilities.responsibilities.forEach { item ->
                        OutlinedCard(onClick = { selectedId = item.id; error = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                Text(item.goal)
                                Text(if (item.id in state.responsibilityHolds) "Held · review rules" else item.state.label(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (state.responsibilities.responsibilities.isEmpty()) Text("No responsibilities yet. Create a standing rule with Mike, then attach it here.")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ResponsibilityDraft(state: AgentUiState, actions: AgentUiActions, onSaved: (String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val bound = state.responsibilities.responsibilities.flatMap { it.ruleIds }.toSet()
    val available = state.automations.overview.summaries.filter { it.id !in bound }
    OutlinedTextField(title, { if (it.length <= 120) title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(goal, { if (it.length <= 2000) goal = it }, label = { Text("What should Mike take care of?") }, modifier = Modifier.fillMaxWidth())
    Text("Choose existing rules. Saving a draft holds these rules until you activate it.", style = MaterialTheme.typography.bodySmall)
    available.forEach { rule ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rule.id in selected, onCheckedChange = { checked ->
                selected = if (checked) selected + rule.id else selected - rule.id
            }, enabled = rule.id in selected || selected.size < 16)
            Column {
                Text(rule.name)
                Text(rule.actions.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (available.isEmpty()) Text("No unattached rules. Ask Mike to create a standing rule first.")
    Button(
        onClick = { onSaved(actions.onCreateResponsibility(title, goal, selected)) },
        enabled = title.isNotBlank() && goal.isNotBlank() && selected.isNotEmpty(),
    ) { Text("Save draft") }
}

private fun ResponsibilityState.label() = when (this) {
    ResponsibilityState.DRAFT -> "Draft · rules held for review"
    ResponsibilityState.ACTIVE -> "Active"
    ResponsibilityState.PAUSED -> "Paused"
    ResponsibilityState.NEEDS_ATTENTION -> "Needs you · result unknown"
    ResponsibilityState.COMPLETED -> "Completed · rules held"
}

private fun ResponsibilityActivityStatus.label() = when (this) {
    ResponsibilityActivityStatus.STARTED -> "Running"
    ResponsibilityActivityStatus.SUCCEEDED -> "Rule actions completed"
    ResponsibilityActivityStatus.DISPATCHED -> "Turn queued"
    ResponsibilityActivityStatus.FAILED -> "Stopped"
    ResponsibilityActivityStatus.UNKNOWN -> "Result unknown"
    ResponsibilityActivityStatus.REVIEWED -> "Result reviewed"
}
