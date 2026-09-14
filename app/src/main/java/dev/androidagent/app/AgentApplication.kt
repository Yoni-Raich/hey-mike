package dev.androidagent.app

import android.app.Application
import android.content.Intent
import dev.androidagent.a11y.A11yDeviceTools
import dev.androidagent.a11y.WhatsAppChatObserver
import dev.androidagent.adb.AndroidAdbTransport
import dev.androidagent.core.AgentCoordinator
import dev.androidagent.core.CompositeDeviceToolGateway
import dev.androidagent.core.KnowledgeStore
import dev.androidagent.core.KnowledgeToolGateway
import dev.androidagent.core.ObservationState
import dev.androidagent.core.WorkflowStore
import dev.androidagent.core.WorkflowToolGateway
import dev.androidagent.core.SessionRunQueue
import dev.androidagent.core.ChatModeController
import dev.androidagent.core.ChatModeStopReason
import dev.androidagent.devicetools.AndroidDeviceTools
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.overlay.FloatingControlOverlay
import dev.androidagent.runtime.AndroidRuntimeHost
import dev.androidagent.workspace.LocalSessionStore
import dev.androidagent.workspace.WorkspaceSeeder
import dev.androidagent.voice.AndroidRealtimeVoiceController
import kotlinx.coroutines.*
import java.io.File

class AgentApplication : Application() {
    lateinit var graph: AgentGraph
        private set
    override fun onCreate() { super.onCreate(); graph = AgentGraph(this) }
}

class AgentGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = LocalSessionStore(app)
    val runtime = AndroidRuntimeHost(app)
    val engine = CodexEngine(runtime)
    val adb = AndroidAdbTransport(app)
    private lateinit var runCoordinator: AgentCoordinator
    val chatModeController = ChatModeController()
    lateinit var chatMode: ChatModeRuntime
        private set
    // Declared before the gateways: they take `overlay` as a constructor argument,
    // so it must already be initialised rather than captured through a lambda.
    val overlay = FloatingControlOverlay(
        app,
        onStop = {
            chatModeController.stop(ChatModeStopReason.STOPPED)
            queue.pause()
            runCoordinator.stop()
            if (voice.state.value.active) scope.launch { voice.stop() }
        },
        onSend = { text -> runCoordinator.steer(text) },
        onOpenApp = { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) },
        onExitChatMode = { chatMode.stop(ChatModeStopReason.USER_EXIT) },
        onApproval = { allow ->
            runCoordinator.state.value.approval?.requestId?.let { requestId -> runCoordinator.approve(requestId, allow) }
        },
    )
    // One counter for every backend, so an observation revision never moves
    // backwards when a call falls through from one gateway to another.
    private val observations = ObservationState()
    val adbTools = AndroidDeviceTools(
        adb,
        BuildConfig.APPLICATION_ID + "/dev.androidagent.app.ime.AgentInputMethodService",
        observations,
        { x, y -> overlay.avoidTouch(x, y) },
    ) { hidden -> overlay.setCaptureHidden(hidden) }
    val a11yTools = A11yDeviceTools(
        app,
        observations,
        avoidTouch = { x, y -> overlay.avoidTouch(x, y) },
        // Used only where Android cannot leave our window out of a screenshot.
        observationVisibility = { hidden -> overlay.setCaptureHidden(hidden) },
        authorizeIntent = { request, dispatch -> runCoordinator.authorizeLocalIntent(request, dispatch) },
        authorizeSend = { request, dispatch ->
            val chat = chatModeController.state.value
            val watching = chat.active
            val voiceSessionId = chat.voiceSessionId
            val revision = chat.contextRevision
            if (watching) {
                val target = request.recipient ?: request.appLabel
                val body = request.message?.replace(Regex("\\s+"), " ")?.trim()?.take(120) ?: "current draft"
                overlay.setChatApproval("Send to $target: $body")
            }
            try {
                runCoordinator.authorizeSend(
                    request,
                    forceFresh = watching,
                    isContextCurrent = {
                        !watching || (voiceSessionId != null && chatMode.isCurrentBeforeSend(voiceSessionId, revision))
                    },
                    keepSourceForeground = watching,
                    dispatch = dispatch,
                )
            } finally {
                if (watching) overlay.setChatApproval(null)
            }
        },
        onMessageSent = { request -> chatMode.onMessageSent(request) },
        // Asking raised this app over the chat. Stepping back uncovers that
        // chat exactly as it was, draft included; relaunching the other app
        // lands on its home screen instead.
        leaveApprovalScreen = {
            foregroundActivity?.get()?.let { activity -> activity.runOnUiThread { activity.moveTaskToBack(true) } }
        },
    )
    /** The resumed activity, if any, so an approval can step out of the way. */
    @Volatile var foregroundActivity: java.lang.ref.WeakReference<android.app.Activity>? = null
    /** "Always allow" answers to send approvals, signed so the agent cannot add its own. */
    val sendGrants = KeystoreSendGrantStore(app)
    // Under homeDirectory, which is global across chats and is the one place
    // WorkspaceSeeder does not rewrite on every access.
    val knowledge = KnowledgeStore(KnowledgeStore.directoryIn(runtime.homeDirectory))
    val knowledgeTools = KnowledgeToolGateway(knowledge)
    // Accessibility first: it needs no ADB, keeps the phone's own settings
    // untouched, and falls through to ADB for anything it cannot do. The
    // knowledge gateway shares no tool name with either device backend, so its
    // position in the chain only decides where its names appear in the list.
    val workflows = WorkflowStore(WorkflowStore.directoryIn(runtime.homeDirectory))
    // The engine dispatches steps back at the composite, which also contains
    // this gateway, so the router is resolved per call rather than captured.
    val workflowTools = WorkflowToolGateway(
        workflows,
        router = { tools },
        canDispatchAction = chatModeController::canDispatchAction,
    )
    // Explicit type: the workflow gateway's router lambda refers back to this
    // property, and an inferred type would make that a recursive definition.
    val tools: CompositeDeviceToolGateway = CompositeDeviceToolGateway(
        listOf(workflowTools, knowledgeTools, a11yTools, adbTools),
        allowFallback = { name ->
            !chatModeController.state.value.active || name in setOf("read_ui", "screenshot", "device_status")
        },
    )
    val voice = AndroidRealtimeVoiceController(app, engine, scope)
    val coordinator: AgentCoordinator
        get() = runCoordinator
    val queue: SessionRunQueue
    init {
        runCoordinator = AgentCoordinator(
            scope, engine, sessions, tools, overlay,
            sendGrants = sendGrants,
            chatMode = chatModeController,
            adbStatus = { adb.status.value },
            // An approval card lives only in the app, and device control means
            // the app is not in front. Raising it is what makes the approval
            // answerable at all.
            bringToForeground = {
                runCatching {
                    app.startActivity(
                        android.content.Intent(app, MainActivity::class.java).addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        ),
                    )
                }
            },
        )
        chatMode = ChatModeRuntime(
            scope = scope,
            controller = chatModeController,
            observer = WhatsAppChatObserver(),
            voice = voice,
            coordinator = { runCoordinator },
        )
        scope.launch {
            chatModeController.state.collect { state ->
                val status = when (state.phase) {
                    dev.androidagent.core.ChatModePhase.OFFERED -> "Chat Mode available"
                    dev.androidagent.core.ChatModePhase.WATCHING -> "Chat Mode · ${state.chatTitle ?: "WhatsApp"}"
                    dev.androidagent.core.ChatModePhase.UPDATE_PENDING -> "New WhatsApp message"
                    else -> null
                }
                overlay.setChatMode(status)
            }
        }
        queue = SessionRunQueue(scope, coordinator, sessions)
        runCatching {
            WorkspaceSeeder.installDefaultSkills(runtime.homeDirectory, app)
        }
        // Separate from the skills so a failed skill install cannot leave the
        // user without their preferences, or the reverse.
        runCatching {
            WorkspaceSeeder.ensureGlobalPreferences(runtime.homeDirectory, File(app.filesDir, "sessions"), app)
        }
    }
}
