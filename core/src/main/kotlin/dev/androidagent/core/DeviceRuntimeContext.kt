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

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The per-turn device snapshot every engine puts before the user's text.
 *
 * Availability is reported **per operation**. An earlier version derived one
 * `Device tools available: yes/no` from the ADB phase alone and told the model
 * "Do not call device tools" whenever the transport was down, which blocked the
 * whole accessibility surface — `open_intent` on an ordinary deep link
 * included — for a reason that had nothing to do with it (issue #44).
 *
 * It is also worded accessibility-first. The accessibility service is the
 * main backend and Wireless ADB an optional extra, and a snapshot that led
 * with the ADB phase kept the model saying "ADB is not connected, so I can't"
 * for tasks it could do.
 */
object DeviceRuntimeContext {
    /** The first line of every snapshot. */
    const val HEADER = "[Trusted Android Agent runtime context]"

    fun render(
        capabilities: DeviceCapabilities,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String = buildString {
        val status = capabilities.adbStatus
        appendLine(HEADER)
        appendLine(
            "This snapshot replaces every older snapshot, and any earlier statement in this chat " +
                "that device tools were unavailable.",
        )
        // The model has no clock. Without this it read "today" off whatever
        // date a calendar happened to show, and got it wrong.
        appendLine(
            "Phone local time: " +
                now.format(TIME_FORMAT) +
                " (${now.zone.id}). A task that names a date means that date, not today.",
        )
        appendLine(
            capabilities.backendStatus?.let { "Backends: $it" }
                ?: "Wireless ADB (optional): ${status.phase.name.lowercase()}",
        )
        appendLine(
            "Device tools you can call now: " +
                if (capabilities.anyReady) capabilities.ready.sorted().joinToString(", ") else "none",
        )
        if (capabilities.blocked.isNotEmpty()) {
            appendLine("Tools that need a backend that is off: " + capabilities.blocked.sorted().joinToString(", "))
        }
        append(
            when {
                !capabilities.deviceBackendLive && status.phase in SETUP_PHASES ->
                    "No device backend is live yet. Screen control needs only the Hey Mike " +
                        "accessibility service: ask the user to enable it in Settings > Accessibility. " +
                        "Wireless ADB is still connecting, but it is optional."
                !capabilities.deviceBackendLive ->
                    "No device backend is live, so you cannot read or operate the screen right now. " +
                        "Anything in the first list still works — opening an app or a deep link needs no " +
                        "backend. For screen control, ask the user to enable the Hey Mike accessibility " +
                        "service in Settings > Accessibility. Wireless ADB is an optional advanced extra."
                capabilities.blocked.isEmpty() ->
                    "Use the supplied device tools when the task needs device access."
                status.phase != ConnectionPhase.CONNECTED ->
                    "Call anything in the first list normally. Wireless ADB is an optional advanced " +
                        "extra and being off is normal. Only if the task truly needs a tool from the " +
                        "second list, name that exact tool and what it needs. Never tell the user a " +
                        "task needs ADB when the first list covers it."
                else ->
                    "Call anything in the first list normally. The tools in the second list need the " +
                        "Hey Mike accessibility service; if the task needs one, ask the user to enable " +
                        "it in Settings > Accessibility. Do not treat the whole device as unavailable."
            },
        )
    }

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)

    /** ADB phases that mean "wait", not "ask the user to start over". */
    private val SETUP_PHASES = setOf(
        ConnectionPhase.DISCOVERING,
        ConnectionPhase.PAIRING,
        ConnectionPhase.CONNECTING,
    )
}
