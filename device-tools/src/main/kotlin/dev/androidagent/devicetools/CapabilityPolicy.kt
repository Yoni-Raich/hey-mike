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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Typed policy failure raised before any side effect. Shaped into JSON by the dispatcher. */
class PolicyException(val errorType: String, override val message: String) : IllegalArgumentException(message)

/**
 * Pure allowlists, limits and validators for the capability gateway.
 *
 * No Android imports here on purpose: everything in this object runs on the
 * local JVM unit tests. The real [CapabilityPlatform] only executes what this
 * policy already accepted.
 */
object CapabilityPolicy {
    const val TOOL_CONTACTS = "contacts"
    const val TOOL_CALENDAR = "calendar"
    const val TOOL_FILES_MEDIA = "files_media"
    const val TOOL_COMMUNICATIONS = "communications"
    const val TOOL_APPS_SETTINGS = "apps_settings"

    val TOOLS: List<String> = listOf(
        TOOL_CONTACTS,
        TOOL_CALENDAR,
        TOOL_FILES_MEDIA,
        TOOL_COMMUNICATIONS,
        TOOL_APPS_SETTINGS,
    )

    val CONTACT_OPS: Set<String> = setOf("permission_status", "search", "list", "get", "create_draft")
    val CALENDAR_OPS: Set<String> = setOf("permission_status", "list", "get", "create_draft")
    val FILES_OPS: Set<String> = setOf(
        "permission_status", "list", "search", "info", "open", "share",
        "ws_list", "ws_read_text", "ws_write_text",
    )
    val COMM_OPS: Set<String> = setOf(
        "draft_sms", "draft_email", "dial",
        "notification_access_status", "open_notification_access_settings",
    )
    val APPS_OPS: Set<String> = setOf(
        "list_apps", "app_info", "open_app", "open_app_settings",
        "permission_status", "request_permissions",
        "open_special_access", "open_system_setting",
    )

    fun operationsFor(tool: String): Set<String> = when (tool) {
        TOOL_CONTACTS -> CONTACT_OPS
        TOOL_CALENDAR -> CALENDAR_OPS
        TOOL_FILES_MEDIA -> FILES_OPS
        TOOL_COMMUNICATIONS -> COMM_OPS
        TOOL_APPS_SETTINGS -> APPS_OPS
        else -> emptySet()
    }

    /**
     * Allowed argument keys per tool and operation, not counting "operation"
     * itself. Anything else is rejected before any side effect.
     */
    val ARGS: Map<String, Map<String, Set<String>>> = mapOf(
        TOOL_CONTACTS to mapOf(
            "permission_status" to emptySet(),
            "search" to setOf("query", "limit"),
            "list" to setOf("query", "limit"),
            "get" to setOf("id"),
            "create_draft" to setOf("name", "phone", "email"),
        ),
        TOOL_CALENDAR to mapOf(
            "permission_status" to emptySet(),
            "list" to setOf("startMs", "endMs", "limit"),
            "get" to setOf("id"),
            "create_draft" to setOf("title", "description", "location", "beginMs", "endMs", "allDay"),
        ),
        TOOL_FILES_MEDIA to mapOf(
            "permission_status" to emptySet(),
            "list" to setOf("kind", "name", "limit"),
            "search" to setOf("name", "kind", "limit"),
            "info" to setOf("uri"),
            "open" to setOf("uri"),
            "share" to setOf("uri"),
            "ws_list" to setOf("path", "limit"),
            "ws_read_text" to setOf("path"),
            "ws_write_text" to setOf("path", "text", "append"),
        ),
        TOOL_COMMUNICATIONS to mapOf(
            "draft_sms" to setOf("to", "body"),
            "draft_email" to setOf("to", "subject", "body"),
            "dial" to setOf("number"),
            "notification_access_status" to emptySet(),
            "open_notification_access_settings" to emptySet(),
        ),
        TOOL_APPS_SETTINGS to mapOf(
            "list_apps" to setOf("query", "limit", "include_system"),
            "app_info" to setOf("package"),
            "open_app" to setOf("package"),
            "open_app_settings" to setOf("package"),
            "permission_status" to setOf("permissions", "permission"),
            "request_permissions" to setOf("permissions", "permission"),
            "open_special_access" to setOf("kind", "package"),
            "open_system_setting" to setOf("setting"),
        ),
    )

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50
    const val MAX_QUERY_CHARS = 200
    const val MAX_STRING_CHARS = 512
    const val MAX_OUTPUT_CHARS = 20_000
    const val MAX_TEXT_CHARS = 65_536
    /** Read cap sized to always fit one result envelope. */
    const val MAX_READ_CHARS = 16_000
    const val MAX_ARRAY_ITEMS = 32
    const val MAX_OBJECT_KEYS = 32
    const val MAX_BOUND_DEPTH = 8
    /** Tool/operation names in envelopes: an unknown operation can be arbitrarily long. */
    const val MAX_TOOL_CHARS = 128
    const val MAX_URI_CHARS = 2_048
    const val MAX_ID_CHARS = 128
    const val MAX_PHONE_CHARS = 64
    const val MAX_EMAIL_COUNT = 10
    const val MAX_PERMISSION_COUNT = 10
    const val MAX_CALENDAR_RANGE_MS = 366L * 24 * 60 * 60 * 1000

