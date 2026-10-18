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

package dev.androidagent.app

import android.app.Application
import android.content.Intent
import dev.androidagent.a11y.A11yDeviceTools
import dev.androidagent.a11y.A11yServiceHandle
import dev.androidagent.adb.AndroidAdbTransport
import dev.androidagent.automations.AndroidAutomationActions
import dev.androidagent.automations.AutomationHost
import dev.androidagent.automations.AutomationHostOwner
import dev.androidagent.core.AccountUsageBook
import dev.androidagent.core.AgentCoordinator
import dev.androidagent.core.AgentRuns
import dev.androidagent.core.AutomationJournal
import dev.androidagent.core.AutomationLibrary
import dev.androidagent.core.AutomationToolGateway
import dev.androidagent.core.CodexAccountVault
import dev.androidagent.core.CompositeDeviceToolGateway
import dev.androidagent.core.KnowledgeStore
import dev.androidagent.core.KnowledgeToolGateway
import dev.androidagent.core.LastUsageStore
import dev.androidagent.core.ObservationState
import dev.androidagent.core.WorkflowConfirmationOutcome
import dev.androidagent.core.WorkflowCallMetadata
import dev.androidagent.core.WorkflowCallRegistry
import dev.androidagent.core.WorkflowLibrary
import dev.androidagent.core.WorkflowStore
import dev.androidagent.core.WorkflowToolGateway
import dev.androidagent.core.SessionRunQueue
import dev.androidagent.devicetools.AndroidDeviceTools
import dev.androidagent.devicetools.AndroidCapabilityTools
import dev.androidagent.devicetools.PhoneStoragePlace
import dev.androidagent.engineclaude.ClaudeCodeEngine
import dev.androidagent.engineclaude.McpToolServer
import dev.androidagent.engineclaude.McpToolServerFactory
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.mcp.LoopbackMcpServer
import dev.androidagent.runtime.AndroidClaudeHost
import dev.androidagent.overlay.FloatingControlOverlay
import dev.androidagent.runtime.AndroidRuntimeHost
import dev.androidagent.workspace.LocalSessionStore
import dev.androidagent.workspace.WorkspaceSeeder
import dev.androidagent.voice.AndroidRealtimeVoiceController
import kotlinx.coroutines.*
import java.io.File

class AgentApplication : Application(), AutomationHostOwner {
    lateinit var graph: AgentGraph
        private set

    /**
     * How an alarm receiver and the notification listener reach the host: the
     * system constructs those classes, so there is nowhere to inject one.
     */
    override val automationHost: AutomationHost?
        get() = if (::graph.isInitialized) graph.automationHost else null

    override fun onCreate() { super.onCreate(); graph = AgentGraph(this) }
}

