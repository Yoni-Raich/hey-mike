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

package dev.androidagent.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** DevTools answered a command with an error. Nothing about the page is implied. */
class CdpException(val method: String, val code: Int, override val message: String) : IOException(message)

/** One page a DevTools server can attach to. */
data class DevToolsPage(val id: String, val url: String, val title: String)

/**
 * A Chrome DevTools Protocol session with one page.
 *
 * Commands are numbered and answered out of order; events arrive between them.
 * One reader thread turns the socket into completed commands and delivered
 * events, so a caller only ever suspends.
 */
class CdpClient private constructor(private val socket: DevToolsSocket) : Closeable {
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, Pair<String, CompletableDeferred<JsonObject>>>()
    private val listeners = CopyOnWriteArrayList<(String, JsonObject) -> Unit>()

    @Volatile private var closed = false

    private val reader = thread(name = "devtools-reader", isDaemon = true) {
        val failure = runCatching {
            while (true) {
                val text = socket.receive() ?: break
                val message = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val id = message["id"]?.jsonPrimitive?.intOrNull
                if (id == null) {
                    val method = message["method"]?.jsonPrimitive?.contentOrNull ?: continue
                    val params = message["params"] as? JsonObject ?: EMPTY
                    // A listener that throws must not take the reader with it.
                    for (listener in listeners) runCatching { listener(method, params) }
                    continue
                }
                val (method, waiter) = pending.remove(id) ?: continue
                val error = message["error"] as? JsonObject
                if (error != null) {
                    waiter.completeExceptionally(
                        CdpException(
                            method,
                            error["code"]?.jsonPrimitive?.intOrNull ?: 0,
                            error["message"]?.jsonPrimitive?.contentOrNull ?: "DevTools error",
                        ),
                    )
                } else {
                    waiter.complete(message["result"] as? JsonObject ?: EMPTY)
                }
            }
        }.exceptionOrNull()
        closed = true
        val reason = IOException("The browser connection closed", failure)
        pending.values.forEach { it.second.completeExceptionally(reason) }
        pending.clear()
    }

    val isOpen: Boolean get() = !closed

    /** Run one command. A timeout leaves the command's fate unknown, and says so. */
    suspend fun call(method: String, params: JsonObject = EMPTY, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonObject {
        coroutineContext.ensureActive()
        if (closed) throw IOException("The browser connection is closed")
        val id = nextId.incrementAndGet()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = method to waiter
        // The reader may have failed every waiter between the check above and
        // this registration; this one would then wait out its whole timeout.
        if (closed) {
            pending.remove(id)
            throw IOException("The browser connection is closed")
        }
        val frame = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        try {
            withContext(Dispatchers.IO) { coroutineContext.ensureActive(); socket.send(frame) }
            return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: run {
                throw CdpException(method, TIMEOUT_CODE, "$method did not answer within ${timeoutMs}ms")
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Hear every event until the returned handle is closed. */
    fun onEvent(listener: (method: String, params: JsonObject) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        reader.interrupt()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val TIMEOUT_CODE = -1

        private val JSON = Json { ignoreUnknownKeys = true }
        private val EMPTY = JsonObject(emptyMap())

        /** The pages this DevTools server can attach to, in its own order. */
        fun pages(dialer: DevToolsDialer): List<DevToolsPage> =
            (JSON.parseToJsonElement(DevToolsHttp.get(dialer, "/json/list")) as? JsonArray ?: JsonArray(emptyList()))
                .jsonArray
                .mapNotNull { it as? JsonObject }
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "page" }
                .mapNotNull { page ->
                    DevToolsPage(
                        id = page["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        url = page["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        title = page["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }

        /** Attach to one page. The stream belongs to the returned client. */
        fun attach(dialer: DevToolsDialer, pageId: String): CdpClient {
            val socket = DevToolsSocket(dialer.dial())
            try {
                socket.handshake("/devtools/page/$pageId")
            } catch (failure: Throwable) {
                socket.close()
                throw failure
            }
            return CdpClient(socket)
        }
    }
}
