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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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


/**
 * Every text style takes its direction from its own words, not from the
 * phone's language. On a Hebrew phone the layout is still mirrored, but an
 * English sentence reads left to right with its "?" at the end, and a Hebrew
 * reply still reads right to left. Unspecified would follow the layout.
 */
internal fun agentTypography(): androidx.compose.material3.Typography {
    val base = androidx.compose.material3.Typography().copy(
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )
    fun androidx.compose.ui.text.TextStyle.byContent() = copy(textDirection = TextDirection.Content)
    return base.copy(
        displayLarge = base.displayLarge.byContent(),
        displayMedium = base.displayMedium.byContent(),
        displaySmall = base.displaySmall.byContent(),
        headlineLarge = base.headlineLarge.byContent(),
        headlineMedium = base.headlineMedium.byContent(),
        headlineSmall = base.headlineSmall.byContent(),
        titleLarge = base.titleLarge.byContent(),
        titleMedium = base.titleMedium.byContent(),
        titleSmall = base.titleSmall.byContent(),
        bodyLarge = base.bodyLarge.byContent(),
        bodyMedium = base.bodyMedium.byContent(),
        bodySmall = base.bodySmall.byContent(),
        labelLarge = base.labelLarge.byContent(),
        labelMedium = base.labelMedium.byContent(),
        labelSmall = base.labelSmall.byContent(),
    )
}

