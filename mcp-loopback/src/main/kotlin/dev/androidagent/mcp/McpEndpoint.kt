package dev.androidagent.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where a started [LoopbackMcpServer] listens, and the bearer token every request must carry. */
data class McpEndpoint(val url: String, val bearerToken: String) {
    /** JSON for `claude --mcp-config`: one HTTP server named [serverName] that sends the Authorization header. */
    fun claudeMcpConfig(serverName: String): String = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(serverName) {
                put("type", "http")
                put("url", url)
                putJsonObject("headers") { put("Authorization", "Bearer $bearerToken") }
            }
        }
    }.toString()

    // Keep the token out of logs and crash reports.
    override fun toString(): String = "McpEndpoint(url=$url, bearerToken=<redacted>)"
}
