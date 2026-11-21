package dev.androidagent.remote

import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import dev.androidagent.mcp.LoopbackMcpServer
import java.io.Closeable
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.*

internal interface AcpPhoneBridge : Closeable {
    val serverConfig: JsonObject
}

internal typealias PhoneCall = suspend (String, JsonObject) -> ToolResult

/** ACP agents must support stdio MCP. The small host process forwards JSON-RPC to
 * Mike's authenticated MCP server through this turn's private SSH connection. */
internal class AntigravityPhoneBridge private constructor(
    private val mcp: LoopbackMcpServer,
    private val link: SshLink,
    override val serverConfig: JsonObject,
) : AcpPhoneBridge {
    override fun close() {
        try { mcp.stop() } finally { link.close() }
    }

    companion object {
        fun open(os: HostOs, link: SshLink, tools: List<ToolDefinition>, call: PhoneCall): AcpPhoneBridge {
            val server = LoopbackMcpServer("mike", { tools }, call)
            try {
                val endpoint = server.start()
                val port = link.forwardPhoneLoopback(URI(endpoint.url).port)
                return AntigravityPhoneBridge(server, link, config(os, port, endpoint.bearerToken))
            } catch (e: Throwable) {
                server.stop()
                link.close()
                throw e
            }
        }

        internal fun config(os: HostOs, port: Int, token: String): JsonObject {
            val windows = os == HostOs.WINDOWS
            val resource = if (windows) "mcp-stdio.ps1" else "mcp-stdio.py"
            val script = checkNotNull(AntigravityPhoneBridge::class.java.getResourceAsStream("/antigravity/$resource"))
                .bufferedReader().use { it.readText() }
            val args = if (windows) listOf("-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
            else {
                val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
                listOf("-I", "-u", "-c", "exec(__import__('base64').b64decode('$encoded'))")
            }
            return buildJsonObject {
                put("name", "mike")
                put("command", if (windows) "powershell.exe" else "python3")
                put("args", buildJsonArray { args.forEach { add(it) } })
                put("env", buildJsonArray {
                    add(buildJsonObject { put("name", "MIKE_MCP_PORT"); put("value", port.toString()) })
                    add(buildJsonObject { put("name", "MIKE_MCP_TOKEN"); put("value", token) })
                })
            }
        }
    }
}
