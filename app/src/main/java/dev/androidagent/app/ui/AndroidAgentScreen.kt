package dev.androidagent.app.ui


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.EngineEvent
import dev.androidagent.app.update.AppUpdateInfo
import dev.androidagent.app.update.UpdateStatus
import dev.androidagent.core.RunPhase
import dev.androidagent.core.SetupChecklist
import dev.androidagent.core.UsageSummary
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Disabled fill and ink for the composer's circular buttons. */
internal val DisabledFill = Color(0xFF444444)
internal val DisabledInk = Color(0xFF999999)

/** Longest approval payload shown before it is folded behind "Show all". */
private const val APPROVAL_DETAIL_LIMIT = 600

private val AgentDarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F4),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF252525),
    onPrimaryContainer = Color(0xFFF4F4F4),
    secondary = Color(0xFF83D9CA),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF174E47),
    onSecondaryContainer = Color(0xFFA1F2E1),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF202020),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFAAAAAA),
    // Used by the approval card and the one-time-code box. Without them the
    // scheme fell through to the Material baseline purple.
    tertiaryContainer = Color(0xFF1E3A34),
    onTertiaryContainer = Color(0xFFCDEFE5),
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF2B1D1B),
    onErrorContainer = Color(0xFFFFDAD6),
)


@Composable
fun AndroidAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgentDarkColors,
        typography = androidx.compose.material3.Typography().copy(
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 27.sp, textDirection = TextDirection.Content),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp, textDirection = TextDirection.Content),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidAgentScreen(
    state: AgentUiState,
    actions: AgentUiActions,
    // Read once per frame by the voice-mode sphere. A reader rather than a
    // field of [state], so a level change never recomposes the screen.
    voiceLevel: () -> Float = { 0f },
) {
    AndroidAgentTheme {
        val drawerState = rememberDrawerState(
            initialValue = if (state.isDrawerOpen) DrawerValue.Open else DrawerValue.Closed,
        )
        val scope = rememberCoroutineScope()

        LaunchedEffect(state.isDrawerOpen) {
            if (state.isDrawerOpen) drawerState.open() else drawerState.close()
        }
        LaunchedEffect(drawerState) {
            snapshotFlow { drawerState.currentValue }
                .collectLatest { actions.onDrawerChanged(it == DrawerValue.Open) }
        }

        val voiceMode = rememberVoiceModeMotion(voiceModeShown(state.shownVoice()))

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !voiceMode.shown,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 360.dp),
                ) {
                    AgentDrawer(
                        state = state,
                        actions = actions,
                        close = { scope.launch { drawerState.close() } },
                    )
                }
            },
        ) {
            val snackbars = remember { SnackbarHostState() }
            // Progress and confirmations used to be list items, which the
            // follow-the-latest scroll pushed out of sight as soon as they
            // arrived. A snackbar also replaces itself, which a list cannot.
            LaunchedEffect(state.infoMessage) {
                val message = state.infoMessage ?: return@LaunchedEffect
                snackbars.showSnackbar(message)
                actions.onDismissInfo()
            }
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    // The chat stays composed under voice mode; keep it out
                    // of touch exploration while it is off screen.
                    .then(if (voiceMode.shown) Modifier.clearAndSetSemantics { } else Modifier),
                contentWindowInsets = WindowInsets.safeDrawing,
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbars) },
                topBar = {
                    Box(Modifier.voiceStage(voiceMode.topBar, lift = (-8).dp)) {
                        ChatTopBar(
                            state = state,
                            actions = actions,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                        )
                    }
                },
                bottomBar = {
                    Column {
                        // Pinned above the composer, never in the chat list: the
                        // list follows the newest message, and a card placed in it
                        // sat above everything, out of sight in any long chat.
                        state.runState.approval?.let { approval ->
                            ApprovalCard(
                                approval = approval,
                                onApproval = actions.onApproval,
                                onApproveAlways = actions.onApproveAlways,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        Box(Modifier.voiceStage(voiceMode.composer, lift = 56.dp)) {
                            AgentComposer(
                                state = state,
                                actions = actions,
                                onVoiceButtonPlaced = { voiceMode.dock.value = it },
                            )
                        }
                    }
                },
            ) { padding ->
                AgentChatContent(
                    state = state,
                    actions = actions,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .voiceStage(voiceMode.chat, lift = (-32).dp, scaleFrom = 0.97f, blur = 10.dp),
                )
            }
            VoiceModeLayer(motion = voiceMode, state = state, actions = actions, voiceLevel = voiceLevel)
        }

        if (state.isSettingsOpen) {
            AgentSettingsSheet(state = state, actions = actions)
        }
        if (state.isAutomationsOpen) {
            AutomationsSheet(state = state, actions = actions)
        }
        if (state.isWorkspaceOpen) {
            WorkspaceFilesSheet(state = state, actions = actions)
        }
    }
}

