package dev.androidagent.remote

import dev.androidagent.core.EngineEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class AntigravityEngineTest {
    /** ACP on two pipe pairs, with a separate reader for the client and for the agent. */
    private class Host : Process() {
        private val clientIn = PipedInputStream()
        private val serverOut = PipedOutputStream(clientIn)
        private val serverIn = PipedInputStream()
        private val clientOut = PipedOutputStream(serverIn)
        private val lines = serverIn.bufferedReader()
        override fun getOutputStream() = clientOut
        override fun getInputStream() = clientIn
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { clientOut.close(); serverOut.close() }
        fun receive() = Json.parseToJsonElement(lines.readLine()).jsonObject
        fun send(line: String) { serverOut.write((line + "\n").toByteArray()); serverOut.flush() }
        fun reply(call: JsonObject, result: String) = send("""{"jsonrpc":"2.0","id":${call["id"]},"result":$result}""")
    }

    @Test fun listsTheModelsByTheirNativeIdsWithoutCollidingWithOtherEngines() {
        val windowsRow = "gemini-4-neon-high\tGemini 4 Neon (High)"
        val listed = AntigravityModels.parse("""
            Fetching available models...
            $windowsRow
            claude-example          Claude Example
        """.trimIndent())
        assertEquals(listOf("agy/gemini-4-neon-high", "agy/claude-example"), listed.map { it.id })
        assertEquals("Gemini 4 Neon (High)", listed.first().displayName)
        assertEquals("claude-example", AntigravityModels.slug(listed.last().id))
        assertNull(AntigravityModels.slug("antigravity"))
    }

    @Test fun installedAgentOpensSessionOrReportsHostSignIn() = runBlocking {
        val executable = System.getenv("MIKE_TEST_AGY")
        org.junit.Assume.assumeTrue(!executable.isNullOrBlank())
        val engine = AntigravityEngine { ProcessBuilder(executable, "acp").start() }
        try {
            val result = runCatching { engine.openAt(File(System.getProperty("java.io.tmpdir")).canonicalPath, null) }
            result.fold(
                onSuccess = { assertTrue(it.startsWith(AntigravityEngine.PREFIX)) },
                onFailure = { assertEquals("Sign in with agy on this computer, then send again.", it.message) },
            )
        } finally { engine.close() }
    }

    @Test fun resumesNativeThreadAndAsksBeforeToolExecution() = runBlocking {
        withTimeout(10_000) {
            val host = Host()
            lateinit var call: PhoneCall
            var bridgeClosed = false
            val tool = dev.androidagent.core.ToolDefinition("device_status", "Test status", JsonObject(emptyMap()))
            val engine = AntigravityEngine(phoneBridge = { definitions, handler ->
                assertEquals(listOf(tool), definitions)
                call = handler
                object : AcpPhoneBridge {
                    override val serverConfig = buildJsonObject { put("name", "mike") }
                    override fun close() { bridgeClosed = true }
                }
            }) { host }
            try {
                val opening = async(Dispatchers.IO) { engine.openAt("/project", "agy:test-id", listOf(tool), model = "agy/example-fast") }
                val init = host.receive()
                assertEquals("initialize", init["method"]?.jsonPrimitive?.content)
                host.reply(init, """{"protocolVersion":1}""")
                val load = host.receive()
                assertEquals("session/load", load["method"]?.jsonPrimitive?.content)
                assertEquals("/project", load["params"]?.jsonObject?.get("cwd")?.jsonPrimitive?.content)
                assertEquals("mike", load["params"]!!.jsonObject["mcpServers"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
                host.reply(load, """{"configOptions":[{"id":"model","category":"model","currentValue":"example-default"}]}""")
                val select = host.receive()
                assertEquals("session/set_config_option", select["method"]?.jsonPrimitive?.content)
                assertEquals("example-fast", select["params"]!!.jsonObject.getValue("value").jsonPrimitive.content)
                host.reply(select, "{}")
                val chat = opening.await()
                assertFalse(call("device_status", JsonObject(emptyMap())).success)
                val ask = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is EngineEvent.Approval } as EngineEvent.Approval }
                val turn = engine.startTurn(chat, "Read the file", emptyList())
                val prompt = host.receive()
                assertEquals("session/prompt", prompt["method"]?.jsonPrimitive?.content)
                val event = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is EngineEvent.ToolCall } as EngineEvent.ToolCall }
                val phoneResult = async { call("device_status", JsonObject(emptyMap())) }
                val request = event.await()
                assertEquals(chat, request.threadId)
                assertEquals(turn, request.turnId)
                engine.answerTool(request.requestId, dev.androidagent.core.ToolResult("ready"))
                assertEquals("ready", phoneResult.await().text)
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is EngineEvent.ToolCall } as EngineEvent.ToolCall }
                val pending = launch { call("device_status", JsonObject(emptyMap())) }
                val cancelledCall = waiting.await()
                val resolved = async(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.first { it is EngineEvent.ApprovalResolved } as EngineEvent.ApprovalResolved
                }
                pending.cancelAndJoin()
                assertEquals(cancelledCall.requestId, resolved.await().requestId)
                engine.answerTool(cancelledCall.requestId, dev.androidagent.core.ToolResult("late"))
                host.send("""{"id":99,"method":"session/request_permission","params":{"sessionId":"test-id","options":[{"kind":"allow_once","optionId":"once"}],"toolCall":{"title":"Read file"}}}""")
                val permission = ask.await()
                assertEquals(turn, permission.turnId)
                engine.answerApproval(permission.requestId, false)
                assertEquals("cancelled", host.receive()["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
                val reply = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is EngineEvent.TextDelta } as EngineEvent.TextDelta }
                host.send("""{"method":"session/update","params":{"sessionId":"test-id","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello"}}}}""")
                assertEquals("Hello", reply.await().text)
                val end = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is EngineEvent.TurnFinished } as EngineEvent.TurnFinished }
                host.reply(prompt, """{"stopReason":"end_turn"}""")
                assertEquals("completed", end.await().status)
                assertTrue(bridgeClosed)
                assertFalse(call("device_status", JsonObject(emptyMap())).success)
            } finally { engine.close() }
        }
    }
}
