package dev.androidagent.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

enum class ConnectionPhase { DISCONNECTED, DISCOVERING, PAIRING, CONNECTING, CONNECTED, ERROR }
data class AdbStatus(val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED, val message: String = "Not connected", val port: Int? = null)
data class AdbEndpoint(val port: Int, val pairing: Boolean, val host: String = "127.0.0.1")
data class CommandResult(val output: String, val exitCode: Int)
interface AdbTransport {
    val status: StateFlow<AdbStatus>
    suspend fun discover(): List<AdbEndpoint>
    suspend fun pair(port: Int, code: String)
    suspend fun connect(port: Int)
    suspend fun execute(command: String, timeoutMs: Long = 30_000): CommandResult
    suspend fun executeBytes(command: String, timeoutMs: Long = 30_000): ByteArray
    suspend fun cancelActive()
    suspend fun disconnect()
    suspend fun forgetPairing()
}

@Serializable
data class ChatSession(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val engineThreadId: String? = null)
@Serializable
data class ChatMessage(val id: String, val sessionId: String, val role: String, val text: String, val createdAt: Long, val state: String = "complete", val attachmentPaths: List<String> = emptyList())
interface SessionStore {
    val sessions: StateFlow<List<ChatSession>>
    suspend fun createSession(): ChatSession
    suspend fun getSession(id: String): ChatSession?
    fun messages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun append(message: ChatMessage)
    suspend fun updateMessage(id: String, text: String, state: String = "complete")
    suspend fun setThread(sessionId: String, threadId: String)
    suspend fun rename(sessionId: String, title: String)
    suspend fun deleteSession(sessionId: String)
    fun workspace(sessionId: String): File
    suspend fun loadQueuedTurns(): List<QueuedTurn> = emptyList()
    suspend fun saveQueuedTurns(turns: List<QueuedTurn>) {}
}

enum class RuntimePhase { MISSING, PREPARING, READY, RUNNING, ERROR }
data class RuntimeStatus(val phase: RuntimePhase = RuntimePhase.MISSING, val message: String = "Runtime not ready", val progress: Float? = null)
interface RuntimeHost {
    val status: StateFlow<RuntimeStatus>
    val homeDirectory: File
    suspend fun prepare()
    suspend fun startAppServer(): Process
    suspend fun stop()
}

data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)
data class ToolResult(val text: String, val imageBase64: String? = null, val success: Boolean = true, val attachmentPaths: List<String> = emptyList())
data class LocalIntentRequest(
    val action: String,
    val uri: String?,
    val packageName: String?,
    val reason: String,
    /** Shown on the card too: an amount in an extra is as much the request as one in the uri. */
    val extras: Map<String, IntentExtra> = emptyMap(),
)
data class AccountStatus(val signedIn: Boolean, val label: String, val loginUrl: String? = null, val userCode: String? = null)

/** A reasoning effort advertised by the connected engine for one model. */
data class ReasoningEffortOption(val value: String, val description: String = "")

/** Model metadata returned by the connected engine's model catalog. */
data class AgentModel(
    val id: String,
    val displayName: String = id,
    val reasoningEfforts: List<ReasoningEffortOption> = emptyList(),
    val defaultReasoningEffort: String? = null,
)

/** Skill metadata returned by Codex's native skills/list catalog. */
@Serializable
data class AgentSkill(
    val name: String,
    val description: String,
    val path: String,
    val scope: String,
    val enabled: Boolean = true,
    /** From the skill's interface block, when its author gave one. */
    val displayName: String? = null,
    val shortDescription: String? = null,
    /** A `#RRGGBB` colour the skill's author picked for it. */
    val brandColor: String? = null,
    /** What to put in the composer when the skill is picked from an empty field. */
    val defaultPrompt: String? = null,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
    val summary: String get() = shortDescription?.takeIf { it.isNotBlank() } ?: description
}
data class TokenUsage(val total: Long, val input: Long, val output: Long, val cachedInput: Long = 0, val contextWindow: Long? = null)
data class UsageLimit(val name: String, val usedPercent: Double?, val resetsAt: Long? = null, val windowMinutes: Long? = null)
data class RunMetrics(val firstResponseMs: Long?, val totalMs: Long, val toolCalls: Int, val toolMs: Long)

