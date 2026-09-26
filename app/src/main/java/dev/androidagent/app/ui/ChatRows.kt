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

import dev.androidagent.core.ChatMessage

/** One row of the conversation: a message, or a run of device actions folded together. */
internal sealed interface ChatRow {
    val key: String
}

internal data class MessageRow(val message: ChatMessage) : ChatRow {
    override val key: String get() = message.id
}

/**
 * Back-to-back tool messages. [live] while the run that produced them is
 * still going and nothing has been said since.
 */
internal data class ActionsRow(val steps: List<ChatMessage>, val live: Boolean) : ChatRow {
    // Keyed by the first step, so the row keeps its place and state as it grows.
    override val key: String get() = "actions-${steps.first().id}"
}

/**
 * Folds consecutive `tool` messages into one [ActionsRow], so five device
 * actions read as one line instead of five identical "Device activity" rows.
 * [running] marks a trailing group as live.
 */
internal fun chatRows(messages: List<ChatMessage>, running: Boolean): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var pending = mutableListOf<ChatMessage>()
    fun flush(live: Boolean) {
        if (pending.isEmpty()) return
        rows += ActionsRow(pending, live)
        pending = mutableListOf()
    }
    for (message in messages) {
        if (message.role.equals("tool", ignoreCase = true)) {
            pending += message
        } else {
            flush(live = false)
            rows += MessageRow(message)
        }
    }
    flush(live = running)
    return rows
}

/** What the "Suggest workflows" chip sends. Visible as the user's message, so the model's answer reads in context. */
internal const val SUGGEST_WORKFLOWS_PROMPT =
    "Suggest workflows from this chat: read the workflows skill, look at what you just did on the " +
        "phone, and propose up to 3 workflows worth saving — a name, what it does, and which values " +
        "should be parameters. Write and test one only after I pick it."

/** Tools that operate the screen. Reading it or looking things up is not a sequence worth saving. */
private val SEQUENCE_TOOLS = setOf(
    "tap", "tap_node", "swipe", "scroll_node", "type_text", "set_text", "key",
    "open_app", "open_intent", "act_and_observe", "act_plan",
)

private const val MIN_ACTIONS_FOR_SUGGESTION = 4

/**
 * Whether to offer "Suggest workflows" under the last reply.
 *
 * Decided without the model, because asking it costs a turn: only after a
 * finished reply whose run operated the phone several times, the case where a
 * saved workflow would have saved turns. A question answered in words, or a
 * run that was itself one workflow call, offers nothing.
 */
internal fun offersWorkflowSuggestion(messages: List<ChatMessage>, running: Boolean): Boolean {
    if (running) return false
    val last = messages.lastOrNull() ?: return false
    if (!last.role.equals("assistant", ignoreCase = true) || !last.state.equals("complete", ignoreCase = true)) return false
    val lastUser = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
    if (lastUser >= 0 && messages[lastUser].text.trim() == SUGGEST_WORKFLOWS_PROMPT) return false
    val actions = messages.drop(lastUser + 1).count { message ->
        message.role.equals("tool", ignoreCase = true) && toolKey(toolNameOf(message)) in SEQUENCE_TOOLS
    }
    return actions >= MIN_ACTIONS_FOR_SUGGESTION
}

internal fun actionsLabel(count: Int, live: Boolean): String = when {
    live -> if (count > 0) "Working on your phone · $count" else "Working on your phone"
    count == 1 -> "1 action on your phone"
    else -> "$count actions on your phone"
}

/** The tool a stored tool message came from; it is saved as "name: result". */
internal fun toolNameOf(message: ChatMessage): String = message.text.substringBefore(':').trim()

