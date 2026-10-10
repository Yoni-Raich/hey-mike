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

import dev.androidagent.app.ui.statusSummary

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.androidagent.app.ui.*
import dev.androidagent.app.update.*
import dev.androidagent.core.*
import dev.androidagent.remote.RemoteBinding
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteSetup
import dev.androidagent.remote.RemoteStore
import dev.androidagent.runtime.ClaudeInstallPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as AgentApplication).graph
    val updateManager = AppUpdateManager(application)
    private val preferences = application.getSharedPreferences("ui", 0)
    private val current = MutableStateFlow<String?>(null)
    private val draftWrites = Channel<Pair<String, String>>(Channel.UNLIMITED)
    /**
     * Each engine's model list, pick and quota. The open chat is offered the
     * models of every engine that can run in it, and shows the pick and the
     * quota of the one it runs on, through [project]. Changed on the main
     * thread only.
     */
    private var engines = EngineChoices()
        .update(EngineKind.CODEX) { ModelChoice(model = preferences.getString(KEY_MODEL, null), effort = preferences.getString(KEY_EFFORT, null)) }
        .update(EngineKind.CLAUDE) { ModelChoice(model = preferences.getString(KEY_CLAUDE_MODEL, null), effort = preferences.getString(KEY_CLAUDE_EFFORT, null)) }
    private val mutable = MutableStateFlow(
        AgentUiState(
            selectedModel = engines.of(EngineKind.CODEX).model,
            selectedReasoningEffort = engines.of(EngineKind.CODEX).effort,
            onboarding = readOnboarding(),
            defaultEngine = ChatEngines.parse(preferences.getString(KEY_DEFAULT_ENGINE, null)),
        )
    )
    val ui: StateFlow<AgentUiState> = mutable.asStateFlow()
    private val usageByThread = mutableMapOf<String, TokenUsage>()
    private var setupJob: Job? = null
    private var pcThreadsRefreshJob: Job? = null
    private var purgedUnstarted = false
    private var previousChat: String? = null

    init {
        viewModelScope.launch {
            for ((id, text) in draftWrites) {
                // Keep typing off disk and coalesce changes without reordering sessions.
                val latest = mutableMapOf(id to text)
                delay(150)
                while (true) {
                    val next = draftWrites.tryReceive().getOrNull() ?: break
                    latest[next.first] = next.second
                }
                for ((sessionId, draft) in latest) {
                    try { graph.sessions.saveComposerDraft(sessionId, draft) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { mutable.update { it.copy(errorMessage = error.message ?: "Draft could not be saved") } }
                }
            }
        }
        viewModelScope.launch {
            try { checkForUpdates(manual = false) } catch (_: Exception) {}
        }
        viewModelScope.launch { refreshSavedAccounts() }
        viewModelScope.launch {
            graph.sessions.sessions.collect { list ->
                mutable.update { it.copy(sessions = list) }
                if (current.value == null || list.none { it.id == current.value }) {
                    val saved = preferences.getString("session", null)
                    current.value = list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
                }
                if (list.isEmpty()) current.value = graph.sessions.createSession(mutable.value.defaultEngine).id
                // Chats nobody wrote in last run are not history: drop them once, at start.
                if (!purgedUnstarted) {
                    purgedUnstarted = true
                    list.filter { it.id != current.value }.forEach { discardIfUnstarted(it.id) }
                }
                updateTitle()
                project()
            }
        }
        viewModelScope.launch { current.filterNotNull().collectLatest { id ->
            // Leaving a chat nobody wrote in: it was a place to start, not history.
            previousChat?.takeIf { it != id }?.let { old -> viewModelScope.launch { discardIfUnstarted(old) } }
            previousChat = id
            // A computer conversation may be held open by Codex on the computer.
            checkPcChatBusy(id)
            preferences.edit().putString("session", id).apply()
            mutable.update { it.copy(activeSessionId = id, messages = emptyList(), attachments = emptyList(), isDrawerOpen = false, isLoadingMessages = true) }
            updateTitle()
            project()
            try {
                val savedDraft = graph.sessions.composerDraft(id)
                mutable.update {
                    if (id in it.composerSeeds) it else it.copy(composerSeeds = it.composerSeeds + (id to savedDraft.orEmpty()))
                }
                // Reading a saved history can touch disk. Keep the loading card
                // responsive, and do not hold the history behind skill discovery.
                val messages = withContext(Dispatchers.IO) { graph.sessions.messages(id) }
                coroutineScope {
                    launch {
                        if (graph.runtime.status.value.phase in setOf(RuntimePhase.READY, RuntimePhase.RUNNING)) {
                            runCatching { loadSkills(id) }
                        }
                    }
                    messages.collect { items -> mutable.update { it.copy(messages = items, isLoadingMessages = false) } }
                }
            } finally {
                mutable.update { if (it.activeSessionId == id) it.copy(isLoadingMessages = false) else it }
            }
        } }
        viewModelScope.launch {
            graph.computers.state.collect { remote ->
                mutable.update {
                    it.copy(
                        computers = remote.computers,
                        computersUnreadable = remote.unreadable,
                        defaultComputerId = remote.defaultComputerId,
                        remoteBindings = remote.bindings,
                        computerProjects = remote.projects,
                    )
                }
                // A chat on a computer is offered other models than one on the phone.
                project()
            }
        }
        viewModelScope.launch { graph.remote.setup.collect { steps -> mutable.update { it.copy(computerSetup = steps) } } }
        // A computer's own Claude Code decides whether its chats are offered Claude models.
        viewModelScope.launch { graph.remote.claudeState.collect { found -> mutable.update { it.copy(computerClaude = found) }; project() } }
        viewModelScope.launch { graph.remote.claudeSignIn.state.collect { found -> mutable.update { it.copy(computerClaudeSignIn = found) } } }
        viewModelScope.launch { graph.remote.claudeSignIn.loginLinks.collect(::openInBrowser) }
        viewModelScope.launch { graph.remote.threads.collect { threads -> mutable.update { it.copy(pcThreads = threads) }; updateTitle() } }
        viewModelScope.launch { graph.remote.refreshing.collect { ids -> mutable.update { it.copy(pcRefreshing = ids) } } }
        viewModelScope.launch { graph.transfers.current.collect { move -> mutable.update { it.copy(fileTransfer = move) } } }
        viewModelScope.launch {
            graph.computerRequests.collect { request ->
                when (request) {
                    null -> return@collect
                    is dev.androidagent.remote.ComputerUiRequest.AddComputer -> mutable.update {
                        it.copy(
                            isComputersOpen = true,
                            folderBrowser = null,
                            computerProposal = ComputerDraft(
                                label = request.label, host = request.host, vpnHost = request.vpnHost, user = request.user,
                                isDefault = it.computers.isEmpty(), proposedByMike = true,
                            ),
                        )
                    }
                    is dev.androidagent.remote.ComputerUiRequest.OpenChat -> {
                        current.value = request.sessionId
                        mutable.update {
                            it.copy(
                                composerSeeds = it.composerSeeds + (request.sessionId to request.draft),
                                isComputersOpen = false,
                                folderBrowser = null,
                                isDrawerOpen = false,
                            )
                        }
                    }
                }
                graph.computerRequests.value = null
            }
        }
        // The open chat shows its own run; another chat running beside it is
        // not this chat's "Working", and the drawer marks it instead.
        viewModelScope.launch {
            combine(graph.coordinator.sessionStates, current, graph.computerTasks.state) { states, id, _ -> states to id }.collect { (states, id) ->
                mutable.update { it.copy(runState = id?.let { source -> graph.computerTasks.sourceState(source, states) } ?: RunState(), runs = states.filterValues { run -> run.active }) }
            }
        }
        viewModelScope.launch { current.collect { graph.openChat.value = it } }
        viewModelScope.launch { graph.queue.turns.collect { turns -> mutable.update { it.copy(queuedTurns = turns) } } }
        viewModelScope.launch { graph.queue.paused.collect { paused -> mutable.update { it.copy(queuePaused = paused) } } }
        viewModelScope.launch { graph.adb.status.collect { state -> mutable.update { it.copy(adbStatus = state) } } }
        // Connection is a flow; whether the user switched it on is a settings
        // read, so re-check it whenever the service attaches or drops.
        viewModelScope.launch {
            dev.androidagent.a11y.A11yServiceHandle.service.collect {
                mutable.update { it.copy(a11yStatus = dev.androidagent.a11y.A11yAvailability.status(application)) }
            }
        }
        // Update on either lifecycle changes or a new proxy event. Proxy
        // failures do not always move the runtime phase, so sampling only the
        // status flow can leave a fresh 502 explanation invisible.
        viewModelScope.launch {
            combine(graph.runtime.status, graph.runtime.proxyEventState) { state, events ->
                state to dev.androidagent.core.ProxyDiagnostics.explain(events)
            }.collect { (state, diagnostic) ->
                mutable.update { it.copy(runtimeStatus = state, networkDiagnostic = diagnostic) }
            }
        }
        viewModelScope.launch { graph.voice.state.collect { state -> mutable.update { it.copy(voiceState = state) } } }
        viewModelScope.launch { graph.voice.muted.collect { muted -> mutable.update { it.copy(voiceMuted = muted) } } }
        viewModelScope.launch { graph.sendGrants.grants.collect { grants -> mutable.update { it.copy(sendGrants = grants) } } }
        viewModelScope.launch {
            graph.voiceConversation.transcript.collect { line ->
                mutable.update { it.copy(voiceTranscript = line.text, voiceTranscriptRole = line.role) }
            }
        }
        viewModelScope.launch { graph.voiceConversation.failures.collect { error(it) } }
        // Voice started from the assistant panel records into a chat this
        // screen did not pick; show that chat while the conversation is live.
        viewModelScope.launch { graph.voiceConversation.sessionId.filterNotNull().collect { current.value = it } }
        // Codex only: its sign-in, quota and the widget's numbers are Codex's.
        viewModelScope.launch { graph.engine.codexEvents.collect { event ->
            when (event) {
                is EngineEvent.AccountChanged -> {
                    mutable.update { it.copy(accountStatus = event.status, infoMessage = if (event.status.signedIn) "Signed in. You can start chatting." else null) }
                    if (event.status.signedIn) { rememberAccount(event.status); loadModels(); runCatching { graph.engine.refreshUsage() } }
                }
                is EngineEvent.UsageChanged -> {
                    usageChanged(EngineKind.CODEX, event)
                    event.limits?.let(::recordUsage)
                }
                EngineEvent.SkillsChanged -> runCatching { loadSkills(forceReload = false) }
                is EngineEvent.Failure -> if (!graph.coordinator.anyActive) error(event.message)
                else -> Unit
            }
        } }
        // Claude's own sign-in and its 5-hour and weekly limits. The home
        // screen widget shows them too, under the account's name.
        viewModelScope.launch { graph.claudeEngine.events.collect { event ->
            when (event) {
                is EngineEvent.AccountChanged -> {
                    mutable.update { it.copy(claude = it.claude.copy(account = event.status)) }
                    project()
                    if (event.status.signedIn) runCatching { loadClaudeModels() }
                }
                is EngineEvent.UsageChanged -> {
                    usageChanged(EngineKind.CLAUDE, event)
                    event.limits?.let(::recordClaudeUsage)
                }
                is EngineEvent.Failure -> if (!graph.coordinator.anyActive) error(event.message)
                else -> Unit
            }
        } }
        // Claude's last limits from disk, until Claude reports new ones.
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { graph.claudeUsage.read() }.getOrNull() } ?: return@launch
            if (mutable.value.claude.usageReadAtMillis != null) return@launch
            engines = engines.withLimits(EngineKind.CLAUDE, saved.limits)
            mutable.update { it.copy(claude = it.claude.copy(usageReadAtMillis = saved.readAtMillis)) }
            project()
        }
        viewModelScope.launch {
            graph.claudeHost.installState.collect { install -> mutable.update { it.copy(claude = it.claude.copy(install = install)) } }
        }
        viewModelScope.launch {
            mutable.map { it.claude.account }.distinctUntilChanged().collect(::rememberClaudeAccount)
        }
        // A binary already on the phone, or one just downloaded: read the sign-in.
        viewModelScope.launch {
            graph.claudeHost.installState
                .map { it.phase == ClaudeInstallPhase.INSTALLED }
                .distinctUntilChanged()
                .collect { installed -> if (installed) runCatching { refreshClaude() } }
        }
    }

    private fun usageChanged(kind: EngineKind, event: EngineEvent.UsageChanged) {
        val eventThread = event.threadId
        val eventUsage = event.usage
        if (eventThread != null && eventUsage != null) usageByThread[eventThread] = eventUsage
        event.limits?.let { engines = engines.withLimits(kind, it) }
        val threadId = mutable.value.sessions.firstOrNull { it.id == current.value }?.engineThreadId
        mutable.update { it.copy(tokenUsage = usageByThread[threadId]) }
        project()
    }

    /**
     * Show the open chat: the models of every engine it can run on, side by
     * side, and the pick and quota of the engine it runs on now.
     */
    private fun project() {
        mutable.update { state ->
            val kind = ChatEngines.of(state.sessions, current.value)
            val choice = engines.of(kind)
            val computer = current.value?.let(graph.computers::binding)?.computerId
            val offered = engines.offered(
                enginesFor(current.value, kind, state),
                // A computer's Claude Code lists its own models; the aliases until it has answered.
                claudeModels = computer?.let { id -> state.computerClaude[id]?.models?.takeIf { it.isNotEmpty() } ?: engines.of(EngineKind.CLAUDE).catalog },
            )
            state.copy(
                activeEngine = kind,
                modelCatalog = offered,
                availableModels = offered.map { it.id },
                selectedModel = choice.model,
                selectedReasoningEffort = choice.effort,
                usageLimits = engines.limits(kind).let { limits ->
                    // Claude's limits can be a saved reading: a window past its reset is not shown as current.
                    if (kind == EngineKind.CLAUDE) LastUsageStore.current(limits, System.currentTimeMillis() / 1000L) else limits
                },
            )
        }
    }

    /** Note when Claude reported [limits] and keep them for the next start. */
    private fun recordClaudeUsage(limits: List<UsageLimit>) {
        if (limits.isEmpty()) return
        mutable.update { it.copy(claude = it.claude.copy(usageReadAtMillis = System.currentTimeMillis())) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.claudeUsage.save(limits) }
            refreshWidget()
        }
    }

    /**
     * Keep the signed-in Claude account's name for the widget, which cannot
     * ask Claude for it. A status that says signed out removes the name, so the
     * widget stops showing an account that is gone.
     */
    private fun rememberClaudeAccount(status: AccountStatus?) {
        status ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val before = graph.claudeUsage.account()
            if (status.signedIn) graph.claudeUsage.saveAccount(status.label) else graph.claudeUsage.clearAccount()
            if (graph.claudeUsage.account() != before) refreshWidget()
        }
    }

    private fun refreshWidget() = dev.androidagent.app.widget.UsageWidget.refresh(getApplication())
    private fun updateTitle() {
        mutable.update { state ->
            val session = state.sessions.firstOrNull { it.id == current.value }
            val binding = session?.let { graph.computers.binding(it.id) }
            state.copy(
                activeSessionTitle = session?.let { PcChats.title(it, binding, state.pcThreads[binding?.computerId].orEmpty()) },
                tokenUsage = usageByThread[session?.engineThreadId],
            )
        }
    }
    fun editUi(change: (AgentUiState) -> AgentUiState) = mutable.update(change)
    // The chat on screen, if nobody has written in it yet, is already a new chat.
    fun newChat() = task {
        val id = current.value
        val blank = id != null && mutable.value.messages.isEmpty() && graph.sessions.getSession(id)?.let(::isBlank) == true
        if (!blank) current.value = graph.sessions.createSession(mutable.value.defaultEngine).id
    }

    /**
     * The engine new chats start on, from onboarding or Settings. A chat that
     * has begun keeps its own until a model of the other engine is picked in it.
     */
    fun setDefaultEngine(kind: EngineKind) {
        rememberDefaultEngine(kind)
        // The empty chat open now (on first launch, the one made at start) follows the choice.
        val id = current.value ?: return
        val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
        if (isBlank(session) && session.engine != kind && graph.computers.binding(id) == null) useEngine(kind)
    }

    /** Nobody wrote in it and no engine holds a thread for it, in use or parked. */
    private fun isBlank(session: ChatSession): Boolean =
        !session.hasMessages && session.engineThreadId == null && session.parked.isEmpty()

    private fun rememberDefaultEngine(kind: EngineKind) {
        preferences.edit().putString(KEY_DEFAULT_ENGINE, kind.name).apply()
        mutable.update { it.copy(defaultEngine = kind) }
    }

    /**
     * The engines the chat [id] can run on, where it runs: the one it is on,
     * Codex, and Claude where it is set up. For a chat on this phone that is
     * the downloaded Claude Code and its sign-in; for a chat on a computer it
     * is that computer's own Claude Code, signed in there.
     */
    private fun enginesFor(id: String?, running: EngineKind, state: AgentUiState): Set<EngineKind> = buildSet {
        add(running)
        add(EngineKind.CODEX)
        val computer = id?.let(graph.computers::binding)?.computerId
        val claude = if (computer == null) state.claude.ready else state.computerClaude[computer]?.ready == true
        if (claude) add(EngineKind.CLAUDE)
    }

    /**
     * Run the open chat on [kind] from its next message. This works at any
     * point in a chat: each engine keeps its own thread, and the one the chat
     * moves to is told what was said meanwhile (see [EngineSwitch]).
     */
    fun useEngine(kind: EngineKind) = task {
        val id = current.value ?: return@task
        val session = graph.sessions.getSession(id) ?: return@task
        if (session.engine == kind) return@task
        check(graph.coordinator.phaseOf(id) == null) { "Wait for Mike to finish, or stop him, before changing the model." }
        check(!(graph.voice.state.value.active && graph.voiceConversation.sessionId.value == id)) { "End voice before changing the model." }
        // A chat nobody wrote in yet may be pointed at an engine that is still
        // being set up, as onboarding does; its first message then says what is missing.
        val computer = graph.computers.binding(id)?.let { graph.computers.computer(it.computerId) }
        check(kind in enginesFor(id, session.engine, mutable.value) || (isBlank(session) && computer == null)) {
            if (computer != null) "Set up Claude on ${computer.label} from Computers."
            else "Set up ${kind.label} in Settings first."
        }
        graph.sessions.setEngine(id, kind)
        // Like any model pick, it also holds for the next new chat.
        rememberDefaultEngine(kind)
        if (session.hasMessages) {
            val model = engines.of(kind).let { choice -> choice.catalog.firstOrNull { it.id == choice.model }?.displayName ?: choice.model }
            note(id, "Switched to ${providerName(kind)}${model?.let { " · $it" }.orEmpty()}. It is told what was said in this chat with your next message.")
        }
    }

    /** Delete a chat that has no message and no thread, unless something is still using it. */
    private suspend fun discardIfUnstarted(id: String) = runCatching {
        val session = graph.sessions.getSession(id) ?: return@runCatching
        if (mutable.value.composerSeeds[id]?.isNotEmpty() == true || !graph.sessions.composerDraft(id).isNullOrEmpty()) return@runCatching
        if (!isBlank(session) || id in mutable.value.pcChatLoading) return@runCatching
        if (graph.coordinator.phaseOf(id) != null) return@runCatching
        if (graph.voiceConversation.sessionId.value == id && graph.voice.state.value.active) return@runCatching
        graph.queue.cancelSession(id)
        withContext(Dispatchers.IO) { graph.computers.unbind(id) }
        graph.sessions.deleteSession(id)
    }

    /** Save a new or edited computer, then connect to it straight away. */
    fun saveComputer(draft: ComputerDraft) = task {
        val host = draft.host.trim()
        val vpnHost = draft.vpnHost.trim().takeIf { it.isNotEmpty() && it != host }
        val user = draft.user.trim()
        check(host.isNotEmpty() || vpnHost != null) { "Enter the computer's home network or VPN address." }
        check(user.isNotEmpty()) { "Enter the Windows user name." }
        val port = draft.port.trim().ifEmpty { "22" }.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: kotlin.error("The port is a number from 1 to 65535.")
        val existing = draft.id?.let(graph.computers::computer)
        check(existing != null || draft.password.isNotEmpty()) { "Enter the password." }
        val base = existing ?: RemoteComputer(id = RemoteStore.newId(), label = host, host = host, user = user)
        val computer = base.copy(
            label = draft.label.trim().ifEmpty { host.ifEmpty { vpnHost.orEmpty() } }, host = host, vpnHost = vpnHost, port = port, user = user, access = draft.access,
        )
        val saved = withContext(Dispatchers.IO) {
            graph.computers.save(computer, draft.password.takeIf { it.isNotEmpty() }, makeDefault = draft.isDefault)
        }
        // The old connection used the old address, password or access.
        graph.remote.reload(saved.id)
        setUpComputer(saved.id)
    }

    fun connectComputer(id: String) = task { setUpComputer(id) }

    fun openComputers() = mutable.update { it.copy(isComputersOpen = true) }

    fun computerClaudeLogin(id: String) = graph.remote.claudeSignIn.start(id)
    fun computerClaudeCode(id: String, code: String) = graph.remote.claudeSignIn.submit(id, code)
    fun cancelComputerClaudeLogin(id: String) = task { graph.remote.claudeSignIn.cancel(id) }
    fun checkComputerClaude(id: String) = graph.remote.claudeSignIn.check(id)

    /** Connect to the computer, then pick the folder of a new project. */
    fun newProject(id: String) {
        mutable.update { it.copy(isComputersOpen = true) }
        if (graph.remote.setup.value[id] is RemoteSetup.Working) return
        connectComputer(id)
    }

    /**
     * List every computer's conversations again, connecting in the
     * background to one not tried yet in this app run. Quiet: a failure only
     * shows on the computer's own row.
     */
    fun refreshPcThreads() {
        if (pcThreadsRefreshJob?.isActive == true) return
        pcThreadsRefreshJob = viewModelScope.launch {
            coroutineScope {
                graph.computers.state.value.computers.forEach { computer ->
                    launch {
                        if (graph.remote.setup.value[computer.id] == null) graph.remote.connectQuietly(computer.id)
                        else graph.remote.refreshThreads(computer.id, force = false)
                    }
                }
            }
        }
    }

    /** Connect to a computer from the side panel, without opening the computers screen. */
    fun reconnectComputer(id: String) = task { graph.remote.reload(id); graph.remote.setUp(id, install = false) }

    /**
     * Whether Codex on the computer (its desktop app) holds this chat's
     * conversation open, so Mike cannot write to it. Quiet on failure: the
     * send itself then says what went wrong.
     */
    fun checkPcChatBusy(sessionId: String) {
        val binding = graph.computers.binding(sessionId)
        val thread = binding?.threadId
        if (binding == null || binding.importedFromPc == false || thread == null) {
            mutable.update { it.copy(pcBusyChats = it.pcBusyChats - sessionId) }
            return
        }
        viewModelScope.launch {
            val busy = runCatching { graph.remote.isThreadBusy(binding.computerId, thread) }.getOrDefault(false)
            if (graph.computers.binding(sessionId) != binding) return@launch
            mutable.update { it.copy(pcBusyChats = if (busy) it.pcBusyChats + sessionId else it.pcBusyChats - sessionId) }
        }
    }

    /**
     * Continue in a copy: fork the conversation on the computer and move this
     * chat onto the copy. The history comes along; the original stays with
     * whoever holds it.
     */
    fun forkPcChat(sessionId: String) = task {
        val binding = graph.computers.binding(sessionId) ?: kotlin.error("This chat does not run on a computer.")
        val thread = binding.threadId ?: kotlin.error("This chat has no conversation on the computer yet.")
        check(binding.importedFromPc != false) { "This conversation belongs to Mike; it does not need a copy." }
        mutable.update { it.copy(pcForking = it.pcForking + sessionId) }
        try {
            // The copy is a Codex thread, so it goes where the chat keeps its Codex thread.
            graph.sessions.setEngine(sessionId, EngineKind.CODEX)
            val copy = graph.remote.forkThread(binding.computerId, binding.cwd, thread)
            withContext(Dispatchers.IO) { graph.computers.bind(sessionId, binding.copy(threadId = copy, importedFromPc = false)) }
            graph.sessions.setThread(sessionId, copy)
            mutable.update { it.copy(pcBusyChats = it.pcBusyChats - sessionId) }
            note(sessionId, "Mike continues here in a copy of the conversation, with its whole history. The original stays in Codex on the computer.")
        } finally {
            mutable.update { it.copy(pcForking = it.pcForking - sessionId) }
        }
    }

    /** Computers whose Tailscale approval page the user opened and has not come back from. */
    private val awaitingApproval = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun openedTailscaleApproval(id: String) { awaitingApproval += id }

    /** Back in the app after the approval page: connect again without being asked. */
    fun appResumed() {
        val ids = synchronized(awaitingApproval) { awaitingApproval.toList().also { awaitingApproval.clear() } }
        ids.forEach { id ->
            if (graph.remote.setup.value[id] is RemoteSetup.NeedsTailscaleApproval) reconnectComputer(id)
        }
    }

    /**
     * Open a conversation Codex keeps on the computer. A chat here that
     * already follows it is reused; otherwise a new chat is bound to the
     * thread and its earlier messages are copied in.
     */
    fun openPcThread(id: String, threadId: String) = task {
        graph.computers.state.value.bindings.entries.firstOrNull { it.value.threadId == threadId }?.let {
            current.value = it.key
            return@task
        }
        val thread = graph.remote.threads.value[id]?.firstOrNull { it.id == threadId }
            ?: kotlin.error("That conversation is no longer on the computer.")
        val session = graph.sessions.createSession()
        val label = graph.computers.computer(id)?.label ?: "the computer"
        mutable.update { it.copy(pcChatLoading = it.pcChatLoading + (session.id to label)) }
        try {
            withContext(Dispatchers.IO) { graph.computers.bind(session.id, RemoteBinding(id, thread.cwd, threadId, importedFromPc = true)) }
            graph.sessions.setThread(session.id, threadId)
            graph.sessions.rename(session.id, thread.title.ifBlank { folderName(thread.cwd) })
            current.value = session.id
            try {
                val messages = graph.remote.readThread(id, threadId)
                // Oldest first, a millisecond apart, so the order survives sorting.
                val start = System.currentTimeMillis() - messages.size
                messages.forEachIndexed { index, message ->
                    graph.sessions.append(ChatMessage(UUID.randomUUID().toString(), session.id, message.role, message.text, start + index))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                note(session.id, "The earlier messages could not be read from the computer: ${failure.message}. Mike still continues this conversation there.")
            }
        } finally {
            mutable.update { it.copy(pcChatLoading = it.pcChatLoading - session.id) }
        }
        checkPcChatBusy(session.id)
    }

    /**
     * Before its first message, a chat can move between the phone and a
     * computer folder. After that its conversation lives where it started.
     */
    fun moveNewChat(computerId: String?, path: String?) = task {
        val id = current.value ?: kotlin.error("Choose a chat first.")
        check(mutable.value.messages.isEmpty() && graph.sessions.getSession(id)?.engineThreadId == null) {
            "This chat has started. Start a new chat to work somewhere else."
        }
        // A still-empty Claude chat stays on Claude only on a computer that has its own Claude Code.
        if (computerId != null && mutable.value.computerClaude[computerId]?.ready != true) graph.sessions.setEngine(id, EngineKind.CODEX)
        withContext(Dispatchers.IO) {
            if (computerId == null || path == null) {
                graph.computers.unbind(id)
            } else {
                graph.computers.bind(id, RemoteBinding(computerId, path))
                graph.computers.addProject(computerId, path)
            }
        }
    }

    fun setDefaultComputer(id: String) = task { withContext(Dispatchers.IO) { graph.computers.setDefault(id) } }

    private suspend fun setUpComputer(id: String) {
        val result = graph.remote.setUp(id)
        // The user may have closed the sheet while it connected.
        if (result is RemoteSetup.Ready && mutable.value.isComputersOpen) {
            browseFolder(id, graph.computers.computer(id)?.lastFolder.orEmpty())
        }
    }

    /** Remove a computer and the chats that run on it. Nothing on the computer changes. */
    fun removeComputer(id: String) = task {
        val chats = graph.computers.state.value.bindings.filterValues { it.computerId == id }.keys
        check(chats.none { graph.coordinator.phaseOf(it) != null }) { "Stop the chat running on this computer first." }
        graph.remote.disconnect(id)
        chats.forEach { chat -> graph.queue.cancelSession(chat); graph.sessions.deleteSession(chat) }
        withContext(Dispatchers.IO) { graph.computers.remove(id) }
        mutable.update { state -> state.copy(folderBrowser = state.folderBrowser?.takeIf { it.computerId != id }) }
    }

    fun browseFolder(id: String, path: String) = task { listFolder(id, path) }

    private suspend fun listFolder(id: String, path: String) {
        mutable.update { state ->
            val previous = state.folderBrowser?.takeIf { it.computerId == id }
            state.copy(folderBrowser = FolderBrowserState(id, previous?.listing, loading = true))
        }
        val listing = runCatching { graph.remote.listFolders(id, path) }
        mutable.update { state ->
            val browser = state.folderBrowser?.takeIf { it.computerId == id } ?: return@update state
            state.copy(
                folderBrowser = listing.fold(
                    { browser.copy(listing = it, loading = false, error = null) },
                    { browser.copy(loading = false, error = it.message ?: "Could not list that folder.") },
                ),
            )
        }
    }

    /** A new chat whose Codex runs on the computer, in [path]. */
    fun openFolderChat(id: String, path: String) = task {
        val computer = graph.computers.computer(id) ?: kotlin.error("That computer was removed.")
        val session = graph.sessions.createSession()
        val folder = folderName(path)
        withContext(Dispatchers.IO) {
            graph.computers.bind(session.id, RemoteBinding(id, path))
            graph.computers.addProject(id, path)
            graph.computers.update(id) { it.copy(lastFolder = path) }
        }
        // A location label until the first request supplies the chat's topic.
        graph.sessions.setAutomaticTitle(session.id, folder, complete = false)
        current.value = session.id
        mutable.update { it.copy(folderBrowser = null, isComputersOpen = false) }
        note(session.id, "This chat runs on ${computer.label}, in $path. Mike works there with Codex (shell, files, git, skills) and can still use this phone.")
    }

    // First launch and consent live in the same "ui" preferences as the model
    // choice: on this phone only, never sent anywhere.
    private fun readOnboarding() = OnboardingProgress(
        welcomed = preferences.getBoolean(KEY_WELCOMED, false),
        consentVersion = preferences.getInt(KEY_CONSENT_VERSION, 0).takeIf { it > 0 },
        consentAt = preferences.getLong(KEY_CONSENT_AT, 0L).takeIf { it > 0L },
        finished = preferences.getBoolean(KEY_ONBOARDED, false),
    )

    private fun saveOnboarding(change: (OnboardingProgress) -> OnboardingProgress) {
        val next = change(mutable.value.onboarding)
        preferences.edit()
            .putBoolean(KEY_WELCOMED, next.welcomed)
            .putInt(KEY_CONSENT_VERSION, next.consentVersion ?: 0)
            .putLong(KEY_CONSENT_AT, next.consentAt ?: 0L)
            .putBoolean(KEY_ONBOARDED, next.finished)
            .apply()
        mutable.update { it.copy(onboarding = next) }
    }

    fun markWelcomed() = saveOnboarding { it.copy(welcomed = true) }

    fun acceptConsent() = saveOnboarding {
        it.copy(welcomed = true, consentVersion = Onboarding.CONSENT_VERSION, consentAt = System.currentTimeMillis())
    }

    fun finishOnboarding() = saveOnboarding { it.copy(finished = true) }

    /**
     * Forget the consent and stop acting. Signing out needs the run and voice
     * to be over, so stop first. Screen access is a system switch the app
     * cannot turn off; the caller opens its screen.
     */
    fun withdrawConsent() {
        stop()
        saveOnboarding { it.copy(consentVersion = null, consentAt = null, finished = false) }
        task {
            withTimeoutOrNull(10_000L) {
                graph.coordinator.state.first { !it.active }
                graph.voice.state.first { !it.active }
            }
            if (mutable.value.accountStatus?.signedIn == true) logout()
            if (mutable.value.claude.account?.signedIn == true) claudeLogout()
        }
    }

    /**
     * Hand wireless debugging to Mike. The pairing reader is armed first, while
     * no run is active, so it can read the code once Mike opens the dialog;
     * the code itself never reaches the model.
     */
    fun letMikeSetUpWireless() = task {
        check(!graph.coordinator.state.value.active) { "Stop the current run first." }
        check(dev.androidagent.a11y.PairingWatcher.available) { "Turn on screen access first." }
        finishOnboarding()
        current.value = graph.sessions.createSession(mutable.value.defaultEngine).id
        // Its own job: it waits for the dialog while Mike's run is active.
        task { pairFromDialog(timeoutMs = WIRELESS_SETUP_TIMEOUT_MS) }
        send(WIRELESS_SETUP_PROMPT, emptyList())
    }
    fun select(id: String) { current.value = id }
    fun composerDraftChanged(id: String, text: String) {
        mutable.update { it.copy(composerSeeds = it.composerSeeds + (id to text)) }
        draftWrites.trySend(id to text)
    }
    fun rename(id: String, title: String) = task { graph.chatTitles.rename(id, title) }
    fun delete(id: String) {
        if (graph.computers.state.value.tasks.values.any { it.originSessionId == id && !it.terminal }) {
            error("Stop this chat's computer subagents before deleting it."); return
        }
        if (graph.coordinator.phaseOf(id) != null) { error("Stop this chat before deleting it."); return }
        if (graph.voiceConversation.sessionId.value == id && graph.voice.state.value.active) { error("End the voice conversation before deleting it."); return }
        task { graph.queue.cancelSession(id); graph.sessions.deleteSession(id) }
    }
    fun send(text: String, attachments: List<PendingAttachment>) {
        val id = current.value ?: return
        // A subagent's existing gate is also answerable in its source chat.
        if (attachments.isEmpty()) {
            if (answerRunGate(id, text)) return
        }
        if (graph.voice.state.value.active) {
            if (id != graph.voiceConversation.sessionId.value) { error("End voice before sending in another chat."); return }
            if (attachments.isNotEmpty()) { error("End voice before sending attachments."); return }
            task { graph.voiceConversation.type(text) }
            return
        }
        val onComputer = graph.computers.binding(id) != null
        val paths = attachments.mapNotNull { it.path?.let(::File) }
        val images = attachments.filter { it.mimeType?.startsWith("image/") == true }.mapNotNull { it.path?.let(::File) }
        val otherFiles = paths.filter { it !in images }
        val otherNames = attachments.filter { it.path != null && it.mimeType?.startsWith("image/") != true }.map { it.name }
        val snapshot = mutable.value
        val invokedSkillName = text.trimStart()
            .takeIf { it.startsWith("\$") }
            ?.drop(1)
            ?.takeWhile { !it.isWhitespace() }
            ?.takeIf { it.isNotBlank() }
        val invokedSkill = invokedSkillName?.let { name ->
            snapshot.availableSkills.firstOrNull { it.enabled && it.name.equals(name, ignoreCase = true) }
        }
        // The chat's own engine's pick. Plan mode is carried by a model name,
        // so it falls back to the first offered model when none is picked.
        val kind = ChatEngines.of(snapshot.sessions, id)
        val choice = engines.of(kind)
        val model = choice.modelFor(snapshot.planMode)
        if (snapshot.planMode && model == null) { error("Choose a model before using plan mode."); return }
        task {
            // Pictures travel inside the turn. Any other file has to be on the
            // machine the agent runs on, so a computer chat copies it there
            // first and names where it landed. A failed copy sends nothing and
            // keeps the attachments, so the user can try again.
            val where = if (onComputer && otherFiles.isNotEmpty()) {
                mutable.update { it.copy(infoMessage = "Sending ${otherFiles.size} file${if (otherFiles.size == 1) "" else "s"} to the computer…", errorMessage = null) }
                try {
                    graph.remote.sendAttachments(id, otherNames.zip(otherFiles))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(infoMessage = null, errorMessage = "Could not send the file to the computer: ${e.message ?: e.javaClass.simpleName}") }
                    return@task
                }
            } else {
                otherFiles.map { it.absolutePath }
            }
            val prompt = if (where.isEmpty()) text else {
                val place = if (onComputer) "Attached files on this computer" else "Attached files in this session"
                text + "\n\n$place:\n" + where.joinToString("\n")
            }
            mutable.update { it.copy(infoMessage = null) }
            graph.queue.submit(QueuedTurn(sessionId = id, prompt = prompt, imagePaths = images.map { it.absolutePath },
                model = model, effort = choice.turnEffort, skill = invokedSkill, planMode = snapshot.planMode, engine = kind))
            mutable.update { if (it.activeSessionId == id) it.copy(attachments = emptyList(), errorMessage = null) else it }
        }
    }
    fun cancelQueued(id: String) = task { graph.queue.cancel(id) }
    fun resumeQueue() = task { graph.queue.resume() }

    fun togglePlanMode() { mutable.update { it.copy(planMode = !it.planMode) } }

    fun compact() {
        val id = current.value ?: return
        if (graph.coordinator.phaseOf(id) != null) { error("Wait for the agent to finish before compacting."); return }
        task {
            val session = graph.sessions.getSession(id)
            val thread = checkNotNull(session?.engineThreadId) { "There is nothing to compact yet. Send a message first." }
            // Claude compacts in the call itself, which can take a while, so
            // the note comes first.
            note(id, "Compacting the chat to free up context")
            if (session.engine == EngineKind.CLAUDE) {
                // After a restart the Claude process for this chat is not open yet.
                graph.engine.openSession(graph.sessions.workspace(id), thread, engines.of(EngineKind.CLAUDE).model, graph.tools.definitions)
                graph.engine.compact(thread)
                note(id, "Chat compacted")
            } else {
                graph.engine.compact(thread)
            }
        }
    }

    fun showStatus() {
        val id = current.value ?: return
        val text = statusSummary(mutable.value)
        task { note(id, text) }
    }

    // A line in the chat that records what a command did; the model never sees it.
    private suspend fun note(sessionId: String, text: String) =
        graph.sessions.append(ChatMessage(UUID.randomUUID().toString(), sessionId, "note", text, System.currentTimeMillis()))

    fun stop() {
        graph.queue.pause()
        // End voice while an assistant press is still setting up cancels it.
        if (!graph.voice.state.value.active && assistantVoiceJob?.isActive == true) {
            assistantVoiceJob?.cancel()
        }
        if (graph.voice.state.value.active) {
            // User Stop covers the voice source's children too. Ending only
            // the microphone through toggleVoice keeps its separate meaning.
            (graph.voiceConversation.sessionId.value ?: current.value)?.let(graph.coordinator::stop)
            stopVoice()
        } else current.value?.let(graph.coordinator::stop)
    }

    /** Words for the open chat's run, which may not be the only one running. */
    private fun answerRunGate(id: String, text: String): Boolean {
        val own = graph.coordinator.stateOf(id)
        val delegatedGate = own?.approval == null && own?.question == null
        val handled = graph.coordinator.answerApprovalByReply(text, sessionId = id) ||
            graph.coordinator.answerQuestionByReply(text, sessionId = id)
        if (handled && delegatedGate) task {
            graph.sessions.append(ChatMessage(UUID.randomUUID().toString(), id, "user", text, System.currentTimeMillis()))
        }
        return handled
    }

    fun steer(text: String) {
        val id = current.value ?: return
        if (answerRunGate(id, text)) return
        // The source can be idle while its subagent works. A new instruction
        // needs a source turn (to inspect/cancel it), not a missing coordinator.
        if (graph.coordinator.phaseOf(id) == null) send(text, emptyList())
        else graph.coordinator.steer(id, text)
    }
    fun toggleVoice() {
        if (graph.voice.state.value.active) stopVoice() else startVoice()
    }
    fun toggleVoiceMute() = graph.voice.setMuted(!graph.voice.muted.value)
    private var assistantVoiceJob: Job? = null

    /**
     * Holding the power button with Mike as the digital assistant. Unlike the
     * mic button this never ends a conversation, and it waits for what a cold
     * start has not loaded yet: the runtime and the saved chat. A chat that
     * already has messages is left alone and voice opens in a new one, the way
     * a fresh assistant press starts over.
     */
    fun startAssistantVoice(automation: dev.androidagent.core.AutomationVoiceRequest? = null) {
        if (automation != null && (graph.voice.state.value.active || assistantVoiceJob?.isActive == true)) {
            // A later notification belongs in the live conversation; do not
            // restart the call or silently discard its context.
            val starting = assistantVoiceJob
            task {
                starting?.join()
                graph.voiceConversation.announce(automation)
            }
            return
        }
        if (graph.voice.state.value.active || assistantVoiceJob?.isActive == true) return
        // The voice screen comes up now, not when the call starts: on a cold
        // start the runtime and the chat take seconds, and a press that shows
        // nothing for that long reads as a press that did nothing.
        mutable.update { it.copy(voiceSummon = "Waking Mike", errorMessage = null) }
        assistantVoiceJob = task {
            try {
                setupJob?.join()
                val id = current.filterNotNull().first()
                if (graph.voice.state.value.active) return@task
                if (!graph.coordinator.state.value.active && graph.sessions.messages(id).first().isNotEmpty()) {
                    // Voice is Codex's, so a chat made for it starts there.
                    current.value = graph.sessions.createSession(EngineKind.CODEX).id
                }
                mutable.update { it.copy(voiceSummon = "Opening your conversation") }
                beginVoice(automation)
            } finally {
                // Voice is already active by here when it started, so the
                // screen stays up; on a failure or a cancel it goes away.
                mutable.update { it.copy(voiceSummon = null) }
            }
        }
    }
    fun refreshAssistantRole() {
        val isDefault = dev.androidagent.app.assist.AssistLaunch.isDefaultAssistant(getApplication())
        mutable.update { it.copy(isDefaultAssistant = isDefault) }
    }
    private fun startVoice() = task { beginVoice() }
    private suspend fun beginVoice(automation: dev.androidagent.core.AutomationVoiceRequest? = null) {
        automation?.requireCurrent()
        graph.queue.pause()
        val sessionId = current.value ?: kotlin.error("Choose a chat first.")
        mutable.update { it.copy(errorMessage = null) }
        graph.voiceConversation.begin(sessionId, engines.of(EngineKind.CODEX).model, automation)
    }
    private fun stopVoice() = task { graph.voiceConversation.stop() }
    fun prepare() {
        if (setupJob?.isActive == true) return
        setupJob = task {
            mutable.update { it.copy(isPreparingRuntime = true, errorMessage = null) }
            try {
                graph.runtime.prepare(); graph.engine.connect()
                val account = graph.engine.account()
                mutable.update { it.copy(accountStatus = account) }
                rememberAccount(account)
                loadModels()
                loadSkills()
                runCatching { graph.engine.refreshUsage() }
            } finally { mutable.update { it.copy(isPreparingRuntime = false) } }
        }
    }
    fun login() = task {
        val status = graph.engine.login()
        mutable.update { it.copy(accountStatus = status, isSettingsOpen = true, errorMessage = null) }
    }
    fun logout() = task {
        check(!graph.coordinator.state.value.active && !graph.voice.state.value.active) { "Stop the current run or voice conversation before signing out." }
        graph.engine.logout()
        // Signing out ends this sign-in for good, so it leaves the saved list;
        // the other saved accounts stay one tap away.
        withContext(Dispatchers.IO) { graph.accounts.state().activeId?.let(graph.accounts::remove) }
        refreshSavedAccounts()
        engines = engines.withLimits(EngineKind.CODEX, emptyList())
        mutable.update { it.copy(accountStatus = AccountStatus(false, "Sign in to Codex")) }
        project()
    }

    // ---- Claude subscription ----------------------------------------------------

    /**
     * Download Claude Code after the user saw its size. Runs in the app's
     * scope so leaving the screen does not stop it; the card shows progress,
     * a cancel, and why it failed.
     */
    fun downloadClaude() {
        val phase = graph.claudeHost.installState.value.phase
        if (phase == ClaudeInstallPhase.DOWNLOADING || phase == ClaudeInstallPhase.VERIFYING) return
        graph.scope.launch {
            try {
                graph.claudeHost.prepare()
            } catch (failure: Exception) {
                // A cancel is its own state; a failure the installer did not
                // record still has to reach the user.
                val recorded = graph.claudeHost.installState.value.phase in setOf(ClaudeInstallPhase.FAILED, ClaudeInstallPhase.CANCELLED)
                if (failure !is CancellationException && !recorded) error(failure.message ?: "Claude Code could not be downloaded.")
            }
        }
    }

    fun cancelClaudeDownload() = graph.claudeHost.installer.cancel()

    /** Start `claude auth login` and open its page in the browser. */
    fun claudeLogin() = claudeTask {
        val status = graph.claudeEngine.login()
        mutable.update { it.copy(claude = it.claude.copy(account = status), errorMessage = null) }
        status.loginUrl?.let(::openInBrowser)
    }

    /** Pass the pasted code to the waiting sign-in. It is not stored, shown or logged here. */
    fun claudeCompleteLogin(code: String) = claudeTask {
        val status = try {
            graph.claudeEngine.completeLogin(code)
        } catch (expired: IllegalStateException) {
            // The waiting sign-in is gone; the next tap starts a new one.
            mutable.update { it.copy(claude = it.claude.copy(account = AccountStatus(false, CLAUDE_SIGN_IN))) }
            throw expired
        }
        mutable.update {
            it.copy(
                claude = it.claude.copy(account = status),
                infoMessage = if (status.signedIn) "Signed in to Claude. You can start a Claude chat." else null,
            )
        }
        if (status.signedIn) runCatching { loadClaudeModels() } else error(status.label)
    }

    fun claudeLogout() = claudeTask {
        check(mutable.value.sessions.none { it.engine == EngineKind.CLAUDE && graph.coordinator.phaseOf(it.id) != null }) {
            "Stop the Claude chat that is running before signing out."
        }
        graph.claudeEngine.logout()
        engines = engines.withLimits(EngineKind.CLAUDE, emptyList())
        // The saved limits belong to the account that signed out.
        withContext(Dispatchers.IO) { runCatching { graph.claudeUsage.clear() } }
        mutable.update { it.copy(claude = it.claude.copy(account = AccountStatus(false, CLAUDE_SIGN_IN), usageReadAtMillis = null)) }
        refreshWidget()
        project()
    }

    /** Read the open chat's engine quota again. */
    fun refreshUsage() {
        if (mutable.value.activeEngine != EngineKind.CLAUDE) {
            refreshAccount()
            return
        }
        task {
            mutable.update { it.copy(isRefreshingAccount = true) }
            try { refreshClaude() } finally { mutable.update { it.copy(isRefreshingAccount = false) } }
        }
    }

    /** Claude's sign-in from `claude auth status`, then its quota and models. Needs the binary. */
    private suspend fun refreshClaude() {
        if (graph.claudeHost.installState.value.phase != ClaudeInstallPhase.INSTALLED) return
        val account = graph.claudeEngine.account()
        mutable.update { it.copy(claude = it.claude.copy(account = account)) }
        project()
        if (account.signedIn) {
            // Usage first: with no chat running, the process that reads it
            // also reads the model list, so the models below need no second one.
            runCatching { graph.claudeEngine.refreshUsage() }
            runCatching { loadClaudeModels() }
        }
    }

    private suspend fun loadClaudeModels() {
        val catalog = graph.claudeEngine.modelCatalog()
        engines = engines.update(EngineKind.CLAUDE) { it.withCatalog(catalog) }
        persistChoice(EngineKind.CLAUDE)
        project()
    }

    private fun claudeTask(block: suspend () -> Unit) = task {
        mutable.update { it.copy(claude = it.claude.copy(busy = true)) }
        try { block() } finally { mutable.update { it.copy(claude = it.claude.copy(busy = false)) } }
    }

    private fun openInBrowser(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { error("No app on this phone can open the sign-in page.") }
    }

    private val accountChange = kotlinx.coroutines.sync.Mutex()

    /**
     * Sign in to one more account. The live one is saved and taken off
     * Codex first, so the device-code sign-in lands in an empty slot instead
     * of replacing it.
     */
    fun addAccount() = changeAccount("adding an account") {
        withContext(Dispatchers.IO) { graph.accounts.detach() }
        val status = graph.engine.login()
        mutable.update { it.copy(accountStatus = status, isSettingsOpen = true) }
    }

    /**
     * Make a saved account the live one. Only Codex's credentials file is
     * swapped: every chat, its thread and its history stay as they are and
     * resume under this account on the next turn. The quota shown is read
     * again, because quota is the one thing that belongs to the account.
     */
    fun switchAccount(id: String) {
        if (mutable.value.savedAccounts.activeId == id && mutable.value.accountStatus?.signedIn == true) return
        changeAccount("switching account") { activate(id) }
    }

    private suspend fun activate(id: String) {
        val target = withContext(Dispatchers.IO) { graph.accounts.activate(id) }
        val account = graph.engine.account()
        mutable.update { it.copy(accountStatus = account, infoMessage = "Switched to ${target.label}. Your chats are unchanged.") }
        runCatching { graph.engine.refreshUsage() }
        runCatching { loadModels() }
    }

    fun removeAccount(id: String) = task {
        check(mutable.value.savedAccounts.activeId != id) { "Switch to another account first, or log out of this one." }
        withContext(Dispatchers.IO) { graph.accounts.remove(id) }
        refreshSavedAccounts()
    }

    /**
     * Codex writes auth.json while it runs, so the swap happens with the
     * app-server stopped and nothing queued able to start it again.
     */
    private fun changeAccount(what: String, block: suspend () -> Unit) = task {
        check(!graph.coordinator.state.value.active && !graph.voice.state.value.active) { "Stop the current run or voice conversation before $what." }
        if (!accountChange.tryLock()) return@task
        val wasPaused = graph.queue.paused.value
        graph.queue.pause()
        engines = engines.withLimits(EngineKind.CODEX, emptyList())
        mutable.update { it.copy(isSwitchingAccount = true, errorMessage = null) }
        project()
        try {
            graph.engine.close()
            block()
        } finally {
            refreshSavedAccounts()
            mutable.update { it.copy(isSwitchingAccount = false) }
            accountChange.unlock()
            if (!wasPaused) graph.queue.resume()
        }
    }

    /** Keep the live sign-in in the saved list, under the email Codex reports. */
    private suspend fun rememberAccount(status: AccountStatus) {
        if (!status.signedIn) return
        runCatching { withContext(Dispatchers.IO) { graph.accounts.captureActive(status.label) } }
        refreshSavedAccounts()
    }

    private suspend fun refreshSavedAccounts() {
        val saved = runCatching { withContext(Dispatchers.IO) { graph.accounts.state() } }.getOrNull() ?: return
        val changed = saved != mutable.value.savedAccounts
        mutable.update { it.copy(savedAccounts = saved) }
        // Which account is in use, and which are saved, is on the widget too.
        if (changed) dev.androidagent.app.widget.UsageWidget.refresh(getApplication())
    }

    /**
     * Keep a quota reading under the account it belongs to, for the widget.
     * The vault on disk names the live account, not the UI state: during a
     * switch the new account's quota arrives before the UI has caught up.
     */
    private fun recordUsage(limits: List<UsageLimit>) {
        if (limits.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val vault = graph.accounts.state()
                val live = vault.activeId ?: return@runCatching
                graph.usageBook.record(live, limits, vault.accounts.map { it.id })
                dev.androidagent.app.widget.UsageWidget.refresh(getApplication())
            }
        }
    }
    fun refreshAccount() = task {
        if (graph.runtime.status.value.phase !in setOf(RuntimePhase.READY, RuntimePhase.RUNNING)) return@task
        // A switch has Codex stopped on purpose; reading now would restart it mid-swap.
        if (accountChange.isLocked) return@task
        mutable.update { it.copy(isRefreshingAccount = true) }
        try {
            val account = graph.engine.account()
            mutable.update { it.copy(accountStatus = account) }
            rememberAccount(account)
            runCatching { graph.engine.refreshUsage() }
        } finally {
            mutable.update { it.copy(isRefreshingAccount = false) }
        }
    }

    /**
     * Re-read the grants that are changed in system Settings rather than in a
     * permission dialog, plus the accessibility switch. Nothing here is
     * observable, so the app has to look again every time it comes back.
     */
    fun refreshPermissions() {
        val app = getApplication<Application>()
        val notifications = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val microphone = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val permissions = DevicePermissions(
            overlay = runCatching { android.provider.Settings.canDrawOverlays(app) }.getOrDefault(false),
            notifications = notifications,
            installUnknownApps = runCatching { updateManager.canRequestPackageInstalls() }.getOrDefault(false),
            microphone = microphone,
        )
        // The accessibility switch is only observed when the service binds, so a
        // switch that was turned on but never started would otherwise stay stale
        // exactly in the restricted-settings case the UI warns about.
        val a11y = runCatching { dev.androidagent.a11y.A11yAvailability.status(app) }.getOrNull()
        mutable.update { state ->
            state.copy(permissions = permissions, a11yStatus = a11y ?: state.a11yStatus)
        }
    }
    /**
     * Re-read the rules and the two permissions they depend on.
     *
     * Both permissions are changed in system Settings and neither is
     * observable, so this runs on every resume beside [refreshPermissions] —
     * a rule that quietly stopped working because notification access was
     * revoked is exactly the state this screen exists to show.
     */
    fun refreshAutomations() {
        val host = graph.automationHost
        val rules = runCatching { graph.automations.all() }.getOrDefault(emptyList())
        val supported = runCatching { host.supportedTriggers() }.getOrDefault(emptySet())
        val overview = runCatching {
            dev.androidagent.core.AutomationOverview.of(
                rules = rules,
                history = graph.automationJournal,
                supported = supported,
                now = java.time.ZonedDateTime.now(),
                appLabel = ::appLabel,
                supportedDeviceStates = host.supportedDeviceStates(),
            )
        }.getOrDefault(dev.androidagent.core.AutomationOverview.EMPTY)
        mutable.update { state ->
            state.copy(
                automations = dev.androidagent.app.ui.AutomationsStatus(
                    overview = overview,
                    notificationAccess = runCatching {
                        dev.androidagent.automations.AutomationNotificationListener.isEnabled(getApplication())
                    }.getOrDefault(false),
                    exactAlarms = runCatching { host.canFireOnTime() }.getOrDefault(true),
                ),
            )
        }
    }

    /**
     * "com.whatsapp" as the user knows it. Null when the app is not installed,
     * which is worth showing as the bare package rather than hiding: a rule
     * watching an app that is gone is a rule that will never fire.
     */
    private fun appLabel(packageName: String): String? = runCatching {
        val packages = getApplication<Application>().packageManager
        packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    /** Turn a rule on or off, then re-arm: the alarm set may have changed. */
    fun setRuleEnabled(id: String, enabled: Boolean) {
        runCatching { graph.automations.setEnabled(id, enabled) }
        runCatching { graph.automationHost.rearm() }
        refreshAutomations()
    }

    /** Delete a rule for good, then re-arm: it may have been the next one due. */
    fun deleteRule(id: String) {
        val removed = runCatching { graph.automations.delete(id) }.getOrDefault(false)
        runCatching { graph.automationHost.rearm() }
        val name = dev.androidagent.core.AutomationSummaries.chipName(id)
        mutable.update {
            it.copy(infoMessage = if (removed) "Deleted \"" + name + "\"" else "Could not delete \"" + name + "\"")
        }
        refreshAutomations()
    }

    /** The rule's editable values, for the edit form. Empty when it is gone or unreadable. */
    fun ruleEditFields(id: String): List<AutomationEditField> =
        runCatching { graph.automations.get(id)?.let(AutomationEditor::fields) }.getOrNull().orEmpty()

    /**
     * Save the edit form.
     *
     * The form's values become the same `changes` the agent's `mode:"update"`
     * takes, and go through the same merge and validation, so the screen cannot
     * save what the tool would refuse. Returns null on success, or the reason
     * in words for the form to show; nothing is written when it fails.
     */
    fun saveRuleEdits(id: String, values: Map<String, String>): String? {
        val rule = graph.automations.get(id) ?: return "This rule no longer exists."
        val changes = AutomationEditor.changes(rule, values)
        if (changes.isEmpty()) return null
        return try {
            graph.automations.update(id, changes)
            runCatching { graph.automationHost.rearm() }
            mutable.update { it.copy(infoMessage = "Saved \"" + AutomationSummaries.chipName(id) + "\"") }
            refreshAutomations()
            null
        } catch (invalid: AutomationFormatException) {
            invalid.message
        } catch (failure: Exception) {
            failure.message ?: "The rule could not be saved."
        }
    }

    /**
     * Fire a rule now. Naming it supplies its trigger; its conditions, cooldown
     * and daily limit still apply, so this may decide not to run — which is why
     * the list is re-read rather than assumed.
     */
    fun runRule(id: String) {
        runCatching { graph.automationHost.runNow(id) }
        val name = dev.androidagent.core.AutomationSummaries.chipName(id)
        mutable.update { it.copy(infoMessage = "Running \"" + name + "\"") }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            refreshAutomations()
        }
    }

    /** Codex's model list. Claude's comes from [loadClaudeModels]. */
    private suspend fun loadModels() {
        mutable.update { it.copy(isLoadingModels = true) }
        try {
            val catalog = graph.engine.modelCatalog()
            engines = engines.update(EngineKind.CODEX) { it.withCatalog(catalog) }
            if (catalog.isNotEmpty()) persistChoice(EngineKind.CODEX)
            project()
        }
        finally { mutable.update { it.copy(isLoadingModels = false) } }
    }
    private suspend fun loadSkills(sessionId: String? = current.value, forceReload: Boolean = true) {
        val id = sessionId ?: return
        mutable.update { it.copy(isLoadingSkills = true) }
        try {
            val workspace = graph.sessions.workspace(id)
            val skills = graph.engine.skillCatalog(workspace, forceReload = forceReload)
            mutable.update { it.copy(availableSkills = skills) }
        } finally {
            mutable.update { it.copy(isLoadingSkills = false) }
        }
    }
    /**
     * Pick a model for the open chat. The list holds both engines' models, so
     * picking one of the other engine also moves the chat to that engine.
     */
    fun model(value: String) {
        val state = mutable.value
        val kind = ChatEngines.engineOfModel(state.modelCatalog, value, state.activeEngine)
        engines = engines.update(kind) { it.select(value) }
        persistChoice(kind)
        if (kind != state.activeEngine) useEngine(kind)
        project()
    }
    fun reasoningEffort(value: String?) {
        val kind = mutable.value.activeEngine
        engines = engines.update(kind) { it.selectEffort(value) }
        persistChoice(kind)
        project()
    }
    /** Each engine's pick under its own keys; Codex keeps the keys the assistant panel reads. */
    private fun persistChoice(kind: EngineKind) {
        val choice = engines.of(kind)
        val (modelKey, effortKey) = if (kind == EngineKind.CLAUDE) KEY_CLAUDE_MODEL to KEY_CLAUDE_EFFORT else KEY_MODEL to KEY_EFFORT
        preferences.edit().apply {
            if (choice.model.isNullOrBlank()) remove(modelKey) else putString(modelKey, choice.model)
            if (choice.effort.isNullOrBlank()) remove(effortKey) else putString(effortKey, choice.effort)
        }.apply()
    }
    fun discover() = task {
        mutable.update { it.copy(isDiscoveringAdb = true) }
        try {
            val values = graph.adb.discover()
            mutable.update {
                it.copy(
                    discoveredEndpoints = values,
                    infoMessage = if (values.isEmpty()) graph.adb.status.value.message else null,
                )
            }
        }
        finally { mutable.update { it.copy(isDiscoveringAdb = false) } }
    }
    /**
     * Pair and then connect without asking for a second port. The transport
     * finds the connect port from the services this phone advertises; the
     * manual [connect] path stays for networks where discovery is blocked.
     */
    fun pair(code: String, port: String) = task {
        check(!graph.coordinator.state.value.active) { "Stop the current run before changing the connection." }
        pairAndConnect(code.trim(), parsePort(port))
    }

    /**
     * Read the pairing code off the system dialog instead of asking the user to
     * carry it back: the dialog wipes the code the moment it closes, which is
     * why typing it meant splitting the screen.
     */
    fun capturePairing() = task {
        check(!graph.coordinator.state.value.active) { "Stop the current run before changing the connection." }
        pairFromDialog(timeoutMs = 120_000L)
    }

    private suspend fun pairFromDialog(timeoutMs: Long) {
        if (!dev.androidagent.a11y.PairingWatcher.available) {
            mutable.update {
                it.copy(infoMessage = "Turn on Screen control to read the code automatically, or type it below.")
            }
            return
        }
        mutable.update {
            it.copy(infoMessage = "Tap \"Pair device with pairing code\" — the code is read from the dialog.", errorMessage = null)
        }
        val details = dev.androidagent.a11y.PairingWatcher.await(timeoutMs = timeoutMs)
        if (details == null) {
            mutable.update { it.copy(infoMessage = "No pairing dialog was found. Type the code below instead.") }
            return
        }
        pairAndConnect(details.code, details.port)
    }

    private suspend fun pairAndConnect(code: String, port: Int) {
        mutable.update { it.copy(isPairing = true, errorMessage = null) }
        try {
            val connected = graph.adb.pairAndConnect(port, code) { progress ->
                mutable.update { it.copy(infoMessage = progress) }
            }
            mutable.update { it.copy(infoMessage = "Connected on port $connected.") }
        } finally {
            mutable.update { it.copy(isPairing = false) }
            discover()
        }
    }
    /**
     * Switch Wireless debugging on without leaving Mike. Returns false when
     * Mike cannot (it has never connected over ADB, which is when it gets the
     * permission), and the caller opens the Settings screen instead. The
     * service loop connects as soon as the switch is on.
     */
    fun turnOnWireless(): Boolean {
        if (!graph.adb.switchWirelessDebuggingOn()) return false
        mutable.update { it.copy(infoMessage = "Turning on Wireless debugging…", errorMessage = null) }
        task {
            delay(WIRELESS_SWITCH_CHECK_MS)
            // Android turns it straight back off without Wi-Fi, and on a
            // network it has not seen asks first with a dialog of its own.
            if (graph.adb.status.value.wirelessDebugging == false) {
                mutable.update {
                    it.copy(infoMessage = "Wireless debugging is still off. It needs Wi-Fi, and on a new network Android asks you to allow it first.")
                }
            }
        }
        return true
    }
    fun connect(port: String) = task {
        check(!graph.coordinator.state.value.active) { "Stop the current run before changing the connection." }
        mutable.update { it.copy(isConnecting = true) }
        try { graph.adb.connect(parsePort(port)); mutable.update { it.copy(infoMessage = "Connected to this phone.") } }
        finally { mutable.update { it.copy(isConnecting = false) } }
    }
    fun disconnect() = task {
        check(!graph.coordinator.state.value.active) { "Stop the current run before disconnecting." }
        graph.adb.disconnect()
    }
    fun forgetPairing() = task {
        check(!graph.coordinator.state.value.active) { "Stop the current run before removing this pairing." }
        graph.adb.forgetPairing()
        mutable.update { it.copy(infoMessage = "Pairing removed. Pair again to connect.") }
    }
    /**
     * [deleteAfter] is a temporary file the copy came from, such as a camera
     * shot, and [name] replaces the name the source gives (a shot's is a UUID).
     */
    fun addAttachment(uri: Uri, deleteAfter: File? = null, name: String? = null) = task {
        try {
            copyAttachment(uri, name)
        } finally {
            deleteAfter?.delete()
        }
    }

    private suspend fun copyAttachment(uri: Uri, givenName: String?) {
        val id = current.value ?: return
        val resolver = getApplication<Application>().contentResolver
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val name = givenName ?: resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "attachment"
        val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(100).ifBlank { "attachment" }
        val target = File(File(graph.sessions.workspace(id), "attachments").apply { mkdirs() }, "${UUID.randomUUID()}-$safeName")
        withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0L
                        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; check(total <= 50L * 1024 * 1024) { "Attachments must be under 50 MB." }; output.write(buffer, 0, count) }
                    }
                } ?: kotlin.error("Could not open this file.")
            } catch (error: Exception) { target.delete(); throw error }
        }
        mutable.update { it.copy(attachments = it.attachments + PendingAttachment(UUID.randomUUID().toString(), name, target.absolutePath, type, target.length())) }
    }
    fun removeAttachment(id: String) { mutable.update { it.copy(attachments = it.attachments.filterNot { item -> item.id == id }) } }
    fun listFiles() = task {
        mutable.update { it.copy(isLoadingWorkspace = true, workspaceError = null) }
        try {
            val root = current.value?.let { graph.sessions.workspace(it) } ?: return@task
            // Files only, and not inside hidden folders such as .agents or
            // .codex: those are Codex's own state, not something to open.
            val files = withContext(Dispatchers.IO) {
                root.walkTopDown()
                    .onEnter { it == root || !it.name.startsWith(".") }
                    .filter { it.isFile }
                    .take(500)
                    .map { WorkspaceFileItem(it.relativeTo(root).invariantSeparatorsPath, it.length(), it.lastModified()) }
                    .toList()
            }
            mutable.update { it.copy(workspaceFiles = files) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(workspaceError = failure.message ?: "Could not read this chat's files.") }
        } finally { mutable.update { it.copy(isLoadingWorkspace = false) } }
    }
    fun resolveWorkspaceFile(item: WorkspaceFileItem): File {
        val root = graph.sessions.workspace(current.value ?: kotlin.error("No chat selected")).canonicalFile
        val file = File(root, item.path).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "File is outside this session." }
        return file
    }
    fun error(message: String) { mutable.update { it.copy(errorMessage = message) } }
    fun checkForUpdates(manual: Boolean = true) = task {
        mutable.update { it.copy(updateStatus = UpdateStatus.Checking) }
        try {
            val info = updateManager.checkForUpdates()
            if (info.isUpdateAvailable) {
                val dismissedKey = preferences.getString("dismissed_update_key", null)
                    ?: preferences.getString("dismissed_update_tag", null)
                val isDismissed = dismissedKey == (info.commitSha ?: info.latestTag)
                mutable.update { it.copy(updateStatus = UpdateStatus.Available(info), updateInfo = info, isUpdateBannerVisible = !isDismissed) }
            } else {
                mutable.update { it.copy(updateStatus = UpdateStatus.UpToDate(info.latestVersionName), updateInfo = info) }
                if (manual) mutable.update { it.copy(infoMessage = "You have the latest version (${info.latestVersionName}).") }
            }
        } catch (e: Exception) {
            if (manual) {
                mutable.update { it.copy(updateStatus = UpdateStatus.Error(e.message ?: "Failed to check for updates")) }
                error(e.message ?: "Failed to check for updates.")
            } else {
                // Background check stays silent on rate limit or network absence
                mutable.update { it.copy(updateStatus = UpdateStatus.Idle) }
            }
        }
    }
    fun downloadUpdate() = task {
        val info = mutable.value.updateInfo ?: return@task
        mutable.update { it.copy(updateStatus = UpdateStatus.Downloading(0f, 0L, info.apkSize)) }
        try {
            val apk = updateManager.downloadUpdate(info) { progress, downloaded, total ->
                mutable.update { it.copy(updateStatus = UpdateStatus.Downloading(progress, downloaded, total)) }
            }
            mutable.update { it.copy(updateStatus = UpdateStatus.ReadyToInstall(apk, info)) }
        } catch (e: Exception) {
            mutable.update { it.copy(updateStatus = UpdateStatus.Error(e.message ?: "Failed to download update")) }
            error("Download failed: ${e.message}")
        }
    }
    fun installUpdate() {
        val status = mutable.value.updateStatus
        val apk = (status as? UpdateStatus.ReadyToInstall)?.apkFile ?: return
        val app = getApplication<Application>()
        try {
            if (!updateManager.canRequestPackageInstalls()) {
                val intent = updateManager.createPermissionIntent()
                app.startActivity(intent)
                return
            }
            val intent = updateManager.createInstallIntent(apk)
            app.startActivity(intent)
        } catch (e: Exception) {
            error("Could not start package installer: ${e.message}")
        }
    }
    fun openInstallPermission() {
        val app = getApplication<Application>()
        runCatching { app.startActivity(updateManager.createPermissionIntent()) }
            .onFailure { error("Could not open install settings: ${it.message}") }
    }
    fun dismissUpdateBanner() {
        val info = mutable.value.updateInfo
        if (info != null) {
            preferences.edit().putString("dismissed_update_key", info.commitSha ?: info.latestTag).apply()
        }
        mutable.update { it.copy(isUpdateBannerVisible = false) }
    }
    private fun parsePort(value: String): Int = value.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: kotlin.error("Enter a port from 1 to 65535.")
    private fun task(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error(failure.message ?: "Something went wrong.") }
    }
}

