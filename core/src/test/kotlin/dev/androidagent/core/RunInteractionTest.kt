package dev.androidagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class RunInteractionTest {
    private val approval = EngineEvent.Approval("a", "approval", buildJsonObject {})
    private val question = UserQuestion("q", "Which file?")

    @Test fun stopAndTerminalStatesNeverExposeRetainedGates() {
        for (phase in listOf(RunPhase.IDLE, RunPhase.ERROR, RunPhase.STOPPING)) {
            val state = RunState(phase = phase, approval = approval, question = question)
            assertEquals(phase.name, RunInteraction.NONE, state.interaction)
            assertFalse(state.waitingForUser)
            assertNull(state.pendingApproval)
            assertNull(state.pendingQuestion)
        }
    }

    @Test fun everyLivePhaseKeepsItsGateWithoutChangingTheExecutionPhase() {
        for (phase in listOf(RunPhase.STARTING, RunPhase.THINKING, RunPhase.TOOL, RunPhase.CONTROLLING)) {
            val state = RunState(phase = phase)
            assertEquals(RunInteraction.NONE, state.interaction)
            assertEquals(RunInteraction.QUESTION, state.copy(question = question).interaction)
            val gated = state.copy(question = question, approval = approval)
            assertEquals(RunInteraction.APPROVAL, gated.interaction)
            assertSame(approval, gated.pendingApproval)
            assertNull(gated.pendingQuestion)
            assertTrue(gated.waitingForUser)
            assertEquals(phase, gated.phase)
        }
    }
}
