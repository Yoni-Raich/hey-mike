package dev.androidagent.app.ui

import dev.androidagent.app.update.AppUpdateInfo
import dev.androidagent.app.update.UpdateStatus
import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AgentModel
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.AdbEndpoint
import dev.androidagent.a11y.A11yStatus
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ChatModeState
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
    val runtimeStatus: RuntimeStatus = RuntimeStatus(),
    /**
     * Why the tunnel to OpenAI last failed, in one sentence, or null when it
     * has not. Without this the user sees only a retry counter and a 502.
     */
    val networkDiagnostic: String? = null,
    val accountStatus: AccountStatus? = null,
    val availableModels: List<String> = emptyList(),
    val modelCatalog: List<AgentModel> = emptyList(),
    val availableSkills: List<AgentSkill> = emptyList(),
    val selectedModel: String? = null,
    val selectedReasoningEffort: String? = null,
    /** Turns sent from the composer run in Codex plan mode. */
    val planMode: Boolean = false,
    val voiceState: VoiceState = VoiceState(),
    val chatModeState: ChatModeState = ChatModeState(),
    val voiceTranscript: String = "",
    val voiceTranscriptRole: String? = null,
    val voiceMuted: Boolean = false,
    /** Status line while a power-button press is setting up voice, before the call exists. */
    val voiceSummon: String? = null,
    /** Sends the user chose to always allow, for review in Settings. */
    val sendGrants: List<dev.androidagent.core.SendGrant> = emptyList(),
    val isDrawerOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
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
    val onExitChatMode: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onCloseSettings: () -> Unit = {},
    val onPrepareRuntime: () -> Unit = {},
    val onLogin: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onRefreshAccount: () -> Unit = {},
    val onPair: (code: String, port: String) -> Unit = { _, _ -> },
    val onConnect: (port: String) -> Unit = {},
    val onDiscover: () -> Unit = {},
    val onOpenWirelessSettings: () -> Unit = {},
    val onOpenAccessibilitySettings: () -> Unit = {},
    val onOpenAssistantSettings: () -> Unit = {},
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
