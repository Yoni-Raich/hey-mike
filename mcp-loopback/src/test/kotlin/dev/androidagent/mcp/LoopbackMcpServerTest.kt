package dev.androidagent.mcp

import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackMcpServerTest {
    private val tapSchema = buildJsonObject { put("type", "object") }
    private var tools = listOf(ToolDefinition("tap", "Tap the screen", tapSchema))
    private var onCall: suspend (String, JsonObject) -> ToolResult = { name, _ -> ToolResult("called $name") }
    private val server = LoopbackMcpServer("mike", { tools }, { name, arguments -> onCall(name, arguments) })

    @After fun tearDown() = server.stop()

    @Test fun startBindsLoopbackWithFreshTokenEachTime() {
        val first = server.start()
        assertTrue(first.url, first.url.matches(Regex("""http://127\.0\.0\.1:\d+/mcp""")))
        assertEquals(32, Base64.getUrlDecoder().decode(first.bearerToken).size)
        server.stop()
        val second = server.start()
        assertNotEquals(first.bearerToken, second.bearerToken)
    }

    @Test fun refusesAnyBindAddressButLoopbackV4() {
        val wide = LoopbackMcpServer("mike", { tools }, { _, _ -> ToolResult("") }, InetAddress.getByName("0.0.0.0"))
        assertThrows(IllegalArgumentException::class.java) { wide.start() }
    }

    @Test fun claudeConfigNamesServerUrlAndBearer() {
        val config = Json.parseToJsonElement(McpEndpoint("http://127.0.0.1:1234/mcp", "tok").claudeMcpConfig("mike"))
        val mike = config.jsonObject["mcpServers"]!!.jsonObject["mike"]!!.jsonObject
        assertEquals("http", mike["type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:1234/mcp", mike["url"]!!.jsonPrimitive.content)
        assertEquals("Bearer tok", mike["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
    }

    @Test fun missingOrWrongTokenIsRejected() {
        val endpoint = server.start()
        assertEquals(401, send(endpoint, rpc(1, "ping"), token = null)!!.status)
        assertEquals(401, send(endpoint, rpc(1, "ping"), token = endpoint.bearerToken.dropLast(1) + "x")!!.status)
        assertEquals(401, send(endpoint, rpc(1, "ping"), token = "short")!!.status)
        assertEquals(200, send(endpoint, rpc(1, "ping"))!!.status)
    }

    @Test fun onlyPostToMcpIsServed() {
        val endpoint = server.start()
        assertEquals(404, send(endpoint, rpc(1, "ping"), path = "/other")!!.status)
        assertEquals(405, send(endpoint, null, method = "GET")!!.status)
        assertEquals(411, send(endpoint, rpc(1, "ping"), contentLength = null)!!.status)
        assertEquals(413, send(endpoint, "", contentLength = 8 * 1024 * 1024 + 1)!!.status)
    }

    @Test fun initializeEchoesSupportedVersionElseNewest() {
        val endpoint = server.start()
        val old = send(endpoint, initialize("2024-11-05"))!!
        assertEquals("application/json", old.headers["content-type"])
        val result = old.json()["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
        assertEquals("mike", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val future = send(endpoint, initialize("2099-01-01"))!!.json()["result"]!!.jsonObject
        assertEquals("2025-06-18", future["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test fun notificationsGetEmptyAccepted() {
        val endpoint = server.start()
        val reply = send(endpoint, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")!!
        assertEquals(202, reply.status)
        assertEquals("", reply.body)
    }

    @Test fun pingAnswersEmptyResult() {
        val endpoint = server.start()
        val reply = send(endpoint, rpc("abc", "ping"))!!.json()
        assertEquals("abc", reply["id"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), reply["result"])
    }

    @Test fun toolsListReadsToolsAtCallTime() {
        val endpoint = server.start()
        val first = send(endpoint, rpc(1, "tools/list"))!!.json()["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, first.size)
        val tap = first[0].jsonObject
        assertEquals("tap", tap["name"]!!.jsonPrimitive.content)
        assertEquals("Tap the screen", tap["description"]!!.jsonPrimitive.content)
        assertEquals(tapSchema, tap["inputSchema"])
        tools = tools + ToolDefinition("swipe", "Swipe", tapSchema)
        val second = send(endpoint, rpc(2, "tools/list"))!!.json()["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("tap", "swipe"), second.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test fun toolsCallReturnsTextAndImage() {
        var seen: JsonObject? = null
        onCall = { _, arguments -> seen = arguments; ToolResult("Screenshot captured", imageBase64 = "iVBORw0K") }
        val endpoint = server.start()
        val result = send(endpoint, call(3, "tap", """{"x":10,"y":20}"""))!!.json()["result"]!!.jsonObject
        assertEquals(10, seen!!["x"]!!.jsonPrimitive.int)
        val content = result["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("text", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("Screenshot captured", content[0]["text"]!!.jsonPrimitive.content)
        assertEquals("image", content[1]["type"]!!.jsonPrimitive.content)
        assertEquals("iVBORw0K", content[1]["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[1]["mimeType"]!!.jsonPrimitive.content)
        assertEquals("false", result["isError"]!!.jsonPrimitive.content)
    }

    @Test fun failuresBecomeErrorResults() {
        onCall = { name, _ ->
            when (name) {
                "tap" -> ToolResult("no window", success = false)
                else -> throw IllegalStateException("device went away")
            }
        }
        tools = tools + ToolDefinition("boom", "Throws", tapSchema)
        val endpoint = server.start()

        val failed = send(endpoint, call(1, "tap"))!!.json()["result"]!!.jsonObject
        assertEquals("true", failed["isError"]!!.jsonPrimitive.content)
        assertEquals(1, failed["content"]!!.jsonArray.size)

        val unknown = send(endpoint, call(2, "nope"))!!.json()["result"]!!.jsonObject
        assertEquals("true", unknown["isError"]!!.jsonPrimitive.content)
        assertTrue(textOf(unknown).contains("nope"))

        val thrown = send(endpoint, call(3, "boom"))!!.json()["result"]!!.jsonObject
        assertEquals("true", thrown["isError"]!!.jsonPrimitive.content)
        assertTrue(textOf(thrown).contains("device went away"))
        assertFalse(textOf(thrown).contains("\tat "))
    }

    @Test fun protocolErrorsUseJsonRpcCodes() {
        val endpoint = server.start()
        val unknown = send(endpoint, rpc(1, "resources/list"))!!
        assertEquals(-32601, unknown.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val malformed = send(endpoint, """{"jsonrpc":"2.0","id":1,"method":""")!!
        assertEquals(400, malformed.status)
        assertEquals(-32700, malformed.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val noName = send(endpoint, rpc(2, "tools/call"))!!
        assertEquals(-32602, noName.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun callsRunConcurrently() = runBlocking<Unit> {
        val entered = AtomicInteger()
        val bothIn = CompletableDeferred<Unit>()
        onCall = { name, _ ->
            if (entered.incrementAndGet() == 2) bothIn.complete(Unit)
            withTimeout(5_000) { bothIn.await() }
            ToolResult("done $name")
        }
        tools = tools + ToolDefinition("swipe", "Swipe", tapSchema)
        val endpoint = server.start()
        val replies = listOf("tap", "swipe").mapIndexed { i, name ->
            async(Dispatchers.IO) { send(endpoint, call(i, name))!!.json()["result"]!!.jsonObject }
        }.awaitAll()
        assertEquals(listOf("done tap", "done swipe"), replies.map(::textOf))
        assertTrue(replies.all { it["isError"]!!.jsonPrimitive.content == "false" })
    }

    @Test fun cancelledNotificationCancelsTheCall() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        onCall = { _, _ -> blockUntilCancelled(entered, cancelled) }
        val endpoint = server.start()
        val pending = async(Dispatchers.IO) { send(endpoint, call(7, "tap")) }
        withTimeout(5_000) { entered.await() }
        val notice = send(endpoint, """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}""")!!
        assertEquals(202, notice.status)
        withTimeout(5_000) { cancelled.await() }
        val reply = withTimeout(5_000) { pending.await() }!!.json()
        assertEquals(7, reply["id"]!!.jsonPrimitive.int)
        assertEquals(-32800, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun stopCancelsRunningCallsAndClosesSocket() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        onCall = { _, _ -> blockUntilCancelled(entered, cancelled) }
        val endpoint = server.start()
        val pending = async(Dispatchers.IO) { send(endpoint, call(1, "tap")) }
        withTimeout(5_000) { entered.await() }
        server.stop()
        withTimeout(5_000) { cancelled.await() }
        assertNull(withTimeout(5_000) { pending.await() })
        assertThrows(ConnectException::class.java) { Socket("127.0.0.1", URI(endpoint.url).port).close() }
    }

    private suspend fun blockUntilCancelled(entered: CompletableDeferred<Unit>, cancelled: CompletableDeferred<Unit>): ToolResult {
        try {
            entered.complete(Unit)
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelled.complete(Unit)
            throw e
        }
    }

    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun rpc(id: Any, method: String): String {
        val idJson = if (id is String) "\"$id\"" else id.toString()
        return """{"jsonrpc":"2.0","id":$idJson,"method":"$method"}"""
    }

    private fun initialize(version: String) =
        """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""

    private fun call(id: Int, name: String, arguments: String = "{}") =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String) {
        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }

    /** One raw HTTP exchange; null when the server closed without answering. */
    private fun send(
        endpoint: McpEndpoint,
        body: String?,
        method: String = "POST",
        path: String = "/mcp",
        token: String? = endpoint.bearerToken,
        contentLength: Int? = body?.toByteArray()?.size,
    ): Reply? {
        val port = URI(endpoint.url).port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
                append("Accept: application/json, text/event-stream\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                if (contentLength != null) append("Content-Type: application/json\r\nContent-Length: $contentLength\r\n")
                append("\r\n")
            }
            val raw = try {
                socket.getOutputStream().run {
                    write(head.toByteArray())
                    if (body != null) write(body.toByteArray())
                    flush()
                }
                val out = ByteArrayOutputStream()
                socket.getInputStream().copyTo(out)
                out.toString(Charsets.UTF_8.name())
            } catch (_: java.io.IOException) {
                return null
            }
            if (raw.isEmpty()) return null
            val split = raw.indexOf("\r\n\r\n")
            val lines = raw.substring(0, split).split("\r\n")
            val headers = lines.drop(1).associate { line ->
                line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
            }
            return Reply(lines[0].split(' ')[1].toInt(), headers, raw.substring(split + 4))
        }
    }
}
