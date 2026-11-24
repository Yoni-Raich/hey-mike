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

package dev.androidagent.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Which files a chat may upload: only files inside its own folder.
 *
 * The rest of the app's private storage holds sign-ins and other chats, so a
 * path that leaves the chat folder, by `..` or by a link, is refused. A file
 * from shared storage is copied into the chat folder first.
 */
object UploadFiles {
    const val MAX_FILES = 10
    const val MAX_BYTES = 100L * 1024 * 1024

    fun resolve(workspace: File, given: JsonElement?): List<File> {
        val paths = (given as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim().orEmpty() }
            ?: (given as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { listOf(it.trim()) }
            ?: throw WebFailure("no_files", "upload needs files: paths in this chat's folder.", "Copy the file into the chat folder first, then pass its path.")
        if (paths.isEmpty() || paths.any { it.isEmpty() }) throw WebFailure("no_files", "upload needs files: non-empty paths in this chat's folder.")
        if (paths.size > MAX_FILES) throw WebFailure("too_many_files", "upload takes at most $MAX_FILES files at a time.")
        // Real paths follow every link, so a link inside the folder cannot point out of it.
        val root = runCatching { workspace.toPath().toRealPath().toFile() }.getOrElse {
            throw WebFailure("no_chat_folder", "This chat has no folder to upload from.")
        }
        return paths.map { path ->
            val plain = path.removePrefix("chat:").removePrefix("./")
            val candidate = File(plain).let { if (it.isAbsolute) it else File(root, plain) }
            val real = runCatching { candidate.toPath().toRealPath().toFile() }.getOrElse {
                throw WebFailure("no_such_file", "$path is not a file in this chat's folder. Nothing was uploaded.")
            }
            if (real != root && !real.path.startsWith(root.path + File.separator)) throw WebFailure(
                "outside_chat_folder", "$path is outside this chat's folder. Nothing was uploaded.",
                "Copy the file into the chat folder first, then upload that copy.")
            if (!real.isFile) throw WebFailure("no_such_file", "$path is not a file in this chat's folder. Nothing was uploaded.")
            if (real.length() > MAX_BYTES) throw WebFailure("file_too_large", "$path is larger than ${MAX_BYTES / 1024 / 1024} MB. Nothing was uploaded.")
            real
        }
    }
}
