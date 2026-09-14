package dev.androidagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import java.util.Locale

enum class ChatMessageDirection { INCOMING, OUTGOING, UNKNOWN }

data class VisibleChatMessage(
    val text: String,
    val direction: ChatMessageDirection,
    val sender: String? = null,
)

data class ForegroundChatSnapshot(
    val packageName: String?,
    val windowId: Int?,
    val chatTitle: String?,
    val messages: List<VisibleChatMessage>,
    val textAvailable: Boolean = true,
)

/** Passive, read-only view of the foreground messaging conversation. */
interface ForegroundChatObserver {
    /** Content-free ticks. Call [snapshot] to read the explicitly watched screen. */
    val changes: Flow<Unit>
    suspend fun snapshot(): ForegroundChatSnapshot
}

enum class ChatModePhase { OFF, OFFERED, WATCHING, UPDATE_PENDING, UNSUPPORTED }

enum class ChatModeStopReason {
    USER_EXIT,
    VOICE_ENDED,
    STOPPED,
    APP_LEFT,
    CHAT_CHANGED,
    SCREEN_LOCKED,
    TIMEOUT,
    ACCESSIBILITY_LOST,
    TEXT_UNAVAILABLE,
    HISTORY_UNSTABLE,
}

data class ChatModeState(
    val phase: ChatModePhase = ChatModePhase.OFF,
    val voiceSessionId: String? = null,
    val packageName: String? = null,
    val chatTitle: String? = null,
    val contextRevision: Long = 0,
    val pendingMessages: List<VisibleChatMessage> = emptyList(),
    val stopReason: ChatModeStopReason? = null,
    val lastActivityAtMs: Long = 0,
) {
    val active: Boolean get() = phase == ChatModePhase.WATCHING || phase == ChatModePhase.UPDATE_PENDING
    val engaged: Boolean get() = phase == ChatModePhase.OFFERED || active
}

sealed interface ChatModeObservation {
    data object NoChange : ChatModeObservation
    data class Incoming(val contextRevision: Long, val messages: List<VisibleChatMessage>) : ChatModeObservation
    data class Stopped(val reason: ChatModeStopReason) : ChatModeObservation
}

/**
 * Pure, in-memory state for one explicitly approved foreground chat watch.
 * Screen content is never persisted here. Android observation and voice I/O
 * stay behind their module boundaries.
 */
