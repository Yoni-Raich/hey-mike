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

/** Small, platform-free presentation mapping used by the floating card. */
internal enum class OverlayTone { ACTIVE, CONTROLLING, WAITING, STOPPING, DONE, ERROR }

/**
 * What the floating controls show for one status label.
 *
 * [headline] is the pill's one line; [commentary] is the agent's latest words,
 * shown when the card is open and carried across tool calls until the agent
 * says something new.
 */
internal data class OverlayContent(
    val tone: OverlayTone,
    val headline: String,
    val commentary: String?,
    val needsApproval: Boolean = false,
)

internal fun overlayTone(status: String): OverlayTone = overlayContent(status, null).tone

/**
 * Maps a core overlay label ("Working · read ui", "Controlling · tap",
 * "Working · <agent text>", "Done · Stopped") to what the card shows.
 * [previousCommentary] is what the card showed before, kept unless this
 * label replaces it.
 */
internal fun overlayContent(label: String, previousCommentary: String?): OverlayContent {
    val title = label.substringBefore('·').trim()
    val detail = label.substringAfter('·', "").trim().takeIf { it.isNotEmpty() }
    return when (title.lowercase()) {
        // A new run starts with nothing said yet.
        "starting" -> OverlayContent(OverlayTone.ACTIVE, "Starting", null)
        "stopping" -> OverlayContent(OverlayTone.STOPPING, "Stopping", previousCommentary)
        "done" -> OverlayContent(OverlayTone.DONE, "Done", detail?.let(::oneLine) ?: previousCommentary)
        "error" -> OverlayContent(OverlayTone.ERROR, "Error", detail?.let(::oneLine) ?: previousCommentary)
        "working", "thinking", "running", "controlling" -> {
            val controlling = title.equals("controlling", ignoreCase = true)
            val tone = if (controlling) OverlayTone.CONTROLLING else OverlayTone.ACTIVE
            val action = detail?.let(::toolHeadline)
            when {
                detail == null -> OverlayContent(tone, if (controlling) "On your screen" else "Working", previousCommentary)
                isApproval(detail) -> OverlayContent(OverlayTone.WAITING, detail, previousCommentary, needsApproval = true)
                detail in ENGINE_ACTIVITY -> OverlayContent(tone, detail, previousCommentary)
                action != null -> OverlayContent(tone, action, previousCommentary)
                // Anything that is not a tool name is the agent talking.
                else -> OverlayContent(OverlayTone.ACTIVE, "Working", oneLine(detail))
            }
        }
        else -> OverlayContent(OverlayTone.ACTIVE, label.trim().ifEmpty { "Working" }, previousCommentary)
    }
}

/**
 * Engine activity lines, which say which kind of work started, not what the
 * agent thinks. They belong on the headline; treating them as speech would
 * wipe the agent's own words on every reasoning step.
 */
private val ENGINE_ACTIVITY = setOf("Working", "Working in session files", "Updating session files")

private fun isApproval(detail: String): Boolean =
    detail.equals("Waiting for approval", ignoreCase = true) || detail.startsWith("Approve", ignoreCase = true)

private val TOOL_HEADLINES = mapOf(
    "tap" to "Tapping",
    "tap node" to "Tapping",
    "swipe" to "Swiping",
    "scroll node" to "Scrolling",
    "type text" to "Typing",
    "set text" to "Typing",
    "key" to "Pressing a key",
    "read ui" to "Reading the screen",
    "screenshot" to "Looking at the screen",
    "open app" to "Opening an app",
    "open intent" to "Opening a link",
    "resolve intent" to "Finding the right app",
    "wait for change" to "Waiting for the screen",
    "act and observe" to "Working on your screen",
    "shell" to "Running a command",
    "push file" to "Sending a file",
    "pull file" to "Copying a file",
    "install apk" to "Installing an app",
    "device status" to "Checking the phone",
)

// Tool names arrive as a few lowercase words; the agent's own text never does.
private val TOOL_NAME = Regex("[a-z]+( [a-z]+){0,3}")

private fun toolHeadline(detail: String): String? {
    val name = detail.replace('_', ' ').trim()
    TOOL_HEADLINES[name]?.let { return it }
    return if (TOOL_NAME.matches(name)) name.replaceFirstChar { it.uppercase() } else null
}

private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$")

/**
 * The agent's text for the card, still Markdown: blank lines dropped so four
 * lines hold as much as they can, headings turned into bold lines, cut at a
 * line break where one fits in [limit].
 */
internal fun cardMarkdown(text: String, limit: Int = 600): String {
    val markdown = text.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .map { line -> HEADING.matchEntire(line)?.let { "**${it.groupValues[1]}**" } ?: line }
        .joinToString("\n")
    if (markdown.length <= limit) return markdown
    val lineEnd = markdown.lastIndexOf('\n', limit)
    return if (lineEnd > 0) markdown.take(lineEnd) else markdown.take(limit).trimEnd() + "…"
}

/** The agent's text as one plain line: no Markdown marks, no line breaks. */
internal fun oneLine(text: String, limit: Int = 220): String {
    val plain = text.lineSequence()
        .map { it.trim().trimStart('#', '>', '-', '*', ' ') }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (plain.length <= limit) plain else plain.take(limit).trimEnd() + "…"
}
