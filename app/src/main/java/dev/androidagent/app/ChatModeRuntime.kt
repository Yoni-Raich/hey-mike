package dev.androidagent.app

import dev.androidagent.core.AgentCoordinator
import dev.androidagent.core.ChatModeConsentReply
import dev.androidagent.core.ChatModeController
import dev.androidagent.core.ChatModeExitReply
import dev.androidagent.core.ChatModeObservation
import dev.androidagent.core.ChatModePhase
import dev.androidagent.core.ChatModeStopReason
import dev.androidagent.core.ForegroundChatObserver
import dev.androidagent.core.SendRequest
import dev.androidagent.voice.AndroidRealtimeVoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App owner for the temporary voice-only WhatsApp watch. */
@OptIn(FlowPreview::class)
class ChatModeRuntime(
    private val scope: CoroutineScope,
    val controller: ChatModeController,
    private val observer: ForegroundChatObserver,
    private val voice: AndroidRealtimeVoiceController,
    private val coordinator: () -> AgentCoordinator,
) {
    private val inspection = Mutex()

    init {
        scope.launch {
            observer.changes.debounce(CHANGE_DEBOUNCE_MS).collect {
                if (controller.state.value.active) inspectForegroundChat()
            }
        }
        scope.launch {
            while (true) {
                delay(TIMEOUT_POLL_MS)
                if (controller.expireIfIdle()) {
                    speak("Chat Mode ended after five minutes without activity.")
                }
            }
        }
    }

    suspend fun onMessageSent(request: SendRequest) {
        val threadId = voice.state.value.threadId?.takeIf { voice.state.value.active } ?: return
        delay(SEND_SETTLE_MS)
        if (controller.state.value.active) {
            inspectForegroundChat()
            controller.userActivity()
            return
        }
        if (controller.state.value.phase != ChatModePhase.OFF) return
        val recipient = request.recipient
        if (recipient.isNullOrBlank()) return
        if (!controller.offer(threadId, request.packageName, recipient)) return
        voice.speakAppMessage("Would you like me to enable Chat Mode and watch for replies in this chat?")
    }

    /** Only finalized user speech or typed voice input may call this. */
    fun answerOfferByUser(text: String): Boolean {
        if (controller.state.value.active && ChatModeExitReply.matches(text)) {
            stop(ChatModeStopReason.USER_EXIT)
            return true
        }
        if (controller.state.value.phase != ChatModePhase.OFFERED) return false
        val allow = ChatModeConsentReply.parse(text) ?: return false
        if (!allow) {
            if (!controller.declineOffer()) return false
            scope.launch { speak("Okay, Chat Mode is off.") }
            return true
        }
        controller.userActivity()
        scope.launch {
            val started = controller.startWatching(observer.snapshot())
            if (started) speak("Chat Mode is on for this WhatsApp chat.")
            else if (controller.state.value.phase == ChatModePhase.UNSUPPORTED) {
                speak("Chat Mode cannot read this WhatsApp conversation reliably.")
            }
        }
        return true
    }

    fun userActivity() = controller.userActivity()

    /** Re-read the visible chat immediately before an approved send is committed. */
    suspend fun isCurrentBeforeSend(voiceSessionId: String, contextRevision: Long): Boolean {
        if (!controller.isCurrent(voiceSessionId, contextRevision)) return false
        inspectForegroundChat()
        return controller.isCurrent(voiceSessionId, contextRevision)
    }

    fun stop(reason: ChatModeStopReason = ChatModeStopReason.USER_EXIT, announce: Boolean = true) {
        if (controller.state.value.phase == ChatModePhase.OFF) return
        controller.stop(reason)
        if (announce && voice.state.value.active) scope.launch { speak(stopMessage(reason)) }
    }

    private suspend fun inspectForegroundChat() = inspection.withLock {
        when (val result = controller.observe(observer.snapshot())) {
            ChatModeObservation.NoChange -> Unit
            is ChatModeObservation.Stopped -> speak(stopMessage(result.reason))
            is ChatModeObservation.Incoming -> {
                voice.interruptOutputForExternalChat()
                coordinator().prepareForExternalChatUpdate()
                val current = controller.state.value
                val title = current.chatTitle ?: "WhatsApp"
                voice.announceExternalChatUpdate(title, result.messages)
                controller.consumePending(result.contextRevision)
            }
        }
    }

    private suspend fun speak(text: String) {
        if (voice.state.value.active) runCatching { voice.speakAppMessage(text) }
    }

    private fun stopMessage(reason: ChatModeStopReason): String = when (reason) {
        ChatModeStopReason.APP_LEFT -> "Chat Mode ended because WhatsApp is no longer in front."
        ChatModeStopReason.CHAT_CHANGED -> "Chat Mode ended because the open WhatsApp chat changed."
        ChatModeStopReason.SCREEN_LOCKED -> "Chat Mode ended because the screen was locked."
        ChatModeStopReason.TIMEOUT -> "Chat Mode ended after five minutes without activity."
        ChatModeStopReason.ACCESSIBILITY_LOST -> "Chat Mode ended because screen access is unavailable."
        ChatModeStopReason.TEXT_UNAVAILABLE, ChatModeStopReason.HISTORY_UNSTABLE ->
            "Chat Mode ended because this WhatsApp conversation cannot be read reliably."
        ChatModeStopReason.VOICE_ENDED, ChatModeStopReason.STOPPED, ChatModeStopReason.USER_EXIT -> "Chat Mode is off."
    }

    private companion object {
        const val CHANGE_DEBOUNCE_MS = 250L
        const val SEND_SETTLE_MS = 400L
        const val TIMEOUT_POLL_MS = 1_000L
    }
}
