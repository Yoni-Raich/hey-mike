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

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a proposal asks for, before the board checks it. */
data class TaskProposal(
    val title: String,
    val why: String,
    val plan: String,
    val topicKey: String? = null,
    val deliverable: String? = null,
    val evidence: List<TaskEvidence> = emptyList(),
    val requestedBy: String? = null,
    val computer: String? = null,
    val project: String? = null,
    val sideEffects: List<String> = emptyList(),
    val kind: String? = null,
    val usefulUntil: Long? = null,
    val partialContext: Boolean = false,
    val category: TaskCategory = TaskCategory.TASK,
    val action: TaskAction? = null,
)

sealed class ProposeOutcome {
    data class Created(val task: MikeTask) : ProposeOutcome()
    data class Duplicate(val task: MikeTask) : ProposeOutcome()
    data class Refused(val reason: String, val message: String) : ProposeOutcome()
}

/**
 * Carries out what the user decided. The board calls it from inside the
 * decision itself: nothing polls a stored status to start work, so a status
 * changed behind the board's back starts nothing.
 */
interface TaskExecutor {
    /** Start the approved task. Throws when nothing could be started. */
    suspend fun start(task: MikeTask, attempt: Int): TaskExecution
    /** Send the user's follow-up to the task's existing chat. */
    suspend fun continueWork(task: MikeTask, message: String)
    suspend fun stop(task: MikeTask)
    /** Apply a settings or report card's action. Throws when it cannot. */
    suspend fun apply(task: MikeTask)
}

/** Only use when the executor knows no work was dispatched. All other failures are uncertain. */
class TaskStartRejectedException(message: String) : IllegalStateException(message)

/**
 * The board: proposals, the user's decisions, and execution status.
 *
 * Its rules are the product's safety rules, so they live here and are tested
 * here: a proposal is deduplicated, capped and checked against what the user
 * said never to suggest; only a decision moves a card out of "proposed"; and a
 * decision to do the work starts it directly through [executor].
 */
