package dev.androidagent.core

import kotlinx.serialization.json.*

/** Small, non-actionable handoff for Codex. Jev keeps the complete observation internally. */
internal object JevResultProjection {
    private const val MAX_NODES = 16
    private const val MAX_HISTORY = 12

    fun observation(value: JevObservation): JsonObject {
        val source = value.json
        val activePackage = source["activePackage"]?.jsonPrimitive?.contentOrNull
        val nodes = value.semantics.nodes
        val selected = nodes.filter { node ->
            listOf("text", "contentDescription", "hintText", "checked", "range", "editable", "selected")
                .any { it in node }
        }.sortedWith(compareBy<JsonObject> { if (it["package"]?.jsonPrimitive?.contentOrNull == activePackage) 0 else 1 }
            .thenBy { if (listOf("checked", "range", "editable", "selected").any { key -> key in it }) 0 else 1 })
            .take(MAX_NODES)
        return buildJsonObject {
            put("summaryOnly", true)
            put("observationId", value.observationId)
            source["activePackage"]?.let { put("activePackage", it) }
            source["screenDigest"]?.let { put("screenDigest", it) }
            source["source"]?.let { put("source", it) }
            put("totalNodes", nodes.size)
            put("shownNodes", selected.size)
            put("partial", selected.size < nodes.size || source["treeTruncated"]?.jsonPrimitive?.booleanOrNull == true)
            put("nodes", buildJsonArray {
                selected.forEach { node -> add(buildJsonObject {
                    put("label", value.semantics.label(node).take(120))
                    node["class"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('.')?.take(48)?.let { put("class", it) }
                    listOf("checked", "selected", "editable").forEach { key -> node[key]?.let { put(key, it) } }
                    node["range"]?.let { put("range", it) }
                }) }
            })
        }
    }

    fun history(entries: List<JevHistoryEntry>): JsonArray = buildJsonArray {
        entries.takeLast(MAX_HISTORY).forEach { entry -> add(buildJsonObject {
            put("operation", entry.operation)
            put("label", entry.label.take(120))
            put("screenChanged", entry.screenChanged)
            if (entry.failed) put("refused", true)
            entry.actionMs?.let { put("actionMs", it) }
            entry.observeMs?.let { put("observeMs", it) }
            entry.outcome?.let { put("result", it.take(160)) }
        }) }
    }
}
