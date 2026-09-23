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
import java.time.ZoneId

/** One heading in the side panel's chat list and the chats under it, newest first. */
data class ChatDayGroup(val label: String, val sessions: List<ChatSession>)

/**
 * Chats by the day they were last active: Today, Yesterday, Earlier this week,
 * Older. A date on every row was noise; the heading carries it once.
 */
object ChatDayGroups {

    fun group(sessions: List<ChatSession>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ChatDayGroup> {
        val today = day(now, zone)
        return sessions
            .sortedByDescending { it.updatedAt }
            .groupBy { label(day(it.updatedAt, zone), today) }
            .map { (label, chats) -> ChatDayGroup(label, chats) }
    }

    /** Only chats whose title contains [query], ignoring case. A blank query keeps every chat. */
    fun filter(sessions: List<ChatSession>, query: String): List<ChatSession> {
        val needle = query.trim()
        if (needle.isEmpty()) return sessions
        return sessions.filter { it.title.contains(needle, ignoreCase = true) }
    }

    private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun label(day: LocalDate, today: LocalDate): String = when {
        !day.isBefore(today) -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> "Earlier this week"
        else -> "Older"
    }
}
