/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.core

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Chat can prepare a draft. Only the user's review screen activates it. */
class ResponsibilityToolGateway(
    private val service: ResponsibilityService,
    private val onChanged: () -> Unit = {},
) : DeviceToolGateway {
    @Volatile private var revoked = true
    override val definitions = listOf(ToolDefinition("responsibility",
        "Persistent ownership of existing automation rules. Create prepares a draft with id, title, goal and ruleIds. " +
            "The user reviews and activates it in Responsibilities. A draft holds its rules without running them. " +
            "No new permissions are granted. Modes: create, list, describe, activity, pause. " +
            "Use exact ids. Notes are explicit user memory and can only be edited in the app. " +
            "Activity reports rule actions; a queued model turn is dispatched, not proof the task finished.",
        buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("mode", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("create", "list", "describe", "activity", "pause").map(::JsonPrimitive))) })
                listOf("id", "title", "goal").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
                put("ruleIds", buildJsonObject {
                    put("type", "array"); put("minItems", 1); put("maxItems", 16)
                    put("items", buildJsonObject { put("type", "string") })
                })
            })
        }))
    override fun beginRun(runId: String, workspace: File) { revoked = false }
    override fun revoke() { revoked = true }
    override suspend fun cancel() {}
    override fun needsControl(name: String) = false
    override fun deviceBackendLive() = false
    override fun statusLine(): String = if (service.loadError != null) "Responsibilities: storage unavailable (automations held)"
        else "Responsibilities: ${service.snapshot.value.responsibilities.count { it.state == ResponsibilityState.ACTIVE }} active"

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        check(!revoked) { "Run stopped." }
        if (name != "responsibility") throw ToolNotServiceable("unsupported", "Unknown responsibility tool.")
        service.loadError?.let { return failed(it) }
        return try {
            require(arguments.keys.all { it in setOf("mode", "id", "title", "goal", "ruleIds") }) { "Unknown argument." }
            fun text(key: String): String {
                val value = arguments[key] as? JsonPrimitive
                require(value?.isString == true) { "'$key' must be a string." }
                return value.content
            }
            val mode = if ("mode" in arguments) text("mode") else "list"
            val output = when (mode) {
                "create" -> {
                    val ids = arguments["ruleIds"] as? JsonArray ?: error("Choose existing ruleIds.")
                    val item = service.create(text("id"), text("title"), text("goal"), ids.map {
                        require(it is JsonPrimitive && it.isString) { "Each rule id must be a string." }; it.content
                    })
                    onChanged()
                    Json.encodeToString(item)
                }
                "list" -> Json.encodeToString(service.snapshot.value.responsibilities)
                "describe" -> Json.encodeToString(service.get(text("id")) ?: error("Unknown responsibility id."))
                "activity" -> {
                    val id = text("id")
                    require(service.get(id) != null) { "Unknown responsibility id." }
                    Json.encodeToString(service.snapshot.value.activity.filter { it.responsibilityId == id }.takeLast(50))
                }
                "pause" -> Json.encodeToString(service.pause(text("id"))).also { onChanged() }
                else -> error("Use create, list, describe, activity or pause.")
            }
            ToolResult(buildJsonObject { put("ok", true); put("result", Json.parseToJsonElement(output)) }.toString())
        } catch (failure: IllegalArgumentException) {
            failed(failure.message ?: "Invalid responsibility.")
        } catch (failure: IllegalStateException) {
            failed(failure.message ?: "Responsibility unavailable.")
        }
    }

    private fun failed(message: String) = ToolResult(
        buildJsonObject { put("ok", false); put("errorType", "responsibility_invalid"); put("message", message) }.toString(),
        success = false,
    )
}
