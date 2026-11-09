/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SignalRowsTest {
    private val clock = 1_800_000_000_000L
    private val on = TaskBoardSettings(discoveryOn = true)
    private fun change(seq: Long, role: String, text: String, chat: String = "chat", phase: String? = null) =
        SignalRows.ChatCapture(SessionMessageChange(seq, ChatMessage("m$seq", chat, role, text, clock, phase = phase)),
            "Same title", ordinary = true, ownerChat = false, taskResult = false, quietUntil = clock + 900_000)

    @Test fun aThousandProgressMessagesOpenNothingAndOneRequestOpensOneTextFreeRow() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store, { clock }).apply { load() }
        val progress = (1L..1_000).map { change(it, "assistant", "Checking $it", phase = "commentary") }
        assertEquals(1_000, inbox.captureChats("feed", 0, progress, clock))
        assertTrue(inbox.pending.value.isEmpty())
        val user = change(1_001, "user", "Keep the original date")
        val final = change(1_002, "assistant", "Done. The original date is kept.", phase = "final_answer")
        inbox.captureChats("feed", 1_000, listOf(user) + progress.mapIndexed { i, it ->
            it.copy(change = it.change.copy(sequence = 1_002L + i))
        } + final.copy(change = final.change.copy(sequence = 2_002)), clock)
        val row = store.loadSignals().single()
        assertEquals("", row.text)
        assertTrue(row.items.isEmpty())
        val text = SignalRows.chatText(row, listOf(user.change.message) + progress.map { it.change.message } + final.change.message)
        assertTrue(text.contains("Keep the original date"))
        assertTrue(text.contains("original date is kept"))
        assertFalse(text.contains("Checking"))
        assertEquals(SignalLane.BACKGROUND, row.lane)
        assertEquals(clock + 900_000, row.notBefore)
    }

    @Test fun oldHistoryAndChildTaskPromptsDoNotBecomeOwnerRequests() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore(), { clock }).apply { load() }
        val old = change(1, "user", "An old request").let { it.copy(change = it.change.copy(message = it.change.message.copy(createdAt = clock - 1))) }
        val child = change(2, "user", "Generated child instructions").copy(ordinary = false)
        inbox.captureChats("feed", 0, listOf(old, child), clock)
        assertTrue(inbox.pending.value.isEmpty())
        inbox.captureChats("feed", 2, listOf(change(3, "user", "Hello", "a"), change(4, "user", "Hello", "b")), clock)
        assertEquals(2, inbox.pending.value.size) // Equal titles do not merge separate chats.
    }

    @Test fun ownerCorrectionsAndMarkedTaskResultsAreImmediateWithoutAProgressLoop() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store, { clock }).apply { load() }
        inbox.captureChats("feed", 0, listOf(change(1, "assistant", "Still working").copy(ordinary = false, taskResult = true)), clock)
        assertTrue(inbox.pending.value.isEmpty())
        inbox.captureChats("feed", 1, listOf(
            change(2, "assistant", "File is ready", "task", "final_answer").copy(ordinary = false, taskResult = true),
            change(3, "user", "Do not track this", "manager").copy(ordinary = false, ownerChat = true)), clock)
        assertTrue(inbox.pending.value.all { it.lane == SignalLane.IMMEDIATE })
        assertEquals("Do not track this", store.loadEvents().single().note)
        assertTrue(store.loadSignals().all { it.text.isEmpty() })
    }

    @Test fun aRepostedAppStatusStaysOneRowAndDoesNotReturnAfterProcessing() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store, { clock }).apply { load() }
        repeat(1_000) { i ->
            inbox.capture(CapturedMessage("alerts.app", "Service", null, "Waiting for alerts", clock + i, false), on, "mike")
        }
        val row = inbox.pending.value.single()
        assertEquals(SignalLane.BACKGROUND, row.lane)
        assertEquals(1, row.items.size)
        val stat = store.loadSourceStats().single()
        assertEquals(1_000, stat.received)
        assertEquals(1, stat.distinct)
        inbox.processed(listOf(row.id))
        assertNull(inbox.capture(CapturedMessage("alerts.app", "Service", null, "Waiting for alerts", clock + 9_999, false), on, "mike"))
        assertTrue(inbox.pending.value.isEmpty())
        assertFalse(stat.toString().contains("Waiting for alerts"))
    }

    @Test fun notificationOverflowStaysInsideItsOwnConversationAndPreservesEqualWordsAtDifferentTimes() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore(), { clock }).apply { load() }
        fun message(who: String, text: String, at: Long) = CapturedMessage("com.whatsapp", who, who, text, at, false, fromPerson = true)
        val other = inbox.capture(message("Other", "Keep this", clock), on, "mike")!!
        repeat(30) { i -> inbox.capture(message("Dana", "Same words", clock + i), on, "mike") }
        val dana = inbox.pending.value.single { it.conversation == "Dana" }
        assertEquals(20, dana.items.size)
        assertEquals(10, dana.omitted)
        assertEquals(other, inbox.pending.value.single { it.id == other.id })
    }

    @Test fun peopleAndOwnerFeedbackHaveRoomAndBudgetWhenBackgroundIsFull() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore(), { clock }).apply { load() }
        repeat(SignalInbox.MAX_PENDING - SignalInbox.RESERVED_PEOPLE_ROWS) { i ->
            inbox.capture(CapturedMessage("app.$i", "Status", null, "New", clock, false), on, "mike")
        }
        assertTrue(runCatching { inbox.capture(CapturedMessage("overflow.app", null, null, "New", clock, false), on, "mike") }.exceptionOrNull() is SignalInboxFullException)
        inbox.capture(CapturedMessage("com.whatsapp", "Dana", "Dana", "A fresh request", clock, false, fromPerson = true), on, "mike")
        inbox.captureChats("feed", 0, listOf(change(1, "user", "A correction", "manager").copy(ownerChat = true, ordinary = false)), clock)
        val plan = DiscoverySchedule.plan(inbox.pending.value, clock + 3_600_001, null, 24, 48, false, backgroundPassesToday = 24)
        assertTrue(plan.due)
        assertEquals(SignalLane.IMMEDIATE, plan.batch.first().lane)
        assertTrue(plan.batch.none { it.lane == SignalLane.BACKGROUND })
        assertFalse(DiscoverySchedule.plan(inbox.pending.value, clock + 3_600_001, null, 48, 48, false).due)
        val background = inbox.pending.value.filter { it.lane == SignalLane.BACKGROUND }
        assertTrue(DiscoverySchedule.plan(background, clock + 3_600_001, null, 24, 48, false, backgroundPassesToday = 24).backgroundLimited)
        assertFalse(DiscoverySchedule.plan(background, clock + 3_600_001, clock + 3_600_001 - 60_000, 1, 48, false).due)
    }

    @Test fun migrationRemovesAutomaticHistoryPointersButKeepsNotificationsAndOwnerFeedback() {
        fun old(id: String, sender: String, session: String, feedback: Boolean = false) =
            TaskSignal(id, "chat", conversation = "Old chat", sender = sender, text = "Old text", at = clock - 1,
                capturedAt = clock, sessionId = session, sourceVerified = true, ownerFeedback = feedback)
        val notification = TaskSignal("n", "notification", app = "alerts.app", text = "Status", at = clock, capturedAt = clock, sourceVerified = true)
        val migration = SignalRows.migrate(listOf(old("a", "Mike", "a"), old("b", "User", "b"), old("c", "User", "manager", true), notification), clock, clock) { false }
        assertEquals(setOf("a", "b", "c", "n"), migration.remove.toSet())
        assertEquals(2, migration.rows.size)
        assertEquals("", migration.rows.single { it.sessionId == "manager" }.text)
        assertEquals("Status", migration.rows.single { it.app != null }.items.single().text)
    }

    @Test fun aNewSourceExclusionAlsoRemovesAnOldTitleFromAnAppRow() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore(), { clock }).apply { load() }
        inbox.capture(CapturedMessage("app", "Keep", null, "Useful", clock, false), on, "mike")
        inbox.capture(CapturedMessage("app", "Remove", null, "Excluded words", clock + 1, false), on, "mike")
        inbox.pruneSources(on.copy(excludedConversations = setOf("app|Remove")))
        assertEquals(listOf("Useful"), inbox.pending.value.single().items.map { it.text })
    }

    @Test fun ownerInputCanPassAFullBackgroundFeedWithoutAcknowledgingTheBlockedRequest() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store, { clock }).apply { load() }
        repeat(SignalInbox.MAX_PENDING - SignalInbox.RESERVED_PEOPLE_ROWS) { index ->
            inbox.add(TaskSignal("bg$index", "chat", text = "", at = clock, capturedAt = clock,
                sessionId = "background$index", lane = SignalLane.BACKGROUND))
        }
        val blocked = change(1, "user", "A regular request", "ordinary")
        val correction = change(2, "user", "A correction", "manager").copy(ownerChat = true, ordinary = false)
        // The priority reader crosses regular input without consuming it.
        inbox.captureChats("priority", 0, listOf(blocked.copy(ordinary = false), correction), clock)
        assertEquals(0, inbox.captureChats("feed", 0, listOf(blocked, correction), clock))
        assertEquals(0, store.sourceCursor("feed"))
        assertEquals(2, store.sourceCursor("priority"))
        assertEquals(1, store.loadEvents().size)
        inbox.processed(listOf("bg0"))
        inbox.captureChats("feed", 0, listOf(blocked, correction), clock)
        assertEquals(2, store.sourceCursor("feed"))
        assertEquals(1, store.loadEvents().size)
        assertEquals(1, inbox.pending.value.count { it.sessionId == "manager" })
    }

    @Test fun explicitHistoryScanLabelsUnknownOldRepliesAndKeepsOnlyTheLastReplyForEachRequest() {
        val messages = listOf(ChatMessage("u", "chat", "user", "Prepare a report", clock),
            ChatMessage("p", "chat", "assistant", "I am checking", clock + 1),
            ChatMessage("r", "chat", "assistant", "The report is ready", clock + 2))
        val row = TaskSignal("scan", "chat", text = "", at = clock, capturedAt = clock,
            sessionId = "chat", lane = SignalLane.BACKGROUND, reason = SignalRows.HISTORY_SCAN)
        val text = SignalRows.chatText(row, messages)
        assertFalse(text.contains("I am checking"))
        assertTrue(text.contains("The report is ready"))
        assertTrue(text.contains("final status unknown"))
    }

    @Test fun theSequenceBoundarySkipsMigratedHistoryButAdmitsANewEditToAnOldRequest() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore(), { clock }).apply { load() }
        val old = change(1, "user", "Old text").let { it.copy(change = it.change.copy(message = it.change.message.copy(createdAt = clock - 900_000_000))) }
        val edited = old.copy(change = old.change.copy(sequence = 2, message = old.change.message.copy(text = "A new correction")))
        inbox.captureChats("feed", 0, listOf(old, edited), clock, startSequence = 1)
        assertEquals(1, inbox.pending.value.size)
        assertEquals(clock - 900_000_000, inbox.pending.value.single().sinceAt)
    }
}
