package dev.androidagent.core

/** Local attention decisions never infer a departure time from travel claims. */
object SituationAttention {
    const val STATE_MAX_AGE = 5 * 60_000L
    data class Plan(val taskIds: List<String>, val reason: String, val nextCheck: Long? = null,
        val deferredTaskIds: List<String> = emptyList())

    fun quietUntil(situation: SharedSituation, now: Long): Long? = situation.claims.singleOrNull {
        it.field == "availability" && it.source == "user" && it.value == "quiet" && it.confidence != "stale" &&
            it.observedAt <= now && it.expiresAt != null && it.expiresAt > now && "availability" !in situation.conflicts
    }?.expiresAt

    fun deadlineEligible(task: MikeTask, situation: SharedSituation, now: Long): Boolean {
        if (situation.lastRefreshAt <= 0 || situation.lastRefreshAt > now || now - situation.lastRefreshAt > STATE_MAX_AGE) return false
        if (TaskFollowThrough.occurrence(task, now)?.startsWith("deadline:") != true) return false
        return freshDeadline(task, situation, now) != null
    }

    private fun freshDeadline(task: MikeTask, situation: SharedSituation, now: Long): Long? {
        if (situation.lastRefreshAt <= 0 || situation.lastRefreshAt > now || now - situation.lastRefreshAt > STATE_MAX_AGE) return null
        return situation.commitments.singleOrNull { it.taskId == task.id }?.takeIf { claim ->
            claim.source == "task_board" && claim.status == task.status.wire && claim.observedAt >= task.updatedAt &&
                claim.observedAt <= now && (claim.expiresAt == null || claim.expiresAt > now) &&
                claim.deadlineAt == task.deadlineAt
        }?.deadlineAt
    }

    fun plan(tasks: Collection<MikeTask>, situation: SharedSituation, now: Long, limit: Int = 10): Plan {
        val current = situation.commitments.associateBy { it.taskId }
        fun freshDeadline(task: MikeTask): Long? = freshDeadline(task, situation, now)
        val due = tasks.filter { task ->
            val occurrence = TaskFollowThrough.occurrence(task, now)
            TaskFollowThrough.needsBrief(task, now) && (occurrence?.startsWith("check:") == true ||
                (occurrence?.startsWith("deadline:") == true && freshDeadline(task) != null))
        }.sortedWith(
            compareBy<MikeTask> { task -> if (freshDeadline(task)?.let { it >= now && it - now <= TaskFollowThrough.DEADLINE_WINDOW } == true) 0 else 1 }
                .thenBy { task -> minOf(task.commitment?.nextCheckAt ?: Long.MAX_VALUE,
                freshDeadline(task) ?: Long.MAX_VALUE) }
                .thenByDescending { task ->
                    current[task.id]?.let { claim -> claim.source == "task_board" &&
                        claim.observedAt >= task.updatedAt && claim.observedAt <= now &&
                        (claim.expiresAt == null || claim.expiresAt > now) &&
                        claim.nextCheckAt == task.commitment?.nextCheckAt } == true
                }.thenBy { it.id }
        ).take(limit.coerceAtLeast(0))
        val quiet = quietUntil(situation, now)
        if (due.isNotEmpty() && quiet != null) return Plan(emptyList(), "quiet window; follow-up or approaching deadline deferred", quiet, due.map { it.id })
        val next = tasks.flatMap { task -> listOfNotNull(task.commitment?.nextCheckAt,
            freshDeadline(task)?.minus(TaskFollowThrough.DEADLINE_WINDOW)) }.filter { it > now }.minOrNull()
        return if (due.isEmpty()) Plan(emptyList(), "no_due_followup; no approaching deadline; no departure inferred from travel or location", next)
        else Plan(due.map { it.id }, if (due.any { TaskFollowThrough.occurrence(it, now)?.startsWith("deadline:") == true })
            "approaching_deadline; no departure inferred from travel or location" else "due_followup; no departure inferred from travel or location", next)
    }
}
