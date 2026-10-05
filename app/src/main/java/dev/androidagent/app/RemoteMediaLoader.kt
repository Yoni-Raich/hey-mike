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

package dev.androidagent.app

import dev.androidagent.core.RemoteMediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/** Where one picture or video that lives on a computer stands on this phone. */
sealed interface RemoteMediaState {
    /** Not fetched. Nothing has moved. */
    data object Idle : RemoteMediaState

    /** [total] is 0 until the transfer reports it. */
    data class Loading(val done: Long, val total: Long) : RemoteMediaState

    data class Ready(val file: File) : RemoteMediaState

    data class Failed(val message: String) : RemoteMediaState
}

/**
 * Brings a [RemoteMediaRef] onto the phone when someone looks at it, and keeps
 * the copy in a cache the system may clear.
 *
 * The chat holds only the reference, so a message about a 400 MB video costs
 * nothing until it is opened, and a file the user never looks at never moves.
 * A fetch belongs to this loader and not to the tile that asked: scrolling a
 * tile off screen does not stop it, and two tiles for one file share one
 * transfer.
 *
 * The cache is keyed by computer, path and size, so a file replaced by one of
 * another size is fetched again. One replaced by one of the same size is not;
 * Retry on a tile cannot tell, so clearing the app's cache is the way out.
 *
 * @param fetch copies the file the reference names to the target, reporting (bytes, total).
 * @param save puts a file on the phone's shared storage and returns where.
 */
class RemoteMediaLoader(
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val fetch: suspend (RemoteMediaRef, File, (Long, Long) -> Unit) -> Unit,
    private val save: suspend (RemoteMediaRef, File) -> String,
    private val maxCacheBytes: Long = 400L * 1024 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val states = HashMap<String, MutableStateFlow<RemoteMediaState>>()
    private val jobs = HashMap<String, Job>()

    /** The state of [ref], starting at Ready when an earlier look left its copy in the cache. */
    fun state(ref: RemoteMediaRef): StateFlow<RemoteMediaState> = flowOf(ref)

    private fun flowOf(ref: RemoteMediaRef): MutableStateFlow<RemoteMediaState> = synchronized(this) {
        states.getOrPut(keyOf(ref)) {
            val cached = fileOf(ref).takeIf { it.isFile && it.length() == ref.size }
            MutableStateFlow(if (cached != null) RemoteMediaState.Ready(cached) else RemoteMediaState.Idle)
        }
    }

    /** Start fetching [ref]. Does nothing while it is loading or already here. */
    fun load(ref: RemoteMediaRef) {
        val flow = flowOf(ref)
        val key = keyOf(ref)
        synchronized(this) {
            val now = flow.value
            if (now is RemoteMediaState.Loading || now is RemoteMediaState.Ready && now.file.isFile) return
            flow.value = RemoteMediaState.Loading(0, ref.size)
            jobs[key] = scope.launch(io) { run(ref, flow, key) }
        }
    }

    private suspend fun run(ref: RemoteMediaRef, flow: MutableStateFlow<RemoteMediaState>, key: String) {
        val target = fileOf(ref)
        try {
            cacheDir.mkdirs()
            fetch(ref, target) { done, total -> flow.value = RemoteMediaState.Loading(done, if (total > 0) total else ref.size) }
            // Before it is announced, so a tile that sees Ready also sees the cache within its limit.
            trim(keep = target)
            flow.value = RemoteMediaState.Ready(target)
        } catch (cancelled: CancellationException) {
            flow.value = RemoteMediaState.Idle
            throw cancelled
        } catch (error: Exception) {
            flow.value = RemoteMediaState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "Could not load it from ${ref.place}.")
        } finally {
            synchronized(this) { jobs.remove(key) }
        }
    }

    /**
     * Load [ref] if needed, then save it on the phone, in the gallery's folders
     * for a picture or video. Returns where it went, or why it did not.
     */
    suspend fun saveToPhone(ref: RemoteMediaRef): Result<String> {
        load(ref)
        return when (val end = flowOf(ref).first { it is RemoteMediaState.Ready || it is RemoteMediaState.Failed }) {
            is RemoteMediaState.Ready -> try {
                Result.success(save(ref, end.file))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            is RemoteMediaState.Failed -> Result.failure(IllegalStateException(end.message))
            else -> Result.failure(IllegalStateException("Could not load it from ${ref.place}."))
        }
    }

    /** Drop the oldest copies until the cache is within its limit; the one just written stays. */
    private fun trim(keep: File) {
        val files = cacheDir.listFiles { file -> file.isFile && !file.name.startsWith(".") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxCacheBytes) break
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) {
                total -= size
                synchronized(this) {
                    states.entries.firstOrNull { (it.value.value as? RemoteMediaState.Ready)?.file == file }
                        ?.value?.value = RemoteMediaState.Idle
                }
            }
        }
    }

    private fun keyOf(ref: RemoteMediaRef): String =
        MessageDigest.getInstance("SHA-256").digest(ref.encode().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** The cache file keeps the original extension: the player and the image loader go by it. */
    private fun fileOf(ref: RemoteMediaRef): File =
        File(cacheDir, keyOf(ref) + "." + ref.name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" })
}
