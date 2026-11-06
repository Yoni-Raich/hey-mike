// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@android.annotation.TargetApi(30) // Manifest disables this service below Android 11.
abstract class MikeConnectService : Service() {
    abstract fun MikeConnect.Builder.configure()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry by lazy { MikeConnect.Builder().apply { configure() }.build() }
    private val ledger by lazy { RequestLedger(java.io.File(noBackupFilesDir, "mike-connect-ledger")) }
    private val jobs = ConcurrentHashMap<String, Job>()
    private data class Round(val fingerprint: String, val state: String, val created: Long)
    private val rounds = ConcurrentHashMap<String, Round>()
    private fun caller() = CallerVerifier.verify(this, Binder.getCallingUid(), registry.trusted)
    private val endpoint = object : IMikeConnect.Stub() {
        override fun bindingLevel(): Int { caller(); return ConnectProtocol.BINDING }
        override fun cancel(jsonRpcId: String) { val agent = caller(); jobs["$agent:${JsonPrimitive(jsonRpcId)}"]?.cancel() }
        override fun send(request: ConnectMessage, callback: IConnectCallback) {
            val agent = try { caller() } catch (_: SecurityException) {
                request.close()
                runCatching { callback.onResponse(ConnectMessage(ConnectProtocol.rpcError(null, -32600, "Untrusted caller").toString())) }
                return
            }
            val body = try { require(request.blobs.isEmpty()) { "Request blobs are not supported" }; ConnectProtocol.parse(request.json) }
                catch (e: Exception) { request.close(); runCatching { callback.onResponse(ConnectMessage(ConnectProtocol.rpcError(null, -32700, "Invalid or oversized message").toString())) }; return }
            request.close()
            if (!body.containsKey("id")) {
                if (body.string("method") == "notifications/cancelled") {
                    val cancelledId = (body["params"] as? JsonObject)?.get("requestId")
                    if (cancelledId != null) jobs["$agent:$cancelledId"]?.cancel()
                }
                return // JSON-RPC notifications never receive a response.
            }
            val id = body["id"] as? JsonPrimitive
            if (body.string("jsonrpc") != "2.0" || id == null || id is JsonNull || (!id.isString && id.longOrNull == null)) {
                runCatching { callback.onResponse(ConnectMessage(ConnectProtocol.rpcError(id, -32600, "Invalid request").toString())) }; return
            }
            val key = "$agent:$id"
            synchronized(jobs) {
                if (jobs.keys.count { it.startsWith("$agent:") } >= 4 || jobs.containsKey(key)) {
                    runCatching { callback.onResponse(ConnectMessage(ConnectProtocol.response(id, ConnectProtocol.failure("busy", "Too many concurrent requests or duplicate RPC id")).toString())) }; return
                }
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    val death = IBinder.DeathRecipient { jobs[key]?.cancel() }
                    val binder = callback.asBinder()
                    try {
                        binder.linkToDeath(death, 0)
                        val response = dispatch(agent, id, body) { notification ->
                            runCatching { callback.onNotification(ConnectMessage(notification.toString())) }
                        }
                        callback.onResponse(ConnectMessage(response.toString()))
                    } catch (_: CancellationException) {
                        runCatching { callback.onResponse(ConnectMessage(ConnectProtocol.response(id, ConnectProtocol.failure("cancelled", "Request cancelled; a write may have run", true)).toString())) }
                    } catch (_: Exception) {
                        runCatching { callback.onResponse(ConnectMessage(ConnectProtocol.response(id, ConnectProtocol.failure("app_error", "Provider failed")).toString())) }
                    } finally { runCatching { binder.unlinkToDeath(death, 0) }; jobs.remove(key) }
                }
                jobs[key] = job
                job.invokeOnCompletion { jobs.remove(key, job) }
                job.start()
            }
        }
    }
    override fun onBind(intent: Intent?) = if (Build.VERSION.SDK_INT >= 30) endpoint else null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    /** In-process test seam. Transport authentication is tested separately on a device. */
    suspend fun testCall(name: String, arguments: JsonObject): JsonObject {
        val request = ConnectProtocol.request(UUID.randomUUID().toString(), "tools/call", buildJsonObject {
            put("name", name); put("arguments", arguments); put("_meta", ConnectProtocol.meta(requestId = UUID.randomUUID().toString()))
        })
        return dispatch(packageName, request.getValue("id"), request) {}
    }
    private suspend fun dispatch(agent: String, id: JsonElement, request: JsonObject, notify: (JsonObject) -> Unit): JsonObject {
        val params = request["params"] as? JsonObject ?: return ConnectProtocol.rpcError(id, -32602, "Missing params")
        val meta = params["_meta"] as? JsonObject ?: return ConnectProtocol.rpcError(id, -32602, "Missing request metadata")
        if (meta.string("io.modelcontextprotocol/protocolVersion") != ConnectProtocol.VERSION)
            return ConnectProtocol.rpcError(id, -32022, "Unsupported protocol version")
        if (meta["io.modelcontextprotocol/clientInfo"] !is JsonObject || meta["io.modelcontextprotocol/clientCapabilities"] !is JsonObject)
            return ConnectProtocol.rpcError(id, -32602, "Missing client metadata")
        val result = when (request.string("method")) {
            "tools/list" -> buildJsonObject {
                put("resultType", "complete"); put("tools", JsonArray(registry.capabilities.map { it.definition.wire() }))
                put("_meta", buildJsonObject { put("io.modelcontextprotocol/serverInfo", buildJsonObject { put("name", packageName); put("version", ConnectProtocol.SDK_VERSION) }) })
            }
            "tools/call" -> call(agent, params, meta, notify)
            else -> return ConnectProtocol.rpcError(id, -32601, "Method not supported")
        }
        val response = ConnectProtocol.response(id, result)
        return if (response.toString().toByteArray().size <= ConnectProtocol.MAX_JSON_BYTES) response
            else ConnectProtocol.response(id, ConnectProtocol.failure("result_too_large", "Provider response exceeds the Binder limit", true))
    }
    private suspend fun call(agent: String, params: JsonObject, meta: JsonObject, notify: (JsonObject) -> Unit): JsonObject {
        val entry = registry.capabilities.firstOrNull { it.definition.name == params.string("name") }
            ?: return ConnectProtocol.failure("not_found", "Unknown capability")
        val cap = entry.definition
        val args = params["arguments"] as? JsonObject ?: return ConnectProtocol.failure("invalid_arguments", "Expected an arguments object")
        try { Schema.validate(cap.inputSchema, args) } catch (e: Exception) { return ConnectProtocol.failure("invalid_arguments", e.message.orEmpty()) }
        if (cap.access == Access.SENSITIVE && !meta.bool(ConnectProtocol.PREFIX + "userApproved"))
            return ConnectProtocol.failure("needs_user", "Sensitive capability requires approval")
        val requestId = meta.string(ConnectProtocol.PREFIX + "requestId")
        if (requestId.length !in 1..120) return ConnectProtocol.failure("invalid_arguments", "A stable requestId is required")
        val fingerprint = ConnectProtocol.fingerprint(buildJsonObject { put("name", cap.name); put("arguments", args) })
        val roundKey = "$agent:$requestId"
        val now = System.currentTimeMillis()
        rounds.entries.removeIf { now - it.value.created > 300_000 }
        if (rounds.size >= 256 && !rounds.containsKey(roundKey))
            return ConnectProtocol.failure("busy", "Too many unfinished user questions")
        val responses = params["inputResponses"] as? JsonObject ?: buildJsonObject {}
        val round = rounds[roundKey]
        if (round != null) {
            if (round.fingerprint != fingerprint || round.state != params.string("requestState") || System.currentTimeMillis() - round.created > 300_000)
                return ConnectProtocol.failure("request_id_conflict", "Invalid elicitation continuation")
            if (!rounds.remove(roundKey, round)) return ConnectProtocol.failure("busy", "Continuation is already running")
        } else if (cap.access != Access.READ) {
            ledger.reserve(agent, requestId, fingerprint)?.let { return it }
        } else if (responses.isNotEmpty()) return ConnectProtocol.failure("invalid_arguments", "Unexpected user input")
        var completed = false
        try {
            val notifications: (JsonObject) -> Unit = { message ->
                val token = meta["progressToken"]
                if (token != null) {
                    val params = message["params"]!!.jsonObject
                    notify(JsonObject(message + ("params" to JsonObject(params + ("progressToken" to token)))))
                }
            }
            val call = CallContext(requestId, meta.string(ConnectProtocol.PREFIX + "origin"), meta.bool(ConnectProtocol.PREFIX + "userApproved"), responses, notifications) { intent ->
                require(cap.ui != Ui.NONE) { "UI is not declared" }
                UiTokens.mint(this, intent)
            }
            val data = withTimeout(cap.timeoutMs) { entry.handler(call, args) }
            cap.outputSchema?.let { Schema.validate(it, data) }
            val result = buildJsonObject {
                ConnectProtocol.complete(data).forEach { (k,v) -> put(k,v) }
                call.openedUi?.let { token -> put("_meta", buildJsonObject { put(ConnectProtocol.PREFIX + "uiToken", token) }) }
            }
            if (result.toString().toByteArray().size > ConnectProtocol.MAX_JSON_BYTES - 1024) throw ConnectException("result_too_large", "Use a resource for large data")
            if (cap.access != Access.READ) ledger.finish(agent, requestId, fingerprint, result)
            completed = true
            return result
        } catch (question: InputRequired) {
            val state = UUID.randomUUID().toString()
            rounds[roundKey] = Round(fingerprint, state, System.currentTimeMillis())
            completed = true // reservation stays; a process death makes this outcome unknown
            return buildJsonObject {
                put("resultType", "input_required"); put("requestState", state)
                put("inputRequests", buildJsonObject { put(question.key, buildJsonObject {
                    put("method", "elicitation/create"); put("params", buildJsonObject {
                        put("mode", "form"); put("message", question.question.take(1_000))
                        put("requestedSchema", buildJsonObject {
                            put("type", "object"); put("properties", buildJsonObject { put("value", buildJsonObject {
                                put("type", "string"); if (question.options.isNotEmpty()) put("enum", JsonArray(question.options.map(::JsonPrimitive)))
                            }) }); put("required", JsonArray(listOf(JsonPrimitive("value"))))
                        })
                    })
                }) })
            }
        } catch (e: TimeoutCancellationException) {
            return ConnectProtocol.failure("outcome_unknown", "Provider call timed out", cap.access != Access.READ)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val result = ConnectProtocol.failure((e as? ConnectException)?.code ?: "app_error", e.message?.take(300) ?: "Provider failed", cap.access != Access.READ)
            if (cap.access != Access.READ) ledger.finish(agent, requestId, fingerprint, result)
            completed = true
            return result
        } finally {
            if (!completed) rounds.remove(roundKey)
        }
    }
}