class AgentGraph(private val app: Application) {
    private fun bringAppForward() {
        runCatching {
            app.startActivity(
                android.content.Intent(app, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = LocalSessionStore(app)
    val runtime = AndroidRuntimeHost(app)
    /** The phone's own Codex. */
    val phoneEngine = CodexEngine(runtime)
    /** Computers the user added, sealed with a Keystore key the agent's shell cannot use. */
    val computers = dev.androidagent.remote.RemoteStore(
        java.io.File(app.filesDir, "remote/computers.bin"),
        dev.androidagent.remote.KeystoreSecretBox(),
    )
    /** Dev channel only: the official release never ships delta APK transfer. */
    private val deltaEnabled = BuildConfig.DELTA_TRANSFER
    val transferCache = if (deltaEnabled) dev.androidagent.workspace.TransferBlobStore(java.io.File(app.filesDir, "transfer-cache/v1")) else null
    private val seedTransferCache = if (!deltaEnabled) null else scope.launch(Dispatchers.IO) {
        try { transferCache?.seedInstalled(java.io.File(app.applicationInfo.sourceDir)) }
        catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped }
        catch (_: Exception) { /* An optimization: storage failure keeps normal full copying available. */ }
    }
    val remote = dev.androidagent.remote.RemoteHub(
        computers,
        authTokens = { refresh, previous ->
            dev.androidagent.remote.PhoneAccountTokens(
                phoneEngine,
                java.io.File(runtime.codexHomeDirectory, "auth.json"),
            ).current(refresh, previous)
        },
        claudeScratch = java.io.File(runtime.runtimeRoot, "claude-computers"),
        deltaTransfers = transferCache?.let { cache ->
            dev.androidagent.core.DeltaTransfers(cache, dev.androidagent.workspace.NativeDeltaApplier())
        },
    )
    /**
     * Anthropic's Claude Code on this phone. Never bundled: it is downloaded
     * only when the user asks in Settings or onboarding, then run unmodified
     * through the packaged musl loader.
     */
    val claudeHost = AndroidClaudeHost(app)
    /**
     * Claude chats. One `claude` process per open chat, each with its own
     * loopback MCP server for the phone tools; the engine itself never
     * downloads anything.
     */
    val claudeEngine = ClaudeCodeEngine(
        claudeHost,
        toolServers = McpToolServerFactory { name, tools, call ->
            val server = LoopbackMcpServer(name, tools, call)
            object : McpToolServer {
                override fun start() = server.start().claudeMcpConfig(name)
                override fun stop() = server.stop()
            }
        },
    )
    /** What chats talk to: the phone's Codex, a computer's for a chat opened on one, or Claude for a Claude chat. */
    val engine = dev.androidagent.remote.RoutingAgentEngine(phoneEngine, remote, claudeEngine, sessions)
    val chatTitles = dev.androidagent.core.ChatTitleManager(sessions, engine)
    // Beside CODEX_HOME, never inside it: Codex must only see the live sign-in.
    val accounts = CodexAccountVault(runtime.codexHomeDirectory, java.io.File(runtime.runtimeRoot, "accounts"))
    // The last quota of every saved account, for the home screen widget.
    val usageBook = AccountUsageBook(java.io.File(runtime.runtimeRoot, "accounts/usage.json"))
    // Claude's last 5-hour and weekly limits, so the usage sheet has them after a restart.
    val claudeUsage = LastUsageStore(java.io.File(runtime.runtimeRoot, "claude-usage.json"))
    val adb = AndroidAdbTransport(app)
    private lateinit var runCoordinator: AgentRuns
    // Declared before the gateways: they take `overlay` as a constructor argument,
    // so it must already be initialised rather than captured through a lambda.
    val overlay = FloatingControlOverlay(
        app,
        onStop = { queue.pause(); runCoordinator.stop(); if (voice.state.value.active) scope.launch { voice.stop() } },
        onSend = { text -> runCoordinator.steerPhone(text) },
        onOpenApp = { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) },
        accessibilityService = { A11yServiceHandle.service.value },
    )
    // One counter for every backend, so an observation revision never moves
    // backwards when a call falls through from one gateway to another.
    private val observations = ObservationState()
    /** The copy moving between places now, for the progress banner. */
    val transfers = dev.androidagent.core.TransferMeter(scope)
    /**
     * copy_file: this chat's folder, the phone's shared storage and every
     * saved computer as places. A use case is a recipe in the
     * files-across-devices skill, never another tool.
     */
    val copyFiles = dev.androidagent.core.CopyFileGateway(
        phone = PhoneStoragePlace(app, adb),
        computers = { remote.filePlaces() },
        home = { workspace -> remote.fileHome(workspace) },
        meter = transfers,
        scratch = java.io.File(app.cacheDir, "copies"),
        publishFile = if (deltaEnabled) dev.androidagent.workspace.NativeAtomicPublisher()::commit else ::publishWithoutNative,
    )
    val adbTools = AndroidDeviceTools(
        adb,
        BuildConfig.APPLICATION_ID + "/dev.androidagent.app.ime.AgentInputMethodService",
        observations,
        { x, y -> overlay.avoidTouch(x, y) },
        { hidden -> overlay.setCaptureHidden(hidden) },
        // install_apk takes a file already on the phone, named as copy_file names it.
        phoneFile = { address, workspace -> copyFiles.phoneFile(address, workspace) },
    )
    val a11yTools = A11yDeviceTools(
        app,
        observations,
        avoidTouch = { x, y -> overlay.avoidTouch(x, y) },
        // Used only where Android cannot leave our window out of a screenshot.
        observationVisibility = { hidden -> overlay.setCaptureHidden(hidden) },
        authorizeIntent = { request, dispatch -> runCoordinator.authorizeLocalIntent(request, dispatch) },
        authorizeSend = { request, dispatch -> runCoordinator.authorizeSend(request, dispatch) },
        leaveApprovalScreen = { leaveApprovalScreen() },
    )

    /**
     * Asking raised this app over the one being driven. Stepping back uncovers
     * that app exactly as it was, draft or open sub-screen included;
     * relaunching it lands on its home screen instead.
     */
    private fun leaveApprovalScreen() {
        foregroundActivity?.get()?.let { activity -> activity.runOnUiThread { activity.moveTaskToBack(true) } }
    }
    /** The resumed activity, if any, so an approval can step out of the way. */
    @Volatile var foregroundActivity: java.lang.ref.WeakReference<android.app.Activity>? = null
    /** "Always allow" answers to send approvals, signed so the agent cannot add its own. */
    val sendGrants = KeystoreSendGrantStore(app)
    // Under homeDirectory, which is global across chats and is the one place
    // WorkspaceSeeder does not rewrite on every access.
    val knowledge = KnowledgeStore(KnowledgeStore.directoryIn(runtime.homeDirectory))
    val knowledgeTools = KnowledgeToolGateway(knowledge)
    val runtimePermissions = RuntimePermissionBroker(app)
    val capabilityTools = AndroidCapabilityTools(app) { requested ->
        runtimePermissions.request(requested)
    }
    private val workflowCalls = WorkflowCallRegistry(
        listOf(
            WorkflowCallMetadata(
                name = "contacts",
                readOnlyOperations = setOf("permission_status", "search", "list", "get"),
            ),
            WorkflowCallMetadata(
                name = "calendar",
                readOnlyOperations = setOf("permission_status", "list", "get"),
            ),
            WorkflowCallMetadata(
                name = "files_media",
                readOnlyOperations = setOf(
                    "permission_status", "list", "search", "info", "ws_list", "ws_read_text",
                ),
            ),
            WorkflowCallMetadata(
                name = "communications",
                readOnlyOperations = setOf("notification_access_status"),
            ),
            WorkflowCallMetadata(
                name = "apps_settings",
                readOnlyOperations = setOf("list_apps", "app_info", "permission_status"),
            ),
        ).associateBy { it.name },
    )
    // Accessibility first: it needs no ADB, keeps the phone's own settings
    // untouched, and falls through to ADB for anything it cannot do. The
    // knowledge gateway shares no tool name with either device backend, so its
    // position in the chain only decides where its names appear in the list.
    val workflows = WorkflowStore(WorkflowStore.directoryIn(runtime.homeDirectory))
    /** Declarative definitions `workflow_runner` executes, beside the literal step lists. */
    val workflowLibrary = WorkflowLibrary(WorkflowLibrary.directoryIn(runtime.homeDirectory))
    // The engine dispatches steps back at the composite, which also contains
    // this gateway, so the router is resolved per call rather than captured.
    val workflowTools = WorkflowToolGateway(
        workflows,
        { tools },
        library = workflowLibrary,
        // A sensitive step asks with the same card, and the same spoken "yes",
        // as a send. Without this the runner refuses such a step outright.
        // Stepping back after a yes is what keeps the sub-screen the workflow
        // reached: relaunching Settings reset it to its home page on a phone.
        confirm = { request ->
            runCoordinator.authorizeWorkflowStep(request).also { outcome ->
                if (outcome == WorkflowConfirmationOutcome.ALLOWED) leaveApprovalScreen()
            }
        },
        callRegistry = workflowCalls,
    )
    /** Standing rules, beside the workflows they name. */
    val automations = AutomationLibrary(AutomationLibrary.directoryIn(runtime.homeDirectory))
    /** Read by the panel to say when each rule last ran; written only by the host. */
    val automationJournal = AutomationJournal(AutomationJournal.fileIn(runtime.homeDirectory))
    private val automationActions = AndroidAutomationActions(
        context = app,
        coordinator = { runCoordinator },
        tools = { tools },
        queue = { queue },
        sessions = sessions,
        openAppIntent = {
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        voiceIntent = { request -> dev.androidagent.app.assist.AssistLaunch.voiceIntent(app, request) },
    )
    lateinit var automationHost: AutomationHost
        private set
    // Saved and reported dormant when this phone cannot serve a rule's trigger,
    // so the point of failure is when it is written rather than the first night
    // it quietly does not fire.
    val automationTools = AutomationToolGateway(
        library = automations,
        history = automationJournal,
        supportedTriggers = { if (::automationHost.isInitialized) automationHost.supportedTriggers() else emptySet() },
        // Naming a rule supplies its trigger, so a scheduled rule can be proved
        // without waiting for its hour. Everything else about it still applies.
        fireNow = { id -> if (::automationHost.isInitialized) automationHost.runNow(id) },
        // A rule the agent writes, edits or deletes changes when the alarm is
        // next due; without this it waited for an unrelated firing to be armed.
        onChanged = { if (::automationHost.isInitialized) automationHost.rearm() },
        deviceState = { if (::automationHost.isInitialized) automationHost.readDeviceState() else emptyMap() },
        supportedDeviceStates = { if (::automationHost.isInitialized) automationHost.supportedDeviceStates() else emptySet() },
        liveDeviceSnapshot = { if (::automationHost.isInitialized) automationHost.deviceSnapshot() else dev.androidagent.core.AutomationDeviceSnapshot() },
    )
    /** What the computers tool asks the screen to show; the view model clears it. */
    val computerRequests = kotlinx.coroutines.flow.MutableStateFlow<dev.androidagent.remote.ComputerUiRequest?>(null)
    val agentOpenRequests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    lateinit var sessionAgents: dev.androidagent.core.SessionAgents
        private set
    val computerTasks = dev.androidagent.remote.ComputerTasks(
        computers, sessions, scope,
        runner = { dev.androidagent.remote.RunsComputerTaskRunner(runCoordinator) },
        prepare = { id, path ->
            val ready = remote.setup.value[id].takeIf { it is dev.androidagent.remote.RemoteSetup.Ready }
                ?: remote.setUp(id, install = false)
            check(ready is dev.androidagent.remote.RemoteSetup.Ready) { "The computer is not ready: $ready" }
            remote.listFolders(id, path) // Validate the directory through the existing connection.
        },
    )
    /** The user's computers, from any chat. Passwords and bindings stay with the app. */
    val computerTools = dev.androidagent.remote.ComputerToolGateway(
        remote, sessions, computerRequests,
        tasks = computerTasks,
        bringToForeground = ::bringAppForward,
    )
    /** The coordinator running [key]'s chat (a chat's page is keyed by its workspace), if it is running. */
    private fun browserOwner(key: String) = coordinator.slots.firstOrNull { slot ->
        slot.state.value.active && slot.state.value.sessionId?.let { sessions.workspace(it).absolutePath == key } == true
    }
    val browserHost = dev.androidagent.web.AndroidBrowserHost(app, takeScreen = { key ->
        browserOwner(key)?.claimBrowserScreen() == true
    }, confirm = { key, summary ->
        val owner = browserOwner(key)
        owner?.claimBrowserScreen() == true && owner.authorizeWorkflowStep(dev.androidagent.core.WorkflowConfirmation(
            workflowId = "browser", stepId = "action", action = "browser",
            summary = summary, packageName = app.packageName, appLabel = "Mike browser",
        )) == dev.androidagent.core.WorkflowConfirmationOutcome.ALLOWED
    }, present = { key ->
        // The page opens on Hey Mike's own screen, in the chat it belongs to.
        browserOwner(key)?.state?.value?.sessionId?.let { chat ->
            runCatching {
                app.startActivity(
                    android.content.Intent(app, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(QuestionNotifier.EXTRA_OPEN_CHAT, chat)
                        .putExtra(EXTRA_OPEN_BROWSER, true),
                )
            }
        }
    })
    private fun runTools(): CompositeDeviceToolGateway = CompositeDeviceToolGateway(
        listOf(dev.androidagent.web.WebToolGateway(browserHost) { android.util.Log.i("MikeBrowserTiming", it) }, workflowTools, knowledgeTools, automationTools,
            computerTools, copyFiles, dev.androidagent.core.ChatToolGateway(), capabilityTools, a11yTools, adbTools),
        runtimeVersions = {
            val claude = claudeHost.updates.activePin.version.takeIf {
                claudeHost.installState.value.phase == dev.androidagent.runtime.ClaudeInstallPhase.INSTALLED
            } ?: "not installed"
            "Versions on phone: Mike ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "Codex ${runtime.bundledVersion ?: "unknown"} (bundled in APK), Claude Code $claude. " +
                "${runtime.updates.state.value.message}. Computer versions are in computers(mode=status)."
        },
    )
    /**
     * The shared surface: the workflow router, automations and voice use it.
     * Each chat's coordinator gets its own [runTools], so its browser gateway
     * is armed and revoked with that chat alone.
     */
    // Explicit type: the workflow gateway's router lambda refers back to this
    // property, and an inferred type would make that a recursive definition.
    val tools: CompositeDeviceToolGateway = runTools()
    val voice = AndroidRealtimeVoiceController(app, engine, scope, automationSnapshot = {
        if (::automationHost.isInitialized) automationHost.deviceSnapshot() else dev.androidagent.core.AutomationDeviceSnapshot()
    })
    /**
     * Pictures and videos Mike showed from a computer: loaded when looked at,
     * kept in a cache the system may clear, saved to the phone on request.
     */
    val remoteMedia = RemoteMediaLoader(
        scope = scope,
        cacheDir = java.io.File(app.cacheDir, "remote-media"),
        fetch = { ref, target, progress -> copyFiles.fetchRemote(ref, target, progress) },
        save = { ref, file -> copyFiles.saveToPhone(file, ref.savePath) },
    )
    /** The app's own window is on screen. */
    val appInFront = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The chat the app shows, so a question for it is not also sent as a notification. */
    val openChat = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** A question a chat asked while its card is not on screen goes to the notification shade. */
    val questions: QuestionNotifier
    /** Every chat's run: several at once, with the phone to one of them at a time. */
    val coordinator: AgentRuns
        get() = runCoordinator
    val queue: SessionRunQueue
    /** The live voice conversation, shared by the chat screen and the assistant panel. */
    val voiceConversation = VoiceConversation(scope, sessions, engine, voice, tools) { runCoordinator }
    init {
        scope.launch {
            A11yServiceHandle.service.collect { overlay.refreshWindowHost() }
        }
        runCoordinator = AgentRuns(scope, engine, onStopSession = { id ->
            if (::sessionAgents.isInitialized) sessionAgents.stop(id)
            if (id != null) runCoordinator.family(id).forEach(computerTasks::stopOrigin)
            else computerTasks.stopAll()
        }, create = { share ->
            AgentCoordinator(
                scope, engine, sessions, runTools(), overlay,
                sendGrants = sendGrants,
                adbStatus = { adb.status.value },
                // An approval card lives only in the app, and device control means
                // the app is not in front. Raising it is what makes the approval
                // answerable at all.
                bringToForeground = ::bringAppForward,
                share = share,
                // show_media reads addresses as copy_file does: this chat, the phone, a computer.
                chatMedia = { address, workspace -> copyFiles.chatMedia(address, workspace) },
                chatTitles = chatTitles,
                sessionAgents = { sessionAgents },
            )
        })
        val childSetup = SessionAgentSetup(sessions, remote, phoneEngine, claudeEngine)
        sessionAgents = dev.androidagent.core.SessionAgents(sessions, scope,
            runner = { dev.androidagent.core.RunsSessionAgentRunner(runCoordinator) },
            prepareChild = childSetup::prepare, options = childSetup::options,
            openChat = { child -> agentOpenRequests.value = child; bringAppForward() },
        )
        dev.androidagent.core.SessionAgentDelivery(scope, sessionAgents.events,
            changes = kotlinx.coroutines.flow.combine(runCoordinator.sessionStates, voice.state, voiceConversation.sessionId) { a, b, c -> Triple(a, b, c) },
            active = { runCoordinator.stateOf(it) != null },
            deliver = { source, updates, allowed ->
                val chat = sessions.getSession(source)
                val state = runCoordinator.stateOf(source)
                when {
                    chat == null || !allowed() -> true
                    state?.approval != null || state?.question != null || state?.phase == dev.androidagent.core.RunPhase.STOPPING -> false
                    voiceConversation.sessionId.value == source -> voiceConversation.deliverChildUpdates(source, updates, allowed)
                    else -> runCoordinator.deliverAgentContext(chat, dev.androidagent.core.SessionAgentUpdate.prompt(updates), allowed)
                }
            },
            failed = { source, error -> sessions.append(dev.androidagent.core.ChatMessage(
                java.util.UUID.randomUUID().toString(), source, "system",
                "A child update could not be delivered automatically. Inspect its chat before continuing. ${error.message.orEmpty()}",
                System.currentTimeMillis())) },
        )
        questions = QuestionNotifier(app, scope, runCoordinator, sessions, appInFront, openChat,
            conversationOwner = { child -> runCoordinator.ancestorsOf(child).lastOrNull() ?: child },
            conversationOrder = { child ->
                val source = runCoordinator.ancestorsOf(child).lastOrNull()
                if (source == null) -1 else runCoordinator.family(source).indexOf(child).takeIf { it >= 0 } ?: Int.MAX_VALUE
            },
        )
        queue = SessionRunQueue(
            scope,
            coordinator,
            sessions,
            // A turn a rule queued has a moment; a turn a person sent does not
            // expire. Dropping a stale one is reported, never silent.
            onExpired = { turn ->
                scope.launch {
                    runCatching {
                        sessions.append(
                            dev.androidagent.core.ChatMessage(
                                id = java.util.UUID.randomUUID().toString(),
                                sessionId = turn.sessionId,
                                role = "assistant",
                                text = "This did not run: the phone was busy until after the moment it was for.",
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            },
        )
        automationHost = AutomationHost(
            context = app,
            library = automations,
            history = automationJournal,
            actions = automationActions,
            scope = scope,
            agentAvailable = { runCoordinator.available.value },
        )
        // Alarms do not survive a restart, and the rules were only read just
        // now, so the first arming happens here rather than at the first event.
        runCatching { automationHost.start() }
        runCatching {
            // Claude reads the same skills from its own home.
            WorkspaceSeeder.installDefaultSkills(runtime.homeDirectory, app, claudeHost.homeDirectory)
        }
        // Report a binary downloaded in an earlier run as ready. This checks
        // its hash and never downloads.
        scope.launch(Dispatchers.IO) {
            runCatching { claudeHost.refresh() }
            appInFront.collect { foreground ->
                if (foreground) {
                    scope.launch { claudeHost.checkForUpdates() }
                    scope.launch { runtime.updates.check() }
                    scope.launch { remote.checkCodexUpdates() }
                }
            }
        }
        scope.launch {
            while (true) {
                // The persisted 15-minute throttle owns network checks; a one-minute
                // wake-up avoids missing its deadline after a foreground transition.
                kotlinx.coroutines.delay(60_000)
                if (appInFront.value) {
                    scope.launch { claudeHost.checkForUpdates() }
                    scope.launch { runtime.updates.check() }
                    scope.launch { remote.checkCodexUpdates() }
                }
            }
        }
        // Separate from the skills so a failed skill install cannot leave the
        // user without their preferences, or the reverse.
        runCatching {
            WorkspaceSeeder.ensureGlobalPreferences(runtime.homeDirectory, File(app.filesDir, "sessions"), app)
        }
    }
}

/** The official release's copy publication: unchanged behaviour, no native library. */
private fun publishWithoutNative(part: File, target: File, @Suppress("UNUSED_PARAMETER") replace: Boolean) {
    if (target.exists()) target.delete()
    check(part.renameTo(target)) { "Could not finish the copy at ${target.name}." }
}