    const val PERM_CONTACTS = "android.permission.READ_CONTACTS"
    const val PERM_CALENDAR = "android.permission.READ_CALENDAR"
    const val PERM_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val PERM_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val PERM_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val PERM_MEDIA_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    /** Pre-33 storage grant (API 30-32 gate media behind this, not READ_MEDIA_*). */
    const val PERM_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"

    /** Runtime permissions this gateway may ever ask about. Nothing else is requested or reported. */
    val REQUESTABLE_PERMISSIONS: Set<String> = setOf(
        PERM_CONTACTS,
        PERM_CALENDAR,
        PERM_MEDIA_IMAGES,
        PERM_MEDIA_VIDEO,
        PERM_MEDIA_AUDIO,
        PERM_MEDIA_SELECTED,
        PERM_STORAGE,
    )

    /**
     * Media classes the caller may actually read (API 33+ selected-photo
     * semantics). The user-selected grant covers images and videos the user
     * picked, never audio.
     */
    fun readableKinds(granted: (String) -> Boolean): Set<String> = buildSet {
        if (granted(PERM_MEDIA_IMAGES) || granted(PERM_MEDIA_SELECTED)) add("image")
        if (granted(PERM_MEDIA_VIDEO) || granted(PERM_MEDIA_SELECTED)) add("video")
        if (granted(PERM_MEDIA_AUDIO)) add("audio")
    }

    /**
     * Missing media permissions for a kind. "any" needs at least one readable
     * class; a named kind needs its own full grant (requesting it also lets
     * the user answer with selected photos on API 34+).
     */
    fun mediaMissingFor(kind: String, granted: (String) -> Boolean): List<String> {
        val readable = readableKinds(granted)
        if (kind == "any" && readable.isNotEmpty()) return emptyList()
        if (kind in readable) return emptyList()
        return when (kind) {
            "image" -> listOf(PERM_MEDIA_IMAGES)
            "video" -> listOf(PERM_MEDIA_VIDEO)
            "audio" -> listOf(PERM_MEDIA_AUDIO)
            else -> listOf(PERM_MEDIA_IMAGES, PERM_MEDIA_VIDEO, PERM_MEDIA_AUDIO)
        }
    }

    /**
     * Which media classes kind=any may query on API 33+: only permitted
     * tables, so image-only or selected-only access never probes video/audio.
     * Pre-33 READ_EXTERNAL_STORAGE covers all three.
     */
    fun anyQueryKinds(readable: Set<String>): List<String> =
        listOf("image", "video", "audio").filter { it in readable }

    val MEDIA_KINDS: Set<String> = setOf("image", "video", "audio", "any")

    val SPECIAL_ACCESS: Set<String> = setOf(
        "overlay", "install_unknown", "all_files", "notification_listener", "write_settings",
    )