@Composable
fun AndroidAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgentDarkColors,
        typography = remember { agentTypography() },
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
            if (state.isDrawerOpen) {
                // The computers' own conversations, fresh for the panel.
                actions.onRefreshPcThreads()
                drawerState.open()
            } else {
                drawerState.close()
            }
        }
        LaunchedEffect(drawerState) {
            snapshotFlow { drawerState.currentValue }
                .collectLatest { actions.onDrawerChanged(it == DrawerValue.Open) }
        }

        val voiceMode = rememberVoiceModeMotion(voiceModeShown(state.shownVoice()))
        val drawerPush = rememberDrawerPush(drawerState)
        val layoutDirection = LocalLayoutDirection.current

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !voiceMode.shown,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 360.dp),
                    drawerContainerColor = LibraryGround,
                    // Named, because the drawer's own color is not in the scheme
                    // and Compose then cannot tell which text color goes on it.
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    ChatLibraryDrawer(
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
                    .drawerPushed(drawerPush, layoutDirection)
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
                        state.activeSessionId?.takeIf { it in state.pcBusyChats }?.let { id ->
                            PcBusyBanner(
                                forking = id in state.pcForking,
                                onFork = { actions.onForkPcChat(id) },
                                onCheck = { actions.onCheckPcChatBusy(id) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        TransferBanner(state.fileTransfer, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
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

        // Sheets are windows of their own and would sit above first launch.
        val onboarding = state.onboardingStep() != dev.androidagent.core.OnboardingStep.DONE
        if (state.isSettingsOpen && !onboarding) {
            AgentSettingsSheet(state = state, actions = actions)
        }
        if (state.isAutomationsOpen && !onboarding) {
            AutomationsSheet(state = state, actions = actions)
        }
        if (state.isWorkspaceOpen && !onboarding) {
            WorkspaceFilesSheet(state = state, actions = actions)
        }
        if (state.isComputersOpen && !onboarding) {
            ComputersSheet(state = state, actions = actions)
        }
        OnboardingFlow(state = state, actions = actions)
    }
}

private val DrawerRunning = Color(0xFF69A7FF)

/**
 * One chat in the library. Rename and delete are a long press on any row. The
 * open chat used to carry a visible menu button as well, which made its row
 * the tallest and widest in the list for an action used rarely.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    session: dev.androidagent.core.ChatSession,
    onComputer: Boolean,
    selected: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    subtitle: String? = null,
    subtitleIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    subtitleColor: Color? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    var deleteOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    var renameText by rememberSaveable(session.id) { mutableStateOf(session.title) }
    val shape = RoundedCornerShape(12.dp)

    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            // The open chat is lifted, not coloured: colour is kept for "running".
            color = if (selected) Color(0xFF252422) else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .combinedClickable(
                        onClick = onSelect,
                        onLongClick = { menuOpen = true },
                        onLongClickLabel = "Session actions",
                    )
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.let { it(); Spacer(Modifier.width(12.dp)) }
                if (running && leading == null) {
                    StatusDot(color = Color(0xFFE8C9A0), size = 8.dp, pulsing = true, modifier = Modifier.padding(end = 10.dp))
                }
                if (onComputer) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = "Runs on a computer",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 10.dp).size(18.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(vertical = if (subtitle != null) 6.dp else 0.dp)) {
                    Text(
                        session.title.ifBlank { "Untitled chat" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    subtitle?.let {
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            val tint = subtitleColor ?: Color(0xFF8E8C85)
                            subtitleIcon?.let { icon ->
                                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(it, style = MaterialTheme.typography.labelSmall, fontSize = 12.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
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

/** How far from the end the chat must be scrolled before "Jump to latest" appears. */
private val JUMP_BUTTON_DISTANCE = 96.dp

private data class ChatScrollSnapshot(
    val totalItemsCount: Int,
    val lastVisibleIndex: Int,
    val lastVisibleEnd: Int,
    val viewportEndOffset: Int,
    val canScrollForward: Boolean,
    val isScrollInProgress: Boolean,
    val followLatest: Boolean,
)

@Composable
private fun AgentChatContent(
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var followLatest by remember(state.activeSessionId) { mutableStateOf(true) }
    var automaticScroll by remember(state.activeSessionId) { mutableStateOf(false) }
    LaunchedEffect(listState, state.activeSessionId) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, automaticScroll) }
            .collect { (scrolling, hasMore, automatic) ->
                if (scrolling && !automatic) followLatest = !hasMore
            }
    }

    // Markdown can grow after composition. Follow its measured end with one
    // scroll writer instead of restarting an animation on every text update.
    LaunchedEffect(listState, state.activeSessionId) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            ChatScrollSnapshot(
                totalItemsCount = info.totalItemsCount,
                lastVisibleIndex = last?.index ?: -1,
                lastVisibleEnd = last?.let { it.offset + it.size } ?: 0,
                viewportEndOffset = info.viewportEndOffset,
                canScrollForward = listState.canScrollForward,
                isScrollInProgress = listState.isScrollInProgress,
                followLatest = followLatest,
            )
        }.collect { position ->
            if (!position.followLatest || position.isScrollInProgress ||
                !position.canScrollForward || position.totalItemsCount == 0
            ) return@collect

            automaticScroll = true
            try {
                if (position.lastVisibleIndex == position.totalItemsCount - 1) {
                    val remaining = position.lastVisibleEnd - position.viewportEndOffset
                    if (remaining > 0) listState.scrollBy(remaining.toFloat())
                } else {
                    // A new item is below the viewport. Move to its true end once.
                    listState.scrollToItem(position.totalItemsCount - 1, Int.MAX_VALUE)
                }
            } finally {
                automaticScroll = false
            }
        }
    }

    Box(modifier = modifier) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
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

        // A conversation opened from a computer fills in from there; say so
        // instead of showing an empty chat that looks finished.
        val pcLoading = state.activeSessionId?.let(state.pcChatLoading::get)
        if (pcLoading != null) {
            item(key = "pc-loading") { PcConversationLoading(pcLoading) }
        } else if (state.isLoadingMessages) {
            item(key = "loading-messages") {
                LoadingMessagesCard()
            }
        } else if (state.messages.isEmpty()) {
            item(key = "empty-chat") {
                EmptyChatCard(state, actions)
            }
        } else {
            // Back-to-back device actions fold into one row, and so do a
            // computer's steps: seven identical rows hid the answer.
            val running = state.runState.active && state.runState.sessionId == state.activeSessionId
            val computerLabel = state.activeSessionId?.let(state.remoteBindings::get)
                ?.let { binding -> state.computers.firstOrNull { it.id == binding.computerId }?.label }
            // While this chat's own run works, one status line sits under the
            // last line, always. A reply still empty used to show its own
            // "Working" instead and the line came back once it had text, so
            // the orb vanished for a moment at every new block: the empty
            // reply is not drawn while the status line stands in for it.
            val showStatus = running && !state.voiceState.active
            val shown = if (showStatus) {
                state.messages.filterNot {
                    it.role.equals("assistant", true) && it.text.isBlank() && it.state.equals("streaming", true)
                }
            } else state.messages
            items(chatRows(shown, running), key = { it.key }) { row ->
                when (row) {
                    is MessageRow -> MessageBubble(row.message, row.copyText)
                    is ActionsRow -> DeviceActionsRow(row)
                    is RemoteActivityRow -> RemoteActivityGroup(row, computerLabel)
                }
            }
            if (showStatus) {
                item(key = "run-status", contentType = "run-status") {
                    RunStatusLine(state.runState, Modifier.fillMaxWidth().animateItem())
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
    // Shown once the user has scrolled a real distance up; tapping it resumes
    // following the reply. "Can scroll forward" alone was true for the padding
    // under the last line, so a nudge of a few pixels raised a button that
    // promised more below where there was none.
    val jumpDistance = with(LocalDensity.current) { JUMP_BUTTON_DISTANCE.toPx() }
    val farFromEnd by remember(listState, jumpDistance) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && (last.index < info.totalItemsCount - 1 ||
                last.offset + last.size - info.viewportEndOffset > jumpDistance)
        }
    }
    // In a corner, on the side the text ends, so it stays off the column being read: a Hebrew reply starts at the right.
    val textEndsRtl = state.messages.lastOrNull()?.text?.let(::endsRtl) == true
    androidx.compose.animation.AnimatedVisibility(
        visible = !followLatest && farFromEnd,
        modifier = Modifier
            .align(if (textEndsRtl) Alignment.BottomStart else Alignment.BottomEnd)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = {
                followLatest = true
            },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Jump to latest")
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
private fun EmptyChatCard(state: AgentUiState, actions: AgentUiActions) {
    val hasSession = state.activeSessionId != null
    val binding = state.activeSessionId?.let(state.remoteBindings::get)
    val computer = binding?.let { b -> state.computers.firstOrNull { it.id == b.computerId } }
    // Where this chat runs can change until its first message: with computers
    // to choose from, that choice is the screen.
    if (hasSession && state.computers.isNotEmpty() && !state.runState.active) {
        WhereMikeWorks(state, binding, actions)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("What can I help with?", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            when {
                !hasSession -> "Create a chat to get started."
                computer != null -> "Mike works in ${folderName(binding.cwd)} on ${computer.label}, and can still use this phone."
                else -> "Ask, plan, or do something on your phone."
            },
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A phone chat picks its engine before its first message.
        if (hasSession && binding == null && !state.runState.active) ChatEngineChoice(state, actions)
    }
}

/**
 * The conversation is held open by Codex on the computer, so Mike cannot
 * write to it. A copy with the whole history can go on right away.
 */
@Composable
private fun PcBusyBanner(forking: Boolean, onFork: () -> Unit, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Computer, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Open in Codex on the computer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Mike can't add to this conversation while the Codex app holds it. Continue in a copy with the whole history, or close it in Codex on the computer.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 32.dp, top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCheck, enabled = !forking) { Text("Check again", color = MaterialTheme.colorScheme.onTertiaryContainer) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onFork,
                    enabled = !forking,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary),
                ) {
                    if (forking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (forking) "Copying" else "Continue in a copy", maxLines = 1)
                }
            }
        }
    }
}

