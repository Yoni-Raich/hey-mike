// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect.example

import dev.heymike.connect.*
import kotlinx.serialization.json.*

class PreferencesConnection : MikeConnectService() {
    override fun MikeConnect.Builder.configure() {
        capability("get_settings") {
            title = "Read display settings"
            description = "Read the example app's saved display accent, blue or amber."
            access = Access.READ
            outputSchema = accentSchema()
            handleJson { buildJsonObject { put("accent", DisplaySettings(this@PreferencesConnection).accent()) } }
        }
        capability("set_accent") {
            title = "Change display accent"
            description = "Save blue or amber as the example app's display accent. Other app settings stay unchanged."
            summary = "Change the example app's display accent."
            access = Access.WRITE
            inputSchema = accentSchema()
            outputSchema = accentSchema()
            handleJson { args ->
                val saved = DisplaySettings(this@PreferencesConnection).setAccent(args.getValue("accent").jsonPrimitive.content)
                buildJsonObject { put("accent", saved) }
            }
        }
    }
}

private fun accentSchema() = buildJsonObject {
    put("type", "object"); put("additionalProperties", false)
    put("properties", buildJsonObject { put("accent", buildJsonObject {
        put("type", "string"); put("enum", JsonArray(listOf("blue", "amber").map(::JsonPrimitive)))
    }) })
    put("required", JsonArray(listOf(JsonPrimitive("accent"))))
}
