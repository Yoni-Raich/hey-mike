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

package dev.androidagent.runtime

import android.content.Context
import dev.androidagent.core.NetDiagnostics
import java.io.File

/**
 * The pinned CA bundle (staged by tools/prepare_runtime.py) copied to
 * `<files>/runtime/cacert.pem`. Shared by the Codex and Claude hosts, so the
 * copy is serialised: two hosts never write the same partial file.
 */
internal object BundledCaFile {
    const val ASSET_PATH = "runtime/cacert.pem"

    fun file(runtimeRoot: File): File = File(runtimeRoot, "cacert.pem")

    /** Copy and validate the bundled PEM into app-private storage atomically. */
    @Synchronized
    fun stage(context: Context, runtimeRoot: File, assetPath: String = ASSET_PATH): String {
        val target = file(runtimeRoot)
        if (target.isFile && target.length() > 0L &&
            NetDiagnostics.validateCaPem(target.readBytes()) != null
        ) return target.absolutePath
        // A truncated file can remain after a killed process. Remove only this
        // known app-private path and rebuild it from the verified APK asset.
        target.delete()
        val partial = File(runtimeRoot, "cacert.pem.part")
        runCatching {
            context.assets.open(assetPath).use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            val bytes = partial.readBytes()
            require(NetDiagnostics.validateCaPem(bytes) != null) { "Bundled CA file is invalid" }
            require(partial.renameTo(target)) { "Could not install bundled CA file" }
        }.getOrElse {
            partial.delete()
            throw IllegalStateException("Could not stage bundled CA file", it)
        }
        return target.absolutePath
    }
}
