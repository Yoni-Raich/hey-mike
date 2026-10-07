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

import dev.androidagent.core.NetDiagnostics
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClaudeRuntimeInstallerTest {
    private lateinit var root: File
    private val payload = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
    private val pin = ClaudeBinaryPin(
        version = "9.9.9",
        url = "https://downloads.claude.ai/claude-code-releases/9.9.9/linux-arm64-musl/claude",
        sha256 = NetDiagnostics.sha256Hex(payload),
        size = payload.size.toLong(),
    )
    private val opened = mutableListOf<FakeConnection>()
    private var hashes = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("claude-installer").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun installer(
        body: () -> InputStream = { ByteArrayInputStream(payload) },
        code: Int = 200,
        length: Long = payload.size.toLong(),
        space: Long = Long.MAX_VALUE,
        pinned: ClaudeBinaryPin = pin,
        supported: Boolean = true,
    ) = ClaudeRuntimeInstaller(
        installRoot = File(root, "claude"),
        pin = pinned,
        supported = supported,
        openConnection = { url -> FakeConnection(url, body(), code, length).also { opened += it } },
        usableSpace = { space },
        hasher = { file -> hashes++; ClaudeRuntimeInstaller.sha256(file) },
    )

    private val binary: File get() = File(root, "claude/9.9.9/claude")
    private val record: File get() = File(root, "claude/9.9.9/${ClaudeRuntimeInstaller.VERIFIED_NAME}")

    private fun leftovers(): List<String> =
        File(root, "claude").walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList().sorted()

    @Test
    fun `install downloads verifies and renames atomically`() = runBlocking<Unit> {
        val installer = installer()
        val binary = installer.install()

        assertEquals(File(root, "claude/9.9.9/claude"), binary)
        assertTrue(binary.readBytes().contentEquals(payload))
        assertEquals(listOf("claude/9.9.9/claude", "claude/9.9.9/claude.verified"), leftovers())
        val state = installer.state.value
        assertEquals(ClaudeInstallPhase.INSTALLED, state.phase)
        assertEquals(payload.size.toLong(), state.bytesDownloaded)
        assertEquals(payload.size.toLong(), state.totalBytes)
        assertTrue(opened.single().disconnected)
    }

    @Test
    fun `hash mismatch deletes the download`() = runBlocking<Unit> {
        val wrong = payload.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val installer = installer(body = { ByteArrayInputStream(wrong) })

        assertFailsWith<IllegalStateException> { installer.install() }
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(ClaudeInstallPhase.FAILED, installer.state.value.phase)
        assertFalse(installer.binaryFile.exists())
    }

    @Test
    fun `short download fails and deletes the partial`() = runBlocking<Unit> {
        val short = payload.copyOf(payload.size - 1)
        val installer = installer(body = { ByteArrayInputStream(short) }, length = -1)

        assertFailsWith<IllegalStateException> { installer.install() }
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(ClaudeInstallPhase.FAILED, installer.state.value.phase)
    }

    @Test
    fun `long download stops at the pinned size`() = runBlocking<Unit> {
        val long = payload + ByteArray(4096)
        val installer = installer(body = { ByteArrayInputStream(long) }, length = -1)

        assertFailsWith<IllegalStateException> { installer.install() }
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `content length that differs from the pin fails before download`() = runBlocking<Unit> {
        var read = false
        val installer = installer(
            body = { object : InputStream() { override fun read(): Int { read = true; return -1 } } },
            length = payload.size + 1L,
        )

        assertFailsWith<IllegalStateException> { installer.install() }
        assertFalse(read)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `http error fails without files`() = runBlocking<Unit> {
        val installer = installer(code = 404)

        assertFailsWith<IllegalStateException> { installer.install() }
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(ClaudeInstallPhase.FAILED, installer.state.value.phase)
    }

    @Test
    fun `cancel stops the download and deletes the partial`() = runBlocking<Unit> {
        lateinit var installer: ClaudeRuntimeInstaller
        val source = ByteArrayInputStream(payload)
        var total = 0
        val body = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (total > 1024 * 1024) installer.cancel()
                val count = source.read(b, off, minOf(len, 64 * 1024))
                if (count > 0) total += count
                return count
            }
        }
        installer = installer(body = { body })

        assertFailsWith<CancellationException> { installer.install() }
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(ClaudeInstallPhase.CANCELLED, installer.state.value.phase)
        assertTrue(total < payload.size)
    }

    @Test
    fun `other versions and stale partials are removed`() = runBlocking<Unit> {
        File(root, "claude/2.0.0").mkdirs()
        File(root, "claude/2.0.0/claude").writeText("old")
        File(root, "claude/9.9.9").mkdirs()
        File(root, "claude/9.9.9/claude.part").writeText("killed mid download")
        File(root, "claude/stray-file").writeText("x")
        val installer = installer()

        installer.install()
        assertEquals(listOf("claude/9.9.9/claude", "claude/9.9.9/claude.verified"), leftovers())
        assertFalse(File(root, "claude/2.0.0").exists())
    }

    @Test
    fun `refresh removes old versions and accepts a verified binary`() = runBlocking<Unit> {
        File(root, "claude/2.0.0").mkdirs()
        File(root, "claude/2.0.0/claude").writeText("old")
        File(root, "claude/9.9.9").mkdirs()
        File(root, "claude/9.9.9/claude").writeBytes(payload)
        val installer = installer()

        assertEquals(File(root, "claude/9.9.9/claude"), installer.refresh())
        assertEquals(ClaudeInstallPhase.INSTALLED, installer.state.value.phase)
        assertEquals(listOf("claude/9.9.9/claude", "claude/9.9.9/claude.verified"), leftovers())
        assertTrue(opened.isEmpty())
        assertEquals(1, hashes)
    }

    @Test
    fun `a later start trusts the record and does not hash again`() = runBlocking<Unit> {
        installer().install()
        assertEquals("the download is hashed as it streams", 0, hashes)
        assertTrue(record.isFile)

        val restarted = installer()
        assertEquals(binary, restarted.refresh())
        assertEquals(ClaudeInstallPhase.INSTALLED, restarted.state.value.phase)
        assertEquals(0, hashes)
        assertEquals(1, opened.size)
    }

    @Test
    fun `a size change hashes again and removes the wrong binary`() = runBlocking<Unit> {
        installer().install()
        binary.appendBytes(byteArrayOf(1))

        assertNull(installer().refresh())
        assertEquals("a wrong size fails before any hash", 0, hashes)
        assertFalse(binary.exists())
        assertFalse(record.exists())
    }

    @Test
    fun `a new modification time hashes again then trusts the new record`() = runBlocking<Unit> {
        installer().install()
        assertTrue(binary.setLastModified(binary.lastModified() - 60_000))

        assertEquals(binary, installer().refresh())
        assertEquals(1, hashes)
        assertEquals(binary, installer().refresh())
        assertEquals("the rewritten record holds", 1, hashes)
    }

    @Test
    fun `a record for another pin is not trusted`() = runBlocking<Unit> {
        installer().install()
        val otherPin = pin.copy(sha256 = "0".repeat(64))

        assertNull(installer(pinned = otherPin).refresh())
        assertEquals(1, hashes)
        assertFalse(binary.exists())
    }

    @Test
    fun `a record naming another version is not trusted`() = runBlocking<Unit> {
        installer().install()
        record.writeText(record.readText().replace("version=9.9.9", "version=9.9.8"))

        assertEquals(binary, installer().refresh())
        assertEquals(1, hashes)
        assertTrue(record.readText().contains("version=9.9.9"))
    }

    @Test
    fun `a corrupt record hashes again and is rewritten`() = runBlocking<Unit> {
        installer().install()
        record.writeText("not a record")

        assertEquals(binary, installer().refresh())
        assertEquals(1, hashes)
        assertEquals(binary, installer().refresh())
        assertEquals(1, hashes)
    }

    @Test
    fun `a record never vouches for a fresh download`() = runBlocking<Unit> {
        installer().install()
        val good = record.readText()
        binary.delete()
        record.writeText(good)
        val wrong = payload.copyOf().also { it[7] = (it[7] + 1).toByte() }

        assertFailsWith<IllegalStateException> { installer(body = { ByteArrayInputStream(wrong) }).install() }
        assertFalse(binary.exists())
        assertFalse(record.exists())
    }

    @Test
    fun `refresh deletes a binary that no longer matches the pin`() = runBlocking<Unit> {
        File(root, "claude/9.9.9").mkdirs()
        File(root, "claude/9.9.9/claude").writeBytes(payload.copyOf().also { it[0] = 42 })
        val installer = installer()

        assertNull(installer.refresh())
        assertEquals(ClaudeInstallPhase.NOT_INSTALLED, installer.state.value.phase)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `install is a no-op when the verified binary is present`() = runBlocking<Unit> {
        val installer = installer()
        installer.install()
        installer.install()
        assertEquals(1, opened.size)
    }

    @Test
    fun `too little free space fails before any download`() = runBlocking<Unit> {
        val installer = installer(space = payload.size.toLong())

        assertFailsWith<IllegalStateException> { installer.install() }
        assertTrue(opened.isEmpty())
        assertEquals(ClaudeInstallPhase.FAILED, installer.state.value.phase)
        assertTrue(installer.state.value.message.contains("space"))
    }

    @Test
    fun `non https pin is refused`() = runBlocking<Unit> {
        val installer = installer(pinned = pin.copy(url = "http://downloads.claude.ai/claude"))

        assertFailsWith<IllegalStateException> { installer.install() }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `unsupported device never downloads`() = runBlocking<Unit> {
        val installer = installer(supported = false)

        assertEquals(ClaudeInstallPhase.UNSUPPORTED, installer.state.value.phase)
        assertFailsWith<IllegalStateException> { installer.install() }
        assertNull(installer.refresh())
        assertEquals(ClaudeInstallPhase.UNSUPPORTED, installer.state.value.phase)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `current pin matches the verified Haiku 5_5 release`() {
        val current = ClaudeBinaryPin.CURRENT
        assertEquals("2.1.293", current.version)
        assertEquals("https://downloads.claude.ai/claude-code-releases/2.1.293/linux-arm64-musl/claude", current.url)
        assertEquals("00755ae106b6925c1adb2b17e4c6d9b0c41eb4cc55bcad3581345b0aa3176c85", current.sha256)
        assertEquals(244_463_424L, current.size)
        assertTrue(ClaudeRuntimeInstaller.requiredFreeBytes(current) > 250L * 1024 * 1024)
    }

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return
            throw AssertionError("expected ${T::class.simpleName}, got $failure", failure)
        }
        fail("expected ${T::class.simpleName}")
    }

    private class FakeConnection(
        url: URL,
        private val body: InputStream,
        private val code: Int,
        private val length: Long,
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream = body
        override fun getContentLengthLong(): Long = length
    }
}
