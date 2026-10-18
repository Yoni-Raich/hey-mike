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

/** The signals backed by Android sources. Unknown is never evidence for a condition. */
object AutomationDeviceStates {
    const val BLUETOOTH_HEADPHONES = "bluetooth_headphones"
    val values = mapOf(
        "power" to setOf("charging", "discharging"),
        "screen" to setOf("on", "off"),
        BLUETOOTH_HEADPHONES to setOf("connected", "disconnected"),
        "wifi" to setOf("connected", "disconnected"),
        "bluetooth_device" to setOf("connected", "disconnected"),
    )

    fun missing(rule: AutomationRule, supported: Set<String>): Set<String> =
        (rule.conditions.mapNotNull { it.stateName } + listOfNotNull(rule.trigger.stateName)).toSet() - supported

    fun validate(name: String, value: String?, trigger: Boolean = false) {
        val allowed = values[name] ?: throw AutomationFormatException(
            "automation_invalid", "Unsupported device state \"$name\". Use ${values.keys.joinToString()}.",
        )
        if (value != null && value !in allowed && !(trigger && name == "screen" && value == "unlocked")) throw AutomationFormatException(
            "automation_invalid", "Unsupported value \"$value\" for \"$name\". Use ${allowed.joinToString()}.",
        )
    }
}