class TaskBoard(
    private val store: TaskBoardStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    private val mutableTasks = MutableStateFlow<List<MikeTask>>(emptyList())
    val tasks: StateFlow<List<MikeTask>> = mutableTasks.asStateFlow()
    private val mutableSettings = MutableStateFlow(TaskBoardSettings())
    val settings: StateFlow<TaskBoardSettings> = mutableSettings.asStateFlow()
    @Volatile var executor: TaskExecutor? = null
    @Volatile private var loaded = false

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        mutableSettings.value = store.loadSettings()
        val restored = store.loadTasks().map { task ->
            // A decision that never reached its child chat cannot be trusted as
            // started or as not started; the card says so instead of retrying.
            if (task.status == TaskStatus.APPROVED && task.execution?.childTaskId == null) {
                task.copy(status = TaskStatus.UNKNOWN, progress = "The app restarted while starting this. Check before trying again.",
                    updatedAt = now()).also { store.saveTask(it) }
            } else task
        }
        mutableTasks.value = restored.sortedByDescending { it.createdAt }
        loaded = true
    }

    fun task(id: String): MikeTask? = mutableTasks.value.firstOrNull { it.id == id }

    suspend fun editSettings(change: (TaskBoardSettings) -> TaskBoardSettings): TaskBoardSettings = lock.withLock {
        val next = change(mutableSettings.value)
        store.saveSettings(next)
        mutableSettings.value = next
        next
    }

    suspend fun propose(proposal: TaskProposal, originKind: String, originSessionId: String?): ProposeOutcome = lock.withLock {
        val title = proposal.title.trim()
        if (title.length < 3) return@withLock refused("bad_title", "Give a short title that names the work.")
        if (proposal.why.isBlank()) return@withLock refused("no_why", "Say why this matters now.")
        if (proposal.plan.isBlank()) return@withLock refused("no_plan", "Say what Mike would do.")
        if (proposal.category == TaskCategory.TASK && proposal.evidence.isEmpty() && originKind != "conversation") {
            return@withLock refused("no_evidence", "A proposal needs the evidence it rests on.")
        }
        val action = proposal.action
        when (proposal.category) {
            TaskCategory.TASK -> if (action != null) return@withLock refused("bad_action", "A task card has no action; describe the work in plan.")
            TaskCategory.SETTINGS -> if (action == null || action.type !in setOf(TaskAction.EXCLUDE_APP, TaskAction.EXCLUDE_CONVERSATION)) {
                return@withLock refused("bad_action", "A settings card needs action exclude_app or exclude_conversation.")
            }
            TaskCategory.REPORT -> if (action != null && action.type != TaskAction.UNDO_SNAPSHOT) {
                return@withLock refused("bad_action", "A report card's only action is undo_snapshot.")
            }
        }
        val topic = topicKey(proposal.topicKey?.takeIf { it.isNotBlank() } ?: title)
        val kind = proposal.kind?.let(::topicKey)?.takeIf { it.isNotEmpty() }
        val clock = now()
        val settings = mutableSettings.value
        if (settings.suppressed.any { (it.topicKey != null && it.topicKey == topic) || (kind != null && it.kind == kind) }) {
            return@withLock refused("suppressed", "The user asked not to suggest this kind of task. Do not propose it again.")
        }
        val all = mutableTasks.value
        all.firstOrNull { it.topicKey == topic && (!it.status.closed || clock - (it.closedAt ?: it.updatedAt) < RECENT_MS) }?.let {
            return@withLock ProposeOutcome.Duplicate(it)
        }
        val today = Instant.ofEpochMilli(clock).atZone(zone()).toLocalDate()
        val madeToday = all.count { it.category == proposal.category &&
            Instant.ofEpochMilli(it.createdAt).atZone(zone()).toLocalDate() == today }
        val dailyCap = if (proposal.category == TaskCategory.TASK) MAX_NEW_PER_DAY else MAX_SYSTEM_PER_DAY
        if (madeToday >= dailyCap) return@withLock refused("daily_cap", "Today's limit of $dailyCap new cards is reached. Keep only what matters most for tomorrow.")
        if (proposal.category == TaskCategory.TASK && all.count { it.category == TaskCategory.TASK && it.status.undecided } >= MAX_OPEN) {
            return@withLock refused("board_full", "$MAX_OPEN cards already wait for the user. Add evidence to one of them instead.")
        }
        val until = proposal.usefulUntil?.coerceAtMost(clock + MAX_LIFETIME_MS)
        if (until != null && until <= clock) return@withLock refused("too_late", "usefulUntil is already past.")
        val task = MikeTask(
            id = newId(),
            title = title.take(MAX_TITLE),
            why = proposal.why.trim().take(MAX_WHY),
            plan = proposal.plan.trim().take(MAX_PLAN),
            deliverable = proposal.deliverable?.trim()?.take(MAX_DELIVERABLE)?.takeIf { it.isNotEmpty() },
            evidence = proposal.evidence.take(MAX_EVIDENCE).map { it.copy(detail = it.detail.take(MAX_DETAIL), source = it.source.take(60)) },
            requestedBy = proposal.requestedBy?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
            computer = proposal.computer?.trim()?.takeIf { it.isNotEmpty() && !it.equals("phone", true) },
            project = proposal.project?.trim()?.takeIf { it.isNotEmpty() },
            sideEffects = proposal.sideEffects.map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(5),
            category = proposal.category,
            action = action,
            originKind = originKind,
            originSessionId = originSessionId,
            topicKey = topic,
            kind = kind,
            createdAt = clock,
            updatedAt = clock,
            usefulUntil = until,
            partialContext = proposal.partialContext,
        )
        put(task)
        ProposeOutcome.Created(task)
    }

    /**
     * Add evidence to a card the user has not decided yet, or say it now looks
     * resolved. Discovery cannot touch a decided card: that is the user's.
     */
    suspend fun update(
        id: String,
        evidence: List<TaskEvidence> = emptyList(),
        why: String? = null,
        usefulUntil: Long? = null,
        partialContext: Boolean? = null,
        looksResolved: String? = null,
    ): MikeTask = lock.withLock {
        val task = required(id)
        check(task.status.undecided) { "Only a card the user has not decided can be updated. This one is ${task.status.wire}." }
        val clock = now()
        val merged = (task.evidence + evidence.map { it.copy(detail = it.detail.take(MAX_DETAIL)) }).distinct().takeLast(MAX_EVIDENCE)
        var next = task.copy(
            evidence = merged,
            why = why?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_WHY) ?: task.why,
            usefulUntil = usefulUntil?.coerceAtMost(clock + MAX_LIFETIME_MS) ?: task.usefulUntil,
            partialContext = partialContext ?: task.partialContext,
            updatedAt = clock,
        )
        if (!looksResolved.isNullOrBlank()) {
            next = next.copy(status = TaskStatus.LOOKS_RESOLVED, resolvedEvidence = looksResolved.trim().take(MAX_DETAIL), closedAt = clock)
        }
        put(next)
        next
    }

    suspend fun setNote(id: String, note: String?): MikeTask = lock.withLock {
        val task = required(id)
        val next = task.copy(note = note?.trim()?.take(MAX_NOTE)?.takeIf { it.isNotEmpty() }, updatedAt = now())
        put(next); next
    }

    suspend fun assign(id: String, memberId: String?): MikeTask = lock.withLock {
        val task = required(id)
        val next = task.copy(assignee = memberId, updatedAt = now())
        put(next); next
    }

    /**
     * The user's choice. `by` is `tap` from the board or `chat` for the user's
     * own words in an owning chat; the tool layer never passes anything else.
     */
    suspend fun decide(
        id: String,
        choice: String,
        by: String,
        note: String? = null,
        reason: DismissReason? = null,
        until: Long? = null,
    ): MikeTask {
        require(by == "tap" || by == "chat") { "A task decision needs a real user input." }
        val clock = now()
        val decision = TaskDecision(choice, clock, by, note?.trim()?.take(MAX_NOTE)?.takeIf { it.isNotEmpty() })
        when (choice) {
            "do" -> return startWork(id, decision)
            "continue" -> return continueWork(id, decision)
            "stop" -> return stopWork(id, decision)
        }
        return lock.withLock {
            val task = required(id)
            val next = when (choice) {
                "mine" -> {
                    check(task.category == TaskCategory.TASK) { "Only a task can be taken by the user." }
                    check(task.status.undecided || task.status in setOf(TaskStatus.FAILED, TaskStatus.STOPPED)) { "This card is ${task.status.wire}." }
                    task.copy(status = TaskStatus.MINE, decision = decision, note = decision.note ?: task.note)
                }
                "dismiss" -> {
                    check(task.status !in setOf(TaskStatus.APPROVED, TaskStatus.RUNNING, TaskStatus.WAITING)) { "Stop the work first." }
                    check(!task.status.closed) { "This card is already closed." }
                    if (reason == DismissReason.NOT_NOW) {
                        task.copy(status = TaskStatus.SNOOZED, snoozedUntil = until ?: (clock + DAY_MS), decision = decision, dismissReason = reason)
                    } else {
                        if (reason == DismissReason.NEVER_THIS_KIND) suppress(task)
                        task.copy(status = TaskStatus.DISMISSED, decision = decision, dismissReason = reason, closedAt = clock)
                    }
                }
                "snooze" -> {
                    check(task.status.undecided) { "Only an undecided card can wait." }
                    task.copy(status = TaskStatus.SNOOZED, snoozedUntil = (until ?: (clock + 3 * HOUR_MS)).coerceAtLeast(clock + 60_000),
                        decision = decision)
                }
                "done" -> {
                    check(task.status in setOf(TaskStatus.MINE, TaskStatus.REVIEW, TaskStatus.FAILED, TaskStatus.STOPPED, TaskStatus.UNKNOWN) ||
                        task.status.undecided) { "This card is ${task.status.wire}." }
                    task.copy(status = TaskStatus.DONE, decision = decision, closedAt = clock)
                }
                "restore" -> {
                    check(task.status.closed) { "Only a closed card can come back." }
                    unsuppress(task)
                    task.copy(status = TaskStatus.PROPOSED, decision = decision, dismissReason = null, closedAt = null,
                        resolvedEvidence = null, snoozedUntil = null)
                }
                else -> error("Choose do, mine, dismiss, snooze, done, continue, stop or restore.")
            }.copy(updatedAt = clock)
            put(next)
            next
        }
    }

    private suspend fun startWork(id: String, decision: TaskDecision): MikeTask {
        val runner = executor ?: error("Mike cannot start work on this phone yet.")
        val (approved, attempt) = lock.withLock {
            val task = required(id)
            val retry = task.status in setOf(TaskStatus.FAILED, TaskStatus.STOPPED)
            check(task.status.undecided || task.status == TaskStatus.MINE || retry) {
                if (task.status == TaskStatus.UNKNOWN) "The last attempt's outcome is unknown. Open its chat and check before starting again."
                else "This card is ${task.status.wire}."
            }
            check(task.usefulUntil == null || task.usefulUntil > decision.at) { "This task is no longer timely. Update its evidence before approving it." }
            val attempt = (task.execution?.rounds ?: 0) + 1
            val next = task.copy(status = TaskStatus.APPROVED, decision = decision, note = decision.note ?: task.note,
                updatedAt = decision.at, progress = "Starting", snoozedUntil = null, dismissReason = null,
                execution = TaskExecution("${task.id}#$attempt", rounds = attempt, startedAt = decision.at), result = null, resultAt = null)
            put(next)
            next to attempt
        }
        if (approved.category != TaskCategory.TASK) {
            return try {
                runner.apply(approved)
                finish(id) { it.copy(status = TaskStatus.DONE, closedAt = now(), progress = "Applied") }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                finish(id) { it.copy(status = TaskStatus.FAILED, progress = error.message ?: "Could not apply this") }
            }
        }
        return try {
            val receipt = runner.start(approved, attempt)
            val execution = receipt.copy(requestId = approved.execution!!.requestId, rounds = attempt)
            val current = finish(id) { current ->
                // A child that already reported is newer than this answer.
                if (current.execution?.requestId != execution.requestId) current
                else if (current.status == TaskStatus.APPROVED) current.copy(status = TaskStatus.RUNNING, execution = execution, progress = "Working")
                else current.copy(execution = execution)
            }
            // Stop can arrive while start() is waiting for its receipt. Stop
            // the now-known child before allowing the card to be retried.
            if (current.decision?.choice == "stop" && current.execution?.requestId == execution.requestId) {
                runner.stop(current)
            }
            current
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    finish(id) { current ->
                        if (current.execution?.requestId == approved.execution?.requestId)
                            current.copy(status = TaskStatus.UNKNOWN, progress = "Starting was interrupted. Check before trying again.")
                        else current
                    }
                }
                throw error
            }
            finish(id) { current ->
                if (current.execution?.requestId != approved.execution?.requestId) current
                else current.copy(status = if (error is TaskStartRejectedException && current.decision?.choice != "stop") TaskStatus.FAILED else TaskStatus.UNKNOWN,
                    progress = error.message ?: "Starting may have reached the executor. Check the existing chat before trying again.")
            }
        }
    }

    private suspend fun continueWork(id: String, decision: TaskDecision): MikeTask {
        val runner = executor ?: error("Mike cannot continue work on this phone yet.")
        val message = decision.note ?: error("Say what Mike should do next.")
        val task = lock.withLock {
            val task = required(id)
            check(task.status in setOf(TaskStatus.REVIEW, TaskStatus.WAITING) && task.execution != null) { "Only work Mike finished or paused can continue." }
            val next = task.copy(status = TaskStatus.RUNNING, decision = decision, progress = "Continuing",
                execution = task.execution.copy(rounds = task.execution.rounds + 1), updatedAt = decision.at, result = null)
            put(next); next
        }
        return try {
            runner.continueWork(task, message)
            task(id) ?: task
        } catch (error: Exception) {
            withContext(NonCancellable) {
                finish(id) { it.copy(status = TaskStatus.UNKNOWN, progress = error.message ?: "The follow-up may not have reached the chat.") }
            }.also { if (error is kotlinx.coroutines.CancellationException) throw error }
        }
    }

    private suspend fun stopWork(id: String, decision: TaskDecision): MikeTask {
        val task = lock.withLock {
            val task = required(id)
            check(task.status in setOf(TaskStatus.APPROVED, TaskStatus.RUNNING, TaskStatus.WAITING)) { "Nothing is running for this card." }
            val next = task.copy(status = TaskStatus.UNKNOWN, decision = decision, progress = "Stop requested; waiting for the executor. Completed actions are not undone.",
                updatedAt = decision.at)
            put(next); next
        }
        // An in-flight start may not have a child id yet. startWork handles
        // its late receipt; until then the outcome remains unknown.
        executor?.stop(task)
        return task(id) ?: task
    }

    private suspend fun finish(id: String, change: (MikeTask) -> MikeTask): MikeTask = lock.withLock {
        val next = change(required(id)).copy(updatedAt = now())
        put(next); next
    }

    /**
     * Follow the child chats the user's decisions started. A card the user
     * already closed keeps its state: a late child result is evidence, not a
     * reason to reopen it.
     */
    suspend fun syncChildren(children: List<SessionAgentTask>) = lock.withLock {
        val byRequest = children.associateBy { it.requestId }
        mutableTasks.value.forEach { task ->
            val execution = task.execution ?: return@forEach
            if (!task.status.executing) return@forEach
            val child = (execution.childTaskId?.let { cid -> children.firstOrNull { it.id == cid } }) ?: byRequest[execution.requestId] ?: return@forEach
            val status = when (child.status) {
                "waiting_for_user" -> TaskStatus.WAITING
                "completed" -> TaskStatus.REVIEW
                "failed" -> TaskStatus.FAILED
                "cancelled" -> TaskStatus.STOPPED
                "unknown" -> TaskStatus.UNKNOWN
                else -> TaskStatus.RUNNING
            }
            // Stop was pressed: the card stays stopped until the child agrees or ends.
            if (task.decision?.choice == "stop" && status in setOf(TaskStatus.RUNNING, TaskStatus.WAITING)) return@forEach
            val next = task.copy(
                status = status,
                progress = child.question?.let { "Waiting for you: $it" } ?: child.progress,
                result = if (status == TaskStatus.REVIEW) child.result?.take(MAX_RESULT) else task.result,
                resultAt = if (status == TaskStatus.REVIEW && task.status != TaskStatus.REVIEW) now() else task.resultAt,
                execution = execution.copy(childTaskId = child.id, sessionId = child.sessionId ?: execution.sessionId),
            )
            if (next != task) put(next.copy(updatedAt = now()))
        }
    }

    /** Wake snoozed cards, expire stale ones, and forget very old history. */
    suspend fun sweep(): List<MikeTask> = lock.withLock {
        val clock = now()
        val changed = mutableListOf<MikeTask>()
        mutableTasks.value.forEach { task ->
            val next = when {
                task.status.undecided && task.usefulUntil != null && task.usefulUntil <= clock ->
                    task.copy(status = TaskStatus.EXPIRED, closedAt = clock, updatedAt = clock)
                task.status == TaskStatus.SNOOZED && (task.snoozedUntil ?: 0) <= clock ->
                    task.copy(status = TaskStatus.PROPOSED, snoozedUntil = null, updatedAt = clock)
                else -> null
            }
            if (next != null) { put(next); changed += next }
        }
        val old = mutableTasks.value.filter { it.status.closed && clock - (it.closedAt ?: it.updatedAt) > HISTORY_MS }.map { it.id }
        if (old.isNotEmpty()) {
            store.deleteTasks(old)
            mutableTasks.value = mutableTasks.value.filterNot { it.id in old }
        }
        changed
    }

    private suspend fun suppress(task: MikeTask) {
        val entry = TaskSuppression(topicKey = task.topicKey, kind = task.kind, at = now(), title = task.title)
        val next = mutableSettings.value.let { it.copy(suppressed = (it.suppressed + entry).takeLast(MAX_SUPPRESSED)) }
        store.saveSettings(next); mutableSettings.value = next
    }

    private suspend fun unsuppress(task: MikeTask) {
        val current = mutableSettings.value
        val kept = current.suppressed.filterNot { it.topicKey == task.topicKey }
        if (kept.size != current.suppressed.size) {
            val next = current.copy(suppressed = kept)
            store.saveSettings(next); mutableSettings.value = next
        }
    }

    /** Lift a suppression from the board's settings screen. */
    suspend fun removeSuppression(entry: TaskSuppression) = editSettings { it.copy(suppressed = it.suppressed - entry) }

    private fun required(id: String): MikeTask = mutableTasks.value.firstOrNull { it.id == id } ?: error("No card has id $id. Use list.")

    private suspend fun put(task: MikeTask) {
        store.saveTask(task)
        val others = mutableTasks.value.filterNot { it.id == task.id }
        mutableTasks.value = (others + task).sortedByDescending { it.createdAt }
    }

    private fun refused(reason: String, message: String) = ProposeOutcome.Refused(reason, message)

    companion object {
        const val MAX_TITLE = 120
        const val MAX_WHY = 400
        const val MAX_PLAN = 1_200
        const val MAX_DELIVERABLE = 300
        const val MAX_DETAIL = 500
        const val MAX_NOTE = 2_000
        const val MAX_RESULT = 8_000
        const val MAX_EVIDENCE = 8
        const val MAX_NEW_PER_DAY = 20
        const val MAX_SYSTEM_PER_DAY = 4
        const val MAX_OPEN = 15
        const val MAX_SUPPRESSED = 200
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val RECENT_MS = 14 * DAY_MS
        const val HISTORY_MS = 90 * DAY_MS
        const val MAX_LIFETIME_MS = 90 * DAY_MS

        /** A stable key for "the same thing": letters and digits in any script, joined by dashes. */
        fun topicKey(text: String): String = text.lowercase().trim()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(80)

        /** An ISO instant, an offset date-time, a local date-time or a date, read in [zone]. Null when unreadable. */
        fun parseTime(text: String?, zone: ZoneId): Long? {
            val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toLongOrNull()?.let { return it }
            return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(value).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 }.getOrNull()
        }
    }
}
