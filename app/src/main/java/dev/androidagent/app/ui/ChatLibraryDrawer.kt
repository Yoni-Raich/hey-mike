package dev.androidagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.core.ChatDayGroups
import dev.androidagent.core.ChatSession
import dev.androidagent.core.SetupChecklist
import dev.androidagent.remote.RemoteSetup

// The drawer's own quiet palette on the lifted surface: a filled field for
// search, a tonal tile behind project icons, teal for "reachable".
private val LibraryField = Color(0xFF26262A)
private val LibraryTile = Color(0xFF2A2A2E)
private val LibraryTeal = Color(0xFF83D9CA)
private val LibraryAmber = Color(0xFFF6B86A)
private val LibraryOffline = Color(0xFF6B6B70)

/**
 * Recent chats first. Browse one device or project without unfolding a tree.
 *
 * Layout, top to bottom: the title, one primary "New chat", a filled search
 * field, device chips (only once there is a computer), a Recent/Projects
 * switch, then the list. Everything under the title scrolls with the list,
 * so a short or sideways screen still shows chats.
 */
@Composable
internal fun ChatLibraryDrawer(state: AgentUiState, actions: AgentUiActions, close: () -> Unit) {
    var location by rememberSaveable { mutableStateOf(ChatLibrary.ALL) }
    var projectsTab by rememberSaveable { mutableStateOf(false) }
    var projectKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectSearch by rememberSaveable { mutableStateOf("") }
    val sections = remember(state.computers, state.defaultComputerId, state.computerProjects, state.remoteBindings, state.sessions, state.pcThreads) {
        PcChats.sections(state.computers, state.defaultComputerId, state.computerProjects, state.remoteBindings, state.sessions, state.pcThreads)
    }
    val hasComputers = state.computers.isNotEmpty()
    val scope = location.takeIf { it in setOf(ChatLibrary.ALL, ChatLibrary.PHONE) || state.computers.any { computer -> computer.id == it } } ?: ChatLibrary.ALL
    val computer = state.computers.firstOrNull { it.id == scope }
    val project = sections.flatMap { it.projects }.firstOrNull { it.key == projectKey }
    val showProjects = projectsTab && project == null && scope != ChatLibrary.PHONE
    val chats = remember(state.sessions, state.remoteBindings, sections, scope, project, query) {
        ChatLibrary.chats(state.sessions, state.remoteBindings, sections, scope, project?.key, query)
    }
    val projects = remember(sections, scope, query) { ChatLibrary.projects(sections, scope, query) }
    var limit by remember(scope, projectKey, query, showProjects) { mutableIntStateOf(40) }
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    val listState = listStates.getOrPut("$scope/$projectKey/$showProjects/$query") { LazyListState() }
    fun backToProjects() { projectKey = null; projectsTab = true; query = projectSearch }
    BackHandler(enabled = state.isDrawerOpen && project != null) { backToProjects() }
    val attention = SetupChecklist.outstanding(state.setupRows()) > 0
    val focus = LocalFocusManager.current
    val dismiss = { focus.clearFocus(); close() }
    val newChat = {
        dismiss()
        when {
            project != null -> actions.onNewChatInProject(project.computerId, project.path)
            computer != null -> actions.onNewProject(computer.id)
            else -> actions.onNewChat()
        }
    }
    fun choose(id: String) { focus.clearFocus(); location = id; projectKey = null; if (id == ChatLibrary.PHONE) projectsTab = false }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxHeight().imePadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close chats") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag("chat-library"),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "new-chat", contentType = "new-chat") {
                    Button(
                        onClick = newChat,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New chat", style = MaterialTheme.typography.labelLarge)
                    }
                }
                item(key = "search", contentType = "search") {
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        placeholder = { Text(if (showProjects) "Search projects" else "Search chats") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }) else null,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = LibraryField,
                            unfocusedContainerColor = LibraryField,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)
                            .semantics { contentDescription = "Search chats or projects" },
                    )
                }
                // Only a computer makes "where" a question worth a row.
                if (hasComputers) item(key = "devices", contentType = "devices") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        item { DeviceChip("All devices", scope == ChatLibrary.ALL) { choose(ChatLibrary.ALL) } }
                        item { DeviceChip("This phone", scope == ChatLibrary.PHONE, icon = Icons.Outlined.PhoneAndroid) { choose(ChatLibrary.PHONE) } }
                        items(sections, key = { it.computer.id }) { section ->
                            val setup = state.computerSetup[section.computer.id]
                            DeviceChip(
                                section.computer.label,
                                scope == section.computer.id,
                                icon = Icons.Outlined.Computer,
                                dot = when {
                                    setup is RemoteSetup.Working || section.computer.id in state.pcRefreshing -> LibraryAmber
                                    setup is RemoteSetup.Ready -> LibraryTeal
                                    else -> LibraryOffline
                                },
                            ) { choose(section.computer.id) }
                        }
                        item {
                            IconButton(onClick = { dismiss(); actions.onOpenComputers() }) {
                                Icon(Icons.Outlined.Tune, contentDescription = "Manage computers", tint = DrawerMuted)
                            }
                        }
                    }
                }
                if (project == null && scope != ChatLibrary.PHONE && hasComputers) item(key = "tabs", contentType = "tabs") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
                        SegmentedButton(
                            selected = !showProjects, onClick = { projectsTab = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = {}, label = { Text("Recent chats", maxLines = 1) },
                        )
                        SegmentedButton(
                            selected = showProjects, onClick = { projectsTab = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = {}, label = { Text("Projects", maxLines = 1) },
                        )
                    }
                }
                if (project != null) item(key = "project-heading", contentType = "heading") {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { backToProjects() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to projects") }
                        Column(Modifier.weight(1f)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.computers.firstOrNull { it.id == project.computerId }?.label.orEmpty(), style = MaterialTheme.typography.bodySmall, color = DrawerMuted)
                        }
                    }
                }
                computer?.let { selected -> item(key = "connection", contentType = "connection") { LibraryConnection(state, selected.id, actions, dismiss) } }
                if (state.isLoadingSessions) item(key = "loading", contentType = "loading") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LibraryTeal)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading chats", style = MaterialTheme.typography.bodyMedium, color = DrawerMuted)
                    }
                } else if (showProjects) {
                    if (projects.isEmpty()) item(key = "empty-projects", contentType = "empty") {
                        LibraryEmpty(
                            icon = if (query.isNotBlank()) Icons.Outlined.SearchOff else Icons.Outlined.Folder,
                            title = if (query.isNotBlank()) "No matching projects" else "No projects yet",
                            detail = if (query.isNotBlank()) "Try another name." else "Pick a computer and start a new chat to choose a folder.",
                        )
                    }
                    item(key = "projects-top", contentType = "spacer") { Spacer(Modifier.height(6.dp)) }
                    items(projects, key = { it.key }, contentType = { "project" }) { row ->
                        val owner = state.computers.firstOrNull { it.id == row.computerId }
                        ProjectRow(
                            name = row.name,
                            detail = listOfNotNull(
                                owner?.label.takeIf { scope == ChatLibrary.ALL },
                                if (row.chats.size == 1) "1 chat" else "${row.chats.size} chats",
                            ).joinToString(" · "),
                            modifier = Modifier.animateItem(),
                        ) { focus.clearFocus(); projectSearch = query; query = ""; projectKey = row.key }
                    }
                } else {
                    if (chats.isEmpty()) item(key = "empty-chats", contentType = "empty") {
                        LibraryEmpty(
                            icon = if (query.isNotBlank()) Icons.Outlined.SearchOff else Icons.Outlined.Forum,
                            title = if (query.isNotBlank()) "No matching chats" else "No chats here yet",
                            detail = if (query.isNotBlank()) "Try another name or choose All devices." else "Start a new chat when you are ready.",
                        )
                    }
                    val visible = chats.take(limit)
                    val rows = visible.associateBy { it.key }
                    val groups = ChatDayGroups.group(visible.map { ChatSession(it.key, it.entry.title, 0L, it.entry.updatedAt) }, System.currentTimeMillis())
                    groups.forEach { group ->
                        item(key = "day-${group.label}", contentType = "day") { DrawerSectionLabel(group.label, Modifier.animateItem().padding(top = 10.dp)) }
                        items(group.sessions, key = { it.id }, contentType = { "chat" }) { stub ->
                            val row = rows.getValue(stub.id)
                            val owner = state.computers.firstOrNull { it.id == row.computerId }
                            // Where a chat lives is worth saying only when there is more than one place.
                            val where = when {
                                project != null || !hasComputers -> null
                                row.computerId == null -> if (scope == ChatLibrary.ALL) "This phone" else null
                                else -> listOfNotNull(owner?.label.takeIf { scope == ChatLibrary.ALL }, row.project?.name).joinToString(" · ").ifBlank { null }
                            }
                            val whereIcon = if (where == null) null else if (row.computerId == null) Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer
                            val local = (row.entry as? PcChatEntry.Local)?.session
                            Box(Modifier.animateItem()) {
                                if (local != null) {
                                    SessionRow(
                                        session = local, onComputer = false, selected = local.id == state.activeSessionId,
                                        running = state.runState.active && local.id == state.runState.sessionId,
                                        onSelect = { dismiss(); actions.onSelectSession(local.id) },
                                        onRename = { actions.onRenameSession(local.id, it) },
                                        onDelete = { actions.onDeleteSession(local.id) },
                                        subtitle = where, subtitleIcon = whereIcon,
                                    )
                                } else {
                                    CodexThreadRow(
                                        title = row.entry.title,
                                        detail = listOfNotNull(where, "In Codex on the computer").joinToString(" · "),
                                    ) { dismiss(); actions.onOpenPcThread(row.computerId!!, (row.entry as PcChatEntry.OnComputer).thread.id) }
                                }
                            }
                        }
                    }
                    if (chats.size > limit) item(key = "more", contentType = "more") {
                        TextButton(onClick = { limit += 40 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show more chats", color = LibraryTeal) }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = LibraryTile)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (state.automations.total > 0) {
                    val summary = when {
                        state.automations.overview.blocked == 1 -> "1 needs you"
                        state.automations.overview.blocked > 1 -> "${state.automations.overview.blocked} need you"
                        state.automations.overview.enabled > 0 -> "${state.automations.overview.enabled} on"
                        else -> "${state.automations.total} off"
                    }
                    FooterRow(
                        icon = Icons.Outlined.Schedule,
                        label = "Automations",
                        trailing = summary,
                        alert = state.automations.overview.needsAttention,
                        modifier = Modifier.semantics { contentDescription = "Automations, $summary" },
                    ) { dismiss(); actions.onOpenAutomations() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FooterRow(Icons.Outlined.Folder, "Files", modifier = Modifier.weight(1f)) { dismiss(); actions.onOpenWorkspaceFiles() }
                    FooterRow(
                        Icons.Outlined.Settings, "Settings", alert = attention,
                        modifier = Modifier.weight(1f).semantics { if (attention) contentDescription = "Settings, needs attention" },
                    ) { dismiss(); actions.onOpenSettings() }
                }
            }
        }
    }
}