@Composable
private fun AgentDrawer(
    state: AgentUiState,
    actions: AgentUiActions,
    close: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hey Mike", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Local Codex workspace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = close) {
                Icon(Icons.Outlined.Close, contentDescription = "Close sessions")
            }
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                close()
                actions.onNewChat()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New chat")
        }

        // Above the chats, because the question this panel is opened with is
        // often "is the standing stuff still working", and that has to be
        // answered before anyone reads a list.
        Spacer(Modifier.height(20.dp))
        AutomationStrip(
            overview = state.automations.overview,
            onOpen = {
                close()
                actions.onOpenAutomations()
            },
            onOpenRule = {
                close()
                actions.onOpenAutomations()
            },
        )

        Spacer(Modifier.height(22.dp))
        Text("Chats", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        when {
            state.isLoadingSessions -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }

            state.sessions.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    "No sessions yet. Start a new chat to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == state.activeSessionId,
                        onSelect = {
                            close()
                            actions.onSelectSession(session.id)
                        },
                        onRename = { title -> actions.onRenameSession(session.id, title) },
                        onDelete = { actions.onDeleteSession(session.id) },
                    )
                }
            }
        }

        HorizontalDivider(color = DividerDefaults.color)
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            label = { Text("Workspace files") },
            selected = false,
            onClick = {
                close()
                actions.onOpenWorkspaceFiles()
            },
            icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            onClick = {
                close()
                actions.onOpenSettings()
            },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
        )
    }
}

