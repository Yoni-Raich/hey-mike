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
    /** Incremented only by an explicit new schedule; persisted with the card. */
    val checkRevision: Long = 0,
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
    val chatSavedAt: Long? = null,
    val notificationAttemptedAt: Long? = null,
    /** posted | disabled | blocked | unknown | cancelled; never means opened or useful. */
    val notificationState: String? = null,
    /** Freeze the visible chat text so a retry keeps the same message content. */
    val title: String? = null,
)

object TaskFollowThrough {
    // Common conversation words must not make an unrelated project look relevant.
    private val stopWords = ("the a an and or of to in on at for from with is are was were be been it its this that these those " +
        "i me my we our you your he she they them have has had do does did can could should would about all any " +
        "what why how when where who not now already please tell know access data conversation " +
        "אני אתה את אתם אנחנו הוא היא הם הן זה זו זאת הזה הזו אלו אלה של שלי שלך שלו שלה שלנו את על עם גם " +
        "לא כן יש אין היה הייתה יהיה להיות לי לך לו לה לנו לכם מהם מה למה איך מתי איפה מי כל כבר עכשיו " +
        "עוד רק אז אבל כי אם או עד כמו צריך אפשר אמור באמת בכלל בבקשה לדעת גישה נתונים הנתונים נתוני שיחה השיחה האלו להיות").split(' ').toSet()

    fun contextWords(text: String): Set<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 1 && it !in stopWords }.toSet()

    const val DAY = 86_400_000L
    const val HOUR = 3_600_000L
    const val DEADLINE_WINDOW = 2 * HOUR
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
        if (commitment != null && now >= commitment.nextCheckAt) {
            val suffix = if (commitment.checkRevision == 0L) "" else ":r${commitment.checkRevision}"
            val check = "check:${commitment.nextCheckAt}$suffix"
            val legacy = task.briefing?.takeUnless { it.notificationState == "cancelled" && it.chatSavedAt == null }?.occurrence
            val pending = task.briefing?.let { it.occurrence == check && it.deliveredAt == null } == true
            if (pending || check != (task.lastCheckBriefingOccurrence ?: legacy)) return check
        }
        if (!(task.status.undecided && task.usefulUntil != null && task.usefulUntil <= now)) {
            val deadline = task.deadlineAt
            if (deadline != null && now <= deadline && deadline - now <= DEADLINE_WINDOW) return "deadline:$deadline"
        }
        if (commitment != null && now >= commitment.nextCheckAt) {
            val suffix = if (commitment.checkRevision == 0L) "" else ":r${commitment.checkRevision}"
            return "check:${commitment.nextCheckAt}$suffix"
        }
        if (task.status in setOf(TaskStatus.WAITING, TaskStatus.REVIEW, TaskStatus.FAILED, TaskStatus.UNKNOWN))
            return "${task.status.wire}:${task.execution?.requestId}:${SignalInbox.idOf(task.result)}"
        if (task.status == TaskStatus.PROPOSED) return "proposal:${task.createdAt}:${SignalInbox.idOf(task.deadlineAt, task.why)}"
        return null
    }

    fun needsBrief(task: MikeTask, now: Long): Boolean = occurrence(task, now)?.let { key ->
        // Legacy rows used briefing as the receipt. An unsent cancellation is not one.
        val legacy = task.briefing?.takeUnless { it.notificationState == "cancelled" && it.chatSavedAt == null }?.occurrence
        when {
            key.startsWith("check:") -> key != (task.lastCheckBriefingOccurrence ?: legacy)
            key.startsWith("deadline:") -> key != (task.lastDeadlineBriefingOccurrence ?: legacy)
            else -> key != (task.lastStatusBriefingOccurrence ?: legacy)
        }
    } == true

    /** Scheduled checks do not depend on model availability or review quotas. */
    fun dueChecks(tasks: Collection<MikeTask>, now: Long, limit: Int = 10): List<MikeTask> = tasks
        .filter { occurrence(it, now)?.startsWith("check:") == true && needsBrief(it, now) }
        .sortedWith(compareBy<MikeTask> { it.commitment?.nextCheckAt ?: Long.MAX_VALUE }.thenBy { it.id })
        .take(limit.coerceAtLeast(0))

    /** Only real owner messages enter the feedback path; generated board chat messages cannot loop. */
    fun captureBoardMessage(role: String, ownerChat: Boolean, executionResult: Boolean): Boolean =
        (ownerChat && role == "user") || (executionResult && role == "assistant")

    fun feedback(signal: TaskSignal): TaskEvent? = signal.takeIf { it.ownerFeedback && it.source == "chat" && it.sourceVerified && it.text.isNotBlank() }?.let {
        TaskEvent("feedback:${it.id}", "owner:${it.sessionId}", 0, "feedback", "user", it.at,
            note = it.text, sourceRefs = listOf(TaskSourcePolicy.of(it)))
    }

    /** Bounded context selected without mutating usage counts or admitting excluded sources. */
    fun context(notes: List<MemoryNote>, query: String, relevantTaskIds: Set<String> = emptySet(), allowed: (MemoryNote) -> Boolean): List<MemoryNote> {
        fun words(s: String) = contextWords(s)
        val wanted = words(query)
        return notes.filter { it.retiredAt == null && it.supersededBy == null && allowed(it) }
            .filter { note ->
                val subjectHit = words(note.subject).intersect(wanted).isNotEmpty()
                val contentHits = words("${note.text} ${note.cue.orEmpty()}").intersect(wanted).size
                note.scope == "global" || subjectHit || note.linkedTaskIds.any { it in relevantTaskIds } ||
                    (note.scope == null && note.kind != "preference" && contentHits >= 2)
            }
            .map { it to (words("${it.subject} ${it.text} ${it.cue.orEmpty()}").intersect(wanted).size * 4 + (if (it.linkedTaskIds.any { id -> id in relevantTaskIds }) 4 else 0) +
                if (it.scope == "global") 2 else 0) }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<MemoryNote, Int>> { it.second }.thenByDescending { it.first.at })
            .take(8).map { it.first }
    }
}
