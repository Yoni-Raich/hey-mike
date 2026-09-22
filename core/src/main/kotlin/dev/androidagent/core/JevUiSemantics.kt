package dev.androidagent.core

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Snapshot addresses are for dispatch only. Names and memory keys live here. */
internal class JevUiSemantics(val observation: JsonObject) {
    val nodes = observation["nodes"]?.jsonArray.orEmpty().map { it.jsonObject }
    private val byId = nodes.associateBy { it.string("nodeId") }
    private val children = nodes.groupBy { it.string("parentId") }
    private val names = mutableMapOf<JsonObject, String>()
    private val bases = mutableMapOf<JsonObject, JsonObject>()
    private val identities = mutableMapOf<JsonObject, String>()

    fun fieldLabel(node: JsonObject): String {
        node.string("contentDescription")?.let { return it }
        node.string("hintText")?.let { return it }
        val children = descendants(node)
        children.firstNotNullOfOrNull { it.string("contentDescription") }?.let { return it }
        children.firstNotNullOfOrNull { it.string("text")?.takeUnless { text -> text == node.string("text") } }
            ?.let { return it }
        return node.string("resourceId")?.substringAfterLast('/') ?: "Unnamed text field"
    }

    fun label(node: JsonObject): String = names.getOrPut(node) { if (node.bool("editable")) fieldLabel(node) else
        node.string("contentDescription") ?: node.string("text") ?: JevNodeNames.rowLabel(nodes, node)
        ?: node.string("resourceId") ?: node.string("class") ?: "control" }

    fun fieldState(node: JsonObject): JsonObject = buildJsonObject {
        put("identity", identity(node))
        put("label", fieldLabel(node))
        node["text"]?.let { put("currentValuePreview", it) }
        put("valueIsPreview", true)
        node["package"]?.let { put("package", it) }
        put("context", JsonArray(ancestors(node).map { JsonPrimitive(label(it).take(120)) }))
    }

    /** Layout movement, field contents and snapshot-local IDs do not rename a target. */
    fun identity(node: JsonObject): String = identities.getOrPut(node) {
        val base = baseIdentity(node)
        val context = ancestors(node).filterNot { it.bool("editable") }
            .mapNotNull { it.string("contentDescription") ?: it.string("text") }.distinct()
        // Ambiguous fields deliberately share a key. find() must not choose an
        // ordinal that may now belong to another field after a list reorder.
        digest(buildJsonObject {
            put("target", base)
            put("context", JsonArray(context.map(::JsonPrimitive)))
        }.toString())
    }

    fun find(key: String): JsonObject? = nodes.singleOrNull { identity(it) == key }

    /** UI freshness still uses the full backend digest. This is only progress memory. */
    val screenKey: String by lazy {
        val appNodes = nodes.filter { node ->
            node.string("windowType") != "keyboard" &&
                (node.string("windowType") != "system" || node.bool("clickable") || node.bool("scrollable") || node.bool("checkable"))
        }
        digest(buildJsonObject {
            val packages = appNodes.mapNotNull { it.string("package") }
            val activePackage = observation.string("activePackage")?.takeIf { it in packages }
                ?: packages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            activePackage?.let { put("app", it) }
            put("nodes", JsonArray(appNodes.map { node -> buildJsonObject {
                put("target", identity(node))
                listOf("text", "checked", "selected", "range", "enabled").forEach { key ->
                    node[key]?.let { put(key, it) }
                }
            } }.sortedBy { it.toString() }))
        }.toString())
    }

    fun actionKey(candidate: JevCandidate): String {
        val action = candidate.action
        val nodeId = candidate.nodeId ?: action?.arguments?.string("nodeId")
        val target = byId[nodeId]
        return digest(buildJsonObject {
            put("operation", candidate.operation)
            // Back with an open IME is a different navigation action from Back
            // on the same app screen after the IME has closed.
            if (candidate.operation == "BACK") put("keyboardVisible", nodes.any { it.string("windowType") == "keyboard" })
            target?.let { put("target", identity(it)) }
            candidate.memoryValue?.let { put("value", it) }
            action?.let { value ->
                put("tool", value.tool)
                put("arguments", JsonObject(value.arguments.filterKeys { key ->
                    key !in setOf("nodeId", "observationId") &&
                        (target == null && !candidate.operation.startsWith("SWIPE_") || key !in setOf("x", "y", "x1", "y1", "x2", "y2"))
                }))
            }
        }.toString())
    }

    private fun baseIdentity(node: JsonObject): JsonObject = bases.getOrPut(node) { buildJsonObject {
        listOf("package", "windowType", "class", "resourceId", "editable").forEach { key ->
            node[key]?.let { put(key, it) }
        }
        put("label", label(node))
    } }

    private fun ancestors(node: JsonObject): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var parent = node.string("parentId")
        val seen = mutableSetOf<String>()
        while (parent != null && seen.add(parent) && result.size < 12) {
            val entry = byId[parent] ?: break
            result += entry
            parent = entry.string("parentId")
        }
        return result.asReversed()
    }

    private fun descendants(node: JsonObject): List<JsonObject> {
        val id = node.string("nodeId") ?: return emptyList()
        val linked = mutableListOf<JsonObject>()
        val queue = ArrayDeque<JsonObject>().apply { addAll(children[id].orEmpty()) }
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty() && linked.size < 100) {
            val child = queue.removeFirst()
            val childId = child.string("nodeId") ?: continue
            if (!seen.add(childId)) continue
            linked += child
            queue.addAll(children[childId].orEmpty())
        }
        if (linked.isNotEmpty()) return linked
        // Older backends have no parent links. Only use contained, same-window
        // descendants until traversal leaves the field; never borrow another row.
        val bounds = node.bounds() ?: return emptyList()
        return nodes.drop(nodes.indexOf(node) + 1).take(12).takeWhile { child ->
            val box = child.bounds()
            child.string("package") == node.string("package") && child.string("windowType") == node.string("windowType") &&
                box != null && box[0] >= bounds[0] && box[1] >= bounds[1] && box[2] <= bounds[2] && box[3] <= bounds[3]
        }
    }

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
        private fun JsonObject.bounds() = this["bounds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }?.takeIf { it.size == 4 }
    }
}
