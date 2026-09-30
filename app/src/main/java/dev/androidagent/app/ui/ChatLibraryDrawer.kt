package dev.androidagent.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.ChatDayGroups
import dev.androidagent.core.ChatSession
import dev.androidagent.core.SetupChecklist
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteSetup

// A warm, quiet ground: the list is the content, the chrome recedes. One
// accent (amber) says "live"; teal only marks a reachable computer.
internal val LibraryGround = Color(0xFF141413)
private val LibraryRail = Color(0xFF0F0F0E)
private val LibraryLine = Color(0xFF232220)
private val LibraryTile = Color(0xFF1F1E1C)
private val LibraryRaised = Color(0xFF252422)
private val LibraryLight = Color(0xFFECEBE6)
private val LibraryMuted = Color(0xFF8E8C85)
private val LibraryFaint = Color(0xFF6C6A64)
private val LibraryAmber = Color(0xFFE8C9A0)
private val LibraryTeal = Color(0xFF7FC8B6)
private val LibraryOffline = Color(0xFF55534E)
private const val CHATS_PER_PROJECT = 5

/**
 * The chat library. With computers, a rail on the left picks the place
 * (everything, this phone, or one computer) and the page beside it shows
 * that place: recent chats, or a computer's projects folding open over
 * their chats. With the phone alone there is no rail, just the chats.
 */
