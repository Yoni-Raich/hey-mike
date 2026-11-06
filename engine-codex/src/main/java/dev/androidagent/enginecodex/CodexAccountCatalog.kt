/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.enginecodex

import dev.androidagent.core.EngineKind
import dev.androidagent.core.proactive.AccountConnector
import kotlinx.serialization.json.*

internal object CodexAccountCatalog {
    fun connectors(servers: List<JsonObject>): List<AccountConnector> {
        val tools = servers.filter { it["name"]?.jsonPrimitive?.contentOrNull == "codex_apps" }
            .flatMap { (it["tools"] as? JsonObject).orEmpty().entries }
        return tools.filter { (_, v) -> (v as? JsonObject)?.get("_meta") is JsonObject }
            .groupBy { (_, v) -> v.jsonObject["_meta"]!!.jsonObject["connector_name"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .filterKeys { it.isNotBlank() }
            .map { (name, entries) ->
                val reads = entries.filter { (_, v) ->
                    val a = v.jsonObject["annotations"] as? JsonObject
                    a?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull == true &&
                        a["destructiveHint"]?.jsonPrimitive?.booleanOrNull != true &&
                        a["openWorldHint"]?.jsonPrimitive?.booleanOrNull != true
                }.map { it.key }.sorted()
                AccountConnector(EngineKind.CODEX, "ChatGPT $name", "connected", reads, entries.size - reads.size)
            }
    }
}
