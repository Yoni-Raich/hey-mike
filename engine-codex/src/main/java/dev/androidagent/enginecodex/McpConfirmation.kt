package dev.androidagent.enginecodex

import kotlinx.serialization.json.*

/** A yes/no card can answer a confirmation, but must never invent form values or proofs. */
internal fun isMcpConfirmation(params: JsonObject): Boolean {
    val mode = (params["mode"] as? JsonPrimitive)?.contentOrNull
    if (mode !in setOf("form", "openai/form", "openaiForm")) return false
    if (listOf("serverName", "threadId", "turnId", "message").any {
        (params[it] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
    }) return false
    val schema = params["requestedSchema"] as? JsonObject ?: return false
    if ((schema["type"] as? JsonPrimitive)?.contentOrNull != "object") return false
    val properties = schema["properties"] as? JsonObject ?: return false
    if (properties.isNotEmpty()) return false
    val required = schema["required"]
    if (required != null && required != JsonNull && (required !is JsonArray || required.isNotEmpty())) return false
    // An empty response must also satisfy every constraint. Unknown schema
    // keywords could require data our card cannot collect, so refuse them.
    return schema.keys.all { it in setOf("type", "properties", "required", "\$schema", "additionalProperties", "title", "description") }
}

internal fun mcpConfirmationResult(allow: Boolean) = buildJsonObject {
    put("action", if (allow) "accept" else "decline")
    put("content", if (allow) buildJsonObject {} else JsonNull)
}
