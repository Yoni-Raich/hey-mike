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
    )

    private fun leftovers(): List<String> =
        File(root, "claude").walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()

    @Test
    fun `install downloads verifies and renames atomically`() = runBlocking<Unit> {
        val installer = installer()
        val binary = installer.install()

        assertEquals(File(root, "claude/9.9.9/claude"), binary)
        assertTrue(binary.readBytes().contentEquals(payload))
        assertEquals(listOf("claude/9.9.9/claude"), leftovers())
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
        assertEquals(listOf("claude/9.9.9/claude"), leftovers())
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
        assertEquals(listOf("claude/9.9.9/claude"), leftovers())
        assertTrue(opened.isEmpty())
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
    fun `current pin matches the approved spec`() {
        val current = ClaudeBinaryPin.CURRENT
        assertEquals("2.1.285", current.version)
        assertEquals("https://downloads.claude.ai/claude-code-releases/2.1.285/linux-arm64-musl/claude", current.url)
        assertEquals("31efc4136bc678575f4c6730e248d34f89dbfea0468be1c5d012af199cd62ee8", current.sha256)
        assertEquals(232_077_120L, current.size)
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
