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

package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File

/**
 * Claude Code's own transcript of a session: `<config>/projects/<folder>/<id>.jsonl`,
 * one record per line, each message naming the one before it in `parentUuid`.
 * `claude --resume <id>` continues from the last message.
 *
 * The app adds records here only to give a chat's earlier turns to Claude as
 * its own history after an engine switch ([AgentEngine.seedHistory]). It never
 * touches the sign-in files beside them. The computer's writer script
 * (`claude_transcript.ps1` / `.py`) does the same as [extend].
 */
object ClaudeTranscript {
    /** Stands for the transcript's last message in the first record given to [extend]. */
    const val LEAF = "@HEYMIKE_LEAF@"
    private const val MAX_FOLDER = 200
    private val messageTypes = setOf("user", "assistant", "system", "attachment")

    /** Claude Code's folder name for [cwd]; null for a path so long that it adds a hash, which is not copied here. */
    fun folderName(cwd: String): String? = cwd.replace(Regex("[^A-Za-z0-9]"), "-").takeIf { it.length <= MAX_FOLDER }

    /** The last record of the main conversation in [lines], which the next one follows. */
    fun leaf(lines: List<String>): String? = lines.asReversed().firstNotNullOfOrNull { line ->
        val row = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
        val type = (row["type"] as? JsonPrimitive)?.content
        val sidechain = (row["isSidechain"] as? JsonPrimitive)?.booleanOrNull == true
        (row["uuid"] as? JsonPrimitive)?.content?.takeIf { type in messageTypes && !sidechain && it.isNotBlank() }
    }

    /**
     * Append [records] (JSON lines; the first names [LEAF] as its parent) to
     * [sessionId]'s transcript under [projects], in [cwd]'s folder when there
     * is none yet. False when [cwd] has no folder name this can make.
     */
    fun extend(projects: File, cwd: String, sessionId: String, records: List<String>): Boolean {
        require(Regex("[0-9a-fA-F-]{36}").matches(sessionId)) { "Invalid Claude session ID" }
        if (records.isEmpty()) return true
        val file = projects.listFiles()?.map { File(it, "$sessionId.jsonl") }?.firstOrNull { it.isFile }
            ?: File(projects, folderName(cwd) ?: return false).also { it.mkdirs() }.resolve("$sessionId.jsonl")
        val existing = if (file.isFile) file.readText() else ""
        val parent = leaf(existing.lines().filter { it.isNotBlank() })
        val text = records.mapIndexed { index, line ->
            if (index == 0) line.replace("\"$LEAF\"", parent?.let { "\"$it\"" } ?: "null") else line
        }.joinToString("\n", postfix = "\n")
        file.appendText(if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n$text" else text)
        return true
    }
}
