package dev.androidagent.app.ui

import dev.androidagent.core.ChatMessage
import dev.androidagent.core.RunPhase
import dev.androidagent.core.RunState
import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat list's Working line and the composer's main button, read from the
 * same state the screen draws. A phone showed the line under Mike's final
 * answer while the composer already offered Send.
 */
class ChatRunViewTest {
    private val chat = "chat"
    private val answer = ChatMessage("a1", chat, "assistant", "Done. The list is pinned.", 2L)
    private val prompt = ChatMessage("u1", chat, "user", "Pin my list", 1L)

    private fun state(run: RunState, messages: List<ChatMessage> = listOf(prompt, answer), voice: VoiceState = VoiceState(), voiceChat: String? = null) =
        AgentUiState(activeSessionId = chat, runState = run, messages = messages, voiceState = voice, voiceSessionId = voiceChat)

    private fun button(state: AgentUiState) = composerAction(
        active = chatRunView(state).statusLine, hasDraft = false, runActive = state.runState.active,
        voiceActive = voiceHere(state), voiceAllowed = !state.voiceState.active,
    )

    @Test fun aFinishedTurnDrawsNoWorkingLineAndOffersVoice() {
        val done = state(RunState(sessionId = chat, status = "Ready"))
        assertFalse(chatRunView(done).statusLine)
        assertEquals(ComposerAction.VOICE, button(done))
    }

    @Test fun anEmptyReplyLeftStreamingAfterTheTurnIsNotDrawn() {
        val leftover = ChatMessage("a2", chat, "assistant", "", 3L, "streaming")
        val done = state(RunState(sessionId = chat, status = "Ready"), listOf(prompt, answer, leftover))
        val rows = chatListRows(done.messages, chatRunView(done))
        assertEquals(listOf("u1", "a1"), rows.map { it.key })
    }

    @Test fun ownRunDrawsTheLineAndStopTogether() {
        val working = state(RunState(RunPhase.THINKING, chat, "Working"))
        assertTrue(chatRunView(working).statusLine)
        assertEquals(ComposerAction.STOP, button(working))
    }

    @Test fun aChildsWorkIsNotTheChatsOwnWorkingLine() {
        // The source chat's own turn is over; a child still works for it.
        val delegated = state(RunState(RunPhase.THINKING, chat, "Working", delegated = true))
        val view = chatRunView(delegated)
        assertFalse(view.statusLine)
        assertFalse(view.ownRun)
        assertTrue(view.busy)
        assertTrue(button(delegated) != ComposerAction.STOP)
        // And the chat's trailing actions do not read "Working on your phone".
        val tool = ChatMessage("t1", chat, "tool", "tap: ok", 3L)
        val rows = chatListRows(listOf(prompt, tool), view)
        assertFalse((rows.last() as ActionsRow).live)
    }

    @Test fun theLineAndStopAgreeWhateverTheVoiceCallIs() {
        val run = RunState(RunPhase.TOOL, chat, "Tapping")
        val listening = VoiceState(VoicePhase.LISTENING, "Listening", "voice")
        listOf(
            state(run),
            state(run, voice = listening, voiceChat = chat),
            state(run, voice = listening, voiceChat = "other"),
            state(run, voice = listening, voiceChat = null),
            state(run.copy(delegated = true)),
            state(RunState(sessionId = chat, status = "Ready")),
            state(run.copy(sessionId = "other")),
        ).forEach { case ->
            val line = chatRunView(case).statusLine
            assertEquals("line and Stop for $case", line, button(case) == ComposerAction.STOP)
        }
    }
}
