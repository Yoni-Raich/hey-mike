/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license. See LICENSE,
 * LICENSE-COMMERCIAL.md and NOTICE. Distributed without any warranty.
 */

package dev.androidagent.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ClaudeRuntimeUpdatesTest {
    private val root = Files.createTempDirectory("claude-updates").toFile()
    private val payload = "official test binary".toByteArray()
    private val hash = dev.androidagent.core.NetDiagnostics.sha256Hex(payload)
    private val base = ClaudeBinaryPin("2.1.293", "${ClaudeReleaseClient.BASE}/2.1.293/linux-arm64-musl/claude", hash, payload.size.toLong())
    private val next = base.copy(version = "2.1.294", url = "${ClaudeReleaseClient.BASE}/2.1.294/linux-arm64-musl/claude")
    private var at = 1_000_000L
    private var checks = 0
    private var downloads = 0
    private var fetchedPin = next
    private var corruptDownload = false

    private fun installer(pin: ClaudeBinaryPin = base, supported: Boolean = true) = ClaudeRuntimeInstaller(
        root, pin, supported, openConnection = { url ->
            downloads++
            val bytes = if (corruptDownload) "wrong".toByteArray() else payload
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(bytes)
                override fun getContentLengthLong() = bytes.size.toLong()
            }
        }, usableSpace = { Long.MAX_VALUE },
    )

    private fun updates(
        supported: Boolean = true,
        downloadAllowed: Boolean = true,
        validate: suspend (File, ClaudeBinaryPin) -> Boolean = { _, _ -> true },
        latest: suspend () -> ClaudeBinaryPin = { checks++; fetchedPin },
    ) = ClaudeRuntimeUpdates(installer(supported = supported), latest, { installer(it) }, validate, { at }, { downloadAllowed })

    @After fun clean() { root.deleteRecursively() }

    @Test fun absentOrUnsupportedRuntimeNeverDownloadsOrChecks() = runBlocking {
        assertFalse(updates().check())
        assertFalse(updates(supported = false).check())
        assertEquals(0, downloads)
        assertEquals(0, checks)
    }

    @Test fun verifiedCandidateActivatesAndRestartRetainsItAndOldBinary() = runBlocking {
        val updater = updates()
        updater.install()
        val old = updater.refresh()!!
        assertTrue(updater.check())
        assertEquals(next, updater.activePin)
        assertTrue(old.isFile)
        assertEquals(payload.toList(), old.readBytes().toList())
        val restarted = updates()
        assertEquals(File(root, "2.1.294/claude"), restarted.refresh())
        assertEquals(next, restarted.activePin)
        assertFalse(restarted.check())
        assertEquals(1, checks)
    }

    @Test fun candidateLaunchFailureKeepsWorkingVersionAcrossRestart() = runBlocking {
        val updater = updates(validate = { _, _ -> false })
        val old = updater.install()
        assertFalse(updater.check())
        assertEquals(base, updater.activePin)
        assertEquals(old, updates().refresh())
        assertEquals(ClaudeInstallPhase.INSTALLED, updater.state.value.phase)
        assertTrue(updater.state.value.updateMessage!!.contains("keeping"))
    }

    @Test fun checksumOrMetadataFailureNeverReplacesWorkingVersion() = runBlocking {
        val updater = updates()
        val old = updater.install()
        corruptDownload = true
        assertFalse(updater.check())
        assertEquals(old, updater.refresh())
        assertEquals(base, updater.activePin)
        assertFalse(File(root, "2.1.294/claude").exists())
        at += ClaudeRuntimeUpdates.CHECK_INTERVAL_MS
        val offline = updates(latest = { error("offline") })
        assertFalse(offline.check())
        assertEquals(old, offline.refresh())
    }

    @Test fun cancellationDuringCompatibilityCheckKeepsWorkingVersion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val updater = updates(validate = { _, _ -> entered.complete(Unit); awaitCancellation() })
        val old = updater.install()
        val checking = async { updater.check() }
        entered.await()
        assertEquals(ClaudeInstallPhase.INSTALLED, updater.state.value.phase)
        updater.cancel()
        checking.cancelAndJoin()
        assertEquals(old, updates().refresh())
        assertEquals(base, updater.activePin)
    }

    @Test fun changedActiveFileRollsBackToPreviousVerifiedVersion() = runBlocking {
        val updater = updates()
        val old = updater.install()
        updater.check()
        updater.refresh()!!.appendText("corrupted")
        val restarted = updates()
        assertEquals(old, restarted.refresh())
        assertEquals(base, restarted.activePin)
    }

    @Test fun checksAreThrottledAndVersionsNeverDowngrade() = runBlocking {
        val updater = updates()
        updater.install()
        updater.check()
        at += ClaudeRuntimeUpdates.CHECK_INTERVAL_MS - 1
        assertFalse(updater.check())
        assertEquals(1, checks)
        at++
        fetchedPin = base
        assertFalse(updater.check())
        assertEquals(2, checks)
        assertEquals(2, downloads)
        assertEquals(next, updater.activePin)
    }

    @Test fun simultaneousChecksShareOneDownloadAndLeaveActiveRuntimeUsable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val updater = updates(validate = { _, _ -> entered.complete(Unit); finish.await(); true })
        val old = updater.install()
        val first = async { updater.check() }
        entered.await()
        assertFalse(updater.check())
        assertEquals(old, updater.refresh())
        assertEquals(ClaudeInstallPhase.INSTALLED, updater.state.value.phase)
        finish.complete(Unit)
        assertTrue(first.await())
        assertEquals(2, downloads)
    }

    @Test fun meteredConnectionWaitsWithoutDownloadThenUnmeteredCanUpdate() = runBlocking {
        val updater = updates(downloadAllowed = false)
        updater.install()
        assertFalse(updater.check())
        assertEquals(1, downloads)
        assertTrue(updater.state.value.updateMessage!!.contains("unmetered"))
        assertTrue(updates().check())
        assertEquals(2, downloads)
    }

    @Test fun unsafeSavedVersionCannotEscapeRuntimeDirectory() = runBlocking {
        installer().install()
        File(root, "active.json").writeText("""{"version":"../../outside","size":22,"sha256":"$hash"}""")
        val updater = updates()
        assertEquals(File(root, "2.1.293/claude"), updater.refresh())
        assertEquals(base, updater.activePin)
    }
}
