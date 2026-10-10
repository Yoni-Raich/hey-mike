package dev.androidagent.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.core.ChatSession
import dev.androidagent.core.SessionAgentTask
import dev.androidagent.remote.ComputerTask
import dev.androidagent.remote.RemoteState

data class ChildAgentItem(
    val id: String,
    val parentSessionId: String,
    val sessionId: String?,
    val title: String,
    val task: String,
    val status: String,
    val progress: String,
    val place: String,
    val result: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
) {
    val terminal: Boolean get() = status in setOf("completed", "failed", "cancelled", "unknown")
    val label: String get() = when (status) {
        "completed" -> "Done"
        "failed" -> "Failed"
        "cancelled" -> "Stopped"
        "unknown" -> "Check chat"
        "waiting_for_user" -> "Needs you"
        "stopping", "cancelling" -> "Stopping"
        "queued", "starting" -> "Starting"
        else -> "Working"
    }
    companion object {
        fun from(task: SessionAgentTask, remote: RemoteState, chats: List<ChatSession>): ChildAgentItem {
            val binding = task.sessionId?.let(remote.bindings::get)
            val chat = chats.firstOrNull { it.id == task.sessionId }
            val place = binding?.let { b ->
                val computer = remote.computers.firstOrNull { it.id == b.computerId }?.label ?: "Computer"
                "$computer · ${b.cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')}"
            } ?: task.computer?.takeUnless { it.equals("phone", true) }?.let { target ->
                val computer = remote.computers.firstOrNull { it.id == target || it.label.equals(target, true) }?.label ?: target
                listOfNotNull(computer, task.project?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')).joinToString(" · ")
            } ?: "This phone"
            return ChildAgentItem(task.id, task.parentSessionId, task.sessionId,
                chat?.title ?: task.title, task.task, task.status, task.progress, place, task.result,
                chat?.model ?: task.model.takeIf { chat == null || chat.engine == task.engine },
                if (chat?.model != null) chat.reasoningEffort else task.reasoningEffort.takeIf { chat == null || chat.engine == task.engine })
        }
        fun from(task: ComputerTask, remote: RemoteState) = ChildAgentItem(task.id, task.originSessionId, task.sessionId,
            task.project.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'), task.message,
            task.status, task.progress, remote.computers.firstOrNull { it.id == task.computerId }?.label ?: "Computer", task.result)
    }
}

@Composable
internal fun ChildAgentCard(child: ChildAgentItem, actions: AgentUiActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    val accent = when (child.status) {
        "failed", "unknown", "waiting_for_user" -> Color(0xFFF6B86A)
        "completed" -> Color(0xFF83D9CA)
        "cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color(0xFF69A7FF)
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = Color(0xFF171C20), contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))) {
        Column {
            Surface(onClick = { child.sessionId?.let(actions.onSelectSession) }, enabled = child.sessionId != null,
                color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Open subagent chat: ${child.title}" }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.AccountTree, null, tint = accent, modifier = Modifier.size(20.dp))
                        Text(child.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                    }
                    Text(child.task, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    child.model?.let { model ->
                        Text(listOfNotNull(shortModelName(model), child.reasoningEffort?.let(::effortLabel)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9C9E2),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(6.dp).background(accent, CircleShape))
                        Text(child.label, style = MaterialTheme.typography.labelMedium, color = accent)
                        Text("· ${child.place}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    if (!compact) Text(child.result?.take(300) ?: child.progress, style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!compact && !child.terminal && child.sessionId != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Open chat to follow up", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.onStopSession(child.sessionId) }) {
                        Icon(Icons.Outlined.Stop, "Stop subagent: ${child.title}", tint = accent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ParentChatBanner(state: AgentUiState, actions: AgentUiActions) {
    val child = state.sessions.firstOrNull { it.id == state.activeSessionId } ?: return
    val parent = child.parentSessionId?.let { id -> state.sessions.firstOrNull { it.id == id } } ?: return
    Surface(onClick = { actions.onSelectSession(parent.id) }, color = Color(0xFF142724),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Open parent chat: ${parent.title}" }) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color(0xFF83D9CA), modifier = Modifier.size(18.dp))
            Text("From ${parent.title}", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB9E5DB),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun VoiceChatBanner(state: AgentUiState, actions: AgentUiActions) {
    val title = state.sessions.firstOrNull { it.id == state.voiceSessionId }?.title ?: "Mike"
    Surface(color = Color(0xFF122726), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, null, tint = Color(0xFF83D9CA))
            Text("Voice · $title", Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = actions.onReturnToVoice) { Text("Return") }
            IconButton(onClick = { state.voiceSessionId?.let(actions.onStopSession) }) {
                Icon(Icons.Outlined.Stop, "End voice and stop its subagents")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChildAgentsButton(state: AgentUiState, actions: AgentUiActions) {
    val children = state.childAgents.filter { it.parentSessionId == state.activeSessionId }
    if (children.isEmpty()) return
    var open by remember(state.activeSessionId) { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.AccountTree, "Subagents (${children.size})") }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = Color(0xFF141A1D)) {
        Text("Subagents", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(children, key = { it.id }) { child -> ChildAgentCard(child,
                actions.copy(onSelectSession = { id -> open = false; actions.onSelectSession(id) })) }
        }
    }
}

@Composable
internal fun VoiceChildAgents(state: AgentUiState, actions: AgentUiActions) {
    val children = state.childAgents.filter { it.parentSessionId == state.voiceSessionId }
    if (children.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(children, key = { it.id }) { child -> ChildAgentCard(child, actions, Modifier.width(280.dp), compact = true) }
    }
}
