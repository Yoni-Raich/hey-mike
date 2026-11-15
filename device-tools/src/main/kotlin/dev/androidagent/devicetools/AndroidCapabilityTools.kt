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

import android.content.Context
import dev.androidagent.core.ActiveNotification
import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.LocalIntentRequest
import dev.androidagent.core.MediaSessionAccess
import dev.androidagent.core.NoNotificationAccess
import dev.androidagent.core.NotificationAccess
import dev.androidagent.core.NotificationOutcome
import dev.androidagent.core.SendRequest
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolNotServiceable
import dev.androidagent.core.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Context-backed gateway for low-latency Android capability APIs. Independent
 * of ADB: contacts, calendar, media, messaging drafts, apps and settings all
 * run through the app process.
 *
 * Five operation-based tools, each dispatched on an "operation" argument:
 * contacts, calendar, files_media, communications, apps_settings. Reads use
 * real provider APIs; anything that sends, saves or opens something visible
 * goes through a system editor or settings screen, so Android (or the user)
 * confirms the final step. There is no second approval layer here.
 *
 * Run scoping matches the other gateways: [beginRun] arms one run id plus
 * workspace, [revoke] flips a volatile flag, and every [invoke] checks it
 * before and after suspending work. [cancel] is a no-op: provider queries and
 * started intents cannot be interrupted safely.
 */
class AndroidCapabilityTools private constructor(
    private val dispatcher: CapabilityDispatcher,
) : DeviceToolGateway {

    /**
     * [authorizeSend] gates anything that leaves the phone (SMS, calls,
     * notification replies); [authorizeChange] gates destructive or raw local
     * changes (settings writes, event deletes, notification action buttons).
     * Both default to refusing, so an unwired gateway can never send or
     * change something without an approval path.
     */
    constructor(
        appContext: Context,
        requestRuntimePermissions: suspend (Set<String>) -> Set<String> = { emptySet() },
        notifications: NotificationAccess = NoNotificationAccess,
        media: MediaSessionAccess = NoNotificationAccess,
        authorizeSend: suspend (SendRequest, suspend () -> ToolResult) -> ToolResult = DENY_SEND,
        authorizeChange: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = DENY_CHANGE,
    ) : this(
        CapabilityDispatcher(
            AndroidCapabilityPlatform(appContext.applicationContext ?: appContext),
            requestRuntimePermissions,
            notifications,
            media,
            AndroidSystemPlatform(appContext.applicationContext ?: appContext),
            authorizeSend,
            authorizeChange,
        ),
    )

    /** Test seam: same dispatcher, fake platform, no Android classes touched. */
    internal constructor(
        platform: CapabilityPlatform,
        requestRuntimePermissions: suspend (Set<String>) -> Set<String> = { emptySet() },
        notifications: NotificationAccess = NoNotificationAccess,
        media: MediaSessionAccess = NoNotificationAccess,
        system: SystemPlatform? = null,
        authorizeSend: suspend (SendRequest, suspend () -> ToolResult) -> ToolResult = DENY_SEND,
        authorizeChange: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = DENY_CHANGE,
    ) : this(
        CapabilityDispatcher(platform, requestRuntimePermissions, notifications, media, system, authorizeSend, authorizeChange),
    )

    override val definitions: List<ToolDefinition> = TOOL_DEFINITIONS

    override fun beginRun(runId: String, workspace: File) = dispatcher.beginRun(runId, workspace)

    override fun revoke() = dispatcher.revoke()

    /** Without arguments, fail safe: each of these tools can launch a screen. */
    override fun needsControl(name: String): Boolean = true

    override fun needsControl(name: String, arguments: JsonObject): Boolean {
        val operation = (arguments["operation"] as? JsonPrimitive)?.contentOrNull?.trim()
        // Keep unknown operations visible until their behavior is classified.
        if (operation !in CapabilityPolicy.operationsFor(name)) return true
        return operation !in when (name) {
            "contacts" -> setOf("permission_status", "search", "list", "get", "create")
            "calendar" -> setOf("permission_status", "list", "get", "create", "delete", "calendars")
            "files_media" -> setOf("permission_status", "list", "search", "info", "ws_list", "ws_read_text", "ws_write_text", "folders", "folder_list", "folder_import", "folder_export")
            // Background ops never take the screen. place_call and
            // notification_press can start an activity, so they stay visible.
            "communications" -> setOf(
                "notification_access_status", "list_notifications", "notification_reply",
                "notification_dismiss", "read_sms", "read_call_log", "send_sms",
            )
            SystemPolicy.TOOL -> SystemPolicy.OPS
            "apps_settings" -> setOf("list_apps", "app_info", "permission_status")
            else -> emptySet()
        }
    }

    override fun statusLine(): String =
        "Capabilities: contacts, calendar, media, notifications, SMS, calls, system controls, apps and settings (on-device, no ADB needed)"

    /** A local gateway: always ready, but it cannot operate the screen. */
    override fun deviceBackendLive(): Boolean = false

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult =
        dispatcher.invoke(name, arguments)

    override suspend fun cancel() {
        // Provider queries and started intents cannot be interrupted safely.
    }

    companion object {
        internal val DENY_SEND: suspend (SendRequest, suspend () -> ToolResult) -> ToolResult = { request, _ ->
            ToolResult(
                CapabilityPolicy.fail(
                    "communications", "send", "approval_unavailable",
                    "No approval path is wired for sends to " + CapabilityPolicy.bound(request.appLabel) + ". Nothing was sent.",
                ),
                success = false,
            )
        }
        internal val DENY_CHANGE: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = { request, _ ->
            ToolResult(
                CapabilityPolicy.fail(
                    "system_controls", "change", "approval_unavailable",
                    "No approval path is wired for: " + CapabilityPolicy.bound(request.action) + ". Nothing was changed.",
                ),
                success = false,
            )
        }

        private fun str(): JsonObject = buildJsonObject { put("type", "string") }
        private fun int(): JsonObject = buildJsonObject { put("type", "integer") }
        private fun bool(): JsonObject = buildJsonObject { put("type", "boolean") }
        private fun strList(): JsonObject = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
        }
        private fun operation(ops: Set<String>): JsonObject = buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(ops.sorted().map { JsonPrimitive(it) }))
        }

        private fun tool(name: String, description: String, operations: Set<String>, properties: Map<String, JsonObject>): ToolDefinition {
            val props = buildJsonObject {
                put("operation", operation(operations))
                for ((key, schema) in properties) put(key, schema)
            }
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", props)
                put("description", description)
                put("required", JsonArray(listOf(JsonPrimitive("operation"))))
                put("additionalProperties", false)
            }
            return ToolDefinition(name, description, schema)
        }

        val TOOL_DEFINITIONS: List<ToolDefinition> = listOf(
            tool(
                "contacts",
                "Phone contacts. operation: permission_status, search (query, limit), list (query?, limit), " +
                    "get (id), create_draft (name?, phone?, email?) which opens the contact editor, " +
                    "create (name, phone?, email?) which saves directly in the background. " +
                    "Reads need READ_CONTACTS, create needs WRITE_CONTACTS.",
                CapabilityPolicy.CONTACT_OPS,
                mapOf(
                    "query" to str(), "limit" to int(),
                    "id" to str(), "name" to str(), "phone" to str(), "email" to str(),
                ),
            ),
            tool(
                "calendar",
                "Calendar events. operation: permission_status, list (startMs?, endMs?, limit), get (id), " +
                    "create_draft (title, description?, location?, beginMs?, endMs?, allDay?) which opens " +
                    "the event editor, create (title, beginMs, endMs?, description?, location?, allDay?, " +
                    "calendarId?) which saves directly, delete (id, asks approval), calendars (writable " +
                    "calendars). Reads need READ_CALENDAR, writes also WRITE_CALENDAR.",
                CapabilityPolicy.CALENDAR_OPS,
                mapOf(
                    "startMs" to int(), "endMs" to int(),
                    "limit" to int(), "id" to str(), "title" to str(),
                    "description" to str(), "location" to str(),
                    "beginMs" to int(), "allDay" to bool(), "calendarId" to str(),
                ),
            ),
            tool(
                "files_media",
                "Media and run-workspace files. operation: permission_status, list/search (kind?, name?, limit), " +
                    "info/open/share (uri, content://media/... only), ws_list/ws_read_text/ws_write_text (path, inside the " +
                    "run workspace only). folders lists folders the user picked; pick_folder opens the system picker; " +
                    "folder_list (folder, path?), folder_import (folder, path, to?) copies into the workspace, " +
                    "folder_export (folder, path, from, overwrite?) writes a workspace file there; replacing asks the user. " +
                    "Media reads need a media permission. No file://, no absolute paths, " +
                    "no .. escape, no recursive delete.",
                CapabilityPolicy.FILES_OPS,
                mapOf(
                    "kind" to str(), "name" to str(),
                    "limit" to int(), "uri" to str(), "path" to str(),
                    "text" to str(), "append" to bool(),
                    "folder" to str(), "to" to str(), "from" to str(), "overwrite" to bool(),
                ),
            ),
            tool(
                "communications",
                "Notifications, SMS, calls and drafts, all without the screen unless noted. operation: " +
                    "list_notifications (package?, limit) reads live notifications with key, text and buttons; " +
                    "notification_reply (key, text, button?) answers through the app's own reply action; " +
                    "notification_press (key, button?; no button = open it, visible); notification_dismiss (key); " +
                    "notification_access_status; open_notification_access_settings; " +
                    "read_sms (box all|inbox|sent, address?, query?, limit); read_call_log (number?, limit); " +
                    "send_sms (to, body) sends directly; place_call (number) calls directly, visible; " +
                    "draft_sms/draft_email (open an editor); dial (opens the dialer). Replies, SMS and calls " +
                    "go through the user's send approval.",
                CapabilityPolicy.COMM_OPS,
                mapOf(
                    "to" to str(), "body" to str(),
                    "subject" to str(), "number" to str(),
                    "package" to str(), "limit" to int(),
                    "key" to str(), "text" to str(), "button" to int(),
                    "box" to str(), "address" to str(), "query" to str(),
                ),
            ),
            tool(
                "apps_settings",
                "Apps and settings. operation: list_apps (query?, limit, include_system?), app_info/open_app/" +
                    "open_app_settings (package), permission_status/request_permissions (permissions, strict " +
                    "capability allowlist), open_special_access (kind, package?; opens the setup screen only, " +
                    "grants nothing; kinds: " + CapabilityPolicy.SPECIAL_ACCESS.sorted().joinToString(", ") +
                    "), open_system_setting (setting, strict allowlist). Settings writes live in system_controls.",
                CapabilityPolicy.APPS_OPS,
                mapOf(
                    "query" to str(), "limit" to int(),
                    "include_system" to bool(), "package" to str(),
                    "permissions" to strList(), "permission" to str(),
                    "kind" to str(), "setting" to str(),
                ),
            ),
            tool(
                SystemPolicy.TOOL,
                "Direct phone controls, in the background. operation: status (volumes, ringer, dnd, " +
                    "brightness, timeout, rotation, torch, grants); torch (on); volume (stream " +
                    "media|ring|notification|alarm|call|system, level? or percent?, show_ui?); ringer (mode " +
                    "normal|vibrate|silent); dnd (mode off|priority|alarms|total); brightness (level 0-255 or " +
                    "percent, auto?); screen_timeout (seconds); auto_rotate (on); clipboard_get; clipboard_set " +
                    "(text); location (precise?, timeout_ms?); media_status; media_control (command " +
                    "play|pause|toggle|next|previous|stop|seek, package?, position_ms?); usage (hours?, limit) " +
                    "app screen time; get_setting/put_setting (namespace system|secure|global, key, value; put " +
                    "asks approval). A grant_missing error names the apps_settings open_special_access kind to open.",
                SystemPolicy.OPS,
                mapOf(
                    "on" to bool(), "stream" to str(), "level" to int(), "percent" to int(),
                    "show_ui" to bool(), "mode" to str(), "auto" to bool(), "seconds" to int(),
                    "text" to str(), "precise" to bool(), "timeout_ms" to int(),
                    "command" to str(), "package" to str(), "position_ms" to int(),
                    "hours" to int(), "limit" to int(),
                    "namespace" to str(), "key" to str(), "value" to str(),
                ),
            ),
        )
    }
}

