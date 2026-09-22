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
import dev.androidagent.adb.AndroidAdbTransport
import dev.androidagent.automations.AndroidAutomationActions
import dev.androidagent.automations.AutomationHost
import dev.androidagent.automations.AutomationHostOwner
import dev.androidagent.core.AgentCoordinator
import dev.androidagent.core.AutomationJournal
import dev.androidagent.core.AutomationLibrary
import dev.androidagent.core.AutomationToolGateway
import dev.androidagent.core.CompositeDeviceToolGateway
import dev.androidagent.core.KnowledgeStore
import dev.androidagent.core.KnowledgeToolGateway
import dev.androidagent.core.ObservationState
import dev.androidagent.core.WorkflowConfirmationOutcome
import dev.androidagent.core.WorkflowCallMetadata
import dev.androidagent.core.WorkflowCallRegistry
import dev.androidagent.core.WorkflowLibrary
import dev.androidagent.core.WorkflowStore
import dev.androidagent.core.WorkflowToolGateway
import dev.androidagent.core.JevToolGateway
import dev.androidagent.core.SessionRunQueue
import dev.androidagent.devicetools.AndroidDeviceTools
import dev.androidagent.devicetools.AndroidCapabilityTools
import dev.androidagent.enginecodex.CodexEngine
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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = LocalSessionStore(app)
    val runtime = AndroidRuntimeHost(app)
    val engine = CodexEngine(runtime)
    val adb = AndroidAdbTransport(app)
    private lateinit var runCoordinator: AgentCoordinator
    // Declared before the gateways: they take `overlay` as a constructor argument,
    // so it must already be initialised rather than captured through a lambda.
    val overlay = FloatingControlOverlay(
        app,
        onStop = { queue.pause(); runCoordinator.stop(); if (voice.state.value.active) scope.launch { voice.stop() } },
        onSend = { text -> runCoordinator.steer(text) },
        onOpenApp = { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) },
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
        voiceIntent = { dev.androidagent.app.assist.AssistLaunch.voiceIntent(app) },
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
    )
    /** One Jev call owns the bounded observe-decide-act loop and routes every action here. */
    val jev = AndroidJevProvider(app)
    val jevTools = JevToolGateway(jev) { tools }
    // Explicit type: the workflow gateway's router lambda refers back to this
    // property, and an inferred type would make that a recursive definition.
    val tools: CompositeDeviceToolGateway = CompositeDeviceToolGateway(
        listOf(workflowTools, knowledgeTools, automationTools, capabilityTools, jevTools, a11yTools, adbTools),
    )
    val voice = AndroidRealtimeVoiceController(app, engine, scope)
    val coordinator: AgentCoordinator
        get() = runCoordinator
    val queue: SessionRunQueue
    init {
        runCoordinator = AgentCoordinator(
            scope, engine, sessions, tools, overlay,
            sendGrants = sendGrants,
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
            WorkspaceSeeder.installDefaultSkills(runtime.homeDirectory, app)
        }
        // Separate from the skills so a failed skill install cannot leave the
        // user without their preferences, or the reverse.
        runCatching {
            WorkspaceSeeder.ensureGlobalPreferences(runtime.homeDirectory, File(app.filesDir, "sessions"), app)
        }
    }
}
