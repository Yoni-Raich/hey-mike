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

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A picture or video that stays on a computer until the user looks at it.
 *
 * It is stored in a message's attachment list as text, [encode]d, so nothing
 * about messages or the session store had to change: a local file is a path
 * and this is `remote:<computer>:<bytes>:<absolute path there>`. The size is
 * known when the message is made (the computer was asked), so the tile can say
 * how big the file is before any byte moves.
 */
data class RemoteMediaRef(val place: String, val path: String, val size: Long) {
    /** The file's own name, in either path style. */
    val name: String
        get() = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { "file" }

    val isImage: Boolean get() = ChatTools.isImage(name)
    val isVideo: Boolean get() = ChatTools.isVideo(name)

    fun encode(): String = PREFIX + URLEncoder.encode(place, "UTF-8") + ":" + size + ":" + path

    /**
     * Where Save puts it on the phone: a Hey Mike folder in Pictures or Movies,
     * where the gallery looks, and Downloads for anything else.
     */
    val savePath: String
        get() {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            return when {
                isImage -> "Pictures/Hey Mike/$safe"
                isVideo -> "Movies/Hey Mike/$safe"
                else -> "Download/$safe"
            }
        }

    companion object {
        const val PREFIX = "remote:"

        fun isRemote(attachment: String): Boolean = attachment.startsWith(PREFIX)

        /** The reference an attachment names, or null for a local path or a malformed one. */
        fun parse(attachment: String): RemoteMediaRef? {
            if (!isRemote(attachment)) return null
            val rest = attachment.removePrefix(PREFIX)
            val first = rest.indexOf(':')
            if (first <= 0) return null
            val second = rest.indexOf(':', first + 1)
            if (second <= first + 1) return null
            val size = rest.substring(first + 1, second).toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val path = rest.substring(second + 1)
            if (path.isEmpty()) return null
            val place = runCatching { URLDecoder.decode(rest.substring(0, first), "UTF-8") }.getOrNull() ?: return null
            return RemoteMediaRef(place, path, size)
        }
    }
}
