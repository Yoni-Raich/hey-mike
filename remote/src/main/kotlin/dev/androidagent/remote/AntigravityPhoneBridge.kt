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
        internal const val MAX_SCHEMA_BYTES = 11_500

        fun open(os: HostOs, link: SshLink, tools: List<ToolDefinition>, call: PhoneCall): AcpPhoneBridge {
            val exposed = compactTools(tools)
            val server = LoopbackMcpServer("mike", { exposed }, call)
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

        /**
         * Antigravity's ACP prompt drops oversized MCP tool schemas when their combined
         * size crosses its eager budget (~12.8 KB). Remove redundant descriptions first,
         * then tighten prose and deep sub-schemas while keeping every tool name,
         * top-level parameter name, type, enum and non-empty required list intact.
         */
        internal fun compactTools(tools: List<ToolDefinition>, maxTotalBytes: Int = MAX_SCHEMA_BYTES): List<ToolDefinition> {
            val deduplicated = tools.map { tool ->
                tool.copy(inputSchema = pruneTopSchema(tool.inputSchema))
            }
            if (estimatedBytes(deduplicated) <= maxTotalBytes) return deduplicated

            val compactDescriptions = deduplicated.map { tool ->
                tool.copy(
                    description = summarize(tool.description, 110),
                    inputSchema = stripNestedDescriptions(tool.inputSchema),
                )
            }
            if (estimatedBytes(compactDescriptions) <= maxTotalBytes) return compactDescriptions

            val flattened = compactDescriptions.map { tool ->
                tool.copy(inputSchema = flattenNestedObjects(tool.inputSchema))
            }
            if (estimatedBytes(flattened) <= maxTotalBytes) return flattened

            return flattened.map { tool ->
                tool.copy(description = summarize(tool.description, 56))
            }
        }

        private fun estimatedBytes(tools: List<ToolDefinition>): Int =
            tools.sumOf { it.name.length + it.description.length + it.inputSchema.toString().length + 48 }

        private fun pruneTopSchema(schema: JsonObject): JsonObject = buildJsonObject {
            for ((key, value) in schema) {
                if (key == "description") continue
                if (key == "required" && value is JsonArray && value.isEmpty()) continue
                if (key == "additionalProperties" && (value as? JsonPrimitive)?.booleanOrNull == false) continue
                put(key, value)
            }
        }

        private fun stripNestedDescriptions(element: JsonElement): JsonObject =
            stripDescriptionsRecursive(element) as? JsonObject ?: JsonObject(emptyMap())

        private fun stripDescriptionsRecursive(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> buildJsonObject {
                for ((key, value) in element) {
                    if (key == "description") continue
                    if (key == "required" && value is JsonArray && value.isEmpty()) continue
                    if (key == "additionalProperties" && (value as? JsonPrimitive)?.booleanOrNull == false) continue
                    put(key, stripDescriptionsRecursive(value))
                }
            }
            is JsonArray -> buildJsonArray { element.forEach { add(stripDescriptionsRecursive(it)) } }
            else -> element
        }

        private fun flattenNestedObjects(schema: JsonObject): JsonObject = buildJsonObject {
            for ((key, value) in schema) {
                if (key == "properties" && value is JsonObject) {
                    put("properties", buildJsonObject {
                        for ((propName, propSchema) in value) {
                            put(propName, simplifyProperty(propSchema))
                        }
                    })
                } else {
                    put(key, value)
                }
            }
        }

        private fun simplifyProperty(prop: JsonElement): JsonElement {
            val obj = prop as? JsonObject ?: return prop
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            return when {
                type == "object" || obj.containsKey("properties") -> buildJsonObject { put("type", "object") }
                type == "array" || obj.containsKey("items") -> buildJsonObject {
                    put("type", "array")
                    val itemType = (obj["items"] as? JsonObject)?.get("type") as? JsonPrimitive
                    if (itemType != null) put("items", buildJsonObject { put("type", itemType.content) })
                }
                else -> buildJsonObject {
                    for ((k, v) in obj) {
                        if (k == "minItems" || k == "maxItems" || k == "additionalProperties") continue
                        put(k, v)
                    }
                }
            }
        }

        private fun summarize(text: String, maxChars: Int): String {
            val singleLine = text.replace(Regex("\\s+"), " ").trim()
            if (singleLine.length <= maxChars) return singleLine
            val sentenceEnd = singleLine.indexOf(". ")
            if (sentenceEnd in 16..maxChars) return singleLine.substring(0, sentenceEnd + 1)
            return singleLine.take(maxChars - 1).trimEnd(' ', ',', ';', ':') + "…"
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
