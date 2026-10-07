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

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A Claude Code build, from the bundled pin or a verified official release manifest. */
data class ClaudeBinaryPin(val version: String, val url: String, val sha256: String, val size: Long) {
    companion object {
        /** Official 2.1.293 linux-arm64-musl manifest, verified for Haiku 5.5. */
        val CURRENT = ClaudeBinaryPin(
            version = "2.1.293",
            url = "https://downloads.claude.ai/claude-code-releases/2.1.293/linux-arm64-musl/claude",
            sha256 = "00755ae106b6925c1adb2b17e4c6d9b0c41eb4cc55bcad3581345b0aa3176c85",
            size = 244_463_424L,
        )
    }
}

enum class ClaudeInstallPhase { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, FAILED, CANCELLED, UNSUPPORTED }

/** Download progress for the UI. [totalBytes] is the pinned size. */
data class ClaudeInstallState(
    val phase: ClaudeInstallPhase,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "",
    val updateMessage: String? = null,
)

/**
 * Downloads the official Claude Code binary to `<installRoot>/<version>/claude`.
 *
 * The binary is never bundled in the APK and never modified. The download
 * goes to `claude.part`, is hashed while it streams, must match the pinned
 * size and sha256, and is then renamed atomically. Any failure or cancel
 * deletes the partial file. Other versions are retained for running processes and rollback. Nothing here reads
 * the Claude config dir.
 *
 * After a good sha256 check, `claude.verified` next to the binary records the
 * version, size, modification time and sha256. A later start trusts it only
 * while all of those still match the pin and the file, so the 244 MB binary
 * is not hashed on every app start; anything else hashes it again.
 */
