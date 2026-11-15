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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Where a `show_ui` panel is drawn. */
enum class PanelSurface(val key: String) {
    /** In the chat, as before. */
    CHAT("chat"),
    /** A card over whatever app is in front. */
    FLOATING("floating"),
    /** The Mike home-screen widget. */
    WIDGET("widget"),
    /** An ongoing notification with action buttons. */
    NOTIFICATION("notification"),
}

/** How long a panel lives. */
sealed interface PanelLifetime {
    /** Until the turn that showed it ends: the existing behavior. */
    data object Turn : PanelLifetime
    /** For [seconds], then it removes itself. */
    data class Ttl(val seconds: Long) : PanelLifetime
    /** Until the user removes it. */
    data object Pinned : PanelLifetime
}

/**
 * A panel's surface and lifetime. A panel that outlives its turn is
 * [detached]: it runs its taps without Mike, so only [PanelAllowlist] calls
 * may be bound, and every one but a short floating card needs the user's
 * approval before it is live.
 */
data class PanelPlacement(val surface: PanelSurface, val lifetime: PanelLifetime) {
    val detached: Boolean get() = lifetime != PanelLifetime.Turn

    /** True unless this is a floating card that lives [SHORT_TTL_SECONDS] or less. */
    val needsApproval: Boolean
        get() = detached && !(surface == PanelSurface.FLOATING && lifetime is PanelLifetime.Ttl && lifetime.seconds <= SHORT_TTL_SECONDS)

    val ttlSeconds: Long? get() = (lifetime as? PanelLifetime.Ttl)?.seconds

    /** One line for the user: "Floating card, 10 minutes". */
    val words: String
        get() {
            val where = when (surface) {
                PanelSurface.CHAT -> "In the chat"
                PanelSurface.FLOATING -> "Floating card over other apps"
                PanelSurface.WIDGET -> "Home-screen widget"
                PanelSurface.NOTIFICATION -> "Ongoing notification, also on the lock screen"
            }
            val long = when (val life = lifetime) {
                PanelLifetime.Turn -> "while Mike's turn lasts"
                PanelLifetime.Pinned -> "until you remove it"
                is PanelLifetime.Ttl -> "for ${duration(life.seconds)}"
            }
            return "$where, $long"
        }

