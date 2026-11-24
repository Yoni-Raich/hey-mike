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

/** One ACP process per open conversation, reused while idle across consecutive turns. The computer owns the files and the native session. */
internal class AntigravityEngine(
    private val phoneBridge: (suspend (List<ToolDefinition>, PhoneCall) -> AcpPhoneBridge)? = null,
    private val instructions: () -> String = { DEFAULT_INSTRUCTIONS },
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val launch: suspend (String) -> Process,
) : AgentEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events = stream.asSharedFlow()
    private val chats = ConcurrentHashMap<String, Peer>()
    private val approvals = ConcurrentHashMap<String, Permission>()
    private val peers = ConcurrentHashMap.newKeySet<Peer>()
    private val toolReplies = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()

    private inner class Permission(val peer: Peer, val id: JsonElement, val once: String?, val chat: String, val turn: String)

    private inner class Peer(
        val process: Process,
        val cwd: String,
        val modelSlug: String?,
        val toolsSignature: List<ToolDefinition>,
    ) {
        val waiting = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
        val serial = AtomicLong()
        @Volatile var promptDone: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }
        private val writeLock = Any()
        private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val stderrLines = ArrayDeque<String>()
        private val toolTitles = ConcurrentHashMap<String, String>()
        private val segment = AtomicLong()
        @Volatile var thread: String? = null
        @Volatile var turn: String? = null
        @Volatile var cancelled = false
        private var closed = false
        private var bridge: AcpPhoneBridge? = null
        private var idleTimer: Job? = null

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

        fun toolPromptHint(): String = synchronized(this) { bridge?.promptHint.orEmpty() }

        fun armIdleTimer(job: Job) {
            val previous: Job?
            val cancelNow = synchronized(this) {
                if (closed || turn != null) {
                    previous = null
                    true
                } else {
                    previous = idleTimer
                    idleTimer = job
                    false
                }
            }
            previous?.cancel()
            if (cancelNow) job.cancel()
        }

        fun tryReuse(cwd: String, modelSlug: String?, tools: List<ToolDefinition>): Boolean {
            val timer = synchronized(this) {
                if (closed || cancelled || turn != null || !process.isAlive) return false
                if (this.cwd != cwd || this.modelSlug != modelSlug || this.toolsSignature != tools) return false
                idleTimer.also { idleTimer = null }
            }
            timer?.cancel()
            return true
        }

        fun claimTurn(nextTurn: String): CompletableDeferred<Unit> {
            val done = CompletableDeferred<Unit>()
            val timer = synchronized(this) {
                check(!closed) { "Reopen this Antigravity session." }
                check(turn == null) { "A turn is already running." }
                turn = nextTurn
                promptDone = done
                idleTimer.also { idleTimer = null }
            }
            timer?.cancel()
            return done
        }

        fun finishTurn(expectedTurn: String): Boolean {
            val (done, keepWarm) = synchronized(this) {
                if (turn == expectedTurn) turn = null
                promptDone to (!closed && !cancelled && process.isAlive)
            }
            toolTitles.clear()
            done.complete(Unit)
            return keepWarm
        }

        fun disposeIfIdle() {
            val shouldDispose = synchronized(this) { !closed && turn == null }
            if (shouldDispose) dispose()
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
                segment.incrementAndGet()
                stream.emit(EngineEvent.ToolCall(request, name, arguments, chat, active))
                return reply.await()
            } finally {
                toolReplies.remove(request, reply)
                if (!reply.isCompleted) withContext(NonCancellable) {
                    stream.emit(EngineEvent.ApprovalResolved(request, chat, active))
                }
            }
        }

        fun send(message: JsonObject) {
            synchronized(this) { check(!closed) { "Antigravity disconnected." } }
            synchronized(writeLock) {
                writer.write(message.toString())
                writer.newLine()
                writer.flush()
            }
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
            scope.launch {
                runCatching {
                    process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val clean = SecretRedactor.redact(line.replace(ANSI, "")).trim()
                            if (clean.isEmpty()) continue
                            synchronized(stderrLines) {
                                if (stderrLines.size >= MAX_STDERR_LINES) stderrLines.removeFirst()
                                stderrLines.addLast(clean.take(240))
                            }
                        }
                    }
                }
            }
            scope.launch {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            val msg = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                            val id = msg["id"]
                            val method = msg.str("method")
                            val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
                            when {
                                method == null -> {
                                    val reply = (id as? JsonPrimitive)?.longOrNull?.let(waiting::get) ?: continue
                                    val error = msg["error"] as? JsonObject
                                    if (error == null) reply.complete(msg["result"] as? JsonObject ?: JsonObject(emptyMap()))
                                    else {
                                        val reason = SecretRedactor.redact(error.str("message").orEmpty()).take(400)
                                        reply.completeExceptionally(IllegalStateException(if (reason.contains("authenticat", true))
                                            "Sign in with agy on this computer, then send again." else "Antigravity: $reason"))
                                    }
                                }
                                method == "session/update" -> update(params)
                                method == "session/request_permission" && id != null -> permission(id, params)
                                id != null -> send(buildJsonObject {
                                    put("jsonrpc", "2.0"); put("id", id)
                                    put("error", buildJsonObject { put("code", -32601); put("message", "Unsupported client method") })
                                })
                            }
                        }
                    }
                } catch (_: Exception) {
                    // EOF or transport loss fails outstanding calls below.
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
                    stream.emit(EngineEvent.TextDelta(it, chat, t, itemId = "agy-${segment.get()}"))
                }
                "tool_call", "tool_call_update" -> {
                    val callId = update.str("toolCallId")?.takeIf { it.isNotBlank() }
                    val title = update.str("title")?.takeIf { it.isNotBlank() }?.also {
                        if (callId != null) toolTitles[callId] = it
                    } ?: callId?.let(toolTitles::get)
                    if (callId != null && title != null) {
                        if (update.str("sessionUpdate") == "tool_call") segment.incrementAndGet()
                        val state = when (update.str("status")?.lowercase()) {
                            "completed" -> "complete"
                            "failed", "error", "cancelled" -> "error"
                            else -> "streaming"
                        }
                        val detail = toolDetail(update)
                        stream.emit(EngineEvent.ItemActivity(callId, title, detail, state, chat, t))
                    } else if (title != null) {
                        segment.incrementAndGet()
                        stream.emit(EngineEvent.Activity(title, chat, t))
                    }
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
            approvals[key] = Permission(this, id, once, chat, t)
            val tool = params["toolCall"] as? JsonObject
            val rawInput = tool?.get("rawInput")
            val inputObj = rawInput as? JsonObject
            val command = inputObj?.let { firstString(it, "CommandLine", "command", "cmd") }
            val path = inputObj?.let { firstString(it, "Path", "TargetFile", "path", "filePath") }
            val details = buildJsonObject {
                put("title", SecretRedactor.redact(tool?.str("title") ?: "Computer tool").take(200))
                command?.let { put("command", SecretRedactor.redact(it).take(400)) }
                path?.let { put("path", SecretRedactor.redact(it).take(300)) }
                if (command == null && path == null && rawInput != null) {
                    put("input", SecretRedactor.redact(rawInput.toString()).take(MAX_APPROVAL_INPUT_CHARS))
                }
            }
            stream.emit(EngineEvent.Approval(key, "antigravity/request_permission", details, chat, t))
        }

        fun respond(id: JsonElement, option: String?) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("result", buildJsonObject { put("outcome", buildJsonObject {
                put("outcome", if (option == null) "cancelled" else "selected")
                option?.let { put("optionId", it) }
            }) })
        })

        fun stderrTail(): String = synchronized(stderrLines) {
            stderrLines.joinToString(" | ").take(MAX_STDERR_SUMMARY_CHARS)
        }

        fun dispose() {
            val timer: Job?
            val ownedBridge: AcpPhoneBridge?
            synchronized(this) {
                if (closed) return
                closed = true
                timer = idleTimer.also { idleTimer = null }
                ownedBridge = bridge.also { bridge = null }
            }
            timer?.cancel()
            try { ownedBridge?.close() } finally { process.destroy() }
            thread?.let { chats.remove(it, this) }
            peers.remove(this)
            val droppedApprovals = mutableListOf<Pair<String, Permission>>()
            val iterator = approvals.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.peer === this) {
                    droppedApprovals += entry.key to entry.value
                    iterator.remove()
                }
            }
            droppedApprovals.forEach { (key, permission) ->
                stream.tryEmit(EngineEvent.ApprovalResolved(key, permission.chat, permission.turn))
            }
            val detail = stderrTail().takeIf { !cancelled && it.isNotEmpty() }
            val message = if (detail != null) "Antigravity disconnected: $detail"
            else "Antigravity disconnected. Check the computer before retrying."
            waiting.values.forEach { it.completeExceptionally(IllegalStateException(message)) }
            promptDone.complete(Unit)
        }
    }

    suspend fun openAt(cwd: String, previous: String?, tools: List<ToolDefinition> = emptyList(), model: String? = null): String = withContext(Dispatchers.IO) {
        require(previous == null || previous.startsWith(PREFIX)) { "Invalid Antigravity session." }
        val slug = AntigravityModels.slug(model)
        if (previous != null) {
            chats[previous]?.let { existing ->
                if (existing.tryReuse(cwd, slug, tools)) {
                    existing.armIdleTimer(scope.launch { delay(idleTimeoutMs); existing.disposeIfIdle() })
                    return@withContext previous
                }
                check(existing.turn == null) { "Antigravity already has this conversation open." }
                existing.dispose()
            }
        }
        val peer = Peer(launch(cwd), cwd, slug, tools)
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
                slug?.let { selectModel(peer, thread.removePrefix(PREFIX), reply, it) }
                peer.thread = thread
                check(chats.putIfAbsent(thread, peer) == null) { "Antigravity already has this conversation open." }
                peer.armIdleTimer(scope.launch { delay(idleTimeoutMs); peer.disposeIfIdle() })
                thread
            } ?: error("Antigravity did not open this session within a minute. Check agy on the computer.")
        } catch (e: Throwable) { peer.dispose(); throw e }
    }

    private suspend fun selectModel(peer: Peer, sessionId: String, session: JsonObject, model: String) {
        val option = (session["configOptions"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.str("category") == "model" }
        if (option != null) {
            if (option.str("currentValue") == model) return
            val configId = checkNotNull(option.str("id")) { "Antigravity returned an invalid model option." }
            peer.request("session/set_config_option", buildJsonObject {
                put("sessionId", sessionId)
                put("configId", configId)
                put("value", model)
            })
            return
        }
        val legacy = session["models"] as? JsonObject
        if (legacy?.str("currentModelId") == model) return
        peer.request("session/set_model", buildJsonObject {
            put("sessionId", sessionId)
            put("modelId", model)
        })
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
        peer.claimTurn(turn)
        scope.launch {
            try {
                val content = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", instructions().trimEnd() + "\n")
                    })
                    val hint = peer.toolPromptHint()
                    if (capabilities != null) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", buildString {
                                append(DeviceRuntimeContext.render(capabilities))
                                append("\nUse the mike MCP server to call these phone tools.")
                                if (hint.isNotBlank()) {
                                    append('\n')
                                    append(hint)
                                }
                                append('\n')
                            })
                        })
                    } else if (hint.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", hint.trimEnd() + "\n")
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
                        check(image.length() <= MAX_IMAGE_BYTES) { "Image exceeds the 20 MB limit for Antigravity." }
                        add(buildJsonObject { put("type", "image"); put("mimeType", mime); put("data", Base64.getEncoder().encodeToString(image.readBytes())) })
                    }
                }
                stream.emit(EngineEvent.TurnStarted(threadId, turn))
                val result = peer.request("session/prompt", buildJsonObject { put("sessionId", threadId.removePrefix(PREFIX)); put("prompt", content) })
                val reason = result.str("stopReason")
                val status = when {
                    peer.cancelled || reason == "cancelled" -> "interrupted"
                    reason == "end_turn" -> "completed"
                    else -> "failed"
                }
                val keepWarm = peer.finishTurn(turn)
                if (status == "completed" && keepWarm) {
                    peer.armIdleTimer(scope.launch { delay(idleTimeoutMs); peer.disposeIfIdle() })
                } else {
                    peer.dispose()
                }
                stream.emit(EngineEvent.TurnFinished(status, if (status == "failed") "Antigravity stopped: $reason" else null, threadId, turn))
            } catch (e: Exception) {
                peer.finishTurn(turn)
                peer.dispose()
                if (peer.cancelled) {
                    stream.emit(EngineEvent.TurnFinished("interrupted", null, threadId, turn))
                } else {
                    stream.emit(EngineEvent.Failure(e.message ?: "Antigravity turn failed.", threadId, turn, uncertain = true))
                }
            }
        }
        return turn
    }

    override suspend fun interrupt(threadId: String, turnId: String) = withContext(Dispatchers.IO) {
        val peer = chats[threadId]?.takeIf { it.turn == turnId } ?: return@withContext
        peer.cancelled = true
        try {
            withTimeoutOrNull(CANCEL_SEND_TIMEOUT_MS) {
                runCatching {
                    runInterruptible {
                        peer.send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("method", "session/cancel")
                            put("params", buildJsonObject { put("sessionId", threadId.removePrefix(PREFIX)) })
                        })
                    }
                }
            }
            // Give agy a short window to acknowledge cancellation and flush native state before closing its process.
            withTimeoutOrNull(CANCEL_DRAIN_TIMEOUT_MS) { peer.promptDone.await() }
        } finally { peer.dispose() }
    }

    override suspend fun answerApproval(requestId: String, allow: Boolean) = withContext(Dispatchers.IO) {
        approvals.remove(requestId)?.let { permission ->
            runCatching { permission.peer.respond(permission.id, if (allow) permission.once else null) }
        }
        Unit
    }

    override suspend fun connect() {}
    // The native session/new or session/load checks the host's actual credentials.
    override suspend fun account() = AccountStatus(true, "Computer Antigravity")
    override suspend fun login(): AccountStatus = error("Sign in with agy on the computer.")
    override suspend fun logout(): Unit = error("Manage Antigravity sign-in on the computer.")
    override suspend fun models() = listOf("antigravity")
    override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) =
        openAt(workspace.path, threadId, tools, model)
    override suspend fun steer(threadId: String, turnId: String, prompt: String): Unit = error("Queue a message for the next Antigravity turn.")
    override suspend fun compact(threadId: String): CompactResult = error("Compaction is not available for Antigravity.")
    override suspend fun answerTool(requestId: String, result: ToolResult) {
        toolReplies[requestId]?.complete(result)
    }
    override suspend fun close() { peers.toList().forEach { it.dispose() }; scope.cancel() }

    companion object {
        const val PREFIX = "agy:"
        const val DEFAULT_INSTRUCTIONS =
            "You are Mike in an Android chat. Antigravity runs on the user's computer in the selected project. " +
                "Use the computer's tools and follow its project instructions. Answer in the user's language. " +
                "Ask before deleting user data, sending externally, or spending money."
        private const val DEFAULT_IDLE_TIMEOUT_MS = 90_000L
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_STDERR_LINES = 16
        private const val MAX_STDERR_SUMMARY_CHARS = 500
        private const val MAX_APPROVAL_INPUT_CHARS = 500
        private const val MAX_TOOL_DETAIL_CHARS = 1_500
        private const val CANCEL_SEND_TIMEOUT_MS = 400L
        private const val CANCEL_DRAIN_TIMEOUT_MS = 900L
        private val ANSI = Regex("\u001b\\[[0-9;]*[A-Za-z]")

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun firstString(obj: JsonObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

        private fun toolDetail(update: JsonObject): String {
            val rawInput = update["rawInput"] as? JsonObject
            val parts = buildList {
                rawInput?.let { input ->
                    firstString(input, "CommandLine", "command", "cmd")?.let { add(it) }
                    firstString(input, "Path", "TargetFile", "path", "filePath")?.let { add(it) }
                }
                if (isEmpty() && update["rawInput"] != null) add(update["rawInput"].toString())
                (update["rawOutput"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            return SecretRedactor.redact(parts.joinToString("\n")).take(MAX_TOOL_DETAIL_CHARS)
        }
    }
}
