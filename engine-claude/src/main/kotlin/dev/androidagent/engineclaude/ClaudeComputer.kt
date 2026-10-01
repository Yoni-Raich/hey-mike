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
import dev.androidagent.engineclaude.ClaudeProtocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One of the user's computers, for an engine that runs that computer's own
 * Claude Code instead of the phone's.
 *
 * The computer's `claude` is the one the user installed and signed in to
 * there. Mike only starts it, over the connection the caller owns, and never
 * copies a sign-in to it or from it. It runs as that user, with their own
 * settings, skills and MCP servers, in the chat's project folder.
 *
 * Two things differ from the phone. The phone tools cannot be reached over a
 * loopback port from another machine, so they travel on the process's own
 * stdin and stdout as an MCP server of the Agent SDK's `sdk` type (see
 * [StdioMcp]). And the computer's own tools (shell, file edits) ask the user
 * before they run, on the phone, unless the computer was given full access.
 */
interface ClaudeComputer {
    /** Added to Claude Code's own system prompt: who is talking, from where, and the rules that still hold. */
    val instructions: String

    /** `--permission-mode` for the computer's own tools. */
    val permissionMode: String

    /** Ask the user on the phone before a tool that needs permission. False only with full access. */
    val askUser: Boolean

    /** What to tell the user while `claude` cannot be started there. */
    val notReady: String

    /**
     * Start one chat's `claude` in [cwd] on the computer with [env].
     * [files] are small texts it reads, by file name; [args] is given where
     * each one was put on the computer and returns the arguments.
     */
    suspend fun startChat(
        chatId: String,
        cwd: String,
        files: Map<String, String>,
        env: Map<String, String>,
        args: (paths: Map<String, String>) -> List<String>,
    ): Process
}

/**
 * The phone tools as an MCP server carried inside the `claude` control
 * protocol, with no socket.
 *
 * This is how the Agent SDK serves in-process tools: the server is declared
 * with `"type":"sdk"` in `--mcp-config` and named in `initialize`
 * (`sdkMcpServers`); the CLI then sends each MCP message as a
 * `control_request` of subtype `mcp_message` and reads the answer from
 * `control_response.response.mcp_response`. Recorded against Claude Code
 * 2.1.286: `initialize`, `notifications/initialized`, `tools/list` and
 * `tools/call` arrive this way, and a notification is answered with an empty
 * result under id 0.
 */
internal object StdioMcp {
    const val PROTOCOL_VERSION = "2025-06-18"
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602

    /** `--mcp-config` text that declares the server; it holds no address and no secret. */
    fun config(serverName: String): String = buildJsonObject {
        put("mcpServers", buildJsonObject {
            put(serverName, buildJsonObject { put("type", "sdk"); put("name", serverName) })
        })
    }.toString()

    /** The extra fields of the `initialize` control request that announce the server. */
    fun initializeExtra(serverName: String): JsonObject = buildJsonObject {
        put("sdkMcpServers", buildJsonArray { add(serverName) })
    }

    /** The MCP message of an `mcp_message` control request for [serverName], or null when it is for another server. */
    fun messageOf(request: JsonObject, serverName: String): JsonObject? =
        (request["message"] as? JsonObject)?.takeIf { request.string("server_name") == serverName }

    /** The control response body for one MCP message. [call] suspends until the app answers the tool call. */
    suspend fun answer(
        serverName: String,
        message: JsonObject,
        tools: List<ToolDefinition>,
        call: suspend (name: String, arguments: JsonObject) -> ToolResult,
    ): JsonObject {
        val id = message["id"] ?: JsonPrimitive(0)
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val reply = when (message.string("method")) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", params.string("protocolVersion").ifBlank { PROTOCOL_VERSION })
                put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                put("serverInfo", buildJsonObject { put("name", serverName); put("version", "1") })
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        })
                    }
                })
            })
            "tools/call" -> {
                val name = params.string("name")
                if (name.isBlank()) error(id, INVALID_PARAMS, "tools/call needs a tool name")
                else result(id, toolResult(call(name, params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))))
            }
            else -> if (message.string("method").startsWith("notifications/")) result(id, JsonObject(emptyMap()))
            else error(id, METHOD_NOT_FOUND, "Method not found")
        }
        return buildJsonObject { put("mcp_response", reply) }
    }

    private fun toolResult(result: ToolResult): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", result.text) })
            result.imageBase64?.let { image ->
                add(buildJsonObject { put("type", "image"); put("data", image); put("mimeType", "image/png") })
            }
        })
        put("isError", !result.success)
    }

    private fun result(id: JsonElement, value: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", value)
    }

    private fun error(id: JsonElement, code: Int, text: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", text) })
    }
}

/**
 * A `can_use_tool` control request from the computer's Claude Code, as the
 * approval the phone shows. Shape recorded against Claude Code 2.1.286:
 * `tool_name`, `input`, `description`, `decision_reason`, `tool_use_id`.
 */
internal object ClaudePermission {
    /** The approval's method name, so the card knows how to word it. */
    const val METHOD = "claude/can_use_tool"

    private val FILE_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
    private val SHELL_TOOLS = setOf("Bash", "PowerShell")

    /** What the card shows: the tool, the command or file it names, why it is asked, and where. */
    fun details(request: JsonObject, cwd: String): JsonObject {
        val tool = request.string("tool_name")
        val input = request["input"] as? JsonObject ?: JsonObject(emptyMap())
        fun field(vararg names: String) = names.firstNotNullOfOrNull { (input[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        return buildJsonObject {
            put("tool", request.string("display_name").ifBlank { tool })
            put("kind", when (tool) { in SHELL_TOOLS -> "command"; in FILE_TOOLS -> "file"; else -> "tool" })
            when (tool) {
                in SHELL_TOOLS -> field("command")?.let { put("command", it.take(MAX_SHOWN)) }
                in FILE_TOOLS -> field("file_path", "notebook_path", "path")?.let { put("path", it) }
                else -> if (input.isNotEmpty()) put("input", input.toString().take(MAX_SHOWN))
            }
            (field("description") ?: request.string("description").takeIf { it.isNotBlank() })?.let { put("reason", it.take(MAX_SHOWN)) }
            put("cwd", cwd)
        }
    }

    /** `updatedInput` is required with `allow`; the input goes back as it came. */
    fun allow(request: JsonObject): JsonObject = buildJsonObject {
        put("behavior", "allow")
        put("updatedInput", request["input"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    fun deny(message: String): JsonObject = buildJsonObject {
        put("behavior", "deny")
        put("message", message)
    }

    private const val MAX_SHOWN = 2_000
}
