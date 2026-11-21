package dev.androidagent.remote

import dev.androidagent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One ACP process per open conversation. The computer owns the files and the native session. */
internal class AntigravityEngine(
    private val phoneBridge: (suspend (List<ToolDefinition>, PhoneCall) -> AcpPhoneBridge)? = null,
    private val launch: suspend (String) -> Process,
) : AgentEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events = stream.asSharedFlow()
    private val chats = ConcurrentHashMap<String, Peer>()
    private val approvals = ConcurrentHashMap<String, Permission>()
    private val peers = ConcurrentHashMap.newKeySet<Peer>()
    private val toolReplies = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()

    private inner class Permission(val peer: Peer, val id: JsonElement, val once: String?)

    private inner class Peer(val process: Process) {
        val waiting = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
        val serial = AtomicLong()
        private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        @Volatile var thread: String? = null
        @Volatile var turn: String? = null
        private var closed = false
        private var bridge: AcpPhoneBridge? = null

        fun attach(candidate: AcpPhoneBridge) {
            synchronized(this) {
                if (!closed) {
                    bridge = candidate
                    return
                }
            }
            candidate.close()
            error("Antigravity disconnected before opening its phone tools.")
        }

        suspend fun callPhone(name: String, arguments: JsonObject): ToolResult {
            val chat = thread ?: return ToolResult("No active chat.", success = false)
            val active = turn ?: return ToolResult("No active turn.", success = false)
            val request = UUID.randomUUID().toString()
            val reply = CompletableDeferred<ToolResult>()
            synchronized(this) {
                if (closed || turn != active) return ToolResult("Turn stopped.", success = false)
                toolReplies[request] = reply
            }
            try {
                currentCoroutineContext().ensureActive()
                stream.emit(EngineEvent.ToolCall(request, name, arguments, chat, active))
                return reply.await()
            } finally {
                toolReplies.remove(request, reply)
                if (!reply.isCompleted) withContext(NonCancellable) {
                    stream.emit(EngineEvent.ApprovalResolved(request, chat, active))
                }
            }
        }

        @Synchronized fun send(message: JsonObject) {
            check(!closed) { "Antigravity disconnected." }
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }

        suspend fun request(method: String, params: JsonObject): JsonObject {
            val id = serial.incrementAndGet()
            val result = CompletableDeferred<JsonObject>()
            waiting[id] = result
            try {
                send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
                return result.await()
            } finally { waiting.remove(id) }
        }

        fun read() {
            scope.launch { runCatching { process.errorStream.use { input -> val buf = ByteArray(4096); while (input.read(buf) >= 0) Unit } } }
            scope.launch {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val msg = Json.parseToJsonElement(line).jsonObject
                            val id = msg["id"]
                            val method = msg.str("method")
                            val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
                            when {
                                method == null -> {
                                    val reply = (id as? JsonPrimitive)?.longOrNull?.let(waiting::get) ?: continue
                                    val error = msg["error"] as? JsonObject
                                    if (error == null) reply.complete(msg["result"] as? JsonObject ?: JsonObject(emptyMap()))
                                    else {
                                        val reason = error.str("message").orEmpty().take(400)
                                        reply.completeExceptionally(IllegalStateException(if (reason.contains("authenticat", true))
                                            "Sign in with agy on this computer, then send again." else "Antigravity: $reason"))
                                    }
                                }
                                method == "session/update" -> update(params)
                                method == "session/request_permission" && id != null -> permission(id, params)
                                id != null -> send(buildJsonObject {
                                    put("jsonrpc","2.0"); put("id",id)
                                    put("error", buildJsonObject { put("code",-32601); put("message","Unsupported client method") })
                                })
                            }
                        }
                    }
                } catch (_: Exception) {
                    // EOF, transport loss or an invalid message fails outstanding calls below.
                } finally { dispose() }
            }
        }

        private suspend fun update(params: JsonObject) {
            val t = turn ?: return // Ignore replayed messages during session/load.
            val chat = thread ?: return
            if (params.str("sessionId") != chat.removePrefix(PREFIX)) return
            val update = params["update"] as? JsonObject ?: return
            when (update.str("sessionUpdate")) {
                "agent_message_chunk" -> (update["content"] as? JsonObject)?.str("text")?.let {
                    stream.emit(EngineEvent.TextDelta(it, chat, t))
                }
                "tool_call", "tool_call_update" -> update.str("title")?.let {
                    stream.emit(EngineEvent.Activity(it, chat, t))
                }
            }
        }

        private suspend fun permission(id: JsonElement, params: JsonObject) {
            val chat = thread
            val t = turn
            val once = (params["options"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it.str("kind") == "allow_once" }?.str("optionId")
            if (t == null || chat == null || params.str("sessionId") != chat.removePrefix(PREFIX) || once == null) {
                respond(id, null)
                return
            }
            val key = UUID.randomUUID().toString()
            approvals[key] = Permission(this, id, once)
            val tool = params["toolCall"] as? JsonObject
            val details = buildJsonObject {
                put("title", tool?.str("title") ?: "Computer tool")
                tool?.get("rawInput")?.let { put("input", it) }
            }
            stream.emit(EngineEvent.Approval(key, "antigravity/request_permission", details, chat, t))
        }

        fun respond(id: JsonElement, option: String?) = send(buildJsonObject {
            put("jsonrpc","2.0"); put("id",id)
            put("result", buildJsonObject { put("outcome", buildJsonObject {
                put("outcome", if (option == null) "cancelled" else "selected")
                option?.let { put("optionId",it) }
            }) })
        })

        fun dispose() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            try { bridge?.close() } finally { process.destroy() }
            thread?.let { chats.remove(it, this) }
            peers.remove(this)
            approvals.entries.removeIf { it.value.peer === this }
            waiting.values.forEach { it.completeExceptionally(IllegalStateException("Antigravity disconnected. Check the computer before retrying.")) }
        }
    }

    suspend fun openAt(cwd: String, previous: String?, tools: List<ToolDefinition> = emptyList()): String = withContext(Dispatchers.IO) {
        require(previous == null || previous.startsWith(PREFIX)) { "Invalid Antigravity session." }
        val peer = Peer(launch(cwd))
        peers.add(peer)
        try {
            peer.read()
            withTimeoutOrNull(60_000) {
                val init = peer.request("initialize", buildJsonObject {
                    put("protocolVersion", 1); put("clientCapabilities", JsonObject(emptyMap()))
                })
                check(init["protocolVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported Antigravity ACP version." }
                val mcp = if (tools.isEmpty()) null else {
                    val create = checkNotNull(phoneBridge) { "The phone tool bridge is unavailable." }
                    create(tools, peer::callPhone).also(peer::attach)
                }
                val reply = peer.request(if (previous == null) "session/new" else "session/load", buildJsonObject {
                    put("cwd", cwd)
                    put("mcpServers", buildJsonArray { mcp?.let { add(it.serverConfig) } })
                    previous?.let { put("sessionId", it.removePrefix(PREFIX)) }
                })
                val thread = previous ?: PREFIX + checkNotNull(reply.str("sessionId")?.takeIf { it.isNotBlank() }) { "Missing Antigravity session ID." }
                peer.thread = thread
                check(chats.putIfAbsent(thread, peer) == null) { "Antigravity already has this conversation open." }
                scope.launch { delay(90_000); if (peer.turn == null) peer.dispose() }
                thread
            } ?: error("Antigravity did not open this session within a minute. Check agy on the computer.")
        } catch (e: Throwable) { peer.dispose(); throw e }
    }

    override suspend fun startTurn(
        threadId: String, prompt: String, images: List<File>, reasoningEffort: String?, skill: AgentSkill?,
        capabilities: DeviceCapabilities, planModel: String?,
    ): String {
        check(planModel == null) { "Turn off Plan mode to send this Antigravity message." }
        return start(threadId, prompt, images, capabilities)
    }

    override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String =
        start(threadId, prompt, images, null)

    private suspend fun start(threadId: String, prompt: String, images: List<File>, capabilities: DeviceCapabilities?): String {
        val peer = checkNotNull(chats[threadId]) { "Reopen this Antigravity session." }
        val turn = UUID.randomUUID().toString()
        synchronized(peer) {
            check(peer.turn == null) { "A turn is already running." }
            peer.turn = turn
        }
        scope.launch {
            try {
                val content = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "You are Mike in an Android chat. Antigravity runs on the user's computer in the selected project. " +
                            "Use the computer's tools and follow its project instructions. Answer in the user's language. " +
                            "Ask before deleting user data, sending externally, or spending money.")
                    })
                    capabilities?.let {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", DeviceRuntimeContext.render(it) + "\nUse the mike MCP server to call these phone tools.")
                        })
                    }
                    add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    for (image in images) {
                        val mime = when (image.extension.lowercase()) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            else -> error("Unsupported Antigravity image type.")
                        }
                        add(buildJsonObject { put("type","image"); put("mimeType",mime); put("data", Base64.getEncoder().encodeToString(image.readBytes())) })
                    }
                }
                stream.emit(EngineEvent.TurnStarted(threadId, turn))
                val result = peer.request("session/prompt", buildJsonObject { put("sessionId",threadId.removePrefix(PREFIX)); put("prompt",content) })
                val reason = result.str("stopReason")
                val status = when (reason) { "end_turn" -> "completed"; "cancelled" -> "interrupted"; else -> "failed" }
                peer.dispose()
                stream.emit(EngineEvent.TurnFinished(status, if (status == "failed") "Antigravity stopped: $reason" else null, threadId, turn))
            } catch (e: Exception) {
                peer.dispose()
                stream.emit(EngineEvent.Failure(e.message ?: "Antigravity turn failed.", threadId, turn, uncertain = true))
            }
        }
        return turn
    }

    override suspend fun interrupt(threadId: String, turnId: String) = withContext(Dispatchers.IO) {
        val peer = chats[threadId]?.takeIf { it.turn == turnId } ?: return@withContext
        try {
            peer.send(buildJsonObject { put("jsonrpc","2.0"); put("method","session/cancel")
                put("params",buildJsonObject { put("sessionId",threadId.removePrefix(PREFIX)) }) })
        } finally { peer.dispose() }
    }

    override suspend fun answerApproval(requestId: String, allow: Boolean) = withContext(Dispatchers.IO) {
        approvals.remove(requestId)?.let { permission -> permission.peer.respond(permission.id, if (allow) permission.once else null) }
        Unit
    }

    override suspend fun connect() {}
    // The native session/new or session/load checks the host's actual credentials.
    override suspend fun account() = AccountStatus(true, "Computer Antigravity")
    override suspend fun login(): AccountStatus = error("Sign in with agy on the computer.")
    override suspend fun logout(): Unit = error("Manage Antigravity sign-in on the computer.")
    override suspend fun models() = listOf("antigravity")
    override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) =
        openAt(workspace.path, threadId, tools)
    override suspend fun steer(threadId: String, turnId: String, prompt: String): Unit = error("Queue a message for the next Antigravity turn.")
    override suspend fun answerTool(requestId: String, result: ToolResult) {
        toolReplies[requestId]?.complete(result)
    }
    override suspend fun close() { peers.toList().forEach { it.dispose() }; scope.cancel() }

    companion object {
        const val PREFIX = "agy:"
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
