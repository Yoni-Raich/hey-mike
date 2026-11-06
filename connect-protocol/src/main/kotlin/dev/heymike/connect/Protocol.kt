// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

object ConnectProtocol {
    const val VERSION = "2026-07-28"
    const val SDK_VERSION = "1.0.0"
    const val ACTION = "dev.heymike.connect.action.CONNECT"
    const val BINDING = 1
    const val MAX_JSON_BYTES = 262_144
    const val MAX_RESULT_CHARS = 20_000
    const val PREFIX = "dev.heymike/"
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    fun meta(origin: String = "chat", requestId: String? = null, approved: Boolean = false) = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", VERSION)
        put("io.modelcontextprotocol/clientInfo", buildJsonObject { put("name", "Hey Mike"); put("version", SDK_VERSION) })
        put("io.modelcontextprotocol/clientCapabilities", buildJsonObject { put("elicitation", buildJsonObject { put("form", buildJsonObject {}) }) })
        put(PREFIX + "origin", origin)
        requestId?.let { put(PREFIX + "requestId", it); put("progressToken", it) }
        put(PREFIX + "userApproved", approved)
    }
    fun request(id: String, method: String, params: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
    }
    fun response(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    }
    fun rpcError(id: JsonElement?, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: JsonNull)
        put("error", buildJsonObject { put("code", code); put("message", message.take(300)) })
    }
    fun failure(code: String, message: String, mayHaveRun: Boolean = false) = buildJsonObject {
        put("resultType", "complete"); put("isError", true)
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", message.take(300)) }) })
        put("_meta", buildJsonObject { put(PREFIX + "code", code); put(PREFIX + "mayHaveRun", mayHaveRun) })
    }
    fun complete(data: JsonElement = buildJsonObject {}, text: String? = null) = buildJsonObject {
        put("resultType", "complete"); put("structuredContent", data)
        put("content", buildJsonArray { text?.let { add(buildJsonObject { put("type", "text"); put("text", it.take(MAX_RESULT_CHARS)) }) } })
    }
    fun parse(body: String): JsonObject {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Message too large" }
        // Bound before parsing, including hostile nesting that would overflow the parser.
        var depth = 0; var quoted = false; var escaped = false
        for (c in body) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 32) { "Message too deep" } }; '}', ']' -> depth-- }
        }
        return json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("Expected JSON object")
    }
    fun fingerprint(value: JsonElement): String {
        fun canonical(v: JsonElement): JsonElement = when (v) {
            is JsonObject -> JsonObject(v.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(v.map(::canonical))
            else -> v
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Serializable enum class Access { READ, WRITE, SENSITIVE }
@Serializable enum class Ui { NONE, MAY, ALWAYS }
@Serializable data class Capability(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val access: Access = Access.READ,
    val ui: Ui = Ui.NONE,
    val summary: String = title,
    val timeoutMs: Long = 15_000,
) {
    fun validate() {
        require(name.matches(Regex("[a-z][a-z0-9_]{1,47}"))) { "Invalid capability name" }
        require(title.isNotBlank() && title.length <= 60 && description.isNotBlank() && description.length <= 1_000)
        require(summary.length <= 240 && timeoutMs in 1_000..60_000)
        Schema.check(inputSchema); outputSchema?.let(Schema::check)
    }
    fun wire() = buildJsonObject {
        put("name", name); put("title", title); put("description", description); put("inputSchema", inputSchema)
        outputSchema?.let { put("outputSchema", it) }
        put("annotations", buildJsonObject { put("readOnlyHint", access == Access.READ); put("destructiveHint", access == Access.SENSITIVE) })
        put("_meta", buildJsonObject {
            put(ConnectProtocol.PREFIX + "access", access.name.lowercase())
            put(ConnectProtocol.PREFIX + "ui", ui.name.lowercase())
            put(ConnectProtocol.PREFIX + "summary", summary)
            put(ConnectProtocol.PREFIX + "timeoutMs", timeoutMs)
        })
    }
    companion object {
        fun fromWire(v: JsonObject): Capability {
            val meta = v["_meta"] as? JsonObject ?: buildJsonObject {}
            val hints = v["annotations"] as? JsonObject ?: buildJsonObject {}
            val declared = when (meta.string(ConnectProtocol.PREFIX + "access")) { "read" -> Access.READ; "write" -> Access.WRITE; "sensitive" -> Access.SENSITIVE; else -> null }
            val inferred = when { hints.bool("destructiveHint") -> Access.SENSITIVE; hints.bool("readOnlyHint") -> Access.READ; else -> Access.WRITE }
            return Capability(v.string("name"), v.string("title").ifBlank { v.string("name") }, v.string("description"),
                v.getValue("inputSchema").jsonObject, v["outputSchema"] as? JsonObject,
                listOfNotNull(declared, inferred).maxBy { it.ordinal },
                when (meta.string(ConnectProtocol.PREFIX + "ui")) { "may" -> Ui.MAY; "always" -> Ui.ALWAYS; "" , "none" -> Ui.NONE; else -> throw IllegalArgumentException("Invalid UI mode") },
                meta.string(ConnectProtocol.PREFIX + "summary").ifBlank { v.string("title") },
                (meta[ConnectProtocol.PREFIX + "timeoutMs"] as? JsonPrimitive)?.longOrNull ?: 15_000).also { it.validate() }
        }
    }
}

class ConnectException(val code: String, message: String) : RuntimeException(message)
fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
