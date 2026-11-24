/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core.connections

import dev.androidagent.core.EngineKind
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import kotlinx.serialization.json.JsonObject

/** Connections already held by the signed-in AI account. Mike stores no connector token. */
data class AccountConnector(
    val engine: EngineKind,
    val server: String,
    val state: String,
    val readTools: List<String>,
    val writeTools: Int,
) {
    val connected: Boolean get() = state == "connected"
    val name: String get() = server.substringAfter(' ', server).trim()
    val id: String get() = "account:${engine.name.lowercase()}:$server"
}

/** The provider discovers metadata; only explicit settings grants enable its read tools. */
interface AccountConnectorSource {
    val engine: EngineKind
    suspend fun accountConnectors(): List<AccountConnector>
    fun allowAccountTools(tools: List<String>)
    fun accountReadDefinitions(): List<ToolDefinition> = emptyList()
    suspend fun readAccountTool(tool: String, arguments: JsonObject, permitted: () -> Boolean): ToolResult =
        ToolResult("Account reads are unavailable.", success = false)
}
