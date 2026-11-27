/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.enginecodex

import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.SecretRedactor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one ephemeral, read-only, tool-less Codex turn on a side [RuntimeHost]
 * and returns the assistant's text reply.
 *
 * Used by background maintenance such as nightly user-memory consolidation so
 * it never touches the main app-server, never leaves a saved thread on disk,
 * and never creates a chat in the user's drawer.
 */
class CodexOneShotRunner(
    private val runtime: RuntimeHost,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    suspend fun complete(
        instructions: String,
        prompt: String,
        tokens: ExternalChatgptTokens? = null,
        refresh: (suspend (String?) -> ExternalChatgptTokens)? = null,
    ): String = withContext(Dispatchers.IO) {
        val process = runtime.startAppServer()
        val session = Session(process, refresh)
        try {
            withTimeoutOrNull(timeoutMs) {
                session.run(instructions, prompt, tokens, runtime.homeDirectory.absolutePath)
            } ?: error("Codex did not finish in time.")
        } finally {
            withContext(NonCancellable) {
                session.close()
                runCatching { runtime.stop() }
            }
        }
    }

    private class Session(
        private val process: Process,
        private val refresh: (suspend (String?) -> ExternalChatgptTokens)?,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writer: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val writeLock = Mutex()
        private val ids = AtomicLong()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val turnEnd = CompletableDeferred<Unit>()
        private val textLock = Any()
        private val deltas = StringBuilder()
        private var completedMessage: String? = null
        @Volatile private var threadId: String? = null
        @Volatile private var turnId: String? = null

        init {
            scope.launch {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isNotBlank()) runCatching { receive(CodexTransportJson.parse(line)) }
                        }
                    }
                } catch (_: IOException) {
                }
                val stopped = IllegalStateException("Codex stopped before answering.")
                pending.values.forEach { it.completeExceptionally(stopped) }
                turnEnd.completeExceptionally(stopped)
            }
            scope.launch {
                try {
                    process.errorStream.bufferedReader(Charsets.UTF_8).use { reader -> while (reader.readLine() != null) Unit }
                } catch (_: IOException) {
                }
            }
        }

        suspend fun run(instructions: String, prompt: String, tokens: ExternalChatgptTokens?, cwd: String): String {
            request("initialize", buildJsonObject {
                put("clientInfo", buildJsonObject { put("name", "android_agent"); put("title", "Hey Mike"); put("version", "0.1.0") })
                put("capabilities", buildJsonObject { put("experimentalApi", true) })
            })
            notify("initialized", buildJsonObject {})
            if (tokens != null) request("account/login/start", CodexWindowStarter.loginParams(tokens))
            val model = CodexWindowStarter.defaultModel(request("model/list", buildJsonObject {}))
            val started = request("thread/start", threadParams(cwd, model?.first, instructions))
            threadId = ((started["thread"] as? JsonObject)?.string("id")).orEmpty().ifBlank { error("Codex returned no thread ID") }
            val turn = request("turn/start", turnParams(threadId!!, prompt, model?.second))
            turnId = (turn["turn"] as? JsonObject)?.string("id")
            turnEnd.await()
            return synchronized(textLock) {
                completedMessage?.takeIf { it.isNotBlank() } ?: deltas.toString()
            }.trim()
        }

        fun close() {
            runCatching { process.outputStream.close() }
            process.destroy()
            scope.cancel()
        }

        private suspend fun request(method: String, params: JsonObject): JsonObject {
            val id = ids.incrementAndGet().toString()
            val response = CompletableDeferred<JsonObject>()
            pending[id] = response
            try {
                write(buildJsonObject { put("id", id.toLong()); put("method", method); put("params", params) })
                return response.await()
            } finally {
                pending.remove(id)
            }
        }

        private suspend fun notify(method: String, params: JsonObject) =
            write(buildJsonObject { put("method", method); put("params", params) })

        private suspend fun write(message: JsonObject) = writeLock.withLock {
            runInterruptible { writer.apply { write(message.toString()); newLine(); flush() } }
        }

        private suspend fun receive(message: JsonObject) {
            val id = message["id"]?.toString()
            val method = message.string("method").orEmpty()
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            if (method.isEmpty() && id != null) {
                val deferred = pending.remove(id) ?: return
                val error = message["error"] as? JsonObject
                if (error != null) deferred.completeExceptionally(IllegalStateException(errorText(error)))
                else deferred.complete(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
                return
            }
            when {
                method == "account/chatgptAuthTokens/refresh" && id != null -> {
                    val updated = runCatching { (refresh ?: error("no refresh"))(params.string("previousAccountId")) }.getOrNull()
                    if (updated != null) {
                        write(buildJsonObject { put("id", Json.parseToJsonElement(id)); put("result", CodexWindowStarter.tokenResult(updated)) })
                    } else {
                        write(buildJsonObject {
                            put("id", Json.parseToJsonElement(id))
                            put("error", buildJsonObject { put("code", -32000); put("message", "Mike could not refresh this account.") })
                        })
                    }
                }
                id != null -> write(buildJsonObject {
                    put("id", Json.parseToJsonElement(id))
                    put("error", buildJsonObject { put("code", -32601); put("message", "Tools are disabled in background memory updates.") })
                })
                method == "item/agentMessage/delta" -> {
                    if (!ours(params.string("threadId"), params.string("turnId"))) return
                    params.string("delta")?.let { delta -> synchronized(textLock) { deltas.append(delta) } }
                }
                method == "item/completed" -> {
                    if (!ours(params.string("threadId"), params.string("turnId"))) return
                    val item = params["item"] as? JsonObject ?: return
                    if (item.string("type") == "agentMessage") {
                        item.string("text")?.takeIf { it.isNotBlank() }?.let { text ->
                            synchronized(textLock) { completedMessage = text }
                        }
                    }
                }
                method == "turn/completed" -> {
                    val turn = params["turn"] as? JsonObject ?: params
                    if (!ours(params.string("threadId"), turn.string("id"))) return
                    when (val status = turn.string("status")) {
                        "completed" -> turnEnd.complete(Unit)
                        else -> turnEnd.completeExceptionally(
                            IllegalStateException((turn["error"] as? JsonObject)?.let(::errorText) ?: "Turn ended as $status."),
                        )
                    }
                }
                method == "error" -> {
                    if ((params["willRetry"] as? JsonPrimitive)?.booleanOrNull == true) return
                    if (!ours(params.string("threadId"), params.string("turnId"))) return
                    turnEnd.completeExceptionally(
                        IllegalStateException((params["error"] as? JsonObject)?.let(::errorText) ?: "Codex reported an error."),
                    )
                }
            }
        }

        private fun ours(thread: String?, turn: String?): Boolean =
            (thread.isNullOrBlank() || thread == threadId) && (turn.isNullOrBlank() || turnId == null || turn == turnId)
    }

    companion object {
        const val TIMEOUT_MS = 180_000L

        internal fun threadParams(cwd: String, model: String?, instructions: String): JsonObject = buildJsonObject {
            put("cwd", cwd)
            put("approvalPolicy", "never")
            put("sandbox", "read-only")
            put("ephemeral", true)
            put("developerInstructions", instructions)
            if (!model.isNullOrBlank()) put("model", model)
        }

        internal fun turnParams(threadId: String, prompt: String, effort: String?): JsonObject = buildJsonObject {
            put("threadId", threadId)
            put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", prompt) }) })
            if (!effort.isNullOrBlank()) put("effort", effort)
        }

        private fun errorText(error: JsonObject): String =
            SecretRedactor.redact(error.string("message")?.ifBlank { null } ?: "Codex refused the request.").take(300)

        private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    }
}