/**
 * All dispatch logic over a [CapabilityPlatform]. Internal so local JVM tests
 * can drive it with a fake platform without touching Android classes.
 */
internal class CapabilityDispatcher(
    private val platform: CapabilityPlatform,
    private val requestRuntimePermissions: suspend (Set<String>) -> Set<String> = { emptySet() },
    private val notifications: NotificationAccess = NoNotificationAccess,
    media: MediaSessionAccess = NoNotificationAccess,
    systemPlatform: SystemPlatform? = null,
    private val authorizeSend: suspend (SendRequest, suspend () -> ToolResult) -> ToolResult = AndroidCapabilityTools.DENY_SEND,
    private val authorizeChange: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = AndroidCapabilityTools.DENY_CHANGE,
) {
    private val system: SystemControlDispatcher? = systemPlatform?.let {
        SystemControlDispatcher(it, media, { checkActive() }, authorizeChange)
    }

    @Volatile private var revoked = true
    @Volatile private var workspace: File? = null

    fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        this.workspace = workspace.absoluteFile.also { it.mkdirs() }
        revoked = false
    }

    fun revoke() {
        revoked = true
    }

    private fun checkActive() {
        if (revoked) throw IllegalStateException("Run stopped. No device action was performed.")
    }

    suspend fun invoke(tool: String, arguments: JsonObject): ToolResult {
        val ws = workspace
        if (revoked || ws == null) throw IllegalStateException("Run stopped. No device action was performed.")
        checkActive()
        if (tool !in CapabilityPolicy.TOOLS) {
            throw ToolNotServiceable(
                "capability_unsupported",
                "Unknown capability tool \"" + CapabilityPolicy.bound(tool) + "\".",
            )
        }
        // Keep malformed model JSON inside the typed tool contract. Accessing
        // jsonPrimitive directly would throw here (outside the dispatch
        // try/catch) when operation is an object or list.
        val operation = (arguments["operation"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return failure(tool, "?", "missing_operation", "\"operation\" is required.")
        if (operation !in CapabilityPolicy.operationsFor(tool)) {
            return failure(
                tool, operation, "unknown_operation",
                "Unknown operation \"$operation\" for \"$tool\". " +
                    "Use: " + CapabilityPolicy.operationsFor(tool).sorted().joinToString(", ") + ".",
            )
        }
        return try {
            // Unknown keys are rejected before anything with a side effect runs.
            CapabilityPolicy.rejectUnknownKeys(tool, operation, arguments)
            val result = when (tool) {
                CapabilityPolicy.TOOL_CONTACTS -> contacts(tool, operation, arguments)
                CapabilityPolicy.TOOL_CALENDAR -> calendar(tool, operation, arguments)
                CapabilityPolicy.TOOL_FILES_MEDIA -> filesMedia(tool, operation, arguments, ws)
                CapabilityPolicy.TOOL_COMMUNICATIONS -> communications(tool, operation, arguments)
                CapabilityPolicy.TOOL_APPS_SETTINGS -> appsSettings(tool, operation, arguments)
                SystemPolicy.TOOL -> system?.invoke(operation, arguments)
                    ?: failure(tool, operation, "capability_unsupported", "System controls are not available here.")
                else -> throw ToolNotServiceable("capability_unsupported", "Unknown capability tool \"$tool\".")
            }
            checkActive()
            aligned(result)
        } catch (denied: PolicyException) {
            ToolResult(CapabilityPolicy.fail(tool, operation, denied.errorType, denied.message), success = false)
        } catch (cancelled: CancellationException) {
            // Never convert cancellation into a result: the run is stopping.
            throw cancelled
        } catch (bad: IllegalArgumentException) {
            ToolResult(CapabilityPolicy.fail(tool, operation, "invalid_argument", bad.message ?: "Bad argument."), success = false)
        } catch (io: java.io.IOException) {
            ToolResult(CapabilityPolicy.fail(tool, operation, "io_error", io.message ?: "File access failed."), success = false)
        } catch (refused: SecurityException) {
            // A revoked grant or a denied provider read must not escape raw.
            ToolResult(
                CapabilityPolicy.fail(
                    tool,
                    operation,
                    "security_denied",
                    "Android refused the call" +
                        (refused.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".") +
                        " Check permission_status.",
                ),
                success = false,
            )
        }
    }

    // ---- contacts ----

    private suspend fun contacts(tool: String, operation: String, args: JsonObject): ToolResult {
        return when (operation) {
            "permission_status" -> ToolResult(
                CapabilityPolicy.ok(tool, operation) {
                    put("permission", CapabilityPolicy.PERM_CONTACTS)
                    put("granted", platform.hasPermission(CapabilityPolicy.PERM_CONTACTS))
                },
            )
            "search", "list" -> {
                val query = CapabilityPolicy.parseQuery(args, required = operation == "search")
                val limit = CapabilityPolicy.parseLimit(args)
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_CONTACTS)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_CONTACTS), args)
                }
                checkActive()
                val rows = platform.queryContacts(query, limit)
                listResult(tool, operation, limit, rows.map { contactJson(it) })
            }
            "get" -> {
                val id = CapabilityPolicy.parseId(args)
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_CONTACTS)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_CONTACTS), args)
                }
                checkActive()
                val row = platform.getContact(id)
                    ?: return failure(tool, operation, "not_found", "No contact with id \"$id\".")
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("contact", contactJson(row))
                    },
                )
            }
            "create_draft" -> {
                val name = CapabilityPolicy.parseBounded(args, "name", required = false)
                val phone = CapabilityPolicy.parseBounded(args, "phone", required = false)
                val email = CapabilityPolicy.parseBounded(args, "email", required = false)
                if (name == null && phone == null && email == null) {
                    return failure(tool, operation, "invalid_argument", "Give at least one of name, phone, email.")
                }
                checkActive()
                if (!platform.insertContactDraft(name, phone, email)) {
                    return noHandler(tool, operation, "No contact editor is available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("draft", true)
                        put("note", "The contact editor is open. Saving stays with the user.")
                    },
                )
            }
            "create" -> {
                val name = CapabilityPolicy.parseBounded(args, "name", required = true)!!
                val phone = CapabilityPolicy.parsePhone(args, "phone", required = false)
                val email = CapabilityPolicy.parseBounded(args, "email", required = false)
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_WRITE_CONTACTS)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_WRITE_CONTACTS), args)
                }
                val id = platform.insertContact(name, phone, email)
                    ?: return failure(tool, operation, "write_failed", "The contacts provider did not save the contact.")
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("created", true)
                        put("rawContactId", CapabilityPolicy.bound(id))
                    },
                )
            }
            else -> failure(tool, operation, "unknown_operation", "Unknown operation \"$operation\".")
        }
    }

    // ---- calendar ----

    private suspend fun calendar(tool: String, operation: String, args: JsonObject): ToolResult {
        return when (operation) {
            "permission_status" -> ToolResult(
                CapabilityPolicy.ok(tool, operation) {
                    put("permission", CapabilityPolicy.PERM_CALENDAR)
                    put("granted", platform.hasPermission(CapabilityPolicy.PERM_CALENDAR))
                },
            )
            "list" -> {
                val now = System.currentTimeMillis()
                val start = CapabilityPolicy.parseMillis(args, "startMs") ?: now
                val end = CapabilityPolicy.parseMillis(args, "endMs") ?: (start + 7L * 24 * 60 * 60 * 1000)
                if (start < 0 || end < 0 || end < start) {
                    return failure(tool, operation, "invalid_argument", "Need 0 <= startMs <= endMs.")
                }
                if (end - start > CapabilityPolicy.MAX_CALENDAR_RANGE_MS) {
                    return failure(tool, operation, "invalid_argument", "Range is longer than 366 days.")
                }
                val limit = CapabilityPolicy.parseLimit(args)
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_CALENDAR)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_CALENDAR), args)
                }
                checkActive()
                val rows = platform.queryEvents(start, end, limit)
                listResult(tool, operation, limit, rows.map { eventJson(it) })
            }
            "get" -> {
                val id = CapabilityPolicy.parseId(args)
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_CALENDAR)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_CALENDAR), args)
                }
                checkActive()
                val row = platform.getEvent(id)
                    ?: return failure(tool, operation, "not_found", "No event with id \"$id\".")
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("event", eventJson(row))
                    },
                )
            }
            "create_draft" -> {
                val title = CapabilityPolicy.parseBounded(args, "title", required = true)!!
                val description = CapabilityPolicy.parseBounded(args, "description", required = false)
                val location = CapabilityPolicy.parseBounded(args, "location", required = false)
                val begin = CapabilityPolicy.parseMillis(args, "beginMs")
                val end = CapabilityPolicy.parseMillis(args, "endMs")
                if (begin != null && end != null && end < begin) {
                    return failure(tool, operation, "invalid_argument", "Need beginMs <= endMs.")
                }
                val allDay = CapabilityPolicy.parseBoolean(args, "allDay")
                checkActive()
                if (!platform.insertEventDraft(title, description, location, begin, end, allDay)) {
                    return noHandler(tool, operation, "No calendar editor is available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("draft", true)
                        put("note", "The event editor is open. Saving stays with the user.")
                    },
                )
            }
            "calendars" -> {
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_CALENDAR)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_CALENDAR), args)
                }
                val rows = platform.writableCalendars()
                listResult(
                    tool, operation, CapabilityPolicy.MAX_LIMIT,
                    rows.take(CapabilityPolicy.MAX_LIMIT).map {
                        buildJsonObject {
                            put("id", CapabilityPolicy.bound(it.id))
                            put("name", CapabilityPolicy.bound(it.name))
                            it.account?.let { a -> put("account", CapabilityPolicy.bound(a)) }
                            put("primary", it.primary)
                        }
                    },
                )
            }
            "create" -> {
                val title = CapabilityPolicy.parseBounded(args, "title", required = true)!!
                val description = CapabilityPolicy.parseBounded(args, "description", required = false)
                val location = CapabilityPolicy.parseBounded(args, "location", required = false)
                val begin = CapabilityPolicy.parseMillis(args, "beginMs")
                    ?: return failure(tool, operation, "invalid_argument", "\"beginMs\" is required.")
                val allDay = CapabilityPolicy.parseBoolean(args, "allDay")
                val end = CapabilityPolicy.parseMillis(args, "endMs")
                    ?: (begin + if (allDay) 24L * 60 * 60 * 1000 else 60L * 60 * 1000)
                if (begin < 0 || end < begin) {
                    return failure(tool, operation, "invalid_argument", "Need 0 <= beginMs <= endMs.")
                }
                val calendarId = CapabilityPolicy.parseBounded(args, "calendarId", required = false, max = CapabilityPolicy.MAX_ID_CHARS)
                if (calendarId != null && calendarId.toLongOrNull() == null) {
                    return failure(tool, operation, "invalid_argument", "\"calendarId\" must be an id from operation=calendars.")
                }
                checkActive()
                val missing = listOf(CapabilityPolicy.PERM_CALENDAR, CapabilityPolicy.PERM_WRITE_CALENDAR)
                    .filter { !platform.hasPermission(it) }
                if (missing.isNotEmpty()) return permissionFailure(tool, operation, missing, args)
                val id = platform.insertEvent(title, description, location, begin, end, allDay, calendarId)
                    ?: return failure(tool, operation, "write_failed", "No writable calendar accepted the event.")
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("created", true)
                        put("id", CapabilityPolicy.bound(id))
                        put("beginMs", begin)
                        put("endMs", end)
                    },
                )
            }
            "delete" -> {
                val id = CapabilityPolicy.parseId(args)
                checkActive()
                val missing = listOf(CapabilityPolicy.PERM_CALENDAR, CapabilityPolicy.PERM_WRITE_CALENDAR)
                    .filter { !platform.hasPermission(it) }
                if (missing.isNotEmpty()) return permissionFailure(tool, operation, missing, args)
                val event = platform.getEvent(id)
                    ?: return failure(tool, operation, "not_found", "No event with id \"$id\".")
                approveLocal(
                    LocalIntentRequest(
                        action = "calendar delete event $id",
                        uri = null,
                        packageName = null,
                        reason = "Delete the calendar event \"" + CapabilityPolicy.bound(event.title) + "\".",
                    ),
                )?.let { return it }
                checkActive()
                if (!platform.deleteEvent(id)) {
                    return failure(tool, operation, "write_failed", "The calendar provider did not delete the event.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("deleted", true)
                        put("id", CapabilityPolicy.bound(id))
                    },
                )
            }
            else -> failure(tool, operation, "unknown_operation", "Unknown operation \"$operation\".")
        }
    }

    // ---- files and media ----

    private suspend fun filesMedia(tool: String, operation: String, args: JsonObject, ws: File): ToolResult {
        return when (operation) {
            "permission_status" -> ToolResult(
                CapabilityPolicy.ok(tool, operation) {
                    put(
                        "permissions",
                        buildJsonObject {
                            // Platform-relevant set: READ_MEDIA_* on API 33+,
                            // READ_EXTERNAL_STORAGE on API 30-32.
                            for ((name, granted) in platform.mediaStatusPermissions()) {
                                put(name, granted)
                            }
                        },
                    )
                },
            )
            "list", "search" -> {
                val kind = CapabilityPolicy.parseBounded(args, "kind", required = false)
                    ?.lowercase()?.takeIf { it.isNotEmpty() } ?: "any"
                if (kind !in CapabilityPolicy.MEDIA_KINDS) {
                    return failure(
                        tool, operation, "invalid_argument",
                        "Unknown kind \"$kind\". Use: " + CapabilityPolicy.MEDIA_KINDS.sorted().joinToString(", ") + ".",
                    )
                }
                val name = CapabilityPolicy.parseQuery(args, key = "name", required = operation == "search")
                val limit = CapabilityPolicy.parseLimit(args)
                checkActive()
                val state = platform.mediaReadState(kind)
                if (!state.granted) return permissionFailure(tool, operation, state.missing, args)
                checkActive()
                val rows = platform.queryMedia(kind, name, limit)
                listResult(tool, operation, limit, rows.map { mediaJson(it) })
            }
            "info" -> {
                val uri = CapabilityPolicy.parseMediaStoreUri(args)
                checkActive()
                val state = platform.mediaReadState("any")
                if (!state.granted) return permissionFailure(tool, operation, state.missing, args)
                checkActive()
                val row = platform.mediaInfo(uri)
                    ?: return failure(tool, operation, "not_found", "No media found for that URI.")
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("file", mediaJson(row))
                    },
                )
            }
            "open" -> {
                val uri = CapabilityPolicy.parseMediaStoreUri(args)
                checkActive()
                if (!platform.openContentUri(uri)) {
                    return noHandler(tool, operation, "No app can open that URI.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("opened", true)
                    },
                )
            }
            "share" -> {
                val uri = CapabilityPolicy.parseMediaStoreUri(args)
                checkActive()
                if (!platform.shareContentUri(uri)) {
                    return noHandler(tool, operation, "No app can share that URI.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("shared", true)
                        put("note", "The share sheet is open. Sending stays with the user.")
                    },
                )
            }
            "ws_list" -> {
                val relative = CapabilityPolicy.parseBounded(args, "path", required = false) ?: ""
                val dir = if (relative.isEmpty()) CapabilityPolicy.workspaceRoot(ws)
                else CapabilityPolicy.resolveWorkspaceFile(ws, relative)
                val limit = CapabilityPolicy.parseLimit(args)
                if (!dir.isDirectory) return failure(tool, operation, "not_found", "No directory at \"$relative\".")
                // Top level only: no recursion, so one call cannot walk the tree.
                val entries = (dir.listFiles() ?: emptyArray())
                    .sortedBy { it.name }
                    .take(limit)
                    .map {
                        buildJsonObject {
                            put("name", CapabilityPolicy.bound(it.name))
                            put("dir", it.isDirectory)
                            put("size", if (it.isFile) it.length() else 0L)
                        }
                    }
                listResult(tool, operation, limit, entries)
            }
            "ws_read_text" -> {
                val relative = CapabilityPolicy.parseBounded(args, "path", required = true)!!
                val file = CapabilityPolicy.resolveWorkspaceFile(ws, relative)
                if (!file.isFile) return failure(tool, operation, "not_found", "No file at \"$relative\".")
                if (file.length() > CapabilityPolicy.MAX_TEXT_CHARS * 4L) {
                    return failure(tool, operation, "file_too_large", "File is larger than the read cap.")
                }
                val raw = file.readText(Charsets.UTF_8)
                // Read cap fits the output envelope, so ok() never overflows here.
                val truncated = raw.length > CapabilityPolicy.MAX_READ_CHARS
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("path", relative)
                        put("truncated", truncated)
                        put("text", CapabilityPolicy.bound(raw, CapabilityPolicy.MAX_READ_CHARS))
                    },
                )
            }
            "ws_write_text" -> {
                val relative = CapabilityPolicy.parseBounded(args, "path", required = true)!!
                val textElement = args["text"]
                    ?: return failure(tool, operation, "invalid_argument", "\"text\" is required.")
                if (textElement !is JsonPrimitive || !textElement.isString) {
                    return failure(tool, operation, "invalid_argument", "\"text\" must be a string.")
                }
                val raw = textElement.content
                if (raw.contains('\u0000')) {
                    return failure(tool, operation, "invalid_argument", "\"text\" contains NUL.")
                }
                val append = CapabilityPolicy.parseBoolean(args, "append")
                val saved = try {
                    atomicWrite(ws, relative, raw, append)
                } catch (denied: PolicyException) {
                    return failure(tool, operation, denied.errorType, denied.message)
                } catch (io: java.io.IOException) {
                    return failure(tool, operation, "io_error", io.message ?: "Could not save \"$relative\".")
                }
                    ?: return failure(tool, operation, "write_failed", "Atomic replace is unavailable. Nothing was written.")
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("path", relative)
                        put("bytes", saved)
                    },
                )
            }
            "folders", "pick_folder", "folder_list", "folder_import", "folder_export" -> try {
                folderOperation(tool, operation, args, ws)
            } catch (denied: PolicyException) {
                failure(tool, operation, denied.errorType, denied.message)
            } catch (io: java.io.IOException) {
                failure(tool, operation, "io_error", io.message ?: "The folder could not be read or written.")
            } catch (denied: SecurityException) {
                failure(tool, operation, "not_granted", "The grant on that folder is gone. Ask the user to pick it again.")
            }
            else -> failure(tool, operation, "unknown_operation", "Unknown operation \"$operation\".")
        }
    }

    // ---- folders the user picked (Storage Access Framework) ----

    private suspend fun folderOperation(tool: String, operation: String, args: JsonObject, ws: File): ToolResult {
        if (operation == "folders") {
            checkActive()
            val rows = platform.grantedFolders()
            return ToolResult(
                CapabilityPolicy.ok(tool, operation) {
                    put("count", rows.size)
                    put("folders", JsonArray(rows.map { buildJsonObject { put("folder", it.uri); put("name", CapabilityPolicy.bound(it.name)); put("writable", it.writable) } }))
                    if (rows.isEmpty()) put("note", "No folder is picked yet. pick_folder opens the system picker for the user.")
                },
            )
        }
        if (operation == "pick_folder") {
            checkActive()
            if (!platform.openFolderPicker()) return noHandler(tool, operation, "The folder picker could not open.")
            return ToolResult(
                CapabilityPolicy.ok(tool, operation) {
                    put("opened", true)
                    put("note", "The user picks a folder in the system picker. Call folders afterwards to see it.")
                },
            )
        }
        val folder = CapabilityPolicy.parseBounded(args, "folder", required = true)!!
        val path = CapabilityPolicy.parseBounded(args, "path", required = operation != "folder_list") ?: ""
        checkActive()
        return when (operation) {
            "folder_list" -> {
                val limit = CapabilityPolicy.parseLimit(args)
                val rows = platform.listFolder(folder, path, limit)
                    ?: return failure(tool, operation, "not_found", "No folder at \"$path\".")
                listResult(
                    tool, operation, limit,
                    rows.map {
                        buildJsonObject {
                            put("name", CapabilityPolicy.bound(it.name))
                            put("dir", it.dir)
                            it.mimeType?.let { m -> put("mimeType", m) }
                            it.size?.let { s -> put("size", s) }
                            it.modifiedMs?.let { m -> put("modifiedMs", m) }
                        }
                    },
                )
            }
            "folder_import" -> {
                val name = path.substringAfterLast('/')
                val to = CapabilityPolicy.parseBounded(args, "to", required = false) ?: "imports/$name"
                val target = CapabilityPolicy.resolveWorkspaceFile(ws, to)
                val bytes = platform.importDocument(folder, path, target, MAX_FOLDER_FILE_BYTES)
                    ?: return failure(tool, operation, "not_found", "No file at \"$path\".")
                ToolResult(CapabilityPolicy.ok(tool, operation) { put("path", to); put("bytes", bytes) })
            }
            "folder_export" -> {
                val from = CapabilityPolicy.parseBounded(args, "from", required = true)!!
                val source = CapabilityPolicy.resolveWorkspaceFile(ws, from)
                if (!source.isFile) return failure(tool, operation, "not_found", "No workspace file at \"$from\".")
                if (source.length() > MAX_FOLDER_FILE_BYTES) return failure(tool, operation, "too_large", "The file is over 50 MB.")
                if (platform.documentExists(folder, path)) {
                    if (!CapabilityPolicy.parseBoolean(args, "overwrite")) {
                        return failure(tool, operation, "exists", "\"$path\" already exists. Pass overwrite=true to replace it (the user is asked).")
                    }
                    approveLocal(
                        LocalIntentRequest(
                            action = "replace file $path",
                            uri = null,
                            packageName = null,
                            reason = "Replace \"" + CapabilityPolicy.bound(path) + "\" in a folder you picked.",
                        ),
                    )?.let { return it }
                    checkActive()
                }
                val mime = java.net.URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"
                if (!platform.exportDocument(folder, path, source, mime)) {
                    return failure(tool, operation, "not_found", "The parent folder of \"$path\" does not exist.")
                }
                ToolResult(CapabilityPolicy.ok(tool, operation) { put("path", path); put("bytes", source.length()) })
            }
            else -> failure(tool, operation, "unknown_operation", "Unknown operation \"$operation\".")
        }
    }

    // ---- communications ----

    private suspend fun communications(tool: String, operation: String, args: JsonObject): ToolResult {
        return when (operation) {
            "draft_sms" -> {
                val to = CapabilityPolicy.parsePhone(args, "to", required = false)
                val body = CapabilityPolicy.parseBounded(args, "body", required = false)
                if (to == null && body == null) {
                    return failure(tool, operation, "invalid_argument", "Give at least one of to, body.")
                }
                checkActive()
                if (!platform.draftSms(to, body)) {
                    return noHandler(tool, operation, "No messaging app is available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("draft", true)
                        put("note", "The message editor is open. Sending stays with the user.")
                    },
                )
            }
            "draft_email" -> {
                val to = CapabilityPolicy.parseEmails(args)
                val subject = CapabilityPolicy.parseBounded(args, "subject", required = false)
                val body = CapabilityPolicy.parseBounded(args, "body", required = false)
                if (to.isEmpty() && subject == null && body == null) {
                    return failure(tool, operation, "invalid_argument", "Give at least one of to, subject, body.")
                }
                checkActive()
                if (!platform.draftEmail(to, subject, body)) {
                    return noHandler(tool, operation, "No email app is available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("draft", true)
                        put("note", "The email editor is open. Sending stays with the user.")
                    },
                )
            }
            "dial" -> {
                val number = CapabilityPolicy.parsePhone(args, "number", required = false)
                checkActive()
                // ACTION_DIAL only: the platform opens the dialer and never calls.
                if (!platform.dial(number)) {
                    return noHandler(tool, operation, "No dialer is available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("dialer", true)
                        put("note", "The dialer is open. Placing the call stays with the user.")
                    },
                )
            }
            "notification_access_status" -> ToolResult(
                CapabilityPolicy.ok(tool, operation) {
                    put("enabled", platform.isNotificationListenerEnabled())
                    put("connected", notifications.connected())
                    put(
                        "note",
                        "When enabled and connected, list_notifications reads the live " +
                            "notifications and notification_reply/press/dismiss act on them.",
                    )
                },
            )
            "list_notifications" -> {
                val pkg = CapabilityPolicy.parsePackage(args, required = false)
                val limit = CapabilityPolicy.parseLimit(args)
                notificationGate(tool, operation)?.let { return it }
                checkActive()
                val rows = notifications.active(pkg, limit)
                listResult(tool, operation, limit, rows.map { notificationJson(it) })
            }
            "notification_reply" -> {
                val key = parseNotificationKey(args)
                val text = CapabilityPolicy.parseBounded(args, "text", required = true, max = MAX_MESSAGE_CHARS)!!
                val button = parseButton(args)
                notificationGate(tool, operation)?.let { return it }
                val target = notifications.find(key)
                    ?: return failure(tool, operation, "not_found", "No live notification with that key. List again.")
                if (target.replyButton == null) {
                    return failure(tool, operation, "no_reply_action", "That notification has no reply action. Open the app instead.")
                }
                checkActive()
                authorizeSend(
                    SendRequest(
                        packageName = target.packageName,
                        appLabel = appLabel(target.packageName),
                        recipient = target.title,
                        message = text,
                    ),
                ) {
                    checkActive()
                    outcome(tool, operation, notifications.reply(key, button, text)) {
                        put("sent", true)
                        put("note", "The reply was handed to the app. Its own delivery state is not visible here.")
                    }
                }
            }
            "notification_press" -> {
                val key = parseNotificationKey(args)
                val button = parseButton(args)
                notificationGate(tool, operation)?.let { return it }
                val target = notifications.find(key)
                    ?: return failure(tool, operation, "not_found", "No live notification with that key. List again.")
                if (button != null) {
                    val action = target.buttons.firstOrNull { it.index == button }
                        ?: return failure(tool, operation, "invalid_argument", "No button $button on that notification.")
                    // An action button can do anything the app wired to it
                    // (delete, pay, mark read), so it goes through approval.
                    approveLocal(
                        LocalIntentRequest(
                            action = "notification action \"" + CapabilityPolicy.bound(action.title, 80) + "\"",
                            uri = null,
                            packageName = target.packageName,
                            reason = "Press \"" + CapabilityPolicy.bound(action.title, 80) + "\" on a " +
                                appLabel(target.packageName) + " notification.",
                        ),
                    )?.let { return it }
                }
                checkActive()
                outcome(tool, operation, notifications.press(key, button)) {
                    put("pressed", true)
                }
            }
            "notification_dismiss" -> {
                val key = parseNotificationKey(args)
                notificationGate(tool, operation)?.let { return it }
                checkActive()
                outcome(tool, operation, notifications.dismiss(key)) {
                    put("dismissed", true)
                }
            }
            "read_sms" -> {
                val box = (CapabilityPolicy.parseBounded(args, "box", required = false) ?: "all").lowercase()
                if (box !in SMS_BOXES) {
                    return failure(tool, operation, "invalid_argument", "Unknown box \"$box\". Use: all, inbox, sent.")
                }
                val address = CapabilityPolicy.parseQuery(args, key = "address")
                val query = CapabilityPolicy.parseQuery(args)
                val limit = CapabilityPolicy.parseLimit(args)
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_READ_SMS)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_READ_SMS), args)
                }
                val rows = platform.querySms(box, address, query, limit)
                listResult(
                    tool, operation, limit,
                    rows.map {
                        buildJsonObject {
                            put("id", CapabilityPolicy.bound(it.id))
                            it.address?.let { a -> put("address", CapabilityPolicy.bound(a)) }
                            put("body", CapabilityPolicy.bound(it.body, MAX_MESSAGE_CHARS))
                            put("dateMs", it.dateMs)
                            put("type", it.type)
                            put("read", it.read)
                            it.threadId?.let { t -> put("threadId", CapabilityPolicy.bound(t)) }
                        }
                    },
                )
            }
            "read_call_log" -> {
                val number = CapabilityPolicy.parseQuery(args, key = "number")
                val limit = CapabilityPolicy.parseLimit(args)
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_READ_CALL_LOG)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_READ_CALL_LOG), args)
                }
                val rows = platform.queryCalls(number, limit)
                listResult(
                    tool, operation, limit,
                    rows.map {
                        buildJsonObject {
                            it.number?.let { n -> put("number", CapabilityPolicy.bound(n)) }
                            it.name?.let { n -> put("name", CapabilityPolicy.bound(n)) }
                            put("type", it.type)
                            put("dateMs", it.dateMs)
                            put("durationSec", it.durationSec)
                        }
                    },
                )
            }
            "send_sms" -> {
                val to = CapabilityPolicy.parsePhone(args, "to", required = true)!!
                val body = CapabilityPolicy.parseBounded(args, "body", required = true, max = MAX_MESSAGE_CHARS)!!
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_SEND_SMS)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_SEND_SMS), args)
                }
                authorizeSend(SendRequest(SMS_PACKAGE, "SMS", to, body)) {
                    checkActive()
                    val parts = platform.sendSms(to, body)
                    ToolResult(
                        CapabilityPolicy.ok(tool, operation) {
                            put("sent", true)
                            put("parts", parts)
                            put("note", "Handed to the phone's SMS service. Carrier delivery is not confirmed here.")
                        },
                    )
                }
            }
            "place_call" -> {
                val number = CapabilityPolicy.parsePhone(args, "number", required = true)!!
                checkActive()
                if (!platform.hasPermission(CapabilityPolicy.PERM_CALL_PHONE)) {
                    return permissionFailure(tool, operation, listOf(CapabilityPolicy.PERM_CALL_PHONE), args)
                }
                authorizeSend(SendRequest(CALL_PACKAGE, "Phone call", number, null)) {
                    checkActive()
                    if (!platform.placeCall(number)) {
                        noHandler(tool, operation, "No app can place the call.")
                    } else {
                        ToolResult(
                            CapabilityPolicy.ok(tool, operation) {
                                put("calling", true)
                                put("note", "The call was started. Use the screen tools to hang up.")
                            },
                        )
                    }
                }
            }
            "open_notification_access_settings" -> {
                checkActive()
                if (!platform.openNotificationAccessSettings()) {
                    return noHandler(tool, operation, "The notification settings screen is not available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("opened", true)
                    },
                )
            }
            else -> failure(tool, operation, "unknown_operation", "Unknown operation \"$operation\".")
        }
    }

    // ---- apps and settings ----

    private suspend fun appsSettings(tool: String, operation: String, args: JsonObject): ToolResult {
        return when (operation) {
            "list_apps" -> {
                val query = CapabilityPolicy.parseQuery(args, required = false)?.lowercase()
                val limit = CapabilityPolicy.parseLimit(args)
                val includeSystem = CapabilityPolicy.parseBoolean(args, "include_system")
                checkActive()
                val rows = platform.queryApps(query, limit, includeSystem)
                listResult(tool, operation, limit, rows.map { appJson(it) })
            }
            "app_info" -> {
                val pkg = CapabilityPolicy.parsePackage(args)!!
                checkActive()
                val row = platform.appInfo(pkg)
                    ?: return failure(tool, operation, "not_found", "No app \"$pkg\" is installed.")
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("app", appJson(row))
                    },
                )
            }
            "open_app" -> {
                val pkg = CapabilityPolicy.parsePackage(args)!!
                checkActive()
                if (!platform.launchApp(pkg)) {
                    return failure(tool, operation, "not_found", "No launchable app \"$pkg\".")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("opened", true)
                    },
                )
            }
            "open_app_settings" -> {
                val pkg = CapabilityPolicy.parsePackage(args)!!
                checkActive()
                if (!platform.openAppSettings(pkg)) {
                    return noHandler(tool, operation, "The app settings screen is not available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("opened", true)
                    },
                )
            }
            "permission_status" -> {
                val wanted = CapabilityPolicy.parsePermissionList(args, required = false)
                val names = wanted.ifEmpty { CapabilityPolicy.REQUESTABLE_PERMISSIONS.sorted() }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put(
                            "permissions",
                            buildJsonObject {
                                for (name in names) put(name, platform.hasPermission(name))
                            },
                        )
                    },
                )
            }
            "request_permissions" -> {
                // Validated before the callback, and only missing permissions
                // reach it: at most one call, never a loop, never a repeat ask.
                val wanted = CapabilityPolicy.parsePermissionList(args, required = true)
                checkActive()
                val missing = wanted.filter { !platform.hasPermission(it) }
                val grantedNow = if (missing.isEmpty()) {
                    emptySet()
                } else {
                    requestRuntimePermissions(missing.toSet())
                }
                checkActive()
                val granted = wanted.filter { platform.hasPermission(it) || it in grantedNow }
                val denied = wanted.filter { it !in granted }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("granted", JsonArray(granted.map { JsonPrimitive(it) }))
                        put("denied", JsonArray(denied.map { JsonPrimitive(it) }))
                    },
                )
            }
            "open_special_access" -> {
                val kind = CapabilityPolicy.parseBounded(args, "kind", required = true)!!
                if (kind !in CapabilityPolicy.SPECIAL_ACCESS) {
                    return failure(
                        tool, operation, "invalid_argument",
                        "Unknown access \"$kind\". Use: " + CapabilityPolicy.SPECIAL_ACCESS.sorted().joinToString(", ") + ".",
                    )
                }
                val pkg = CapabilityPolicy.parsePackage(args, key = "package", required = false)
                checkActive()
                if (!platform.openSpecialAccess(kind, pkg)) {
                    return noHandler(tool, operation, "That access screen is not available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("opened", true)
                        put(
                            "note",
                            "This only opens the setup screen. It grants nothing and does not " +
                                "prove the app declared or uses that access.",
                        )
                    },
                )
            }
            "open_system_setting" -> {
                val key = CapabilityPolicy.parseBounded(args, "setting", required = true)!!
                val action = CapabilityPolicy.SYSTEM_SETTINGS[key]
                    ?: return failure(
                        tool, operation, "invalid_setting",
                        "Unknown setting \"$key\". Use: " + CapabilityPolicy.SYSTEM_SETTINGS.keys.sorted().joinToString(", ") + ".",
                    )
                checkActive()
                if (!platform.openSystemSetting(action)) {
                    return noHandler(tool, operation, "That settings screen is not available.")
                }
                ToolResult(
                    CapabilityPolicy.ok(tool, operation) {
                        put("opened", true)
                    },
                )
            }
            else -> failure(tool, operation, "unknown_operation", "Unknown operation \"$operation\".")
        }
    }

    // ---- notification and approval helpers ----

    private companion object {
        const val MAX_MESSAGE_CHARS = 4_000
        const val MAX_FOLDER_FILE_BYTES = 50L * 1024 * 1024
        val SMS_BOXES = setOf("all", "inbox", "sent")
        /** Pseudo packages for send grants: SMS and calls are not tied to one app here. */
        const val SMS_PACKAGE = "android.telephony.sms"
        const val CALL_PACKAGE = "android.telephony.call"
    }

    private fun notificationGate(tool: String, operation: String): ToolResult? = when {
        !notifications.granted() -> ToolResult(
            CapabilityPolicy.fail(
                tool, operation, "notification_access_missing",
                "Notification access is off. Run communications operation=open_notification_access_settings " +
                    "and ask the user to enable Hey Mike.",
            ),
            success = false,
        )
        !notifications.connected() -> failure(
            tool, operation, "listener_unavailable",
            "Notification access is on but the listener is not connected yet. Try again in a few seconds.",
        )
        else -> null
    }

    private fun parseNotificationKey(args: JsonObject): String =
        CapabilityPolicy.parseBounded(args, "key", required = true, max = CapabilityPolicy.MAX_STRING_CHARS)!!

    private fun parseButton(args: JsonObject): Int? {
        val raw = args["button"] ?: return null
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: throw PolicyException("invalid_argument", "\"button\" must be an integer index.")
        if (value !in 0..15) throw PolicyException("invalid_argument", "\"button\" must be in 0..15.")
        return value
    }

    private suspend fun appLabel(packageName: String): String =
        runCatching { platform.appInfo(packageName)?.label }.getOrNull() ?: packageName

    /**
     * Runs [authorizeChange] and returns null only when it approved. The
     * gate's dispatch is not suspending, so the real work happens after.
     */
    private suspend fun approveLocal(request: LocalIntentRequest): ToolResult? {
        var approved = false
        val gate = authorizeChange(request) {
            approved = true
            ToolResult("{\"ok\":true}")
        }
        return if (approved) null else gate
    }

    private fun outcome(
        tool: String,
        operation: String,
        result: NotificationOutcome,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): ToolResult = when (result) {
        NotificationOutcome.Done -> ToolResult(CapabilityPolicy.ok(tool, operation, body))
        is NotificationOutcome.Failed -> failure(tool, operation, result.errorType, result.message)
    }

    private fun notificationJson(row: ActiveNotification): JsonObject = buildJsonObject {
        put("key", CapabilityPolicy.bound(row.key))
        put("package", CapabilityPolicy.bound(row.packageName))
        if (row.title.isNotEmpty()) put("title", CapabilityPolicy.bound(row.title))
        if (row.text.isNotEmpty()) put("text", CapabilityPolicy.bound(row.text, MAX_MESSAGE_CHARS))
        row.subText?.let { put("subText", CapabilityPolicy.bound(it)) }
        put("postedAtMs", row.postedAtMs)
        row.category?.let { put("category", CapabilityPolicy.bound(it)) }
        put("ongoing", row.ongoing)
        put("clearable", row.clearable)
        if (row.buttons.isNotEmpty()) {
            put(
                "buttons",
                JsonArray(
                    row.buttons.map {
                        buildJsonObject {
                            put("index", it.index)
                            put("title", CapabilityPolicy.bound(it.title, 80))
                            if (it.acceptsText) put("reply", true)
                        }
                    },
                ),
            )
        }
    }

    // ---- result helpers ----

    private fun failure(tool: String, operation: String, errorType: String, message: String): ToolResult =
        ToolResult(CapabilityPolicy.fail(tool, operation, errorType, message), success = false)

    private fun permissionFailure(tool: String, operation: String, missing: List<String>, args: JsonObject): ToolResult =
        ToolResult(CapabilityPolicy.permissionFailure(tool, operation, missing, args), success = false)

    private fun noHandler(tool: String, operation: String, message: String): ToolResult =
        failure(tool, operation, "no_handler", "$message Nothing was sent or created.")

    /**
     * Success flag derived from the envelope, never assumed. ok() falls back
     * to a typed `{"ok":false}` overflow envelope when a payload does not
     * fit, and this single choke point keeps ToolResult.success from ever
     * contradicting the JSON the model reads.
     */
    private fun aligned(result: ToolResult): ToolResult {
        val okFlag = runCatching {
            Json.parseToJsonElement(result.text).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull
        }.getOrNull() ?: return result.copy(success = false)
        return if (okFlag == result.success) result else result.copy(success = okFlag)
    }

    /**
     * Replace-or-append through a same-directory temp file plus an atomic
     * move. The cap applies to the final text, not to the chunk: an append
     * that would push the file over the cap writes nothing. Containment is
     * re-resolved after parent creation and again just before the move, so a
     * link swapped in between fails closed. Returns the saved byte count, or
     * null when no atomic move is available — then nothing is written and the
     * caller reports write_failed instead of falling back to a torn write.
     */
    private fun atomicWrite(ws: File, relative: String, raw: String, append: Boolean): Long? {
        val target = CapabilityPolicy.resolveWorkspaceFile(ws, relative)
        val parent = target.parentFile!!
        parent.mkdirs()
        // Revalidate: creating parents can materialize a swapped link.
        if (CapabilityPolicy.resolveWorkspaceFile(ws, relative) != target) {
            throw PolicyException("invalid_argument", "Path escapes the workspace.")
        }
        if (target.isDirectory) throw PolicyException("invalid_argument", "Path names a directory.")
        val current = if (append && target.isFile) {
            val existing = target.readText(Charsets.UTF_8)
            if (existing.length > CapabilityPolicy.MAX_TEXT_CHARS) {
                throw PolicyException("file_too_large", "Existing file is larger than the write cap.")
            }
            existing
        } else {
            ""
        }
        val final = current + raw
        if (final.length > CapabilityPolicy.MAX_TEXT_CHARS) {
            throw PolicyException("file_too_large", "Final text is larger than the write cap. Nothing was written.")
        }
        val tmp = java.nio.file.Files.createTempFile(parent.toPath(), ".cap", ".tmp")
        try {
            java.nio.file.Files.write(tmp, final.toByteArray(Charsets.UTF_8))
            // Revalidate immediately before the replace.
            if (CapabilityPolicy.resolveWorkspaceFile(ws, relative) != target) {
                throw PolicyException("invalid_argument", "Path escapes the workspace.")
            }
            try {
                java.nio.file.Files.move(
                    tmp,
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                return null
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
        return target.length()
    }

    private fun contactJson(row: ContactRow): JsonObject = buildJsonObject {
        put("id", CapabilityPolicy.bound(row.id))
        put("name", CapabilityPolicy.bound(row.displayName))
        row.phone?.let { put("phone", CapabilityPolicy.bound(it)) }
        row.email?.let { put("email", CapabilityPolicy.bound(it)) }
    }

    private fun eventJson(row: EventRow): JsonObject = buildJsonObject {
        put("id", CapabilityPolicy.bound(row.id))
        put("title", CapabilityPolicy.bound(row.title))
        put("startMs", row.startMs)
        row.endMs?.let { put("endMs", it) }
        row.location?.let { put("location", CapabilityPolicy.bound(it)) }
    }

    private fun mediaJson(row: MediaRow): JsonObject = buildJsonObject {
        put("uri", CapabilityPolicy.bound(row.uri, CapabilityPolicy.MAX_URI_CHARS))
        put("name", CapabilityPolicy.bound(row.displayName))
        row.mimeType?.let { put("mimeType", CapabilityPolicy.bound(it)) }
        row.sizeBytes?.let { put("sizeBytes", it) }
        row.dateMs?.let { put("dateMs", it) }
        row.durationMs?.let { put("durationMs", it) }
        row.width?.let { put("width", it) }
        row.height?.let { put("height", it) }
        row.rotationDegrees?.let { put("rotationDegrees", it) }
        row.metadataStatus?.let { put("metadataStatus", it) }
    }

    private fun appJson(row: AppRow): JsonObject = buildJsonObject {
        put("package", CapabilityPolicy.bound(row.packageName))
        put("label", CapabilityPolicy.bound(row.label))
        row.versionName?.let { put("versionName", CapabilityPolicy.bound(it)) }
        row.versionCode?.let { put("versionCode", it) }
        put("system", row.system)
    }

    /**
     * Bounded list envelope that stays valid JSON: rows are pre-bounded, and
     * when the serialized form still exceeds the cap the tail is dropped with
     * truncated:true rather than cutting the string mid-object. Raw JSON is
     * measured directly because ok() already collapses oversized payloads.
     */
    private fun listResult(tool: String, operation: String, limit: Int, items: List<JsonObject>): ToolResult {
        var shown = items
        var truncated = false
        var text = renderListRaw(tool, operation, limit, shown, truncated = false)
        while (text.length > CapabilityPolicy.MAX_OUTPUT_CHARS && shown.size > 1) {
            shown = shown.dropLast(1)
            truncated = true
            text = renderListRaw(tool, operation, limit, shown, truncated = true)
        }
        if (text.length > CapabilityPolicy.MAX_OUTPUT_CHARS) {
            return failure(tool, operation, "output_too_large", "The result does not fit the output cap.")
        }
        return ToolResult(text)
    }

    private fun renderListRaw(
        tool: String,
        operation: String,
        limit: Int,
        shown: List<JsonObject>,
        truncated: Boolean,
    ): String = buildJsonObject {
        put("ok", true)
        put("tool", tool)
        put("operation", operation)
        put("count", shown.size)
        put("limit", limit)
        if (truncated) put("truncated", true)
        put("items", JsonArray(shown))
    }.toString()
}
