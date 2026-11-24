package dev.androidagent.core

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class AgentTaskStatusTest {
    private val ended = RunState(sessionId = "child", runId = "run")

    @Test fun wireNamesAreTheContractParentAgentsAndStoredReceiptsRead() {
        assertEquals(
            listOf("queued", "starting", "running", "waiting_for_user", "stopping", "completed", "failed", "cancelled", "unknown"),
            AgentTaskStatus.entries.map { it.wire })
        AgentTaskStatus.entries.forEach { assertEquals(it, AgentTaskStatus.of(it.wire)) }
        assertEquals(
            setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED, AgentTaskStatus.UNKNOWN),
            AgentTaskStatus.entries.filter { it.terminal }.toSet())
    }

    @Test fun olderAndForeignWordsNeverLookFinished() {
        assertEquals(AgentTaskStatus.STOPPING, AgentTaskStatus.of("cancelling"))
        assertEquals(AgentTaskStatus.RUNNING, AgentTaskStatus.of("a_status_from_a_newer_build"))
        assertFalse(AgentTaskStatus.of("a_status_from_a_newer_build").terminal)
    }

    @Test fun aRunsOutcomeNeverDependsOnTheWordsShownToTheUser() {
        assertNull(RunState().outcome) // A chat that has not run.
        assertNull(ended.copy(phase = RunPhase.THINKING).outcome)
        assertEquals(RunOutcome.COMPLETED, ended.outcome)
        assertEquals(RunOutcome.COMPLETED, ended.copy(status = "Stopped").outcome) // Text alone decides nothing.
        assertEquals(RunOutcome.COMPLETED, ended.copy(status = "Voice ended").outcome)
        assertEquals(RunOutcome.STOPPED, ended.copy(stopped = true, status = "Reworded").outcome)
        assertEquals(RunOutcome.FAILED, ended.copy(phase = RunPhase.ERROR, status = "No network").outcome)
        assertEquals(RunOutcome.FAILED, RunState(phase = RunPhase.ERROR).outcome)
    }

    @Test fun anUnconfirmedResultOutranksEveryOtherEnding() {
        assertEquals(RunOutcome.UNKNOWN, ended.copy(stopped = true, stopConfirmed = false).outcome)
        assertEquals(RunOutcome.UNKNOWN, ended.copy(phase = RunPhase.ERROR, outcomeUnknown = true).outcome)
        assertEquals(RunOutcome.UNKNOWN, ended.copy(outcomeUnknown = true).outcome)
        assertEquals(RunOutcome.STOPPED, ended.copy(stopped = true, stopConfirmed = true).outcome)
    }

    @Test fun oneRuleTurnsAChildsRunIntoItsTaskStatus() {
        val live = ended.copy(phase = RunPhase.TOOL)
        val question = UserQuestion("q", "Which file?")
        val approval = EngineEvent.Approval("a", "approval", buildJsonObject {})
        assertEquals(AgentTaskStatus.RUNNING, AgentTaskStatus.of(live))
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, AgentTaskStatus.of(live.copy(question = question)))
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, AgentTaskStatus.of(live.copy(approval = approval)))
        // Stop closes the gate: a request nobody can answer is not "waiting".
        assertEquals(AgentTaskStatus.STOPPING, AgentTaskStatus.of(live.copy(phase = RunPhase.STOPPING, question = question)))
        assertEquals(AgentTaskStatus.FAILED, AgentTaskStatus.of(ended.copy(phase = RunPhase.ERROR, approval = approval)))
        assertEquals(AgentTaskStatus.COMPLETED, AgentTaskStatus.of(ended))
        assertEquals(AgentTaskStatus.CANCELLED, AgentTaskStatus.of(ended.copy(stopped = true)))
        assertEquals(AgentTaskStatus.UNKNOWN, AgentTaskStatus.of(ended.copy(outcomeUnknown = true)))
        assertEquals(AgentTaskStatus.COMPLETED, AgentTaskStatus.ended(null))
    }

    @Test fun onlyGatesAndUnhappyEndingsNeedAttention() {
        assertEquals(
            setOf(AgentTaskStatus.WAITING_FOR_USER, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED, AgentTaskStatus.UNKNOWN),
            AgentTaskStatus.entries.filter { it.needsAttention }.toSet())
    }

    @Test fun aTaskKeepsItsStoredWordAndReadsItTyped() {
        val task = SessionAgentTask("t", "r", "parent", "Title", "Do it", EngineKind.CODEX, createdAt = 1)
        assertEquals("queued", task.status)
        assertEquals(AgentTaskStatus.QUEUED, task.state)
        val stopped = task.at(AgentTaskStatus.CANCELLED, "Stopped before dispatch")
        assertEquals("cancelled", stopped.status)
        assertEquals("Stopped before dispatch", stopped.progress)
        assertTrue(stopped.terminal)
        assertEquals("cancelled", stopped.toJson()["status"].toString().trim('"'))
    }
}
