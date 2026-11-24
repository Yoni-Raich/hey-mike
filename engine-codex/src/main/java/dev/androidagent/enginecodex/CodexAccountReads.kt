/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.enginecodex

import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import dev.androidagent.core.connections.AccountConnector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Read-only access to the ChatGPT account's apps (Gmail, Calendar, ...) on the
 * phone's app-server. User chats run with `features.apps` off, so the model
 * never sees these tools directly; Mike's `account_read` tool calls them here,
 * one switched-on read-only tool at a time, on an ephemeral read-only thread.
 */
internal class CodexAccountReads(
    private val enabled: Boolean,
    private val connect: suspend () -> Unit,
    private val request: suspend (method: String, params: JsonObject) -> JsonObject,
) {
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var definitions: List<ToolDefinition> = emptyList()
    private val threadLock = Mutex()
    private var thread: String? = null

    fun allow(tools: List<String>) { allowed = tools.toSet() }

    /** Schemas of the switched-on read tools, as the last catalog listed them. */
    fun definitions(): List<ToolDefinition> = definitions.filter { it.name in allowed }

    /** A new app-server process has no threads; the next read opens one. */
    fun forgetThread() { thread = null }

    suspend fun connectors(): List<AccountConnector> {
        if (!enabled) return emptyList()
        connect()
        val catalog = mutableListOf<JsonObject>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = request("mcpServerStatus/list", buildJsonObject {
                put("limit", 100)
                cursor?.let { put("cursor", it) }
            })
            catalog += (result["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
            if (cursor == null) {
                val connectors = CodexAccountCatalog.connectors(catalog)
                val reads = connectors.flatMap { it.readTools }.toSet()
                definitions = catalog.filter { it.string("name") == CodexAccountCatalog.SERVER }.flatMap {
                    (it["tools"] as? JsonObject).orEmpty().mapNotNull { (name, raw) ->
                        val tool = raw as? JsonObject ?: return@mapNotNull null
                        val schema = tool["inputSchema"] as? JsonObject ?: return@mapNotNull null
                        if (name in reads) ToolDefinition(name, tool.string("description"), schema) else null
                    }
                }
                return connectors
            }
        }
        error("Account catalog pagination limit reached")
    }

    suspend fun read(tool: String, arguments: JsonObject, permitted: () -> Boolean): ToolResult {
        if (!enabled || tool !in allowed || !permitted()) return ToolResult("Account tool is off.", success = false)
        // Recheck the service annotation and the owner's switch for every call.
        val reads = connectors().flatMap { it.readTools }.toSet()
        if (tool !in reads || tool !in allowed || !permitted()) return ToolResult("Not an enabled read-only account tool.", success = false)
        val threadId = threadLock.withLock {
            thread ?: request("thread/start", buildJsonObject {
                put("ephemeral", true); put("sandbox", "read-only"); put("approvalPolicy", "never")
                put("config", buildJsonObject { put("features.apps", true) })
            })["thread"]!!.jsonObject.string("id").also { thread = it }
        }
        if (!permitted() || tool !in allowed) return ToolResult("Account read stopped.", success = false)
        val result = request("mcpServer/tool/call", buildJsonObject {
            put("threadId", threadId)
            put("server", CodexAccountCatalog.SERVER); put("tool", tool); put("arguments", arguments)
        })
        if (!permitted() || tool !in allowed) return ToolResult("Account read stopped.", success = false)
        return ToolResult(result.toString().take(RESULT_MAX), success = result["isError"]?.jsonPrimitive?.booleanOrNull != true)
    }

    private companion object {
        const val MAX_PAGES = 20
        const val RESULT_MAX = 40_000
        fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
