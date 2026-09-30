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

package dev.androidagent.engineclaude

import dev.androidagent.core.ClaudeProcessHost
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** An in-memory pipe that, unlike `PipedInputStream`, does not care which thread wrote last. */
internal class FakePipe {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val eof = ByteArray(0)
    @Volatile private var closed = false

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Broken pipe")
            if (len > 0) queue.put(b.copyOfRange(off, off + len))
        }
        override fun close() {
            if (!closed) { closed = true; queue.put(eof) }
        }
    }

    val input: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var position = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var chunk = current
            if (chunk == null || position >= chunk.size) {
                val next = queue.take()
                if (next === eof) { queue.put(eof); return -1 }
                chunk = next
                current = next
                position = 0
            }
            val count = minOf(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, count)
            position += count
            return count
        }
        override fun close() = output.close()
    }
}

/**
 * A scripted `claude` process. What the engine writes to stdin arrives in
 * [lines] and [onLine]; [send] writes stdout. Closing stdin ends it with 0,
 * as the real CLI does.
 */
internal class FakeClaude(val args: List<String>, val cwd: File, val env: Map<String, String>) : Process() {
    private val stdin = FakePipe()
    private val stdout = FakePipe()
    private val stderr = FakePipe()
    private val exited = CountDownLatch(1)
    val lines = LinkedBlockingQueue<String>()
    @Volatile var onLine: (FakeClaude, String) -> Unit = { _, _ -> }
    @Volatile var exitCode: Int? = null
    @Volatile var forced = false

    init {
        thread(isDaemon = true, name = "fake-claude-stdin") {
            try {
                stdin.input.bufferedReader().forEachLine { line ->
                    lines.put(line)
                    onLine(this, line)
                }
            } catch (_: IOException) {
            }
            finish(0)
        }
    }

    fun after(flag: String): String = args[args.indexOf(flag) + 1]

    fun send(line: String) = sendRaw("$line\n")
    fun send(message: JsonObject) = send(message.toString())
    fun sendRaw(text: String) {
        stdout.output.write(text.toByteArray())
    }
    fun err(line: String) {
        stderr.output.write("$line\n".toByteArray())
    }

    fun finish(code: Int) {
        synchronized(this) {
            if (exitCode != null) return
            exitCode = code
        }
        stdout.output.close()
        stderr.output.close()
        exited.countDown()
    }

    /** The next line the engine wrote that is a JSON frame matching [predicate]. */
    fun nextFrame(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean = { true }): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw AssertionError("No matching frame from the engine")
            val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
            val frame = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (predicate(frame)) return frame
        }
    }

    /** Answer `initialize` with a recorded model list, as the real CLI does. */
    fun answerInitialize() {
        onLine = { process, line ->
            val frame = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            if (frame != null && frame.type() == "control_request" && frame.requestSubtype() == "initialize") {
                process.send(initializeResponse(frame["request_id"]!!.jsonPrimitive.content))
            }
        }
    }

    override fun getOutputStream(): OutputStream = stdin.output
    override fun getInputStream(): InputStream = stdout.input
    override fun getErrorStream(): InputStream = stderr.input
    override fun waitFor(): Int { exited.await(); return exitCode!! }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
    override fun exitValue(): Int = exitCode ?: throw IllegalThreadStateException("running")
    override fun isAlive(): Boolean = exitCode == null
    override fun destroy() = finish(143)
    override fun destroyForcibly(): Process { forced = true; finish(137); return this }

    companion object {
        private val initialize: JsonObject by lazy {
            val line = FakeClaude::class.java.getResource("/claude/initialize-response.jsonl")!!.readText().trim()
            Json.parseToJsonElement(line).jsonObject
        }

        fun initializeResponse(requestId: String): JsonObject {
            val response = initialize["response"]!!.jsonObject
            return JsonObject(initialize + ("response" to JsonObject(response + ("request_id" to JsonPrimitive(requestId)))))
        }
    }
}

internal fun JsonObject.type(): String = (this["type"] as? JsonPrimitive)?.content.orEmpty()
internal fun JsonObject.requestSubtype(): String =
    ((this["request"] as? JsonObject)?.get("subtype") as? JsonPrimitive)?.content.orEmpty()

internal class FakeHost(override val homeDirectory: File) : ClaudeProcessHost {
    override val status = MutableStateFlow(RuntimeStatus(RuntimePhase.READY, "Ready"))
    val started = CopyOnWriteArrayList<FakeClaude>()
    @Volatile var script: (FakeClaude) -> Unit = { it.answerInitialize() }
    @Volatile var prepareCalls = 0
    @Volatile var stopAllCalls = 0

    override suspend fun prepare() { prepareCalls++ }

    override suspend fun start(args: List<String>, workingDirectory: File, extraEnv: Map<String, String>): Process {
        val process = FakeClaude(args, workingDirectory, extraEnv)
        script(process)
        started += process
        return process
    }

    override suspend fun stopAll() { stopAllCalls++ }

    fun chats(): List<FakeClaude> = started.filter { "--system-prompt-file" in it.args }
}

internal class FakeToolServer(
    val name: String,
    val tools: () -> List<ToolDefinition>,
    val call: suspend (String, JsonObject) -> ToolResult,
) : McpToolServer {
    @Volatile var started = false
    @Volatile var stopped = false
    override fun start(): String {
        started = true
        return """{"mcpServers":{"$name":{"type":"http","url":"http://127.0.0.1:40000/mcp","headers":{"Authorization":"Bearer test-token"}}}}"""
    }
    override fun stop() { stopped = true }
}

internal class FakeToolServers : McpToolServerFactory {
    val servers = CopyOnWriteArrayList<FakeToolServer>()
    override fun create(
        serverName: String,
        tools: () -> List<ToolDefinition>,
        call: suspend (name: String, arguments: JsonObject) -> ToolResult,
    ): McpToolServer = FakeToolServer(serverName, tools, call).also { servers += it }
}
