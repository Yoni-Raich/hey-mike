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

import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RunPhase
import dev.androidagent.core.RunState
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionNoticesTest {
    private val approval = EngineEvent.Approval("request-1", "send_message", buildJsonObject {})
    private val working = RunState(phase = RunPhase.THINKING, status = "Reading the page")
    private val self: (String) -> String = { it }

    @Test fun everyConversationAtWorkHasARowAndAChildCountsAsItsSource() {
        val states = linkedMapOf(
            "child" to RunState(phase = RunPhase.TOOL, status = "Searching"),
            "source" to working,
            "other" to RunState(phase = RunPhase.THINKING, approval = approval),
            "idle" to RunState(),
            "backstage" to working,
        )
        val rows = SessionNotices.running(states, owner = { if (it == "child") "source" else it }, hidden = { it == "backstage" })
        assertEquals(mapOf("source" to "Reading the page", "other" to "Waiting for your approval"), rows)
    }

    @Test fun aChildWorkingAloneStillShowsUnderItsSource() {
        val rows = SessionNotices.running(mapOf("child" to working), owner = { "source" }, hidden = { false })
        assertEquals(mapOf("source" to "Reading the page"), rows)
    }

    @Test fun allConcurrentApprovalsAreRetainedEvenWhenTheyShareAnOwner() {
        val second = EngineEvent.Approval("request-2", "open_intent", buildJsonObject {})
        val states = mapOf(
            "chat" to RunState(phase = RunPhase.TOOL, approval = approval),
            "child" to RunState(phase = RunPhase.TOOL, approval = second),
        )
        assertEquals(
            mapOf("request-1" to ("source" to approval), "request-2" to ("source" to second)),
            SessionNotices.approvalsDue(states) { "source" },
        )
    }

    @Test fun onlyARunThatEndedOnItsOwnIsNews() {
        assertEquals(SessionNotices.Ending.DONE, SessionNotices.ending(RunState(status = "Ready")))
        assertEquals(SessionNotices.Ending.FAILED, SessionNotices.ending(RunState(phase = RunPhase.ERROR, status = "No connection")))
        assertNull(SessionNotices.ending(RunState(status = "Stopped")))
        assertNull(SessionNotices.ending(RunState(status = "Interrupted")))
        assertNull(SessionNotices.ending(RunState(status = "Voice ended")))
    }

    @Test fun teamUpdatesNeverReachTheShadeButTheUsersOwnRequestsDo() {
        val done = RunState(status = "Ready", runId = "r")
        val fed = done.copy(agentInput = true)
        val failed = RunState(phase = RunPhase.ERROR, status = "No connection", agentInput = true)
        val sources = mapOf("child" to "chat", "manager-child" to "manager")
        val owner: (String) -> String = { sources[it] ?: it }
        val hidden: (String) -> Boolean = { it == "scout" }
        val team: (String) -> Boolean = { it == "manager" }
        fun announced(chat: String, outcome: RunState) = SessionNotices.announced(chat, outcome, owner, hidden, team)
        // Scout and other backstage passes: never, done or failed, whatever their input.
        assertNull(announced("scout", fed)); assertNull(announced("scout", failed)); assertNull(announced("scout", done))
        // A delegated child: its source chat reports it, so the child itself is never announced.
        assertNull(announced("child", done)); assertNull(announced("manager-child", done))
        // The Manager chat working on a briefing or child updates is team work...
        assertNull(announced("manager", fed)); assertNull(announced("manager", failed))
        // ...but the user's own request in the Manager chat still tells them it finished.
        assertEquals(SessionNotices.Ending.DONE, announced("manager", done))
        // An ordinary chat keeps its notification, also for a child result the user asked for: one, on the source.
        assertEquals(SessionNotices.Ending.DONE, announced("chat", done))
        assertEquals(SessionNotices.Ending.DONE, announced("chat", fed))
        assertEquals(SessionNotices.Ending.FAILED, announced("chat", failed))
        assertNull(announced("chat", RunState(status = "Stopped")))
    }

    @Test fun teamProgressHasNoRowButApprovalsFromAnyChatStillDo() {
        val states = linkedMapOf(
            "manager" to working.copy(agentInput = true),
            "scout" to RunState(phase = RunPhase.TOOL, approval = approval, agentInput = true),
            "chat" to working.copy(agentInput = true),
        )
        val rows = SessionNotices.running(states, self, hidden = { it == "scout" }, team = { it == "manager" })
        assertEquals(mapOf("chat" to "Reading the page"), rows)
        // An approval is something the user must answer: it is never classed as a team update.
        assertEquals(setOf("request-1"), SessionNotices.approvalsDue(states, self).keys)
    }

    @Test fun aLongReplyIsCutForTheNotification() {
        assertEquals("One\nTwo", SessionNotices.preview("  One\n\n\nTwo "))
        assertEquals("aaaa…", SessionNotices.preview("a".repeat(50), limit = 5))
    }

    @Test fun permissionsSurviveEncoding() {
        val saved = ChatPermissions(setOf("b", "a"), PermissionLevel.FULL)
        assertEquals(saved, KeystorePermissionStore.decode(KeystorePermissionStore.encode(saved)))
        assertEquals(PermissionLevel.ASK, saved.of("c"))
        assertEquals(PermissionLevel.FULL, saved.of("a"))
    }
}
