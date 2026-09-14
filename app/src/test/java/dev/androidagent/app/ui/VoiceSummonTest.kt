package dev.androidagent.app.ui

import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSummonTest {
    @Test fun aPressPutsTheVoiceScreenUpBeforeTheCallExists() {
        val voice = AgentUiState(voiceSummon = "Waking Mike").shownVoice()
        assertTrue(voiceModeShown(voice))
        assertEquals(VoicePhase.STARTING, voice.phase)
        assertEquals("Waking Mike", voice.message)
    }

    @Test fun theRealCallTakesOverOnceItStarts() {
        val call = VoiceState(VoicePhase.LISTENING, "Listening", "thread")
        assertEquals(call, AgentUiState(voiceSummon = "Opening your conversation", voiceState = call).shownVoice())
    }

    @Test fun withoutAPressTheScreenFollowsTheCallAlone() {
        assertFalse(voiceModeShown(AgentUiState().shownVoice()))
    }
}
