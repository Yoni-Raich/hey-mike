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
