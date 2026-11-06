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
    val approvedPlan: ApprovedTaskPlan? = null,
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
    val revision: Long = 0,
    val sourceRefs: List<TaskSourceRef> = emptyList(),
    val commitment: TaskCommitment? = null,
    val briefing: TaskBriefing? = null,
    /** A deadline is separate from proposal expiry. */
    val deadlineAt: Long? = null,
    /** Status and scheduled checks have independent durable receipts. */
    val lastStatusBriefingOccurrence: String? = null,
    /** A status briefing must not erase a scheduled check's receipt. */
    val lastCheckBriefingOccurrence: String? = null,
    /** Deadline reminders have their own durable receipt. */
    val lastDeadlineBriefingOccurrence: String? = null,
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
    val sourceRefs: List<TaskSourceRef> = emptyList(),
    /** Null means legacy/unspecified, never assumed global. */
    val scope: String? = null,
)

/** How soon a queue row deserves a model. The declared order is the processing order. */
@Serializable
enum class SignalLane {
    @SerialName("immediate") IMMEDIATE,
    @SerialName("people") PEOPLE,
    @SerialName("background") BACKGROUND,
}

/** One remembered line of a notification row. */
@Serializable
data class SignalItem(
    val text: String,
    val sender: String? = null,
    /** The notification's title, in a row that holds a whole app. */
    val title: String? = null,
    val at: Long,
    /** How many times this same line arrived while the row waited. */
    val count: Int = 1,
)

/**
 * One conversation or app with something new for discovery. The text lives
 * only until a pass has used it: the store deletes it after a successful pass.
 * A chat row holds no text at all, only the place to read from.
 */
@Serializable
data class TaskSignal(
    /** Stable for this row, so a replayed capture updates it rather than adding another. */
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
    val notBefore: Long = 0,
    /** Set by the current personal-profile capture path; old external input stays unverified. */
    val sourceVerified: Boolean = false,
    /** App-owned: this is the user's message in a Manager/member chat, not model output. */
    val ownerFeedback: Boolean = false,
    /** Null only on input stored before rows existed. */
    val lane: SignalLane? = null,
    /** Why this row entered the queue: one of the [SignalRows] reasons. */
    val reason: String? = null,
    /** A notification row's lines, oldest first. */
    val items: List<SignalItem> = emptyList(),
    /** Lines this row had to push out, so a pass knows its context is partial. */
    val omitted: Int = 0,
    /** A chat row reads messages from this time on. */
    val sinceAt: Long = 0,
    /** The last change to this row. Quiet time and expiry start here. */
    val updatedAt: Long = 0,
    /** A pass may consume only the version it read. */
    val revision: Long = 0,
    val rowKey: String? = null,
) {
    val touchedAt: Long get() = maxOf(updatedAt, capturedAt)
    val chars: Int get() = text.length + items.sumOf { it.text.length }
}

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
    val sourceRefs: List<TaskSourceRef> = emptyList(),
)

/** The user's choices for the board. Off until the user turns discovery on. */
@Serializable
data class TaskBoardSettings(
    val discoveryOn: Boolean = false,
    /** Context connections by id ("whatsapp-web", "account:claude:claude.ai Gmail"): the user's "use to understand messages" switch. */
    val connectors: Map<String, Boolean> = emptyMap(),
    val pauseReason: String? = null,
    val pausedAt: Long? = null,
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
    val lastPassAt: Long? = null,
    val passDay: String? = null,
    val passesToday: Int = 0,
    /** Passes today that held nothing but background rows. */
    val backgroundPassesToday: Int = 0,
    /** Ordinary chat history before this time is left out unless the user asks for a scan. */
    val feedStartAt: Long? = null,
    val feedStartSequence: Long? = null,
    /** Read only by older hosts. New collection never imports history automatically. */
    val backfillDone: Boolean = true,
    val chatCursors: Map<String, Long> = emptyMap(),
    val lastCalendarCheckAt: Long? = null,
    val lastReviewAt: Long? = null,
    val lastUpkeepAt: Long? = null,
    val lastDeepUpkeepAt: Long? = null,
    val suppressed: List<TaskSuppression> = emptyList(),
    val policyRevision: Long = 0,
    val memoryForgetRevision: Long = 0,
    val lastSuccessfulPassAt: Long? = null,
    val lastReviewAttemptAt: Long? = null,
    val lastUpkeepAttemptAt: Long? = null,
    val upkeepCursor: Int = 0,
    val captureGaps: Int = 0,
    val lastCaptureGapAt: Long? = null,
    /** Messages from people that no pass ever saw. Counted apart from expired background input. */
    val lostPeopleMessages: Int = 0,
    val expiredBackgroundRows: Int = 0,
    val managerReviewedEvents: List<String> = emptyList(),
    val coachReviewedEvents: List<String> = emptyList(),
    val lastManagerReviewAt: Long? = null,
    val managerReviewDay: String? = null,
    val managerReviewsToday: Int = 0,
    val managerReviewSessionId: String? = null,
    val executionEngine: EngineKind? = null,
    val executionModel: String? = null,
    val executionEffort: String? = null,
)