@Composable
internal fun ChatLibraryDrawer(state: AgentUiState, actions: AgentUiActions, close: () -> Unit) {
    var location by rememberSaveable { mutableStateOf(ChatLibrary.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val showAll = remember { mutableStateMapOf<String, Boolean>() }
    val sections = remember(state.computers, state.defaultComputerId, state.computerProjects, state.remoteBindings, state.sessions, state.pcThreads) {
        PcChats.sections(state.computers, state.defaultComputerId, state.computerProjects, state.remoteBindings, state.sessions, state.pcThreads)
    }
    val hasComputers = state.computers.isNotEmpty()
    val scope = location.takeIf { it in setOf(ChatLibrary.ALL, ChatLibrary.PHONE) || state.computers.any { c -> c.id == it } }
        ?.takeIf { hasComputers } ?: ChatLibrary.ALL
    val computer = state.computers.firstOrNull { it.id == scope }
    val chats = remember(state.sessions, state.remoteBindings, sections, scope, query) {
        ChatLibrary.chats(state.sessions, state.remoteBindings, sections, scope, null, query)
    }
    val projects = remember(sections, scope, query) { if (computer == null) emptyList() else ChatLibrary.projects(sections, scope, query) }
    var limit by remember(scope, query) { mutableStateOf(40) }
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    val listState = listStates.getOrPut(scope) { LazyListState() }
    val attention = SetupChecklist.outstanding(state.setupRows()) > 0
    val focus = LocalFocusManager.current
    val dismiss = { focus.clearFocus(); close() }
    fun choose(id: String) { focus.clearFocus(); location = id }
    val newChat = {
        dismiss()
        if (computer != null) actions.onNewProject(computer.id) else actions.onNewChat()
    }
    val automationSummary = when {
        state.automations.overview.blocked == 1 -> "1 needs you"
        state.automations.overview.blocked > 1 -> "${state.automations.overview.blocked} need you"
        state.automations.overview.enabled > 0 -> "${state.automations.overview.enabled} on"
        else -> "${state.automations.total} off"
    }

    CompositionLocalProvider(LocalContentColor provides LibraryLight) {
        Row(Modifier.fillMaxHeight().background(LibraryGround).imePadding()) {
            if (hasComputers) {
                DeviceRail(
                    state = state,
                    sections = sections.map { it.computer },
                    scope = scope,
                    attention = attention,
                    automationSummary = automationSummary,
                    onChoose = ::choose,
                    onAdd = { dismiss(); actions.onOpenComputers() },
                    onAutomations = { dismiss(); actions.onOpenAutomations() },
                    onFiles = { dismiss(); actions.onOpenWorkspaceFiles() },
                    onSettings = { dismiss(); actions.onOpenSettings() },
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LibraryHeader(
                    title = when {
                        !hasComputers -> "Chats"
                        scope == ChatLibrary.ALL -> "Everything"
                        scope == ChatLibrary.PHONE -> "This phone"
                        else -> computer?.label.orEmpty()
                    },
                    searching = searching,
                    onSearch = { searching = !searching; if (!searching) query = "" },
                    onClose = dismiss,
                )
                LibraryStatus(state, scope, computer, chats.size, sections.size)
                AnimatedVisibility(visible = searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        placeholder = { Text(if (computer != null) "Search ${computer.label}" else "Search chats") },
                        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }) else null,
                        shape = RoundedCornerShape(14.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = LibraryTile, unfocusedContainerColor = LibraryTile,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = LibraryAmber,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp)
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = "Search chats or projects" },
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag("chat-library"),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 16.dp),
                ) {
                    item(key = "new-chat", contentType = "new-chat") {
                        NewChatCard(
                            hint = when {
                                computer != null -> "pick a folder"
                                hasComputers -> "on this phone"
                                else -> null
                            },
                            onClick = newChat,
                        )
                    }
                    if (computer != null) {
                        item(key = "connection", contentType = "connection") { LibraryConnection(state, computer.id, actions, dismiss) }
                        item(key = "projects-label", contentType = "label") {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Caps("Projects", Modifier.weight(1f))
                                TextButton(onClick = { dismiss(); actions.onNewProject(computer.id) }) { Text("New project", color = LibraryAmber) }
                            }
                        }
                        if (projects.isEmpty() && state.computerSetup[computer.id] is RemoteSetup.Ready) item(key = "no-projects", contentType = "empty") {
                            LibraryEmpty(
                                icon = if (query.isNotBlank()) Icons.Outlined.SearchOff else Icons.Outlined.Folder,
                                title = if (query.isNotBlank()) "Nothing matches" else "No projects yet",
                                detail = if (query.isNotBlank()) "Try another name." else "Start a new chat to pick a folder on ${computer.label}.",
                            )
                        }
                        projects.forEachIndexed { index, project ->
                            val holdsCurrent = project.chats.any { (it as? PcChatEntry.Local)?.session?.id == state.activeSessionId }
                            val open = expanded[project.key] ?: (query.isNotBlank() || holdsCurrent || index == 0)
                            val matching = if (query.isBlank() || project.name.contains(query.trim(), true)) project.chats
                            else project.chats.filter { it.title.contains(query.trim(), true) }
                            item(key = project.key, contentType = "project") {
                                ProjectHeader(project.name, project.chats.size, open, Modifier.animateItem()) {
                                    focus.clearFocus(); expanded[project.key] = !open
                                }
                            }
                            if (open) {
                                val all = showAll[project.key] == true
                                val rows = if (all) matching else matching.take(CHATS_PER_PROJECT)
                                items(rows, key = { "${project.key}/${it.key}" }, contentType = { "chat" }) { entry ->
                                    Box(Modifier.animateItem().padding(start = 18.dp)) {
                                        LibraryChatRow(
                                            entry = entry,
                                            detail = if (entry is PcChatEntry.OnComputer) "In Codex on ${computer.label}" else "",
                                            leading = null,
                                            state = state,
                                            actions = actions,
                                            dismiss = dismiss,
                                            computerId = computer.id,
                                        )
                                    }
                                }
                                if (matching.size > CHATS_PER_PROJECT) item(key = "${project.key}/more", contentType = "quiet") {
                                    QuietLink(if (all) "Show fewer" else "Show all ${matching.size}", Modifier.animateItem().padding(start = 30.dp)) { showAll[project.key] = !all }
                                }
                                item(key = "${project.key}/new", contentType = "quiet") {
                                    QuietLink("New chat here", Modifier.animateItem().padding(start = 30.dp), icon = Icons.Outlined.Add) {
                                        dismiss(); actions.onNewChatInProject(project.computerId, project.path)
                                    }
                                }
                            }
                        }
                    } else {
                        if (state.isLoadingSessions) item(key = "loading", contentType = "loading") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LibraryAmber)
                                Spacer(Modifier.width(12.dp))
                                Text("Loading chats", style = MaterialTheme.typography.bodyMedium, color = LibraryMuted)
                            }
                        } else if (chats.isEmpty()) item(key = "empty", contentType = "empty") {
                            LibraryEmpty(
                                icon = if (query.isNotBlank()) Icons.Outlined.SearchOff else Icons.Outlined.Forum,
                                title = if (query.isNotBlank()) "Nothing matches" else "No chats yet",
                                detail = if (query.isNotBlank()) "Try another word." else "Start one with New chat.",
                            )
                        }
                        val visible = chats.take(limit)
                        val byKey = visible.associateBy { it.key }
                        val groups = ChatDayGroups.group(visible.map { ChatSession(it.key, it.entry.title, 0L, it.entry.updatedAt) }, System.currentTimeMillis())
                        groups.forEach { group ->
                            item(key = "day-${group.label}", contentType = "day") { Caps(group.label, Modifier.animateItem().padding(start = 12.dp, top = 18.dp, bottom = 4.dp)) }
                            items(group.sessions, key = { it.id }, contentType = { "chat" }) { stub ->
                                val row = byKey.getValue(stub.id)
                                val owner = state.computers.firstOrNull { it.id == row.computerId }
                                // Only what the title does not already say: the
                                // day headings carry the time, and a line under
                                // every row doubled its height for a clock.
                                val detail = listOfNotNull(
                                    if (scope == ChatLibrary.ALL && owner != null) row.project?.name?.takeIf { !it.equals(row.entry.title, true) } else null,
                                    if (row.entry is PcChatEntry.OnComputer) "In Codex" else null,
                                ).joinToString(" · ")
                                Box(Modifier.animateItem()) {
                                    LibraryChatRow(
                                        entry = row.entry,
                                        detail = detail,
                                        leading = if (scope == ChatLibrary.ALL && hasComputers) ({ PlaceBadge(owner) }) else null,
                                        state = state,
                                        actions = actions,
                                        dismiss = dismiss,
                                        computerId = row.computerId,
                                    )
                                }
                            }
                        }
                        if (chats.size > limit) item(key = "more", contentType = "quiet") {
                            QuietLink("Show more chats", Modifier.padding(start = 12.dp)) { limit += 40 }
                        }
                    }
                }

                // Without a rail these live at the bottom of the page instead.
                if (!hasComputers) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = LibraryLine)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.automations.total > 0) {
                            FooterLink(Icons.Outlined.Schedule, "Automations", Modifier.weight(1f).semantics { contentDescription = "Automations, $automationSummary" }, alert = state.automations.overview.needsAttention) { dismiss(); actions.onOpenAutomations() }
                        }
                        FooterLink(Icons.Outlined.Folder, "Files", Modifier.weight(1f)) { dismiss(); actions.onOpenWorkspaceFiles() }
                        FooterLink(Icons.Outlined.Settings, "Settings", Modifier.weight(1f).semantics { if (attention) contentDescription = "Settings, needs attention" }, alert = attention) { dismiss(); actions.onOpenSettings() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRail(
    state: AgentUiState,
    sections: List<RemoteComputer>,
    scope: String,
    attention: Boolean,
    automationSummary: String,
    onChoose: (String) -> Unit,
    onAdd: () -> Unit,
    onAutomations: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(LibraryRail).padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RailButton("All devices", scope == ChatLibrary.ALL, onClick = { onChoose(ChatLibrary.ALL) }) {
                Icon(Icons.Outlined.Layers, null, Modifier.size(22.dp))
            }
            Box(Modifier.width(28.dp).height(1.dp).background(LibraryLine))
            RailButton("This phone", scope == ChatLibrary.PHONE, onClick = { onChoose(ChatLibrary.PHONE) }) {
                Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(20.dp))
            }
            sections.forEach { computer ->
                val setup = state.computerSetup[computer.id]
                val busy = setup is RemoteSetup.Working || computer.id in state.pcRefreshing
                val dot = when {
                    busy -> LibraryAmber
                    setup is RemoteSetup.Ready -> LibraryTeal
                    else -> LibraryOffline
                }
                val status = when {
                    busy -> "connecting"
                    setup is RemoteSetup.Ready -> "connected"
                    else -> "not connected"
                }
                RailButton("${computer.label}, $status", scope == computer.id, dot = dot, label = computer.label, onClick = { onChoose(computer.id) }) {
                    Text(monogram(computer.label), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
            }
            Surface(
                onClick = onAdd,
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, LibraryLine),
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Manage computers" },
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = LibraryMuted) }
            }
        }
        RailIcon(Icons.Outlined.Schedule, "Automations, $automationSummary", alert = state.automations.overview.needsAttention, onClick = onAutomations)
        RailIcon(Icons.Outlined.Folder, "Files", onClick = onFiles)
        RailIcon(Icons.Outlined.Settings, if (attention) "Settings, needs attention" else "Settings", alert = attention, onClick = onSettings)
    }
}

