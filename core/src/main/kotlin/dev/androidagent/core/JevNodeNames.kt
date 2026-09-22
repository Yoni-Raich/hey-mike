package dev.androidagent.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object JevNodeNames {
    /**
     * A name for a checkbox or switch that carries none of its own.
     *
     * In a list row the control and its label are siblings: the checkbox came
     * first and "Task #3" after it, so `{checked: true}` read as belonging to
     * nothing. Jev could not tell the task was already done and toggled it
     * back and forth. The label is the named node sharing the control's row.
     */
    fun rowLabel(nodes: List<JsonObject>, node: JsonObject): String? {
        if (node.name() != null) return null
        if (node["checkable"]?.jsonPrimitive?.booleanOrNull != true && "checked" !in node) return null
        val box = node.box() ?: return null
        val height = box[3] - box[1]
        if (height <= 0) return null
        return nodes.asSequence()
            .filter { it !== node }
            .filter { it["package"] == node["package"] && it["windowType"] == node["windowType"] }
            .mapNotNull { other ->
                val name = other.name() ?: return@mapNotNull null
                val label = other.box() ?: return@mapNotNull null
                val labelHeight = label[3] - label[1]
                if (labelHeight <= 0 || labelHeight > height * MAX_LABEL_HEIGHT_RATIO) return@mapNotNull null
                val overlap = min(box[3], label[3]) - max(box[1], label[1])
                if (overlap <= 0) return@mapNotNull null
                val gap = when {
                    label[0] >= box[2] -> label[0] - box[2]
                    label[2] <= box[0] -> box[0] - label[2]
                    else -> 0
                }
                Triple(name, overlap.toDouble() / min(height, labelHeight), gap)
            }
            .sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { abs(it.third) })
            .firstOrNull()?.first?.take(MAX_NAME_CHARS)
    }

    private fun JsonObject.name(): String? = listOf("text", "contentDescription").firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.box(): List<Int>? =
        this["bounds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }?.takeIf { it.size == 4 }

    private const val MAX_LABEL_HEIGHT_RATIO = 3
    private const val MAX_NAME_CHARS = 180
}
