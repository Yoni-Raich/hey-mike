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
import dev.androidagent.core.DeviceToolGateway
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

    constructor(
        appContext: Context,
        requestRuntimePermissions: suspend (Set<String>) -> Set<String> = { emptySet() },
    ) : this(
        CapabilityDispatcher(
            AndroidCapabilityPlatform(appContext.applicationContext ?: appContext),
            requestRuntimePermissions,
        ),
    )

    /** Test seam: same dispatcher, fake platform, no Android classes touched. */
    internal constructor(
        platform: CapabilityPlatform,
        requestRuntimePermissions: suspend (Set<String>) -> Set<String> = { emptySet() },
    ) : this(CapabilityDispatcher(platform, requestRuntimePermissions))

    override val definitions: List<ToolDefinition> = TOOL_DEFINITIONS

    override fun beginRun(runId: String, workspace: File) = dispatcher.beginRun(runId, workspace)

    override fun revoke() = dispatcher.revoke()

    /**
     * Every tool can open a visible editor/settings screen or write a
     * workspace file, so all of them show the control banner.
     */
    override fun needsControl(name: String): Boolean = true

    override fun statusLine(): String =
        "Capabilities: contacts, calendar, media, messaging drafts, apps and settings (on-device, no ADB needed)"

    /** A local gateway: always ready, but it cannot operate the screen. */
    override fun deviceBackendLive(): Boolean = false

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult =
        dispatcher.invoke(name, arguments)

    override suspend fun cancel() {
        // Provider queries and started intents cannot be interrupted safely.
    }

    companion object {
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
                    "get (id), create_draft (name?, phone?, email?) which opens the contact editor. " +
                    "Reads need READ_CONTACTS. Nothing is written or deleted directly.",
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
                    "the event editor. Reads need READ_CALENDAR. Nothing is written or deleted directly.",
                CapabilityPolicy.CALENDAR_OPS,
                mapOf(
                    "startMs" to int(), "endMs" to int(),
                    "limit" to int(), "id" to str(), "title" to str(),
                    "description" to str(), "location" to str(),
                    "beginMs" to int(), "allDay" to bool(),
                ),
            ),
            tool(
                "files_media",
                "Media and run-workspace files. operation: permission_status, list/search (kind?, name?, limit), " +
                    "info/open/share (uri, content://media/... only), ws_list/ws_read_text/ws_write_text (path, inside the " +
                    "run workspace only). Media reads need a media permission. No file://, no absolute paths, " +
                    "no .. escape, no recursive delete.",
                CapabilityPolicy.FILES_OPS,
                mapOf(
                    "kind" to str(), "name" to str(),
                    "limit" to int(), "uri" to str(), "path" to str(),
                    "text" to str(), "append" to bool(),
                ),
            ),
            tool(
                "communications",
                "Messaging drafts and dialer. operation: draft_sms (to?, body?), draft_email (to?, subject?, body?), " +
                    "dial (number?, opens the dialer, never calls), notification_access_status (reports only " +
                    "whether the listener component is enabled; this app cannot read notifications), " +
                    "open_notification_access_settings. Drafts open a visible editor; nothing is sent directly.",
                CapabilityPolicy.COMM_OPS,
                mapOf(
                    "to" to str(), "body" to str(),
                    "subject" to str(), "number" to str(),
                ),
            ),
            tool(
                "apps_settings",
                "Apps and settings. operation: list_apps (query?, limit, include_system?), app_info/open_app/" +
                    "open_app_settings (package), permission_status/request_permissions (permissions, strict " +
                    "capability allowlist), open_special_access (kind, package?; opens the setup screen only, " +
                    "grants nothing), open_system_setting (setting, " +
                    "strict allowlist). Never runs an arbitrary intent or writes a setting directly.",
                CapabilityPolicy.APPS_OPS,
                mapOf(
                    "query" to str(), "limit" to int(),
                    "include_system" to bool(), "package" to str(),
                    "permissions" to strList(), "permission" to str(),
                    "kind" to str(), "setting" to str(),
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
) {
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
                    put(
                        "note",
                        "Reports only whether the listener component is enabled. This app has " +
                            "no NotificationListenerService and cannot read notifications.",
                    )
                },
            )
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