@Composable
private fun SessionRow(
    session: dev.androidagent.core.ChatSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    var deleteOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    var renameText by rememberSaveable(session.id) { mutableStateOf(session.title) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title.ifBlank { "Untitled chat" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    formatSessionTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Session actions" },
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            renameText = session.title
                            renameOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            deleteOpen = true
                        },
                    )
                }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.trim().isNotEmpty(),
                    onClick = {
                        renameOpen = false
                        onRename(renameText.trim())
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Delete session?") },
            text = { Text("This removes the session from the list. Workspace files are kept by the root app policy.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteOpen = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AgentChatContent(
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lastMessage = state.messages.lastOrNull()
    var followLatest by remember(state.activeSessionId) { mutableStateOf(true) }
    var automaticScroll by remember { mutableStateOf(false) }
    LaunchedEffect(listState, state.activeSessionId) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, automaticScroll) }
            .collect { (scrolling, hasMore, automatic) ->
                if (scrolling && !automatic) followLatest = !hasMore
            }
    }

    LaunchedEffect(
        state.activeSessionId,
        lastMessage?.id,
        lastMessage?.text,
        state.toolCards.size,
        state.statusCards.size,
        state.runState.phase,
    ) {
        if (followLatest && listState.layoutInfo.totalItemsCount > 0) {
            automaticScroll = true
            try {
                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            } finally {
                automaticScroll = false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
    ) {
        if (state.errorMessage != null) {
            item(key = "error") {
                ErrorBanner(
                    message = state.errorMessage,
                    onRetry = actions.onRetry,
                    onDismiss = actions.onDismissError,
                )
            }
        }
        val outstanding = SetupChecklist.outstanding(state.setupRows())
        if (outstanding > 0) {
            item(key = "setup-prompt") { SetupPrompt(outstanding, actions.onOpenSettings) }
        }
        val updateInfo = state.updateInfo
        if (updateInfo?.isUpdateAvailable == true && state.isUpdateBannerVisible) {
            item(key = "update-banner") {
                UpdateBanner(
                    info = updateInfo,
                    status = state.updateStatus,
                    onDownload = actions.onDownloadUpdate,
                    onInstall = actions.onInstallUpdate,
                    onDismiss = actions.onDismissUpdateBanner,
                )
            }
        }

        if (state.statusCards.isNotEmpty()) {
            items(state.statusCards, key = { "status-${it.id}" }) { card -> StatusCard(card) }
        }
        if (state.toolCards.isNotEmpty()) {
            items(state.toolCards, key = { "tool-${it.id}" }) { card -> ToolCard(card) }
        }

        if (state.isLoadingMessages) {
            item(key = "loading-messages") {
                LoadingMessagesCard()
            }
        } else if (state.messages.isEmpty()) {
            item(key = "empty-chat") {
                EmptyChatCard(hasSession = state.activeSessionId != null)
            }
        } else {
            // Back-to-back device actions fold into one row.
            val running = state.runState.active && state.runState.sessionId == state.activeSessionId
            items(chatRows(state.messages, running), key = { it.key }) { row ->
                when (row) {
                    is MessageRow -> MessageBubble(row.message)
                    is ActionsRow -> DeviceActionsRow(row)
                }
            }
            if (offersWorkflowSuggestion(state.messages, running)) {
                item(key = "suggest-workflows-${state.messages.last().id}") {
                    AssistChip(
                        onClick = { actions.onSend(SUGGEST_WORKFLOWS_PROMPT, emptyList()) },
                        label = { Text("Suggest workflows") },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                    )
                }
            }
        }
    }
}

/** Quiet reminder that the agent cannot run yet. It disappears when nothing is outstanding. */
@Composable
private fun SetupPrompt(outstanding: Int, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (outstanding == 1) "1 thing to finish before the agent can run" else
                    "$outstanding things to finish before the agent can run",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Open settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmptyChatCard(hasSession: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("What can I help with?", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(if (hasSession) "Ask, plan, or do something on your phone." else "Create a chat to get started.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingMessagesCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Loading messages…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val role = message.role.lowercase()
    val user = role == "user"
    val system = role == "system" || role == "tool"
    if (role == "note") {
        CommandNote(message.text)
        return
    }
    if (system) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityDetail(
                title = if (role == "tool") "Device activity" else "Run details",
                detail = dev.androidagent.core.SecretRedactor.redact(message.text),
                key = message.id,
            )
            InlineImages(message.attachmentPaths)
        }
        return
    }
    val bubbleColor = when {
        user -> MaterialTheme.colorScheme.primaryContainer
        system -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        else -> Color.Transparent
    }
    val textColor = when {
        user -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = if (user) Modifier.fillMaxWidth(0.88f).widthIn(max = 520.dp) else Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp,
            ),
            color = bubbleColor,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = if (user || system) 16.dp else 0.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!user && message.state.lowercase() in setOf("error", "failed")) {
                    Text(
                        text = "Failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (message.text.isNotBlank()) {
                    if (user) {
                        SelectionContainer { Text(withRtlLines(message.text), modifier = Modifier.fillMaxWidth(), color = textColor, style = MaterialTheme.typography.bodyLarge) }
                    } else {
                        MarkdownMessage(message.text, textColor)
                        val clipboard = LocalClipboardManager.current
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(message.text)) },
                                modifier = Modifier.size(40.dp).semantics { contentDescription = "Copy message" },
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy message", modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else if (message.state.equals("streaming", ignoreCase = true)) {
                    Row(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AgentOrb(modifier = Modifier.size(28.dp))
                        Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("No text returned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InlineImages(message.attachmentPaths)
                if (message.attachmentPaths.any { !isImagePath(it) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(message.attachmentPaths.filterNot(::isImagePath), key = { it }) { path ->
                            AssistChip(
                                onClick = {},
                                label = { Text(path.substringAfterLast('/').substringAfterLast('\\'), maxLines = 1) },
                                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApprovalCard(
    approval: EngineEvent.Approval,
    onApproval: (String, Boolean) -> Unit,
    onApproveAlways: (String, dev.androidagent.core.ApprovalScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = remember(approval) { approval.summary() }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Nothing else in the app is assertive: this one blocks the run
            // until the user answers, so it has to interrupt a screen reader.
            Text(
                summary.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            var showAll by rememberSaveable(approval.requestId) { mutableStateOf(false) }
            summary.lines.forEach { (label, value) ->
                val long = value.length > APPROVAL_DETAIL_LIMIT
                SelectionContainer {
                    Text(
                        "$label: " + if (long && !showAll) value.take(APPROVAL_DETAIL_LIMIT) + "…" else value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (long) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all") }
                }
            }
            Text(
                "Or say “yes” or “no”",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onApproval(approval.requestId, false) }) { Text("Deny") }
                Button(onClick = { onApproval(approval.requestId, true) }) { Text("Allow") }
            }
            // Standing permissions only for a send, and only by a tap: a spoken
            // "yes" always answers this one message.
            summary.sendApp?.let { app ->
                summary.sendRecipient?.let { recipient ->
                    TextButton(onClick = { onApproveAlways(approval.requestId, dev.androidagent.core.ApprovalScope.CONTACT) }) {
                        Text("Always allow for $recipient")
                    }
                }
                TextButton(onClick = { onApproveAlways(approval.requestId, dev.androidagent.core.ApprovalScope.APP) }) {
                    Text("Always allow sending in $app")
                }
            }
        }
    }
}

@Composable
private fun ActivityDetail(title: String, detail: String, failed: Boolean = false, key: Any = title) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.PictureInPictureAlt, null,
                Modifier.size(18.dp), tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotBlank()) Icon(Icons.Outlined.ExpandMore,
                if (expanded) "Hide activity details" else "Show activity details", Modifier.size(18.dp))
        }
        if (expanded && detail.isNotBlank()) SelectionContainer {
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}

@Composable
private fun ToolCard(card: ToolStatusCard) {
    ActivityDetail(card.title, card.detail, card.state == AgentCardState.ERROR, key = card.id)
}

@Composable
private fun StatusCard(card: AgentStatusCard) {
    ActivityDetail(card.title, card.detail, card.state == AgentCardState.ERROR, key = card.id)
}


@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Surface(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Something went wrong", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss error") }
            }
            // The message itself is what tells the user whether this is theirs
            // to fix. Hiding all of it behind a toggle made every failure look
            // the same.
            SelectionContainer {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(message)) }) { Text("Copy") }
                if (message.length > 120) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Less" else "More")
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    info: AppUpdateInfo,
    status: UpdateStatus,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Update available: v${info.latestVersionName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss update", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                }
            }
            if (info.releaseNotes.isNotBlank()) {
                Text(
                    info.releaseNotes.take(150) + if (info.releaseNotes.length > 150) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (status) {
                is UpdateStatus.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Downloading: ${(status.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                is UpdateStatus.ReadyToInstall -> {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Install update")
                    }
                }
                else -> {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val sizeStr = if (info.apkSize > 0) " (${info.apkSize / (1024 * 1024)} MB)" else ""
                        Text("Update now$sizeStr")
                    }
                }
            }
        }
    }
}

internal fun readableRunPhase(phase: RunPhase): String = when (phase) {
    RunPhase.IDLE -> "Ready"
    RunPhase.STARTING -> "Starting"
    RunPhase.THINKING -> "Working"
    RunPhase.TOOL -> "Running a tool"
    RunPhase.CONTROLLING -> "Controlling device"
    RunPhase.STOPPING -> "Stopping"
    RunPhase.ERROR -> "Run error"
}




internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> "${bytes / (1024L * 1024L * 1024L)} GB"
}

private fun formatSessionTime(timestamp: Long): String {
    if (timestamp <= 0L) return "No activity time"
    return try {
        val formatter = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        formatter.format(java.util.Date(timestamp))
    } catch (_: Exception) {
        "Updated"
    }
}
