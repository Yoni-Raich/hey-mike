package dev.androidagent.enginecodex

import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The side run that starts an account's usage window, against a scripted app-server. */
class CodexWindowStarterTest {

    @Test fun aSavedAccountSendsOneShortEphemeralMessageAndReadsTheQuota() = runBlocking {
        val server = FakeServer()
        val limits = CodexWindowStarter(server.host, timeoutMs = 5_000).start()
        assertEquals(listOf("initialize", "initialized", "model/list", "thread/start", "turn/start", "account/rateLimits/read"), server.methods)
        val thread = server.params("thread/start")
        assertEquals(true, thread["ephemeral"]?.jsonPrimitive?.boolean)
        assertEquals("read-only", thread["sandbox"]?.jsonPrimitive?.content)
        assertEquals("never", thread["approvalPolicy"]?.jsonPrimitive?.content)
        assertEquals("gpt-default", thread["model"]?.jsonPrimitive?.content)
        assertNull("no tools in a window start", thread["dynamicTools"])
        val turn = server.params("turn/start")
        assertEquals("hi", turn["input"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("low", turn["effort"]?.jsonPrimitive?.content)
        assertEquals(300L, limits.first().windowMinutes)
        assertEquals(1_900_000_000L, limits.first().resetsAt)
        assertTrue("the process is stopped after the run", server.stopped)
    }

    @Test fun theLiveAccountSignsInWithTokensAndAnswersARefresh() = runBlocking {
        val server = FakeServer(askRefresh = true)
        var refreshedFor: String? = "unset"
        CodexWindowStarter(server.host, timeoutMs = 5_000).start(ExternalChatgptTokens("access-1", "acct-1")) { previous ->
            refreshedFor = previous
            ExternalChatgptTokens("access-2", "acct-1")
        }
        val login = server.params("account/login/start")
        assertEquals("chatgptAuthTokens", login["type"]?.jsonPrimitive?.content)
        assertEquals("access-1", login["accessToken"]?.jsonPrimitive?.content)
        assertEquals("acct-1", refreshedFor)
        assertEquals("access-2", server.refreshAnswer?.get("result")?.jsonObject?.get("accessToken")?.jsonPrimitive?.content)
    }

    @Test fun aFailedTurnIsAnErrorWithTheServersReason() = runBlocking {
        val server = FakeServer(turnStatus = "failed", turnError = "You've hit your usage limit.")
        val failure = runCatching { CodexWindowStarter(server.host, timeoutMs = 5_000).start() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("usage limit"))
        assertTrue(server.stopped)
    }

    @Test fun anyRequestToActIsRefused() = runBlocking {
        val server = FakeServer(askApproval = true)
        CodexWindowStarter(server.host, timeoutMs = 5_000).start()
        assertNotNull(server.approvalAnswer?.get("error"))
    }

    @Test fun theDefaultModelWinsOverTheFirstAndTheLowestEffortIsUsed() {
        val list = Json.parseToJsonElement(
            """{"data":[
              {"model":"small","supportedReasoningEfforts":[{"reasoningEffort":"minimal"}]},
              {"model":"big","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"low"},{"reasoningEffort":"minimal"}]}
            ]}""",
        ).jsonObject
        assertEquals("big" to "minimal", CodexWindowStarter.defaultModel(list))
        assertNull(CodexWindowStarter.defaultModel(buildJsonObject { put("data", JsonArray(emptyList())) }))
    }

    private class FakeServer(
        private val turnStatus: String = "completed",
        private val turnError: String? = null,
        private val askRefresh: Boolean = false,
        private val askApproval: Boolean = false,
    ) {
        private val serverIn = PipedInputStream()
        private val clientOut = PipedOutputStream(serverIn)
        private val clientIn = PipedInputStream()
        private val serverOut = PipedOutputStream(clientIn)
        private val writer = serverOut.bufferedWriter()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val methods: List<String> get() = requests.mapNotNull { it["method"]?.jsonPrimitive?.content }
        fun params(method: String) = requests.first { it["method"]?.jsonPrimitive?.content == method }["params"]!!.jsonObject
        @Volatile var stopped = false
        @Volatile var refreshAnswer: JsonObject? = null
        @Volatile var approvalAnswer: JsonObject? = null

        private val process = object : Process() {
            override fun isAlive() = !stopped
            override fun getOutputStream() = clientOut
            override fun getInputStream() = clientIn
            override fun getErrorStream() = ByteArray(0).inputStream()
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { stopped = true; runCatching { serverOut.close() } }
        }
        val host = object : RuntimeHost {
            override val status = MutableStateFlow(RuntimeStatus())
            override val homeDirectory = File("home")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer() = process
            override suspend fun stop() { process.destroy() }
        }

        init {
            scope.launch {
                val reader = serverIn.bufferedReader()
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val message = Json.parseToJsonElement(line).jsonObject
                    val method = message["method"]?.jsonPrimitive?.content
                    if (method == null) {
                        when (message["id"]?.jsonPrimitive?.content) {
                            "900" -> refreshAnswer = message
                            "901" -> approvalAnswer = message
                        }
                        continue
                    }
                    requests += message
                    val id = message["id"] ?: continue
                    when (method) {
                        "model/list" -> write("""{"id":$id,"result":{"data":[{"model":"gpt-default","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"high"}]}]}}""")
                        "thread/start" -> write("""{"id":$id,"result":{"thread":{"id":"t1"}}}""")
                        "turn/start" -> {
                            write("""{"id":$id,"result":{"turn":{"id":"u1","status":"inProgress"}}}""")
                            if (askRefresh) write("""{"id":900,"method":"account/chatgptAuthTokens/refresh","params":{"reason":"unauthorized","previousAccountId":"acct-1"}}""")
                            if (askApproval) write("""{"id":901,"method":"item/commandExecution/requestApproval","params":{"threadId":"t1","turnId":"u1"}}""")
                            // Give the answers a moment to arrive before the turn ends.
                            delay(100)
                            val error = turnError?.let { ""","error":{"message":"$it"}""" }.orEmpty()
                            write("""{"method":"turn/completed","params":{"threadId":"t1","turn":{"id":"u1","status":"$turnStatus"$error}}}""")
                        }
                        "account/rateLimits/read" -> write(
                            """{"id":$id,"result":{"rateLimits":{"primary":{"usedPercent":1,"windowDurationMins":300,"resetsAt":1900000000},""" +
                                """"secondary":{"usedPercent":12,"windowDurationMins":10080,"resetsAt":1900500000}}}}""",
                        )
                        else -> write("""{"id":$id,"result":{}}""")
                    }
                }
            }
        }

        private fun write(message: String) = synchronized(writer) { writer.write(message); writer.newLine(); writer.flush() }
    }
}
