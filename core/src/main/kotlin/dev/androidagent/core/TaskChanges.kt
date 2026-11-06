package dev.androidagent.core

import kotlinx.serialization.Serializable

@Serializable
data class TaskChange(val before: MikeTask? = null, val after: MikeTask)
@Serializable
data class NoteChange(val before: MemoryNote? = null, val after: MemoryNote? = null) {
    val id: String get() = after?.id ?: before!!.id
}
@Serializable
data class MemberChange(val before: TeamMember? = null, val after: TeamMember? = null) {
    val id: String get() = after?.id ?: before!!.id
}
@Serializable
data class CoachDisposition(val eventId: String, val disposition: String, val reason: String, val evidence: String,
    val appliedFiles: List<String> = emptyList()) {
    fun validate(eventIds: Set<String>) {
        require(eventId in eventIds) { "The event is not in this review." }
        require(disposition in setOf("applied", "no_change", "defer", "retry")) { "Unknown disposition." }
        require(reason.trim().length in 3..240 && evidence.trim().length in 3..240) { "Give a short reason and evidence." }
        require(appliedFiles.size <= 16 && appliedFiles.all { it.length in 1..160 }) { "Invalid applied file evidence." }
        require(disposition != "applied" || appliedFiles.isNotEmpty()) { "Applied requires a changed file citing this event." }
    }
}

object CoachReview {
    /** IDs must be separate tokens, not prefixes of another event ID. */
    fun citesEvent(text: String, eventId: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}_:-])" + Regex.escape(eventId) + "(?![\\p{L}\\p{N}_-]|:(?=\\S))").containsMatchIn(text)

    /** An intermediate edit is not evidence if the final file was reverted or dropped the citation. */
    fun validateFinalFiles(decisions: List<CoachDisposition>, before: (String) -> String?, after: (String) -> String?,
        sources: (String) -> List<TaskSourceRef>) {
        decisions.filter { it.disposition == "applied" }.forEach { decision ->
            require(decision.appliedFiles.any { path ->
                val current = after(path)
                current != null && current != before(path) && citesEvent(current, decision.eventId) &&
                    sources(path).any { it.eventId == decision.eventId }
            }) { "Applied event ${decision.eventId} has no final changed file citing it." }
        }
    }

    fun completed(eventIds: List<String>, decisions: List<CoachDisposition>, hasChange: Boolean): Set<String> {
        decisions.forEach { it.validate(eventIds.toSet()) }
        require(decisions.map { it.eventId }.toSet() == eventIds.toSet() && decisions.size == eventIds.distinct().size) {
            "Give one disposition for each review event."
        }
        require(hasChange || decisions.none { it.disposition == "applied" }) { "Applied requires a saved change." }
        return decisions.filter { it.disposition in setOf("applied", "no_change") }.map { it.eventId }.toSet()
    }
}

@Serializable
data class TaskRun(val id: String, val sessionId: String, val purpose: String, val policyRevision: Long,
    val inputIds: List<String>, val startedAt: Long, val state: String = "running", val error: String? = null,
    val eventIds: List<String> = emptyList(), val deepUpkeep: Boolean = false, val nextUpkeepCursor: Int? = null,
    /** Text-free reservation; new input merged during a pass must survive its commit. */
    val inputVersions: Map<String, Long> = emptyMap(), val coachDispositions: List<CoachDisposition> = emptyList())
@Serializable
data class TaskChangeSet(val id: String, val policyRevision: Long,
    val tasks: List<TaskChange> = emptyList(), val notes: List<NoteChange> = emptyList(),
    val events: List<TaskEvent> = emptyList(), val baseGeneration: String? = null,
    val generation: String? = null, val undone: Boolean = false, val members: List<MemberChange> = emptyList())
data class TaskUndoResult(val restored: Int, val conflicts: Int)

/** An isolated, bounded model view. Its changes do not reach live stores until commit. */
class StagedTaskData private constructor(
    val store: InMemoryTaskBoardStore,
    private val tasksBefore: Map<String, MikeTask>,
    private val notesBefore: Map<String, MemoryNote>,
    private val eventIds: Set<String>,
    private val membersBefore: Map<String, TeamMember>,
) {
    suspend fun changes(id: String, policyRevision: Long, baseGeneration: String?, generation: String?): TaskChangeSet {
        val tasks = store.loadTasks().filter { tasksBefore[it.id] != it }.map { TaskChange(tasksBefore[it.id], it) }
        val notes = store.loadNotes().filter { notesBefore[it.id] != it }.map { NoteChange(notesBefore[it.id], it) }
        val members = store.loadMembers().associateBy { it.id }
        val memberChanges = (membersBefore.keys + members.keys).distinct().filter { membersBefore[it] != members[it] }
            .map { MemberChange(membersBefore[it], members[it]) }
        return TaskChangeSet(id, policyRevision, tasks, notes, store.loadEvents().filter { it.id !in eventIds }, baseGeneration, generation, members = memberChanges)
    }
    companion object {
        suspend fun create(source: TaskBoardStore): StagedTaskData {
            val tasks = source.loadTasks(); val notes = source.loadNotes(); val events = source.loadEvents()
            val store = InMemoryTaskBoardStore()
            tasks.forEach { store.saveTask(it) }; store.saveNotes(notes); store.saveSettings(source.loadSettings())
            source.loadMembers().forEach { store.saveMember(it) }
            // Original events are supplied separately to the Coach, not duplicated in the staged journal.
            return StagedTaskData(store, tasks.associateBy { it.id }, notes.associateBy { it.id }, events.map { it.id }.toSet(), source.loadMembers().associateBy { it.id })
        }
    }
}
