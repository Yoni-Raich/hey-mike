package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.AgentEngine
import dev.androidagent.core.AgentModel
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RealtimeAudioChunk
import dev.androidagent.core.RealtimeTransport
import dev.androidagent.core.RealtimeVoiceEngine
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one engine the coordinator sees: the phone's Codex, or a computer's.
 *
 * A chat bound to a computer (see [RemoteStore.bind]) opens its thread on
 * that computer's Codex; every later call about the thread goes to the same
 * place. Sign-in, models, usage and voice stay the phone's. Two app-servers
 * both number their requests from zero, so a computer's tool and approval
 * requests are tagged with the computer before the coordinator sees them.
 *
 * Voice follows the thread too: realtime is started by the app-server that
 * owns the chat's thread, so a computer chat talks to the computer's Codex.
 * Its audio and events are that engine's until the next voice start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingAgentEngine(
    private val local: CodexEngine,
    private val hub: RemoteHub,
) : AgentEngine, RealtimeVoiceEngine {

    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The engine running voice now, or the one that ran it last. */
    private val voiceEngine = MutableStateFlow<RealtimeVoiceEngine>(local)
    override val voiceEvents: Flow<VoiceEvent> = voiceEngine.flatMapLatest { it.voiceEvents }
    override val voiceState: StateFlow<VoiceState> = voiceEngine
        .flatMapLatest { it.voiceState }
        .stateIn(voiceScope, SharingStarted.Eagerly, local.voiceState.value)

    override suspend fun startVoice(threadId: String, model: String?, transport: RealtimeTransport, offerSdp: String?) {
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
    /** The computer running the current turn, if any. */
    @Volatile private var activeComputer: String? = null

    override val events: Flow<EngineEvent> = merge(local.events, hub.events.mapNotNull(::translate))

    /** The computer binding of the chat whose folder is [workspace], or null for the phone. */
    fun bindingOf(workspace: File): RemoteBinding? = sessionIdOf(workspace)?.let(store::binding)

    override suspend fun connect() = local.connect()
    override suspend fun account(): AccountStatus = local.account()
    override suspend fun refreshUsage() = local.refreshUsage()
    override suspend fun login(): AccountStatus = local.login()
    override suspend fun logout() = local.logout()
    override suspend fun models(): List<String> = local.models()
    override suspend fun modelCatalog(): List<AgentModel> = local.modelCatalog()

    override suspend fun skillCatalog(workspace: File, forceReload: Boolean): List<AgentSkill> {
        val binding = bindingOf(workspace) ?: return local.skillCatalog(workspace, forceReload)
        return hub.engine(binding.computerId).skillCatalogAt(binding.cwd, forceReload)
    }

    override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>): String {
        val sessionId = sessionIdOf(workspace)
        val binding = sessionId?.let(store::binding) ?: return local.openSession(workspace, threadId, model, tools)
        val engine = hub.engine(binding.computerId)
        // Only a thread the computer made can be resumed there.
        val resumable = binding.threadId?.takeIf { it == threadId }
        val opened = engine.openSessionAt(binding.cwd, resumable, model, tools)
        threads[opened] = binding.computerId
        if (opened != binding.threadId) store.bind(sessionId, binding.copy(threadId = opened))
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
    override suspend fun steer(threadId: String, turnId: String, prompt: String) = engineFor(threadId).steer(threadId, turnId, prompt)
    override suspend fun interrupt(threadId: String, turnId: String) = engineFor(threadId).interrupt(threadId, turnId)

    override suspend fun answerTool(requestId: String, result: ToolResult) {
        val (computer, raw) = untag(requestId) ?: return local.answerTool(requestId, result)
        hub.engine(computer).answerTool(raw, result)
    }

    override suspend fun answerApproval(requestId: String, allow: Boolean) {
        val (computer, raw) = untag(requestId) ?: return local.answerApproval(requestId, allow)
        hub.engine(computer).answerApproval(raw, allow)
    }

    override suspend fun close() {
        activeComputer = null
        runCatching { hub.closeAll() }
        local.close()
    }

    /** The engine for a turn, remembering which computer, if any, is now working. */
    private suspend fun route(threadId: String): AgentEngine {
        val computer = computerOf(threadId)
        activeComputer = computer
        return if (computer == null) local else hub.engine(computer)
    }

    private suspend fun engineFor(threadId: String): AgentEngine =
        computerOf(threadId)?.let { hub.engine(it) } ?: local

    private fun computerOf(threadId: String): String? =
        threads[threadId] ?: store.bindingForThread(threadId)?.second?.computerId?.also { threads[threadId] = it }

    private fun translate(remote: RemoteEvent): EngineEvent? {
        val computer = remote.computerId
        return when (val event = remote.event) {
            is EngineEvent.ToolCall -> event.copy(requestId = tag(computer, event.requestId))
            is EngineEvent.Approval -> event.copy(requestId = tag(computer, event.requestId))
            // The computer's own sign-in and quota are not the phone's.
            is EngineEvent.AccountChanged -> null
            is EngineEvent.UsageChanged -> event.takeIf { it.threadId != null }
            // An unscoped failure ends whatever run is active, so a computer
            // that is not running this one keeps its failures to itself.
            is EngineEvent.Failure -> event.takeIf { !it.threadId.isNullOrBlank() || activeComputer == computer }
            else -> event
        }
    }

    companion object {
        private const val TAG = "remote|"

        internal fun tag(computerId: String, requestId: String) = "$TAG$computerId|$requestId"

        internal fun untag(requestId: String): Pair<String, String>? {
            if (!requestId.startsWith(TAG)) return null
            val parts = requestId.removePrefix(TAG).split('|', limit = 2)
            return if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }

        /** Chat folders are `<sessions>/<chat id>/workspace`. */
        internal fun sessionIdOf(workspace: File): String? =
            workspace.takeIf { it.name == "workspace" }?.parentFile?.name
    }
}
