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

import dev.androidagent.core.ChatSession
import dev.androidagent.core.MikeMemory
import dev.androidagent.core.MikeState
import dev.androidagent.core.MikeTask
import dev.androidagent.core.MikeTaskStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// What Mike's sheet and his drawer entry say, kept apart from how they are
// drawn so the wording can be tested without a screen.

/**
 * Where a task sits in the sheet. Grouped by what it needs from the person,
 * the way the rules sheet does it: nobody opens this to read eight statuses,
 * they open it to see whether anything is waiting on them.
 */
internal enum class MikeTaskGroup(val title: String) {
    NEEDS_YOU("Needs you"),
    WORKING("Working"),
    WAITING("Waiting"),
    READY("Ready when you are"),
    DONE("Done"),
}

internal fun MikeTask.group(): MikeTaskGroup = when {
    status == MikeTaskStatus.UNKNOWN || status == MikeTaskStatus.FAILED -> MikeTaskGroup.NEEDS_YOU
    // A turn that already recorded a wait is still a turn in progress until it ends.
    turnActive || status == MikeTaskStatus.RUNNING || status == MikeTaskStatus.QUEUED -> MikeTaskGroup.WORKING
    status == MikeTaskStatus.WAITING -> MikeTaskGroup.WAITING
    status == MikeTaskStatus.DONE -> MikeTaskGroup.DONE
    else -> MikeTaskGroup.READY
}

/** One line under a task's name: where it stands, in words a person would use. */
internal fun MikeTask.statusLine(now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String = when {
    status == MikeTaskStatus.UNKNOWN -> "Interrupted · check before retrying"
    status == MikeTaskStatus.FAILED -> "Did not finish"
    status == MikeTaskStatus.QUEUED -> "Starting"
    turnActive || status == MikeTaskStatus.RUNNING -> "Working now"
    status == MikeTaskStatus.WAITING -> wakeAt?.let { "Wakes " + wakeLabel(it, now, zone, locale) } ?: "Waiting · no wake time set"
    status == MikeTaskStatus.DONE -> "Done"
    status == MikeTaskStatus.READY -> "Not started"
    else -> "Paused"
}

/** The text most worth reading on a task's row: its result once it has one, else what comes next. */
internal fun MikeTask.snippet(): String = when (status) {
    MikeTaskStatus.DONE, MikeTaskStatus.FAILED -> result.ifBlank { nextStep }
    // An interrupted task's next step is the app's own warning, which its status line already gives.
    MikeTaskStatus.READY, MikeTaskStatus.UNKNOWN -> instruction
    else -> nextStep.ifBlank { result.ifBlank { instruction } }
}.trim()

/** Under a task chat's name in the library, so it is not taken for a chat the person started. */
internal fun MikeTask.chatLabel(): String = "Task · " + when (group()) {
    MikeTaskGroup.NEEDS_YOU -> "needs you"
    MikeTaskGroup.WORKING -> "working"
    MikeTaskGroup.WAITING -> "waiting"
    MikeTaskGroup.DONE -> "done"
    MikeTaskGroup.READY -> if (status == MikeTaskStatus.READY) "not started" else "paused"
}

internal fun MikeTask.canRun(): Boolean =
    !turnActive && status in setOf(MikeTaskStatus.READY, MikeTaskStatus.PAUSED, MikeTaskStatus.WAITING, MikeTaskStatus.FAILED)

internal fun MikeTask.canPause(): Boolean =
    status in setOf(MikeTaskStatus.RUNNING, MikeTaskStatus.QUEUED, MikeTaskStatus.WAITING)

internal fun MikeTask.canRemove(): Boolean =
    !turnActive && status !in setOf(MikeTaskStatus.RUNNING, MikeTaskStatus.QUEUED)

internal fun MikeTask.runLabel(): String = when (status) {
    MikeTaskStatus.READY -> "Start now"
    MikeTaskStatus.WAITING -> "Continue now"
    MikeTaskStatus.FAILED -> "Try again"
    else -> "Continue"
}

/** "today at 19:00", "tomorrow at 07:30", "Thursday at 07:30", "12 Oct at 07:30". */
internal fun wakeLabel(at: Long, now: Long, zone: ZoneId, locale: Locale): String {
    // Android may hold a wake back, so a time that has passed is not a promise.
    if (at <= now) return "any moment"
    val then = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = then.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return when (ChronoUnit.DAYS.between(today, then.toLocalDate())) {
        0L -> "today at $time"
        1L -> "tomorrow at $time"
        in 2L..6L -> then.format(DateTimeFormatter.ofPattern("EEEE", locale)) + " at $time"
        else -> then.format(DateTimeFormatter.ofPattern("d MMM", locale)) + " at $time"
    }
}

/** The kinds of memory the store accepts, in the order the sheet lists them. */
internal enum class MikeMemoryKind(val id: String, val title: String, val label: String, val hint: String) {
    FACT("fact", "Facts", "Fact", "Something that is true: a name, a place, how things are set up."),
    PREFERENCE("preference", "Preferences", "Preference", "How you like things done."),
    LESSON("lesson", "Lessons", "Lesson", "Something that worked, or did not, worth repeating next time.");

    companion object {
        fun of(id: String): MikeMemoryKind = entries.firstOrNull { it.id == id } ?: FACT
    }
}

/**
 * Who put a memory there. Mike saves on his own during a chat, so a memory
 * says when that happened and where: something he wrote down unasked should
 * never look like something the person typed.
 */
internal fun memorySource(memory: MikeMemory, sessions: List<ChatSession>): String {
    val id = memory.sourceSessionId ?: return "Added by you"
    val chat = sessions.firstOrNull { it.id == id } ?: return "Saved by Mike"
    return if (chat.isMike) "Saved by Mike in your conversation"
    else "Saved by Mike in “" + chat.title.ifBlank { "Untitled chat" } + "”"
}

/** The two figures on Mike's drawer entry. [attention] turns the tasks figure amber. */
internal data class MikeGlance(val memory: String, val tasks: String, val attention: Boolean, val working: Boolean)

internal fun mikeGlance(state: MikeState): MikeGlance {
    val groups = state.tasks.groupingBy { it.group() }.eachCount()
    val needsYou = groups[MikeTaskGroup.NEEDS_YOU] ?: 0
    val working = groups[MikeTaskGroup.WORKING] ?: 0
    val open = state.tasks.size - (groups[MikeTaskGroup.DONE] ?: 0)
    // Stop holds every task, and nothing else on the way in says so.
    val held = state.paused && open > 0
    return MikeGlance(
        memory = when (val count = state.memories.size) {
            0 -> "Nothing yet"
            else -> "$count saved"
        },
        tasks = when {
            needsYou == 1 -> "1 needs you"
            needsYou > 1 -> "$needsYou need you"
            held -> "On hold"
            working > 0 -> "$working working"
            open > 0 -> "$open open"
            else -> "None open"
        },
        attention = needsYou > 0 || held,
        working = working > 0 && needsYou == 0 && !held,
    )
}
