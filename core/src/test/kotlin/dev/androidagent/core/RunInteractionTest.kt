package dev.androidagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class RunInteractionTest {
    private val approval = EngineEvent.Approval("a", "approval", buildJsonObject {})
    private val question = UserQuestion("q", "Which file?")
    private val panel = AgentPanel("panel-1", "Settings", emptyList(), emptyList())

    @Test fun stopAndTerminalStatesNeverExposeRetainedGates() {
        for (phase in listOf(RunPhase.IDLE, RunPhase.ERROR, RunPhase.STOPPING)) {
            val state = RunState(phase = phase, approval = approval, question = question, panel = panel)
            assertEquals(phase.name, RunInteraction.NONE, state.interaction)
            assertFalse(state.waitingForUser)
            assertNull(state.pendingApproval)
            assertNull(state.pendingQuestion)
            assertNull(state.pendingPanel)
        }
    }

    @Test fun everyLivePhaseKeepsItsGateWithoutChangingTheExecutionPhase() {
        for (phase in listOf(RunPhase.STARTING, RunPhase.THINKING, RunPhase.TOOL, RunPhase.CONTROLLING)) {
            val state = RunState(phase = phase)
            assertEquals(RunInteraction.NONE, state.interaction)
            val paneled = state.copy(panel = panel)
            assertEquals(RunInteraction.PANEL, paneled.interaction)
            assertSame(panel, paneled.pendingPanel)
            assertTrue(paneled.waitingForUser)
            val questioned = paneled.copy(question = question)
            assertEquals(RunInteraction.QUESTION, questioned.interaction)
            assertSame(question, questioned.pendingQuestion)
            assertNull(questioned.pendingPanel)
            val gated = questioned.copy(approval = approval)
            assertEquals(RunInteraction.APPROVAL, gated.interaction)
            assertSame(approval, gated.pendingApproval)
            assertNull(gated.pendingQuestion)
            assertNull(gated.pendingPanel)
            assertTrue(gated.waitingForUser)
            assertEquals(phase, gated.phase)
        }
    }
}