sealed interface EngineEvent {
    data class TurnStarted(val threadId: String, val turnId: String) : EngineEvent
    data class TextDelta(val text: String, val threadId: String? = null, val turnId: String? = null, val itemId: String? = null) : EngineEvent
    data class MessageCompleted(val text: String, val threadId: String, val turnId: String, val itemId: String, val phase: String? = null) : EngineEvent
    data class UsageChanged(val threadId: String?, val usage: TokenUsage? = null, val limits: List<UsageLimit>? = null) : EngineEvent
    data class GeneratedImage(val threadId: String, val turnId: String, val itemId: String, val base64: String, val savedPath: String?) : EngineEvent
    data class ToolCall(val requestId: String, val name: String, val arguments: JsonObject, val threadId: String? = null, val turnId: String? = null) : EngineEvent
    data class Approval(val requestId: String, val method: String, val details: JsonObject, val threadId: String? = null, val turnId: String? = null) : EngineEvent
    data class Activity(val text: String, val threadId: String? = null, val turnId: String? = null) : EngineEvent
    data class TurnFinished(val status: String, val error: String? = null, val threadId: String? = null, val turnId: String? = null) : EngineEvent
    data class AccountChanged(val status: AccountStatus) : EngineEvent
    data object SkillsChanged : EngineEvent
    data class Failure(val message: String, val threadId: String? = null, val turnId: String? = null) : EngineEvent
}
interface AgentEngine {
    val events: Flow<EngineEvent>
    suspend fun connect()
    suspend fun account(): AccountStatus
    suspend fun refreshUsage() {}
    suspend fun login(): AccountStatus
    suspend fun logout()
    suspend fun models(): List<String>
    /**
     * Return model metadata when the engine can provide it. The default keeps
     * older engine implementations usable while exposing a catalog to newer
     * clients.
     */
    suspend fun modelCatalog(): List<AgentModel> = models().map { AgentModel(id = it) }
    suspend fun skillCatalog(workspace: File, forceReload: Boolean = false): List<AgentSkill> = emptyList()
    suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>): String
    suspend fun startTurn(threadId: String, prompt: String, images: List<File> = emptyList()): String
    /** Start a turn with an optional model-advertised reasoning effort. */
    suspend fun startTurn(threadId: String, prompt: String, images: List<File> = emptyList(), reasoningEffort: String?): String =
        startTurn(threadId, prompt, images)
    /** Start a turn with an explicitly invoked Codex skill input item. */
    suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File> = emptyList(),
        reasoningEffort: String?,
        skill: AgentSkill?,
    ): String = startTurn(threadId, prompt, images, reasoningEffort)
    /** Start a turn with a point-in-time snapshot of the app-owned ADB connection. */
    suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File> = emptyList(),
        reasoningEffort: String?,
        skill: AgentSkill?,
        adbStatus: AdbStatus,
    ): String = startTurn(threadId, prompt, images, reasoningEffort, skill)
    /**
     * Start a turn with a point-in-time snapshot of what device control can do.
     *
     * Preferred over the [AdbStatus] overload: ADB is one backend of several,
     * and a turn that only knows the ADB phase cannot tell the model that
     * accessibility operations are still live.
     */
    suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File> = emptyList(),
        reasoningEffort: String?,
        skill: AgentSkill?,
        capabilities: DeviceCapabilities,
    ): String = startTurn(threadId, prompt, images, reasoningEffort, skill, capabilities.adbStatus)
    /**
     * Start a turn in plan mode when [planModel] is set: the agent agrees a
     * plan before acting. Plan mode needs a model name, so it rides on one.
     */
    suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File> = emptyList(),
        reasoningEffort: String?,
        skill: AgentSkill?,
        capabilities: DeviceCapabilities,
        planModel: String?,
    ): String = startTurn(threadId, prompt, images, reasoningEffort, skill, capabilities)
    /** Summarize the thread's history to free up context. */
    suspend fun compact(threadId: String): Unit = throw UnsupportedOperationException("This engine cannot compact a chat.")
    suspend fun steer(threadId: String, turnId: String, prompt: String)
    suspend fun interrupt(threadId: String, turnId: String)
    suspend fun answerTool(requestId: String, result: ToolResult)
    suspend fun answerApproval(requestId: String, allow: Boolean)
    suspend fun close()
}

