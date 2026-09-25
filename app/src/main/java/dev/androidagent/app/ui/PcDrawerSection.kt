package dev.androidagent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.remote.RemoteSetup

private val PcReady = Color(0xFF83D9CA)
private const val CHATS_SHOWN = 5

/**
 * The side panel's computers: each one a header, its projects under it, and
 * a project's chats under the project. Open and closed projects are kept in
 * [expanded]; a project nobody touched is open when it holds the current chat
 * or is the computer's most recent one.
 */
internal fun LazyListScope.pcSections(
    sections: List<PcSection>,
    state: AgentUiState,
    actions: AgentUiActions,
    expanded: SnapshotStateMap<String, Boolean>,
    showAll: SnapshotStateMap<String, Boolean>,
    close: () -> Unit,
) {
    sections.forEach { section ->
        val computer = section.computer
        val setup = state.computerSetup[computer.id]
        val sectionOpen = expanded["pc-${computer.id}"] ?: true
        item(key = "pc-${computer.id}") {
            PcHeader(
                label = computer.label,
                status = when (setup) {
                    is RemoteSetup.Ready -> "Connected · ${section.projects.size} projects"
                    is RemoteSetup.Working -> setup.step
                    is RemoteSetup.NeedsSignIn -> "Sign in to Codex on the PC"
                    is RemoteSetup.Failed -> "Not connected"
                    null -> "Not connected"
                },
                connected = setup is RemoteSetup.Ready,
                open = sectionOpen,
                onToggle = { expanded["pc-${computer.id}"] = !sectionOpen },
                onManage = { close(); actions.onOpenComputers() },
            )
        }
        if (!sectionOpen) return@forEach
        if (setup is RemoteSetup.Failed || setup is RemoteSetup.NeedsSignIn) {
            item(key = "pc-${computer.id}-problem") {
                Text(
                    (setup as? RemoteSetup.Failed)?.message ?: "Codex on the PC needs a sign-in.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 34.dp, end = 8.dp, top = 2.dp),
                )
            }
        }
        if (setup !is RemoteSetup.Ready && setup !is RemoteSetup.Working) {
            item(key = "pc-${computer.id}-connect") {
                QuietRow("Connect to load its projects and chats", icon = Icons.Outlined.Sync) { actions.onReconnectComputer(computer.id) }
            }
        }
        section.projects.forEachIndexed { index, project ->
            val holdsCurrent = project.chats.any { (it as? PcChatEntry.Local)?.session?.id == state.activeSessionId }
            val open = expanded[project.key] ?: (holdsCurrent || index == 0)
            item(key = project.key) {
                ProjectRow(
                    name = project.name,
                    count = project.chats.size,
                    open = open,
                    onToggle = { expanded[project.key] = !open },
                    onNewChat = { close(); actions.onNewChatInProject(computer.id, project.path) },
                )
            }
            if (open) {
                val all = showAll[project.key] == true
                val chats = if (all) project.chats else project.chats.take(CHATS_SHOWN)
                items(chats, key = { "${project.key}-${it.key}" }) { entry ->
                    PcChatRow(
                        entry = entry,
                        selected = (entry as? PcChatEntry.Local)?.session?.id == state.activeSessionId,
                        running = state.runState.active && (entry as? PcChatEntry.Local)?.session?.id == state.runState.sessionId,
                        onOpen = {
                            close()
                            when (entry) {
                                is PcChatEntry.Local -> actions.onSelectSession(entry.session.id)
                                is PcChatEntry.OnComputer -> actions.onOpenPcThread(computer.id, entry.thread.id)
                            }
                        },
                    )
                }
                if (project.chats.size > CHATS_SHOWN) {
                    item(key = "${project.key}-more") {
                        QuietRow(
                            text = if (all) "Show fewer" else "Show all ${project.chats.size}",
                            indent = true,
                        ) { showAll[project.key] = !all }
                    }
                }
            }
        }
        item(key = "pc-${computer.id}-new-project") {
            QuietRow(text = "New project on ${computer.label}", icon = Icons.Outlined.CreateNewFolder) {
                close()
                actions.onNewProject(computer.id)
            }
        }
    }
}

@Composable
internal fun DrawerSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PcHeader(label: String, status: String, connected: Boolean, open: Boolean, onToggle: () -> Unit, onManage: () -> Unit) {
    FoldHeader(label, status, open, Icons.Outlined.Computer, statusColor = if (connected) PcReady else null, onToggle = onToggle) {
        IconButton(onClick = onManage) {
            Icon(Icons.Outlined.Settings, contentDescription = "Manage $label", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A side panel section title that folds its section away. */
@Composable
internal fun FoldHeader(
    title: String,
    status: String,
    open: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    statusColor: Color? = null,
    onToggle: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Surface(onClick = onToggle, shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
            Icon(
                if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = if (open) "Fold $title" else "Unfold $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun ProjectRow(name: String, count: Int, open: Boolean, onToggle: () -> Unit, onNewChat: () -> Unit) {
    Surface(onClick = onToggle, shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = if (open) "Close $name" else "Open $name",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onNewChat) { Icon(Icons.Outlined.Add, contentDescription = "New chat in $name") }
        }
    }
}

@Composable
private fun PcChatRow(entry: PcChatEntry, selected: Boolean, running: Boolean, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 38.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) StatusDot(color = Color(0xFF69A7FF), size = 8.dp, pulsing = true, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title.ifBlank { "Untitled chat" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry is PcChatEntry.OnComputer) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    }
                    Text(
                        (if (entry is PcChatEntry.OnComputer) "From Codex on the PC · " else "") + formatSessionTime(entry.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuietRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, indent: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = if (indent) 38.dp else 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
