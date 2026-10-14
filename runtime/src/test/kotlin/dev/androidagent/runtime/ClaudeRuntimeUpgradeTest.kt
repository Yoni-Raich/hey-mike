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
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** APK upgrades must reuse authenticated installed bytes before attempting any network work. */
class ClaudeRuntimeUpgradeTest {
    private val fixture = Files.createTempDirectory("claude-apk-upgrade").toFile()
    private val root = File(fixture, "claude").apply { mkdirs() }
    private val oldBytes = "synthetic official legacy 285".toByteArray()
    private val bundledBytes = "synthetic official bundled 293".toByteArray()
    private val nextBytes = "synthetic official candidate 294".toByteArray()
    private val legacy = pin("2.1.285", oldBytes)
    private val bundled = pin("2.1.293", bundledBytes)
    private val next = pin("2.1.294", nextBytes)
    private val downloads = mutableListOf<String>()
    private var checks = 0
    private var offline = false
    private var binaryOffline = false
    private var corruptCandidate = false

    private fun pin(version: String, bytes: ByteArray) = ClaudeBinaryPin(
        version,
        "${ClaudeReleaseClient.BASE}/$version/linux-arm64-musl/claude",
        dev.androidagent.core.NetDiagnostics.sha256Hex(bytes),
        bytes.size.toLong(),
    )

    private fun bytesFor(pin: ClaudeBinaryPin): ByteArray = when (pin.version) {
        legacy.version -> oldBytes
        bundled.version -> bundledBytes
        next.version -> nextBytes
        else -> error("Unexpected synthetic download: ${pin.version}")
    }

    private fun installer(pin: ClaudeBinaryPin) = ClaudeRuntimeInstaller(
        root,
        pin,
        openConnection = { url ->
            downloads += url.toString()
            if (binaryOffline) error("Offline binary download fixture")
            val original = bytesFor(pin)
            val bytes = if (corruptCandidate) changed(original) else original
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = HTTP_OK
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        },
        usableSpace = { Long.MAX_VALUE },
    )

    private fun updates(validate: suspend (File, ClaudeBinaryPin) -> Boolean = { _, _ -> true }) =
        ClaudeRuntimeUpdates(
            bundled = installer(bundled),
            latest = { checks++; if (offline) error("Offline fixture") else next },
            createInstaller = { installer(it) },
            validate = validate,
            now = { 1_000_000L },
            legacyPins = listOf(legacy),
        )

    private fun seed(pin: ClaudeBinaryPin, bytes: ByteArray = bytesFor(pin)): File =
        File(root, "${pin.version}/claude").apply { parentFile.mkdirs(); writeBytes(bytes) }

    private fun savedPin(name: String, pin: ClaudeBinaryPin) {
        File(root, name).writeText(buildJsonObject {
            put("version", pin.version)
            put("size", pin.size)
            put("sha256", pin.sha256)
        }.toString())
    }

    private fun changed(bytes: ByteArray): ByteArray = bytes.copyOf().also {
        it[0] = (it[0].toInt() xor 1).toByte()
    }

    private fun assertInstalled(updater: ClaudeRuntimeUpdates, pin: ClaudeBinaryPin) {
        assertEquals(pin, updater.activePin)
        assertEquals(ClaudeInstallPhase.INSTALLED, updater.state.value.phase)
    }

    @After fun clean() { fixture.deleteRecursively() }

    @Test fun existingLegacyWithoutSelectionIsAdoptedOfflineWithoutDownload() = runBlocking {
        val old = seed(legacy)
        offline = true
        val updater = updates()

        assertEquals(old, updater.refresh())
        assertInstalled(updater, legacy)
        assertEquals(0, checks)
        assertTrue(downloads.isEmpty())
        assertTrue(File(root, "active.json").readText().contains(legacy.sha256))
        assertEquals(old, updater.refresh())
        assertInstalled(updater, legacy)
        assertArrayEquals(oldBytes, old.readBytes())
        assertTrue(downloads.isEmpty())
    }

    @Test fun migratedLegacySelectionSurvivesRestartWithoutNetwork() = runBlocking {
        val old = seed(legacy)
        assertEquals(old, updates().refresh())
        offline = true

        val restarted = updates()
        assertEquals(old, restarted.refresh())
        assertInstalled(restarted, legacy)
        assertEquals(old, restarted.install())
        assertEquals(0, checks)
        assertTrue(downloads.isEmpty())
    }

