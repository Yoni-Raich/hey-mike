/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether an advertised tool schema says enough to be callable.
 *
 * `act_plan` shipped with `steps` as a bare `{"type":"array"}`. A client with no
 * `items` renders that as an array of strings, so the agent sent each step as
 * quoted JSON and was refused - for the schema's mistake, not its own, and only
 * on a phone. A schema is the only description of a tool a caller is guaranteed
 * to have read, so "it is an array" is not a description of anything.
 *
 * This is the shared definition of that defect. Every gateway's tests run it
 * over everything that gateway advertises, so a new tool cannot reintroduce it
 * and a reviewer does not have to remember the rule.
 */
object ToolSchemaAudit {

    /**
     * One line per defect, empty when every definition describes itself.
     *
     * Deliberately narrow: it judges whether a schema *says* what it takes, not
     * whether the wording is good. Anything stricter would fail honest schemas
     * and be switched off.
     */
    fun complaints(definitions: List<ToolDefinition>): List<String> =
        definitions.flatMap { definition -> complaints(definition) }

    fun complaints(definition: ToolDefinition): List<String> {
        val where = definition.name
        val schema = definition.inputSchema
        val complaints = mutableListOf<String>()
        if (schema.type() != "object") {
            complaints += "$where: the input schema is not an object."
        }
        val properties = schema["properties"] as? JsonObject
        if (properties == null) {
            // A tool that takes nothing is allowed to say nothing.
            if (schema["required"]?.let { it is JsonArray && it.isNotEmpty() } == true) {
                complaints += "$where: it requires arguments but declares no properties."
            }
            return complaints
        }
        (schema["required"] as? JsonArray).orEmpty().mapNotNull { it.text() }.forEach { name ->
            if (name !in properties) complaints += "$where: \"$name\" is required but is not a property."
        }
        for ((name, property) in properties) {
            complaints += complaints(property as? JsonObject, "$where.$name")
        }
        return complaints
    }

    private fun complaints(schema: JsonObject?, where: String): List<String> {
        if (schema == null) return listOf("$where: the property schema is not an object.")
        val complaints = mutableListOf<String>()
        when (schema.type()) {
            "array" -> {
                val items = schema["items"] as? JsonObject
                if (items == null) {
                    complaints += "$where: an array with no \"items\" reads as an array of strings."
                } else {
                    complaints += complaints(items, "$where[]")
                }
            }
            "object" ->
                // Either the keys are named, or they are declared open. Saying
                // neither leaves a caller to guess whether its keys are read.
                if (schema["properties"] == null && schema["additionalProperties"] == null) {
                    complaints += "$where: an object that names no properties and does not allow additional ones."
                }
            null ->
                // A union - `rule` is a whole rule or its id - may skip `type`,
                // but it still has to describe one of its shapes.
                if (DESCRIBES.none { schema[it] != null }) {
                    complaints += "$where: no \"type\" and nothing else that says what it takes."
                }
        }
        (schema["properties"] as? JsonObject)?.forEach { (name, nested) ->
            complaints += complaints(nested as? JsonObject, "$where.$name")
        }
        return complaints
    }

    /** Ways a schema with no `type` can still say what it takes. */
    private val DESCRIBES = listOf("properties", "items", "enum", "oneOf", "anyOf", "allOf", "const")

    private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun kotlinx.serialization.json.JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
}
