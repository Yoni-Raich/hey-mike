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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The special app access a panel's device call needs before it can work, so
 * the approval screen can flag it while it is off and a failed tap can say
 * which switch to flip. It mirrors AndroidSystemPlatform exactly: brightness,
 * screen timeout and auto-rotate write system settings ("Modify system
 * settings"); Do Not Disturb and the silent ringer need Do Not Disturb access.
 * Volume is not flagged: it needs Do Not Disturb access only when Android
 * refuses to silence a stream, which no one can know before the call. Nothing
 * here grants anything; the user flips the switch in Android's own screen.
 */
object PanelGrants {
    const val WRITE_SETTINGS = "write_settings"
    const val NOTIFICATION_POLICY = "notification_policy"

    /** A grant one call of a button needs; [step] is its place in a steps action, from 1. */
    data class Need(val grant: String, val step: Int? = null)

    /** The grant [call] needs, or null when it needs none we can check first. */
    fun needed(call: PanelCall): String? {
        if (call.tool != PanelAllowlist.TOOL) return null
        fun text(key: String) = (call.arguments[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        return when (text("operation")) {
            "brightness", "screen_timeout", "auto_rotate" -> WRITE_SETTINGS
            "dnd" -> NOTIFICATION_POLICY
            "ringer" -> if (text("mode") == "silent") NOTIFICATION_POLICY else null
            else -> null
        }
    }

    /** Android's own name for [grant], as the user finds it under Special app access. */
    fun name(grant: String): String = when (grant) {
        WRITE_SETTINGS -> "Modify system settings"
        NOTIFICATION_POLICY -> "Do Not Disturb access"
        else -> grant
    }

    /** The grant a failed call's result says is missing, from its `grant_missing` error. */
    fun missing(result: String): String? = runCatching {
        val body = Json.parseToJsonElement(result).jsonObject
        if ((body["errorType"] as? JsonPrimitive)?.contentOrNull != "grant_missing") return@runCatching null
        (body["grant"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }.getOrNull()

    /** A failed tap's reason when a grant is off: what to allow and where. */
    fun reason(grant: String): String = "Hey Mike needs “${name(grant)}”; allow it in Saved panels"

    /**
     * One line per grant that is off, for a button's approval line: "Step 2
     * needs “Modify system settings”, which is off." [missing] holds the
     * grants that are off on this phone right now.
     */
    fun flags(needs: List<Need>, missing: Set<String>): List<Pair<String, String>> =
        needs.filter { it.grant in missing }.groupBy { it.grant }.map { (grant, all) ->
            val steps = all.mapNotNull { it.step }.distinct().sorted()
            val who = when {
                steps.isEmpty() -> "Needs"
                steps.size == 1 -> "Step ${steps.single()} needs"
                else -> "Steps " + steps.dropLast(1).joinToString(", ") + " and ${steps.last()} need"
            }
            grant to "$who “${name(grant)}” for Hey Mike, which is off. It fails until you allow it."
        }
}