class ClaudeRuntimeInstaller(
    /** `filesDir/runtime/claude`: holds only version directories. */
    val installRoot: File,
    val pin: ClaudeBinaryPin = ClaudeBinaryPin.CURRENT,
    /** False on devices without the arm64 loader; the state stays UNSUPPORTED. */
    private val supported: Boolean = true,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val usableSpace: (File) -> Long = { it.usableSpace },
    /** Hashes a binary already on disk. Tests count the calls. */
    private val hasher: (File) -> String = { sha256(it) },
) {
    private val mutableState = MutableStateFlow(
        if (supported) ClaudeInstallState(ClaudeInstallPhase.NOT_INSTALLED, totalBytes = pin.size)
        else ClaudeInstallState(ClaudeInstallPhase.UNSUPPORTED, totalBytes = pin.size, message = UNSUPPORTED_MESSAGE)
    )
    val state: StateFlow<ClaudeInstallState> = mutableState

    val versionDirectory: File get() = File(installRoot, pin.version)
    val binaryFile: File get() = File(versionDirectory, BINARY_NAME)
    private val partialFile: File get() = File(versionDirectory, PARTIAL_NAME)
    /** The last good sha256 check, so an app start need not hash 244 MB again. */
    internal val verifiedRecordFile: File get() = File(versionDirectory, VERIFIED_NAME)
    private val verifiedRecordTemp: File get() = File(versionDirectory, "$VERIFIED_NAME.tmp")

    private val lock = Mutex()
    @Volatile private var cancelRequested = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    /** (length, lastModified) of the binary last verified in this process. */
    @Volatile private var verifiedKey: Pair<Long, Long>? = null

    /**
     * Clean up and report the installed state without downloading.
     * Returns the verified binary, or null when it is missing or wrong.
     */
    suspend fun refresh(): File? = lock.withLock {
        withContext(Dispatchers.IO) { refreshLocked() }
    }

    /** Download and verify the pinned binary if needed. Fails closed. */
    suspend fun install(): File = lock.withLock {
        withContext(Dispatchers.IO) { installLocked() }
    }

    /** Stop a running download. The partial file is deleted. */
    fun cancel() {
        cancelRequested = true
        activeConnection?.let { runCatching { it.disconnect() } }
    }

    private fun refreshLocked(): File? {
        if (!supported) {
            mutableState.value = ClaudeInstallState(ClaudeInstallPhase.UNSUPPORTED, totalBytes = pin.size, message = UNSUPPORTED_MESSAGE)
            return null
        }
        partialFile.delete()
        verifiedRecordTemp.delete()
        val binary = verifiedBinaryOrNull()
        mutableState.value = if (binary != null) {
            ClaudeInstallState(ClaudeInstallPhase.INSTALLED, pin.size, pin.size, "Claude Code ${pin.version} ready")
        } else {
            ClaudeInstallState(ClaudeInstallPhase.NOT_INSTALLED, totalBytes = pin.size, message = "Claude Code is not downloaded")
        }
        return binary
    }

    private suspend fun installLocked(): File {
        refreshLocked()?.let { return it }
        if (!supported) error(UNSUPPORTED_MESSAGE)
        cancelRequested = false
        try {
            check(pin.url.startsWith("https://")) { "Claude Code download must use HTTPS" }
            if (!versionDirectory.isDirectory && !versionDirectory.mkdirs() && !versionDirectory.isDirectory) {
                error("Cannot create ${versionDirectory.absolutePath}")
            }
            val needed = requiredFreeBytes(pin)
            val free = usableSpace(versionDirectory)
            check(free >= needed) {
                "Not enough free space: Claude Code needs ${needed / MIB} MB, ${free / MIB} MB free"
            }
            // A record never vouches for a new download: the bytes are hashed as they arrive.
            verifiedRecordFile.delete()
            publish(ClaudeInstallPhase.DOWNLOADING, 0, "Downloading Claude Code ${pin.version}")
            download()
            publish(ClaudeInstallPhase.VERIFYING, pin.size, "Verifying Claude Code")
            Files.move(
                partialFile.toPath(),
                binaryFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            verifiedKey = binaryFile.length() to binaryFile.lastModified()
            writeVerifiedRecord(binaryFile)
            publish(ClaudeInstallPhase.INSTALLED, pin.size, "Claude Code ${pin.version} ready")
            return binaryFile
        } catch (failure: Throwable) {
            partialFile.delete()
            val cancelled = failure is CancellationException || cancelRequested
            if (cancelled) {
                mutableState.value = ClaudeInstallState(
                    ClaudeInstallPhase.CANCELLED, totalBytes = pin.size, message = "Download cancelled"
                )
                throw failure as? CancellationException ?: CancellationException("Download cancelled")
            }
            mutableState.value = ClaudeInstallState(
                ClaudeInstallPhase.FAILED,
                totalBytes = pin.size,
                message = failure.message ?: "Claude Code download failed",
            )
            throw failure as? IllegalStateException ?: IllegalStateException(
                "Claude Code download failed: ${failure.message ?: failure.javaClass.simpleName}", failure
            )
        } finally {
            cancelRequested = false
        }
    }

    private suspend fun download() {
        val connection = openConnection(URL(pin.url))
        activeConnection = connection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "Claude Code download failed: HTTP $code" }
            val announced = connection.contentLengthLong
            check(announced < 0 || announced == pin.size) {
                "Claude Code download has size $announced, expected ${pin.size}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastPublished = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (cancelRequested) throw CancellationException("Download cancelled")
                        val count = input.read(buffer)
                        if (cancelRequested) throw CancellationException("Download cancelled")
                        if (count < 0) break
                        received += count
                        check(received <= pin.size) { "Claude Code download is larger than ${pin.size} bytes" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (received - lastPublished >= PROGRESS_STEP_BYTES) {
                            lastPublished = received
                            publish(ClaudeInstallPhase.DOWNLOADING, received, "Downloading Claude Code ${pin.version}")
                        }
                    }
                    output.fd.sync()
                }
            }
            check(received == pin.size) { "Claude Code download has $received bytes, expected ${pin.size}" }
            val actual = digest.digest().toHex()
            check(actual.equals(pin.sha256, ignoreCase = true)) {
                "Claude Code download does not match the pinned sha256"
            }
        } finally {
            activeConnection = null
            runCatching { connection.disconnect() }
        }
    }

    /**
     * The binary if its size and sha256 match the pin; a wrong file is deleted.
     * The hash is skipped only when the record of the last good check still
     * names this pin and the file's size and modification time are unchanged.
     */
    private fun verifiedBinaryOrNull(): File? {
        val binary = binaryFile
        if (!binary.isFile) {
            verifiedRecordFile.delete()
            return null
        }
        val key = binary.length() to binary.lastModified()
        if (key == verifiedKey) return binary
        if (recordStillHolds(binary)) {
            verifiedKey = key
            return binary
        }
        val matches = binary.length() == pin.size && hasher(binary).equals(pin.sha256, ignoreCase = true)
        if (!matches) {
            verifiedKey = null
            verifiedRecordFile.delete()
            binary.delete()
            return null
        }
        verifiedKey = key
        writeVerifiedRecord(binary)
        return binary
    }

    private fun recordStillHolds(binary: File): Boolean {
        val file = verifiedRecordFile
        if (!file.isFile || file.length() > MAX_RECORD_BYTES) return false
        val record = runCatching { VerifiedRecord.decode(file.readText()) }.getOrNull() ?: return false
        val modified = binary.lastModified()
        return record.version == pin.version &&
            record.sha256.equals(pin.sha256, ignoreCase = true) &&
            record.size == pin.size &&
            record.size == binary.length() &&
            modified > 0 &&
            record.lastModified == modified
    }

    /** Written only after a sha256 match, and atomically. A failed write just means hashing again next start. */
    private fun writeVerifiedRecord(binary: File) {
        val record = VerifiedRecord(pin.version, binary.length(), binary.lastModified(), pin.sha256.lowercase())
        runCatching {
            verifiedRecordTemp.writeText(record.encode())
            Files.move(
                verifiedRecordTemp.toPath(),
                verifiedRecordFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            verifiedRecordTemp.delete()
            verifiedRecordFile.delete()
        }
    }

    /** What was true of the binary when its sha256 last matched the pin. */
    internal data class VerifiedRecord(val version: String, val size: Long, val lastModified: Long, val sha256: String) {
        fun encode(): String = "version=$version\nsize=$size\nlastModified=$lastModified\nsha256=$sha256\n"

        companion object {
            /** Null for anything that is not a complete record. */
            fun decode(text: String): VerifiedRecord? {
                val fields = text.lines().mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
                }.toMap()
                val version = fields["version"]?.takeIf { it.isNotEmpty() } ?: return null
                val size = fields["size"]?.toLongOrNull() ?: return null
                val lastModified = fields["lastModified"]?.toLongOrNull() ?: return null
                val sha256 = fields["sha256"]?.takeIf { SHA256_HEX.matches(it) } ?: return null
                return VerifiedRecord(version, size, lastModified, sha256)
            }

            private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
        }
    }

    private fun publish(phase: ClaudeInstallPhase, bytes: Long, message: String) {
        mutableState.value = ClaudeInstallState(phase, bytes, pin.size, message)
    }

    companion object {
        const val BINARY_NAME = "claude"
        private const val PARTIAL_NAME = "claude.part"
        const val VERIFIED_NAME = "claude.verified"
        private const val MAX_RECORD_BYTES = 4L * 1024
        private const val MIB = 1024L * 1024L
        /** Room left after the download for sessions, temp files and updates. */
        const val FREE_SPACE_MARGIN_BYTES = 64L * MIB
        private const val BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 1L * MIB
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        const val UNSUPPORTED_MESSAGE = "Not available on this device"

        fun requiredFreeBytes(pin: ClaudeBinaryPin): Long = pin.size + FREE_SPACE_MARGIN_BYTES

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