/** What the board, memory, signals and team read and write. One store, one transaction each. */
interface TaskBoardStore {
    suspend fun loadTasks(): List<MikeTask>
    suspend fun saveTask(task: MikeTask)
    suspend fun loadEvents(): List<TaskEvent> = emptyList()
    suspend fun saveTaskWithEvent(task: MikeTask, event: TaskEvent) { saveTask(task) }
    suspend fun deleteTasks(ids: Collection<String>)
    suspend fun loadNotes(): List<MemoryNote>
    suspend fun saveNotes(notes: List<MemoryNote>)
    /** Atomic replacement, so a failed undo cannot leave memory empty. */
    suspend fun replaceNotes(notes: List<MemoryNote>)
    suspend fun deleteNotes(ids: Collection<String>)
    suspend fun forgetNotes(ids: Collection<String>) { deleteNotes(ids) }
    suspend fun forgottenNoteIds(): Set<String> = emptySet()
    suspend fun loadSignals(): List<TaskSignal>
    /** False for a duplicate; throws [SignalInboxFullException] without changing input when full. */
    suspend fun insertSignal(signal: TaskSignal): Boolean
    suspend fun saveSignals(signals: List<TaskSignal>)
    suspend fun deleteSignals(ids: Collection<String>)
    suspend fun loadMembers(): List<TeamMember>
    suspend fun saveMember(member: TeamMember)
    suspend fun deleteMember(id: String)
    suspend fun loadSettings(): TaskBoardSettings
    suspend fun saveSettings(settings: TaskBoardSettings)
    suspend fun loadSourceRefs(): Map<String, TaskSourceRef> = emptyMap()
    suspend fun saveRun(run: TaskRun) {}
    suspend fun loadRuns(): List<TaskRun> = emptyList()
    suspend fun activeGeneration(): String? = null
    suspend fun initializeGeneration(id: String): String = id
    suspend fun commitChanges(changes: TaskChangeSet, run: TaskRun) { error("Atomic board commits are not supported.") }
    suspend fun undoChanges(id: String): TaskUndoResult { error("Undo is not supported.") }
    suspend fun changes(id: String): TaskChangeSet? = null
    suspend fun sourceCursor(source: String): Long = 0
    /**
     * One atomic capture: rows, line [receipts], [events] and the cursor. A row
     * already waiting is updated in place. A newer revision can reopen it if
     * the previous batch committed concurrently. Callers dedupe input changes
     * using durable cursors and line receipts. Returns the written rows.
     */
    suspend fun capturePage(source: String, expected: Long, through: Long, signals: List<TaskSignal>,
        receipts: List<String> = emptyList(), events: List<TaskEvent> = emptyList(),
        stats: List<SourceStat> = emptyList()): List<TaskSignal> {
        error("Atomic source capture is not supported by this store.")
    }
    /** True when this line was already stored within the dedupe window. */
    suspend fun seenSignal(id: String, since: Long = 0): Boolean = false
    /** The one-time move of waiting input to rows: all of it, or none. */
    suspend fun replaceSignals(remove: Collection<String>, add: List<TaskSignal>, receipts: List<String>) {
        error("Atomic signal replacement is not supported by this store.")
    }
    suspend fun loadSourceStats(): List<SourceStat> = emptyList()
    suspend fun saveSourceStats(stats: List<SourceStat>, remove: Collection<String> = emptyList()) {}
    suspend fun clearSourceStats() {}
}

