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
data class TaskRun(val id: String, val sessionId: String, val purpose: String, val policyRevision: Long,
    val inputIds: List<String>, val startedAt: Long, val state: String = "running", val error: String? = null,
    val eventIds: List<String> = emptyList(), val deepUpkeep: Boolean = false, val nextUpkeepCursor: Int? = null)
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
