package dev.androidagent.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import androidx.compose.ui.unit.dp
import dev.androidagent.remote.RemoteSetup

// The drawer's own palette, on top of the app's dark scheme: a lifted surface
// for the sheet, a teal for "connected", amber for "working on it".
internal val DrawerSurface = Color(0xFF161618)
internal val DrawerMuted = Color(0xFF9A9A9E)
internal val DrawerChip = Color(0xFF2A2A2E)
private val PcReady = Color(0xFF83D9CA)
private val PcBusy = Color(0xFFF6B86A)
private val PcTrack = Color(0xFF243A36)
private const val CHATS_SHOWN = 5

/**
 * The side panel's computers: each one a header, its projects under it, and
 * a project's chats under the project. Open and closed sections are kept in
 * [expanded]; a project nobody touched is open when it holds the current chat
 * or is the computer's most recent one.
 *
 * Anything slow says so where it happens: a progress line under the header
 * while connecting or listing, placeholder rows until the first projects
 * arrive, and an error card with a retry when the computer does not answer.
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
        val refreshing = computer.id in state.pcRefreshing
        val busy = setup is RemoteSetup.Working || refreshing
        val sectionOpen = expanded["pc-${computer.id}"] ?: true
        item(key = "pc-${computer.id}", contentType = "pc-header") {
            Column(Modifier.animateItem()) {
                PcHeader(
                    label = computer.label,
                    status = when (setup) {
                        is RemoteSetup.Ready -> listOfNotNull(
                            if (refreshing) "Loading conversations" else "Connected",
                            setup.route?.let { if (it.viaVpn) "VPN" else "home network" },
                            "${section.projects.size} projects",
                        ).joinToString(" · ")
                        is RemoteSetup.Working -> setup.step
                        is RemoteSetup.NeedsSignIn -> "Codex needs a sign-in"
                        is RemoteSetup.NeedsTailscaleApproval -> "Waiting for your approval in Tailscale"
                        is RemoteSetup.Failed -> "Not connected"
                        null -> "Not connected"
                    },
                    statusColor = when {
                        busy -> PcBusy
                        setup is RemoteSetup.Ready -> PcReady
                        else -> DrawerMuted
                    },
                    dot = when {
                        busy -> null
                        setup is RemoteSetup.Ready -> PcReady
                        else -> Color(0xFF6B6B70)
                    },
                    busy = busy,
                    open = sectionOpen,
                    onToggle = { expanded["pc-${computer.id}"] = !sectionOpen },
                    onManage = { close(); actions.onOpenComputers() },
                )
                // Indeterminate, so it never pretends to know how long the PC takes.
                if (busy && sectionOpen) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = PcReady,
                        trackColor = PcTrack,
                    )
                }
            }
        }
        if (!sectionOpen) return@forEach
        when (setup) {
            is RemoteSetup.Failed -> item(key = "pc-${computer.id}-problem", contentType = "pc-problem") {
                PcProblemCard(
                    message = setup.message,
                    primary = "Try again",
                    onPrimary = { actions.onReconnectComputer(computer.id) },
                    secondary = "What to check",
                    onSecondary = { close(); actions.onOpenComputers() },
                    modifier = Modifier.animateItem(),
                )
            }
            is RemoteSetup.NeedsTailscaleApproval -> item(key = "pc-${computer.id}-tailscale", contentType = "pc-approval") {
                TailscaleApprovalCard(
                    computer = computer.label,
                    url = setup.url,
                    onOpen = { url -> actions.onOpenTailscaleApproval(computer.id, url) },
                    onConnect = { actions.onReconnectComputer(computer.id) },
                    modifier = Modifier.animateItem().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            is RemoteSetup.NeedsSignIn -> item(key = "pc-${computer.id}-signin", contentType = "pc-problem") {
                PcProblemCard(
                    message = "Codex on ${computer.label} is not signed in. Sign in once and its projects show here.",
                    primary = "Sign in",
                    onPrimary = { close(); actions.onOpenComputers() },
                    modifier = Modifier.animateItem(),
                )
            }
            null -> item(key = "pc-${computer.id}-connect", contentType = "quiet") {
                QuietRow("Connect to load its projects and chats", icon = Icons.Outlined.Computer, modifier = Modifier.animateItem()) {
                    actions.onReconnectComputer(computer.id)
                }
            }
            else -> Unit
        }
        // Placeholders only while nothing is known yet; a known list stays put
        // and refreshes in place.
        if (busy && section.projects.isEmpty()) {
            items(3, key = { "pc-${computer.id}-skeleton-$it" }, contentType = { "skeleton" }) { index ->
                SkeletonRow(width = listOf(132, 96, 150)[index], modifier = Modifier.animateItem())
            }
            item(key = "pc-${computer.id}-waiting", contentType = "hint") {
                Text(
                    "Projects and conversations show here as soon as the computer answers. Phone chats work meanwhile.",
                    style = MaterialTheme.typography.labelSmall,
                    color = DrawerMuted,
                    modifier = Modifier.animateItem().padding(start = 48.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
        val stale = setup is RemoteSetup.Failed || setup == null
        if (stale && section.projects.isNotEmpty()) {
            item(key = "pc-${computer.id}-stale", contentType = "label") {
                DrawerSectionLabel("From the last connection", Modifier.animateItem())
            }
        }
        section.projects.forEachIndexed { index, project ->
            val holdsCurrent = project.chats.any { (it as? PcChatEntry.Local)?.session?.id == state.activeSessionId }
            val open = expanded[project.key] ?: (holdsCurrent || index == 0)
            item(key = project.key, contentType = "project") {
                ProjectRow(
                    name = project.name,
                    count = project.chats.size,
                    open = open,
                    dimmed = stale,
                    onToggle = { expanded[project.key] = !open },
                    onNewChat = { close(); actions.onNewChatInProject(computer.id, project.path) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (open) {
                val all = showAll[project.key] == true
                val chats = if (all) project.chats else project.chats.take(CHATS_SHOWN)
                items(chats, key = { "${project.key}-${it.key}" }, contentType = { "pc-chat" }) { entry ->
                    val session = (entry as? PcChatEntry.Local)?.session?.id
                    PcChatRow(
                        entry = entry,
                        selected = session != null && session == state.activeSessionId,
                        running = state.runState.active && session != null && session == state.runState.sessionId,
                        onOpen = {
                            close()
                            when (entry) {
                                is PcChatEntry.Local -> actions.onSelectSession(entry.session.id)
                                is PcChatEntry.OnComputer -> actions.onOpenPcThread(computer.id, entry.thread.id)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (project.chats.size > CHATS_SHOWN) {
                    item(key = "${project.key}-more", contentType = "quiet") {
                        QuietRow(
                            text = if (all) "Show fewer" else "Show all ${project.chats.size}",
                            indent = true,
                            modifier = Modifier.animateItem(),
                        ) { showAll[project.key] = !all }
                    }
                }
            }
        }
        item(key = "pc-${computer.id}-new-project", contentType = "quiet") {
            QuietRow(text = "New project on ${computer.label}", icon = Icons.Outlined.CreateNewFolder, modifier = Modifier.animateItem()) {
                close()
                actions.onNewProject(computer.id)
            }
        }
    }
    if (sections.isNotEmpty()) {
        item(key = "pc-divider", contentType = "divider") {
            HorizontalDivider(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 8.dp), color = DrawerChip)
        }
    }
}

@Composable
internal fun DrawerSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = DrawerMuted,
        modifier = modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PcHeader(
    label: String,
    status: String,
    statusColor: Color,
    dot: Color?,
    busy: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onManage: () -> Unit,
) {
    FoldHeader(label, status, open, Icons.Outlined.Computer, statusColor = statusColor, dot = dot, onToggle = onToggle) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 4.dp).size(18.dp).semantics { contentDescription = "Working" },
                strokeWidth = 2.dp,
                color = PcReady,
                trackColor = DrawerChip,
            )
        }
        IconButton(onClick = onManage) {
            Icon(Icons.Outlined.Settings, contentDescription = "Manage $label", tint = DrawerMuted)
        }
    }
}

/** A side panel section title that folds its section away. */
@Composable
internal fun FoldHeader(
    title: String,
    status: String,
    open: Boolean,
    icon: ImageVector,
    statusColor: Color? = null,
    dot: Color? = null,
    onToggle: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Surface(onClick = onToggle, shape = RoundedCornerShape(28.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dot?.let {
                        Box(Modifier.size(6.dp).background(it, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor ?: DrawerMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
            Icon(
                if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = if (open) "Fold $title" else "Unfold $title",
                tint = DrawerMuted,
                modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun PcProblemCard(
    message: String,
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                secondary?.let {
                    TextButton(onClick = onSecondary) { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
                Button(
                    onClick = onPrimary,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color(0xFF561E19)),
                ) { Text(primary) }
            }
        }
    }
}

/** A placeholder row that breathes while the real one is on its way. */
@Composable
private fun SkeletonRow(width: Int, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Row(
        modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp).alpha(pulse),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).background(DrawerChip, RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(width.dp).height(12.dp).background(DrawerChip, RoundedCornerShape(6.dp)))
    }
}

@Composable
private fun ProjectRow(
    name: String,
    count: Int,
    open: Boolean,
    dimmed: Boolean,
    onToggle: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(onClick = onToggle, shape = RoundedCornerShape(24.dp), color = Color.Transparent, modifier = modifier.fillMaxWidth().alpha(if (dimmed) 0.6f else 1f)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = if (open) "Close $name" else "Open $name",
                tint = DrawerMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count > 0) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.background(DrawerChip, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Outlined.Add, contentDescription = "New chat in $name") }
        }
    }
}