/** For tests and hosts without storage. */
class InMemoryTaskBoardStore : TaskBoardStore {
    private val tasks = linkedMapOf<String, MikeTask>()
    private val events = linkedMapOf<String, TaskEvent>()
    private val notes = linkedMapOf<String, MemoryNote>()
    private val signals = linkedMapOf<String, TaskSignal>()
    private val seenSignals = linkedMapOf<String, Long>()
    private val members = linkedMapOf<String, TeamMember>()
    private var settings = TaskBoardSettings()
    private val cursors = mutableMapOf<String, Long>()
    private val stats = linkedMapOf<String, SourceStat>()
    override suspend fun sourceCursor(source: String) = synchronized(this) { cursors[source] ?: 0 }
    override suspend fun capturePage(source: String, expected: Long, through: Long, signals: List<TaskSignal>,
        receipts: List<String>, events: List<TaskEvent>, stats: List<SourceStat>) = synchronized(this) {
        check((cursors[source] ?: 0) == expected) { "The source cursor changed." }
        val fresh = signals.distinctBy { it.id }.filter { it.revision > 0 || it.id in this.signals || it.id !in seenSignals }
        if (this.signals.size + fresh.count { it.id !in this.signals } > SignalInbox.MAX_PENDING) throw SignalInboxFullException()
        fresh.forEach { this.signals[it.id] = it; seenSignals[it.id] = it.touchedAt }
        val stamp = maxOf(signals.maxOfOrNull { it.touchedAt } ?: 0, stats.maxOfOrNull { it.updatedAt } ?: 0)
        receipts.forEach { seenSignals[it] = stamp }
        (events + fresh.mapNotNull(TaskFollowThrough::feedback)).forEach { this.events[it.id] = it }
        stats.forEach { delta -> this.stats[delta.key] = (this.stats[delta.key] ?: SourceStat.base(delta.app, delta.updatedAt)).plus(delta) }
        seenSignals.entries.removeAll { stamp - it.value > SignalInbox.DEDUPE_MS }
        while (seenSignals.size > SignalInbox.MAX_RECEIPTS) seenSignals.remove(seenSignals.keys.first())
        this.events.values.filter { it.kind == "feedback" }.dropLast(500).forEach { this.events.remove(it.id) }
        cursors[source] = through
        fresh
    }
    override suspend fun seenSignal(id: String, since: Long) = synchronized(this) { (seenSignals[id] ?: Long.MIN_VALUE) >= since }
    override suspend fun replaceSignals(remove: Collection<String>, add: List<TaskSignal>, receipts: List<String>) = synchronized(this) {
        remove.forEach { signals.remove(it) }
        add.forEach { signals[it.id] = it; seenSignals[it.id] = it.touchedAt }
        val stamp = add.maxOfOrNull { it.touchedAt } ?: 0
        receipts.forEach { seenSignals[it] = stamp }
    }
    override suspend fun loadSourceStats() = synchronized(this) { stats.values.toList() }
    override suspend fun saveSourceStats(stats: List<SourceStat>, remove: Collection<String>) = synchronized(this) {
        stats.forEach { this.stats[it.key] = it }; remove.forEach { this.stats.remove(it) }
    }
    override suspend fun clearSourceStats() = synchronized(this) { stats.clear() }
    override suspend fun loadTasks() = synchronized(this) { tasks.values.toList() }
    override suspend fun saveTask(task: MikeTask) = synchronized(this) { tasks[task.id] = task }
    override suspend fun loadEvents() = synchronized(this) { events.values.toList() }
    override suspend fun saveTaskWithEvent(task: MikeTask, event: TaskEvent) = synchronized(this) {
        tasks[task.id] = task; events[event.id] = event
    }
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
            if (signals.size >= SignalInbox.MAX_PENDING) throw SignalInboxFullException()
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
