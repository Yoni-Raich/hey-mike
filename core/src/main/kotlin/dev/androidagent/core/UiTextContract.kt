package dev.androidagent.core

import kotlinx.serialization.json.*

/** Full text stays local; model-facing observations contain a redacted preview. */
class UiTextValue(private val value: String) {
    fun matches(expected: String): Boolean = value == expected
    override fun toString(): String = "<local text value>"
}

enum class TextEditMode {
    INSERT, REPLACE;
    companion object {
        fun from(arguments: JsonObject): TextEditMode = when (arguments["mode"]?.jsonPrimitive?.contentOrNull ?: "replace") {
            "insert" -> INSERT
            "replace" -> REPLACE
            else -> throw IllegalArgumentException("mode must be insert or replace")
        }
    }
}

object UiTextContract {
    const val MAX_EDIT_CHARS = 4_000
    val editDefinition = ToolDefinition("type_text",
        "Edit an observed or focused field. mode=replace (default) replaces the whole value; mode=insert replaces its current selection. " +
            "ADB replacement requires the configured IME. Submit requires local verification.", buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("maxLength", MAX_EDIT_CHARS) })
                put("mode", buildJsonObject {
                    put("type", "string"); put("enum", JsonArray(listOf("insert", "replace").map(::JsonPrimitive)))
                    put("default", "replace")
                })
                put("submit", buildJsonObject { put("type", "boolean") })
                listOf("nodeId", "observationId").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
                listOf("x", "y").forEach { key -> put(key, buildJsonObject { put("type", "integer"); put("minimum", 0) }) }
            })
            put("required", JsonArray(listOf(JsonPrimitive("text"))))
        })
    val verifyDefinition = ToolDefinition("verify_text", "Compare a current observed field with exact expected text locally. Returns only whether it matches; never returns field contents.", buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", buildJsonObject {
            listOf("nodeId", "observationId", "text").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        })
        put("required", JsonArray(listOf("nodeId", "observationId", "text").map(::JsonPrimitive)))
    })
    fun verification(matches: Boolean): ToolResult = ToolResult(buildJsonObject {
        put("matches", matches)
    }.toString(), dispatch = ToolDispatch.NOT_DISPATCHED)

    fun rejected(reason: String, message: String): ToolResult = ToolResult(buildJsonObject {
        put("textRejection", reason); put("message", message)
    }.toString(), success = false, dispatch = ToolDispatch.NOT_DISPATCHED)

    fun isCapabilityRejection(result: String?): Boolean = result != null && runCatching {
        Json.parseToJsonElement(result).jsonObject["textRejection"]?.jsonPrimitive?.contentOrNull in setOf("not_editable", "set_text_rejected")
    }.getOrDefault(false)
}