/** One device in the "where" row: selected is filled, others outlined; computers carry their state as a dot. */
@Composable
private fun DeviceChip(label: String, selected: Boolean, icon: ImageVector? = null, dot: Color? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (dot != null) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    icon?.let { Icon(it, null, Modifier.size(18.dp)) }
                    Box(Modifier.align(Alignment.BottomEnd).size(7.dp).background(dot, CircleShape))
                }
            } else icon?.let { Icon(it, null, Modifier.size(18.dp)) }
        },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurface,
            iconColor = DrawerMuted,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = LibraryTile, selectedBorderColor = Color.Transparent),
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

@Composable
private fun ProjectRow(name: String, detail: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(LibraryTile, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Folder, null, tint = LibraryTeal, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = DrawerMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, "Open $name", tint = DrawerMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** A conversation only Codex on the computer has so far: opening it brings it here. */
@Composable
private fun CodexThreadRow(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.Center) {
            Text(title.ifBlank { "Untitled chat" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Computer, null, tint = DrawerMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(detail, style = MaterialTheme.typography.labelSmall, color = DrawerMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LibraryEmpty(icon: ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(LibraryTile, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = DrawerMuted, modifier = Modifier.size(24.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp), textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = DrawerMuted, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun FooterRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    alert: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = trailing != null), maxLines = 1)
            trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = DrawerMuted, maxLines = 1) }
            if (alert) { Spacer(Modifier.width(8.dp)); StatusDot(MaterialTheme.colorScheme.error, size = 7.dp) }
        }
    }
}

@Composable
private fun LibraryConnection(state: AgentUiState, id: String, actions: AgentUiActions, close: () -> Unit) {
    val setup = state.computerSetup[id]
    Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        when (setup) {
            is RemoteSetup.Working -> Column(Modifier.padding(horizontal = 4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = LibraryTeal, trackColor = LibraryTile)
                Text(setup.step, style = MaterialTheme.typography.bodySmall, color = DrawerMuted, modifier = Modifier.padding(top = 8.dp))
            }
            is RemoteSetup.Failed -> PcProblemCard(message = setup.message, primary = "Try again", onPrimary = { actions.onReconnectComputer(id) }, secondary = "Settings", onSecondary = { close(); actions.onOpenComputers() }, modifier = Modifier.padding(0.dp))
            is RemoteSetup.NeedsTailscaleApproval -> TailscaleApprovalCard(state.computers.first { it.id == id }.label, setup.url, { actions.onOpenTailscaleApproval(id, it) }, { actions.onReconnectComputer(id) })
            is RemoteSetup.NeedsSignIn -> PcProblemCard("Codex needs a sign-in on this computer.", "Sign in", { close(); actions.onOpenComputers() })
            is RemoteSetup.Ready -> Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val refreshing = id in state.pcRefreshing
                if (refreshing) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = LibraryAmber)
                else Box(Modifier.size(7.dp).background(LibraryTeal, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (refreshing) "Refreshing chats…" else "Connected${if (setup.route?.viaVpn == true) " over VPN" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (refreshing) LibraryAmber else LibraryTeal,
                )
            }
            null -> QuietRow("Connect to load chats", Icons.Outlined.Computer) { actions.onReconnectComputer(id) }
        }
    }
}
