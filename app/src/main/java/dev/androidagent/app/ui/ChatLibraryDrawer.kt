package dev.androidagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.core.ChatSession
import dev.androidagent.core.ChatDayGroups
import dev.androidagent.core.SetupChecklist
import dev.androidagent.remote.RemoteSetup

/** Recent chats first. Browse one device or project without unfolding a tree. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatLibraryDrawer(state: AgentUiState, actions: AgentUiActions, close: () -> Unit) {
    var location by rememberSaveable { mutableStateOf(ChatLibrary.ALL) }
    var projectsTab by rememberSaveable { mutableStateOf(false) }
    var projectKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectSearch by rememberSaveable { mutableStateOf("") }
    var chooserOpen by remember { mutableStateOf(false) }
    val sections = remember(state.computers, state.defaultComputerId, state.computerProjects, state.remoteBindings, state.sessions, state.pcThreads) {
        PcChats.sections(state.computers, state.defaultComputerId, state.computerProjects, state.remoteBindings, state.sessions, state.pcThreads)
    }
    val scope = location.takeIf { it in setOf(ChatLibrary.ALL, ChatLibrary.PHONE) || state.computers.any { computer -> computer.id == it } } ?: ChatLibrary.ALL
    val computer = state.computers.firstOrNull { it.id == scope }
    val project = sections.flatMap { it.projects }.firstOrNull { it.key == projectKey }
    val title = when (scope) { ChatLibrary.ALL -> "All devices"; ChatLibrary.PHONE -> "This phone"; else -> computer?.label.orEmpty() }
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
    val largeText = LocalDensity.current.fontScale > 1.3f
    val dismiss = { focus.clearFocus(); close() }
    val newChat = {
        dismiss()
        when {
            project != null -> actions.onNewChatInProject(project.computerId, project.path)
            computer != null -> actions.onNewProject(computer.id)
            else -> actions.onNewChat()
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxHeight().imePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!largeText) LibraryNewChat(newChat)
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close chats") }
            }
            if (largeText) LibraryNewChat(newChat, Modifier.fillMaxWidth().padding(bottom = 8.dp))

            // Filters scroll with the list on short screens; the title and exit stay reachable.
            LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("chat-library"), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "location") {
                    Box {
                        OutlinedButton(onClick = { chooserOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(if (scope == ChatLibrary.PHONE) Icons.Outlined.PhoneAndroid else Icons.Outlined.Devices, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ExpandMore, "Choose device")
                        }
                        DropdownMenu(expanded = chooserOpen, onDismissRequest = { chooserOpen = false }) {
                            fun choose(id: String) { focus.clearFocus(); location = id; projectKey = null; if (id == ChatLibrary.PHONE) projectsTab = false; chooserOpen = false }
                            DropdownMenuItem(text = { Text("All devices") }, leadingIcon = { Icon(Icons.Outlined.Devices, null) }, onClick = { choose(ChatLibrary.ALL) })
                            DropdownMenuItem(text = { Text("This phone") }, leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null) }, onClick = { choose(ChatLibrary.PHONE) })
                            sections.forEach { section ->
                                DropdownMenuItem(text = {
                                    Column {
                                        Text(section.computer.label, fontWeight = if (scope == section.computer.id) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(if (state.computerSetup[section.computer.id] is RemoteSetup.Ready) "Connected" else "Not connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }, leadingIcon = { Icon(Icons.Outlined.Computer, null) }, onClick = { choose(section.computer.id) })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Manage computers") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { chooserOpen = false; dismiss(); actions.onOpenComputers() })
                        }
                    }
                }
                if (project != null) item(key = "project-heading") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { backToProjects() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to projects") }
                        Column(Modifier.weight(1f)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.computers.firstOrNull { it.id == project.computerId }?.label.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "search") {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        placeholder = { Text(if (showProjects) "Search projects" else "Search chats") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }) else null,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chats or projects" })
                }
                if (project == null && scope != ChatLibrary.PHONE && state.computers.isNotEmpty()) item(key = "tabs") {
                    SecondaryTabRow(selectedTabIndex = if (showProjects) 1 else 0, containerColor = Color.Transparent, divider = {}) {
                        Tab(selected = !showProjects, onClick = { projectsTab = false }, text = { Text("Recent chats") })
                        Tab(selected = showProjects, onClick = { projectsTab = true }, text = { Text("Projects") })
                    }
                }
                computer?.let { selected -> item(key = "connection") { LibraryConnection(state, selected.id, actions, dismiss) } }
                if (state.isLoadingSessions) item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text("Loading chats")
                    }
                } else if (showProjects) {
                    if (projects.isEmpty()) item(key = "empty-projects") {
                        LibraryEmpty(if (query.isNotBlank()) "No matching projects" else "No projects yet", if (query.isNotBlank()) "Try another name." else "Choose a computer and start a new chat to pick a folder.")
                    }
                    items(projects, key = { it.key }, contentType = { "project" }) { row ->
                        val owner = state.computers.firstOrNull { it.id == row.computerId }
                        Surface(onClick = { focus.clearFocus(); projectSearch = query; query = ""; projectKey = row.key }, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(owner?.label.takeIf { scope == ChatLibrary.ALL }, if (row.chats.size == 1) "1 chat" else "${row.chats.size} chats").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Outlined.ChevronRight, "Open ${row.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                } else {
                    if (chats.isEmpty()) item(key = "empty-chats") {
                        LibraryEmpty(if (query.isNotBlank()) "No matching chats" else "No chats here yet", if (query.isNotBlank()) "Try another name or choose All devices." else "Start a new chat when you are ready.")
                    }
                    val visible = chats.take(limit)
                    val rows = visible.associateBy { it.key }
                    val groups = ChatDayGroups.group(visible.map { ChatSession(it.key, it.entry.title, 0L, it.entry.updatedAt) }, System.currentTimeMillis())
                    groups.forEach { group ->
                        item(key = "day-${group.label}", contentType = "day") { DrawerSectionLabel(group.label, Modifier.padding(top = 8.dp)) }
                        items(group.sessions, key = { it.id }, contentType = { "chat" }) { stub ->
                            val row = rows.getValue(stub.id)
                            val owner = state.computers.firstOrNull { it.id == row.computerId }
                            val subtitle = if (project != null) null else if (row.computerId == null) {
                                if (scope == ChatLibrary.ALL) "This phone" else null
                            } else listOfNotNull(owner?.label.takeIf { scope == ChatLibrary.ALL }, row.project?.name).joinToString(" · ")
                            val local = (row.entry as? PcChatEntry.Local)?.session
                            if (local != null) SessionRow(session = local, onComputer = false, selected = local.id == state.activeSessionId,
                                running = state.runState.active && local.id == state.runState.sessionId,
                                onSelect = { dismiss(); actions.onSelectSession(local.id) }, onRename = { actions.onRenameSession(local.id, it) }, onDelete = { actions.onDeleteSession(local.id) }, subtitle = subtitle)
                            else Surface(onClick = { dismiss(); actions.onOpenPcThread(row.computerId!!, (row.entry as PcChatEntry.OnComputer).thread.id) }, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Text(row.entry.title.ifBlank { "Untitled chat" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(subtitle, "From Codex").joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (chats.size > limit) item(key = "more") {
                        TextButton(onClick = { limit += 40 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show more chats") }
                    }
                }
            }
            HorizontalDivider()
            if (state.automations.total > 0) {
                val summary = when {
                    state.automations.overview.blocked == 1 -> "1 needs you"
                    state.automations.overview.blocked > 1 -> "${state.automations.overview.blocked} need you"
                    state.automations.overview.enabled > 0 -> "${state.automations.overview.enabled} on"
                    else -> "${state.automations.total} off"
                }
                TextButton(onClick = { dismiss(); actions.onOpenAutomations() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Automations, $summary" }) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(12.dp))
                    Text("Automations", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.automations.total.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.automations.overview.needsAttention) { Spacer(Modifier.width(8.dp)); StatusDot(MaterialTheme.colorScheme.error, size = 6.dp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { dismiss(); actions.onOpenWorkspaceFiles() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Files")
                }
                TextButton(onClick = { dismiss(); actions.onOpenSettings() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { if (attention) contentDescription = "Settings, needs attention" }) {
                    Icon(Icons.Outlined.Settings, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Settings")
                    if (attention) { Spacer(Modifier.width(6.dp)); StatusDot(MaterialTheme.colorScheme.error, size = 6.dp) }
                }
            }
        }
    }
}

@Composable
private fun LibraryNewChat(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp), modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("New chat")
    }
}

@Composable
private fun LibraryEmpty(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun LibraryConnection(state: AgentUiState, id: String, actions: AgentUiActions, close: () -> Unit) {
    val setup = state.computerSetup[id]
    when (setup) {
        is RemoteSetup.Working -> Column(Modifier.padding(vertical = 8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(setup.step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        is RemoteSetup.Failed -> PcProblemCard(message = setup.message, primary = "Try again", onPrimary = { actions.onReconnectComputer(id) }, secondary = "Settings", onSecondary = { close(); actions.onOpenComputers() })
        is RemoteSetup.NeedsTailscaleApproval -> TailscaleApprovalCard(state.computers.first { it.id == id }.label, setup.url, { actions.onOpenTailscaleApproval(id, it) }, { actions.onReconnectComputer(id) })
        is RemoteSetup.NeedsSignIn -> PcProblemCard("Codex needs a sign-in on this computer.", "Sign in", { close(); actions.onOpenComputers() })
        is RemoteSetup.Ready -> Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Text(if (id in state.pcRefreshing) "Refreshing chats…" else "Connected${if (setup.route?.viaVpn == true) " · VPN" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        null -> QuietRow("Connect to load chats", Icons.Outlined.Computer) { actions.onReconnectComputer(id) }
    }
}
