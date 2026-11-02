package dev.androidagent.core

import org.junit.Assert.*
import org.junit.Test

class SituationAttentionTest {
    private val now = 1_000_000L
    private fun task(id: String, due: Long) = MikeTask(id, id, "why", "plan", originKind = "chat", topicKey = id,
        createdAt = 1, updatedAt = 2, status = TaskStatus.WAITING, commitment = TaskCommitment("user", due))

    @Test fun dueChecksOutrankRoutineAndFreshBoardStateBreaksTies() {
        val a = task("a", now); val b = task("b", now)
        val routine = task("routine", now + 1)
        val situation = SharedSituation(commitments = listOf(SituationCommitment("b", "b", "waiting", observedAt = 2, nextCheckAt = now)))
        val plan = SituationAttention.plan(listOf(a, b, routine), situation, now)
        assertEquals(listOf("b", "a"), plan.taskIds)
        assertTrue(plan.reason.startsWith("due_followup"))
        val stale = situation.copy(commitments = situation.commitments.map { it.copy(expiresAt = now) })
        assertEquals(listOf("a", "b"), SituationAttention.plan(listOf(a, b, routine), stale, now).taskIds)
    }

    @Test fun unknownStaleAndConflictingTravelNeverCreateDepartureReminder() {
        val situation = SharedSituation(unknown = listOf("current_location", "eta"), conflicts = listOf("travel_mode"),
            claims = listOf(SituationClaim("eta", "5 min", "old", 1, 2, "stale")))
        val plan = SituationAttention.plan(listOf(task("routine", now + 60_000)), situation, now)
        assertTrue(plan.taskIds.isEmpty())
        assertTrue(plan.reason.startsWith("no_due_followup"))
        assertTrue(plan.reason.contains("no departure inferred"))
    }

    @Test fun freshDeadlineChangesOrderingButExpiredDeadlineDoesNot() {
        val a = task("a", now); val b = task("b", now)
        val state = SharedSituation(lastRefreshAt = now, commitments = listOf(SituationCommitment("b", "b", "waiting", observedAt = 2,
            nextCheckAt = now, deadlineAt = now - 10, expiresAt = now + 1)))
        assertEquals(listOf("b", "a"), SituationAttention.plan(listOf(a, b), state, now).taskIds)
        assertEquals(listOf("a", "b"), SituationAttention.plan(listOf(a, b), state, now + 1).taskIds)
    }

    @Test fun explicitQuietWindowDefersButStaleOrConflictingAvailabilityDoesNot() {
        val due = task("due", now)
        val quiet = SituationClaim("availability", "quiet", "user", now - 1, now + 100, "user_supplied")
        val state = SharedSituation(claims = listOf(quiet))
        val deferred = SituationAttention.plan(listOf(due), state, now)
        assertTrue(deferred.taskIds.isEmpty())
        assertEquals(now + 100, deferred.nextCheck)
        assertEquals(listOf("due"), SituationAttention.plan(listOf(due), state, now + 100).taskIds)
        assertEquals(listOf("due"), SituationAttention.plan(listOf(due), state.copy(conflicts = listOf("availability")), now).taskIds)
    }
}
