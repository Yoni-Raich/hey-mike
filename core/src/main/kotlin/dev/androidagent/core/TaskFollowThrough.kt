package dev.androidagent.core

import kotlinx.serialization.Serializable

/** Follow-up state never grants authority or changes the frozen execution plan. */
@Serializable
data class TaskCommitment(
    val owner: String,
    val nextCheckAt: Long,
    val deadlineAt: Long? = null,
    val completionEvidence: String? = null,
    val checkedAt: Long? = null,
)

/** One durable, idempotently delivered message per meaningful task occurrence. */
@Serializable
data class TaskBriefing(
    val id: String,
    val occurrence: String,
    val text: String,
    val createdAt: Long,
    val sourceRefs: List<TaskSourceRef>,
    val deliveredAt: Long? = null,
    val deliveryNote: String? = null,
)

object TaskFollowThrough {
    const val DAY = 86_400_000L
    const val HOUR = 3_600_000L
    data class ExecutionProfile(val engine: EngineKind, val model: String?, val effort: String?)
    fun executionProfile(plan: ApprovedTaskPlan?, parent: ChatSession?): ExecutionProfile =
        if(plan?.engine != null) ExecutionProfile(plan.engine, plan.model, plan.reasoningEffort.takeIf { plan.model != null })
        else ExecutionProfile(parent?.engine ?: EngineKind.CODEX, parent?.model, parent?.reasoningEffort.takeIf { parent?.model != null })

    fun accepted(task: MikeTask, owner: String, now: Long, until: Long? = null) = TaskCommitment(
        owner, (until ?: task.deadlineAt?.minus(2 * HOUR)?.coerceAtMost(now + DAY) ?: (now + DAY))
            .coerceAtLeast(now + 60_000), task.deadlineAt)

    /** The key is stable through delivery/bookkeeping writes and process restarts. */
    fun occurrence(task: MikeTask, now: Long): String? {
        if (task.category != TaskCategory.TASK || task.status.closed || task.status == TaskStatus.SNOOZED) return null
        val commitment = task.commitment
        if (commitment?.completionEvidence != null) return "resolved:${SignalInbox.idOf(commitment.completionEvidence)}"
        if (task.status in setOf(TaskStatus.WAITING, TaskStatus.REVIEW, TaskStatus.FAILED, TaskStatus.UNKNOWN))
            return "${task.status.wire}:${task.execution?.requestId}:${SignalInbox.idOf(task.result)}"
        if (commitment != null && now >= commitment.nextCheckAt) return "check:${commitment.nextCheckAt}"
        if (task.status == TaskStatus.PROPOSED) return "proposal:${task.createdAt}:${SignalInbox.idOf(task.deadlineAt, task.why)}"
        return null
    }

    fun needsBrief(task: MikeTask, now: Long): Boolean = occurrence(task, now)?.let { it != task.briefing?.occurrence } == true

    /** Only real owner messages enter the feedback path; generated board chat messages cannot loop. */
    fun captureBoardMessage(role: String, ownerChat: Boolean, executionResult: Boolean): Boolean =
        (ownerChat && role == "user") || (executionResult && role == "assistant")

    fun feedback(signal: TaskSignal): TaskEvent? = signal.takeIf { it.ownerFeedback && it.source == "chat" && it.sourceVerified }?.let {
        TaskEvent("feedback:${it.id}", "owner:${it.sessionId}", 0, "feedback", "user", it.at,
            note = it.text, sourceRefs = listOf(TaskSourcePolicy.of(it)))
    }

    /** Bounded context selected without mutating usage counts or admitting excluded sources. */
    fun context(notes: List<MemoryNote>, query: String, allowed: (MemoryNote) -> Boolean): List<MemoryNote> {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 1 }.toSet()
        val wanted = words(query)
        return notes.filter { it.retiredAt == null && it.supersededBy == null && allowed(it) }
            .map { it to (words("${it.subject} ${it.text} ${it.cue.orEmpty()}").intersect(wanted).size * 4 +
                if (it.kind in setOf("preference", "profile") || it.pinned) 2 else 0) }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<MemoryNote, Int>> { it.second }.thenByDescending { it.first.at })
            .take(8).map { it.first }
    }
}
