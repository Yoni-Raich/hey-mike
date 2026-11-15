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

package dev.androidagent.devicetools

import dev.androidagent.core.LocalIntentRequest
import dev.androidagent.core.MediaSessionAccess
import dev.androidagent.core.NotificationOutcome
import dev.androidagent.core.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A fix from the location providers. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val speedMps: Float?,
    val provider: String?,
    val timeMs: Long,
    val fresh: Boolean,
)

/** One app's foreground time in a usage window. */
data class UsageRow(val packageName: String, val foregroundMs: Long, val lastUsedMs: Long)

/** Result of a system write: done, or a typed reason with the grant that fixes it. */
sealed interface SystemWrite {
    data object Done : SystemWrite
    data class Missing(val grant: String, val message: String) : SystemWrite
    data class Failed(val errorType: String, val message: String) : SystemWrite
}

/**
 * Direct system controls the app process can perform without the screen.
 * Each write reports the special grant it lacks instead of opening anything.
 */
interface SystemPlatform {
    /** Volumes, ringer, DND, brightness, timeout, rotation, torch, and which grants are held. */
    fun status(): Map<String, Any?>
    fun setTorch(on: Boolean): SystemWrite
    /** Sets [stream] to [level] (0..max). Returns the stream's level and max afterwards. */
    fun setVolume(stream: String, level: Int?, percent: Int?, showUi: Boolean): Pair<SystemWrite, Map<String, Int>>
    fun setRinger(mode: String): SystemWrite
    fun setDnd(mode: String): SystemWrite
    fun setBrightness(level: Int?, auto: Boolean?): SystemWrite
    fun setScreenTimeout(seconds: Int): SystemWrite
    fun setAutoRotate(on: Boolean): SystemWrite
    fun clipboardGet(): String?
    fun clipboardSet(text: String): SystemWrite
    suspend fun location(precise: Boolean, timeoutMs: Long): LocationFix?
    fun hasUsageAccess(): Boolean
    fun usage(sinceMs: Long, untilMs: Long, limit: Int): List<UsageRow>
    fun getSetting(namespace: String, key: String): String?
    fun putSetting(namespace: String, key: String, value: String?): SystemWrite
}

/** Pure policy for [SystemControlDispatcher]. */
object SystemPolicy {
    const val TOOL = "system_controls"

    val OPS: Set<String> = setOf(
        "status", "torch", "volume", "ringer", "dnd", "brightness", "screen_timeout", "auto_rotate",
        "clipboard_get", "clipboard_set", "location", "media_status", "media_control",
        "usage", "get_setting", "put_setting",
    )

    /** Operations that change nothing and never open a screen. */
    val READ_ONLY: Set<String> = setOf("status", "clipboard_get", "location", "media_status", "usage", "get_setting")

    val ARGS: Map<String, Set<String>> = mapOf(
        "status" to emptySet(),
        "torch" to setOf("on"),
        "volume" to setOf("stream", "level", "percent", "show_ui"),
        "ringer" to setOf("mode"),
        "dnd" to setOf("mode"),
        "brightness" to setOf("level", "percent", "auto"),
        "screen_timeout" to setOf("seconds"),
        "auto_rotate" to setOf("on"),
        "clipboard_get" to emptySet(),
        "clipboard_set" to setOf("text"),
        "location" to setOf("precise", "timeout_ms"),
        "media_status" to emptySet(),
        "media_control" to setOf("command", "package", "position_ms"),
        "usage" to setOf("hours", "limit"),
        "get_setting" to setOf("namespace", "key"),
        "put_setting" to setOf("namespace", "key", "value"),
    )

    val STREAMS = setOf("media", "ring", "notification", "alarm", "call", "system")
    val RINGER_MODES = setOf("normal", "vibrate", "silent")
    val DND_MODES = setOf("off", "priority", "alarms", "total")
    val MEDIA_COMMANDS = setOf("play", "pause", "toggle", "next", "previous", "stop", "seek")
    val NAMESPACES = setOf("system", "secure", "global")
    private val SETTING_KEY_RE = Regex("^[A-Za-z0-9_.:\\-]{1,128}$")