class ChatModeController(
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(ChatModeState())
    val state: StateFlow<ChatModeState> = mutable.asStateFlow()

    private var generation = 0L
    private var binding: Binding? = null
    private var baseline: List<VisibleChatMessage> = emptyList()

    @Synchronized
    fun offer(voiceSessionId: String, packageName: String, chatTitle: String): Boolean {
        if (mutable.value.phase != ChatModePhase.OFF) return false
        if (packageName !in WHATSAPP_PACKAGES || chatTitle.isBlank()) return false
        generation++
        binding = Binding(
            generation = generation,
            voiceSessionId = voiceSessionId,
            packageName = packageName,
            windowId = null,
            normalizedTitle = normalizeTitle(chatTitle),
        )
        baseline = emptyList()
        mutable.value = ChatModeState(
            phase = ChatModePhase.OFFERED,
            voiceSessionId = voiceSessionId,
            packageName = packageName,
            chatTitle = chatTitle,
            lastActivityAtMs = nowMs(),
        )
        return true
    }

    @Synchronized
    fun declineOffer(): Boolean {
        val current = mutable.value
        if (current.phase != ChatModePhase.OFFERED || binding == null) return false
        clear(ChatModeStopReason.USER_EXIT)
        return true
    }

    /** Capture the first message baseline only after the user gave consent. */
    @Synchronized
    fun startWatching(snapshot: ForegroundChatSnapshot): Boolean {
        val currentBinding = binding ?: return false
        val current = mutable.value
        if (current.phase != ChatModePhase.OFFERED) return false
        if (snapshot.packageName == null && !snapshot.textAvailable) {
            clear(ChatModeStopReason.ACCESSIBILITY_LOST)
            return false
        }
        if (snapshot.packageName != currentBinding.packageName) {
            clear(ChatModeStopReason.APP_LEFT)
            return false
        }
        if (snapshot.chatTitle == null || normalizeTitle(snapshot.chatTitle) != currentBinding.normalizedTitle) {
            clear(ChatModeStopReason.CHAT_CHANGED)
            return false
        }
        if (!validSnapshot(snapshot)) {
            clear(ChatModeStopReason.TEXT_UNAVAILABLE)
            return false
        }
        binding = currentBinding.copy(windowId = snapshot.windowId)
        baseline = snapshot.messages
        mutable.value = current.copy(
            phase = ChatModePhase.WATCHING,
            chatTitle = snapshot.chatTitle,
            lastActivityAtMs = nowMs(),
        )
        return true
    }

    @Synchronized
    fun observe(snapshot: ForegroundChatSnapshot): ChatModeObservation {
        val currentBinding = binding ?: return ChatModeObservation.NoChange
        val current = mutable.value
        if (!current.active) return ChatModeObservation.NoChange
        if (snapshot.packageName == null && !snapshot.textAvailable) {
            return stopWith(ChatModeStopReason.ACCESSIBILITY_LOST)
        }
        if (snapshot.packageName != currentBinding.packageName) return stopWith(ChatModeStopReason.APP_LEFT)
        if (snapshot.chatTitle == null || normalizeTitle(snapshot.chatTitle) != currentBinding.normalizedTitle) {
            return stopWith(ChatModeStopReason.CHAT_CHANGED)
        }
        if (currentBinding.windowId != null && snapshot.windowId != null && snapshot.windowId != currentBinding.windowId) {
            return stopWith(ChatModeStopReason.CHAT_CHANGED)
        }
        if (!snapshot.textAvailable) return stopWith(ChatModeStopReason.TEXT_UNAVAILABLE)
        val overlap = suffixPrefixOverlap(baseline, snapshot.messages)
        if (baseline.isNotEmpty() && snapshot.messages.isNotEmpty() && overlap == 0) {
            return stopWith(ChatModeStopReason.HISTORY_UNSTABLE)
        }
        val newMessages = snapshot.messages.drop(overlap)
        baseline = snapshot.messages
        if (newMessages.isEmpty()) return ChatModeObservation.NoChange
        if (newMessages.any { it.direction == ChatMessageDirection.UNKNOWN }) {
            return stopWith(ChatModeStopReason.TEXT_UNAVAILABLE)
        }

        val incoming = newMessages.filter { it.direction == ChatMessageDirection.INCOMING }
        if (incoming.isEmpty()) return ChatModeObservation.NoChange
        val revision = current.contextRevision + 1
        val pending = current.pendingMessages + incoming
        mutable.value = current.copy(
            phase = ChatModePhase.UPDATE_PENDING,
            contextRevision = revision,
            pendingMessages = pending,
            lastActivityAtMs = nowMs(),
        )
        return ChatModeObservation.Incoming(revision, incoming)
    }

    @Synchronized
    fun consumePending(contextRevision: Long): List<VisibleChatMessage> {
        val current = mutable.value
        if (current.phase != ChatModePhase.UPDATE_PENDING || current.contextRevision != contextRevision) return emptyList()
        val result = current.pendingMessages
        mutable.value = current.copy(phase = ChatModePhase.WATCHING, pendingMessages = emptyList())
        return result
    }

    @Synchronized
    fun userActivity() {
        val current = mutable.value
        if (current.phase != ChatModePhase.OFF) mutable.value = current.copy(lastActivityAtMs = nowMs())
    }

    @Synchronized
    fun expireIfIdle(): Boolean {
        val current = mutable.value
        if (!current.engaged || nowMs() - current.lastActivityAtMs < idleTimeoutMs) return false
        clear(ChatModeStopReason.TIMEOUT)
        return true
    }

    @Synchronized
    fun stop(reason: ChatModeStopReason) {
        clear(reason)
    }

    @Synchronized
    fun isCurrent(voiceSessionId: String, contextRevision: Long): Boolean {
        val currentBinding = binding ?: return false
        val current = mutable.value
        return current.active && currentBinding.voiceSessionId == voiceSessionId && current.contextRevision == contextRevision
    }

    @Synchronized
    fun canDispatchAction(): Boolean = mutable.value.phase != ChatModePhase.UPDATE_PENDING

    /** ADB-only writes can bypass the visible messaging send gate, so they are unavailable while watching. */
    @Synchronized
    fun canDispatchTool(name: String): Boolean {
        val current = mutable.value
        if (current.phase == ChatModePhase.UPDATE_PENDING) return false
        return !current.active || name !in UNSAFE_DIRECT_TOOLS
    }

    private fun stopWith(reason: ChatModeStopReason): ChatModeObservation.Stopped {
        clear(reason)
        return ChatModeObservation.Stopped(reason)
    }

    private fun clear(reason: ChatModeStopReason) {
        val previous = mutable.value
        generation++
        binding = null
        baseline = emptyList()
        mutable.value = ChatModeState(
            phase = if (reason in setOf(ChatModeStopReason.TEXT_UNAVAILABLE, ChatModeStopReason.HISTORY_UNSTABLE)) {
                ChatModePhase.UNSUPPORTED
            } else {
                ChatModePhase.OFF
            },
            contextRevision = previous.contextRevision,
            stopReason = reason,
            lastActivityAtMs = nowMs(),
        )
    }

    private data class Binding(
        val generation: Long,
        val voiceSessionId: String,
        val packageName: String,
        val windowId: Int?,
        val normalizedTitle: String,
    )

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1_000L
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val UNSAFE_DIRECT_TOOLS = setOf("shell", "push_file", "install_apk")

        fun normalizeTitle(value: String): String = value
            .filterNot { it in "‎‏‪‫‬‭‮⁦⁧⁨⁩" }
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

        internal fun suffixPrefixOverlap(
            previous: List<VisibleChatMessage>,
            current: List<VisibleChatMessage>,
        ): Int {
            val limit = minOf(previous.size, current.size)
            for (size in limit downTo 1) {
                if (previous.takeLast(size) == current.take(size)) return size
            }
            return 0
        }

        private fun validSnapshot(snapshot: ForegroundChatSnapshot): Boolean =
            snapshot.textAvailable && !snapshot.chatTitle.isNullOrBlank() &&
                snapshot.messages.none { it.text.isBlank() || it.direction == ChatMessageDirection.UNKNOWN }
    }
}

/** Consent is narrower than send approval: only an unambiguous yes or no. */
object ChatModeConsentReply {
    private val yes = setOf("yes", "yeah", "yep", "ok", "okay", "sure", "כן", "בסדר", "אוקיי", "סבבה")
    private val no = setOf("no", "nope", "לא", "בטל", "עזוב")

    fun parse(value: String): Boolean? {
        val normalized = value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return when (normalized) {
            in yes -> true
            in no -> false
            else -> null
        }
    }
}

/** Local exit commands are narrow so ordinary discussion about Chat Mode is not consumed. */
object ChatModeExitReply {
    private val commands = setOf(
        "exit chat mode",
        "stop chat mode",
        "turn off chat mode",
        "disable chat mode",
        "צא ממצב צ׳אט",
        "צא ממצב צאט",
        "כבה מצב צ׳אט",
        "כבה מצב צאט",
    )

    fun matches(value: String): Boolean = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}׳']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ") in commands
}
