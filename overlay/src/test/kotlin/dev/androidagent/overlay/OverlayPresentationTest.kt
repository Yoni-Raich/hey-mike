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

package dev.androidagent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPresentationTest {
    @Test fun lifecycleStatusesUseDistinctTones() {
        assertEquals(OverlayTone.ACTIVE, overlayTone("Starting"))
        assertEquals(OverlayTone.ACTIVE, overlayTone("Thinking"))
        assertEquals(OverlayTone.ACTIVE, overlayTone("Running · read ui"))
        assertEquals(OverlayTone.CONTROLLING, overlayTone("Controlling · tap"))
        assertEquals(OverlayTone.WAITING, overlayTone("Working · Approve in Hey Mike"))
        assertEquals(OverlayTone.STOPPING, overlayTone("Stopping"))
        assertEquals(OverlayTone.DONE, overlayTone("Done · Stopped"))
        assertEquals(OverlayTone.ERROR, overlayTone("Error · Overlay permission missing"))
    }

    @Test fun toolNamesReadAsWhatTheAgentIsDoing() {
        assertEquals("Tapping", overlayContent("Controlling · tap", null).headline)
        assertEquals("Typing", overlayContent("Controlling · type text", null).headline)
        assertEquals("Reading the screen", overlayContent("Working · read ui", null).headline)
        assertEquals("Opening a link", overlayContent("Controlling · open_intent", null).headline)
        assertEquals("Run workflow", overlayContent("Working · run workflow", null).headline)
        assertEquals("Working", overlayContent("Working", null).headline)
        assertEquals("On your screen", overlayContent("Controlling", null).headline)
    }

    @Test fun agentTextBecomesCommentaryAndOutlivesToolCalls() {
        val said = overlayContent("Working · I'll message Dana that you're late.", null)
        assertEquals("Working", said.headline)
        assertEquals("I'll message Dana that you're late.", said.commentary)

        val tapping = overlayContent("Controlling · tap", said.commentary)
        assertEquals("Tapping", tapping.headline)
        assertEquals("I'll message Dana that you're late.", tapping.commentary)
    }

    @Test fun engineActivityLinesStayOnTheHeadlineAndKeepWhatTheAgentSaid() {
        val said = "I'll message Dana that you're late."
        assertEquals("Working in session files", overlayContent("Working · Working in session files", said).headline)
        assertEquals(said, overlayContent("Working · Working in session files", said).commentary)
        assertEquals(said, overlayContent("Working · Working", said).commentary)
        assertEquals(said, overlayContent("Working · Updating session files", said).commentary)
    }

    @Test fun aNewRunStartsWithNoCommentary() {
        assertNull(overlayContent("Starting", "Left over from the last run").commentary)
    }

    @Test fun approvalAsksForTheApp() {
        val waiting = overlayContent("Working · Approve in Hey Mike", "Sending the message")
        assertTrue(waiting.needsApproval)
        assertEquals("Approve in Hey Mike", waiting.headline)
        assertEquals("Sending the message", waiting.commentary)
        assertFalse(overlayContent("Controlling · tap", null).needsApproval)
    }

    @Test fun endingsExplainThemselves() {
        assertEquals("Stopped", overlayContent("Done · Stopped", "Earlier words").commentary)
        assertEquals("Earlier words", overlayContent("Done", "Earlier words").commentary)
        assertEquals("Overlay permission missing", overlayContent("Error · Overlay permission missing", null).commentary)
    }

    @Test fun commentaryIsOnePlainLine() {
        assertEquals(
            "Plan Open the chat and send it.",
            oneLine("## Plan\n\n- Open the **chat**\n- and send `it`."),
        )
        // Cut at 220 characters, the trailing space dropped, then the ellipsis.
        assertEquals("word ".repeat(44).trimEnd() + "…", oneLine("word ".repeat(100)))
    }

    @Test fun cardKeepsTheMarkdownOfWhatTheAgentSaid() {
        assertEquals(
            "**Plan**\n- Open the **chat**\n- and send `it`.",
            cardMarkdown("## Plan\n\n- Open the **chat**\n- and send `it`.\n\n"),
        )
        // Cut at the last line break that fits, so no line ends half-marked.
        assertEquals("- one\n- two", cardMarkdown("- one\n- two\n- three", limit = 15))
        // One line longer than the limit is cut on it and says so.
        assertEquals("word ".repeat(4).trimEnd() + "…", cardMarkdown("word ".repeat(10), limit = 20))
    }
}
