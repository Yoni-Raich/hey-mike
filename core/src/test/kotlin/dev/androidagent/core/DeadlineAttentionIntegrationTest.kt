package dev.androidagent.core

import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeadlineAttentionIntegrationTest {
    private var clock = 10_000_000L
    private val deadline = clock + TaskFollowThrough.HOUR
    private fun card(id: String, deadlineAt: Long? = deadline) = MikeTask(
        id, id, "why", "plan", originKind = "chat", topicKey = id,
        createdAt = clock - 100, updatedAt = clock - 10, status = TaskStatus.MINE,
        commitment = TaskCommitment("user", clock + 3 * TaskFollowThrough.HOUR), deadlineAt = deadlineAt)
    private fun snapshot(tasks: List<MikeTask>, allowed: (MikeTask) -> Boolean = { true }): SharedSituation {
        val projection = TaskSituation.snapshot(tasks, clock, ZoneId.of("UTC"), false, allowed)
        return SharedSituation(lastRefreshAt = clock, commitments = projection.commitments)
    }

    @Test fun approachingDeadlineIsPrioritizedAndRequiresCurrentAllowedState() {
        val near = card("near")
        val routine = card("routine", null).copy(commitment = TaskCommitment("user", clock - 1))
        val hidden = card("hidden")
        val expired = card("expired").copy(status = TaskStatus.PROPOSED, usefulUntil = clock)
        val all = listOf(routine, near, hidden, expired)
        val state = snapshot(all) { it.id != "hidden" }
        val plan = SituationAttention.plan(all.filter { it.id != "hidden" }, state, clock, limit = 1)
        assertEquals(listOf("near"), plan.taskIds)
        assertTrue(plan.reason.contains("approaching_deadline"))
        assertFalse(SituationAttention.deadlineEligible(hidden, state, clock))
        assertFalse(SituationAttention.deadlineEligible(expired, state, clock))
        assertFalse(SituationAttention.deadlineEligible(near, state.copy(lastRefreshAt = clock - SituationAttention.STATE_MAX_AGE - 1), clock))
        assertFalse(SituationAttention.deadlineEligible(near, state.copy(commitments = state.commitments.map { it.copy(expiresAt = clock) }), clock))
        assertFalse(SituationAttention.deadlineEligible(near.copy(updatedAt = clock), state, clock))
        assertFalse(SituationAttention.deadlineEligible(near.copy(status = TaskStatus.DONE), state, clock))
    }

    @Test fun quietDefersWithAuditAndDeadlineReceiptSurvivesRestart() = runTest {
        val item = card("deadline")
        val store = InMemoryTaskBoardStore().apply { saveTask(item) }
        val stateFile = Files.createTempDirectory("attention-test").resolve("situation.json").toFile()
        val situationStore = SharedSituationStore(stateFile)
        situationStore.refresh(listOf(item), clock, ZoneId.of("UTC"), false, false) { true }
        situationStore.correct("availability", "quiet", clock, clock + 60_000)
        fun currentState() = situationStore.snapshot(clock, ZoneId.of("UTC"), setOf(item.id), false)
        val quiet = SituationAttention.plan(listOf(item), currentState(), clock)
        assertTrue(quiet.taskIds.isEmpty())
        assertEquals(listOf(item.id), quiet.deferredTaskIds)
        assertEquals(clock + 60_000, quiet.nextCheck)
        situationStore.recordDecision(AttentionDecision("defer", quiet.reason, quiet.deferredTaskIds, clock, quiet.nextCheck))
        assertEquals(quiet.nextCheck, SharedSituationStore(stateFile).snapshot(clock, ZoneId.of("UTC"), setOf(item.id), false).attention.single().nextCheck)
        clock += 60_000
        val board = TaskBoard(store, now = { clock }).apply { load() }
        val ready = SituationAttention.plan(board.tasks.value, currentState(), clock)
        assertEquals(listOf(item.id), ready.taskIds)
        val brief = board.prepareBriefing(item.id, "This task's deadline is approaching.", emptyList()).briefing!!
        var appends = 0
        assertTrue(board.appendBriefingIfCurrent(item.id, brief.id,
            { task, _ -> SituationAttention.deadlineEligible(task, currentState(), clock) }) { appends++ })
        assertEquals(1, appends)
        board.delivered(item.id, brief.id, "saved", "posted")
        val restarted = TaskBoard(store, now = { clock }).apply { load() }
        assertTrue(SituationAttention.plan(restarted.tasks.value, currentState(), clock).taskIds.isEmpty())
        assertEquals("deadline:$deadline", restarted.task(item.id)!!.lastDeadlineBriefingOccurrence)
    }

    @Test fun silenceReportsNextDeadlineWindowAndDoesNotInferTravel() {
        val later = card("later", clock + 3 * TaskFollowThrough.HOUR)
        val plan = SituationAttention.plan(listOf(later), snapshot(listOf(later)), clock)
        assertTrue(plan.taskIds.isEmpty())
        assertEquals(clock + TaskFollowThrough.HOUR, plan.nextCheck)
        assertTrue(plan.reason.contains("no departure inferred"))
    }

    @Test fun deliveredCheckDoesNotPermanentlyMaskLaterDeadline() = runTest {
        val item = card("deadline", clock + 3 * TaskFollowThrough.HOUR).copy(commitment = TaskCommitment("user", clock))
        val store = InMemoryTaskBoardStore().apply { saveTask(item) }
        val board = TaskBoard(store, now = { clock }).apply { load() }
        val check = board.prepareBriefing(item.id, "Time to check", emptyList()).briefing!!
        assertTrue(check.occurrence.startsWith("check:"))
        // Preparing records the receipt; it must not invalidate the unsent check.
        assertEquals(check.occurrence, TaskFollowThrough.occurrence(board.task(item.id)!!, clock))
        board.delivered(item.id, check.id, "saved", "posted")
        clock += TaskFollowThrough.HOUR
        val restarted = TaskBoard(store, now = { clock }).apply { load() }
        val state = snapshot(restarted.tasks.value)
        assertEquals(listOf(item.id), SituationAttention.plan(restarted.tasks.value, state, clock).taskIds)
        assertEquals("deadline:${item.deadlineAt}", TaskFollowThrough.occurrence(restarted.task(item.id)!!, clock))
        val brief = restarted.prepareBriefing(item.id, "Deadline approaching", emptyList()).briefing!!
        assertNotEquals(check.id, brief.id)
    }
}