/** Lifecycle phase for the experimental Codex Realtime voice session. */
enum class VoicePhase { IDLE, STARTING, LISTENING, SPEAKING, STOPPING, ERROR }

/** State exposed to the voice UI without coupling it to the app-server protocol. */
data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val message: String = "Ready",
    val threadId: String? = null,
) {
    val active: Boolean get() = phase !in setOf(VoicePhase.IDLE, VoicePhase.ERROR)
}

/** Transport used by a realtime voice session. WebRTC is the account-auth path. */
enum class RealtimeTransport { WEBRTC, WEBSOCKET }

/** PCM audio chunk used by the voice contract. The engine owns protocol encoding. */
data class RealtimeAudioChunk(
    val data: ByteArray,
    val sampleRate: Int,
    val numChannels: Int,
    val samplesPerChannel: Int? = null,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(numChannels > 0) { "numChannels must be positive" }
        require(samplesPerChannel == null || samplesPerChannel >= 0) {
            "samplesPerChannel must be non-negative"
        }
    }

    /** Return a copy so callers cannot mutate a chunk while it is being sent or played. */
    fun copyData(): ByteArray = data.copyOf()
}

/** Events emitted by a Realtime voice session. */
sealed interface VoiceEvent {
    data class Started(
        val threadId: String,
        val realtimeSessionId: String? = null,
        val version: String? = null,
    ) : VoiceEvent

    /** Remote SDP answer emitted by app-server for a WebRTC session. */
    data class SdpAnswer(val threadId: String, val sdp: String) : VoiceEvent

    data class TranscriptDelta(val threadId: String, val role: String, val delta: String) : VoiceEvent
    data class TranscriptDone(val threadId: String, val role: String, val text: String) : VoiceEvent
    data class OutputAudio(val threadId: String, val audio: RealtimeAudioChunk) : VoiceEvent
    data class Failure(val message: String, val threadId: String? = null) : VoiceEvent
    data class Closed(val threadId: String, val reason: String? = null) : VoiceEvent
}

/** Small boundary around the experimental app-server Realtime Voice API. */
interface RealtimeVoiceEngine {
    val voiceEvents: Flow<VoiceEvent>
    val voiceState: StateFlow<VoiceState>

    suspend fun startVoice(
        threadId: String,
        model: String? = null,
        transport: RealtimeTransport = RealtimeTransport.WEBSOCKET,
        offerSdp: String? = null,
    )
    suspend fun appendAudio(audio: RealtimeAudioChunk)
    suspend fun appendText(text: String, role: String = "user")
    suspend fun appendSpeech(text: String)
    suspend fun stopVoice()
}

enum class RunPhase { IDLE, STARTING, THINKING, TOOL, CONTROLLING, STOPPING, ERROR }
data class RunState(val phase: RunPhase = RunPhase.IDLE, val sessionId: String? = null, val status: String = "Ready", val controlling: Boolean = false, val approval: EngineEvent.Approval? = null, val toolName: String? = null) {
    val active: Boolean get() = phase !in setOf(RunPhase.IDLE, RunPhase.ERROR)

    /**
     * The tool the run is executing right now, or null when it is doing
     * anything else. Reading it through the phase means a name left behind by
     * an earlier call can never be reported as still running.
     */
    val tool: String? get() = toolName?.takeIf { phase == RunPhase.TOOL || phase == RunPhase.CONTROLLING }
}
enum class OverlayPhase { STARTING, THINKING, RUNNING, CONTROLLING, STOPPING, DONE, ERROR }
data class OverlayState(val phase: OverlayPhase, val detail: String? = null) {
    val label: String
        get() = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { "${phase.title} · $it" } ?: phase.title

    private val OverlayPhase.title: String
        get() = if (this == OverlayPhase.THINKING || this == OverlayPhase.RUNNING) "Working" else name.lowercase().replaceFirstChar { it.uppercase() }
}
interface DeviceToolGateway {
    val definitions: List<ToolDefinition>
    fun beginRun(runId: String, workspace: File)
    fun revoke()
    fun needsControl(name: String): Boolean
    suspend fun invoke(name: String, arguments: JsonObject): ToolResult
    suspend fun cancel()