    /**
     * Short keys the model may use, mapped to exact Settings actions. Anything
     * not in this map is refused; the gateway never starts an arbitrary action.
     */
    val SYSTEM_SETTINGS: Map<String, String> = mapOf(
        "settings" to "android.settings.SETTINGS",
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "date" to "android.settings.DATE_SETTINGS",
        "locale" to "android.settings.LOCALE_SETTINGS",
        "battery_saver" to "android.settings.BATTERY_SAVER_SETTINGS",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "apps" to "android.settings.APPLICATION_SETTINGS",
        "notifications" to "android.settings.NOTIFICATION_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
    )

    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PHONE_RE = Regex("^[+*#0-9][+*#0-9 ,;\\-]*$")
    private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val ABSOLUTE_RE = Regex("^[A-Za-z]:.*")

    fun rejectUnknownKeys(tool: String, operation: String, args: JsonObject) {
        val allowed = ARGS[tool]?.get(operation)
            ?: throw PolicyException("unknown_operation", "Unknown operation \"$operation\" for \"$tool\".")
        for (key in args.keys) {
            if (key != "operation" && key !in allowed) {
                throw PolicyException("unknown_argument", "Unknown argument \"$key\" for \"$tool.$operation\".")
            }
        }
    }

    fun parseLimit(args: JsonObject): Int {
        val raw = args["limit"] ?: return DEFAULT_LIMIT
        val value = raw.jsonPrimitive.intOrNull
            ?: throw PolicyException("invalid_argument", "\"limit\" must be an integer.")
        if (value < 1) throw PolicyException("invalid_argument", "\"limit\" must be at least 1.")
        return value.coerceIn(1, MAX_LIMIT)
    }

    fun parseQuery(args: JsonObject, key: String = "query", required: Boolean = false): String? {
        val element = args[key]
        if (element == null) {
            if (required) throw PolicyException("invalid_argument", "\"$key\" is required.")
            return null
        }
        if (element !is JsonPrimitive || !element.isString) {
            throw PolicyException("invalid_argument", "\"$key\" must be a string.")
        }
        val raw = element.content.trim()
        if (raw.isEmpty()) {
            if (required) throw PolicyException("invalid_argument", "\"$key\" is required.")
            return null
        }
        if (raw.length > MAX_QUERY_CHARS) {
            throw PolicyException("invalid_argument", "\"$key\" is longer than $MAX_QUERY_CHARS characters.")
        }
        if (raw.contains('\u0000')) throw PolicyException("invalid_argument", "\"$key\" contains NUL.")
        return raw
    }

    fun parseBounded(args: JsonObject, key: String, required: Boolean, max: Int = MAX_STRING_CHARS): String? {
        val element = args[key]
        if (element == null) {
            if (required) throw PolicyException("invalid_argument", "\"$key\" is required.")
            return null
        }
        // Strict: a number or boolean is never silently read as text.
        if (element !is JsonPrimitive || !element.isString) {
            throw PolicyException("invalid_argument", "\"$key\" must be a string.")
        }
        val value = element.content.trim()
        if (required && value.isEmpty()) throw PolicyException("invalid_argument", "\"$key\" is required.")
        if (value.isEmpty()) return null
        if (value.length > max) throw PolicyException("invalid_argument", "\"$key\" is longer than $max characters.")
        if (value.contains('\u0000')) throw PolicyException("invalid_argument", "\"$key\" contains NUL.")
        return value
    }

    fun parseId(args: JsonObject): String {
        val id = parseBounded(args, "id", required = true, max = MAX_ID_CHARS)!!
        if (id.contains('/') || id.contains("..")) {
            throw PolicyException("invalid_argument", "\"id\" is not a valid row id.")
        }
        return id
    }

    fun parsePackage(args: JsonObject, key: String = "package", required: Boolean = true): String? {
        val pkg = parseBounded(args, key, required, MAX_STRING_CHARS) ?: return null
        if (pkg.length !in 3..255 || !pkg.matches(PACKAGE_RE)) {
            throw PolicyException("invalid_argument", "\"$key\" is not a valid package name.")
        }
        return pkg
    }

    private val MEDIASTORE_URI_RE = Regex("^content://([^/]+)(/.*)?$", RegexOption.IGNORE_CASE)

    /**
     * Only MediaStore URIs produced by this gateway (authority "media").
     * Any other content:// authority is rejected before dispatch, so a
     * contacts, calendar or app-provider URI can never reach open/share/info.
     */
    fun parseMediaStoreUri(args: JsonObject): String {
        val uri = parseBounded(args, "uri", required = true, max = MAX_URI_CHARS)!!
        requireMediaStoreUri(uri)
        return uri
    }

