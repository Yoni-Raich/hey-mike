package dev.androidagent.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest

class TaskBriefingDeliveryTest {
    private val prepared = TaskBriefing("id", "check:1", "Review the form", 1, emptyList())

    @Test fun crashRecoveryKeepsAnUnknownNotificationFromBeingRepeated() {
        val saved = prepared.copy(chatSavedAt = 2)
        val reserved = saved.copy(notificationAttemptedAt = 3)
        assertEquals(TaskBriefingDelivery.Action.SAVE_CHAT, TaskBriefingDelivery.next(prepared, true))
        // A crash after the atomic chat insert but before its receipt repeats only the insert.
        assertEquals(TaskBriefingDelivery.Action.SAVE_CHAT, TaskBriefingDelivery.next(prepared, true))
        assertEquals(TaskBriefingDelivery.Action.ATTEMPT_NOTIFICATION, TaskBriefingDelivery.next(saved, true))
        // A crash immediately before or after the OS call has the same honest outcome.
        assertEquals(TaskBriefingDelivery.Action.FINISH_UNKNOWN, TaskBriefingDelivery.next(reserved, true))
        assertEquals(TaskBriefingDelivery.Action.FINISH_UNKNOWN, TaskBriefingDelivery.next(reserved, false))
        assertEquals(TaskBriefingDelivery.Action.DONE, TaskBriefingDelivery.next(reserved.copy(deliveredAt = 4, notificationState = "posted"), true))
    }

    @Test fun obsoleteOrExcludedBriefingIsNotSent() {
        assertEquals(TaskBriefingDelivery.Action.CANCEL_OBSOLETE, TaskBriefingDelivery.next(prepared, false))
        assertEquals(TaskBriefingDelivery.Action.CANCEL_OBSOLETE, TaskBriefingDelivery.next(prepared.copy(chatSavedAt = 2), false))
    }

    @Test fun cancelledBeforeChatCanRearmAfterClockChangeButSavedChatCannotRepeat() = runTest {
        var clock = 200_000L
        val store = InMemoryTaskBoardStore()
        store.saveTask(MikeTask("task", "Form", "why", "plan", originKind = "chat", topicKey = "form",
            createdAt = 1, updatedAt = 1, status = TaskStatus.MINE, commitment = TaskCommitment("user", clock)))
        var board = TaskBoard(store, now = { clock }).apply { load() }
        val first = board.prepareBriefing("task", "Review the form", emptyList()).briefing!!
        clock -= 1
        board.delivered("task", first.id, "No longer due", "cancelled")
        board = TaskBoard(store, now = { clock }).apply { load() }
        clock += 1
        assertTrue(TaskFollowThrough.needsBrief(board.task("task")!!, clock))
        val retried = board.prepareBriefing("task", "Review the form", emptyList()).briefing!!
        board.briefingChatSaved("task", retried.id)
        clock -= 1
        board.delivered("task", retried.id, "No longer due", "cancelled")
        board = TaskBoard(store, now = { clock }).apply { load() }
        clock += 1
        assertFalse(TaskFollowThrough.needsBrief(board.task("task")!!, clock))
    }

    @Test fun knownUncalledNotificationCanDeferOrCancelWithoutBecomingUnknown() = runTest {
        val store = InMemoryTaskBoardStore()
        store.saveTask(MikeTask("task", "Form", "why", "plan", originKind = "chat", topicKey = "form",
            createdAt = 1, updatedAt = 1, status = TaskStatus.MINE, commitment = TaskCommitment("user", 100)))
        var board = TaskBoard(store, now = { 200L }).apply { load() }
        val brief = board.prepareBriefing("task", "Review the form", emptyList()).briefing!!
        board.briefingChatSaved("task", brief.id)
        assertTrue(board.beginBriefingNotification("task", brief.id))
        board.deferBriefingNotification("task", brief.id)
        board = TaskBoard(store, now = { 201L }).apply { load() }
        assertEquals(TaskBriefingDelivery.Action.ATTEMPT_NOTIFICATION, TaskBriefingDelivery.next(board.task("task")!!.briefing!!, true))
        assertTrue(board.beginBriefingNotification("task", brief.id))
        board.delivered("task", brief.id, "Cancelled before system call", "cancelled")
        board = TaskBoard(store, now = { 202L }).apply { load() }
        assertEquals("cancelled", board.task("task")!!.briefing!!.notificationState)
        assertEquals(TaskBriefingDelivery.Action.DONE, TaskBriefingDelivery.next(board.task("task")!!.briefing!!, false))
        assertFalse(board.beginBriefingNotification("task", brief.id))
    }
}