    @Test fun migrationCheckUsesBundledPinBeforeLatestDiscovery() = runBlocking {
        val old = seed(legacy)
        offline = true
        val updater = updates()
        assertEquals(old, updater.refresh())

        assertTrue(updater.check())
        assertInstalled(updater, bundled)
        assertEquals(0, checks)
        assertEquals(listOf(bundled.url), downloads)
        assertArrayEquals(oldBytes, old.readBytes())
        val restarted = updates()
        assertEquals(File(root, "${bundled.version}/claude"), restarted.refresh())
        assertInstalled(restarted, bundled)
        assertArrayEquals(bundledBytes, restarted.refresh()!!.readBytes())
    }

    @Test fun offlineNetworkKeepsLegacyWhileBundledUpgradeCannotDownload() = runBlocking {
        val old = seed(legacy)
        offline = true
        binaryOffline = true
        val updater = updates()
        assertEquals(old, updater.refresh())

        assertFalse(updater.check())
        assertInstalled(updater, legacy)
        assertEquals(0, checks)
        assertEquals(listOf(bundled.url), downloads)
        assertFalse(File(root, "${bundled.version}/claude").exists())
        assertArrayEquals(oldBytes, old.readBytes())
        val restarted = updates()
        assertEquals(old, restarted.refresh())
        assertInstalled(restarted, legacy)
    }

    @Test fun firstInstallPersistsSelectionAndRestartKeepsIt() = runBlocking {
        val updater = updates()
        assertNull(updater.refresh())

        val current = updater.install()
        assertInstalled(updater, bundled)
        assertTrue(File(root, "active.json").isFile)
        assertTrue(File(root, "active.json").readText().contains(bundled.sha256))
        assertArrayEquals(bundledBytes, current.readBytes())
        val restarted = updates()
        assertEquals(current, restarted.refresh())
        assertInstalled(restarted, bundled)
        assertEquals(current, restarted.install())
        assertEquals(listOf(bundled.url), downloads)
        assertEquals(0, checks)
    }

    @Test fun downgradeBuildKeepsVerifiedNewerSelectionAcrossRestart() = runBlocking {
        val old = seed(legacy)
        val newer = seed(next)
        savedPin("active.json", next)
        fun olderBuild() = ClaudeRuntimeUpdates(
            bundled = installer(legacy),
            latest = { checks++; error("Downgrade must not need network discovery") },
            createInstaller = { installer(it) },
            validate = { _, _ -> error("Existing verified selection does not need an update probe") },
            legacyPins = emptyList(),
        )
        val updater = olderBuild()

        assertEquals(newer, updater.refresh())
        assertInstalled(updater, next)
        assertEquals(newer, olderBuild().refresh())
        assertArrayEquals(nextBytes, newer.readBytes())
        assertArrayEquals(oldBytes, old.readBytes())
        assertEquals(0, checks)
        assertTrue(downloads.isEmpty())
    }

    @Test fun installedBundledVersionIsPreferredOverUnselectedLegacy() = runBlocking {
        val old = seed(legacy)
        val current = seed(bundled)
        val updater = updates()

        assertEquals(current, updater.refresh())
        assertInstalled(updater, bundled)
        assertArrayEquals(oldBytes, old.readBytes())
        assertTrue(downloads.isEmpty())
    }

    @Test fun explicitVerifiedLegacySelectionIsKeptEvenWhenBundledExists() = runBlocking {
        val old = seed(legacy)
        val current = seed(bundled)
        savedPin("active.json", legacy)
        val updater = updates()

        assertEquals(old, updater.refresh())
        assertInstalled(updater, legacy)
        assertArrayEquals(bundledBytes, current.readBytes())
        assertTrue(downloads.isEmpty())
    }

    @Test fun verifiedPreviousLegacyIsRecoveredWhenActiveSelectionIsMissing() = runBlocking {
        val old = seed(legacy)
        seed(bundled)
        savedPin("previous.json", legacy)
        val updater = updates()

        assertEquals(old, updater.refresh())
        assertInstalled(updater, legacy)
        assertTrue(downloads.isEmpty())
    }

    @Test fun candidateLaunchFailureKeepsMigratedLegacyAcrossRestart() = runBlocking {
        val old = seed(legacy)
        val updater = updates(validate = { _, _ -> false })
        assertEquals(old, updater.refresh())

        assertFalse(updater.check())
        assertInstalled(updater, legacy)
        assertArrayEquals(oldBytes, old.readBytes())
        assertEquals(old, updates().refresh())
        assertEquals(listOf(bundled.url), downloads)
    }