    fun settingKey(args: JsonObject): String {
        val key = CapabilityPolicy.parseBounded(args, "key", required = true, max = 128)!!
        if (!key.matches(SETTING_KEY_RE)) throw PolicyException("invalid_argument", "\"key\" is not a settings key.")
        return key
    }

    fun choice(args: JsonObject, key: String, allowed: Set<String>): String {
        val value = CapabilityPolicy.parseBounded(args, key, required = true)!!.lowercase()
        if (value !in allowed) {
            throw PolicyException("invalid_argument", "Unknown $key \"$value\". Use: " + allowed.sorted().joinToString(", ") + ".")
        }
        return value
    }

    fun int(args: JsonObject, key: String, range: IntRange): Int? {
        val raw = args[key] ?: return null
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw PolicyException("invalid_argument", "\"$key\" must be an integer.")
        if (value !in range) throw PolicyException("invalid_argument", "\"$key\" must be in ${range.first}..${range.last}.")
        return value
    }

    fun requiredBool(args: JsonObject, key: String): Boolean {
        if (args[key] == null) throw PolicyException("invalid_argument", "\"$key\" is required.")
        return CapabilityPolicy.parseBoolean(args, key)
    }

    fun optionalBool(args: JsonObject, key: String): Boolean? =
        if (args[key] == null) null else CapabilityPolicy.parseBoolean(args, key)
}

/**
 * Dispatch for the system_controls tool. Shares the capability envelopes, so
 * the model reads the same `{"ok":...}` shape as every other capability.
 */
