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

import dev.androidagent.core.CompactResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompactAndContextTest {
    // A real `/context` answer from Claude Code, with the long tables cut.
    private val answer = """
        ## Context Usage

        **Model:** claude-sonnet-5-5
        **Tokens:** 403.7k / 1m (40%)

        ### Estimated usage by category

        | Category | Tokens | Percentage |
        |----------|--------|------------|
        | System prompt | 4.6k | 0.5% |
        | Messages | 313.2k | 31.3% |
        | Free space | 563.3k | 56.3% |

        ### MCP Tools

        | Tool | Server | Tokens |
        |------|--------|--------|
        | mcp__mike__tap | mike | 96 |
    """.trimIndent()

    @Test fun contextAnswerReadsAsAGaugeWithItsParts() {
        val usage = parseContextUsage(answer)!!
        assertEquals("claude-sonnet-5-5", usage.model)
        assertEquals("403.7k", usage.used)
        assertEquals("1m", usage.window)
        assertEquals(40.0, usage.percent, 0.0)
        assertEquals(listOf("System prompt", "Messages", "Free space"), usage.categories.map { it.name })
        assertEquals(31.3, usage.categories[1].percent!!, 0.0)
    }

    @Test fun otherTextIsNotAContextAnswer() {
        assertNull(parseContextUsage("Here is the Context Usage you asked for"))
        assertNull(parseContextUsage("## Context Usage\n\nnothing measured"))
        assertNotNull(parseContextUsage("\n  $answer"))
    }

    @Test fun tokensAndTimesAreWrittenShort() {
        assertEquals("911", formatTokens(911))
        assertEquals("7.0k", formatTokens(7_015))
        assertEquals("1.2m", formatTokens(1_200_000))
        assertEquals("12s", elapsedLabel(12_400))
        assertEquals("1m 05s", elapsedLabel(65_000))
    }

    @Test fun theNoteAfterACompactionSaysWhatShrankAndHowLong() {
        assertEquals(
            "Chat compacted · 7.0k → 911 tokens (−87%) · 12s",
            compactedNote(CompactResult(7_015, 911), 12_000),
        )
        assertEquals("Chat compacted · Token reduction not reported · 3s", compactedNote(null, 3_000))
        assertEquals("Chat compacted · Token reduction not reported · 3s", compactedNote(CompactResult(100, 200), 3_000))
    }
}
