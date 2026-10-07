/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license. See LICENSE,
 * LICENSE-COMMERCIAL.md and NOTICE. Distributed without any warranty.
 */

package dev.androidagent.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClaudeRuntimeProbeTest {
    private val reply = """{"type":"control_response","response":{"request_id":"mike-update-check","subtype":"success","response":{"models":[{"value":"haiku","displayName":"Haiku"}]}}}"""

    @Test fun probeUsesVersionAndInitializeOnlyAndStopsEveryProcess() = runBlocking {
        val processes = mutableListOf<ProcessStub>()
        val args = mutableListOf<List<String>>()
        assertTrue(ClaudeRuntimeProbe.verify("2.1.294") { command ->
            args += command
            ProcessStub(if (command == listOf("--version")) "2.1.294 (Claude Code)\n" else reply + "\n")
                .also { processes += it }
        })
        assertEquals(listOf("--version"), args.first())
        assertTrue("--no-session-persistence" in args.last())
        assertTrue("--strict-mcp-config" in args.last())
        assertTrue(processes.all { it.destroyed })
        assertFalse(processes.any { it.stdin.toString().contains("\"type\":\"user\"") })
        assertTrue(processes.last().stdin.toString().contains("\"subtype\":\"initialize\""))
    }

    @Test fun wrongVersionDoesNotAttemptInitialize() = runBlocking {
        var count = 0
        assertFalse(ClaudeRuntimeProbe.verify("2.1.294") { count++; ProcessStub("2.1.293 (Claude Code)\n") })
        assertEquals(1, count)
    }

    @Test fun nonzeroExitDoesNotPassVersionCheck() = runBlocking {
        assertFalse(ClaudeRuntimeProbe.verify("2.1.294") { ProcessStub("2.1.294 (Claude Code)\n", exit = 1) })
    }

    @Test fun malformedWrongRequestErrorAndEmptyModelsCannotPassCompatibilityCheck() {
        assertTrue(ClaudeRuntimeProbe.acceptsInitialize(reply))
        for (bad in listOf("not JSON", reply.replace("success", "error"), reply.replace("mike-update-check", "other"),
            reply.replace("""[{"value":"haiku","displayName":"Haiku"}]""", "[]"),
            reply.replace("\"value\":\"haiku\"", "\"value\":\"\""))) {
            assertFalse(ClaudeRuntimeProbe.acceptsInitialize(bad))
        }
    }

    private class ProcessStub(stdout: String, private val exit: Int = 0) : Process() {
        val stdin = ByteArrayOutputStream()
        var destroyed = false
        private val out = ByteArrayInputStream(stdout.toByteArray())
        override fun getInputStream() = out
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = stdin
        override fun waitFor() = exit
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = exit
        override fun isAlive() = false
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
