package dev.androidagent.app

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
            graph.sessions.messages(id).collect { items -> mutable.update { it.copy(messages = items) } }
        } }
        viewModelScope.launch { graph.coordinator.state.collect { state -> mutable.update { it.copy(runState = state) } } }
        viewModelScope.launch { graph.adb.status.collect { state -> mutable.update { it.copy(adbStatus = state) } } }
        viewModelScope.launch { graph.runtime.status.collect { state -> mutable.update { it.copy(runtimeStatus = state) } } }
        viewModelScope.launch { graph.voice.state.collect { state -> mutable.update { it.copy(voiceState = state) } } }
        viewModelScope.launch { graph.engine.voiceEvents.collect(::handleVoiceEvent) }
        viewModelScope.launch { graph.engine.events.collect { event ->
            when (event) {
                is EngineEvent.AccountChanged -> { mutable.update { it.copy(accountStatus = event.status, infoMessage = if (event.status.signedIn) "Signed in. You can start chatting." else null) }; if (event.status.signedIn) loadModels() }
                is EngineEvent.Failure -> if (!graph.coordinator.state.value.active) error(event.message)
                else -> Unit
            }
        } }
    }
    private fun updateTitle() { mutable.update { state -> state.copy(activeSessionTitle = state.sessions.firstOrNull { it.id == current.value }?.title) } }
    fun editUi(change: (AgentUiState) -> AgentUiState) = mutable.update(change)
    fun newChat() = task { current.value = graph.sessions.createSession().id }
    fun select(id: String) { current.value = id }
    fun rename(id: String, title: String) = task { graph.sessions.rename(id, title) }
    fun delete(id: String) {
        if (graph.coordinator.state.value.sessionId == id && graph.coordinator.state.value.active) { error("Stop this chat before deleting it."); return }
        if (voiceLocalSessionId == id && graph.voice.state.value.active) { error("End the voice conversation before deleting it."); return }
        task { graph.sessions.deleteSession(id) }
    }
    fun send(text: String, attachments: List<PendingAttachment>) {
        val id = current.value ?: return
        if (graph.voice.state.value.active) {
            if (attachments.isNotEmpty()) { error("End voice before sending attachments."); return }
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
        if (active.active && active.sessionId != id) { error("Another chat is working. Stop it before starting this one."); return }
        val paths = attachments.mapNotNull { it.path?.let(::File) }
        val images = attachments.filter { it.mimeType?.startsWith("image/") == true }.mapNotNull { it.path?.let(::File) }
        val otherFiles = paths.filter { it !in images }
        val prompt = if (otherFiles.isEmpty()) text else text + "\n\nAttached files in this session:\n" + otherFiles.joinToString("\n") { it.absolutePath }
        val snapshot = mutable.value
        graph.coordinator.send(
            id,
            prompt,
            images,
            snapshot.selectedModel,
            selectedReasoningEffort(snapshot),
        )
        mutable.update { it.copy(attachments = emptyList(), errorMessage = null) }
    }
    fun stop() {
        if (graph.voice.state.value.active) stopVoice() else graph.coordinator.stop()
    }
    fun toggleVoice() {
        if (graph.voice.state.value.active) stopVoice() else startVoice()
    }
    private fun startVoice() = task {
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
            } finally { mutable.update { it.copy(isPreparingRuntime = false) } }
        }
    }
    fun login() = task {
        val status = graph.engine.login()
        mutable.update { it.copy(accountStatus = status, isSettingsOpen = true, errorMessage = null) }
    }
    fun logout() = task { check(!graph.coordinator.state.value.active && !graph.voice.state.value.active) { "Stop the current run or voice conversation before signing out." }; graph.engine.logout(); mutable.update { it.copy(accountStatus = AccountStatus(false, "Sign in to Codex")) } }
    fun refreshAccount() = task { if (graph.runtime.status.value.phase in setOf(RuntimePhase.READY, RuntimePhase.RUNNING)) { val account = graph.engine.account(); mutable.update { it.copy(accountStatus = account) } } }
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
    fun pair(code: String, port: String) = task {
        check(!graph.coordinator.state.value.active) { "Stop the current run before changing the connection." }
        mutable.update { it.copy(isPairing = true) }
        try {
            graph.adb.pair(parsePort(port), code.trim())
            mutable.update { it.copy(infoMessage = "Paired. Looking for the Wireless Debugging connect port…") }
            discover()
        }
        finally { mutable.update { it.copy(isPairing = false) } }
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
            val files = withContext(Dispatchers.IO) { root.walkTopDown().filter { it != root }.take(500).map { WorkspaceFileItem(it.relativeTo(root).path, it.length(), it.lastModified(), it.isDirectory) }.toList() }
            mutable.update { it.copy(workspaceFiles = files) }
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
            is VoiceEvent.TranscriptDelta -> if (event.threadId == graph.voice.state.value.threadId) {
                mutable.update { state ->
                    val text = if (state.voiceTranscriptRole == event.role) state.voiceTranscript + event.delta else event.delta
                    state.copy(voiceTranscript = text, voiceTranscriptRole = event.role)
                }
            }
            is VoiceEvent.TranscriptDone -> {
                val localSessionId = voiceLocalSessionId ?: return
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
            is VoiceEvent.Started, is VoiceEvent.OutputAudio -> Unit
        }
    }
    private fun task(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error(failure.message ?: "Something went wrong.") }
    }
}
