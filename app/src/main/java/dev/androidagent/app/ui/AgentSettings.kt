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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Assistant
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.app.update.UpdateStatus
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.RunPhase
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.SetupChecklist
import dev.androidagent.core.SetupImportance
import dev.androidagent.core.SetupItem
import dev.androidagent.core.SetupRow
import dev.androidagent.core.SetupState
import dev.androidagent.core.UsageSummary

// Settings are two levels: a hub that answers "what still needs doing", and one
// page per thing. The old single scroll mixed one-time setup with everyday
// configuration, so nothing said whether the phone was ready.

private enum class SettingsRoute {
    RUNTIME, ACCOUNT, SCREEN_CONTROL, FLOATING_CONTROL, WIRELESS_ADB,
    NOTIFICATIONS, INSTALL_UPDATES, MICROPHONE,
    MODEL, WORKSPACE, USAGE, UPDATES, SEND_APPROVALS, ASSISTANT, AUTOMATIONS,
    JEV, PRIVACY,
}

private fun SetupItem.route(): SettingsRoute = when (this) {
    SetupItem.RUNTIME -> SettingsRoute.RUNTIME
    SetupItem.ACCOUNT -> SettingsRoute.ACCOUNT
    SetupItem.SCREEN_CONTROL -> SettingsRoute.SCREEN_CONTROL
    SetupItem.FLOATING_CONTROL -> SettingsRoute.FLOATING_CONTROL
    SetupItem.WIRELESS_ADB -> SettingsRoute.WIRELESS_ADB
    SetupItem.NOTIFICATIONS -> SettingsRoute.NOTIFICATIONS
    SetupItem.INSTALL_UPDATES -> SettingsRoute.INSTALL_UPDATES
    SetupItem.MICROPHONE -> SettingsRoute.MICROPHONE
}

private fun SettingsRoute.title(): String = when (this) {
    SettingsRoute.RUNTIME -> "Local runtime"
    SettingsRoute.ACCOUNT -> "Codex account"
    SettingsRoute.SCREEN_CONTROL -> "See and tap the screen"
    SettingsRoute.FLOATING_CONTROL -> "Floating Stop button"
    SettingsRoute.WIRELESS_ADB -> "Run commands and install apps"
    SettingsRoute.NOTIFICATIONS -> "Show progress"
    SettingsRoute.INSTALL_UPDATES -> "Install app updates"
    SettingsRoute.MICROPHONE -> "Hear you"
    SettingsRoute.MODEL -> "Model"
    SettingsRoute.WORKSPACE -> "Workspace"
    SettingsRoute.USAGE -> "Usage"
    SettingsRoute.UPDATES -> "App updates"
    SettingsRoute.SEND_APPROVALS -> "Ask before sending"
    SettingsRoute.ASSISTANT -> "Power button assistant"
    SettingsRoute.AUTOMATIONS -> "Standing rules"
    SettingsRoute.JEV -> "Jev"
    SettingsRoute.PRIVACY -> "Privacy and consent"
}

