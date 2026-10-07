package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.AgentEngine
import dev.androidagent.core.AgentModel
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.EngineKind
import dev.androidagent.core.EngineSwitch
import dev.androidagent.core.RealtimeAudioChunk
import dev.androidagent.core.RealtimeTransport
import dev.androidagent.core.RealtimeVoiceEngine
import dev.androidagent.core.SessionStore
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import dev.androidagent.core.VoiceEvent
import dev.androidagent.core.VoiceState
import dev.androidagent.enginecodex.CodexEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one engine the coordinator sees: the phone's Codex, a computer's, or
 * Claude on the phone.
 *
 * A chat bound to a computer (see [RemoteStore.bind]) opens its thread on
 * that computer's Codex; every later call about the thread goes to the same
 * place. Sign-in, models, usage and voice stay the phone's. Two app-servers
 * both number their requests from zero, so a computer's tool and approval
 * requests are tagged with the computer before the coordinator sees them.
 *
 * A chat whose [dev.androidagent.core.ChatSession.engine] is Claude opens on
 * [claude] instead, looked up through [sessions] every time the chat is
 * opened, because a chat can move between the engines from one turn to the
 * next. A thread stays with the engine that made it, so every later call
 * about that thread goes there. Claude's request ids are tagged the same way
 * as a computer's, so they can never meet a Codex id. The plain account calls
 * stay Codex's; [account] with a kind reaches Claude.
 *
 * A Claude chat bound to a computer runs on that computer's own Claude Code
 * ([RemoteHub.claude]), with the computer's own sign-in; the phone's Claude
 * is not involved, and need not be set up.
 *
 * Voice follows the thread too: realtime is started by the app-server that
 * owns the chat's thread, so a computer chat talks to the computer's Codex.
 * Its audio and events are that engine's until the next voice start. A
 * Claude thread has no voice of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingAgentEngine(
    private val local: CodexEngine,
    private val hub: RemoteHub,
    /** The phone's Claude, or null in a build without it. */
    private val claude: AgentEngine? = null,
    /** Where a chat's engine is read. Without it every phone chat is Codex. */
    private val sessions: SessionStore? = null,
) : AgentEngine, RealtimeVoiceEngine {

    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The engine running voice now, or the one that ran it last. */
    private val voiceEngine = MutableStateFlow<RealtimeVoiceEngine>(local)
    override val voiceEvents: Flow<VoiceEvent> = voiceEngine.flatMapLatest { it.voiceEvents }
    override val voiceState: StateFlow<VoiceState> = voiceEngine
        .flatMapLatest { it.voiceState }
        .stateIn(voiceScope, SharingStarted.Eagerly, local.voiceState.value)

    override suspend fun startVoice(threadId: String, model: String?, transport: RealtimeTransport, offerSdp: String?) {
        check(!isClaude(threadId)) { "Voice works only in Codex chats." }
        val owner: RealtimeVoiceEngine = computerOf(threadId)?.let { hub.engine(it) } ?: local
        voiceEngine.value = owner
        owner.startVoice(threadId, model, transport, offerSdp)
    }

    override suspend fun appendAudio(audio: RealtimeAudioChunk) = voiceEngine.value.appendAudio(audio)
    override suspend fun appendText(text: String, role: String) = voiceEngine.value.appendText(text, role)
    override suspend fun appendSpeech(text: String) = voiceEngine.value.appendSpeech(text)
    override suspend fun stopVoice() = voiceEngine.value.stopVoice()

    private val store get() = hub.store
    /** Remote thread -> computer. Rebuilt from the store after a restart. */
    private val threads = ConcurrentHashMap<String, String>()
    private val localCodexThreads = ConcurrentHashMap.newKeySet<String>()
    /** Threads Claude owns. Rebuilt from the chats after a restart. */
    private val claudeThreads = ConcurrentHashMap.newKeySet<String>()
    /** Claude thread -> computer, for the Claude threads that run on one. */
    private val claudeComputers = ConcurrentHashMap<String, String>()
    /** Events of the phone's and the computers' Codex only: sign-in and quota here are Codex's. */
    val codexEvents: Flow<EngineEvent> =
        merge(phoneEvents(local.events, EngineKind.CODEX), computerEvents(EngineKind.CODEX))

    private val computerClaudeEvents: Flow<EngineEvent> =
        computerEvents(EngineKind.CLAUDE)

    private fun computerEvents(kind: EngineKind): Flow<EngineEvent> = hub.events.transform { remote ->
        if (remote.engine == kind) translate(remote).forEach { emit(it) }
    }

    private fun phoneEvents(source: Flow<EngineEvent>, kind: EngineKind): Flow<EngineEvent> = source.transform { event ->
        val events = if (event is EngineEvent.Failure && event.threadId.isNullOrBlank()) {
            phoneThreadIds(kind).map { event.copy(threadId = it, turnId = null, uncertain = true) }
        } else listOf(event)
        events.forEach { emit(if (kind == EngineKind.CLAUDE) fromClaude(it) else it) }
    }

    override val events: Flow<EngineEvent> =
        claude?.let { merge(codexEvents, computerClaudeEvents, phoneEvents(it.events, EngineKind.CLAUDE)) } ?: merge(codexEvents, computerClaudeEvents)

    /** The computer binding of the chat whose folder is [workspace], or null for the phone. */
    fun bindingOf(workspace: File): RemoteBinding? = sessionIdOf(workspace)?.let(store::binding)

    /** The engine the chat whose folder is [workspace] runs on. */
    suspend fun engineOf(workspace: File): EngineKind =
        sessionIdOf(workspace)?.let { sessions?.getSession(it)?.engine } ?: EngineKind.CODEX

    override suspend fun connect() = local.connect()
    override suspend fun account(): AccountStatus = local.account()
    override suspend fun connect(kind: EngineKind) = if (kind == EngineKind.CLAUDE) claude().connect() else local.connect()
    override suspend fun account(kind: EngineKind): AccountStatus =
        if (kind == EngineKind.CLAUDE) claude().account() else local.account()

    /** A Claude chat on a computer needs that computer's Claude Code, not the phone's. */
    override suspend fun connect(kind: EngineKind, workspace: File) {
        val binding = bindingOf(workspace).takeIf { kind == EngineKind.CLAUDE } ?: return connect(kind)
        hub.claude(binding.computerId).connect()
    }

    override suspend fun account(kind: EngineKind, workspace: File): AccountStatus {
        val binding = bindingOf(workspace).takeIf { kind == EngineKind.CLAUDE } ?: return account(kind)
        val status = hub.claude(binding.computerId).account()
        // The sign-in is the computer's own, so the way to fix it is there, not in Mike's Settings.
        check(status.signedIn) {
            "Claude Code on ${store.computer(binding.computerId)?.label ?: "the computer"} is not signed in. Run `claude` there and sign in, then send again."
        }
        return status
    }
    override suspend fun refreshUsage() = local.refreshUsage()
    override suspend fun login(): AccountStatus = local.login()
    override suspend fun logout() = local.logout()
    override suspend fun models(): List<String> = local.models()
    override suspend fun modelCatalog(): List<AgentModel> = local.modelCatalog()

    override suspend fun skillCatalog(workspace: File, forceReload: Boolean): List<AgentSkill> {
        val binding = bindingOf(workspace)
        if (engineOf(workspace) == EngineKind.CLAUDE) {
            // The computer's Claude Code lists its own skills when asked with "/"; none are read from here.
            return if (binding == null) claude().skillCatalog(workspace, forceReload) else emptyList()
        }
        if (binding == null) return local.skillCatalog(workspace, forceReload)
        return hub.engine(binding.computerId).skillCatalogAt(binding.cwd, forceReload)
    }

    override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>): String {
        val sessionId = sessionIdOf(workspace)
        if (engineOf(workspace) == EngineKind.CLAUDE) {
            val computer = sessionId?.let(store::binding)
            val opened = if (computer == null) claude().openSession(workspace, threadId, model, tools)
            else hub.claude(computer.computerId).openSessionAt(computer.cwd, threadId, model, tools)
            claudeThreads += opened
            if (computer != null) claudeComputers[opened] = computer.computerId
            return opened
        }
        val binding = sessionId?.let(store::binding) ?: return local.openSession(workspace, threadId, model, tools).also { localCodexThreads += it }
        val engine = hub.engine(binding.computerId)
        // Only a thread the computer made can be resumed there.
        val resumable = binding.threadId?.takeIf { it == threadId }
        // A thread the computer already has is continued or reported, never
        // silently swapped for an empty one.
        val opened = try {
            engine.openSessionAt(binding.cwd, resumable, model, tools, freshIfLost = resumable == null)
        } catch (error: IllegalStateException) {
            val remedy = if (binding.importedFromPc == true) {
                "Close it in Codex on the computer, or continue in a copy from the banner above the message box."
            } else {
                "Reconnect this computer in Mike and try again."
            }
            throw IllegalStateException(
                "${error.message}. $remedy",
                error,
            )
        }
        threads[opened] = binding.computerId
        if (opened != binding.threadId) {
            store.bind(sessionId, binding.copy(threadId = opened))
        }
        return opened
    }

    override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String =
        route(threadId).startTurn(threadId, prompt, images)

    override suspend fun startTurn(threadId: String, prompt: String, images: List<File>, reasoningEffort: String?): String =
        route(threadId).startTurn(threadId, prompt, images, reasoningEffort)

    override suspend fun startTurn(
        threadId: String, prompt: String, images: List<File>, reasoningEffort: String?, skill: AgentSkill?,
    ): String = route(threadId).startTurn(threadId, prompt, images, reasoningEffort, skill)

    override suspend fun startTurn(
        threadId: String, prompt: String, images: List<File>, reasoningEffort: String?, skill: AgentSkill?, adbStatus: AdbStatus,
    ): String = route(threadId).startTurn(threadId, prompt, images, reasoningEffort, skill, adbStatus)

    override suspend fun startTurn(
        threadId: String, prompt: String, images: List<File>, reasoningEffort: String?, skill: AgentSkill?,
        capabilities: DeviceCapabilities,
    ): String = route(threadId).startTurn(threadId, prompt, images, reasoningEffort, skill, capabilities)

    override suspend fun startTurn(
        threadId: String, prompt: String, images: List<File>, reasoningEffort: String?, skill: AgentSkill?,
        capabilities: DeviceCapabilities, planModel: String?,
    ): String = route(threadId).startTurn(threadId, prompt, images, reasoningEffort, skill, capabilities, planModel)

    override suspend fun compact(threadId: String) = engineFor(threadId).compact(threadId)
    override suspend fun renameThread(threadId: String, title: String) {
        engineFor(threadId).renameThread(threadId, title)
        if (!isClaude(threadId)) computerOf(threadId)?.let { hub.threadRenamed(it, threadId, title) }
    }
    override suspend fun threadName(threadId: String): String? {
        val name = engineFor(threadId).threadName(threadId)
        if (name != null && !isClaude(threadId)) computerOf(threadId)?.let { hub.threadRenamed(it, threadId, name) }
        return name
    }
    override suspend fun steer(threadId: String, turnId: String, prompt: String) = engineFor(threadId).steer(threadId, turnId, prompt)
    override suspend fun interrupt(threadId: String, turnId: String) = engineFor(threadId).interrupt(threadId, turnId)

    override suspend fun answerTool(requestId: String, result: ToolResult) {
        untagClaude(requestId)?.let { return claude().answerTool(it, result) }
        untag(requestId, CLAUDE_REMOTE_TAG)?.let { (computer, raw) -> return hub.claude(computer).answerTool(raw, result) }
        val (computer, raw) = untag(requestId) ?: return local.answerTool(requestId, result)
        hub.engine(computer).answerTool(raw, result)
    }

    override suspend fun answerApproval(requestId: String, allow: Boolean) {
        untagClaude(requestId)?.let { return claude().answerApproval(it, allow) }
        untag(requestId, CLAUDE_REMOTE_TAG)?.let { (computer, raw) -> return hub.claude(computer).answerApproval(raw, allow) }
        val (computer, raw) = untag(requestId) ?: return local.answerApproval(requestId, allow)
        hub.engine(computer).answerApproval(raw, allow)
    }

    override suspend fun close() {
        runCatching { hub.closeAll() }
        claude?.let { runCatching { it.close() } }
        local.close()
    }

    private fun claude(): AgentEngine = claude ?: error("Claude is not available in this build.")

    private suspend fun route(threadId: String): AgentEngine = engineFor(threadId)

    private suspend fun engineFor(threadId: String): AgentEngine {
        if (isClaude(threadId)) return claudeFor(threadId)
        return computerOf(threadId)?.let { hub.engine(it) } ?: local
    }

    private suspend fun claudeFor(threadId: String): AgentEngine =
        claudeComputerOf(threadId)?.let { hub.claude(it) } ?: claude()

    /** The computer a Claude thread runs on: remembered when opened, else read from the chat that holds it. */
    private fun claudeComputerOf(threadId: String): String? =
        claudeComputers[threadId]
            ?: sessions?.sessions?.value?.firstOrNull { EngineSwitch.engineOf(it, threadId) == EngineKind.CLAUDE }
                ?.let { store.binding(it.id)?.computerId }
                ?.also { claudeComputers[threadId] = it }

    /**
     * Claude threads are remembered when opened; after a restart the chat that
     * stored one names it, whether the chat runs on Claude now or has moved
     * to Codex and keeps the Claude thread for later.
     */
    private fun isClaude(threadId: String): Boolean {
        if (threadId in claudeThreads) return true
        val owned = sessions?.sessions?.value?.any { EngineSwitch.engineOf(it, threadId) == EngineKind.CLAUDE } == true
        if (owned) claudeThreads += threadId
        return owned
    }

    private fun computerOf(threadId: String): String? =
        threads[threadId] ?: store.bindingForThread(threadId)?.second?.computerId?.also { threads[threadId] = it }

    private fun fromClaude(event: EngineEvent): EngineEvent = when (event) {
        is EngineEvent.ToolCall -> event.copy(requestId = CLAUDE_TAG + event.requestId)
        is EngineEvent.Approval -> event.copy(requestId = CLAUDE_TAG + event.requestId)
        else -> event
    }

    private fun phoneThreadIds(kind: EngineKind): Set<String> {
        val ids = if (kind == EngineKind.CODEX) localCodexThreads.toMutableSet()
            else claudeThreads.filter { claudeComputerOf(it) == null }.toMutableSet()
        sessions?.sessions?.value?.filter { store.binding(it.id) == null }?.forEach { chat ->
            if (chat.engine == kind) chat.engineThreadId?.let(ids::add)
            chat.parked[kind]?.threadId?.let(ids::add)
        }
        return ids
    }

    /** A server failure belongs to every thread on that server, regardless of which chat sent last. */
    private fun computerThreadIds(computer: String, kind: EngineKind): Set<String> {
        val known = if (kind == EngineKind.CLAUDE) claudeComputers else threads
        val ids = known.entries.filter { it.value == computer }.map { it.key }.toMutableSet()
        if (kind == EngineKind.CODEX) {
            ids += store.state.value.bindings.values.filter { it.computerId == computer }
                .mapNotNull { it.threadId }.filterNot(::isClaude)
        }
        sessions?.sessions?.value?.filter { store.binding(it.id)?.computerId == computer }?.forEach { chat ->
            if (chat.engine == kind) chat.engineThreadId?.let(ids::add)
            chat.parked[kind]?.threadId?.let(ids::add)
        }
        return ids
    }

    private fun translate(remote: RemoteEvent): List<EngineEvent> {
        val computer = remote.computerId
        // Two engines on one computer each number their own requests.
        val prefix = if (remote.engine == EngineKind.CLAUDE) CLAUDE_REMOTE_TAG else TAG
        val event = remote.event
        if (event is EngineEvent.Failure && event.threadId.isNullOrBlank()) {
            // Passing this unscoped would end unrelated phone and computer runs.
            // Dropping it would leave children waiting forever after their server died.
            return computerThreadIds(computer, remote.engine).map { event.copy(threadId = it, turnId = null, uncertain = true) }
        }
        val translated = when (event) {
            is EngineEvent.ToolCall -> event.copy(requestId = tag(computer, event.requestId, prefix))
            is EngineEvent.Approval -> event.copy(requestId = tag(computer, event.requestId, prefix))
            // The computer's own sign-in and quota are not the phone's.
            is EngineEvent.AccountChanged -> null
            is EngineEvent.UsageChanged -> event.takeIf { it.threadId != null }
            is EngineEvent.ItemActivity -> event.copy(remote = true)
            else -> event
        }
        return listOfNotNull(translated)
    }

    companion object {
        private const val TAG = "remote|"
        private const val CLAUDE_TAG = "claude|"
        private const val CLAUDE_REMOTE_TAG = "remote-claude|"

        internal fun tag(computerId: String, requestId: String, prefix: String = TAG) = "$prefix$computerId|$requestId"

        internal fun untag(requestId: String, prefix: String = TAG): Pair<String, String>? {
            if (!requestId.startsWith(prefix)) return null
            val parts = requestId.removePrefix(prefix).split('|', limit = 2)
            return if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }

        internal fun untagClaude(requestId: String): String? =
            requestId.takeIf { it.startsWith(CLAUDE_TAG) }?.removePrefix(CLAUDE_TAG)?.takeIf { it.isNotEmpty() }

        /** Chat folders are `<sessions>/<chat id>/workspace`. */
        internal fun sessionIdOf(workspace: File): String? =
            workspace.takeIf { it.name == "workspace" }?.parentFile?.name
    }
}
