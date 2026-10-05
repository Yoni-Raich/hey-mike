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

import dev.androidagent.app.ui.fileSize
import dev.androidagent.core.RemoteMediaRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteMediaLoaderTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val photo = RemoteMediaRef("Pc", "C:\\pics\\holiday.png", 5)
    private val clip = RemoteMediaRef("Pc", "C:\\pics\\clip.mp4", 8)

    private var fetches = mutableListOf<RemoteMediaRef>()
    private var gate: CompletableDeferred<Unit>? = null
    private var failWith: String? = null
    private val saved = mutableListOf<Pair<RemoteMediaRef, String>>()

    @After fun stop() = scope.cancel()

    private fun loader(maxCache: Long = 1_000_000) = RemoteMediaLoader(
        scope, File(temp.root, "cache"),
        fetch = { ref, target, progress ->
            fetches += ref
            gate?.await()
            failWith?.let { error(it) }
            progress(2, ref.size)
            target.writeText("x".repeat(ref.size.toInt()))
        },
        save = { ref, file -> saved += ref to file.readText(); "phone:Pictures/Hey Mike/${ref.name}" },
        maxCacheBytes = maxCache,
    )

    private fun RemoteMediaLoader.ready(ref: RemoteMediaRef): RemoteMediaState =
        runBlocking { state(ref).first { it is RemoteMediaState.Ready || it is RemoteMediaState.Failed } }

    @Test fun nothingMovesUntilSomeoneLooks() {
        val loader = loader()
        assertEquals(RemoteMediaState.Idle, loader.state(photo).value)
        assertTrue(fetches.isEmpty())
    }

    @Test fun lookingBringsTheFileIntoTheCacheOnceWhoeverAsks() {
        val loader = loader()
        gate = CompletableDeferred()
        loader.load(photo)
        loader.load(photo)
        assertTrue(loader.state(photo).value is RemoteMediaState.Loading)
        gate!!.complete(Unit)
        val file = (loader.ready(photo) as RemoteMediaState.Ready).file
        assertEquals("xxxxx", file.readText())
        assertEquals("png", file.extension)
        assertEquals(listOf(photo), fetches)
        // Asking again, or asking after a restart, is a cache hit.
        loader.load(photo)
        assertEquals(1, fetches.size)
        assertTrue(loader(maxCache = 1_000_000).state(photo).value is RemoteMediaState.Ready)
    }

    @Test fun aFailureIsShownAndRetryingFetchesAgain() {
        val loader = loader()
        failWith = "Server is not reachable."
        loader.load(clip)
        assertEquals("Server is not reachable.", (loader.ready(clip) as RemoteMediaState.Failed).message)
        failWith = null
        loader.load(clip)
        assertTrue(loader.ready(clip) is RemoteMediaState.Ready)
        assertEquals(2, fetches.size)
    }

    @Test fun savingLoadsFirstThenPutsTheCachedCopyOnThePhone() = runBlocking {
        val loader = loader()
        val result = loader.saveToPhone(photo)
        assertEquals("phone:Pictures/Hey Mike/holiday.png", result.getOrThrow())
        assertEquals(listOf(photo to "xxxxx"), saved)
        // Saving what is already here moves no more bytes.
        loader.saveToPhone(photo)
        assertEquals(1, fetches.size)
        assertEquals(2, saved.size)
    }

    @Test fun savingReportsWhyItCouldNotLoad() = runBlocking {
        val loader = loader()
        failWith = "Pc is no longer saved."
        val result = loader.saveToPhone(clip)
        assertTrue(result.isFailure)
        assertEquals("Pc is no longer saved.", result.exceptionOrNull()!!.message)
        assertTrue(saved.isEmpty())
    }

    @Test fun theOldestCopiesGoWhenTheCacheIsFull() {
        val loader = loader(maxCache = 10)
        loader.load(photo)
        loader.ready(photo)
        val first = (loader.state(photo).value as RemoteMediaState.Ready).file
        first.setLastModified(System.currentTimeMillis() - 60_000)
        loader.load(clip)
        loader.ready(clip)
        val second = (loader.state(clip).value as RemoteMediaState.Ready).file
        // 5 + 8 bytes is over 10: the older one is dropped, the one just fetched stays.
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(RemoteMediaState.Idle, loader.state(photo).value)
        assertNotNull(loader.state(clip).value as? RemoteMediaState.Ready)
    }

    @Test fun sizesReadAsAPersonWouldSayThem() {
        assertEquals("1 KB", fileSize(300))
        assertEquals("340 KB", fileSize(340_000))
        assertEquals("12 MB", fileSize(12_400_000))
        assertEquals("1.4 GB", fileSize(1_400_000_000))
    }
}
