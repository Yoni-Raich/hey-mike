package dev.androidagent.enginecodex

import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the app-server wire, including a saved thread after process restart. */
class CodexCompactionTest {
    @Test fun resumedChatWaitsForItsCompactionTurnToFinish() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("saved", wire.engine.openSession(File("workspace"), "saved", null, emptyList()))
            val compact = async { wire.engine.compact("saved") }
            wire.started.receive()
            wire.send("turn/started", """{"threadId":"saved","turn":{"id":"compact-1","status":"inProgress"}}""")
            wire.send("item/completed", """{"threadId":"saved","turnId":"compact-1","item":{"type":"contextCompaction","id":"summary"}}""")
            wire.barrier()
            assertFalse("RPC acceptance and the item alone are not turn completion", compact.isCompleted)
            wire.end("other-thread", "compact-1")
            wire.end("saved", "old-turn")
            wire.barrier()
            assertFalse("Another thread or old turn must not end the animation", compact.isCompleted)
            wire.end("saved", "compact-1")
            assertNull(withTimeout(5_000) { compact.await() })
        } finally { wire.close() }
    }

    @Test fun failedAndInterruptedTurnsAreNotReportedAsCompacted() = runBlocking {
        for (status in listOf("failed", "interrupted")) {
            val wire = Wire()
            try {
                val compact = async { runCatching { wire.engine.compact("saved") } }
                wire.started.receive()
                wire.send("turn/started", """{"threadId":"saved","turn":{"id":"compact-1"}}""")
                wire.end("saved", "compact-1", status)
                val failure = withTimeout(5_000) { compact.await() }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains(status))
            } finally { wire.close() }
        }
    }

    @Test fun missingCompletionIsAnErrorAndCanNeverProduceASuccessNote() = runBlocking {
        val wire = Wire(waitMs = 100)
        try {
            val compact = async { runCatching { wire.engine.compact("saved") } }
            wire.started.receive()
            val failure = withTimeout(5_000) { compact.await() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("completion was not confirmed"))
        } finally { wire.close() }
    }

    @Test fun rpcRejectionReportsTheRealSessionError() = runBlocking {
        val wire = Wire(reject = true)
        try {
            val failure = runCatching { wire.engine.compact("missing") }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("thread not found"))
        } finally { wire.close() }
    }

    @Test fun processClosingEndsTheWaitWithAnError() = runBlocking {
        val wire = Wire()
        try {
            val compact = async { runCatching { wire.engine.compact("saved") } }
            wire.started.receive()
            wire.engine.close()
            val failure = withTimeout(5_000) { compact.await() }.exceptionOrNull()
            assertNotNull(failure)
        } finally { wire.close() }
    }

    @Test fun aSecondRequestCannotReplaceTheFirstWaiter() = runBlocking {
        val wire = Wire()
        try {
            val first = async { wire.engine.compact("saved") }
            wire.started.receive()
            val failure = runCatching { wire.engine.compact("saved") }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("already being compacted"))
            wire.send("turn/started", """{"threadId":"saved","turn":{"id":"compact-1"}}""")
            wire.end("saved", "compact-1")
            assertNull(withTimeout(5_000) { first.await() })
        } finally { wire.close() }
    }

    private class Wire(waitMs: Long = 5_000, reject: Boolean = false) {
        private val serverIn = PipedInputStream()
        private val clientOut = PipedOutputStream(serverIn)
        private val clientIn = PipedInputStream()
        private val serverOut = PipedOutputStream(clientIn)
        private val writer = serverOut.bufferedWriter()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = Channel<Unit>(Channel.UNLIMITED)
        private val process = object : Process() {
            @Volatile private var alive = true
            override fun isAlive() = alive
            override fun getOutputStream() = clientOut
            override fun getInputStream() = clientIn
            override fun getErrorStream() = ByteArray(0).inputStream()
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { alive = false; runCatching { serverOut.close() } }
        }
        private val host = object : RuntimeHost {
            override val status = MutableStateFlow(RuntimeStatus())
            override val homeDirectory = File("home")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer() = process
            override suspend fun stop() { process.destroy() }
        }
        val engine = CodexEngine(host, diagnosticSink = {}, compactWaitMs = waitMs)
        init {
            scope.launch {
                val reader = serverIn.bufferedReader()
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val request = Json.parseToJsonElement(line).jsonObject
                    val id = request["id"] ?: continue
                    val method = request["method"]?.jsonPrimitive?.content
                    val response = when (method) {
                        "thread/resume" -> """{"id":$id,"result":{"thread":{"id":"saved"}}}"""
                        "model/list" -> """{"id":$id,"result":{"data":[]}}"""
                        "thread/compact/start" -> if (reject) """{"id":$id,"error":{"code":-32600,"message":"thread not found"}}""" else """{"id":$id,"result":{}}"""
                        else -> """{"id":$id,"result":{}}"""
                    }
                    write(response)
                    if (method == "thread/compact/start") started.send(Unit)
                }
            }
        }
        private fun write(message: String) = synchronized(writer) { writer.write(message); writer.newLine(); writer.flush() }
        fun send(method: String, params: String) = write("""{"method":"$method","params":$params}""")
        fun end(thread: String, turn: String, status: String = "completed") =
            send("turn/completed", """{"threadId":"$thread","turn":{"id":"$turn","status":"$status"}}""")
        suspend fun barrier() { engine.models() }
        suspend fun close() {
            engine.close()
            scope.cancel()
            listOf(serverIn, clientIn, serverOut, clientOut).forEach { runCatching { it.close() } }
        }
    }
}
