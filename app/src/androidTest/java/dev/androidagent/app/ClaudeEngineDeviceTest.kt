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

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.ClaudeProcessHost
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.engineclaude.ClaudeCodeEngine
import dev.androidagent.engineclaude.McpToolServer
import dev.androidagent.engineclaude.McpToolServerFactory
import dev.androidagent.mcp.LoopbackMcpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real Claude engine from the app process, without a sign-in.
 *
 * Runs the downloaded `claude` through the app's own [dev.androidagent.runtime.AndroidClaudeHost]
 * (musl loader, private HOME, CONNECT proxy) and a real [LoopbackMcpServer]
 * with the app's phone tools, then sends one message. `system/init` is
 * written before any model request, so it shows which tools reached the
 * CLI. Without a sign-in the turn must end quickly as failed.
 *
 * Skipped unless the binary is downloaded. Only `system/init` and the
 * `result` frame are read; nothing under the Claude config dir is touched.
 */
class ClaudeEngineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as AgentApplication).graph
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun initListsPhoneToolsAndAnUnsignedTurnFailsQuickly() = runBlocking {
        val host = graph.claudeHost
        assumeTrue("no arm64 loader", host.isSupported)
        assumeTrue("Claude Code is not downloaded", host.installer.refresh() != null)
        withTimeout(10_000) { host.status.first { it.phase == RuntimePhase.READY } }

        val stdout = ByteArrayOutputStream()
        val tee = TeeHost(host, stdout)
        val engine = ClaudeCodeEngine(
            tee,
            toolServers = McpToolServerFactory { name, tools, call ->
                val server = LoopbackMcpServer(name, tools, call)
                object : McpToolServer {
                    override fun start() = server.start().claudeMcpConfig(name)
                    override fun stop() = server.stop()
                }
            },
        )
        val workspace = File(instrumentation.targetContext.filesDir, "wpf-claude-test").apply { deleteRecursively(); mkdirs() }
        try {
            val definitions = graph.tools.definitions
            val thread = engine.openSession(workspace, null, "sonnet", definitions)
            val finished = async(start = CoroutineStart.UNDISPATCHED) {
                engine.events.first { it is EngineEvent.TurnFinished && it.threadId == thread } as EngineEvent.TurnFinished
            }
            val started = SystemClock.elapsedRealtime()
            engine.startTurn(thread, "Say hello.", emptyList())
            val end = withTimeoutOrNull(90_000) { finished.await() }
            val turnMs = SystemClock.elapsedRealtime() - started

            val lines = synchronized(stdout) { stdout.toString(Charsets.UTF_8.name()) }.lines()
                .mapNotNull { line -> runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() }
            val init = lines.firstOrNull { it.str("type") == "system" && it.str("subtype") == "init" }
            val result = lines.lastOrNull { it.str("type") == "result" }
            val tools = (init?.get("tools") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val mike = (init?.get("mcp_servers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("name") == "mike" }
            val report = buildString {
                appendLine("claude_code_version=${init?.str("claude_code_version")}")
                appendLine("model=${init?.str("model")} permissionMode=${init?.str("permissionMode")} apiKeySource=${init?.str("apiKeySource")}")
                appendLine("mcp_mike_status=${mike?.str("status")}")
                appendLine("phone_tool_definitions=${definitions.size}")
                appendLine("builtin_tools=${tools.filterNot { it.startsWith("mcp__") }}")
                appendLine("mcp_tools(${tools.count { it.startsWith("mcp__mike__") }})=${tools.filter { it.startsWith("mcp__") }}")
                appendLine("turn_status=${end?.status} turn_ms=$turnMs error=${end?.error}")
                appendLine("result_subtype=${result?.str("subtype")} is_error=${result?.get("is_error")} result=${result?.str("result")?.take(300)}")
                appendLine("frame_types=${lines.map { it.str("type") + "/" + it.str("subtype") }.distinct()}")
            }
            report.lines().forEach { Log.i(TAG, it) }
            File(instrumentation.targetContext.getExternalFilesDir(null), "claude-engine-device.txt").writeText(report)

            assertTrue("no system/init", init != null)
            assertEquals("connected", mike?.str("status"))
            assertTrue("no mcp__mike__ tools", tools.any { it.startsWith("mcp__mike__") })
            assertTrue("the turn did not end within 90 s", end != null)
            assertEquals("failed", end!!.status)
        } finally {
            engine.close()
            workspace.deleteRecursively()
        }
    }

    /**
     * Stop right after the turn starts: the turn must end (interrupted, or
     * failed if the CLI answered first) within the 3 s grace, and the chat
     * must take the next message.
     */
    @Test fun stopRightAfterStartEndsTheTurnAndTheChatStillWorks() = runBlocking {
        val host = graph.claudeHost
        assumeTrue("no arm64 loader", host.isSupported)
        assumeTrue("Claude Code is not downloaded", host.installer.refresh() != null)
        withTimeout(10_000) { host.status.first { it.phase == RuntimePhase.READY } }
        val engine = ClaudeCodeEngine(
            TeeHost(host, ByteArrayOutputStream()),
            toolServers = McpToolServerFactory { name, tools, call ->
                val server = LoopbackMcpServer(name, tools, call)
                object : McpToolServer {
                    override fun start() = server.start().claudeMcpConfig(name)
                    override fun stop() = server.stop()
                }
            },
        )
        val workspace = File(instrumentation.targetContext.filesDir, "wpf-claude-stop").apply { deleteRecursively(); mkdirs() }
        try {
            val thread = engine.openSession(workspace, null, "sonnet", graph.tools.definitions)
            suspend fun turn(stop: Boolean): Pair<EngineEvent.TurnFinished?, Long> {
                val finished = async(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.first { it is EngineEvent.TurnFinished && it.threadId == thread } as EngineEvent.TurnFinished
                }
                val turnId = engine.startTurn(thread, "Say hello.", emptyList())
                val stopped = SystemClock.elapsedRealtime()
                if (stop) engine.interrupt(thread, turnId)
                val end = withTimeoutOrNull(10_000) { finished.await() }
                return end to (SystemClock.elapsedRealtime() - stopped)
            }
            val (first, stopMs) = turn(stop = true)
            Log.i(TAG, "stop: status=${first?.status} ms=$stopMs error=${first?.error}")
            val (second, nextMs) = turn(stop = false)
            val report = "stop: status=${first?.status} ms=$stopMs error=${first?.error}\n" +
                "next: status=${second?.status} ms=$nextMs error=${second?.error}\n"
            report.lines().forEach { Log.i(TAG, it) }
            File(instrumentation.targetContext.getExternalFilesDir(null), "claude-engine-stop.txt").writeText(report)
            assertTrue("the stopped turn did not end", first != null)
            assertTrue(first!!.status in setOf("interrupted", "failed"))
            assertTrue("stop took ${stopMs} ms", stopMs < 5_000)
            assertEquals("failed", second?.status)
        } finally {
            engine.close()
            workspace.deleteRecursively()
        }
    }

    private fun JsonObject.str(name: String): String = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** The app's host, with a copy of each chat process's stdout for this test. */
    private class TeeHost(private val inner: ClaudeProcessHost, private val sink: ByteArrayOutputStream) : ClaudeProcessHost {
        override val status: StateFlow<RuntimeStatus> get() = inner.status
        override val homeDirectory: File get() = inner.homeDirectory
        override suspend fun prepare() = inner.prepare()
        override suspend fun stopAll() = Unit
        override suspend fun start(args: List<String>, workingDirectory: File, extraEnv: Map<String, String>): Process {
            val process = inner.start(args, workingDirectory, extraEnv)
            // Only chat processes (stream-json) are copied; `auth` output never is.
            return if ("--input-format" in args) TeeProcess(process, sink) else process
        }
    }

    private class TeeProcess(private val inner: Process, private val sink: ByteArrayOutputStream) : Process() {
        private val input = object : FilterInputStream(inner.inputStream) {
            override fun read(): Int = super.read().also { if (it >= 0) synchronized(sink) { sink.write(it) } }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) synchronized(sink) { sink.write(b, off, it) } }
        }
        override fun getOutputStream(): OutputStream = inner.outputStream
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = inner.errorStream
        override fun waitFor(): Int = inner.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = inner.waitFor(timeout, unit)
        override fun exitValue(): Int = inner.exitValue()
        override fun destroy() = inner.destroy()
        override fun destroyForcibly(): Process { inner.destroyForcibly(); return this }
        override fun isAlive(): Boolean = inner.isAlive
    }

    private companion object { const val TAG = "ClaudeEngineDeviceTest" }
}
