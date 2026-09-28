package dev.androidagent.remote

import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.enginecodex.CodexEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutingAgentEngineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun resumingAnImportedThreadKeepsItsOriginAcrossReconnects() = runBlocking {
        val box = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val stateFile = File(temp.root, "computers.bin")
        val store = RemoteStore(stateFile, box)
        store.save(RemoteComputer("pc", "PC", "localhost", user = "test"), "test-only")
        val hub = RemoteHub(store) { _, profile -> CodexEngine(ReplyRuntime(), profile) }
        val router = RoutingAgentEngine(CodexEngine(ReplyRuntime()), hub)
        try {
            for (origin in listOf(true, null, false)) {
                val chat = "chat-$origin"
                val threadId = "thread-$origin"
                val binding = RemoteBinding("pc", "C:\\project", threadId, importedFromPc = origin)
                store.bind(chat, binding)
                val workspace = File(temp.root, "sessions/$chat/workspace").apply { mkdirs() }
                repeat(2) {
                    assertEquals(threadId, withTimeout(5_000) { router.openSession(workspace, threadId, null, emptyList()) })
                    assertEquals(binding, RemoteStore(stateFile, box).binding(chat))
                    hub.disconnect("pc")
                }
            }
            val workspace = File(temp.root, "sessions/new/workspace").apply { mkdirs() }
            store.bind("new", RemoteBinding("pc", "C:\\project"))
            assertEquals("new-thread", withTimeout(5_000) { router.openSession(workspace, null, null, emptyList()) })
            assertEquals(false, store.binding("new")?.importedFromPc)
            assertEquals("new-thread", store.binding("new")?.threadId)
        } finally { router.close() }
    }

    /** A protocol peer which resumes the requested thread; no SSH or account is used. */
    private class ReplyRuntime : RuntimeHost {
        override val status = MutableStateFlow(RuntimeStatus())
        override val homeDirectory = File(".")
        private val input = PipedInputStream()
        private val replies = PipedOutputStream(input)
        private val output = object : ByteArrayOutputStream() {
            override fun flush() {
                val lines = toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
                reset()
                lines.forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val id = request["id"] ?: return@forEach
                    val method = request["method"]?.jsonPrimitive?.content
                    val thread = (request["params"] as? JsonObject)?.get("threadId") ?: JsonPrimitive("new-thread")
                    val reply = buildJsonObject {
                        put("id", id)
                        put("result", if (method == "initialize") buildJsonObject {} else buildJsonObject {
                            put("thread", buildJsonObject { put("id", thread) })
                        })
                    }
                    replies.write((reply.toString() + "\n").toByteArray())
                    replies.flush()
                }
            }
        }
        override suspend fun prepare() = Unit
        override suspend fun startAppServer(): Process = object : Process() {
            override fun getInputStream() = input
            override fun getOutputStream() = output
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun isAlive() = true
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
        }
        override suspend fun stop() { replies.close(); input.close() }
    }
}
