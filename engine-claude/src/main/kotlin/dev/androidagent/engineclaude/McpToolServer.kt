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

package dev.androidagent.engineclaude

import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * One MCP endpoint that serves the phone tools to one `claude` process.
 *
 * This is the seam to `:mcp-loopback`. The engine starts one server per chat
 * process, so every tool call is tied to the chat that made it: a server
 * shared by several processes could not tell a stale chat's call from the
 * running one. The app wires `LoopbackMcpServer` in with a few lines:
 *
 * ```
 * McpToolServerFactory { name, tools, call ->
 *     val server = LoopbackMcpServer(name, tools, call)
 *     object : McpToolServer {
 *         override fun start() = server.start().claudeMcpConfig(name)
 *         override fun stop() = server.stop()
 *     }
 * }
 * ```
 */
interface McpToolServer {
    /** Bind and serve. Returns the JSON for `claude --mcp-config`, bearer token included. */
    fun start(): String

    /** Close the socket. In-flight calls are cancelled. */
    fun stop()
}

/** Builds [McpToolServer]s. [call] suspends until the app answers the tool call. */
fun interface McpToolServerFactory {
    fun create(
        serverName: String,
        tools: () -> List<ToolDefinition>,
        call: suspend (name: String, arguments: JsonObject) -> ToolResult,
    ): McpToolServer
}