    fun requireMediaStoreUri(uri: String) {
        val match = MEDIASTORE_URI_RE.matchEntire(uri)
            ?: throw PolicyException("invalid_uri", "Only content:// URIs are accepted.")
        if (!match.groupValues[1].equals("media", ignoreCase = true)) {
            throw PolicyException(
                "invalid_uri",
                "Only MediaStore URIs from this gateway (content://media/...) are accepted.",
            )
        }
        if (match.groupValues[2].length < 2) {
            throw PolicyException("invalid_uri", "That content URI names no media item.")
        }
    }

    /**
     * Escape LIKE wildcards so a search matches literally. Backslash is the
     * escape character; pair with [LIKE_ESCAPE] in the selection.
     */
    fun likePattern(value: String): String {
        val out = StringBuilder(value.length + 8).append('%')
        for (char in value) {
            if (char == '\\' || char == '%' || char == '_') out.append('\\')
            out.append(char)
        }
        return out.append('%').toString()
    }

    /** Escape clause for selections built with [likePattern]. */
    const val LIKE_ESCAPE = " ESCAPE '\\'"

    fun parsePhone(args: JsonObject, key: String, required: Boolean): String? {
        val value = parseBounded(args, key, required, MAX_PHONE_CHARS) ?: return null
        if (!value.matches(PHONE_RE)) {
            throw PolicyException("invalid_argument", "\"$key\" is not a dialable number.")
        }
        return value
    }