private const val KEY_WELCOMED = "onboardingWelcomed"
private const val KEY_CONSENT_VERSION = "consentVersion"
private const val KEY_CONSENT_AT = "consentAt"
private const val KEY_ONBOARDED = "onboardingFinished"
private const val KEY_MODEL = "model"
private const val KEY_EFFORT = "reasoningEffort"
private const val KEY_CLAUDE_MODEL = "claudeModel"
private const val KEY_CLAUDE_EFFORT = "claudeReasoningEffort"
private const val KEY_DEFAULT_ENGINE = "defaultEngine"
private const val CLAUDE_SIGN_IN = "Sign in to Claude"

/** Long enough for Mike to reach Developer options, including turning them on. */
private const val WIRELESS_SETUP_TIMEOUT_MS = 300_000L
private const val WIRELESS_SWITCH_CHECK_MS = 5_000L

// The user approved this in the app before the run starts, so the prompt says
// so rather than asking Mike to ask again, which would end the turn and let
// the pairing reader time out.
private const val WIRELESS_SETUP_PROMPT =
    "Set up wireless debugging on this phone so you can run commands, move files and install apps. " +
        "I already approved turning on Developer options and Wireless debugging for this.\n" +
        "1. Open Settings > Developer options. If Developer options is hidden, open About phone and tap " +
        "Build number seven times. If the phone asks for a PIN, stop and tell me.\n" +
        "2. Turn on Wireless debugging and accept Android's confirmation.\n" +
        "3. Open Wireless debugging and tap \"Pair device with pairing code\". Leave that dialog open: " +
        "Hey Mike reads the code from it and pairs by itself. Never read the code aloud or type it anywhere.\n" +
        "4. Wait until the dialog closes, then go back to Hey Mike and tell me in one sentence whether it worked. " +
        "Change no other setting."
