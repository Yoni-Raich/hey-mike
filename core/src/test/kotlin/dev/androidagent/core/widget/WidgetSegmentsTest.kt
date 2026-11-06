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

package dev.androidagent.core.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSegmentsTest {
    private val tiny = """{"body":[{"type":"text","text":"hi"}]}"""

    private fun fenced(body: String, language: String = WIDGET_FENCE, fence: String = "```") = "$fence$language\n$body\n$fence"

    @Test fun plainTextIsOnePart() {
        val parts = splitMessage("hello **world**", streaming = false)
        assertEquals(listOf<MessagePart>(MessagePart.Markdown("hello **world**")), parts)
    }

    @Test fun aWidgetSitsBetweenItsProse() {
        val text = "Here you go:\n\n${fenced(tiny)}\n\nTweak the sliders."
        val parts = splitMessage(text, streaming = false)
        assertEquals(3, parts.size)
        assertEquals(MessagePart.Markdown("Here you go:"), parts[0])
        assertTrue(parts[1] is MessagePart.Widget)
        assertEquals(MessagePart.Markdown("Tweak the sliders."), parts[2])
    }

    @Test fun twoWidgetsGetTheirOwnIndex() {
        val parts = splitMessage("${fenced(tiny)}\n\nand\n\n${fenced(tiny)}", streaming = false)
        val widgets = parts.filterIsInstance<MessagePart.Widget>()
        assertEquals(listOf(0, 1), widgets.map { it.index })
    }

    @Test fun anOpenBlockWhileStreamingIsPendingNotRawJson() {
        val partial = "Building it:\n```mike-widget\n{\"body\":[{\"type\":\"te"
        val parts = splitMessage(partial, streaming = true)
        assertEquals(2, parts.size)
        assertEquals(MessagePart.Markdown("Building it:"), parts[0])
        assertEquals(MessagePart.Pending, parts[1])
    }

    @Test fun anOpenBlockInAFinishedMessageStaysVisibleAsBroken() {
        val parts = splitMessage("```mike-widget\n{\"body\":[", streaming = false)
        val broken = parts.single() as MessagePart.Broken
        assertTrue(broken.reason.contains("never closed"))
        assertTrue(broken.source.contains("\"body\""))
    }

    @Test fun aClosedBlockWithBadJsonIsBrokenWithAReason() {
        val parts = splitMessage(fenced("{\"body\": nope nope"), streaming = true)
        val broken = parts.single() as MessagePart.Broken
        assertTrue(broken.reason.isNotBlank())
        assertTrue(broken.source.contains("nope"))
    }

    @Test fun aClosedBlockStreamingThenFinishingBecomesAWidget() {
        val full = "Text\n${fenced(tiny)}"
        val cut = full.dropLast(5)
        assertTrue(splitMessage(cut, streaming = true).last() is MessagePart.Pending)
        assertTrue(splitMessage(full, streaming = true).last() is MessagePart.Widget)
    }

    @Test fun otherCodeBlocksAreLeftAlone() {
        val text = "```kotlin\nval x = 1\n```\n\nand\n\n```json\n{\"a\":1}\n```"
        assertEquals(listOf<MessagePart>(MessagePart.Markdown(text)), splitMessage(text, streaming = false))
    }

    @Test fun aCodeBlockThatMentionsTheFenceIsStillProse() {
        val text = "Use it like this:\n\n````markdown\n```mike-widget\n$tiny\n```\n````\n\nDone."
        val parts = splitMessage(text, streaming = false)
        assertEquals(1, parts.size)
        assertTrue(parts.single() is MessagePart.Markdown)
        assertEquals(text, (parts.single() as MessagePart.Markdown).text)
    }

    @Test fun aTildeFenceAndUppercaseLanguageWork() {
        val parts = splitMessage("~~~Mike-Widget\n$tiny\n~~~", streaming = false)
        assertTrue(parts.single() is MessagePart.Widget)
    }

    @Test fun aLongerFenceNeedsAMatchingClose() {
        val body = "{\"body\":[{\"type\":\"text\",\"text\":\"```inner```\"}]}"
        val parts = splitMessage(fenced(body, fence = "````"), streaming = false)
        assertTrue(parts.single() is MessagePart.Widget)
    }

    @Test fun windowsLineEndingsAreFine() {
        val parts = splitMessage("Hi\r\n```mike-widget\r\n$tiny\r\n```\r\nBye", streaming = false)
        assertEquals(3, parts.size)
        assertTrue(parts[1] is MessagePart.Widget)
    }

    @Test fun aWidgetWithWarningsStillRenders() {
        val parts = splitMessage(fenced("""{"body":[{"type":"hologram"},{"type":"text","text":"x"}]}"""), streaming = false)
        val widget = parts.single() as MessagePart.Widget
        assertEquals(1, widget.spec.warnings.size)
    }

    @Test fun theReferenceDashboardSurvivesTheSplit() {
        val parts = splitMessage("הנה הדשבורד:\n${fenced(CONTROL_CENTER)}\nנסה את הסליידרים.", streaming = false)
        assertEquals(3, parts.size)
        val widget = parts[1] as MessagePart.Widget
        assertEquals(emptyList<String>(), widget.spec.warnings)
        assertEquals(120.0, widget.spec.initialState()["perDay"])
    }

    @Test fun textWithoutTheFenceIsReturnedUntouched() {
        val text = "  leading spaces\n\n\ntrailing newlines\n\n"
        assertEquals(listOf<MessagePart>(MessagePart.Markdown(text)), splitMessage(text, streaming = true))
    }
}