    fun parseEmails(args: JsonObject): List<String> {
        val element = args["to"] ?: return emptyList()
        if (element !is JsonPrimitive || !element.isString) {
            throw PolicyException("invalid_argument", "\"to\" must be a string.")
        }
        val text = element.content.trim()
        if (text.isEmpty()) return emptyList()
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > MAX_EMAIL_COUNT) {
            throw PolicyException("invalid_argument", "Too many recipients (max $MAX_EMAIL_COUNT).")
        }
        for (address in parts) {
            if (address.length > MAX_STRING_CHARS || !address.matches(EMAIL_RE)) {
                throw PolicyException("invalid_argument", "\"$address\" is not a valid email address.")
            }
        }
        return parts
    }

    /**
     * Optional boolean flag. A non-boolean (including the string "true") is
     * rejected instead of silently becoming false.
     */
    fun parseBoolean(args: JsonObject, key: String): Boolean {
        val element = args[key] ?: return false
        if (element !is JsonPrimitive || element.isString || element.booleanOrNull == null) {
            throw PolicyException("invalid_argument", "\"$key\" must be a boolean.")
        }
        return element.boolean
    }

    fun parsePermissionList(args: JsonObject, required: Boolean): List<String> {
        val singleElement = args["permission"]
        val single = if (singleElement == null) {
            null
        } else {
            if (singleElement !is JsonPrimitive || !singleElement.isString) {
                throw PolicyException("invalid_argument", "\"permission\" must be a string.")
            }
            singleElement.content.trim().takeIf { it.isNotEmpty() }
        }
        val raw = args["permissions"]
        val values = when {
            raw is JsonArray -> raw.map { item ->
                if (item !is JsonPrimitive || !item.isString) {
                    throw PolicyException("invalid_argument", "\"permissions\" must be an array of strings.")
                }
                item.content.trim()
            }.filter { it.isNotEmpty() }
            raw == null -> if (single != null) listOf(single) else emptyList()
            else -> throw PolicyException("invalid_argument", "\"permissions\" must be an array of strings.")
        }
        if (values.isEmpty()) {
            if (required) throw PolicyException("invalid_argument", "\"permissions\" is required.")
            return emptyList()
        }
        if (values.size > MAX_PERMISSION_COUNT) {
            throw PolicyException("invalid_argument", "Too many permissions (max $MAX_PERMISSION_COUNT).")
        }
        val unexpected = values.firstOrNull { it !in REQUESTABLE_PERMISSIONS }
        if (unexpected != null) {
            throw PolicyException(
                "permission_not_requestable",
                "\"$unexpected\" is not a capability permission. Requestable: " +
                    REQUESTABLE_PERMISSIONS.sorted().joinToString(", ") + ".",
            )
        }
        return values.distinct()
    }

    /**
     * Resolve a model-supplied path inside the run workspace. Rejects absolute
     * paths, file:// URIs, URI schemes, backslashes, NUL and ".." escape.
     *
     * Fail-closed: an existing path whose links cannot be resolved (IO or
     * permission failure) becomes a typed failure, never a lexical fallback
     * that pretends the path is safe. A not-yet-existing tail is still
     * allowed so new files can be created.
     *
     * Canonical comparison alone does not stop symlink escape everywhere:
     * `File.canonicalFile` leaves `ws\link.txt` unresolved on Windows
     * (verified on this machine: `toRealPath` reaches the outside target
     * while `getCanonicalPath` stays inside). The longest existing prefix is
     * therefore resolved with NIO so a link anywhere in the chain is caught.
     */
    fun resolveWorkspaceFile(workspace: File, relative: String): File {
        checkRelative(relative)
        val base = workspace.toPath().toAbsolutePath().normalize()
        return resolveIn(base, { it.toRealPath() }, relative)
    }

    /** Lexical checks shared by every resolution path. */
    private fun checkRelative(relative: String) {
        if (relative.isBlank()) throw PolicyException("invalid_argument", "Path cannot be blank.")
        if (relative.contains('\u0000')) throw PolicyException("invalid_argument", "Path contains NUL.")
        if (relative.startsWith("/")) throw PolicyException("invalid_argument", "Absolute paths are not allowed.")
        if (relative.matches(ABSOLUTE_RE)) throw PolicyException("invalid_argument", "Absolute paths are not allowed.")
        if (relative.contains("\\")) throw PolicyException("invalid_argument", "Backslashes are not allowed.")
        if (relative.startsWith("file:", ignoreCase = true) || relative.contains("://")) {
            throw PolicyException("invalid_argument", "URIs are not workspace paths.")
        }
        if (relative.split('/').any { it == ".." }) {
            throw PolicyException("invalid_argument", "Path escapes the workspace.")
        }
    }

    /**
     * Test seam: [realPath] stands in for `Path.toRealPath` so IO failures
     * can be simulated without a broken disk.
     */
    internal fun resolveIn(base: Path, realPath: (Path) -> Path, relative: String): File {
        checkRelative(relative)
        val realBase = translate(base, realPath, "workspace")
        // Lexical containment first: stays inside even before links resolve.
        val normalized = base.resolve(relative).normalize()
        if (normalized != base && !normalized.startsWith(base)) {
            throw PolicyException("invalid_argument", "Path escapes the workspace.")
        }
        var prefix = normalized
        val tail = ArrayDeque<String>()
        while (!Files.exists(prefix, LinkOption.NOFOLLOW_LINKS)) {
            val name = prefix.fileName?.toString() ?: break
            tail.addFirst(name)
            prefix = prefix.parent ?: break
        }
        val realPrefix = translate(prefix, realPath, "path")
        var realTarget = realPrefix
        for (segment in tail) realTarget = realTarget.resolve(segment)
        realTarget = realTarget.normalize()
        if (realTarget != realBase && !realTarget.startsWith(realBase)) {
            throw PolicyException("invalid_argument", "Path escapes the workspace.")
        }
        if (realTarget == realBase) throw PolicyException("invalid_argument", "Path must name a file inside the workspace.")
        return realTarget.toFile()
    }

    /** Resolve one link chain, failing closed on IO or permission errors. */
    private fun translate(path: Path, realPath: (Path) -> Path, what: String): Path =
        try {
            realPath(path)
        } catch (e: IOException) {
            throw PolicyException("path_unresolvable", "The $what cannot be resolved safely.")
        } catch (e: SecurityException) {
            throw PolicyException("path_unresolvable", "The $what cannot be resolved safely.")
        }

    /** Workspace root with links resolved, for top-level listing. Fail-closed. */
    fun workspaceRoot(workspace: File): File {
        val base = workspace.toPath().toAbsolutePath().normalize()
        return translate(base, { it.toRealPath() }, "workspace").toFile()
    }

    fun bound(value: String, max: Int = MAX_STRING_CHARS): String =
        if (value.length <= max) value else value.take(max)

    /**
     * Bounded deep copy of one JSON value. Strings are shortened, arrays and
     * objects are capped in size and depth; numbers, booleans and null keep
     * their types. Always returns valid JSON built from the parsed model, so
     * the cap can never cut a serialized string mid-value.
     */
    fun boundElement(element: JsonElement, depth: Int = 0): JsonElement {
        return when (element) {
        is JsonObject -> {
            if (depth >= MAX_BOUND_DEPTH) return buildJsonObject {}
            buildJsonObject {
                var count = 0
                for ((key, value) in element) {
                    if (count >= MAX_OBJECT_KEYS) break
                    put(bound(key, 128), boundElement(value, depth + 1))
                    count++
                }
            }
        }
        is JsonArray -> {
            if (depth >= MAX_BOUND_DEPTH) return JsonArray(emptyList())
            JsonArray(element.take(MAX_ARRAY_ITEMS).map { boundElement(it, depth + 1) })
        }
        is JsonPrimitive -> {
            if (element.isString) JsonPrimitive(bound(element.content, MAX_STRING_CHARS))
            else element
        }
        }
    }

    /**
     * Bounded copy of the caller's arguments, operation included, with JSON
     * types preserved. The retry envelope carries this as-is, so the model
     * can repeat the call unchanged.
     */
    fun retryArguments(args: JsonObject): JsonObject =
        boundElement(args) as JsonObject

    /**
     * Success envelope. Never truncates serialized JSON: when the payload
     * does not fit, the caller gets a small typed overflow instead. List
     * results trim rows before this; single values are pre-bounded.
     */
    fun ok(tool: String, operation: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
        val text = buildJsonObject {
            put("ok", true)
            put("tool", bound(tool, MAX_TOOL_CHARS))
            put("operation", bound(operation, MAX_TOOL_CHARS))
            body()
        }.toString()
        if (text.length <= MAX_OUTPUT_CHARS) return text
        return overflow(tool, operation)
    }

    /** Failure envelope. Same no-truncation rule: oversized extras fall back small. */
    fun fail(
        tool: String,
        operation: String,
        errorType: String,
        message: String,
        extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ): String {
        val text = buildJsonObject {
            put("ok", false)
            put("tool", bound(tool, MAX_TOOL_CHARS))
            put("operation", bound(operation, MAX_TOOL_CHARS))
            put("errorType", errorType)
            put("message", bound(message))
            extra?.invoke(this)
        }.toString()
        if (text.length <= MAX_OUTPUT_CHARS) return text
        return buildJsonObject {
            put("ok", false)
            put("tool", bound(tool, MAX_TOOL_CHARS))
            put("operation", bound(operation, MAX_TOOL_CHARS))
            put("errorType", errorType)
            put("message", bound(message, 200))
        }.toString()
    }

    /** Small typed envelope used when a success payload does not fit the cap. */
    fun overflow(tool: String, operation: String): String = buildJsonObject {
        put("ok", false)
        put("tool", bound(tool, MAX_TOOL_CHARS))
        put("operation", bound(operation, MAX_TOOL_CHARS))
        put("errorType", "output_too_large")
        put("message", "The result does not fit the output cap. Narrow the query or lower the limit.")
    }.toString()

    fun permissionFailure(tool: String, operation: String, missing: List<String>, args: JsonObject): String =
        fail(tool, operation, "permission_denied", missing.joinToString(", ") + " not granted.", {
            put("permission", missing.first())
            put("permissions", JsonArray(missing.map { JsonPrimitive(it) }))
            put(
                "retry",
                buildJsonObject {
                    put("tool", tool)
                    put("arguments", retryArguments(args))
                },
            )
            put(
                "hint",
                "Grant with apps_settings request_permissions, then repeat the retry call unchanged.",
            )
        })

    fun parseMillis(args: JsonObject, key: String): Long? {
        val raw = args[key] ?: return null
        return raw.jsonPrimitive.longOrNull
            ?: throw PolicyException("invalid_argument", "\"$key\" must be epoch milliseconds.")
    }
}
