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

import dev.androidagent.core.AgentCoordinator
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.SessionStore
import dev.androidagent.core.VoiceEvent
import dev.androidagent.remote.RoutingAgentEngine
import dev.androidagent.voice.AndroidRealtimeVoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** The line being spoken right now, before it is final. */
data class VoiceTranscript(val text: String = "", val role: String? = null)

/**
 * The one live voice conversation, owned by the app rather than a screen.
 *
 * The chat screen and the assistant panel both start and show it, and the
 * panel runs with no activity at all, so the chat it records into, the typed
 * lines waiting for their echo and the transcript all live here.
 */
class VoiceConversation(
    private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val engine: RoutingAgentEngine,
    private val voice: AndroidRealtimeVoiceController,
    private val tools: DeviceToolGateway,
    private val coordinator: () -> AgentCoordinator,
) {
    private val mutableSessionId = MutableStateFlow<String?>(null)
    private val mutableTranscript = MutableStateFlow(VoiceTranscript())
    private val mutableFailures = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val pendingTypedTexts = java.util.ArrayDeque<String>()

    /** The chat the live conversation records into, or null when none is live. */
    val sessionId: StateFlow<String?> = mutableSessionId.asStateFlow()
    val transcript: StateFlow<VoiceTranscript> = mutableTranscript.asStateFlow()
    /** Voice failures reported by the engine, for whichever surface is showing. */
    val failures: SharedFlow<String> = mutableFailures.asSharedFlow()

    init {
        scope.launch { engine.voiceEvents.collect(::handle) }
    }

    /** Open [sessionId]'s thread and start talking in it. */
    suspend fun begin(sessionId: String, model: String?) {
        check(!coordinator().state.value.active) { "Stop the current agent run before starting voice." }
        val session = sessions.getSession(sessionId) ?: error("Chat no longer exists.")
        engine.connect()
        check(engine.account().signedIn) { "Sign in to Codex in Settings first." }
        val workspace = sessions.workspace(sessionId)
        val threadId = engine.openSession(workspace, session.engineThreadId, model, tools.definitions)
        sessions.setThread(sessionId, threadId)
        mutableSessionId.value = sessionId
        mutableTranscript.value = VoiceTranscript()
        coordinator().beginVoice(sessionId, threadId, workspace)
        try {
            // Realtime selects its own compatible voice model. The normal Codex
            // model remains a thread setting and is not forced into this RPC.
            voice.start(threadId)
        } catch (failure: Throwable) {
            coordinator().endVoice()
            clear()
            throw failure
        }
    }

    suspend fun stop() {
        // Revoke before the remote stop so no new device action can begin while
        // the voice session is ending. Completed side effects are not undone.
        coordinator().endVoice()
        voice.stop()
        clear()
    }

    /** A line the user typed during voice: sent, and recorded once rather than again on its echo. */
    suspend fun type(text: String) {
        val id = mutableSessionId.value ?: error("Voice is not active.")
        synchronized(pendingTypedTexts) { pendingTypedTexts.addLast(text) }
        try {
            voice.appendText(text)
            sessions.append(ChatMessage(UUID.randomUUID().toString(), id, "user", text, System.currentTimeMillis()))
        } catch (failure: Exception) {
            synchronized(pendingTypedTexts) { pendingTypedTexts.removeLastOccurrence(text) }
            throw failure
        }
    }

    /**
     * Context the model should use but not reply to, such as the screen the
     * user summoned Mike over. Not recorded in the chat: the user never said it.
     */
    suspend fun addContext(text: String) {
        voice.appendText(text, role = "developer")
    }

    private suspend fun handle(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.TranscriptDelta -> if (event.threadId == voice.state.value.threadId && mutableSessionId.value != null) {
                mutableTranscript.value = mutableTranscript.value.let { current ->
                    VoiceTranscript(if (current.role == event.role) current.text + event.delta else event.delta, event.role)
                }
            }
            is VoiceEvent.TranscriptDone -> {
                val localSessionId = mutableSessionId.value ?: return
                if (event.threadId != voice.state.value.threadId) return
                val text = event.text.trim()
                val skipTypedUserEcho = event.role.equals("user", ignoreCase = true) && synchronized(pendingTypedTexts) {
                    if (pendingTypedTexts.peekFirst() == text) {
                        pendingTypedTexts.removeFirst()
                        true
                    } else false
                }
                if (text.isNotBlank() && !skipTypedUserEcho) {
                    val role = if (event.role.equals("assistant", ignoreCase = true)) "assistant" else "user"
                    // Saying "yes" / "כן" answers a waiting approval: in voice
                    // mode the card is under the voice screen. Only the user's
                    // own transcript can do this, never the agent's speech.
                    if (role == "user") coordinator().answerApprovalByReply(text, record = false)
                    sessions.append(ChatMessage(UUID.randomUUID().toString(), localSessionId, role, text, System.currentTimeMillis()))
                    val session = sessions.getSession(localSessionId)
                    if (role == "user" && session?.title == "New chat") sessions.rename(localSessionId, text.take(48))
                }
                if (mutableTranscript.value.role == event.role) mutableTranscript.value = VoiceTranscript()
            }
            is VoiceEvent.Failure -> {
                coordinator().endVoice()
                clear()
                mutableFailures.tryEmit(event.message)
            }
            is VoiceEvent.Closed -> {
                coordinator().endVoice()
                clear()
            }
            is VoiceEvent.Started, is VoiceEvent.SdpAnswer, is VoiceEvent.OutputAudio -> Unit
        }
    }

    private fun clear() {
        mutableSessionId.value = null
        synchronized(pendingTypedTexts) { pendingTypedTexts.clear() }
        mutableTranscript.value = VoiceTranscript()
    }
}