private val TOOL_ACTIONS = mapOf(
    // tool name to (done, in progress)
    "tap" to ("Tapped" to "Tapping"),
    "tap_node" to ("Tapped" to "Tapping"),
    "swipe" to ("Swiped" to "Swiping"),
    "scroll_node" to ("Scrolled" to "Scrolling"),
    "type_text" to ("Typed text" to "Typing"),
    "set_text" to ("Typed text" to "Typing"),
    "key" to ("Pressed a key" to "Pressing a key"),
    "read_ui" to ("Read the screen" to "Reading the screen"),
    "screenshot" to ("Looked at the screen" to "Looking at the screen"),
    "open_app" to ("Opened an app" to "Opening an app"),
    "open_intent" to ("Opened a link" to "Opening a link"),
    "resolve_intent" to ("Found the right app" to "Finding the right app"),
    "wait_for_change" to ("Waited for the screen" to "Waiting for the screen"),
    "act_and_observe" to ("Acted on the screen" to "Working on the screen"),
    "shell" to ("Ran a command" to "Running a command"),
    "push_file" to ("Sent a file" to "Sending a file"),
    "pull_file" to ("Copied a file" to "Copying a file"),
    "install_apk" to ("Installed an app" to "Installing an app"),
    "device_status" to ("Checked the phone" to "Checking the phone"),
    "run_workflow" to ("Ran a workflow" to "Running a workflow"),
    "workflow_runner" to ("Ran a workflow" to "Running a workflow"),
    "act_plan" to ("Ran a sequence" to "Working through a sequence"),
)

/** "read_ui" as a finished step: "Read the screen". Unknown tools read as their name. */
internal fun toolStepLabel(name: String): String =
    TOOL_ACTIONS[toolKey(name)]?.first ?: plainName(name)

/**
 * The run status as the composer shows it. The coordinator reports a tool by
 * its name ("read ui"); anything else it reports is already a sentence.
 */
internal fun runStatusLabel(status: String): String =
    TOOL_ACTIONS[toolKey(status)]?.second ?: status

private fun toolKey(name: String): String = name.trim().lowercase().replace(' ', '_')

private fun plainName(name: String): String =
    name.trim().replace('_', ' ').replaceFirstChar { it.uppercase() }.ifEmpty { "Device action" }

private val RTL_LETTER = Regex("[\u0590-\u08FF\uFB1D-\uFDFF\uFE70-\uFEFF]")
internal const val RLM = '\u200F'

/** True when [text] holds any Hebrew or Arabic letter. */
internal fun containsRtl(text: String): Boolean = RTL_LETTER.containsMatchIn(text)

/**
 * Any line with a Hebrew or Arabic letter reads right to left, even when it
 * starts with Latin ("Yoni Raich (את/ה)"). Text is laid out by its first strong
 * character, so such lines get an invisible right-to-left mark in front.
 */
internal fun withRtlLines(text: String): String =
    text.lines().joinToString("\n") { line ->
        if (containsRtl(line) && !line.startsWith(RLM)) "$RLM$line" else line
    }

/**
 * Where [withRtlLines] puts its marks: the start offset of every line that
 * holds a Hebrew or Arabic letter. The composer uses it to show the marks
 * without writing them into the draft.
 */
internal fun rtlLineStarts(text: String): List<Int> {
    val starts = mutableListOf<Int>()
    var lineStart = 0
    for (line in text.split('\n')) {
        if (containsRtl(line) && !line.startsWith(RLM)) starts += lineStart
        lineStart += line.length + 1
    }
    return starts
}

private val LIST_ITEM = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+)(.*)$")

/**
 * A Markdown list with any right-to-left item reads right to left as one
 * block: its all-Latin items ("Team Standup") get a right-to-left mark so they
 * stay on the same side as their neighbours. Code blocks are left alone.
 */
internal fun keepListsTogether(markdown: String): String {
    val lines = markdown.lines().toMutableList()
    var inFence = false
    var index = 0
    while (index < lines.size) {
        if (lines[index].trimStart().startsWith("```")) inFence = !inFence
        if (inFence || !LIST_ITEM.matches(lines[index])) {
            index++
            continue
        }
        var end = index
        while (end + 1 < lines.size && LIST_ITEM.matches(lines[end + 1])) end++
        if ((index..end).any { containsRtl(lines[it]) }) {
            for (line in index..end) {
                val match = LIST_ITEM.find(lines[line]) ?: continue
                val body = match.groupValues[2]
                if (!containsRtl(body) && !body.startsWith(RLM)) lines[line] = match.groupValues[1] + RLM + body
            }
        }
        index = end + 1
    }
    return lines.joinToString("\n")
}
