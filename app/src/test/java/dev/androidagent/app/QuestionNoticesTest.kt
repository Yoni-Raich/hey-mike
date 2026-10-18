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

package dev.androidagent.app

import dev.androidagent.app.ui.videoLength
import dev.androidagent.core.UserQuestion
import dev.androidagent.core.RunState
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionNoticesTest {
    private val evening = UserQuestion("q1", "Which evening?", listOf("Friday", "Saturday", "Sunday"))
    private val name = UserQuestion("q2", "What name?")
    private val waiting = mapOf("chat-a" to evening, "chat-b" to name)

    @Test fun aSubagentQuestionUsesTheSourceChatAndIsNotNotifiedWhileItIsVisible() {
        val projected = QuestionNotices.waiting(mapOf("child" to RunState(question = evening)),
            owner = { "source" }, order = { 0 })
        assertEquals(mapOf("source" to evening), projected)
        assertEquals(emptyMap<String, UserQuestion>(), QuestionNotices.due(projected, true, "source"))
        assertEquals(projected, QuestionNotices.due(projected, false, "source"))
    }

    @Test fun sourceQuestionWinsThenTheFirstDispatchedChildWins() {
        val children = linkedMapOf("later" to RunState(question = name), "first" to RunState(question = evening))
        val owner: (String) -> String = { "source" }
        val order: (String) -> Int = { if (it == "first") 0 else 1 }
        assertEquals(evening, QuestionNotices.waiting(children, owner, order)["source"])
        assertEquals(name, QuestionNotices.waiting(children + ("source" to RunState(question = name)), owner, order)["source"])
    }

    @Test fun aQuestionWhoseCardIsOnScreenIsNotAlsoANotification() {
        assertEquals(setOf("chat-b"), QuestionNotices.due(waiting, appInFront = true, openChat = "chat-a").keys)
    }

    @Test fun outsideTheAppEveryWaitingQuestionIsANotification() {
        // The open chat only counts while the app is on screen.
        assertEquals(waiting.keys, QuestionNotices.due(waiting, appInFront = false, openChat = "chat-a").keys)
        assertEquals(waiting.keys, QuestionNotices.due(waiting, appInFront = true, openChat = null).keys)
    }

    @Test fun optionsAreNumberedSoANumberIsAnAnswer() {
        assertEquals("Which evening?\n1. Friday\n2. Saturday\n3. Sunday", QuestionNotices.body(evening))
        assertEquals("What name?", QuestionNotices.body(name))
        assertEquals("Saturday", evening.resolve(" 2 "))
        assertEquals("sunday works", evening.resolve("sunday works"))
        assertEquals("Sunday", evening.resolve("sunday"))
        // Not an option's number: the user's own words.
        assertEquals("7", evening.resolve("7"))
        assertEquals(null, evening.resolve("  "))
    }

    @Test fun aVideosLengthReadsLikeAClock() {
        assertEquals("0:07", videoLength(7_400))
        assertEquals("12:40", videoLength(760_000))
        assertEquals("1:02:03", videoLength(3_723_000))
    }
}
