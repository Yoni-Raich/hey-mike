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

package dev.androidagent.app.ui

import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ChatSession
import dev.androidagent.core.MikeChatNote
import dev.androidagent.core.MikeMemory
import dev.androidagent.core.MikeState
import dev.androidagent.core.MikeTask
import dev.androidagent.core.MikeTaskStatus
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MikeGlanceTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    // A Tuesday, 14:00.
    private val now = ZonedDateTime.of(2026, 10, 6, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private fun task(status: MikeTaskStatus, id: String = status.name, turnActive: Boolean = false, wakeAt: Long? = null) =
        MikeTask(id, "Task $id", "Do it", "chat-$id", status, nextStep = "next", result = "result", wakeAt = wakeAt, updatedAt = now, turnActive = turnActive)

    @Test fun tasksAreGroupedByWhatTheyNeedFromThePerson() {
        assertEquals(MikeTaskGroup.NEEDS_YOU, task(MikeTaskStatus.UNKNOWN).group())
        assertEquals(MikeTaskGroup.NEEDS_YOU, task(MikeTaskStatus.FAILED).group())
        assertEquals(MikeTaskGroup.WORKING, task(MikeTaskStatus.RUNNING).group())
        assertEquals(MikeTaskGroup.WORKING, task(MikeTaskStatus.QUEUED).group())
        assertEquals(MikeTaskGroup.WAITING, task(MikeTaskStatus.WAITING).group())
        assertEquals(MikeTaskGroup.READY, task(MikeTaskStatus.READY).group())
        assertEquals(MikeTaskGroup.READY, task(MikeTaskStatus.PAUSED).group())
        assertEquals(MikeTaskGroup.DONE, task(MikeTaskStatus.DONE).group())
        // A turn that recorded a wait has not ended yet: it is still working.
        assertEquals(MikeTaskGroup.WORKING, task(MikeTaskStatus.WAITING, turnActive = true).group())
    }

    @Test fun aWakeTimeReadsAsADayAndAnHour() {
        assertEquals("today at 19:00", wakeLabel(at(6, 19), now, zone, Locale.UK))
        assertEquals("tomorrow at 07:30", wakeLabel(at(7, 7, 30), now, zone, Locale.UK))
        assertEquals("Friday at 09:00", wakeLabel(at(9, 9), now, zone, Locale.UK))
        assertEquals("20 Oct at 09:00", wakeLabel(at(20, 9), now, zone, Locale.UK))
        // Android may hold a wake back; a time already past promises nothing.
        assertEquals("any moment", wakeLabel(now - 1, now, zone, Locale.UK))
        assertEquals("Wakes tomorrow at 07:30", task(MikeTaskStatus.WAITING, wakeAt = at(7, 7, 30)).statusLine(now, zone, Locale.UK))
        assertEquals("Waiting · no wake time set", task(MikeTaskStatus.WAITING).statusLine(now, zone, Locale.UK))
    }

    @Test fun aRowShowsTheResultOnceThereIsOneAndTheNextStepBefore() {
        assertEquals("result", task(MikeTaskStatus.DONE).snippet())
        assertEquals("next", task(MikeTaskStatus.WAITING).snippet())
        assertEquals("Do it", task(MikeTaskStatus.READY).snippet())
        // An interrupted task's next step is the app's warning; its row shows what it was asked instead.
        assertEquals("Do it", task(MikeTaskStatus.UNKNOWN).snippet())
        assertEquals("Task · needs you", task(MikeTaskStatus.UNKNOWN).chatLabel())
        assertEquals("Task · paused", task(MikeTaskStatus.PAUSED).chatLabel())
        assertEquals("Task · not started", task(MikeTaskStatus.READY).chatLabel())
    }

    @Test fun onlySafeStepsAreOffered() {
        assertTrue(task(MikeTaskStatus.READY).canRun())
        assertTrue(task(MikeTaskStatus.FAILED).canRun())
        // An interrupted task is retried only after its chat was read, never from a list.
        assertFalse(task(MikeTaskStatus.UNKNOWN).canRun())
        assertFalse(task(MikeTaskStatus.DONE).canRun())
        assertFalse(task(MikeTaskStatus.WAITING, turnActive = true).canRun())
        assertTrue(task(MikeTaskStatus.RUNNING).canPause())
        assertFalse(task(MikeTaskStatus.RUNNING).canRemove())
        assertTrue(task(MikeTaskStatus.DONE).canRemove())
        assertTrue(task(MikeTaskStatus.UNKNOWN).canRemove())
    }

    @Test fun theDrawerSaysWhatNeedsThePersonBeforeAnythingElse() {
        val memory = MikeMemory("k", "text", "fact", null, now)
        assertEquals(MikeGlance("Nothing yet", "None open", attention = false, working = false), mikeGlance(MikeState()))
        val busy = MikeState(memories = listOf(memory), tasks = listOf(task(MikeTaskStatus.RUNNING), task(MikeTaskStatus.READY), task(MikeTaskStatus.DONE)))
        assertEquals(MikeGlance("1 saved", "1 working", attention = false, working = true), mikeGlance(busy))
        assertEquals("1 needs you", mikeGlance(busy.copy(tasks = busy.tasks + task(MikeTaskStatus.UNKNOWN))).tasks)
        assertEquals("2 need you", mikeGlance(busy.copy(tasks = busy.tasks + task(MikeTaskStatus.UNKNOWN) + task(MikeTaskStatus.FAILED))).tasks)
        // Stop holds every task; the way in has to say so.
        assertEquals(MikeGlance("1 saved", "On hold", attention = true, working = false), mikeGlance(busy.copy(paused = true)))
        assertEquals("1 open", mikeGlance(MikeState(tasks = listOf(task(MikeTaskStatus.PAUSED), task(MikeTaskStatus.DONE)))).tasks)
        // Held, with nothing left to hold, is not worth an amber word.
        assertEquals("None open", mikeGlance(MikeState(paused = true, tasks = listOf(task(MikeTaskStatus.DONE)))).tasks)
    }

    @Test fun aMemorySaysWhoPutItThere() {
        val chats = listOf(ChatSession("main", "Mike", 0, 0, isMike = true), ChatSession("trip", "Lisbon", 0, 0))
        assertEquals("Added by you", memorySource(MikeMemory("a", "t", "fact", null, 0), chats))
        assertEquals("Saved by Mike in “Lisbon”", memorySource(MikeMemory("a", "t", "fact", "trip", 0), chats))
        assertEquals("Saved by Mike in your conversation", memorySource(MikeMemory("a", "t", "fact", "main", 0), chats))
        // The chat it came from was deleted; the memory outlives it.
        assertEquals("Saved by Mike", memorySource(MikeMemory("a", "t", "fact", "gone", 0), chats))
    }

    @Test fun whatTheAppWroteForTheEngineIsNeverAMessageRow() {
        val task = task(MikeTaskStatus.DONE, id = "t")
        val rows = chatRows(
            listOf(
                ChatMessage("u", "s", "user", "hello", 0),
                ChatMessage("b1", "s", "user", MikeChatNote.brief(task), 1),
                ChatMessage("b2", "s", "user", MikeChatNote.brief(task), 2),
                ChatMessage("r", "s", "system", MikeChatNote.report(task), 3),
                ChatMessage("h", "s", "user", MikeChatNote.handoff(task), 4),
                ChatMessage("d", "s", "system", "12s thinking · 3s on the phone across 2 calls.", 5),
            ),
            running = false,
        )
        assertEquals(listOf(false, true, true, true, true, false), rows.map { it is MikeNoteRow })
        // The same brief a second time is a line, not the whole task again.
        assertEquals(listOf(false, true, false, false), rows.filterIsInstance<MikeNoteRow>().map { it.repeated })
    }
}