private fun SettingsRoute.icon(): ImageVector = when (this) {
    SettingsRoute.RUNTIME -> Icons.Outlined.Memory
    SettingsRoute.ACCOUNT -> Icons.Outlined.Key
    SettingsRoute.SCREEN_CONTROL -> Icons.Outlined.Visibility
    SettingsRoute.FLOATING_CONTROL -> Icons.Outlined.PictureInPictureAlt
    SettingsRoute.WIRELESS_ADB -> Icons.Outlined.Wifi
    SettingsRoute.NOTIFICATIONS -> Icons.Outlined.NotificationsNone
    SettingsRoute.INSTALL_UPDATES -> Icons.Outlined.Download
    SettingsRoute.MICROPHONE -> Icons.Outlined.MicNone
    SettingsRoute.MODEL -> Icons.Outlined.Tune
    SettingsRoute.WORKSPACE -> Icons.Outlined.Folder
    SettingsRoute.USAGE -> Icons.Outlined.DataUsage
    SettingsRoute.UPDATES -> Icons.Outlined.Update
    SettingsRoute.SEND_APPROVALS -> Icons.Outlined.Key
    SettingsRoute.ASSISTANT -> Icons.Outlined.Assistant
    SettingsRoute.AUTOMATIONS -> Icons.Outlined.Schedule
    SettingsRoute.JEV -> Icons.Outlined.Tune
    SettingsRoute.PRIVACY -> Icons.Outlined.Shield
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSettingsSheet(state: AgentUiState, actions: AgentUiActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    // Stored as a name rather than the enum so rememberSaveable needs no Saver.
    var routeName by rememberSaveable { mutableStateOf<String?>(null) }
    val route = routeName?.let { name -> runCatching { SettingsRoute.valueOf(name) }.getOrNull() }

    // Read outside the transition spec: that lambda is not composable.
    val animate = animationsEnabled()

    ModalBottomSheet(
        onDismissRequest = actions.onCloseSettings,
        sheetState = sheetState,
        // The sheet hosts its content in its own window with its own back
        // dispatcher, which swallowed a nested BackHandler and closed the whole
        // sheet from a detail page. Taking back over here is the only way to
        // make it walk detail then hub. Verified on device.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.fillMaxHeight(0.94f),
    ) {
        BackHandler { if (route != null) routeName = null else actions.onCloseSettings() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (route == null) actions.onCloseSettings() else routeName = null }) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = if (route == null) "Close settings" else "Back to settings",
                    )
                }
                Text(
                    route?.title() ?: "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    if (!animate) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else if (targetState == null) {
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                    } else {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                    }.using(SizeTransform(clip = false))
                },
                label = "settings-route",
            ) { target ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (target == null) {
                        SettingsHub(state) { routeName = it.name }
                    } else {
                        SettingsDetail(target, state, actions)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsHub(state: AgentUiState, onOpen: (SettingsRoute) -> Unit) {
    val rows = state.setupRows()
    val ready = SetupChecklist.readyForRuns(rows)
    fun row(item: SetupItem) = rows.first { it.item == item }

    // The orb says at a glance whether Mike can act, the same way the chat's
    // top bar does, and one sentence says what to do if not.
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentOrb(
                modifier = Modifier.size(72.dp),
                phase = RunPhase.IDLE,
                idleColor = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (ready) "Mike is ready" else "Mike needs your help",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                Text(
                    if (ready) "Everything needed is on. Mike asks before adding more." else
                        "${SetupChecklist.headline(rows)}. Everything else is fine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Abilities rather than permissions: the user decides what Mike may do,
    // and the Android grant behind each one is a detail of its page. Screen
    // control and the floating control are one row because neither works
    // without the other.
    HubGroup("What Mike can do") {
        val screen = combined(row(SetupItem.SCREEN_CONTROL), row(SetupItem.FLOATING_CONTROL))
        AbilityRow(SettingsRoute.SCREEN_CONTROL, screen.first, screen.second, onOpen)
        AbilityRow(SettingsRoute.NOTIFICATIONS, row(SetupItem.NOTIFICATIONS).state, row(SetupItem.NOTIFICATIONS).summary, onOpen)
        val adb = row(SetupItem.WIRELESS_ADB)
        AbilityRow(
            SettingsRoute.WIRELESS_ADB,
            adb.state,
            if (adb.state == SetupState.PENDING) "Optional · Mike can set it up for you" else adb.summary,
            onOpen,
        )
        val access = state.automations.notificationAccess
        AbilityRow(
            SettingsRoute.AUTOMATIONS,
            if (access) SetupState.DONE else SetupState.PENDING,
            if (access) "On · for rules that react to messages" else "Asked when a rule needs it",
            onOpen,
            title = "Read notifications",
        )
        AbilityRow(SettingsRoute.MICROPHONE, row(SetupItem.MICROPHONE).state, row(SetupItem.MICROPHONE).summary, onOpen)
        AbilityRow(SettingsRoute.INSTALL_UPDATES, row(SetupItem.INSTALL_UPDATES).state, row(SetupItem.INSTALL_UPDATES).summary, onOpen)
    }

    // No dots here on purpose: these are choices, not steps, and marking them
    // green would teach the eye to skip the markers that do mean something.
    HubGroup("How Mike works") {
        SettingsHubRow(
            icon = SettingsRoute.AUTOMATIONS.icon(),
            title = "Standing rules",
            summary = state.automations.summary,
            onClick = { onOpen(SettingsRoute.AUTOMATIONS) },
        )
        SettingsHubRow(
            icon = SettingsRoute.SEND_APPROVALS.icon(),
            title = SettingsRoute.SEND_APPROVALS.title(),
            summary = if (state.sendGrants.isEmpty()) "Every message asks first" else "${state.sendGrants.size} always allowed",
            onClick = { onOpen(SettingsRoute.SEND_APPROVALS) },
        )
        SettingsHubRow(
            icon = SettingsRoute.MODEL.icon(),
            title = SettingsRoute.MODEL.title(),
            summary = state.selectedModel ?: "Not chosen yet",
            onClick = { onOpen(SettingsRoute.MODEL) },
        )
        SettingsHubRow(
            icon = SettingsRoute.ASSISTANT.icon(),
            title = SettingsRoute.ASSISTANT.title(),
            summary = if (state.isDefaultAssistant) "Hold the power button to talk to Mike" else "Gemini still answers the power button",
            onClick = { onOpen(SettingsRoute.ASSISTANT) },
        )
    }

    HubGroup("Account and privacy") {
        val account = row(SetupItem.ACCOUNT)
        SettingsHubRow(
            icon = SettingsRoute.ACCOUNT.icon(),
            title = SettingsRoute.ACCOUNT.title(),
            summary = account.summary,
            state = account.state.takeIf { it != SetupState.DONE },
            onClick = { onOpen(SettingsRoute.ACCOUNT) },
        )
        SettingsHubRow(
            icon = SettingsRoute.USAGE.icon(),
            title = SettingsRoute.USAGE.title(),
            summary = state.usageLimits.firstOrNull()?.usedPercent
                ?.let { "${(100 - it).toInt()}% of the quota left" }
                ?: "Token usage and account quota",
            onClick = { onOpen(SettingsRoute.USAGE) },
        )
        SettingsHubRow(
            icon = SettingsRoute.PRIVACY.icon(),
            title = SettingsRoute.PRIVACY.title(),
            summary = "What you agreed to · where data goes",
            onClick = { onOpen(SettingsRoute.PRIVACY) },
        )
        SettingsHubRow(
            icon = SettingsRoute.WORKSPACE.icon(),
            title = SettingsRoute.WORKSPACE.title(),
            summary = if (state.workspaceFiles.isEmpty()) "No files loaded" else "${state.workspaceFiles.size} files loaded",
            onClick = { onOpen(SettingsRoute.WORKSPACE) },
        )
    }

    HubGroup("Advanced") {
        val runtime = row(SetupItem.RUNTIME)
        SettingsHubRow(
            icon = SettingsRoute.RUNTIME.icon(),
            title = SettingsRoute.RUNTIME.title(),
            summary = runtime.summary,
            state = runtime.state.takeIf { it != SetupState.DONE },
            onClick = { onOpen(SettingsRoute.RUNTIME) },
        )
        SettingsHubRow(
            icon = SettingsRoute.JEV.icon(),
            title = SettingsRoute.JEV.title(),
            summary = when {
                !state.jevEnabled -> "Off"
                !state.jevTokenConfigured -> "Enabled · token missing"
                else -> "Enabled · ready for the agent"
            },
            onClick = { onOpen(SettingsRoute.JEV) },
        )
        SettingsHubRow(
            icon = SettingsRoute.UPDATES.icon(),
            title = SettingsRoute.UPDATES.title(),
            summary = "Version ${dev.androidagent.app.BuildConfig.VERSION_NAME}",
            onClick = { onOpen(SettingsRoute.UPDATES) },
        )
    }
}

/** Screen control and the floating control as one ability: the worse of the two wins. */
private fun combined(screen: SetupRow, floating: SetupRow): Pair<SetupState, String> = when {
    screen.state != SetupState.DONE -> screen.state to screen.summary
    floating.state != SetupState.DONE -> floating.state to "Stop button not allowed · Mike can't act yet"
    else -> SetupState.DONE to "On · with the floating Stop button"
}

@Composable
private fun AbilityRow(
    route: SettingsRoute,
    state: SetupState,
    summary: String,
    onOpen: (SettingsRoute) -> Unit,
    title: String = route.title(),
) {
    SettingsHubRow(
        icon = route.icon(),
        title = title,
        summary = summary,
        state = state,
        action = when (state) {
            SetupState.DONE -> "On"
            SetupState.BLOCKED -> "Fix"
            SetupState.WORKING -> "…"
            SetupState.PENDING -> "Set up"
        },
        onClick = { onOpen(route) },
    )
}

@Composable
private fun ColumnScope.PrivacySettings(state: AgentUiState, actions: AgentUiActions) {
    val uriHandler = LocalUriHandler.current
    var confirmWithdraw by rememberSaveable { mutableStateOf(false) }
    Text("Where your data goes", fontWeight = FontWeight.Medium)
    Explanation(
        "Hey Mike has no servers of its own. Chats, files and your sign-in stay on this phone. What Mike sees " +
            "and what you type goes to Codex (OpenAI) to answer you, and is covered only by the Codex policies. " +
            "Update checks ask GitHub for the latest version and send nothing about you.",
    )
    TextButton(onClick = { uriHandler.openUri(POLICIES_URL) }) { Text("Read the Codex and OpenAI policies") }

    val agreedAt = state.onboarding.consentAt
    Text(
        agreedAt?.let {
            "You agreed on " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))
        } ?: "You have not agreed yet",
        fontWeight = FontWeight.Medium,
    )
    ConsentStatements.forEach { (title, body) ->
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 10.dp, top = 2.dp).size(18.dp),
            )
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Withdraw consent", fontWeight = FontWeight.Medium)
    Explanation(
        "Mike stops, signs out and opens Accessibility settings so you can turn off screen access, which only " +
            "you can do. Your chats stay on this phone until you delete them.",
    )
    OutlinedButton(
        onClick = { confirmWithdraw = true },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Withdraw and stop Mike", color = MaterialTheme.colorScheme.error) }

    if (confirmWithdraw) {
        AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            title = { Text("Withdraw consent?") },
            text = { Text("Mike stops any task, signs out, and asks for consent again before it can run.") },
            confirmButton = {
                TextButton(onClick = { confirmWithdraw = false; actions.onWithdrawConsent() }) {
                    Text("Withdraw", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWithdraw = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsDetail(route: SettingsRoute, state: AgentUiState, actions: AgentUiActions) {
    SettingsSection(title = route.title(), icon = route.icon()) {
        when (route) {
            SettingsRoute.RUNTIME -> RuntimeSettings(state, actions)
            SettingsRoute.ACCOUNT -> AccountSettings(state, actions)
            SettingsRoute.SCREEN_CONTROL -> ScreenControlSettings(state, actions)
            SettingsRoute.FLOATING_CONTROL -> FloatingControlSettings(state, actions)
            SettingsRoute.WIRELESS_ADB -> WirelessAdbSettings(state, actions)
            SettingsRoute.NOTIFICATIONS -> NotificationSettings(state, actions)
            SettingsRoute.INSTALL_UPDATES -> InstallUpdatesSettings(state, actions)
            SettingsRoute.MICROPHONE -> MicrophoneSettings(state, actions)
            SettingsRoute.MODEL -> ModelSettings(state, actions)
            SettingsRoute.WORKSPACE -> WorkspaceSettings(state, actions)
            SettingsRoute.USAGE -> UsageSettings(state, actions)
            SettingsRoute.UPDATES -> UpdateSettings(state, actions)
            SettingsRoute.SEND_APPROVALS -> SendApprovalSettings(state, actions)
            SettingsRoute.ASSISTANT -> AssistantSettings(state, actions)
            SettingsRoute.AUTOMATIONS -> AutomationSettings(state, actions)
            SettingsRoute.JEV -> JevSettings(state, actions)
            SettingsRoute.PRIVACY -> PrivacySettings(state, actions)
        }
    }
}

@Composable
private fun HubGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            content()
        }
    }
}

/** The one-line readiness summary a detail page opens with. */
@Composable
private fun ReadinessLine(state: AgentUiState, item: SetupItem) {
    val row = state.setupRows().firstOrNull { it.item == item } ?: return
    StatusLine(readinessWord(row.state), row.summary, readinessColor(row.state))
}

/** Shared with the rules sheet, which explains itself the same way. */
@Composable
internal fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.RuntimeSettings(state: AgentUiState, actions: AgentUiActions) {
    StatusLine(
        title = readableRuntimePhase(state.runtimeStatus.phase),
        detail = state.runtimeStatus.message,
        color = readinessColor(setupState(state, SetupItem.RUNTIME)),
    )
    Explanation("Codex runs on this phone. Preparing unpacks the engine and its tools into the app's own storage.")
    state.runtimeStatus.progress?.let { progress ->
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
    // Proxy metadata only: category, host and port. The buffer this is derived
    // from never holds tunnel bytes, headers or credentials, and that has to
    // stay true of anything shown here.
    state.networkDiagnostic?.let { diagnostic ->
        StatusLine(title = "Network", detail = diagnostic, color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = actions.onPrepareRuntime,
        enabled = !state.isPreparingRuntime,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LoadingButtonContent(
            loading = state.isPreparingRuntime,
            icon = Icons.Outlined.PlayArrow,
            label = if (state.runtimeStatus.phase == RuntimePhase.READY) "Runtime ready" else "Prepare runtime",
            loadingLabel = "Preparing…",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ColumnScope.AccountSettings(state: AgentUiState, actions: AgentUiActions) {
    val uriHandler = LocalUriHandler.current
    val account = state.accountStatus
    StatusLine(
        title = account?.label ?: "Account status unavailable",
        detail = when {
            account == null -> "Connect the engine to read account status."
            account.signedIn -> "Signed in"
            else -> "Sign in to use Codex."
        },
        color = readinessColor(setupState(state, SetupItem.ACCOUNT)),
    )
    Explanation("The agent talks to Codex with your own account. Nothing runs until this is signed in.")
    account?.loginUrl?.let {
        Explanation("Browser login is waiting for completion.")
        OutlinedButton(onClick = { uriHandler.openUri(it) }, modifier = Modifier.fillMaxWidth()) {
            LoadingButtonContent(loading = false, icon = Icons.Outlined.Key, label = "Open official login")
        }
    }
    account?.userCode?.takeIf { it.isNotBlank() }?.let { code ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("One-time code", style = MaterialTheme.typography.labelLarge)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                SelectionContainer {
                    Text(
                        code,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
            Explanation("Select and copy this code in the browser if asked.")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (account?.signedIn == true) {
            OutlinedButton(onClick = actions.onLogout, modifier = Modifier.weight(1f)) {
                LoadingButtonContent(loading = false, icon = Icons.Outlined.Logout, label = "Log out")
            }
        } else {
            Button(onClick = actions.onLogin, modifier = Modifier.weight(1f)) {
                LoadingButtonContent(
                    loading = false,
                    icon = Icons.Outlined.Login,
                    label = "Log in",
                    spinnerColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        OutlinedButton(
            onClick = actions.onRefreshAccount,
            enabled = !state.isRefreshingAccount,
            modifier = Modifier.weight(1f),
        ) {
            LoadingButtonContent(
                loading = state.isRefreshingAccount,
                icon = Icons.Outlined.Refresh,
                label = "Refresh",
                loadingLabel = "Refreshing…",
            )
        }
    }
    if (state.savedAccounts.accounts.isNotEmpty() || account?.signedIn == true) {
        Text("Accounts", style = MaterialTheme.typography.labelLarge)
        Explanation(
            "Keep several accounts signed in and switch in one tap. Chats and their history stay exactly as they are; " +
                "only the usage quota follows the account.",
        )
        AccountSwitcher(state, actions, allowRemove = true)
    }
}

@Composable
private fun ColumnScope.ScreenControlSettings(state: AgentUiState, actions: AgentUiActions) {
    ReadinessLine(state, SetupItem.SCREEN_CONTROL)
    Explanation(
        "Lets the agent read the screen and tap without Wireless Debugging. " +
            "While a task runs it reads on-screen text and sends it to the model.",
    )
    if (state.a11yStatus.blockedByRestrictedSetting) {
        // Switched on but never connected. On Android 13+ that is what a
        // sideloaded build looks like before the user allows restricted
        // settings, and no API reports it directly.
        Text(
            "Android is blocking this because the app was installed outside the Play Store. " +
                "Open App info, tap the three-dot menu, choose \"Allow restricted settings\", " +
                "then turn it on again in Accessibility.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = actions.onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
            LoadingButtonContent(loading = false, icon = Icons.Outlined.Settings, label = "Open App info")
        }
    }
    Button(onClick = actions.onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = false,
            icon = Icons.Outlined.Visibility,
            label = "Open accessibility settings",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(SettingsRoute.FLOATING_CONTROL.title(), fontWeight = FontWeight.Medium)
    FloatingControlSettings(state, actions)
}

@Composable
private fun ColumnScope.AutomationSettings(state: AgentUiState, actions: AgentUiActions) {
    val automations = state.automations
    val overview = automations.overview
    StatusLine(
        title = if (automations.total == 0) "None yet" else automations.summary,
        // The same live sentence the panel's strip shows, so the two surfaces
        // never disagree about what is wrong.
        detail = overview.line,
        color = when {
            overview.blocked > 0 -> MaterialTheme.colorScheme.error
            overview.enabled > 0 -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    Explanation(
        "A standing rule does something when something happens: every day at 19:00, when you get " +
            "a message from someone, when the phone starts charging. Ask Mike to make one, and " +
            "ask it to list or turn off the ones you have.",
    )

    // The two permissions are the whole reason this screen exists. A rule that
    // looks on and cannot run is the failure the user would otherwise only
    // notice by the thing not happening.
    if (overview.blocked > 0) {
        Explanation(
            "${overview.blocked} rule(s) are turned on but cannot run on this phone, because a " +
                "permission below is missing. They will start working the moment it is granted.",
        )
    }

    StatusLine(
        title = if (automations.notificationAccess) "Notification access: on" else "Notification access: off",
        detail = if (automations.notificationAccess) {
            "Rules can watch the apps they name"
        } else {
            "Rules that react to messages cannot run"
        },
        color = if (automations.notificationAccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Explanation(
        "Mike only ever looks at notifications from the apps your rules name, matches them on the " +
            "phone, and sends on only the parts a rule actually uses. Nothing is stored. Android " +
            "only lets you grant this yourself, from the list of apps on the next screen.",
    )
    Button(onClick = actions.onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = false,
            icon = Icons.Outlined.NotificationsNone,
            label = if (automations.notificationAccess) "Review notification access" else "Allow notification access",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }

    StatusLine(
        title = if (automations.exactAlarms) "Alarms: exact" else "Alarms: approximate",
        detail = if (automations.exactAlarms) {
            "A rule set for 19:00 runs at 19:00"
        } else {
            "A rule set for 19:00 may run up to an hour late"
        },
        color = if (automations.exactAlarms) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!automations.exactAlarms) {
        Explanation(
            "Without this Android batches the wake-up to save battery, so a rule fires whenever the " +
                "phone next wakes rather than at the time you asked for. Turn on \"Alarms & " +
                "reminders\" for Hey Mike.",
        )
        Button(onClick = actions.onOpenExactAlarmSettings, modifier = Modifier.fillMaxWidth()) {
            LoadingButtonContent(
                loading = false,
                icon = Icons.Outlined.Schedule,
                label = "Allow exact alarms",
                spinnerColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun ColumnScope.AssistantSettings(state: AgentUiState, actions: AgentUiActions) {
    StatusLine(
        title = if (state.isDefaultAssistant) "On" else "Off",
        detail = if (state.isDefaultAssistant) "Mike is this phone's digital assistant" else "Another app is the digital assistant",
        color = if (state.isDefaultAssistant) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Explanation(
        "Make Mike the digital assistant and holding the power button opens a live voice " +
            "conversation with Mike instead of Gemini. It never opens over the lock screen.",
    )
    if (!state.isDefaultAssistant) {
        // No API lets an app take this role for itself, so say where to tap.
        Explanation(
            "Android only lets you choose this yourself: pick Hey Mike under Default apps > " +
                "Digital assistant app. If holding the power button shows the power menu, turn on " +
                "\"Press and hold for assistant\" in the power button settings. \"Hey Google\" stays with Google.",
        )
    }
    Button(onClick = actions.onOpenAssistantSettings, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = false,
            icon = Icons.Outlined.Assistant,
            label = "Open assistant settings",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ColumnScope.FloatingControlSettings(state: AgentUiState, actions: AgentUiActions) {
    ReadinessLine(state, SetupItem.FLOATING_CONTROL)
    Explanation(
        "Device taps and screenshots run behind a small floating control, so a task can never " +
            "touch the screen invisibly. Without this permission device control refuses to start.",
    )
    Button(onClick = actions.onOpenOverlayPermission, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = false,
            icon = Icons.Outlined.PictureInPictureAlt,
            label = if (state.permissions.overlay) "Review overlay permission" else "Allow display over other apps",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ColumnScope.WirelessAdbSettings(state: AgentUiState, actions: AgentUiActions) {
    var pairCode by rememberSaveable { mutableStateOf("") }
    var pairPort by rememberSaveable { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val busy = state.isPairing || state.isConnecting

    // Look once when the page opens: the pairing port is advertised over mDNS,
    // so in the normal case the user only has to type the code.
    LaunchedEffect(Unit) {
        if (!busy && !state.isDiscoveringAdb) actions.onDiscover()
    }
    LaunchedEffect(state.discoveredEndpoints) {
        if (pairPort.isBlank()) {
            state.discoveredEndpoints.singleOrNull { it.pairing }?.let { pairPort = it.port.toString() }
        }
    }
    LaunchedEffect(state.adbStatus.phase) {
        // Pairing codes are short lived. Keep them only while the form is in use.
        if (state.adbStatus.phase == ConnectionPhase.CONNECTING || state.adbStatus.phase == ConnectionPhase.CONNECTED) {
            pairCode = ""
        }
        // A failure is the moment the manual port is worth offering.
        if (state.adbStatus.phase == ConnectionPhase.ERROR) advanced = true
    }

    var confirmMike by rememberSaveable { mutableStateOf(false) }
    val canHandOff = state.adbStatus.phase != ConnectionPhase.CONNECTED && !busy &&
        state.a11yStatus.connected && state.permissions.overlay && !state.runState.active
    if (state.adbStatus.phase != ConnectionPhase.CONNECTED) {
        Explanation("With this, Mike can also run system commands for exact changes, move and organize files, and install or remove apps.")
        Button(onClick = { confirmMike = true }, enabled = canHandOff, modifier = Modifier.fillMaxWidth()) {
            LoadingButtonContent(
                loading = false,
                icon = Icons.Outlined.Wifi,
                label = "Let Mike set it up",
                spinnerColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Explanation(
            if (canHandOff) "Mike turns on Wireless debugging and pairs while you watch. Or do it yourself below."
            else "Mike needs screen access and the floating Stop button to do this. Or do it yourself below.",
        )
        if (confirmMike) {
            WirelessSetupDialog(
                onAllow = { confirmMike = false; actions.onLetMikeSetUpWireless() },
                onDismiss = { confirmMike = false },
            )
        }
    }
    StatusLine(
        title = readableConnectionPhase(state.adbStatus.phase),
        detail = buildString {
            append(state.adbStatus.message)
            state.adbStatus.port?.let { append(" · port ").append(it) }
        },
        color = readinessColor(setupState(state, SetupItem.WIRELESS_ADB)),
    )
    Explanation(
        "A second way to control this phone, and the one that can type any character and install files. " +
            "Turn on Wireless debugging, tap \"Pair device with pairing code\", and enter the six digits it shows.",
    )
    // The code the system shows is wiped the moment its dialog closes, so the
    // app reads it off that dialog rather than asking the user to carry it back.
    Button(onClick = actions.onCapturePairing, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = state.isPairing,
            icon = Icons.Outlined.Wifi,
            label = "Open pairing dialog and connect",
            loadingLabel = "Pairing…",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
    Explanation(
        if (state.a11yStatus.connected) {
            "Tap it, then choose \"Pair device with pairing code\". The six digits and the port are read from that " +
                "dialog and used straight away — they never leave the phone or reach the model."
        } else {
            "Screen control is off, so the code cannot be read automatically. Turn it on, or type the code below."
        },
    )
    TextButton(onClick = actions.onOpenWirelessSettings, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(loading = false, icon = Icons.Outlined.Settings, label = "Open wireless debugging settings")
    }
    OutlinedTextField(
        value = pairCode,
        onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
        label = { Text("Pairing code") },
        supportingText = { Text("The six digits in the pairing dialog.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pairPort,
        onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
        label = { Text("Pairing port") },
        supportingText = { Text("Filled in automatically while the pairing dialog is open.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { actions.onPair(pairCode.trim(), pairPort.trim()) },
        enabled = pairCode.length == 6 && pairPort.toIntOrNull() != null && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LoadingButtonContent(
            loading = state.isPairing,
            icon = Icons.Outlined.Key,
            label = "Pair with the typed code",
            loadingLabel = "Pairing…",
        )
    }
    Explanation("Either way, the connect port is found automatically once pairing succeeds.")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = actions.onDisconnect,
            enabled = state.adbStatus.phase == ConnectionPhase.CONNECTED,
            modifier = Modifier.weight(1f),
        ) {
            LoadingButtonContent(loading = false, icon = Icons.Outlined.Close, label = "Disconnect")
        }
        TextButton(
            onClick = actions.onForgetPairing,
            enabled = state.adbStatus.phase != ConnectionPhase.PAIRING && state.adbStatus.phase != ConnectionPhase.CONNECTING,
            modifier = Modifier.weight(1f),
        ) {
            LoadingButtonContent(loading = false, icon = Icons.Outlined.DeleteOutline, label = "Forget pairing")
        }
    }

    Explanation("Typing needs no keyboard setup: the agent switches to its own input method for the moment it types, then puts yours back.")

    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
        Text(if (advanced) "Hide manual connection" else "Enter the port manually")
    }
    if (advanced) {
        // Kept because mDNS is blocked on some networks and by some VPN or
        // private-DNS setups; without this those phones could never connect.
        Explanation("Use this if discovery is blocked on your network. The connect port is the one on the main Wireless debugging screen.")
        OutlinedTextField(
            value = connectPort,
            onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
            label = { Text("Connect port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = actions.onDiscover,
                enabled = !state.isDiscoveringAdb,
                modifier = Modifier.weight(1f),
            ) {
                LoadingButtonContent(
                    loading = state.isDiscoveringAdb,
                    icon = Icons.Outlined.Refresh,
                    label = "Discover",
                    loadingLabel = "Looking…",
                )
            }
            Button(
                onClick = { actions.onConnect(connectPort.trim()) },
                enabled = connectPort.toIntOrNull() != null && !busy,
                modifier = Modifier.weight(1f),
            ) {
                LoadingButtonContent(
                    loading = state.isConnecting,
                    icon = Icons.Outlined.Wifi,
                    label = "Connect",
                    loadingLabel = "Connecting…",
                    spinnerColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (state.discoveredEndpoints.isEmpty()) {
            Explanation("No endpoints found yet.")
        } else {
            Text("Discovered endpoints", style = MaterialTheme.typography.labelLarge)
            state.discoveredEndpoints.forEach { endpoint ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (endpoint.pairing) Icons.Outlined.Key else Icons.Outlined.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${endpoint.host}:${endpoint.port} · ${if (endpoint.pairing) "pairing" else "connect"}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        if (endpoint.pairing) pairPort = endpoint.port.toString() else connectPort = endpoint.port.toString()
                    }) { Text("Use") }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.NotificationSettings(state: AgentUiState, actions: AgentUiActions) {
    ReadinessLine(state, SetupItem.NOTIFICATIONS)
    Explanation(
        "The agent runs in a foreground service so a task survives leaving the app. " +
            "Without notifications you cannot see that it is running or stop it from outside the app.",
    )
    Button(onClick = actions.onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = false,
            icon = Icons.Outlined.NotificationsNone,
            label = if (state.permissions.notifications) "Review notification settings" else "Allow notifications",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ColumnScope.InstallUpdatesSettings(state: AgentUiState, actions: AgentUiActions) {
    ReadinessLine(state, SetupItem.INSTALL_UPDATES)
    Explanation(
        "Updates are downloaded from GitHub and installed by Android's package installer, " +
            "which needs permission to install apps from this app.",
    )
    Button(onClick = actions.onOpenInstallPermission, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = false,
            icon = Icons.Outlined.Download,
            label = if (state.permissions.installUnknownApps) "Review install permission" else "Allow installing updates",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ColumnScope.MicrophoneSettings(state: AgentUiState, actions: AgentUiActions) {
    ReadinessLine(state, SetupItem.MICROPHONE)
    Explanation("Only used while a voice conversation is open. Text chat and device control never touch the microphone.")
    Button(onClick = actions.onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(
            loading = false,
            icon = Icons.Outlined.MicNone,
            label = if (state.permissions.microphone) "Review app permissions" else "Open app permissions",
            spinnerColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ColumnScope.ModelSettings(state: AgentUiState, actions: AgentUiActions) {
    var expanded by remember { mutableStateOf(false) }
    if (state.isLoadingModels) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadingButtonContent(loading = true, icon = Icons.Outlined.Tune, label = "Loading models…")
        }
    } else if (state.availableModels.isEmpty()) {
        Explanation("Models are unavailable until the engine connects.")
    } else {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.selectedModel ?: "Choose a model", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose model")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        trailingIcon = if (model == state.selectedModel) ({ Icon(Icons.Outlined.Check, contentDescription = null) }) else null,
                        onClick = {
                            expanded = false
                            actions.onModelSelected(model)
                        },
                    )
                }
            }
        }
        Explanation("Reasoning effort is chosen per message, next to the model name in the composer.")
    }
}

@Composable
private fun ColumnScope.JevSettings(state: AgentUiState, actions: AgentUiActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Use Jev as the UI engine", fontWeight = FontWeight.Medium)
            Text(
                when {
                    !state.jevEnabled -> "Off. The agent cannot use the Jev tool."
                    !state.jevTokenConfigured -> "On, but a token is still needed."
                    else -> "On. Jev can run complete UI tasks through Hey Mike's device controls."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.jevEnabled, onCheckedChange = actions.onJevEnabledChanged)
    }
    Explanation(
        "One agent call gives Jev the complete goal. Jev then observes, chooses and checks the next screen " +
            "without waiting for the main model between taps. It can use every supported UI action, including " +
            "exact text and sliders; Hey Mike still executes the actions, handles approvals, and stops locally.",
    )
    var token by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(if (state.jevTokenConfigured) "Replace Jev API token" else "Jev API token") },
        placeholder = if (state.jevTokenConfigured) ({ Text("Token saved securely") }) else null,
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { actions.onSaveJevToken(token); token = "" },
            enabled = token.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) { Text("Save token") }
        OutlinedButton(
            onClick = { token = ""; actions.onClearJevToken() },
            enabled = state.jevTokenConfigured,
            modifier = Modifier.weight(1f),
        ) { Text("Clear token") }
    }
    Text(
        "The goal and current on-screen labels are sent to api.typesafe.ai while Jev runs. The token is " +
            "encrypted with Android Keystore, is not shown to the agent, and is not stored in chat history.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.WorkspaceSettings(state: AgentUiState, actions: AgentUiActions) {
    if (state.isLoadingWorkspace) {
        Explanation("Loading workspace files…")
    } else if (state.workspaceError != null) {
        Text(state.workspaceError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    } else {
        Explanation(
            if (state.workspaceFiles.isEmpty()) "No workspace files loaded." else "${state.workspaceFiles.size} files loaded.",
        )
    }
    OutlinedButton(onClick = actions.onOpenWorkspaceFiles, modifier = Modifier.fillMaxWidth()) {
        LoadingButtonContent(loading = false, icon = Icons.Outlined.Folder, label = "Open workspace files")
    }
}

@Composable
private fun ColumnScope.UsageSettings(state: AgentUiState, actions: AgentUiActions) {
    val usage = state.tokenUsage
    // The same bars the top-bar meter draws, so the two places can never
    // disagree about what "74% left" looks like.
    val windows = remember(state.usageLimits) {
        UsageSummary.windows(state.usageLimits, System.currentTimeMillis() / 1000L)
    }
    if (windows.isEmpty()) Explanation("Account quota is not available for this account yet.")
    windows.forEach { window -> UsageWindowRow(window) }
    Text(
        if (usage == null) "Token usage is not available yet." else
            "${usage.total} tokens · ${usage.input} input · ${usage.output} output · ${usage.cachedInput} cached",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = actions.onRefreshAccount,
        enabled = !state.isRefreshingAccount,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LoadingButtonContent(
            loading = state.isRefreshingAccount,
            icon = Icons.Outlined.Refresh,
            label = "Refresh usage",
            loadingLabel = "Refreshing…",
        )
    }
}

@Composable
private fun ColumnScope.UpdateSettings(state: AgentUiState, actions: AgentUiActions) {
    StatusLine(
        title = "Version ${dev.androidagent.app.BuildConfig.VERSION_NAME}",
        detail = when (val status = state.updateStatus) {
            is UpdateStatus.Checking -> "Checking for updates…"
            is UpdateStatus.UpToDate -> "App is up to date (${status.currentVersion})"
            is UpdateStatus.Available -> "New version v${status.info.latestVersionName} available"
            is UpdateStatus.Downloading -> "Downloading update: ${(status.progress * 100).toInt()}%"
            is UpdateStatus.ReadyToInstall -> "Update downloaded and ready to install"
            is UpdateStatus.Error -> "Check failed: ${status.message}"
            UpdateStatus.Idle -> state.updateInfo?.let {
                if (it.isUpdateAvailable) "Update v${it.latestVersionName} available" else "Up to date"
            } ?: "Check GitHub for new releases"
        },
        color = when (state.updateStatus) {
            is UpdateStatus.Available, is UpdateStatus.ReadyToInstall -> MaterialTheme.colorScheme.secondary
            is UpdateStatus.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    (state.updateStatus as? UpdateStatus.Downloading)?.let { downloading ->
        LinearProgressIndicator(progress = { downloading.progress }, modifier = Modifier.fillMaxWidth())
    }
    if (state.updateStatus is UpdateStatus.ReadyToInstall) {
        Button(onClick = actions.onInstallUpdate, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
    } else if (state.updateStatus is UpdateStatus.Available ||
        (state.updateInfo?.isUpdateAvailable == true && state.updateStatus !is UpdateStatus.Downloading)
    ) {
        Button(onClick = actions.onDownloadUpdate, modifier = Modifier.fillMaxWidth()) { Text("Download update") }
    }
    if (!state.permissions.installUnknownApps) {
        Explanation("Installing an update also needs permission to install apps from this app, under \"Install updates\".")
    }
    OutlinedButton(
        onClick = actions.onCheckForUpdates,
        enabled = state.updateStatus !is UpdateStatus.Checking && state.updateStatus !is UpdateStatus.Downloading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LoadingButtonContent(
            loading = state.updateStatus is UpdateStatus.Checking,
            icon = Icons.Outlined.Refresh,
            label = "Check for updates",
            loadingLabel = "Checking…",
        )
    }
}

private fun setupState(state: AgentUiState, item: SetupItem): SetupState =
    state.setupRows().firstOrNull { it.item == item }?.state ?: SetupState.PENDING

@Composable
internal fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                content()
            },
        )
    }
}

@Composable
internal fun StatusLine(title: String, detail: String, color: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(color = color, modifier = Modifier.padding(top = 5.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun readableRuntimePhase(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.MISSING -> "Runtime not ready"
    RuntimePhase.PREPARING -> "Preparing runtime"
    RuntimePhase.READY -> "Runtime ready"
    RuntimePhase.RUNNING -> "Runtime running"
    RuntimePhase.ERROR -> "Runtime error"
}

internal fun readableConnectionPhase(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.DISCONNECTED -> "ADB disconnected"
    ConnectionPhase.DISCOVERING -> "Discovering ADB"
    ConnectionPhase.PAIRING -> "Pairing ADB"
    ConnectionPhase.CONNECTING -> "Connecting ADB"
    ConnectionPhase.CONNECTED -> "ADB connected"
    ConnectionPhase.ERROR -> "ADB error"
}

@Composable
private fun SendApprovalSettings(state: AgentUiState, actions: AgentUiActions) {
    Text(
        "Mike asks before pressing Send in another app. Messages you chose to always allow go without asking. " +
            "Remove one to be asked again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.sendGrants.isEmpty()) {
        Text("Nothing is always allowed.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    state.sendGrants.forEach { grant ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(grant.recipient ?: "Anyone", style = MaterialTheme.typography.bodyLarge)
                Text("in ${grant.appLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { actions.onRemoveSendGrant(grant) }) { Text("Remove") }
        }
    }
}
