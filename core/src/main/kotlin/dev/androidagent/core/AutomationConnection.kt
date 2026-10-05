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

import kotlinx.serialization.json.*

/** Current connections only. Display names never act as identity. */
data class AutomationConnection(
    val state: String,
    val address: String? = null,
    val name: String? = null,
    val profiles: Set<String> = emptySet(),
    val ssid: String? = null,
    val bssid: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("state", state)
        address?.let { put("deviceAddress", it) }
        name?.let { put("name", it) }
        if (profiles.isNotEmpty()) put("profiles", JsonArray(profiles.sorted().map(::JsonPrimitive)))
        ssid?.let { put("ssid", it) }
        bssid?.let { put("bssid", it) }
    }
    companion object {
        fun parse(json: JsonObject) = AutomationConnection(
            state = requireNotNull(json.str("state")), address = json.str("deviceAddress"), name = json.str("name"),
            profiles = (json["profiles"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            ssid = (json["ssid"] as? JsonPrimitive)?.content, bssid = json.str("bssid"),
        )
    }
}

data class AutomationDeviceSnapshot(
    val states: Map<String, String> = emptyMap(),
    val connections: List<AutomationConnection> = emptyList(),
)
