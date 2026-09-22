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

import dev.androidagent.app.update.AppUpdateInfo
import dev.androidagent.app.update.UpdateStatus
import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AgentModel
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.AdbEndpoint
import dev.androidagent.a11y.A11yStatus
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.AutomationOverview
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ChatSession
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RunState
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.core.SetupChecklist
import dev.androidagent.core.SetupRow
import dev.androidagent.core.SetupSignals
import dev.androidagent.core.VoiceState

/**
 * A file that is waiting to be sent with the next user message.
 * The root app owns the picker and decides how the path is resolved.
 */
data class PendingAttachment(
    val id: String,
    val name: String,
    val path: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
)

data class WorkspaceFileItem(
    val path: String,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
    val isDirectory: Boolean = false,
)

enum class AgentCardState { ACTIVE, COMPLETE, ERROR, BLOCKED }

data class ToolStatusCard(
    val id: String,
    val title: String,
    val detail: String = "",
    val state: AgentCardState = AgentCardState.ACTIVE,
)

data class AgentStatusCard(
    val id: String,
    val title: String,
    val detail: String = "",
    val state: AgentCardState = AgentCardState.COMPLETE,
)

/**
 * Android grants the app asks for outside the permission dialog flow. They are
 * changed in system Settings, so they are re-read when the app resumes rather
 * than observed.
 */
data class DevicePermissions(
    val overlay: Boolean = false,
    val notifications: Boolean = false,
    val installUnknownApps: Boolean = false,
    val microphone: Boolean = false,
)

/**
 * All data needed by the screen. It is intentionally free of ViewModel or
 * engine references so the root app can map its own flows into this state.
 */
data class AgentUiState(
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null,
    val activeSessionTitle: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val toolCards: List<ToolStatusCard> = emptyList(),
    val statusCards: List<AgentStatusCard> = emptyList(),
    val attachments: List<PendingAttachment> = emptyList(),
    val workspaceFiles: List<WorkspaceFileItem> = emptyList(),
    val discoveredEndpoints: List<AdbEndpoint> = emptyList(),
    val runState: RunState = RunState(),
    val queuedTurns: List<dev.androidagent.core.QueuedTurn> = emptyList(),
    val queuePaused: Boolean = false,
    val tokenUsage: dev.androidagent.core.TokenUsage? = null,
    val usageLimits: List<dev.androidagent.core.UsageLimit> = emptyList(),
    val adbStatus: AdbStatus = AdbStatus(),
    val a11yStatus: A11yStatus = A11yStatus(declaredEnabled = false, connected = false),
    val permissions: DevicePermissions = DevicePermissions(),
    /** Holding the power button opens Mike. Changed in system Settings, so re-read on resume. */
    val isDefaultAssistant: Boolean = false,
    /** Standing rules and the two permissions they need. Re-read on resume, like the rest. */
    val automations: AutomationsStatus = AutomationsStatus(),
    val runtimeStatus: RuntimeStatus = RuntimeStatus(),
    /**
     * Why the tunnel to OpenAI last failed, in one sentence, or null when it
     * has not. Without this the user sees only a retry counter and a 502.
     */
    val networkDiagnostic: String? = null,
    val accountStatus: AccountStatus? = null,
    /** Every Codex sign-in saved on this phone and which one is live. */
    val savedAccounts: dev.androidagent.core.AccountVaultState = dev.androidagent.core.AccountVaultState(),
    /** An account switch is stopping and restarting Codex. */
    val isSwitchingAccount: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val modelCatalog: List<AgentModel> = emptyList(),
    val availableSkills: List<AgentSkill> = emptyList(),
    val selectedModel: String? = null,
    val selectedReasoningEffort: String? = null,
    /** Turns sent from the composer run in Codex plan mode. */
    val planMode: Boolean = false,
    val voiceState: VoiceState = VoiceState(),
    val voiceTranscript: String = "",
    val voiceTranscriptRole: String? = null,
    val voiceMuted: Boolean = false,
    /** Status line while a power-button press is setting up voice, before the call exists. */
    val voiceSummon: String? = null,
    /** Sends the user chose to always allow, for review in Settings. */
    val sendGrants: List<dev.androidagent.core.SendGrant> = emptyList(),
    /** Jev status never contains the token itself. */
    val jevEnabled: Boolean = false,
    val jevTokenConfigured: Boolean = false,
    val isDrawerOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
    /** The standing-rules sheet, opened from the panel's strip. */
    val isAutomationsOpen: Boolean = false,
    /** The chat folder's file sheet is showing. */
    val isWorkspaceOpen: Boolean = false,
    val isLoadingSessions: Boolean = false,
    val isLoadingMessages: Boolean = false,
    val isPreparingRuntime: Boolean = false,
    val isDiscoveringAdb: Boolean = false,
    val isPairing: Boolean = false,
    val isConnecting: Boolean = false,
    val isRefreshingAccount: Boolean = false,
    val isLoadingModels: Boolean = false,
    val isLoadingSkills: Boolean = false,
    val isLoadingWorkspace: Boolean = false,
    val workspaceError: String? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val updateStatus: UpdateStatus = UpdateStatus.Idle,
    val updateInfo: AppUpdateInfo? = null,
    val isUpdateBannerVisible: Boolean = true,
)

/**
 * UI events are callbacks so the root app can keep all device and engine
 * operations behind its core gateways.
 */
