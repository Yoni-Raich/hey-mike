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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A tool whose arguments are plain scalars, plus any spelled-out schemas in
 * [structured].
 *
 * [properties] maps each argument to its JSON type. An `object` argument is a
 * free-form map, such as open_intent's extras or workflow_runner's params, so
 * it allows any keys.
 *
 * Spell every array out in [structured] with its `items`; [ToolSchemaAudit]
 * explains why a bare `{"type":"array"}` is a defect.
 */
fun toolDefinition(
    name: String,
    description: String,
    properties: Map<String, String>,
    required: List<String>,
    structured: Map<String, JsonObject> = emptyMap(),
): ToolDefinition {
    val props = buildJsonObject {
        for ((key, type) in properties) {
            put(key, structured[key] ?: buildJsonObject {
                put("type", type)
                if (type == "object") put("additionalProperties", true)
            })
        }
    }
    val schema = buildJsonObject {
        put("type", "object")
        put("properties", props)
        put("description", description)
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
    return ToolDefinition(name, description, schema)
}
