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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Routes each tool to the first backend that declares it, and falls through to
 * the next when that backend reports the capability is absent.
 *
 * The advertised surface is deliberately **static**. Codex binds the tool list
 * at `thread/start` and never re-sends it on `thread/resume`, so a surface that
 * shrank when the accessibility service was switched off would leave every
 * running thread holding a list that no longer matches reality, with no way to
 * correct it. Availability is therefore reported at invoke time, as a typed
 * failure the model can act on.
 *
 * @param members backends in priority order, most preferred first.
 */
class CompositeDeviceToolGateway(
    private val members: List<DeviceToolGateway>,
) : DeviceToolGateway {

    init {
        require(members.isNotEmpty()) { "A composite gateway needs at least one backend" }
    }

    /** Tool name to its ordered fallback chain. */
    private val routes: Map<String, List<DeviceToolGateway>> =
        members
            .flatMap { member -> member.definitions.map { it.name to member } }
            .groupBy({ it.first }, { it.second })

    /**
     * First declaration of a name wins; a later duplicate only joins its fallback chain.
     * `device_status` is answered here, so it carries a description of every backend
     * rather than whichever member happened to declare it.
     */
    override val definitions: List<ToolDefinition> =
        members.flatMap { it.definitions }.distinctBy { it.name }.map { definition ->
            if (definition.name == DEVICE_STATUS) definition.copy(description = DEVICE_STATUS_DESCRIPTION) else definition
        }

    override fun beginRun(runId: String, workspace: File) {
        val failures = mutableListOf<Throwable>()
        for (member in members) {
            runCatching { member.beginRun(runId, workspace) }.onFailure { failures += it }
        }
        // A half-armed composite is the worst state to be in: one backend would
        // accept device calls while another silently refuses them.
        if (failures.isNotEmpty()) {
            revoke()
            throw failures.first()
        }
    }

    override fun revoke() {
        // Every member is revoked even when an earlier one throws. Skipping a
        // revocation would leave a live backend after Stop.
        val failures = mutableListOf<Throwable>()
        for (member in members) {
            runCatching { member.revoke() }.onFailure { failures += it }
        }
        failures.firstOrNull()?.let { throw it }
    }

    override fun needsControl(name: String): Boolean =
        // Unrouted names fail safe as visible control, matching the ADB gateway.
        routes[name]?.firstOrNull()?.needsControl(name) ?: true

    override fun hidesOverlayDuringCapture(name: String): Boolean =
        routes[name]?.firstOrNull()?.hidesOverlayDuringCapture(name) ?: false

    override fun statusLine(): String? =
        members.mapNotNull { it.statusLine() }.joinToString(" | ").takeIf { it.isNotEmpty() }

    /**
     * The union over live members, restricted to what this composite actually
     * advertises.
     *
     * A name served by a dead first choice and a live fallback is ready: that
     * is exactly what the fallback chain is for, and it is why a down ADB
     * transport does not make `read_ui` or `open_intent` unavailable.
     */
    override fun readyTools(): Set<String> {
        val live = members.flatMap { member -> runCatching { member.readyTools() }.getOrDefault(emptySet()) }
        // device_status is answered here, with no backend, so it is always ready.
        return definitions.map { it.name }.toSet() intersect (live.toSet() + DEVICE_STATUS)
    }

    override fun deviceBackendLive(): Boolean =
        members.any { member -> runCatching { member.deviceBackendLive() }.getOrDefault(false) }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (name == DEVICE_STATUS) {
            // Only the composite knows every backend, so it answers this itself.
            return ToolResult(statusLine() ?: "No device backend is configured.")
        }
        val chain = routes[name] ?: return ToolResult("Unknown tool: $name", success = false)
        val refusals = mutableListOf<ToolNotServiceable>()
        for (member in chain) {
            try {
                return member.invoke(name, arguments)
            } catch (absent: ToolNotServiceable) {
                // Contract: nothing was dispatched, so another backend may try.
                refusals += absent
            }
            // Any other exception propagates: the action may already have been
            // committed, and repeating it on another backend could double it.
        }
        return unavailable(name, refusals)
    }

    override suspend fun cancel() {
        // The coordinator budgets one timeout for the whole composite, so the
        // members are cancelled together rather than one after another.
        coroutineScope {
            members.map { member -> async { runCatching { member.cancel() } } }.awaitAll()
        }
    }

    /**
     * Explains an exhausted chain by its most useful refusal.
     *
     * A backend that is simply switched off says nothing about the call. When a
     * live backend refused for a reason of its own — no focused field, a key it
     * cannot send — that reason leads. Reporting "no backend" instead is what
     * made the model tell users a task needed ADB when it only needed a tap.
     */
    private fun unavailable(name: String, refusals: List<ToolNotServiceable>): ToolResult {
        val specific = refusals.firstOrNull { it.errorType !in BACKEND_OFF }
        val onlyAdbCanServe = refusals.isNotEmpty() && refusals.all { it.errorType == ADB_NOT_CONNECTED }
        return ToolResult(
            UiObservationSerializer.failureJson(
                observationId = "-",
                revision = 0,
                elapsedMs = 0,
                errorType = specific?.errorType ?: "backend_unavailable",
                message = specific?.message ?: "No device backend can currently serve \"$name\".",
                remedy = when {
                    specific != null ->
                        "Nothing happened on the device. Act on the message. Wireless ADB being off is " +
                            "normal and is not the cause unless the message says so."
                    onlyAdbCanServe ->
                        "\"$name\" is one of the few tools that need Wireless ADB, an optional advanced " +
                            "feature. Tell the user this one tool needs Wireless Debugging paired in Hey Mike " +
                            "settings; screen control works without it."
                    else ->
                        "Ask the user to enable the Hey Mike accessibility service in " +
                            "Settings > Accessibility. Wireless ADB is optional and not needed for this."
                },
                alternatives = definitions.map { it.name }.filter { it != name && it in READ_ONLY_ALTERNATIVES },
                reasons = refusals.map { "${it.errorType}: ${it.message}" },
            ),
            success = false,
        )
    }

    private companion object {
        const val DEVICE_STATUS = "device_status"
        const val DEVICE_STATUS_DESCRIPTION =
            "Report which device backends are live: the accessibility service, which serves screen control, " +
                "and the optional Wireless ADB. Read-only."

        const val ADB_NOT_CONNECTED = "adb_not_connected"

        /** Refusals that only mean "this backend is off", never why the call itself failed. */
        val BACKEND_OFF = setOf("a11y_unavailable", ADB_NOT_CONNECTED)

        /** Tools worth suggesting when the requested one has no live backend. */
        val READ_ONLY_ALTERNATIVES = setOf("read_ui", "screenshot", "device_status")
    }
}
