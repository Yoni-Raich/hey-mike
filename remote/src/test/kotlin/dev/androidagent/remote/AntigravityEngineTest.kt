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
    private class Host(stderr: String = "") : Process() {
        private val clientIn = PipedInputStream()
        private val serverOut = PipedOutputStream(clientIn)
        private val serverIn = PipedInputStream()
        private val clientOut = PipedOutputStream(serverIn)
        private val lines = serverIn.bufferedReader()
        private val err = ByteArrayInputStream(stderr.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = clientOut
        override fun getInputStream() = clientIn
        override fun getErrorStream() = err
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { runCatching { clientOut.close() }; runCatching { serverOut.close() } }
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
            val result = runCatching { engine.openAt(File(System.getProperty("java.io.tmpdir") ?: ".").canonicalPath, null) }
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

    @Test fun interruptSendsCancelFinishesAsInterruptedAndResolvesPendingApproval() = runBlocking {
        withTimeout(10_000) {
            val host = Host()
            var bridgeClosed = false
            val engine = AntigravityEngine(phoneBridge = { _, _ ->
                object : AcpPhoneBridge {
                    override val serverConfig = buildJsonObject { put("name", "mike") }
                    override fun close() { bridgeClosed = true }
                }
            }) { host }
            try {
                val opening = async(Dispatchers.IO) {
                    engine.openAt("/project", null, listOf(dev.androidagent.core.ToolDefinition("device_status", "Status", JsonObject(emptyMap()))))
                }
                host.reply(host.receive(), """{"protocolVersion":1}""")
                host.reply(host.receive(), """{"sessionId":"cancel-id"}""")
                val chat = opening.await()
                val ask = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is EngineEvent.Approval } as EngineEvent.Approval }
                val turn = engine.startTurn(chat, "Run build", emptyList())
                val prompt = host.receive()
                assertEquals("session/prompt", prompt["method"]?.jsonPrimitive?.content)
                host.send("""{"id":77,"method":"session/request_permission","params":{"sessionId":"cancel-id","options":[{"kind":"allow_once","optionId":"once"}],"toolCall":{"title":"Run command","rawInput":{"CommandLine":"git status"}}}}""")
                val permission = ask.await()
                assertEquals("git status", permission.details["command"]?.jsonPrimitive?.content)
                val resolved = async(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.first { it is EngineEvent.ApprovalResolved } as EngineEvent.ApprovalResolved
                }
                val finished = async(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.first { it is EngineEvent.TurnFinished || it is EngineEvent.Failure }
                }
                val cancelJob = launch(Dispatchers.IO) { engine.interrupt(chat, turn) }
                val cancelMsg = host.receive()
                assertEquals("session/cancel", cancelMsg["method"]?.jsonPrimitive?.content)
                host.reply(prompt, """{"stopReason":"cancelled"}""")
                cancelJob.join()
                assertEquals(permission.requestId, resolved.await().requestId)
                val end = finished.await()
                assertTrue("Expected TurnFinished(interrupted) but got $end", end is EngineEvent.TurnFinished && end.status == "interrupted")
                assertTrue(bridgeClosed)
            } finally { engine.close() }
        }
    }

    @Test fun ignoresNonJsonStdoutAndSurfacesRedactedStderrOnDisconnect() = runBlocking {
        withTimeout(10_000) {
            val host = Host(stderr = "Fatal: auth failed with Bearer sk-1234567890abcdefghijklmnop\n")
            val engine = AntigravityEngine { host }
            try {
                val opening = async(Dispatchers.IO) { engine.openAt("/project", null, model = "agy/legacy-model") }
                host.send("")
                host.send("WARNING: non-json startup notice")
                host.reply(host.receive(), """{"protocolVersion":1}""")
                host.reply(host.receive(), """{"sessionId":"stderr-id","models":{"currentModelId":"default"}}""")
                val setModel = host.receive()
                assertEquals("session/set_model", setModel["method"]?.jsonPrimitive?.content)
                assertEquals("legacy-model", setModel["params"]!!.jsonObject.getValue("modelId").jsonPrimitive.content)
                host.reply(setModel, "{}")
                val chat = opening.await()
                val failure = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first { it is EngineEvent.Failure } as EngineEvent.Failure }
                engine.startTurn(chat, "Hello", emptyList())
                host.receive()
                delay(50)
                host.destroy()
                val event = failure.await()
                assertTrue(event.message.contains("Fatal: auth failed"))
                assertFalse(event.message.contains("sk-1234567890"))
            } finally { engine.close() }
        }
    }

    @Test fun toolCallsEmitItemActivityAndSegmentAssistantText() = runBlocking {
        withTimeout(10_000) {
            val host = Host()
            val engine = AntigravityEngine { host }
            try {
                val opening = async(Dispatchers.IO) { engine.openAt("/project", null) }
                host.reply(host.receive(), """{"protocolVersion":1}""")
                host.reply(host.receive(), """{"sessionId":"seg-id"}""")
                val chat = opening.await()
                val collected = mutableListOf<EngineEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.collect { collected += it }
                }
                engine.startTurn(chat, "Inspect", emptyList())
                val prompt = host.receive()
                host.send("""{"method":"session/update","params":{"sessionId":"seg-id","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Before"}}}}""")
                host.send("""{"method":"session/update","params":{"sessionId":"seg-id","update":{"sessionUpdate":"tool_call","toolCallId":"call-1","title":"Run git","status":"in_progress","rawInput":{"CommandLine":"git status"}}}}""")
                host.send("""{"method":"session/update","params":{"sessionId":"seg-id","update":{"sessionUpdate":"tool_call_update","toolCallId":"call-1","status":"completed"}}}""")
                host.send("""{"method":"session/update","params":{"sessionId":"seg-id","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"After"}}}}""")
                host.reply(prompt, """{"stopReason":"end_turn"}""")
                withTimeout(5_000) { while (collected.none { it is EngineEvent.TurnFinished }) delay(10) }
                collector.cancel()
                val deltas = collected.filterIsInstance<EngineEvent.TextDelta>()
                assertEquals(listOf("Before", "After"), deltas.map { it.text })
                assertNotNull(deltas[0].itemId)
                assertNotEquals(deltas[0].itemId, deltas[1].itemId)
                val items = collected.filterIsInstance<EngineEvent.ItemActivity>()
                assertEquals(2, items.size)
                assertEquals("Run git", items[0].title)
                assertEquals("streaming", items[0].state)
                assertEquals("Run git", items[1].title)
                assertEquals("complete", items[1].state)
            } finally { engine.close() }
        }
    }

    @Test fun compactToolsFitsLargeCatalogUnderBudgetWhileKeepingRequiredAndEnums() {
        val longDesc = "Detailed explanation of the phone tool behavior. " + "More guidance text. ".repeat(40)
        val catalog = (1..41).map { i ->
            dev.androidagent.core.ToolDefinition(
                name = if (i == 1) "read_ui" else "tool_$i",
                description = longDesc,
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("description", longDesc)
                    put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        put("operation", buildJsonObject {
                            put("type", "string")
                            put("description", "Verbose operation description ".repeat(10))
                            put("enum", buildJsonArray { add("list"); add("get") })
                        })
                        put("nested", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("deep", buildJsonObject { put("type", "string"); put("description", "Deep ".repeat(30)) })
                                put("extraA", buildJsonObject { put("type", "string") })
                                put("extraB", buildJsonObject { put("type", "integer") })
                                put("extraC", buildJsonObject { put("type", "boolean") })
                            })
                        })
                        put("rule", buildJsonObject {
                            put("properties", buildJsonObject {
                                put("id", buildJsonObject { put("type", "string") })
                                put("when", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("type", buildJsonObject { put("type", "string") })
                                        put("package", buildJsonObject { put("type", "string") })
                                    })
                                })
                                put("then", buildJsonObject {
                                    put("type", "array")
                                    put("items", buildJsonObject { put("type", "object") })
                                })
                            })
                        })
                    })
                    put("required", buildJsonArray { add("operation") })
                },
            )
        }
        val compacted = AntigravityPhoneBridge.compactTools(catalog)
        assertEquals(41, compacted.size)
        assertEquals("read_ui", compacted.first().name)
        val firstSchema = compacted.first().inputSchema
        assertNull(firstSchema["description"])
        assertEquals(listOf("operation"), firstSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val opEnum = firstSchema["properties"]!!.jsonObject["operation"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("list", "get"), opEnum)
        assertEquals("object", firstSchema["properties"]!!.jsonObject["nested"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(firstSchema["properties"]!!.jsonObject["nested"]!!.jsonObject["properties"])
        assertEquals("object", firstSchema["properties"]!!.jsonObject["rule"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(firstSchema["properties"]!!.jsonObject["rule"]!!.jsonObject["properties"])
        val totalJson = compacted.sumOf { it.name.length + it.description.length + it.inputSchema.toString().length + 48 }
        assertTrue("Expected compacted catalog under 12500 bytes, got $totalJson", totalJson <= 12_500)
    }

    @Test fun compactMovesEnumsToPromptHintWhenWideCatalogExceedsBudgetAndForwardsHint() = runBlocking {
        val wideCatalog = (1..41).map { i ->
            val name = when (i) {
                1 -> "browser"
                2 -> "system_controls"
                else -> "tool_$i"
            }
            dev.androidagent.core.ToolDefinition(
                name = name,
                description = "Phone capability tool number $i with detailed guidance for the model.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("operation", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                if (name == "browser") { add("open"); add("click"); add("snapshot") }
                                else if (name == "system_controls") { add("brightness"); add("volume"); add("torch") }
                                else { add("list"); add("get"); add("set"); add("clear") }
                            })
                        })
                        val extraCount = when (name) {
                            "browser" -> 20
                            "system_controls" -> 16
                            else -> if (i <= 12) 6 else 2
                        }
                        for (p in 1..extraCount) {
                            put("param_$p", buildJsonObject { put("type", "string") })
                        }
                    })
                    put("required", buildJsonArray { add("operation") })
                },
            )
        }
        val compacted = AntigravityPhoneBridge.compact(wideCatalog)
        assertEquals(41, compacted.tools.size)
        val browserSchema = compacted.tools.first().inputSchema
        assertEquals(listOf("operation"), browserSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", browserSchema["properties"]!!.jsonObject["operation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(browserSchema["properties"]!!.jsonObject["operation"]!!.jsonObject["enum"])
        assertTrue(compacted.promptHint.contains("browser.operation=open|click|snapshot"))
        assertTrue(compacted.promptHint.contains("system_controls.operation=brightness|volume|torch"))
        val totalJson = compacted.tools.sumOf { it.name.length + it.description.length + it.inputSchema.toString().length + 42 }
        assertTrue("Expected compacted wide catalog under 12200 bytes, got $totalJson", totalJson <= 12_200)

        withTimeout(10_000) {
            val host = Host()
            val engine = AntigravityEngine(phoneBridge = { _, _ ->
                object : AcpPhoneBridge {
                    override val serverConfig = buildJsonObject { put("name", "mike") }
                    override val promptHint = compacted.promptHint
                    override fun close() {}
                }
            }) { host }
            try {
                val opening = async(Dispatchers.IO) { engine.openAt("/project", null, wideCatalog) }
                host.reply(host.receive(), """{"protocolVersion":1}""")
                host.reply(host.receive(), """{"sessionId":"hint-1"}""")
                val thread = opening.await()
                engine.startTurn(thread, "Open browser", emptyList())
                val promptReq = host.receive()
                val text = promptReq["params"]!!.jsonObject["prompt"]!!.jsonArray
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n")
                assertTrue(text.contains("browser.operation=open|click|snapshot"))
                host.reply(promptReq, """{"stopReason":"end_turn"}""")
            } finally { engine.close() }
        }
    }

    @Test fun compactThrowsClearUnsupportedError() = runBlocking {
        val engine = AntigravityEngine { Host() }
        try {
            val failure = runCatching { engine.compact("agy:test") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("Compaction"))
        } finally { engine.close() }
    }
}
