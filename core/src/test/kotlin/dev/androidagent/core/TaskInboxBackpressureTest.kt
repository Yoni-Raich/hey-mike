/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskInboxBackpressureTest {
    private val clock = 1_800_000_000_000L
    private fun signal(id: String) = TaskSignal(id, "chat", text = "Pending input $id", at = clock, capturedAt = clock)

    @Test fun calendarBurstWaitsAtCapacityAndUsesTheFirstFreedSlot() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store).apply { load() }
        val pending = (1..SignalInbox.MAX_PENDING).map { signal("s$it") }
        inbox.capturePage("chat", 0, 1, pending)
        val calendar = CalendarSignalBuffer()
        repeat(1_000) { calendar.changed() }
        assertNull(calendar.capture(inbox, clock, clock, enabled = true))
        assertEquals(pending, store.loadSignals())
        assertEquals(1, store.sourceCursor("chat"))

        inbox.processed(pending.take(DiscoverySchedule.MAX_BATCH).map { it.id })
        val captured = calendar.capture(inbox, clock + 60_000, clock, enabled = true)!!
        assertEquals("calendar", captured.source)
        repeat(10) { assertNull(calendar.capture(inbox, clock + 60_000, clock, enabled = true)) }
        assertEquals(1, store.loadSignals().count { it.source == "calendar" })
        assertTrue(store.loadSignals().containsAll(pending.drop(DiscoverySchedule.MAX_BATCH)))
    }

    @Test fun aChangeDuringACalendarWriteRemainsPendingForTheNextPass() = runTest {
        val calendar = CalendarSignalBuffer()
        val backing = InMemoryTaskBoardStore()
        val store = object : TaskBoardStore by backing {
            override suspend fun insertSignal(signal: TaskSignal): Boolean {
                calendar.changed()
                return backing.insertSignal(signal)
            }
        }
        val inbox = SignalInbox(store).apply { load() }
        calendar.changed()
        val first = calendar.capture(inbox, clock, clock, enabled = true)!!
        inbox.processed(listOf(first.id))
        assertNotNull(calendar.capture(inbox, clock + 60_000, clock, enabled = true))
    }

    @Test fun calendarDeferralAlsoHandlesStorageThatFilledAfterTheInboxSnapshot() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store).apply { load() }
        val pending = (1..SignalInbox.MAX_PENDING).map { signal("s$it") }
        store.saveSignals(pending)
        val calendar = CalendarSignalBuffer().apply { changed() }
        assertNull(calendar.capture(inbox, clock, clock, enabled = true))
        assertEquals(pending, store.loadSignals())
        store.deleteSignals(listOf(pending.first().id))
        assertNotNull(calendar.capture(inbox, clock + 60_000, clock, enabled = true))
    }

    @Test fun disabledCalendarCaptureWaitsAndNormalPollingStillRunsAfterSixHours() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore()).apply { load() }
        val calendar = CalendarSignalBuffer().apply { changed() }
        assertNull(calendar.capture(inbox, clock, clock, enabled = false))
        val first = calendar.capture(inbox, clock, clock, enabled = true)!!
        inbox.processed(listOf(first.id))
        assertNull(calendar.capture(inbox, clock + 60_000, clock, enabled = true))
        assertNotNull(calendar.capture(inbox, clock + CalendarSignalBuffer.POLL_INTERVAL_MS + 1, clock, enabled = true))
    }

    @Test fun calendarDeferralDoesNotHideCancellationOrOtherStorageFailures() = runTest {
        for (failure in listOf(CancellationException("Stop"), IllegalStateException("Database unavailable"))) {
            val store = object : TaskBoardStore by InMemoryTaskBoardStore() {
                override suspend fun insertSignal(signal: TaskSignal): Boolean = throw failure
            }
            val inbox = SignalInbox(store).apply { load() }
            assertSame(failure, runCatching { inbox.tryAdd(signal("s")) }.exceptionOrNull())
        }
    }

    @Test fun aReadyBacklogDrainsSoonerWithoutRaisingBatchOrDailyLimits() {
        val pending = (1..SignalInbox.MAX_PENDING).map { signal("s$it").copy(text = "x".repeat(SignalInbox.MAX_TEXT)) }
        val last = clock - DiscoverySchedule.BACKLOG_INTERVAL_MS
        val plan = DiscoverySchedule.plan(pending, clock, last, 28, 48, busy = false)
        assertTrue(plan.due)
        assertTrue(plan.batch.size <= DiscoverySchedule.MAX_BATCH)
        assertTrue(plan.batch.sumOf { it.text.length } <= DiscoverySchedule.MAX_BATCH_CHARS)
        assertFalse(DiscoverySchedule.plan(pending, clock, clock - 1, 28, 48, busy = false).due)
        assertFalse(DiscoverySchedule.plan(pending, clock, last, 48, 48, busy = false).due)
        assertFalse(DiscoverySchedule.plan(pending, clock, last, 28, 48, busy = true).due)
        assertFalse(DiscoverySchedule.plan(pending.take(2), clock, last, 28, 48, busy = false).due)
    }

    @Test fun backlogRecoveryDoesNotBypassQuietTimeOrNotBefore() {
        val pending = (1..SignalInbox.MAX_PENDING).map { signal("s$it").copy(source = "notification") }
        assertFalse(DiscoverySchedule.plan(pending, clock, null, 0, 48, busy = false).due)
        assertFalse(DiscoverySchedule.plan(pending.map { it.copy(source = "chat", notBefore = clock + 60_000) },
            clock, null, 0, 48, busy = false).due)
    }
}
