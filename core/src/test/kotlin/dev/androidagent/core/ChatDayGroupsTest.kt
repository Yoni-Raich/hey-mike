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

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDayGroupsTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 9, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun chat(id: String, title: String, time: Long) = ChatSession(id, title, time, time)

    private val now = at(23, 10)

    @Test fun chatsFallUnderTheirDayNewestFirst() {
        val groups = ChatDayGroups.group(
            listOf(
                chat("old", "Old", at(1, 9)),
                chat("today-early", "Early", at(23, 1)),
                chat("yesterday", "Yesterday", at(22, 23)),
                chat("today-late", "Late", at(23, 9)),
                chat("week", "Week", at(19, 12)),
            ),
            now,
            zone,
        )
        assertEquals(listOf("Today", "Yesterday", "Earlier this week", "Older"), groups.map { it.label })
        assertEquals(listOf("today-late", "today-early"), groups[0].sessions.map { it.id })
    }

    @Test fun midnightIsTheBoundaryInTheLocalZone() {
        val groups = ChatDayGroups.group(listOf(chat("a", "A", at(23, 0)), chat("b", "B", at(22, 23))), now, zone)
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.label })
    }

    @Test fun searchMatchesTitlesIgnoringCase() {
        val chats = listOf(chat("1", "Set an alarm", now), chat("2", "Dark mode", now))
        assertEquals(listOf("2"), ChatDayGroups.filter(chats, "  dark ").map { it.id })
        assertEquals(chats, ChatDayGroups.filter(chats, " "))
    }
}
