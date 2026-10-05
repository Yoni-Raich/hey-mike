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

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.ChatMessage

// What Codex does on a computer, as one row. Each Codex item (a command, a
// file change, a thought) arrives as its own `remote_activity` message named
// only by its type, so seven of them used to be seven rows that all said
// "Command execution" or "Thinking" and filled half the screen before the
// answer began. Here they fold into one group whose header says what
// happened and where, and whose steps say what each one did.

/** What one Codex item was doing. The engine names each in English; the kind comes from that name. */
internal enum class ActivityKind { THINKING, COMMAND, FILES, TOOL, SEARCH, OTHER }

/** [STOPPED] is a step that never reported an end because its run did. */
internal enum class StepState { RUNNING, DONE, FAILED, STOPPED }

internal data class ActivityStep(
    val id: String,
    val kind: ActivityKind,
    /** One line that says what happened: "Ran ls -la", not "Command execution". */
    val label: String,
    /** The full command, the changed paths, the reasoning summary. */
    val detail: String,
    val state: StepState,
)

/** The group's header: [failed] is kept apart so it can be shown in the error color. */
internal data class ActivityHeader(val text: String, val failed: String?) {
    val spoken: String get() = if (failed == null) text else "$text, $failed"
}

private const val LABEL_LIMIT = 160

/** A live group that nobody opened shows only its newest steps; the rest are a tap away. */
private const val LIVE_STEPS_SHOWN = 4

/**
 * One `remote_activity` message as a step. The message text is
 * "title\n\ndetail", and [live] says whether its run is still going: a step
 * left "streaming" by a run that ended was stopped, not still running.
 */
internal fun activityStep(message: ChatMessage, live: Boolean): ActivityStep {
    val title = message.text.substringBefore("\n\n").trim()
    val detail = message.text.substringAfter("\n\n", "").trim()
    val state = when (message.state.lowercase()) {
        "streaming" -> if (live) StepState.RUNNING else StepState.STOPPED
        "failed", "interrupted" -> StepState.FAILED
        else -> StepState.DONE
    }
    val kind = when {
        title == "Thinking" -> ActivityKind.THINKING
        title == "Command execution" -> ActivityKind.COMMAND
        title == "File change" -> ActivityKind.FILES
        title.startsWith("Tool: ") -> ActivityKind.TOOL
        title == "Web search" -> ActivityKind.SEARCH
        else -> ActivityKind.OTHER
    }
    return ActivityStep(message.id, kind, stepLabel(kind, title, detail, state), detail, state)
}

private fun stepLabel(kind: ActivityKind, title: String, detail: String, state: StepState): String {
    fun by(done: String, running: String, failed: String, stopped: String = done) = when (state) {
        StepState.DONE -> done
        StepState.RUNNING -> running
        StepState.FAILED -> failed
        StepState.STOPPED -> stopped
    }
    return when (kind) {
        ActivityKind.THINKING -> by("Thought", "Thinking…", "Thinking stopped")
        ActivityKind.COMMAND -> {
            val command = shortCommand(commandOf(detail))
            if (command.isEmpty()) {
                by("Ran a command", "Running a command", "A command failed", "A command was stopped")
            } else {
                by("Ran $command", "Running $command", "Failed: $command", "Stopped: $command")
            }
        }
        ActivityKind.FILES -> {
            val paths = detail.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val what = when (paths.size) {
                0 -> "files"
                1 -> folderName(paths[0])
                else -> "${paths.size} files"
            }
            by("Changed $what", "Changing $what", "Could not change $what", "Stopped changing $what")
        }
        ActivityKind.TOOL -> {
            val tool = title.removePrefix("Tool: ").trim().ifEmpty { "a tool" }
            by("Used $tool", "Using $tool", "$tool failed", "Stopped using $tool")
        }
        ActivityKind.SEARCH -> {
            val query = detail.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            if (query.isEmpty()) {
                by("Searched the web", "Searching the web", "The web search failed")
            } else {
                by("Searched for $query", "Searching for $query", "The web search failed: $query", "Stopped searching for $query")
            }
        }
        ActivityKind.OTHER -> {
            val name = title.ifEmpty { "Activity" }
            if (state == StepState.FAILED) "$name failed" else name
        }
    }
}

/** A command's text without the trailing "Exit code: N" line the engine adds once it ends. */
private fun commandOf(detail: String): String {
    val lines = detail.lines()
    val body = if (lines.lastOrNull()?.trim()?.startsWith("Exit code:") == true) lines.dropLast(1) else lines
    return body.joinToString("\n").trim()
}

// `bash -lc '...'`, `powershell.exe -NoProfile -Command "..."`, `cmd /c ...`:
// the shell Codex wraps every command in says nothing about what it did.
private val SHELL_WRAPPER = Regex(
    // The shell, with or without its folder (quoted, as Windows paths with spaces are) ...
    "^(?:\"[^\"]*[\\\\/]|\\S*[\\\\/])?(?:bash|zsh|sh|dash|powershell|pwsh|cmd)(?:\\.exe)?\"?\\s+" +
        // ... any switches before the one that carries the command ...
        "(?:-{1,2}[A-Za-z]+\\s+)*?" +
        // ... and that switch.
        "(?:-lc|-c|-Command|/c)\\s+",
    RegexOption.IGNORE_CASE,
)

/** The command as a person would name it: no shell wrapper, no quotes around it, one line. */
internal fun shortCommand(raw: String): String {
    val unwrapped = unquote(SHELL_WRAPPER.replaceFirst(raw.trim(), "")).trim()
    val lines = unwrapped.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val first = lines.firstOrNull()?.replace(Regex("\\s+"), " ").orEmpty()
    val capped = if (first.length > LABEL_LIMIT) first.take(LABEL_LIMIT - 1) + "…" else first
    return if (lines.size > 1 && !capped.endsWith("…")) "$capped …" else capped
}

