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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as AgentApplication).graph
    val updateManager = AppUpdateManager(application)
    private val preferences = application.getSharedPreferences("ui", 0)
    private val current = MutableStateFlow<String?>(null)
    private val mutable = MutableStateFlow(
        AgentUiState(
            selectedModel = preferences.getString("model", null),
            selectedReasoningEffort = preferences.getString("reasoningEffort", null),
        )
    )
    val ui: StateFlow<AgentUiState> = mutable.asStateFlow()
    private val usageByThread = mutableMapOf<String, TokenUsage>()
    private var setupJob: Job? = null
    private var voiceLocalSessionId: String? = null
    private val pendingVoiceTexts = java.util.ArrayDeque<String>()

    init {
        viewModelScope.launch {
            try { checkForUpdates(manual = false) } catch (_: Exception) {}
        }
        viewModelScope.launch {
            graph.sessions.sessions.collect { list ->
                mutable.update { it.copy(sessions = list) }
                if (current.value == null || list.none { it.id == current.value }) {
                    val saved = preferences.getString("session", null)
                    current.value = list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
                }
                if (list.isEmpty()) current.value = graph.sessions.createSession().id
                updateTitle()
            }
        }
        viewModelScope.launch { current.filterNotNull().collectLatest { id ->
            preferences.edit().putString("session", id).apply()
            mutable.update { it.copy(activeSessionId = id, messages = emptyList(), attachments = emptyList(), isDrawerOpen = false) }
            updateTitle()
            if (graph.runtime.status.value.phase in setOf(RuntimePhase.READY, RuntimePhase.RUNNING)) {
                runCatching { loadSkills(id) }
            }
            graph.sessions.messages(id).collect { items -> mutable.update { it.copy(messages = items) } }
        } }
        viewModelScope.launch { graph.coordinator.state.collect { state -> mutable.update { it.copy(runState = state) } } }
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
        viewModelScope.launch { graph.engine.voiceEvents.collect(::handleVoiceEvent) }
        viewModelScope.launch { graph.engine.events.collect { event ->
            when (event) {
                is EngineEvent.AccountChanged -> { mutable.update { it.copy(accountStatus = event.status, infoMessage = if (event.status.signedIn) "Signed in. You can start chatting." else null) }; if (event.status.signedIn) loadModels() }
                is EngineEvent.UsageChanged -> {
                    val eventThread = event.threadId
                    val eventUsage = event.usage
                    if (eventThread != null && eventUsage != null) usageByThread[eventThread] = eventUsage
                    val threadId = mutable.value.sessions.firstOrNull { it.id == current.value }?.engineThreadId
                    mutable.update { it.copy(tokenUsage = usageByThread[threadId], usageLimits = event.limits ?: it.usageLimits) }
                }
                EngineEvent.SkillsChanged -> runCatching { loadSkills(forceReload = false) }
                is EngineEvent.Failure -> if (!graph.coordinator.state.value.active) error(event.message)
                else -> Unit
            }
        } }
    }
    private fun updateTitle() { mutable.update { state -> state.copy(activeSessionTitle = state.sessions.firstOrNull { it.id == current.value }?.title, tokenUsage = usageByThread[state.sessions.firstOrNull { it.id == current.value }?.engineThreadId]) } }
    fun editUi(change: (AgentUiState) -> AgentUiState) = mutable.update(change)
    fun newChat() = task { current.value = graph.sessions.createSession().id }
    fun select(id: String) { current.value = id }
    fun rename(id: String, title: String) = task { graph.sessions.rename(id, title) }
    fun delete(id: String) {
        if (graph.coordinator.state.value.sessionId == id && graph.coordinator.state.value.active) { error("Stop this chat before deleting it."); return }
        if (voiceLocalSessionId == id && graph.voice.state.value.active) { error("End the voice conversation before deleting it."); return }
        task { graph.queue.cancelSession(id); graph.sessions.deleteSession(id) }
    }
    fun send(text: String, attachments: List<PendingAttachment>) {
        val id = current.value ?: return
        if (graph.voice.state.value.active) {
            if (id != voiceLocalSessionId) { error("End voice before sending in another chat."); return }
            if (attachments.isNotEmpty()) { error("End voice before sending attachments."); return }
            if (graph.coordinator.answerApprovalByReply(text)) return
            task {
                synchronized(pendingVoiceTexts) { pendingVoiceTexts.addLast(text) }
                try {
                    graph.voice.appendText(text)
                    graph.sessions.append(ChatMessage(UUID.randomUUID().toString(), id, "user", text, System.currentTimeMillis()))
                } catch (failure: Exception) {
                    synchronized(pendingVoiceTexts) { pendingVoiceTexts.removeLastOccurrence(text) }
                    throw failure
                }
            }
            return
        }
        val active = graph.coordinator.state.value
        val paths = attachments.mapNotNull { it.path?.let(::File) }
        val images = attachments.filter { it.mimeType?.startsWith("image/") == true }.mapNotNull { it.path?.let(::File) }
        val otherFiles = paths.filter { it !in images }
        val promptText = text
        val prompt = if (otherFiles.isEmpty()) promptText else promptText + "\n\nAttached files in this session:\n" + otherFiles.joinToString("\n") { it.absolutePath }
        val snapshot = mutable.value
        val invokedSkillName = text.trimStart()
            .takeIf { it.startsWith("\$") }
            ?.drop(1)
            ?.takeWhile { !it.isWhitespace() }
            ?.takeIf { it.isNotBlank() }
        val invokedSkill = invokedSkillName?.let { name ->
            snapshot.availableSkills.firstOrNull { it.enabled && it.name.equals(name, ignoreCase = true) }
        }
        // Plan mode is carried by a model name, so fall back to the first
        // offered model when the user has not picked one.
        val model = snapshot.selectedModel
            ?: if (snapshot.planMode) snapshot.modelCatalog.firstOrNull()?.id ?: snapshot.availableModels.firstOrNull() else null
        if (snapshot.planMode && model == null) { error("Choose a model before using plan mode."); return }
        task {
            graph.queue.submit(QueuedTurn(sessionId = id, prompt = prompt, imagePaths = images.map { it.absolutePath },
                model = model, effort = selectedReasoningEffort(snapshot), skill = invokedSkill, planMode = snapshot.planMode))
            mutable.update { if (it.activeSessionId == id) it.copy(attachments = emptyList(), errorMessage = null) else it }
        }
    }
    fun cancelQueued(id: String) = task { graph.queue.cancel(id) }
    fun resumeQueue() = task { graph.queue.resume() }

    fun togglePlanMode() { mutable.update { it.copy(planMode = !it.planMode) } }

    fun compact() {
        val id = current.value ?: return
        if (graph.coordinator.state.value.active) { error("Wait for the agent to finish before compacting."); return }
        task {
            val thread = checkNotNull(graph.sessions.getSession(id)?.engineThreadId) { "There is nothing to compact yet. Send a message first." }
            graph.engine.compact(thread)
            note(id, "Compacting the chat to free up context")
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
            return
        }
        if (graph.voice.state.value.active) stopVoice() else graph.coordinator.stop()
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
    fun startAssistantVoice() {
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
                    current.value = graph.sessions.createSession().id
                }
                mutable.update { it.copy(voiceSummon = "Opening your conversation") }
                beginVoice()
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
    private suspend fun beginVoice() {
        graph.queue.pause()
        check(!graph.coordinator.state.value.active) { "Stop the current agent run before starting voice." }
        val sessionId = current.value ?: kotlin.error("Choose a chat first.")
        val session = graph.sessions.getSession(sessionId) ?: kotlin.error("Chat no longer exists.")
        graph.engine.connect()
        check(graph.engine.account().signedIn) { "Sign in to Codex in Settings first." }
        val snapshot = mutable.value
        val workspace = graph.sessions.workspace(sessionId)
        val threadId = graph.engine.openSession(
            workspace,
            session.engineThreadId,
            snapshot.selectedModel,
            graph.tools.definitions,
        )
        graph.sessions.setThread(sessionId, threadId)
        voiceLocalSessionId = sessionId
        mutable.update { it.copy(errorMessage = null, voiceTranscript = "", voiceTranscriptRole = null) }
        graph.coordinator.beginVoice(sessionId, threadId, workspace)
        try {
            // Realtime selects its own compatible voice model. The normal Codex
            // model remains a thread setting and is not forced into this RPC.
            graph.voice.start(threadId)
        } catch (failure: Throwable) {
            graph.coordinator.endVoice()
            voiceLocalSessionId = null
            throw failure
        }
    }
    private fun stopVoice() = task {
        // Revoke before the remote stop so no new device action can begin while
        // the voice session is ending. Completed side effects are not undone.
        graph.coordinator.endVoice()
        graph.voice.stop()
        voiceLocalSessionId = null
        synchronized(pendingVoiceTexts) { pendingVoiceTexts.clear() }
        mutable.update { it.copy(voiceTranscript = "", voiceTranscriptRole = null) }
    }
    fun prepare() {
        if (setupJob?.isActive == true) return
        setupJob = task {
            mutable.update { it.copy(isPreparingRuntime = true, errorMessage = null) }
            try {
                graph.runtime.prepare(); graph.engine.connect()
                val account = graph.engine.account()
                mutable.update { it.copy(accountStatus = account) }
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
    fun logout() = task { check(!graph.coordinator.state.value.active && !graph.voice.state.value.active) { "Stop the current run or voice conversation before signing out." }; graph.engine.logout(); mutable.update { it.copy(accountStatus = AccountStatus(false, "Sign in to Codex")) } }
    fun refreshAccount() = task {
        if (graph.runtime.status.value.phase !in setOf(RuntimePhase.READY, RuntimePhase.RUNNING)) return@task
        mutable.update { it.copy(isRefreshingAccount = true) }
        try {
            val account = graph.engine.account()
            mutable.update { it.copy(accountStatus = account) }
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

    private suspend fun loadModels() {
        mutable.update { it.copy(isLoadingModels = true) }
        try {
            val catalog = graph.engine.modelCatalog()
            val models = catalog.map { it.id }
            mutable.update { current ->
                if (catalog.isEmpty()) {
                    current.copy(availableModels = models, modelCatalog = catalog)
                } else {
                    val selectedModel = current.selectedModel?.takeIf { it in models }
                    val selectedEffort = selectedModel
                        ?.let { id -> catalog.firstOrNull { it.id == id } }
                        ?.let { normalizeReasoningEffort(it, current.selectedReasoningEffort) }
                    persistModelAndEffort(selectedModel, selectedEffort)
                    current.copy(
                        availableModels = models,
                        modelCatalog = catalog,
                        selectedModel = selectedModel,
                        selectedReasoningEffort = selectedEffort,
                    )
                }
            }
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
    fun model(value: String) {
        val model = mutable.value.modelCatalog.firstOrNull { it.id == value }
        val effort = model?.let { normalizeReasoningEffort(it, mutable.value.selectedReasoningEffort) }
        persistModelAndEffort(value, effort)
        mutable.update { it.copy(selectedModel = value, selectedReasoningEffort = effort) }
    }
    fun reasoningEffort(value: String?) {
        val current = mutable.value
        val model = current.selectedModel?.let { id -> current.modelCatalog.firstOrNull { it.id == id } } ?: return
        val selected = value?.takeIf { effort -> model.reasoningEfforts.any { it.value == effort } }
        persistModelAndEffort(current.selectedModel, selected)
        mutable.update { it.copy(selectedReasoningEffort = selected) }
    }
    private fun selectedReasoningEffort(state: AgentUiState): String? {
        val model = state.selectedModel?.let { id -> state.modelCatalog.firstOrNull { it.id == id } } ?: return null
        return state.selectedReasoningEffort
            ?.takeIf { value -> model.reasoningEfforts.any { it.value == value } }
            ?: model.defaultReasoningEffort?.takeIf { value -> model.reasoningEfforts.any { it.value == value } }
    }
    private fun normalizeReasoningEffort(model: AgentModel, requested: String?): String? {
        if (model.reasoningEfforts.isEmpty()) return null
        // Keep Auto as the initial choice so the server can apply its own
        // advertised default. A stale explicit choice is cleared on model
        // changes instead of guessing a level that may not be supported.
        return requested?.takeIf { value -> model.reasoningEfforts.any { it.value == value } }
    }
    private fun persistModelAndEffort(model: String?, effort: String?) {
        preferences.edit().apply {
            if (model.isNullOrBlank()) remove("model") else putString("model", model)
            if (effort.isNullOrBlank()) remove("reasoningEffort") else putString("reasoningEffort", effort)
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
        if (!dev.androidagent.a11y.PairingWatcher.available) {
            mutable.update {
                it.copy(infoMessage = "Turn on Screen control to read the code automatically, or type it below.")
            }
            return@task
        }
        mutable.update {
            it.copy(infoMessage = "Tap \"Pair device with pairing code\" — the code is read from the dialog.", errorMessage = null)
        }
        val details = dev.androidagent.a11y.PairingWatcher.await()
        if (details == null) {
            mutable.update { it.copy(infoMessage = "No pairing dialog was found. Type the code below instead.") }
            return@task
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
    fun addAttachment(uri: Uri) = task {
        val id = current.value ?: return@task
        val resolver = getApplication<Application>().contentResolver
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "attachment"
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
    private suspend fun handleVoiceEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.TranscriptDelta -> if (event.threadId == graph.voice.state.value.threadId && voiceLocalSessionId != null) {
                mutable.update { state ->
                    val text = if (state.voiceTranscriptRole == event.role) state.voiceTranscript + event.delta else event.delta
                    state.copy(voiceTranscript = text, voiceTranscriptRole = event.role)
                }
            }
            is VoiceEvent.TranscriptDone -> {
                val localSessionId = voiceLocalSessionId ?: return
                if (event.threadId != graph.voice.state.value.threadId) return
                val text = event.text.trim()
                val skipTypedUserEcho = if (event.role.equals("user", ignoreCase = true)) {
                    synchronized(pendingVoiceTexts) {
                        if (pendingVoiceTexts.peekFirst() == text) {
                            pendingVoiceTexts.removeFirst()
                            true
                        } else false
                    }
                } else false
                if (text.isNotBlank() && !skipTypedUserEcho) {
                    val role = if (event.role.equals("assistant", ignoreCase = true)) "assistant" else "user"
                    // Saying "yes" / "כן" answers a waiting approval: in voice
                    // mode the card is under the voice screen. Only the user's
                    // own transcript can do this, never the agent's speech.
                    if (role == "user") graph.coordinator.answerApprovalByReply(text, record = false)
                    graph.sessions.append(
                        ChatMessage(UUID.randomUUID().toString(), localSessionId, role, text, System.currentTimeMillis())
                    )
                    val session = graph.sessions.getSession(localSessionId)
                    if (role == "user" && session?.title == "New chat") graph.sessions.rename(localSessionId, text.take(48))
                }
                mutable.update { state ->
                    if (state.voiceTranscriptRole == event.role) state.copy(voiceTranscript = "", voiceTranscriptRole = null)
                    else state
                }
            }
            is VoiceEvent.Failure -> {
                graph.coordinator.endVoice()
                voiceLocalSessionId = null
                synchronized(pendingVoiceTexts) { pendingVoiceTexts.clear() }
                mutable.update { it.copy(voiceTranscript = "", voiceTranscriptRole = null) }
                error(event.message)
            }
            is VoiceEvent.Closed -> {
                graph.coordinator.endVoice()
                voiceLocalSessionId = null
                synchronized(pendingVoiceTexts) { pendingVoiceTexts.clear() }
                mutable.update { it.copy(voiceTranscript = "", voiceTranscriptRole = null) }
            }
            is VoiceEvent.Started, is VoiceEvent.SdpAnswer, is VoiceEvent.OutputAudio -> Unit
        }
    }
    private fun task(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error(failure.message ?: "Something went wrong.") }
    }
}