@Composable
private fun PcChatRow(entry: PcChatEntry, selected: Boolean, running: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(26.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().padding(start = 28.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) StatusDot(color = Color(0xFF69A7FF), size = 8.dp, pulsing = true, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title.ifBlank { "Untitled chat" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry is PcChatEntry.OnComputer) {
                        Icon(Icons.Outlined.Computer, contentDescription = null, tint = DrawerMuted, modifier = Modifier.size(12.dp))
                    }
                    Text(
                        buildString {
                            if (entry is PcChatEntry.OnComputer) append("On the computer · ")
                            append(formatSessionTime(entry.updatedAt))
                            if (running) append(" · running")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f) else DrawerMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuietRow(
    text: String,
    icon: ImageVector? = null,
    indent: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = if (indent) 44.dp else 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = PcReady, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = PcReady)
        }
    }
}

/**
 * Tailscale SSH answered instead of the computer's own SSH server: not an
 * error, a step. It signs in with the user's Tailscale account, so the
 * user approves once in the browser and connects again.
 */
@Composable
internal fun TailscaleApprovalCard(
    computer: String,
    url: String?,
    onOpen: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = PcReady, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Approve this phone in Tailscale", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (url != null) {
                    "$computer signs in with your Tailscale account instead of a password. Approve once on the Tailscale page; coming back here connects."
                } else {
                    "$computer signs in with your Tailscale account instead of a password, and its Tailscale rules do not let this phone in yet."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, start = 34.dp),
            )
            // Stacked, full width: side by side they squeezed each other on a phone.
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (url != null) {
                    Button(
                        onClick = { onOpen(url) },
                        colors = ButtonDefaults.buttonColors(containerColor = PcReady, contentColor = Color(0xFF003731)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open approval page", maxLines = 1)
                    }
                }
                TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(if (url != null) "Already approved? Connect" else "Connect again", color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
                }
            }
            Text(
                "Rather use a password? On the computer: sudo tailscale set --ssh=false",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp, start = 34.dp),
            )
        }
    }
}