/** A place on the rail: a circle that squares off when chosen, with its name under it. */
@Composable
private fun RailButton(
    description: String,
    selected: Boolean,
    dot: Color? = null,
    label: String? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val chosen = selected
    val corner by animateDpAsState(if (selected) 16.dp else 24.dp, label = "rail-corner")
    val fill by animateColorAsState(if (selected) LibraryLight else LibraryTile, label = "rail-fill")
    val ink by animateColorAsState(if (selected) LibraryGround else LibraryLight, label = "rail-ink")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(corner),
                color = fill,
                contentColor = ink,
                modifier = Modifier.size(48.dp).semantics { contentDescription = description; this.selected = chosen; role = Role.Tab },
            ) { Box(contentAlignment = Alignment.Center) { content() } }
            dot?.let {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(14.dp).background(LibraryRail, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(it, CircleShape))
                }
            }
        }
        label?.let {
            Text(
                it, style = MaterialTheme.typography.labelSmall, color = if (selected) LibraryLight else LibraryFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp).padding(top = 4.dp), textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RailIcon(icon: ImageVector, description: String, alert: Boolean = false, onClick: () -> Unit) {
    Box {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
            Icon(icon, null, Modifier.size(20.dp), tint = LibraryMuted)
        }
        if (alert) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(7.dp).background(MaterialTheme.colorScheme.error, CircleShape))
    }
}