    /**
     * True when the overlay must be taken off the captured surface for this
     * tool. Only backends that capture the composited screen need it; a
     * gateway that filters its own package out of the tree does not.
     */
    fun hidesOverlayDuringCapture(name: String): Boolean = false

    /** One human line for `device_status`. Null when the gateway has nothing to report. */
    fun statusLine(): String? = null

    /**
     * Tool names this gateway can serve **right now**.
     *
     * Advisory only. It never changes [definitions], which stays static because
     * Codex binds the tool list once per thread; it exists so a per-turn
     * snapshot can say which operations are live instead of collapsing every
     * backend into one availability flag. A gateway that needs no transport
     * says all of them, which is the right answer for a purely local backend.
     */
    fun readyTools(): Set<String> = definitions.map { it.name }.toSet()

    /**
     * True when this gateway can operate the phone right now.
     *
     * Kept apart from [readyTools] because local gateways (knowledge,
     * workflows) are always ready yet cannot touch the screen; counting them
     * would hide "no device backend is live" behind a non-empty list.
     */
    fun deviceBackendLive(): Boolean = readyTools().isNotEmpty()
}

/**
 * A per-turn snapshot of what device control can actually do.
 *
 * Availability is per operation, not one global ADB flag. The accessibility
 * backend serves observation, touch, text and intents with no ADB at all, so an
 * ADB transport that is down must never read as "no device tools" — that is the
 * blanket block issue #44 reported, which stopped `open_intent` on a deep link
 * that never needed ADB in the first place.
 */
data class DeviceCapabilities(
    val adbStatus: AdbStatus = AdbStatus(),
    /** Advertised names at least one live backend can serve. */
    val ready: Set<String> = emptySet(),
    /** Advertised names with no live backend right now. */
    val blocked: Set<String> = emptySet(),
    /** Human backend lines, e.g. `Accessibility: connected | Wireless ADB (optional): ...`. */
    val backendStatus: String? = null,
    /**
     * At least one backend that can operate the phone is live. The default
     * serves callers that only know the ADB phase; [of] reads the real answer.
     */
    val deviceBackendLive: Boolean = ready.isNotEmpty() || adbStatus.phase == ConnectionPhase.CONNECTED,
) {
    /** True when at least one operation can be dispatched. */
    val anyReady: Boolean get() = ready.isNotEmpty()

    companion object {
        /** Read the live picture off a gateway. Never throws: a snapshot is not worth a failed turn. */
        fun of(tools: DeviceToolGateway, adbStatus: AdbStatus): DeviceCapabilities {
            val ready = runCatching { tools.readyTools() }.getOrDefault(emptySet())
            val advertised = runCatching { tools.definitions.map { it.name }.toSet() }
                .getOrDefault(emptySet())
            return DeviceCapabilities(
                adbStatus = adbStatus,
                ready = advertised intersect ready,
                blocked = advertised - ready,
                backendStatus = runCatching { tools.statusLine() }.getOrNull(),
                deviceBackendLive = runCatching { tools.deviceBackendLive() }.getOrDefault(false),
            )
        }
    }
}

/**
 * A backend cannot serve this call at all — the capability is absent, not
 * broken.
 *
 * Throwing this is a promise that **no device side effect has happened yet**,
 * which is what lets [CompositeDeviceToolGateway] retry the call on another
 * backend. A failure after any action has been dispatched must be a failed
 * [ToolResult] or a different exception, never this one.
 */
class ToolNotServiceable(val errorType: String, override val message: String) : Exception(message)
interface ControlOverlay {
    suspend fun show(status: String)
    fun update(status: String)
    fun hide()
    /** Show one explicit lifecycle state. Implementations may reuse an existing card. */
    suspend fun showState(state: OverlayState) { show(state.label) }
    /** Update the lifecycle state without changing which window owns the card. */
    fun updateState(state: OverlayState) { update(state.label) }
    /** Display the terminal state, then release the overlay. */
    fun finish(state: OverlayState) { updateState(state); hide() }
    /** Move the compact control card away from a planned device coordinate. */
    fun avoidTouch(x: Int, y: Int) {}
    /** Temporarily removes the overlay from screenshots/UI hierarchy capture. */
    suspend fun setCaptureHidden(hidden: Boolean) {}
}