internal class SystemControlDispatcher(
    private val platform: SystemPlatform,
    private val media: MediaSessionAccess,
    private val checkActive: () -> Unit,
    private val authorizeChange: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult,
) {
    private val tool = SystemPolicy.TOOL

    suspend fun invoke(operation: String, args: JsonObject): ToolResult {
        val allowed = SystemPolicy.ARGS[operation]
            ?: throw PolicyException("unknown_operation", "Unknown operation \"$operation\" for \"$tool\".")
        for (key in args.keys) {
            if (key != "operation" && key !in allowed) {
                throw PolicyException("unknown_argument", "Unknown argument \"$key\" for \"$tool.$operation\".")
            }
        }
        return when (operation) {
            "status" -> ok(operation) {
                for ((key, value) in platform.status()) putAny(key, value)
            }
            "torch" -> write(operation, platform.setTorch(SystemPolicy.requiredBool(args, "on")))
            "volume" -> {
                val stream = SystemPolicy.choice(args, "stream", SystemPolicy.STREAMS)
                val level = SystemPolicy.int(args, "level", 0..100)
                val percent = SystemPolicy.int(args, "percent", 0..100)
                if (level != null && percent != null) throw PolicyException("invalid_argument", "Give level or percent, not both.")
                val showUi = CapabilityPolicy.parseBoolean(args, "show_ui")
                checkActive()
                val (result, after) = platform.setVolume(stream, level, percent, showUi)
                write(operation, result) { for ((k, v) in after) put(k, v) }
            }
            "ringer" -> write(operation, platform.setRinger(SystemPolicy.choice(args, "mode", SystemPolicy.RINGER_MODES)))
            "dnd" -> write(operation, platform.setDnd(SystemPolicy.choice(args, "mode", SystemPolicy.DND_MODES)))
            "brightness" -> {
                val level = SystemPolicy.int(args, "level", 0..255)
                val percent = SystemPolicy.int(args, "percent", 0..100)
                val auto = SystemPolicy.optionalBool(args, "auto")
                if (level != null && percent != null) throw PolicyException("invalid_argument", "Give level or percent, not both.")
                if (level == null && percent == null && auto == null) {
                    throw PolicyException("invalid_argument", "Give level, percent or auto.")
                }
                write(operation, platform.setBrightness(level ?: percent?.let { (it * 255 + 50) / 100 }, auto))
            }
            "screen_timeout" -> write(operation, platform.setScreenTimeout(SystemPolicy.int(args, "seconds", 5..86_400)
                ?: throw PolicyException("invalid_argument", "\"seconds\" is required.")))
            "auto_rotate" -> write(operation, platform.setAutoRotate(SystemPolicy.requiredBool(args, "on")))
            "clipboard_get" -> {
                val text = platform.clipboardGet()
                ok(operation) {
                    if (text == null) {
                        put("available", false)
                        put("note", "Android hides the clipboard from apps that are not in front or not the keyboard. Switch to the Hey Mike keyboard or open Hey Mike, then try again.")
                    } else {
                        put("available", true)
                        put("text", CapabilityPolicy.bound(text, CapabilityPolicy.MAX_READ_CHARS))
                    }
                }
            }
            "clipboard_set" -> {
                val element = args["text"] as? JsonPrimitive
                if (element == null || !element.isString) throw PolicyException("invalid_argument", "\"text\" must be a string.")
                if (element.content.length > CapabilityPolicy.MAX_TEXT_CHARS) throw PolicyException("invalid_argument", "\"text\" is too long.")
                write(operation, platform.clipboardSet(element.content))
            }
            "location" -> {
                val precise = SystemPolicy.optionalBool(args, "precise") ?: true
                val timeout = (SystemPolicy.int(args, "timeout_ms", 1_000..60_000) ?: 15_000).toLong()
                val permission = if (precise) CapabilityPolicy.PERM_FINE_LOCATION else CapabilityPolicy.PERM_COARSE_LOCATION
                checkActive()
                val fix = try {
                    platform.location(precise, timeout)
                } catch (_: SecurityException) {
                    return ToolResult(CapabilityPolicy.permissionFailure(tool, operation, listOf(permission), args), success = false)
                }
                checkActive()
                if (fix == null) {
                    return fail(operation, "no_fix", "No location within ${timeout / 1000}s. Location may be off in Settings, or the phone has no signal.")
                }
                ok(operation) {
                    put("latitude", fix.latitude)
                    put("longitude", fix.longitude)
                    fix.accuracyM?.let { put("accuracyM", it) }
                    fix.altitudeM?.let { put("altitudeM", it) }
                    fix.speedMps?.let { put("speedMps", it) }
                    fix.provider?.let { put("provider", it) }
                    put("timeMs", fix.timeMs)
                    put("fresh", fix.fresh)
                    put("mapsUrl", "https://maps.google.com/?q=${fix.latitude},${fix.longitude}")
                }
            }
            "media_status" -> {
                val sessions = media.sessions()
                ok(operation) {
                    put("sessions", JsonArray(sessions.take(CapabilityPolicy.MAX_ARRAY_ITEMS).map { s ->
                        buildJsonObject {
                            put("package", s.packageName)
                            put("state", s.state)
                            s.title?.let { put("title", CapabilityPolicy.bound(it)) }
                            s.artist?.let { put("artist", CapabilityPolicy.bound(it)) }
                            s.album?.let { put("album", CapabilityPolicy.bound(it)) }
                            s.durationMs?.let { put("durationMs", it) }
                            s.positionMs?.let { put("positionMs", it) }
                            put("actions", JsonArray(s.actions.map { JsonPrimitive(it) }))
                        }
                    }))
                    if (sessions.isEmpty()) put("note", "No active media session, or notification access is off (media control needs it).")
                }
            }
            "media_control" -> {
                val command = SystemPolicy.choice(args, "command", SystemPolicy.MEDIA_COMMANDS)
                val pkg = CapabilityPolicy.parsePackage(args, required = false)
                val position = CapabilityPolicy.parseMillis(args, "position_ms")
                checkActive()
                when (val outcome = media.control(pkg, command, position)) {
                    NotificationOutcome.Done -> ok(operation) { put("done", true) }
                    is NotificationOutcome.Failed -> fail(operation, outcome.errorType, outcome.message)
                }
            }
            "usage" -> {
                val hours = SystemPolicy.int(args, "hours", 1..24 * 31) ?: 24
                val limit = CapabilityPolicy.parseLimit(args)
                if (!platform.hasUsageAccess()) {
                    return fail(operation, "usage_access_missing", "Usage access is off for Hey Mike. Open it with apps_settings open_special_access kind=usage_access.")
                }
                val until = System.currentTimeMillis()
                val rows = platform.usage(until - hours * 3_600_000L, until, limit)
                ok(operation) {
                    put("hours", hours)
                    put("apps", JsonArray(rows.map { r ->
                        buildJsonObject {
                            put("package", r.packageName)
                            put("foregroundMinutes", r.foregroundMs / 60_000)
                            put("lastUsedMs", r.lastUsedMs)
                        }
                    }))
                }
            }
            "get_setting" -> {
                val ns = SystemPolicy.choice(args, "namespace", SystemPolicy.NAMESPACES)
                val key = SystemPolicy.settingKey(args)
                val value = platform.getSetting(ns, key)
                ok(operation) {
                    put("namespace", ns)
                    put("key", key)
                    if (value == null) put("value", kotlinx.serialization.json.JsonNull) else put("value", CapabilityPolicy.bound(value))
                }
            }
            "put_setting" -> {
                val ns = SystemPolicy.choice(args, "namespace", SystemPolicy.NAMESPACES)
                val key = SystemPolicy.settingKey(args)
                val value = CapabilityPolicy.parseBounded(args, "value", required = false)
                checkActive()
                val request = LocalIntentRequest(
                    action = "settings put $ns $key ${value ?: "(delete)"}",
                    uri = null,
                    packageName = null,
                    reason = "Change the phone setting $ns/$key to ${value ?: "its default"}.",
                )
                // A raw settings write can turn off the screen reader, the
                // keyboard or ADB. The user approves each one (Full access
                // approves automatically, as for every other approval).
                authorizeChange(request) {
                    when (val result = platform.putSetting(ns, key, value)) {
                        SystemWrite.Done -> ToolResult(CapabilityPolicy.ok(tool, operation) {
                            put("namespace", ns); put("key", key)
                            put("value", platform.getSetting(ns, key)?.let { JsonPrimitive(CapabilityPolicy.bound(it)) } ?: kotlinx.serialization.json.JsonNull)
                        })
                        else -> writeFailure(operation, result)
                    }
                }
            }
            else -> fail(operation, "unknown_operation", "Unknown operation \"$operation\".")
        }
    }

    private fun write(operation: String, result: SystemWrite, extra: JsonObjectBuilder.() -> Unit = {}): ToolResult =
        when (result) {
            SystemWrite.Done -> ok(operation) { put("done", true); extra() }
            else -> writeFailure(operation, result)
        }

    private fun writeFailure(operation: String, result: SystemWrite): ToolResult = when (result) {
        is SystemWrite.Missing -> ToolResult(
            CapabilityPolicy.fail(tool, operation, "grant_missing", result.message) {
                put("grant", result.grant)
                put("hint", "Open it with apps_settings open_special_access kind=${result.grant}; the user flips the switch, then retry.")
            },
            success = false,
        )
        is SystemWrite.Failed -> fail(operation, result.errorType, result.message)
        SystemWrite.Done -> ok(operation) { put("done", true) }
    }

    private fun ok(operation: String, body: JsonObjectBuilder.() -> Unit): ToolResult =
        ToolResult(CapabilityPolicy.ok(tool, operation, body))

    private fun fail(operation: String, errorType: String, message: String): ToolResult =
        ToolResult(CapabilityPolicy.fail(tool, operation, errorType, message), success = false)

    private fun JsonObjectBuilder.putAny(key: String, value: Any?) {
        when (value) {
            null -> put(key, kotlinx.serialization.json.JsonNull)
            is Boolean -> put(key, value)
            is Number -> put(key, value)
            is String -> put(key, CapabilityPolicy.bound(value))
            is Map<*, *> -> put(key, buildJsonObject { for ((k, v) in value) putAny(k.toString(), v) })
            else -> put(key, CapabilityPolicy.bound(value.toString()))
        }
    }
}
