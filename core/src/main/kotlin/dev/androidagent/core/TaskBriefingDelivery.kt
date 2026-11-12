package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Recovery decisions are based on durable receipts, not on whether a user saw a notice. */
object TaskBriefingDelivery {
    enum class Action { SAVE_CHAT, ATTEMPT_NOTIFICATION, FINISH_UNKNOWN, CANCEL_OBSOLETE, DONE }

    fun next(briefing: TaskBriefing, eligible: Boolean): Action = when {
        briefing.deliveredAt != null -> Action.DONE
        briefing.notificationAttemptedAt != null -> Action.FINISH_UNKNOWN
        !eligible -> Action.CANCEL_OBSOLETE
        briefing.chatSavedAt == null -> Action.SAVE_CHAT
        else -> Action.ATTEMPT_NOTIFICATION
    }

    /**
     * A follow-up time or deadline on a card the user took on is a reminder they asked for, so it
     * may post its quiet notification. Every other briefing (a proposal, waiting, review, failed or
     * resolved status) is a team update: saved in the Manager chat and on the board, never posted.
     */
    fun reminder(briefing: TaskBriefing): Boolean =
        briefing.occurrence.startsWith("check:") || briefing.occurrence.startsWith("deadline:")
}

/** The Manager's durable outbox. The board lock orders card and policy edits with chat insertion;
 * the external notification call cannot share that transaction. */
class ManagerBriefingDelivery(
    private val board: TaskBoard,
    private val sessions: SessionStore,
    private val now: () -> Long,
    private val active: () -> Boolean,
    private val taskAllowed: (MikeTask) -> Boolean,
    private val sourceAllowed: (TaskSourceRef) -> Boolean,
    private val occurrenceAllowed: (MikeTask, TaskBriefing) -> Boolean = { _, _ -> true },
    private val quietUntil: suspend (MikeTask) -> Long?,
    private val recorded: suspend (MikeTask) -> Unit,
    /** Posts the quiet notification for a [reminder][TaskBriefingDelivery.reminder]; team updates never reach it. */
    private val notify: suspend (String, String, String, String) -> Pair<String, String>,
    private val onFailure: (Exception) -> Unit,
) {
    private var scanOffset = 0

    // The caller serializes scans. Board mutations use the board's own lock.
    suspend fun deliver(manager: String) {
        val pending = board.tasks.value.filter { it.briefing != null && it.briefing.deliveredAt == null }
        if (pending.isEmpty()) { scanOffset = 0; return }
        val start = scanOffset.mod(pending.size)
        val batch = (0 until minOf(50, pending.size)).map { pending[(start + it) % pending.size] }
        scanOffset = (start + batch.size) % pending.size
        var notificationAttempts = 0
        batch.forEach { task ->
            try {
                if (notificationAttempts >= 3) return@forEach
                val briefing = task.briefing!!
                val messageId = "manager-brief:${briefing.id}"
                fun matchesMessage(message: ChatMessage): Boolean = message.role == "assistant" &&
                    if (briefing.title != null) message.text == "${briefing.title}\n\n${briefing.text}"
                    else message.text.endsWith("\n\n${briefing.text}")
                suspend fun messages() = withContext(Dispatchers.IO) { sessions.messages(manager).first() }
                fun eligible(): Boolean {
                    val current = board.task(task.id) ?: return false
                    return current.briefing?.id == briefing.id && taskAllowed(current) && occurrenceAllowed(current, briefing) &&
                        briefing.sourceRefs.all(sourceAllowed) &&
                        briefing.occurrence == TaskFollowThrough.occurrence(current, now())
                }
                suspend fun finishObsolete() {
                    var current = board.task(task.id)?.briefing?.takeIf { it.id == briefing.id } ?: return
                    if (current.chatSavedAt == null) {
                        val saved = messages().firstOrNull { it.id == messageId }
                        if (saved != null) {
                            check(matchesMessage(saved)) { "Existing briefing message does not match its receipt." }
                            board.briefingChatSaved(task.id, briefing.id)
                            current = board.task(task.id)?.briefing?.takeIf { it.id == briefing.id } ?: return
                        }
                    }
                    if (current.notificationAttemptedAt != null)
                        board.delivered(task.id, briefing.id, "Saved in Manager chat; notification outcome is unknown after interruption.", "unknown")
                    else board.delivered(task.id, briefing.id, "Not sent: this briefing is no longer current or its source is unavailable.", "cancelled")
                }
                val action = TaskBriefingDelivery.next(briefing, eligible())
                when (action) {
                    TaskBriefingDelivery.Action.DONE -> return@forEach
                    TaskBriefingDelivery.Action.FINISH_UNKNOWN, TaskBriefingDelivery.Action.CANCEL_OBSOLETE -> {
                        finishObsolete(); return@forEach
                    }
                    else -> Unit
                }
                if (quietUntil(task) != null || !active()) return@forEach
                if (action == TaskBriefingDelivery.Action.SAVE_CHAT) {
                    val legacy = if (briefing.title == null) messages().firstOrNull { it.id == messageId } else null
                    if (legacy != null) check(matchesMessage(legacy)) { "Existing briefing message does not match its receipt." }
                    if (!active()) return@forEach
                    val saved = board.appendBriefingIfCurrent(task.id, briefing.id,
                        allowed = { current, brief -> active() && taskAllowed(current) && occurrenceAllowed(current, brief) && brief.sourceRefs.all(sourceAllowed) }) {
                        sessions.appendOnce(legacy ?: ChatMessage(messageId, manager, "assistant",
                            "${briefing.title ?: task.title}\n\n${briefing.text}", now()))
                    }
                    if (!saved) { finishObsolete(); return@forEach }
                    recorded(task)
                }
                if (!active()) return@forEach
                if (!eligible()) { finishObsolete(); return@forEach }
                if (!TaskBriefingDelivery.reminder(briefing)) {
                    board.delivered(task.id, briefing.id, CHAT_ONLY_NOTE, CHAT_ONLY)
                    return@forEach
                }
                if (!board.beginBriefingNotification(task.id, briefing.id)) {
                    val current = board.task(task.id)?.briefing
                    if (current?.id == briefing.id && current.notificationAttemptedAt != null && current.deliveredAt == null)
                        board.delivered(task.id, briefing.id, "Saved in Manager chat; notification outcome is unknown after interruption.", "unknown")
                    return@forEach
                }
                if (!active()) { board.deferBriefingNotification(task.id, briefing.id); return@forEach }
                if (!eligible()) {
                    board.delivered(task.id, briefing.id, "Notification cancelled before the system call: briefing is no longer current or its source is unavailable.", "cancelled")
                    return@forEach
                }
                notificationAttempts++
                val (state, note) = notify(manager, briefing.title ?: task.title, briefing.text, briefing.id)
                board.delivered(task.id, briefing.id, note, state)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onFailure(e) }
        }
    }

    companion object {
        const val CHAT_ONLY = "chat_only"
        const val CHAT_ONLY_NOTE = "Saved in Manager chat. Team updates post no notification."
    }
}
