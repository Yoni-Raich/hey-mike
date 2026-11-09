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

/** One piece of an assistant message: prose, or a block the chat draws as a live widget. */
sealed interface MessagePart {
    data class Markdown(val text: String) : MessagePart

    /** A finished widget. [index] counts the widgets of the message, so each keeps its own state. */
    class Widget(val index: Int, val spec: WidgetSpec, val source: String) : MessagePart

    /** A widget block the model is still writing. It is not shown as raw JSON. */
    data object Pending : MessagePart

    /** A widget block that cannot be drawn. It is shown as the code it is, with the reason. */
    data class Broken(val source: String, val reason: String) : MessagePart
}

/** The info string that marks a fenced block as a widget. */
const val WIDGET_FENCE = "mike-widget"

private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})\\s*(.*)$")

/**
 * Splits an assistant message into prose and `mike-widget` blocks.
 *
 * A widget is a fenced code block whose language is [WIDGET_FENCE] and whose
 * body is JSON. Any other fenced block, even one that mentions the fence in
 * its text, stays prose. While the message [streaming], a widget block that
 * has not closed yet becomes [MessagePart.Pending]; in a finished message it
 * is [MessagePart.Broken], so nothing the model wrote disappears.
 *
 * A streaming message is split again at every token. [parse] lets the caller
 * keep the widgets it has already parsed instead of parsing them each time.
 */
fun splitMessage(
    text: String,
    streaming: Boolean,
    parse: (String) -> WidgetSpec = WidgetSpec::parse,
): List<MessagePart> {
    if (!text.contains(WIDGET_FENCE, ignoreCase = true)) return listOf(MessagePart.Markdown(text))
    val parts = ArrayList<MessagePart>()
    val prose = StringBuilder()
    val lines = text.split('\n')
    var index = 0
    var widgets = 0
    fun flushProse() {
        if (prose.isNotBlank()) parts += MessagePart.Markdown(prose.toString().trim('\n'))
        prose.clear()
    }
    while (index < lines.size) {
        val line = lines[index]
        val open = FENCE.matchEntire(line.trimEnd('\r'))
        if (open == null) {
            prose.append(line).append('\n')
            index++
            continue
        }
        val marker = open.groupValues[1]
        val language = open.groupValues[2].trim().substringBefore(' ').lowercase()
        var end = index + 1
        while (end < lines.size && !closes(lines[end], marker)) end++
        val closed = end < lines.size
        if (language != WIDGET_FENCE) {
            // An ordinary code block: copy it whole so its content is never read as a fence.
            val stop = if (closed) end else lines.size - 1
            for (i in index..stop) prose.append(lines[i]).append('\n')
            index = stop + 1
            continue
        }
        flushProse()
        val body = lines.subList(index + 1, if (closed) end else lines.size).joinToString("\n")
        parts += when {
            !closed && streaming -> MessagePart.Pending
            !closed -> MessagePart.Broken(body, "the block was never closed")
            else -> try {
                MessagePart.Widget(widgets++, parse(body), body)
            } catch (e: WidgetParseException) {
                MessagePart.Broken(body, e.message.orEmpty())
            }
        }
        index = if (closed) end + 1 else lines.size
    }
    flushProse()
    return parts
}

private fun closes(line: String, marker: String): Boolean {
    val trimmed = line.trimEnd('\r').trim()
    return trimmed.length >= marker.length && trimmed.all { it == marker[0] }
}
