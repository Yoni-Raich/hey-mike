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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexApprovalTest {
    @Test fun permissionAnswersUseTheRequestedProfileAndTurnScope() = runBlocking {
        val server = Server()
        try {
            server.engine.connect()
            val requested = Json.parseToJsonElement(
                """{"network":{"enabled":true},"fileSystem":{"write":["C:\\shared"]}}""",
            ).jsonObject
            for (allow in listOf(true, false)) {
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    server.engine.events.filterIsInstance<EngineEvent.Approval>().first()
                }
                server.send(buildJsonObject {
                    put("id", "permission-$allow")
                    put("method", "item/permissions/requestApproval")
                    put("params", buildJsonObject {
                        put("threadId", "thread"); put("turnId", "turn")
                        put("permissions", requested)
                    })
                })
                val approval = withTimeout(5_000) { request.await() }
                server.engine.answerApproval(approval.requestId, allow)
                val reply = withTimeout(5_000) { server.responses.receive() }
                assertEquals(JsonPrimitive("permission-$allow"), reply["id"])
                val result = reply.getValue("result").jsonObject
                assertEquals(if (allow) requested else buildJsonObject {}, result["permissions"])
                assertEquals(JsonPrimitive("turn"), result["scope"])
                assertFalse("Permission replies must not use command decisions", "decision" in result)
                server.engine.answerApproval(approval.requestId, !allow)
                assertTrue("An approval is consumed once", server.responses.tryReceive().isFailure)
            }
        } finally { server.close() }
    }

    @Test fun commandAndFileApprovalsStillUseAcceptAndDecline() = runBlocking {
        val server = Server()
        try {
            server.engine.connect()
            for (method in listOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval")) {
                for (allow in listOf(true, false)) {
                    val request = async(start = CoroutineStart.UNDISPATCHED) {
                        server.engine.events.filterIsInstance<EngineEvent.Approval>().first()
                    }
                    server.send(buildJsonObject {
                        put("id", 7); put("method", method)
                        put("params", buildJsonObject { put("threadId", "thread"); put("turnId", "turn") })
                    })
                    val approval = withTimeout(5_000) { request.await() }
                    server.engine.answerApproval(approval.requestId, allow)
                    val reply = withTimeout(5_000) { server.responses.receive() }
                    assertEquals(JsonPrimitive(7), reply["id"])
                    assertEquals(buildJsonObject { put("decision", if (allow) "accept" else "decline") }, reply["result"])
                }
            }
        } finally { server.close() }
    }

    private class Server {
        private val input = PipedInputStream()
        private val clientOutput = PipedOutputStream(input)
        private val clientInput = PipedInputStream()
        private val output = PipedOutputStream(clientInput)
        private val writer = output.bufferedWriter()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val responses = Channel<JsonObject>(Channel.UNLIMITED)
        val engine = CodexEngine(object : RuntimeHost {
            override val status = MutableStateFlow(RuntimeStatus())
            override val homeDirectory = File(".")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer(): Process = object : Process() {
                override fun getInputStream() = clientInput
                override fun getOutputStream() = clientOutput
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun isAlive() = true
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() = Unit
            }
            override suspend fun stop() {
                listOf(input, output, clientInput, clientOutput).forEach { runCatching { it.close() } }
            }
        })

        init {
            scope.launch {
                try {
                    input.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val message = Json.parseToJsonElement(line).jsonObject
                            if (message["method"] == JsonPrimitive("initialize")) {
                                send(buildJsonObject { put("id", message.getValue("id")); put("result", buildJsonObject {}) })
                            } else if ("result" in message) {
                                responses.trySend(message)
                            }
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
        }

        @Synchronized fun send(message: JsonObject) {
            writer.write(message.toString()); writer.newLine(); writer.flush()
        }

        suspend fun close() {
            engine.close()
            scope.cancel()
        }
    }
}