    @Test fun candidateHashFailureKeepsMigratedLegacyAcrossRestart() = runBlocking {
        val old = seed(legacy)
        val updater = updates()
        assertEquals(old, updater.refresh())
        corruptCandidate = true

        assertFalse(updater.check())
        assertInstalled(updater, legacy)
        assertEquals(old, updates().refresh())
        assertArrayEquals(oldBytes, old.readBytes())
        assertFalse(File(root, "${bundled.version}/claude").exists())
    }

    @Test fun cancellingCandidateValidationKeepsMigratedLegacyAcrossRestart() = runBlocking {
        val old = seed(legacy)
        val entered = CompletableDeferred<Unit>()
        val updater = updates(validate = { _, _ -> entered.complete(Unit); awaitCancellation() })
        assertEquals(old, updater.refresh())
        val checking = async { updater.check() }

        withTimeout(5_000) { entered.await() }
        assertInstalled(updater, legacy)
        updater.cancel()
        checking.cancelAndJoin()
        assertEquals(old, updates().refresh())
        assertArrayEquals(oldBytes, old.readBytes())
    }

    @Test fun corruptLegacyIsRejectedDespiteMatchingVersionAndVerificationFilename() = runBlocking {
        val badBytes = changed(oldBytes)
        val old = seed(legacy, badBytes)
        File(old.parentFile, ClaudeRuntimeInstaller.VERIFIED_NAME).writeText(
            "version=${legacy.version}\nsize=${legacy.size}\nlastModified=${old.lastModified()}\n" +
                "sha256=${dev.androidagent.core.NetDiagnostics.sha256Hex(badBytes)}\n",
        )
        val updater = updates()

        assertNull(updater.refresh())
        assertEquals(ClaudeInstallPhase.NOT_INSTALLED, updater.state.value.phase)
        assertFalse(File(root, "active.json").exists())
        assertEquals(0, checks)
        assertTrue(downloads.isEmpty())
    }

    @Test fun knownLegacyVersionWithAlteredSavedMetadataIsNotAuthenticated() = runBlocking {
        val badBytes = changed(oldBytes)
        seed(legacy, badBytes)
        savedPin("active.json", pin(legacy.version, badBytes))
        val updater = updates()

        assertNull(updater.refresh())
        assertEquals(ClaudeInstallPhase.NOT_INSTALLED, updater.state.value.phase)
        assertTrue(downloads.isEmpty())
    }

    @Test fun unknownOlderBinaryAndSelfClaimedSelectionAreNotAdoptedOrRemoved() = runBlocking {
        val unknownBytes = "unknown older binary".toByteArray()
        val unknown = pin("2.1.284", unknownBytes)
        val unknownFile = seed(unknown, unknownBytes)
        savedPin("active.json", unknown)
        val updater = updates()

        assertNull(updater.refresh())
        assertEquals(ClaudeInstallPhase.NOT_INSTALLED, updater.state.value.phase)
        assertArrayEquals(unknownBytes, unknownFile.readBytes())
        assertEquals(0, checks)
        assertTrue(downloads.isEmpty())
    }

    @Test fun escapedSelectionIsIgnoredWithoutTouchingOutsideBinary() = runBlocking {
        val old = seed(legacy)
        val outside = File(fixture, "outside/claude").apply { parentFile.mkdirs(); writeBytes(nextBytes) }
        savedPin("active.json", pin("../outside", nextBytes))
        val updater = updates()

        assertEquals(old, updater.refresh())
        assertInstalled(updater, legacy)
        assertArrayEquals(nextBytes, outside.readBytes())
        assertTrue(downloads.isEmpty())
    }

    @Test fun corruptNewerActiveVersionRollsBackToVerifiedLegacy() = runBlocking {
        val old = seed(legacy)
        seed(next, changed(nextBytes))
        savedPin("active.json", next)
        savedPin("previous.json", legacy)
        val updater = updates()

        assertEquals(old, updater.refresh())
        assertInstalled(updater, legacy)
        assertEquals(old, updates().refresh())
        assertArrayEquals(oldBytes, old.readBytes())
        assertTrue(downloads.isEmpty())
    }

    @Test fun failedSelectionPersistenceDoesNotHideAnExistingVerifiedRuntime() = runBlocking {
        val old = seed(legacy)
        val selectionDirectory = File(root, "active.json").apply { mkdirs() }
        val sentinel = File(selectionDirectory, "keep").apply { writeText("owned fixture sentinel") }
        val updater = updates()

        assertEquals(old, updater.refresh())
        assertInstalled(updater, legacy)
        assertEquals(old, updater.refresh())
        assertEquals(old, updates().refresh())
        assertEquals("owned fixture sentinel", sentinel.readText())
        assertArrayEquals(oldBytes, old.readBytes())
        assertTrue(downloads.isEmpty())
    }
}