@Composable
private fun LibraryHeader(title: String, searching: Boolean, onSearch: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            letterSpacing = (-0.4).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch) {
            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = if (searching) "Close search" else "Search")
        }
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close chats", tint = LibraryMuted) }
    }
}

/** One line under the title: how much is here, or how the computer is. */
@Composable
private fun LibraryStatus(state: AgentUiState, scope: String, computer: RemoteComputer?, chatCount: Int, computers: Int) {
    val (text, color) = when {
        computer != null -> when (val setup = state.computerSetup[computer.id]) {
            is RemoteSetup.Ready -> listOfNotNull(
                if (computer.id in state.pcRefreshing) "Refreshing" else "Connected",
                if (setup.route?.viaVpn == true) "over VPN" else null,
                computer.os?.label,
            ).joinToString(" · ") to (if (computer.id in state.pcRefreshing) LibraryAmber else LibraryTeal)
            is RemoteSetup.Working -> setup.step to LibraryAmber
            else -> "Not connected" to LibraryMuted
        }
        scope == ChatLibrary.ALL && computers > 0 -> "${computers + 1} places · $chatCount chats" to LibraryMuted
        else -> (if (chatCount == 1) "1 chat" else "$chatCount chats") to LibraryMuted
    }
    Row(Modifier.padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (computer != null) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NewChatCard(hint: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = LibraryLight,
        contentColor = LibraryGround,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditNote, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("New chat", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5B5953)) }
        }
    }
}

