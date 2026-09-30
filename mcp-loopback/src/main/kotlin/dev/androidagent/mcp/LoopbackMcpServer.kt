package dev.androidagent.mcp

import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A minimal MCP server (Streamable HTTP, plain JSON replies) on `127.0.0.1`, so a local agent
 * process can list and call the app's own tools.
 *
 * Every [start] binds a fresh port and mints a fresh bearer token; requests without it get 401.
 * [tools] is read at each `tools/list`, and [call] runs each `tools/call` in its own coroutine,
 * cancelled by `notifications/cancelled` or [stop].
 */
class LoopbackMcpServer internal constructor(
    private val serverName: String,
    private val tools: () -> List<ToolDefinition>,
    private val call: suspend (name: String, arguments: JsonObject) -> ToolResult,
    private val bindAddress: InetAddress,
) {
    constructor(
        serverName: String,
        tools: () -> List<ToolDefinition>,
        call: suspend (name: String, arguments: JsonObject) -> ToolResult,
    ) : this(serverName, tools, call, InetAddress.getByAddress(LOOPBACK))

    private val lock = Any()
    private var running: Running? = null

    /** Binds `127.0.0.1` on a free port and starts serving. Throws if already started. */
    fun start(): McpEndpoint = synchronized(lock) {
        check(running == null) { "MCP server is already running" }
        require(bindAddress is Inet4Address && bindAddress.address.contentEquals(LOOPBACK)) {
            "MCP server binds 127.0.0.1 only"
        }
        val token = newToken()
        val socket = ServerSocket(0, BACKLOG, bindAddress)
        running = Running(socket, token.toByteArray(Charsets.US_ASCII)).also { it.open() }
        McpEndpoint("http://127.0.0.1:${socket.localPort}/mcp", token)
    }

    /** Closes the socket and every connection, and cancels calls still running. Safe to call twice. */
    fun stop() {
        val stopping = synchronized(lock) { running.also { running = null } } ?: return
        stopping.close()
    }

    private class Reply(val status: Int, val body: String)

    /** State of one [start]; a restart never shares a token, socket, or call with the last one. */
    private inner class Running(private val socket: ServerSocket, private val token: ByteArray) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        private val inFlight = ConcurrentHashMap<JsonElement, Job>()

        fun open() {
            scope.launch { acceptLoop() }
        }

        fun close() {
            scope.cancel()
            runCatching { socket.close() }
            clients.forEach { runCatching { it.close() } }
        }

        private suspend fun acceptLoop() {
            while (currentCoroutineContext().isActive) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    return
                }
                clients += client
                if (!scope.isActive) {
                    runCatching { client.close() }
                    return
                }
                scope.launch {
                    try {
                        client.use { serve(it) }
                    } catch (_: IOException) {
                        // The peer went away or stop() closed the socket.
                    } finally {
                        clients -= client
                    }
                }
            }
        }

        private suspend fun serve(client: Socket) {
            client.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val body = try {
                readBody(input)
            } catch (rejection: HttpRejection) {
                val reason = if (rejection.status == 401) "unauthorized" else "rejected"
                writeResponse(output, rejection.status, """{"error":"$reason"}""", rejection.extraHeaders)
                finishUnread(client, input)
                return
            }
            val reply = dispatch(body)
            writeResponse(output, reply.status, reply.body)
            client.shutdownOutput()
        }

        /** Checks path, method, token and length, in that order, and reads the body only if all pass. */
        private fun readBody(input: BufferedInputStream): String {
            val head = readHead(input)
            if (head.path != "/mcp") throw HttpRejection(404)
            if (head.method != "POST") throw HttpRejection(405, listOf("Allow: POST"))
            if (!authorized(head.headers["authorization"])) throw HttpRejection(401, listOf("WWW-Authenticate: Bearer"))
            if ("transfer-encoding" in head.headers) throw HttpRejection(411)
            val declared = head.headers["content-length"] ?: throw HttpRejection(411)
            if (declared.isEmpty() || !declared.all(Char::isDigit)) throw HttpRejection(400)
            val length = declared.toLongOrNull()?.takeIf { it <= MAX_BODY_BYTES } ?: throw HttpRejection(413)
            val bytes = ByteArray(length.toInt())
            DataInputStream(input).readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun authorized(header: String?): Boolean {
            if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) return false
            val presented = header.substring("Bearer ".length).trim().toByteArray(Charsets.US_ASCII)
            return MessageDigest.isEqual(presented, token)
        }

        private suspend fun dispatch(text: String): Reply {
            val message = try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                return Reply(400, error(JsonNull, PARSE_ERROR, "Parse error"))
            }
            if (message !is JsonObject) return Reply(400, error(JsonNull, INVALID_REQUEST, "Expected one JSON-RPC message"))
            val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = message["id"]
            if (method == null) {
                // A reply to a server request. This server sends none, so accept and drop it.
                if ("result" in message || "error" in message) return ACCEPTED
                return Reply(400, error(id ?: JsonNull, INVALID_REQUEST, "Missing method"))
            }
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            if (id == null) {
                notification(method, params)
                return ACCEPTED
            }
            if (id !is JsonPrimitive || id is JsonNull) return Reply(400, error(JsonNull, INVALID_REQUEST, "Invalid id"))
            return when (method) {
                "initialize" -> Reply(200, success(id, initializeResult(params)))
                "ping" -> Reply(200, success(id, JsonObject(emptyMap())))
                "tools/list" -> listTools(id)
                "tools/call" -> callTool(id, params)
                else -> Reply(200, error(id, METHOD_NOT_FOUND, "Method not found: $method"))
            }
        }

        private fun notification(method: String, params: JsonObject) {
            if (method != "notifications/cancelled") return
            val requestId = params["requestId"] as? JsonPrimitive ?: return
            inFlight[requestId]?.cancel()
        }

        private fun initializeResult(params: JsonObject): JsonObject {
            val asked = (params["protocolVersion"] as? JsonPrimitive)?.content
            val version = asked?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first()
            return buildJsonObject {
                put("protocolVersion", version)
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") {
                    put("name", serverName)
                    put("version", SERVER_VERSION)
                }
            }
        }

        private fun listTools(id: JsonElement): Reply {
            val definitions = try {
                tools()
            } catch (_: Exception) {
                return Reply(200, error(id, INTERNAL_ERROR, "Could not list tools"))
            }
            val result = buildJsonObject {
                put("tools", JsonArray(definitions.map { tool ->
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    }
                }))
            }
            return Reply(200, success(id, result))
        }

        private suspend fun callTool(id: JsonElement, params: JsonObject): Reply {
            val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return Reply(200, error(id, INVALID_PARAMS, "tools/call needs a tool name"))
            val arguments = when (val raw = params["arguments"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> return Reply(200, error(id, INVALID_PARAMS, "Tool arguments must be an object"))
            }
            val known = runCatching { tools().any { it.name == name } }.getOrDefault(false)
            if (!known) return Reply(200, success(id, toolContent(ToolResult("Unknown tool: $name", success = false))))
            return coroutineScope {
                val job = async { runTool(name, arguments) }
                inFlight[id] = job
                try {
                    Reply(200, success(id, toolContent(job.await())))
                } catch (e: CancellationException) {
                    // stop() cancels this coroutine too; then the connection just closes.
                    currentCoroutineContext().ensureActive()
                    Reply(200, error(id, REQUEST_CANCELLED, "Request cancelled"))
                } finally {
                    inFlight.remove(id, job)
                }
            }
        }

        private suspend fun runTool(name: String, arguments: JsonObject): ToolResult = try {
            call(name, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(300)
            ToolResult("Tool $name failed: $detail", success = false)
        }
    }

    private companion object {
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)
        val PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val SERVER_VERSION = "1.0.0"
        const val BACKLOG = 16
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val REQUEST_CANCELLED = -32800
        val ACCEPTED = Reply(202, "")
        private val random = SecureRandom()

        fun newToken(): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun success(id: JsonElement, result: JsonElement): String = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()

        fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()

        fun toolContent(result: ToolResult): JsonObject = buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", result.text)
                })
                result.imageBase64?.let { image ->
                    add(buildJsonObject {
                        put("type", "image")
                        put("data", image)
                        put("mimeType", "image/png")
                    })
                }
            })
            put("isError", !result.success)
        }
    }
}
