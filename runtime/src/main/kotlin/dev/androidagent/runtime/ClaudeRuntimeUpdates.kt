/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license. See LICENSE,
 * LICENSE-COMMERCIAL.md and NOTICE. Distributed without any warranty.
 */

package dev.androidagent.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Side-by-side updates after the user has installed Claude. Only a verified, launch-tested
 * candidate becomes active. Old files stay readable by live turns and available for rollback.
 * No account or chat files are read. Checks are throttled across app restarts.
 */
class ClaudeRuntimeUpdates(
    private val bundled: ClaudeRuntimeInstaller,
    private val latest: suspend () -> ClaudeBinaryPin = { ClaudeReleaseClient().latest() },
    private val createInstaller: (ClaudeBinaryPin) -> ClaudeRuntimeInstaller = { ClaudeRuntimeInstaller(bundled.installRoot, it) },
    private val validate: suspend (File, ClaudeBinaryPin) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val canDownload: () -> Boolean = { true },
) {
    private val root = bundled.installRoot
    private val selection = Mutex()
    private val update = Mutex()
    @Volatile private var restored = false
    @Volatile private var active = bundled
    @Volatile private var downloading: ClaudeRuntimeInstaller? = null
    @Volatile private var updateJob: kotlinx.coroutines.Job? = null
    private val mutableState = MutableStateFlow(bundled.state.value)
    val state: StateFlow<ClaudeInstallState> = mutableState
    val activePin: ClaudeBinaryPin get() = active.pin

    suspend fun refresh(): File? = selection.withLock {
        withContext(Dispatchers.IO) {
            if (bundled.state.value.phase == ClaudeInstallPhase.UNSUPPORTED) {
                mutableState.value = bundled.state.value
                return@withContext null
            }
            if (!restored) {
                readPin(File(root, "active.json"))?.let { active = createInstaller(it) }
                restored = true
            }
            var binary = active.refresh()
            if (binary == null && active !== bundled) {
                val previous = readPin(File(root, "previous.json"))?.let(createInstaller)
                binary = previous?.refresh()
                active = if (binary != null) previous!! else bundled
                if (binary == null) binary = bundled.refresh()
                if (binary != null) writePin(File(root, "active.json"), active.pin)
            }
            mutableState.value = active.state.value.copy(updateMessage = mutableState.value.updateMessage)
            binary
        }
    }

    /** First installation still follows the existing explicit Download action. */
    suspend fun install(): File = update.withLock {
        refresh()?.let { return@withLock it }
        mirror(active) { active.install() }
    }

    fun cancel() {
        (downloading ?: active).cancel()
        updateJob?.cancel(CancellationException("Claude update cancelled"))
    }

    /** Quiet when absent/unsupported/offline. A failed update never makes the working runtime unavailable. */
    suspend fun check(): Boolean {
        if (!update.tryLock()) return false
        updateJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        try {
            if (refresh() == null) return false
            val at = now()
            val last = withContext(Dispatchers.IO) {
                File(root, "last-check").takeIf { it.isFile && it.length() < 64 }?.readText()?.toLongOrNull()
            }
            if (last != null && at >= last && at - last < CHECK_INTERVAL_MS) return false
            withContext(Dispatchers.IO) { atomicWrite(File(root, "last-check"), at.toString()) }
            mutableState.value = active.state.value.copy(updateMessage = "Checking for Claude updates")
            val candidatePin = latest()
            require(candidatePin.url == "${ClaudeReleaseClient.BASE}/${candidatePin.version}/linux-arm64-musl/claude")
            if (!ClaudeReleaseClient.newer(candidatePin.version, active.pin.version)) {
                mutableState.value = active.state.value
                return false
            }
            if (!canDownload()) {
                // Try again on the next foreground/network check rather than waiting a full interval.
                withContext(Dispatchers.IO) { File(root, "last-check").delete() }
                mutableState.value = active.state.value.copy(updateMessage = "Claude update will download on an unmetered connection")
                return false
            }
            val candidate = createInstaller(candidatePin)
            val binary = mirror(candidate) { candidate.install() }
            mutableState.value = active.state.value.copy(updateMessage = "Checking Claude ${candidatePin.version}")
            check(validate(binary, candidatePin)) { "New Claude version is not compatible" }
            selection.withLock {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    writePin(File(root, "previous.json"), active.pin)
                    writePin(File(root, "active.json"), candidatePin)
                    active = candidate
                    mutableState.value = candidate.state.value.copy(updateMessage = "Updated to Claude ${candidatePin.version}")
                }
            }
            return true
        } catch (cancelled: CancellationException) {
            mutableState.value = active.state.value.copy(updateMessage = "Update cancelled; keeping Claude ${active.pin.version}")
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = active.state.value.copy(updateMessage = "Update could not finish; keeping Claude ${active.pin.version}. Will retry later.")
            return false
        } finally {
            downloading = null
            updateJob = null
            update.unlock()
        }
    }

    private suspend fun <T> mirror(installer: ClaudeRuntimeInstaller, block: suspend () -> T): T = coroutineScope {
        downloading = installer
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            installer.state.collect { progress ->
                mutableState.value = if (active.state.value.phase == ClaudeInstallPhase.INSTALLED && installer !== active) {
                    active.state.value.copy(
                        bytesDownloaded = progress.bytesDownloaded,
                        totalBytes = progress.totalBytes,
                        updateMessage = progress.message,
                    )
                } else progress
            }
        }
        try { block() } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                collector.cancelAndJoin()
                downloading = null
                mutableState.value = active.state.value
            }
        }
    }

    private fun readPin(file: File): ClaudeBinaryPin? = runCatching {
        if (!file.isFile || file.length() > 4096) return@runCatching null
        val fields = Json.parseToJsonElement(file.readText()) as JsonObject
        val version = fields["version"]!!.jsonPrimitive.content
        val size = fields["size"]!!.jsonPrimitive.longOrNull ?: return@runCatching null
        val hash = fields["sha256"]!!.jsonPrimitive.content
        require(ClaudeReleaseClient.validVersion(version) && hash.matches(Regex("[0-9a-f]{64}")) && size in 1..(1024L * 1024 * 1024))
        require(version == bundled.pin.version || ClaudeReleaseClient.newer(version, bundled.pin.version))
        require(version.substringBefore('.') == bundled.pin.version.substringBefore('.'))
        ClaudeBinaryPin(version, "${ClaudeReleaseClient.BASE}/$version/linux-arm64-musl/claude", hash, size)
    }.getOrNull()

    private fun writePin(file: File, pin: ClaudeBinaryPin) = atomicWrite(file, buildJsonObject {
        put("version", pin.version); put("size", pin.size); put("sha256", pin.sha256)
    }.toString())

    private fun atomicWrite(file: File, text: String) {
        root.mkdirs()
        val temp = File(root, file.name + ".tmp")
        try {
            java.io.FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    }
}
