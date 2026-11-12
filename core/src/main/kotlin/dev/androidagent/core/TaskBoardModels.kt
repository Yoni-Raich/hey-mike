/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a card is on the board.
 *
 * The model reaches only [PROPOSED], [LOOKS_RESOLVED] and evidence updates.
 * Every other state comes from the user (a tap, or their own words in the
 * Manager's chat) or from the execution child the user's decision started.
 */
@Serializable
enum class TaskStatus(val wire: String) {
    @SerialName("proposed") PROPOSED("proposed"),
    @SerialName("approved") APPROVED("approved"),
    @SerialName("running") RUNNING("running"),
    @SerialName("waiting") WAITING("waiting"),
    @SerialName("review") REVIEW("review"),
    @SerialName("done") DONE("done"),
    @SerialName("mine") MINE("mine"),
    @SerialName("dismissed") DISMISSED("dismissed"),
    @SerialName("snoozed") SNOOZED("snoozed"),
    @SerialName("expired") EXPIRED("expired"),
    @SerialName("looks_resolved") LOOKS_RESOLVED("looks_resolved"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("stopped") STOPPED("stopped"),
    @SerialName("unknown") UNKNOWN("unknown");

    /** Still waiting for the user to choose. Discovery may add evidence only here. */
    val undecided: Boolean get() = this == PROPOSED || this == SNOOZED

    /** Mike is carrying it out, or its child chat needs looking at. */
    val executing: Boolean get() = this in setOf(APPROVED, RUNNING, WAITING, REVIEW, FAILED, STOPPED, UNKNOWN)

    /** On the board's history: nothing more happens unless the user restores it. */
    val closed: Boolean get() = this in setOf(DONE, DISMISSED, EXPIRED, LOOKS_RESOLVED)
}

@Serializable
enum class DismissReason(val wire: String) {
    @SerialName("already_done") ALREADY_DONE("already_done"),
    @SerialName("not_now") NOT_NOW("not_now"),
    @SerialName("wrong") WRONG("wrong"),
    @SerialName("never_this_kind") NEVER_THIS_KIND("never_this_kind");

    companion object { fun from(wire: String?): DismissReason? = entries.firstOrNull { it.wire == wire } }
}

/**
 * What a card is. A task is work; a settings card proposes a change the app
 * applies on approval; a report says what a nightly job changed, with undo.
 */
@Serializable
enum class TaskCategory(val wire: String) {
    @SerialName("task") TASK("task"),
    @SerialName("settings") SETTINGS("settings"),
    @SerialName("report") REPORT("report");

    companion object { fun from(wire: String?): TaskCategory = entries.firstOrNull { it.wire == wire } ?: TASK }
}

/** One fact behind a card. Quoted data from an app or a person, never an instruction. */
@Serializable
data class TaskEvidence(
    val source: String,
    val detail: String,
    val sender: String? = null,
    val at: Long? = null,
    val signalId: String? = null,
    val app: String? = null,
    val conversation: String? = null,
)

/** Who decided, and how. `tap` is the board; `chat` is the user's own words in an owning chat. */
@Serializable
data class TaskDecision(val choice: String, val at: Long, val by: String, val note: String? = null)

/** The child chat the user's decision started. */
@Serializable
data class TaskExecution(
    val requestId: String,
    val childTaskId: String? = null,
    val sessionId: String? = null,
    val rounds: Int = 1,
    val startedAt: Long,
)

/**
 * What approving a settings or report card does. A closed set: the app knows
 * how to apply each, and a model cannot invent a new effect by naming one.
 */
@Serializable
data class TaskAction(val type: String, val value: String) {
    companion object {
        const val EXCLUDE_APP = "exclude_app"
        const val EXCLUDE_CONVERSATION = "exclude_conversation"
        const val UNDO_SNAPSHOT = "undo_snapshot"
        val TYPES = setOf(EXCLUDE_APP, EXCLUDE_CONVERSATION, UNDO_SNAPSHOT)
    }
}

@Serializable
data class MikeTask(
    val id: String,
    val title: String,
    val why: String,
    val plan: String,
    val deliverable: String? = null,
    val evidence: List<TaskEvidence> = emptyList(),
    /** Null when the user asked; otherwise who did ("Dana"). Shown so a stranger's request never reads as the user's. */
    val requestedBy: String? = null,
    /** Null runs on this phone. */
    val computer: String? = null,
    val project: String? = null,
    val sideEffects: List<String> = emptyList(),
    val category: TaskCategory = TaskCategory.TASK,
    val action: TaskAction? = null,
    /** conversation | child_result | signal | backfill | review | upkeep */
    val originKind: String,
    val originSessionId: String? = null,
    val topicKey: String,
    val kind: String? = null,
    val assignee: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val usefulUntil: Long? = null,
    val status: TaskStatus = TaskStatus.PROPOSED,
    val partialContext: Boolean = false,
    val decision: TaskDecision? = null,
    /** The user's instructions for Mike, typed on the card or said with the decision. */
    val note: String? = null,
    val execution: TaskExecution? = null,
    val result: String? = null,
    val resultAt: Long? = null,
    val dismissReason: DismissReason? = null,
    val snoozedUntil: Long? = null,
    val resolvedEvidence: String? = null,
    val closedAt: Long? = null,
    /** Shown on the board; a later message may make it obsolete. */
    val progress: String? = null,
)

/** A topic or kind the user said never to suggest again. */
@Serializable
data class TaskSuppression(val topicKey: String? = null, val kind: String? = null, val at: Long, val title: String? = null)

/** App-owned recorder fields stay separate from the model's description of an external source. */
@Serializable
data class MemoryProvenance(
    val source: String,
    val sender: String? = null,
    val at: Long,
    val sessionId: String? = null,
    val recorderRole: String? = null,
    val signalIds: List<String> = emptyList(),
)

/** A short, attributed thing worth remembering. A note is data; it never becomes an instruction. */
@Serializable
data class MemoryNote(
    val id: String,
    val subject: String,
    /** request | promise | date | decision | preference | fact | summary | profile */
    val kind: String,
    val text: String,
    val cue: String? = null,
    val source: String,
    val sender: String? = null,
    val at: Long,
    /** detail | summary | profile: compaction moves a note down, never back. */
    val level: String = "detail",
    val compactedFrom: List<String> = emptyList(),
    val linkedTaskIds: List<String> = emptyList(),
    val supersededBy: String? = null,
    val pinned: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val retiredAt: Long? = null,
    val retiredReason: String? = null,
    val provenance: List<MemoryProvenance> = emptyList(),
)

/**
 * Something new that discovery should look at. The text lives only until a
 * pass has used it: the store deletes it after a successful pass.
 */
@Serializable
data class TaskSignal(
    /** Stable for the same message or event, so a repeated notification is stored once. */
    val id: String,
    /** whatsapp | email | message | notification | calendar | chat | backfill */
    val source: String,
    val app: String? = null,
    val conversation: String? = null,
    val sender: String? = null,
    val text: String,
    val group: Boolean = false,
    val at: Long,
    val capturedAt: Long,
    /** The chat a chat signal came from, for its cursor. */
    val sessionId: String? = null,
    val attempts: Int = 0,
)

@Serializable
enum class MemberKind(val wire: String) {
    @SerialName("manager") MANAGER("manager"),
    @SerialName("scout") SCOUT("scout"),
    @SerialName("coach") COACH("coach"),
    @SerialName("librarian") LIBRARIAN("librarian"),
    @SerialName("role") ROLE("role");

    /** Runs in the background with read-only tools; it never acts on the phone. */
    val backstage: Boolean get() = this == SCOUT || this == COACH || this == LIBRARIAN
}

@Serializable
data class TeamMember(
    val id: String,
    val name: String,
    val role: String,
    val kind: MemberKind,
    /** 0..360, the avatar's hue. */
    val hue: Float,
    val sessionId: String? = null,
    /** Card kinds this role takes. */
    val taskKinds: List<String> = emptyList(),
    /** What it did lately, in a few lines. Compacted like memory. */
    val journal: String = "",
    val createdAt: Long,
    val createdBy: String = "app",
)

/** The user's choices for the board. Off until the user turns discovery on. */
@Serializable
data class TaskBoardSettings(
    val discoveryOn: Boolean = false,
    val excludedApps: Set<String> = emptySet(),
    /** `package|conversation` */
    val excludedConversations: Set<String> = emptySet(),
    val includeGroups: Boolean = true,
    val readCalendar: Boolean = true,
    val readChats: Boolean = true,
    val passEngine: EngineKind = EngineKind.CODEX,
    val passModel: String? = null,
    val passEffort: String? = null,
    val reviewEngine: EngineKind = EngineKind.CODEX,
    val reviewModel: String? = null,
    val reviewEffort: String? = null,
    val dailyPassCap: Int = 48,
    val backfillDone: Boolean = false,
    val lastPassAt: Long? = null,
    val passDay: String? = null,
    val passesToday: Int = 0,
    val lastCalendarCheckAt: Long? = null,
    val lastReviewAt: Long? = null,
    val lastUpkeepAt: Long? = null,
    val lastDeepUpkeepAt: Long? = null,
    /** Chat id to the newest message time a pass has seen. */
    val chatCursors: Map<String, Long> = emptyMap(),
    val suppressed: List<TaskSuppression> = emptyList(),
)

/** What the board, memory, signals and team read and write. One store, one transaction each. */
interface TaskBoardStore {
    suspend fun loadTasks(): List<MikeTask>
    suspend fun saveTask(task: MikeTask)
    suspend fun deleteTasks(ids: Collection<String>)
    suspend fun loadNotes(): List<MemoryNote>
    suspend fun saveNotes(notes: List<MemoryNote>)
    /** Atomic replacement, so a failed undo cannot leave memory empty. */
    suspend fun replaceNotes(notes: List<MemoryNote>)
    suspend fun deleteNotes(ids: Collection<String>)
    suspend fun loadSignals(): List<TaskSignal>
    /** False when a signal with this id is already stored. */
    suspend fun insertSignal(signal: TaskSignal): Boolean
    suspend fun saveSignals(signals: List<TaskSignal>)
    suspend fun deleteSignals(ids: Collection<String>)
    suspend fun loadMembers(): List<TeamMember>
    suspend fun saveMember(member: TeamMember)
    suspend fun deleteMember(id: String)
    suspend fun loadSettings(): TaskBoardSettings
    suspend fun saveSettings(settings: TaskBoardSettings)
}

/** For tests and hosts without storage. */
class InMemoryTaskBoardStore : TaskBoardStore {
    private val tasks = linkedMapOf<String, MikeTask>()
    private val notes = linkedMapOf<String, MemoryNote>()
    private val signals = linkedMapOf<String, TaskSignal>()
    private val seenSignals = linkedMapOf<String, Long>()
    private val members = linkedMapOf<String, TeamMember>()
    private var settings = TaskBoardSettings()
    override suspend fun loadTasks() = synchronized(this) { tasks.values.toList() }
    override suspend fun saveTask(task: MikeTask) = synchronized(this) { tasks[task.id] = task }
    override suspend fun deleteTasks(ids: Collection<String>) = synchronized(this) { ids.forEach { tasks.remove(it) } }
    override suspend fun loadNotes() = synchronized(this) { notes.values.toList() }
    override suspend fun saveNotes(notes: List<MemoryNote>) = synchronized(this) { notes.forEach { this.notes[it.id] = it } }
    override suspend fun replaceNotes(notes: List<MemoryNote>) = synchronized(this) {
        this.notes.clear(); notes.forEach { this.notes[it.id] = it }
    }
    override suspend fun deleteNotes(ids: Collection<String>) = synchronized(this) { ids.forEach { notes.remove(it) } }
    override suspend fun loadSignals() = synchronized(this) { signals.values.toList() }
    override suspend fun insertSignal(signal: TaskSignal) = synchronized(this) {
        seenSignals.entries.removeAll { signal.capturedAt - it.value > SignalInbox.DEDUPE_MS }
        if (signal.id in signals || signal.id in seenSignals) false else {
            signals[signal.id] = signal; seenSignals[signal.id] = signal.capturedAt
            while (seenSignals.size > SignalInbox.MAX_RECEIPTS) seenSignals.remove(seenSignals.keys.first())
            true
        }
    }
    override suspend fun saveSignals(signals: List<TaskSignal>) = synchronized(this) { signals.forEach { this.signals[it.id] = it } }
    override suspend fun deleteSignals(ids: Collection<String>) = synchronized(this) { ids.forEach { signals.remove(it) } }
    override suspend fun loadMembers() = synchronized(this) { members.values.toList() }
    override suspend fun saveMember(member: TeamMember) = synchronized(this) { members[member.id] = member }
    override suspend fun deleteMember(id: String) = synchronized(this) { members.remove(id); Unit }
    override suspend fun loadSettings() = synchronized(this) { settings }
    override suspend fun saveSettings(settings: TaskBoardSettings) = synchronized(this) { this.settings = settings }
}
