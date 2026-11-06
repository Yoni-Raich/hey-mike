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
import dev.androidagent.core.UsageLimit
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
import kotlinx.serialization.json.JsonArray
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
 * Starts a ChatGPT account's 5-hour window with one short message.
 *
 * The window starts at the first message after it resets, so the message is
 * the whole point; its answer is thrown away. It runs on a separate
 * app-server from [runtime], never the one chats use: that one holds the live
 * sign-in and the user's threads, and a side run must touch neither. The
 * thread is ephemeral, has no tools, and runs read-only with no approvals.
 *
 * With [tokens] the app-server signs in with them and keeps nothing on disk
 * (the live account, whose refresh token belongs to the main app-server);
 * without, it uses the `auth.json` already placed in its CODEX_HOME (a saved
 * account lent by the vault).
 */
class CodexWindowStarter(
    private val runtime: RuntimeHost,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** @return the account's quota as read right after the message; empty when it could not be read. */
    suspend fun start(
        tokens: ExternalChatgptTokens? = null,
        refresh: (suspend (String?) -> ExternalChatgptTokens)? = null,
    ): List<UsageLimit> = withContext(Dispatchers.IO) {
        val process = runtime.startAppServer()
        val session = Session(process, refresh)
        try {
            // A timeout is a failed start, not a cancelled caller.
            withTimeoutOrNull(timeoutMs) { session.run(tokens, runtime.homeDirectory.absolutePath) }
                ?: error("Codex did not answer in time.")
        } finally {
            withContext(NonCancellable) {
                session.close()
                runCatching { runtime.stop() }
            }
        }
    }

    private class Session(private val process: Process, private val refresh: (suspend (String?) -> ExternalChatgptTokens)?) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writer: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val writeLock = Mutex()
        private val ids = AtomicLong()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val turnEnd = CompletableDeferred<Unit>()
        @Volatile private var threadId: String? = null
        @Volatile private var turnId: String? = null
        @Volatile private var latestLimits: List<UsageLimit> = emptyList()

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
                val stopped = IllegalStateException("Codex stopped before the message was answered.")
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

        suspend fun run(tokens: ExternalChatgptTokens?, cwd: String): List<UsageLimit> {
            request("initialize", buildJsonObject {
                put("clientInfo", buildJsonObject { put("name", "android_agent"); put("title", "Hey Mike"); put("version", "0.1.0") })
                put("capabilities", buildJsonObject { put("experimentalApi", true) })
            })
            notify("initialized", buildJsonObject {})
            if (tokens != null) request("account/login/start", loginParams(tokens))
            val model = defaultModel(request("model/list", buildJsonObject {}))
            val started = request("thread/start", threadParams(cwd, model?.first))
            threadId = ((started["thread"] as? JsonObject)?.string("id")).orEmpty().ifBlank { error("Codex returned no thread ID") }
            val turn = request("turn/start", turnParams(threadId!!, model?.second))
            turnId = (turn["turn"] as? JsonObject)?.string("id")
            turnEnd.await()
            // The quota as the server now counts it. The turn's own updates are the fallback.
            return runCatching { CodexEngine.parseRateLimits(request("account/rateLimits/read", buildJsonObject {})) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: latestLimits
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
                        write(buildJsonObject { put("id", Json.parseToJsonElement(id)); put("result", tokenResult(updated)) })
                    } else {
                        write(buildJsonObject {
                            put("id", Json.parseToJsonElement(id))
                            put("error", buildJsonObject { put("code", -32000); put("message", "Mike could not refresh this account.") })
                        })
                    }
                }
                // Nothing here may run a command or change a file: refuse whatever is asked.
                id != null -> write(buildJsonObject {
                    put("id", Json.parseToJsonElement(id))
                    put("error", buildJsonObject { put("code", -32601); put("message", "Not available in a usage-window start.") })
                })
                method == "account/rateLimits/updated" -> CodexEngine.parseRateLimits(params).takeIf { it.isNotEmpty() }?.let { latestLimits = it }
                method == "turn/completed" -> {
                    val turn = params["turn"] as? JsonObject ?: params
                    if (!ours(params.string("threadId"), turn.string("id"))) return
                    when (val status = turn.string("status")) {
                        "completed" -> turnEnd.complete(Unit)
                        else -> turnEnd.completeExceptionally(IllegalStateException(
                            (turn["error"] as? JsonObject)?.let(::errorText) ?: "The message ended as $status.",
                        ))
                    }
                }
                method == "error" -> {
                    if ((params["willRetry"] as? JsonPrimitive)?.booleanOrNull == true) return
                    if (!ours(params.string("threadId"), params.string("turnId"))) return
                    turnEnd.completeExceptionally(IllegalStateException(
                        (params["error"] as? JsonObject)?.let(::errorText) ?: "Codex reported an error.",
                    ))
                }
            }
        }

        /** A notification without ids is about the only thread there is. */
        private fun ours(thread: String?, turn: String?): Boolean =
            (thread.isNullOrBlank() || thread == threadId) && (turn.isNullOrBlank() || turnId == null || turn == turnId)
    }

    companion object {
        /** Starting the app-server, one model call and a quota read. */
        const val TIMEOUT_MS = 120_000L

        /** Short, so a model told to say little says little; the answer is never read. */
        const val MESSAGE = "hi"

        private const val INSTRUCTIONS =
            "This is an automatic message from Hey Mike that starts the account's usage window. " +
                "Reply with one word. Do not run commands, read files or use tools."

        /** The lowest effort first: the message needs no thought. */
        private val EFFORT_ORDER = listOf("none", "minimal", "low", "medium")

        internal fun loginParams(tokens: ExternalChatgptTokens): JsonObject = buildJsonObject {
            put("type", "chatgptAuthTokens")
            put("accessToken", tokens.accessToken)
            put("chatgptAccountId", tokens.accountId)
            tokens.planType?.let { put("chatgptPlanType", it) }
        }

        internal fun tokenResult(tokens: ExternalChatgptTokens): JsonObject = buildJsonObject {
            put("accessToken", tokens.accessToken)
            put("chatgptAccountId", tokens.accountId)
            tokens.planType?.let { put("chatgptPlanType", it) }
        }

        internal fun threadParams(cwd: String, model: String?): JsonObject = buildJsonObject {
            put("cwd", cwd)
            put("approvalPolicy", "never")
            put("sandbox", "read-only")
            put("ephemeral", true)
            put("developerInstructions", INSTRUCTIONS)
            if (!model.isNullOrBlank()) put("model", model)
        }

        internal fun turnParams(threadId: String, effort: String?): JsonObject = buildJsonObject {
            put("threadId", threadId)
            put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", MESSAGE) }) })
            if (effort != null) put("effort", effort)
        }

        /**
         * The model the account would use by default, and its lowest effort.
         * The default rather than the cheapest: a smaller model can carry a
         * limit of its own, and a message there would not start the plan's
         * window. Null leaves both to the server.
         */
        internal fun defaultModel(list: JsonObject): Pair<String, String?>? {
            val models = (list["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val chosen = models.firstOrNull { (it["isDefault"] as? JsonPrimitive)?.booleanOrNull == true } ?: models.firstOrNull() ?: return null
            val id = chosen.string("model")?.ifBlank { null } ?: chosen.string("id")?.ifBlank { null } ?: return null
            val efforts = CodexEngine.parseModelCatalog(buildJsonObject { put("data", JsonArray(listOf(chosen))) })
                .firstOrNull()?.reasoningEfforts.orEmpty().map { it.value }
            return id to EFFORT_ORDER.firstOrNull { it in efforts }
        }

        private fun errorText(error: JsonObject): String =
            SecretRedactor.redact(error.string("message")?.ifBlank { null } ?: "Codex refused the request.").take(300)

        private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    }
}
