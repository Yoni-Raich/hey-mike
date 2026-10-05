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

import dev.androidagent.core.RuntimePhase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidClaudeHostTest {
    @Test
    fun `status follows the installer`() {
        fun status(phase: ClaudeInstallPhase, bytes: Long = 0) =
            AndroidClaudeHost.statusOf(ClaudeInstallState(phase, bytes, 200, "msg"))

        assertEquals(RuntimePhase.MISSING, status(ClaudeInstallPhase.NOT_INSTALLED).phase)
        assertEquals(RuntimePhase.MISSING, status(ClaudeInstallPhase.CANCELLED).phase)
        val downloading = status(ClaudeInstallPhase.DOWNLOADING, 50)
        assertEquals(RuntimePhase.PREPARING, downloading.phase)
        assertEquals(0.25f, downloading.progress!!, 0.0001f)
        assertEquals(RuntimePhase.PREPARING, status(ClaudeInstallPhase.VERIFYING, 200).phase)
        assertEquals(RuntimePhase.READY, status(ClaudeInstallPhase.INSTALLED, 200).phase)
        assertEquals(RuntimePhase.ERROR, status(ClaudeInstallPhase.FAILED).phase)
        val unsupported = status(ClaudeInstallPhase.UNSUPPORTED)
        assertEquals(RuntimePhase.ERROR, unsupported.phase)
        assertEquals(ClaudeRuntimeInstaller.UNSUPPORTED_MESSAGE, unsupported.message)
    }

    @Test
    fun `launch goes through the loader and never execs the binary`() {
        val loader = File("/data/app/lib/arm64/libld_musl.so")
        val binary = File("/data/user/0/app/files/runtime/claude/2.1.285/claude")
        val command = AndroidClaudeHost.launchCommand(loader, binary, listOf("auth", "status"))
        assertEquals(listOf(loader.absolutePath, binary.absolutePath, "auth", "status"), command)
    }

    @Test
    fun `stop destroys then force kills after the grace period`() {
        val polite = FakeProcess(exitsOnDestroy = true)
        val stubborn = FakeProcess(exitsOnDestroy = false)
        val started = System.nanoTime()

        AndroidClaudeHost.stopProcesses(listOf(polite, stubborn), graceMillis = 300)

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(polite.destroyed)
        assertFalse(polite.forced)
        assertTrue(stubborn.destroyed)
        assertTrue(stubborn.forced)
        assertFalse(stubborn.isAlive)
        assertTrue("waited $elapsedMs ms", elapsedMs >= 250)
    }

    @Test
    fun `stop with only polite processes does not wait for the grace period`() {
        val polite = FakeProcess(exitsOnDestroy = true)
        val started = System.nanoTime()
        AndroidClaudeHost.stopProcesses(listOf(polite), graceMillis = 5_000)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertFalse(polite.forced)
    }

    private class FakeProcess(private val exitsOnDestroy: Boolean) : Process() {
        @Volatile var destroyed = false
        @Volatile var forced = false
        @Volatile private var alive = true
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun isAlive(): Boolean = alive
        override fun destroy() {
            destroyed = true
            if (exitsOnDestroy) alive = false
        }
        override fun destroyForcibly(): Process {
            forced = true
            alive = false
            return this
        }
    }
}