data class AgentUiActions(
    val onDrawerChanged: (Boolean) -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onAttach: () -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onSend: (String, List<PendingAttachment>) -> Unit = { _, _ -> },
    val onSteer: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onCancelQueued: (String) -> Unit = {},
    val onResumeQueue: () -> Unit = {},
    val onVoiceToggle: () -> Unit = {},
    val onVoiceMuteToggle: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onCloseSettings: () -> Unit = {},
    val onPrepareRuntime: () -> Unit = {},
    val onLogin: () -> Unit = {},
    val onLogout: () -> Unit = {},
    /** Sign in to one more account; the live one stays saved. */
    val onAddAccount: () -> Unit = {},
    /** Make a saved account the live one. Chats are untouched. */
    val onSwitchAccount: (String) -> Unit = {},
    /** Forget a saved account that is not live. */
    val onRemoveAccount: (String) -> Unit = {},
    val onRefreshAccount: () -> Unit = {},
    val onPair: (code: String, port: String) -> Unit = { _, _ -> },
    val onConnect: (port: String) -> Unit = {},
    val onDiscover: () -> Unit = {},
    val onOpenWirelessSettings: () -> Unit = {},
    val onOpenAccessibilitySettings: () -> Unit = {},
    val onOpenAssistantSettings: () -> Unit = {},
    /** Notification access: the one permission no app can grant itself, and rules need it. */
    val onOpenNotificationAccess: () -> Unit = {},
    /** Alarms & reminders, so a rule that says 19:00 lands at 19:00 rather than whenever. */
    val onOpenExactAlarmSettings: () -> Unit = {},
    /** Open the rules list from the panel's strip. */
    val onOpenAutomations: () -> Unit = {},
    val onCloseAutomations: () -> Unit = {},
    /** Turn one rule on or off. */
    val onToggleRule: (id: String, enabled: Boolean) -> Unit = { _, _ -> },
    /** Fire one rule now: naming it supplies its trigger, nothing else is waived. */
    val onRunRule: (String) -> Unit = {},
    /** Delete one rule for good. The screen asks first. */
    val onDeleteRule: (String) -> Unit = {},
    /** The saved rule as editable JSON, or null when it cannot be read. */
    val ruleDefinition: (String) -> String? = { null },
    /** Save an edited rule. Null on success, else the reason it was refused. */
    val onSaveRule: (id: String, json: String) -> String? = { _, _ -> null },
    val onOpenAppInfo: () -> Unit = {},
    val onOpenOverlayPermission: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onForgetPairing: () -> Unit = {},
    val onModelSelected: (String) -> Unit = {},
    val onReasoningEffortSelected: (String?) -> Unit = {},
    val onRenameSession: (sessionId: String, title: String) -> Unit = { _, _ -> },
    val onCompact: () -> Unit = {},
    val onTogglePlanMode: () -> Unit = {},
    val onShowStatus: () -> Unit = {},
    val onDeleteSession: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onOpenWorkspaceFiles: () -> Unit = {},
    val onOpenWorkspaceFile: (WorkspaceFileItem) -> Unit = {},
    val onShareWorkspaceFile: (WorkspaceFileItem) -> Unit = {},
    val onCloseWorkspaceFiles: () -> Unit = {},
    val onApproval: (requestId: String, allow: Boolean) -> Unit = { _, _ -> },
    /** Allow a send and remember it for this contact or this whole app. */
    val onApproveAlways: (requestId: String, scope: dev.androidagent.core.ApprovalScope) -> Unit = { _, _ -> },
    val onRemoveSendGrant: (dev.androidagent.core.SendGrant) -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onDownloadUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onDismissUpdateBanner: () -> Unit = {},
    val onOpenInstallPermission: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    /** Open the pairing dialog and read the code from it instead of asking for it. */
    val onCapturePairing: () -> Unit = {},
    val onDismissInfo: () -> Unit = {},
    val onJevEnabledChanged: (Boolean) -> Unit = {},
    val onSaveJevToken: (String) -> Unit = {},
    val onClearJevToken: () -> Unit = {},
)

/** The setup checklist for this state, so no screen assembles the signals itself. */
internal fun AgentUiState.setupRows(): List<SetupRow> = SetupChecklist.rows(
    SetupSignals(
        runtimePhase = runtimeStatus.phase,
        signedIn = accountStatus?.signedIn,
        loginPending = accountStatus?.signedIn == false && accountStatus?.loginUrl != null,
        a11yConnected = a11yStatus.connected,
        a11yDeclared = a11yStatus.declaredEnabled,
        overlayGranted = permissions.overlay,
        notificationsGranted = permissions.notifications,
        installUpdatesGranted = permissions.installUnknownApps,
        microphoneGranted = permissions.microphone,
        adbPhase = adbStatus.phase,
        adbPort = adbStatus.port,
    ),
)

internal fun EngineEvent.Approval.detailsText(): String = details.toString().removeSurrounding("{", "}")

/**
 * The standing rules, as the panel and the settings screen read them.
 *
 * The [overview] is decided in `:core` — which rules are chips, which sentence
 * the strip shows, whether the chat name wears its dot — so this carries it
 * rather than recomputing anything. The two permissions ride alongside because
 * they are the reason a rule is blocked, and only the Android side can read
 * them.
 */
data class AutomationsStatus(
    val overview: AutomationOverview = AutomationOverview.EMPTY,
    /** Granted by hand in Settings; no app can grant it itself. */
    val notificationAccess: Boolean = false,
    /** False means a rule that says 19:00 may land an hour later under Doze. */
    val exactAlarms: Boolean = true,
) {
    val total: Int get() = overview.summaries.size

    /** The settings hub row, where there is space for one line and no chips. */
    val summary: String
        get() = when {
            total == 0 -> "No rules yet"
            overview.blocked > 0 -> "${overview.enabled} on · ${overview.blocked} cannot run on this phone"
            overview.off > 0 -> "${overview.enabled} on · ${overview.off} off"
            else -> "${overview.enabled} on"
        }
}