/** The earlier messages of a computer's conversation on their way, with placeholders where they will land. */
@Composable
private fun PcConversationLoading(computer: String) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "pc-loading").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "pc-loading-alpha",
    )
    val bar = Color(0xFF242427)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = Color(0xFF1A2B28),
        )
        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1B1B1E), modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(10.dp))
                Text("Loading the conversation from $computer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().alpha(pulse), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.align(Alignment.End).width(220.dp).height(44.dp).background(bar, RoundedCornerShape(22.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.9f).height(12.dp).background(bar, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(0.8f).height(12.dp).background(bar, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(0.55f).height(12.dp).background(bar, RoundedCornerShape(6.dp)))
            }
            Box(Modifier.align(Alignment.End).width(160.dp).height(44.dp).background(bar, RoundedCornerShape(22.dp)))
        }
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
private fun MessageBubble(message: ChatMessage, copyText: String? = null) {
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
                    // A long press on a block opens its menu. A user's prompt is
                    // one block; an agent's reply is everything after that prompt.
                    val clipboard = LocalClipboardManager.current
                    val blockText = if (user) message.text else copyText ?: message.text
                    var menuOpen by remember(message.id) { mutableStateOf(false) }
                    var selectOpen by remember(message.id) { mutableStateOf(false) }
                    Box(
                        Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Copy message") { clipboard.setText(AnnotatedString(blockText)); true },
                            )
                        },
                    ) {
                        if (user) {
                            Text(
                                withRtlLines(message.text),
                                modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) },
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                            MarkdownMessage(message.text, textColor, onLongPress = { menuOpen = true })
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = { menuOpen = false; clipboard.setText(AnnotatedString(blockText)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Select text") },
                                leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                                onClick = { menuOpen = false; selectOpen = true },
                            )
                        }
                    }
                    if (selectOpen) SelectTextDialog(blockText) { selectOpen = false }
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

/** The block's text where part of it can be selected, since a long press opens the menu instead. */
@Composable
private fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            SelectionContainer {
                Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
            // Tinted like its title: left at the default it is the brightest thing in the row.
            if (detail.isNotBlank()) Icon(Icons.Outlined.ExpandMore,
                if (expanded) "Hide activity details" else "Show activity details", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