private fun unquote(text: String): String {
    val t = text.trim()
    val quote = t.firstOrNull()
    return if (t.length >= 2 && (quote == '\'' || quote == '"') && t.last() == quote) t.substring(1, t.length - 1) else t
}

/**
 * What the collapsed group says. While it works it names the computer; when it
 * is done it counts what was done, so "Worked on Server · 4 commands" answers
 * the question the seven identical rows never did.
 */
internal fun activityHeader(steps: List<ActivityStep>, where: String?, live: Boolean): ActivityHeader {
    val at = where?.let { " on $it" }.orEmpty()
    if (live) return ActivityHeader("Working$at", null)
    val parts = listOfNotNull(
        counted(steps, ActivityKind.COMMAND, "command", "commands"),
        counted(steps, ActivityKind.FILES, "file change", "file changes"),
        counted(steps, ActivityKind.TOOL, "tool call", "tool calls"),
        counted(steps, ActivityKind.SEARCH, "search", "searches"),
    )
    val text = when {
        parts.isNotEmpty() -> "Worked$at · ${parts.joinToString(", ")}"
        steps.all { it.kind == ActivityKind.THINKING } -> "Thought"
        else -> "Worked$at"
    }
    val failed = steps.count { it.state == StepState.FAILED }
    return ActivityHeader(text, if (failed > 0) "$failed failed" else null)
}

private fun counted(steps: List<ActivityStep>, kind: ActivityKind, one: String, many: String): String? {
    val n = steps.count { it.kind == kind }
    return if (n == 0) null else "$n ${if (n == 1) one else many}"
}

private fun ActivityKind.icon(): ImageVector = when (this) {
    ActivityKind.THINKING -> Icons.Outlined.Psychology
    ActivityKind.COMMAND -> Icons.Outlined.Terminal
    ActivityKind.FILES -> Icons.Outlined.Edit
    ActivityKind.TOOL -> Icons.Outlined.Build
    ActivityKind.SEARCH -> Icons.Outlined.Search
    ActivityKind.OTHER -> Icons.Outlined.MoreHoriz
}

/**
 * A run of steps Codex took on a computer, as one row. It opens to the steps,
 * and each step opens to its detail. While the run is still going it is open
 * and shows the newest steps; afterwards it is closed until tapped. It is
 * drawn like [DeviceActionsRow], with the computer's icon instead of the
 * phone's, so what ran where stays readable at a glance.
 */
@Composable
internal fun RemoteActivityGroup(row: RemoteActivityRow, where: String?) {
    // Null until the user taps: then their choice wins over the live default.
    var choice by rememberSaveable(row.key) { mutableStateOf<Boolean?>(null) }
    val expanded = choice ?: row.live
    val steps = remember(row.steps, row.live) { row.steps.map { activityStep(it, row.live) } }
    val header = activityHeader(steps, where, row.live)
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(240), label = "activity-chevron")
    val error = MaterialTheme.colorScheme.error
    val title = buildAnnotatedString {
        append(header.text)
        header.failed?.let { failed ->
            append(" · ")
            withStyle(SpanStyle(color = error)) { append(failed) }
        }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, GroupBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize(tween(240))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { choice = !expanded }
                    .heightIn(min = 44.dp)
                    .padding(start = 14.dp, end = 12.dp)
                    .semantics {
                        contentDescription = "${header.spoken}. ${if (expanded) "Hide steps" else "Show steps"}"
                        if (row.live) liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (row.live) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        StatusDot(color = MaterialTheme.colorScheme.secondary, size = 8.dp, pulsing = true)
                    }
                } else {
                    Icon(Icons.Outlined.Computer, contentDescription = null, modifier = Modifier.size(18.dp), tint = StepInk)
                }
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(chevron), tint = StepInk)
            }
            if (expanded) {
                val shown = if (choice == null && row.live) steps.takeLast(LIVE_STEPS_SHOWN) else steps
                val hidden = steps.size - shown.size
                Column(Modifier.padding(start = 22.dp, end = 14.dp, bottom = 10.dp)) {
                    if (hidden > 0) {
                        Text(
                            if (hidden == 1) "1 earlier step" else "$hidden earlier steps",
                            Modifier.padding(start = 14.dp, bottom = 4.dp),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = StepInk,
                        )
                    }
                    shown.forEach { step -> key(step.id) { ActivityStepRow(step) } }
                }
            }
        }
    }
}

@Composable
private fun ActivityStepRow(step: ActivityStep) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val canOpen = step.detail.isNotBlank()
    val failed = step.state == StepState.FAILED
    val ink = if (failed) MaterialTheme.colorScheme.error else StepInk
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "step-chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(StepLine, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .padding(start = 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (canOpen) Modifier.clickable { open = !open } else Modifier)
                .heightIn(min = 36.dp)
                .semantics {
                    contentDescription = when {
                        !canOpen -> step.label
                        open -> "${step.label}. Hide details"
                        else -> "${step.label}. Show details"
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when (step.state) {
                    StepState.RUNNING -> CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    StepState.FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = ink)
                    else -> Icon(step.kind.icon(), contentDescription = null, modifier = Modifier.size(16.dp), tint = ink)
                }
            }
            Text(
                step.label,
                Modifier.weight(1f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = ink,
                maxLines = if (open) 4 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Tinted like its label: a brighter arrow than the words it belongs to read as the loudest thing in the row.
            if (canOpen) Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp).rotate(chevron), tint = ink)
        }
        if (open && canOpen) {
            SelectionContainer {
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (step.kind == ActivityKind.COMMAND) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}
