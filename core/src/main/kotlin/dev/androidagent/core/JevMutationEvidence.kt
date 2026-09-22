package dev.androidagent.core

import kotlinx.serialization.json.*

/** Local checks for exact writes. An accepted dispatch is not a verified value. */
internal class JevMutationEvidence {
    private data class Write(val identity: JsonObject, val expected: String)
    private val pendingText = linkedMapOf<String, Write>()

    fun recordText(before: JsonObject, nodeId: String, expected: String, dispatch: ToolDispatch) {
        val node = before["nodes"]?.jsonArray.orEmpty().map { it.jsonObject }
            .singleOrNull { it["nodeId"]?.jsonPrimitive?.contentOrNull == nodeId } ?: return
        val identity = identity(node)
        val key = identity.toString()
        if (dispatch == ToolDispatch.VERIFIED) pendingText.remove(key)
        else pendingText[key] = Write(identity, expected)
    }

    fun reconcile(observation: JsonObject) {
        val nodes = observation["nodes"]?.jsonArray.orEmpty().map { it.jsonObject }
        pendingText.entries.removeAll { (_, write) ->
            // Node IDs are snapshot-local. Match a unique field identity instead.
            val field = nodes.singleOrNull { identity(it) == write.identity }
            field?.get("text")?.jsonPrimitive?.contentOrNull == write.expected
        }
    }

    val resolved: Boolean get() = pendingText.isEmpty()

    fun state(): JsonArray = buildJsonArray {
        pendingText.values.forEach { write -> add(buildJsonObject {
            put("field", write.identity)
            put("expectedPreview", write.expected.take(200))
            put("expectedLength", write.expected.length)
            put("previewTruncated", write.expected.length > 200)
            put("status", "exact text not yet verified; inspect or replace before submission/completion")
        }) }
    }

    private fun identity(node: JsonObject): JsonObject = buildJsonObject {
        // Resource identity survives keyboard resizing. Duplicate IDs remain
        // unverified because reconcile requires a unique match.
        val keys = if (node["resourceId"] != null) listOf("package", "resourceId", "class", "editable")
            else listOf("package", "class", "bounds", "contentDescription", "editable")
        keys.forEach { key ->
            node[key]?.let { put(key, it) }
        }
    }
}