@Composable
private fun Caps(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = LibraryFaint,
        modifier = modifier,
    )
}

@Composable
private fun ProjectHeader(name: String, count: Int, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = LibraryLight, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, if (open) "Close $name" else "Open $name", Modifier.size(18.dp), tint = LibraryMuted)
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$count", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = LibraryFaint)
        }
    }
}

/** Which place a chat lives in, as a small monogram or a phone. */
@Composable
private fun PlaceBadge(computer: RemoteComputer?) {
    Box(Modifier.size(28.dp).background(LibraryTile, CircleShape), contentAlignment = Alignment.Center) {
        if (computer == null) Icon(Icons.Outlined.PhoneAndroid, "This phone", Modifier.size(14.dp), tint = LibraryMuted)
        else Text(monogram(computer.label), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.semantics { contentDescription = computer.label })
    }
}

@Composable
private fun LibraryChatRow(
    entry: PcChatEntry,
    detail: String,
    leading: (@Composable () -> Unit)?,
    state: AgentUiState,
    actions: AgentUiActions,
    dismiss: () -> Unit,
    computerId: String?,
) {
    val local = (entry as? PcChatEntry.Local)?.session
    if (local != null) {
        val running = state.runState.active && local.id == state.runState.sessionId
        SessionRow(
            session = local, onComputer = false, selected = local.id == state.activeSessionId,
            running = running,
            onSelect = { dismiss(); actions.onSelectSession(local.id) },
            onRename = { actions.onRenameSession(local.id, it) },
            onDelete = { actions.onDeleteSession(local.id) },
            subtitle = if (running) listOf("Running", detail).filter { it.isNotBlank() }.joinToString(" · ") else detail.ifBlank { null },
            leading = leading,
            subtitleColor = if (running) LibraryAmber else null,
        )
    } else {
        Surface(
            onClick = { dismiss(); actions.onOpenPcThread(computerId!!, (entry as PcChatEntry.OnComputer).thread.id) },
            color = Color.Transparent, contentColor = LibraryLight, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                leading?.let { it(); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { "Untitled chat" }, style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotBlank()) {
                        Text(detail, style = MaterialTheme.typography.labelSmall, fontSize = 12.sp, color = LibraryMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuietLink(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = LibraryAmber, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LibraryEmpty(icon: ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).border(1.dp, LibraryLine, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LibraryMuted, modifier = Modifier.size(20.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = LibraryMuted, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun FooterLink(icon: ImageVector, label: String, modifier: Modifier = Modifier, alert: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = LibraryLight, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = LibraryMuted)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (alert) { Spacer(Modifier.width(6.dp)); StatusDot(MaterialTheme.colorScheme.error, size = 6.dp) }
        }
    }
}

@Composable
private fun LibraryConnection(state: AgentUiState, id: String, actions: AgentUiActions, close: () -> Unit) {
    val setup = state.computerSetup[id]
    Box(Modifier.padding(horizontal = 4.dp).padding(top = 10.dp)) {
        when (setup) {
            is RemoteSetup.Working -> LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = LibraryAmber, trackColor = LibraryTile)
            is RemoteSetup.Failed -> PcProblemCard(message = setup.message, primary = "Try again", onPrimary = { actions.onReconnectComputer(id) }, secondary = "Settings", onSecondary = { close(); actions.onOpenComputers() }, modifier = Modifier.padding(0.dp))
            is RemoteSetup.NeedsTailscaleApproval -> TailscaleApprovalCard(state.computers.first { it.id == id }.label, setup.url, { actions.onOpenTailscaleApproval(id, it) }, { actions.onReconnectComputer(id) })
            is RemoteSetup.Ready -> Unit
            null -> QuietLink("Connect to load its projects", icon = Icons.Outlined.Computer) { actions.onReconnectComputer(id) }
        }
    }
}

/** First letter of a computer's name, for the rail and the badges. */
internal fun monogram(label: String): String = label.trim().firstOrNull()?.uppercase() ?: "?"
