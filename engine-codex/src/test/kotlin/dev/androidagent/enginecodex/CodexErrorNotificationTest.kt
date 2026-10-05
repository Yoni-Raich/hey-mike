/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package dev.androidagent.enginecodex

import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexErrorNotificationTest {
    @Test fun retryingTurnStillDeliversItsReplyAndCompletion() = runBlocking {
        withServer { server, _, events ->
            server.send("error", """{"threadId":"thread-one","turnId":"turn-one","willRetry":true,"error":{"message":"Reconnecting... 2/5"}}""")
            server.send("item/agentMessage/delta", """{"threadId":"thread-one","turnId":"turn-one","delta":"Recovered"}""")
            server.send("turn/completed", """{"threadId":"thread-one","turn":{"id":"turn-one","status":"completed"}}""")
            val status = events.next() as EngineEvent.Activity
            assertEquals("Reconnecting... 2/5", status.text)
            assertEquals("thread-one", status.threadId)
            assertEquals("turn-one", status.turnId)
            assertEquals("Recovered", (events.next() as EngineEvent.TextDelta).text)
            assertEquals("completed", (events.next() as EngineEvent.TurnFinished).status)
        }
    }

    @Test fun terminalAndLegacyErrorsKeepTheirThreadAndTurnAndRedactTheirDetails() = runBlocking {
        withServer { server, _, events ->
            for (retryField in listOf("\"willRetry\":false,", "")) {
                server.send("error", """{"threadId":"thread-two","turnId":"turn-two",$retryField"error":{"message":"Request failed","additionalDetails":"HTTP 503; Bearer fake-token-12345678","codexErrorInfo":{"httpConnectionFailed":{"httpStatusCode":503}}}}""")
                val failure = events.next() as EngineEvent.Failure
                assertEquals("thread-two", failure.threadId)
                assertEquals("turn-two", failure.turnId)
                assertTrue(failure.message.contains("HTTP 503"))
                assertTrue(failure.message.contains("httpConnectionFailed"))
                assertFalse(failure.message.contains("fake-token-12345678"))
            }
        }
    }

    @Test fun unrelatedStderrIsLoggedSeparatelyFromRpcAndTurnFailures() = runBlocking {
        val diagnostic = CompletableDeferred<String>()
        withServer(stderr = "old account refresh failed; Bearer fake-token-12345678\n", diagnosticSink = { diagnostic.complete(it) }) { server, engine, events ->
            val logged = withTimeout(5_000) { diagnostic.await() }
            assertTrue(logged.contains("old account refresh failed"))
            assertFalse(logged.contains("fake-token-12345678"))
            try {
                engine.models()
                fail("The model/list RPC must fail")
            } catch (error: IllegalStateException) {
                assertTrue(error.message!!.contains("Current model RPC failed"))
                assertFalse(error.message!!.contains("old account"))
            }
            server.send("error", """{"threadId":"t","turnId":"u","willRetry":false,"error":{"message":"Current turn failed"}}""")
            val failure = events.next() as EngineEvent.Failure
            assertTrue(failure.message.contains("Current turn failed"))
            assertFalse(failure.message.contains("old account"))
            server.send("turn/completed", """{"threadId":"t","turn":{"id":"u","status":"failed","error":{"message":"Current completion failed"}}}""")
            val completed = events.next() as EngineEvent.TurnFinished
            assertTrue(completed.error!!.contains("Current completion failed"))
            assertFalse(completed.error!!.contains("old account"))
        }
    }

    @Test fun aBrokenJsonConnectionRemainsGlobalAndDoesNotAppendUnrelatedStderr() = runBlocking {
        val diagnostic = CompletableDeferred<String>()
        withServer(stderr = "old patch verification failed\n", diagnosticSink = { diagnostic.complete(it) }) { server, _, events ->
            withTimeout(5_000) { diagnostic.await() }
            server.writeLine("{\"incomplete\":")
            val failure = events.next() as EngineEvent.Failure
            assertNull(failure.threadId)
            assertNull(failure.turnId)
            assertTrue(failure.message.contains("Codex connection ended"))
            assertFalse(failure.message.contains("old patch"))
        }
    }

    private suspend fun Channel<EngineEvent>.next() = withTimeout(5_000) { receive() }

    private suspend fun withServer(
        stderr: String = "",
        diagnosticSink: (String) -> Unit = {},
        check: suspend (Server, CodexEngine, Channel<EngineEvent>) -> Unit,
    ) = coroutineScope {
        val server = Server(stderr)
        val engine = CodexEngine(server, diagnosticSink = diagnosticSink)
        val events = Channel<EngineEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events.send(it) } }
        try {
            engine.connect()
            check(server, engine, events)
        } finally {
            collector.cancelAndJoin()
            engine.close()
            server.stop()
        }
    }

    /** A real stdio reader/writer path, without starting a model or using account data. */
    private class Server(stderr: String) : RuntimeHost {
        override val status = MutableStateFlow(RuntimeStatus())
        override val homeDirectory = File("fixture-home")
        private val requests = PipedInputStream(64 * 1024)
        private val clientOutput = PipedOutputStream(requests)
        private val clientInput = PipedInputStream(64 * 1024)
        private val replies = PipedOutputStream(clientInput)
        private val diagnostics = ByteArrayInputStream(stderr.toByteArray(Charsets.UTF_8))
        private val writer = replies.bufferedWriter(Charsets.UTF_8)
        private val lock = Any()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var alive = true
        private val process = object : Process() {
            override fun getOutputStream() = clientOutput
            override fun getInputStream() = clientInput
            override fun getErrorStream() = diagnostics
            override fun isAlive() = alive
            override fun waitFor() = 0
            override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
            override fun destroy() { alive = false }
        }

        init {
            scope.launch {
                try {
                    requests.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val request = Json.parseToJsonElement(line).jsonObject
                            val id = request["id"] ?: return@forEach
                            val reply = if (request["method"]?.jsonPrimitive?.content == "model/list") {
                                buildJsonObject { put("id", id); put("error", buildJsonObject { put("code", -32000); put("message", "Current model RPC failed") }) }
                            } else buildJsonObject { put("id", id); put("result", buildJsonObject {}) }
                            writeLine(reply.toString())
                        }
                    }
                } catch (_: java.io.IOException) {
                    // The test closes the fixture's pipes after cancelling the engine.
                }
            }
        }

        fun send(method: String, params: String) = writeLine(buildJsonObject {
            put("method", method)
            put("params", Json.parseToJsonElement(params))
        }.toString())

        fun writeLine(line: String) = synchronized(lock) { writer.write(line); writer.newLine(); writer.flush() }
        override suspend fun prepare() = Unit
        override suspend fun startAppServer(): Process = process
        override suspend fun stop() {
            alive = false
            scope.cancel()
            listOf(requests, clientOutput, clientInput, replies, diagnostics).forEach { runCatching { it.close() } }
        }
    }
}