    companion object {
        /** The longest floating card that is shown without an approval, and a floating card's default. */
        const val SHORT_TTL_SECONDS = 60L
        const val MIN_TTL_SECONDS = 5L
        const val MAX_TTL_SECONDS = 86_400L
        val CHAT = PanelPlacement(PanelSurface.CHAT, PanelLifetime.Turn)

        /** The placement in `show_ui` [arguments], or a reason the model can fix. */
        fun parse(arguments: JsonObject): PanelPlacement {
            val rawSurface = (arguments["surface"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            val surface = if (rawSurface.isNullOrEmpty()) PanelSurface.CHAT else PanelSurface.entries.firstOrNull { it.key == rawSurface }
                ?: throw IllegalArgumentException("surface \"$rawSurface\" is not one of ${PanelSurface.entries.joinToString("|") { it.key }}.")
            val ttl = arguments["ttl_seconds"]?.let { raw ->
                (raw as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toLong() }
                    ?: throw IllegalArgumentException("ttl_seconds must be a number of seconds.")
            }
            val rawLifetime = (arguments["lifetime"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            val lasting = surface == PanelSurface.WIDGET || surface == PanelSurface.NOTIFICATION
            val lifetime = when (rawLifetime) {
                null, "" -> when {
                    ttl != null -> PanelLifetime.Ttl(ttl)
                    lasting -> PanelLifetime.Pinned
                    // A floating card is drawn by the app over other apps, not by the
                    // turn, so it always lives on its own: a short card by default.
                    surface == PanelSurface.FLOATING -> PanelLifetime.Ttl(SHORT_TTL_SECONDS)
                    else -> PanelLifetime.Turn
                }
                "turn" -> PanelLifetime.Turn
                "pinned" -> PanelLifetime.Pinned
                "ttl" -> PanelLifetime.Ttl(ttl ?: throw IllegalArgumentException("lifetime ttl needs ttl_seconds."))
                else -> throw IllegalArgumentException("lifetime \"$rawLifetime\" is not one of turn|ttl|pinned.")
            }
            if (ttl != null) require(lifetime is PanelLifetime.Ttl) { "ttl_seconds needs lifetime ttl." }
            if (lifetime is PanelLifetime.Ttl) {
                require(lifetime.seconds in MIN_TTL_SECONDS..MAX_TTL_SECONDS) {
                    "ttl_seconds must be $MIN_TTL_SECONDS to $MAX_TTL_SECONDS (24 hours). For longer, use lifetime pinned."
                }
            }
            if (surface == PanelSurface.CHAT) {
                require(lifetime == PanelLifetime.Turn) {
                    "A chat panel lives for the turn. To keep a panel, use surface floating, widget or notification."
                }
            }
            if (surface != PanelSurface.CHAT) {
                require(lifetime != PanelLifetime.Turn) { "A ${surface.key} outlives the turn: use lifetime ttl or pinned." }
            }
            return PanelPlacement(surface, lifetime)
        }

        internal fun duration(seconds: Long): String = when {
            seconds % 3_600 == 0L -> plural(seconds / 3_600, "hour")
            seconds % 60 == 0L -> plural(seconds / 60, "minute")
            else -> plural(seconds, "second")
        }

        private fun plural(count: Long, unit: String) = "$count $unit" + if (count == 1L) "" else "s"
    }
}

/**
 * The only tool calls a panel may make without Mike.
 *
 * A detached panel's tap runs with no model turn and nobody reads it first,
 * so the set is small and explicit: `system_controls` operations that change
 * one phone setting or press a media key, and two reads for live values.
 * Nothing here touches the screen, runs a shell, types, sends or reads
 * private data. Checked when the panel is shown, on every load and on every
 * tap, on the arguments as they are really sent.
 */
object PanelAllowlist {
    const val TOOL = "system_controls"
    val ACTIONS: Set<String> = setOf("torch", "volume", "ringer", "dnd", "brightness", "auto_rotate", "media_control")
    val READS: Set<String> = setOf("status", "media_status")
    /** Keys that choose what a call does; a slider's number may never go there. */
    private val CHOOSERS = setOf("operation", "command")

    /** Why [call] may not run from a tap, or null when it may. */
    fun checkAction(call: PanelCall): String? = check(call, ACTIONS, "run without Mike")

    /** Why [call] may not be read for a live value, or null when it may. */
    fun checkRead(call: PanelCall): String? = check(call, READS, "be read for a live value")

    private fun check(call: PanelCall, allowed: Set<String>, what: String): String? {
        val list = allowed.sorted().joinToString(", ")
        if (call.tool != TOOL) return "${call.tool} cannot $what. A panel that outlives the turn may only use $TOOL ($list)."
        if (call.arguments.toString().length > AgentPanels.MAX_ARGUMENTS) return "its arguments are too long."
        CHOOSERS.forEach { key ->
            val value = call.arguments[key] ?: return@forEach
            if (holdsMark(value)) return "\"${AgentPanels.VALUE}\" cannot choose the $key."
            if (value !is JsonPrimitive || !value.isString) return "$key must be a string."
        }
        val operation = (call.arguments["operation"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return "$TOOL needs an operation."
        if (operation !in allowed) return "$TOOL $operation cannot $what. Allowed here: $list."
        return null
    }

    private fun holdsMark(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.any(::holdsMark)
        is JsonArray -> element.any(::holdsMark)
        is JsonPrimitive -> element.isString && element.content.contains(AgentPanels.VALUE)
    }
}

/**
 * Plain words for a panel's call, for the approval screen. The raw call is
 * always shown next to these, so a vague line cannot hide what a tap does.
 */
object PanelCallWords {
    fun describe(call: PanelCall): String {
        if (call.tool != PanelAllowlist.TOOL) return "Run ${call.tool}"
        val args = call.arguments
        fun text(key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.let {
            if (it == AgentPanels.VALUE) "the slider's number" else it
        }
        fun onOff(key: String): String = when (text(key)?.lowercase()) {
            "true" -> "on"
            "false" -> "off"
            null -> "on or off"
            else -> text(key)!!
        }
        fun amount(): String = when {
            args["percent"] != null -> text("percent")!!.let { if (AgentPanels.holdsValue(args["percent"]!!)) "the slider's percent" else "$it%" }
            args["level"] != null -> text("level")!!.let { if (AgentPanels.holdsValue(args["level"]!!)) "the slider's level" else "level $it" }
            else -> "a new level"
        }
        return when (text("operation")) {
            "torch" -> "Turn the flashlight ${onOff("on")}"
            "volume" -> {
                val stream = text("stream") ?: "media"
                "Set the $stream volume to ${amount()}"
            }
            "ringer" -> "Set the ringer to ${text("mode") ?: "a new mode"}"
            "dnd" -> "Set Do Not Disturb to ${text("mode") ?: "a new mode"}"
            "brightness" -> when {
                args["auto"] != null -> "Turn automatic brightness ${onOff("auto")}"
                else -> "Set the screen brightness to ${amount()}"
            }
            "auto_rotate" -> "Turn auto-rotate ${onOff("on")}"
            "media_control" -> media(text("command"), args)
            "media_status" -> "Read what is playing: app, title, artist, state and position"
            "status" -> "Read the phone's status: battery, volume, ringer and similar"
            else -> "Run ${PanelAllowlist.TOOL} ${text("operation").orEmpty()}".trim()
        }
    }

    private fun media(command: String?, args: JsonObject): String {
        val app = (args["package"] as? JsonPrimitive)?.contentOrNull?.let { " in $it" } ?: " in the playing app"
        return when (command) {
            "play" -> "Play media$app"
            "pause" -> "Pause media$app"
            "toggle" -> "Play or pause media$app"
            "next" -> "Skip to the next track$app"
            "previous" -> "Go back to the previous track$app"
            "stop" -> "Stop media$app"
            "seek" -> "Jump to a position in the track$app"
            "seek_by" -> {
                val raw = (args["offset_ms"] as? JsonPrimitive)?.contentOrNull
                val ms = raw?.toLongOrNull()
                when {
                    ms == null -> "Skip by the slider's amount$app"
                    ms >= 0 -> "Skip forward ${seconds(ms)}$app"
                    else -> "Skip back ${seconds(-ms)}$app"
                }
            }
            else -> "Send the media key \"${command.orEmpty()}\"$app"
        }
    }

    private fun seconds(ms: Long): String = AgentPanels.number(ms / 1_000.0, 1) + " s"
}
