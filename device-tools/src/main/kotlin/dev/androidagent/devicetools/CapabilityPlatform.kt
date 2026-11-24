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

/** One contact row. Small on purpose: the result JSON is bounded. */
data class ContactRow(
    val id: String,
    val displayName: String,
    val phone: String? = null,
    val email: String? = null,
)

/** One calendar event row. */
data class EventRow(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long? = null,
    val location: String? = null,
)

/** One media row. The uri is the content:// handle the model can open or share. */
data class MediaRow(
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val dateMs: Long? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    /** Missing metadata is unknown, never a zero-duration success. */
    val metadataStatus: String? = null,
)

/** One installed app row. */
data class AppRow(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val system: Boolean = false,
)

/** Whether a media read is allowed, and what to ask for when it is not. */
data class MediaReadState(
    val granted: Boolean,
    val missing: List<String> = emptyList(),
)

/**
 * Small seam between the gateway dispatcher and Android.
 *
 * Every method does one typed thing: no generic "run this intent", no extras
 * maps, no reflection. Launch methods return false when nothing resolves the
 * request, so the dispatcher can say so instead of crashing. The local JVM
 * tests fake this interface; the real one lives in [AndroidCapabilityPlatform].
 */
interface CapabilityPlatform {
    fun hasPermission(permission: String): Boolean
    fun mediaReadState(kind: String): MediaReadState

    /**
     * The media permissions that matter on this OS version, each with its
     * current grant state. API 33+ reports the READ_MEDIA_* set; API 30-32
     * reports READ_EXTERNAL_STORAGE, which is what those versions enforce.
     */
    fun mediaStatusPermissions(): Map<String, Boolean>

    suspend fun queryContacts(query: String?, limit: Int): List<ContactRow>
    suspend fun getContact(id: String): ContactRow?
    suspend fun queryEvents(startMs: Long, endMs: Long, limit: Int): List<EventRow>
    suspend fun getEvent(id: String): EventRow?
    suspend fun queryMedia(kind: String, nameQuery: String?, limit: Int): List<MediaRow>
    suspend fun mediaInfo(uri: String): MediaRow?

    /** Open a content:// URI for viewing. False when nothing handles it. */
    suspend fun openContentUri(uri: String): Boolean

    /** Share one content:// URI. False when nothing handles it. */
    suspend fun shareContentUri(uri: String): Boolean

    /** Drafts below open a visible editor. The system/user confirms any send or save. */
    suspend fun insertContactDraft(name: String?, phone: String?, email: String?): Boolean
    suspend fun insertEventDraft(
        title: String,
        description: String?,
        location: String?,
        beginMs: Long?,
        endMs: Long?,
        allDay: Boolean,
    ): Boolean
    suspend fun draftSms(to: String?, body: String?): Boolean
    suspend fun draftEmail(to: List<String>, subject: String?, body: String?): Boolean

    /** Opens the dialer. Never places a call. */
    suspend fun dial(number: String?): Boolean

    fun isNotificationListenerEnabled(): Boolean
    suspend fun openNotificationAccessSettings(): Boolean

    suspend fun queryApps(query: String?, limit: Int, includeSystem: Boolean): List<AppRow>
    suspend fun appInfo(packageName: String): AppRow?
    suspend fun launchApp(packageName: String): Boolean
    suspend fun openAppSettings(packageName: String): Boolean
    suspend fun openSpecialAccess(kind: String, packageName: String?): Boolean
    suspend fun openSystemSetting(action: String): Boolean

    // ---- direct reads and writes (added 2026-11-15; defaults keep fakes small) ----

    /** SMS rows from [box] (inbox, sent, all), newest first. Needs READ_SMS. */
    suspend fun querySms(box: String, address: String?, query: String?, limit: Int): List<SmsRow> = emptyList()

    /** Call log rows, newest first. Needs READ_CALL_LOG. */
    suspend fun queryCalls(number: String?, limit: Int): List<CallRow> = emptyList()

    /** Sends a text through the default SIM. Needs SEND_SMS. Returns the part count. */
    suspend fun sendSms(to: String, body: String): Int = throw UnsupportedOperationException("SMS is not available.")

    /** Places a call. Needs CALL_PHONE. False when nothing handles it. */
    suspend fun placeCall(number: String): Boolean = false

    /** Inserts a contact without an editor. Needs WRITE_CONTACTS. Returns the new raw contact id. */
    suspend fun insertContact(name: String, phone: String?, email: String?): String? = null

    /** Inserts an event into the primary writable calendar. Needs WRITE_CALENDAR. Returns the event id. */
    suspend fun insertEvent(
        title: String,
        description: String?,
        location: String?,
        beginMs: Long,
        endMs: Long,
        allDay: Boolean,
        calendarId: String?,
    ): String? = null

    suspend fun deleteEvent(id: String): Boolean = false

    /** Calendars the app can write to. */
    suspend fun writableCalendars(): List<CalendarRow> = emptyList()

    // ---- folders the user granted (Storage Access Framework) ----

    /** Folder trees the user picked, with a lasting grant. */
    suspend fun grantedFolders(): List<FolderRow> = emptyList()

    /** Opens the system folder picker. The user's pick is kept for later runs. */
    suspend fun openFolderPicker(): Boolean = false

    /** Children of [path] ("" = the folder itself) in tree [folder]; null when the path does not exist. */
    suspend fun listFolder(folder: String, path: String, limit: Int): List<DocumentRow>? = null

    /** Copies document [path] into [target]. Returns bytes, or null when it does not exist. */
    suspend fun importDocument(folder: String, path: String, target: java.io.File, maxBytes: Long): Long? = null

    /** Writes [source] to [path]. Returns false when the parent folder does not exist. */
    suspend fun exportDocument(folder: String, path: String, source: java.io.File, mimeType: String): Boolean = false

    /** True when [path] names an existing document. */
    suspend fun documentExists(folder: String, path: String): Boolean = false}

/** One SMS. [type] is inbox, sent, draft, outbox or other. */
data class SmsRow(
    val id: String,
    val address: String?,
    val body: String,
    val dateMs: Long,
    val type: String,
    val read: Boolean,
    val threadId: String?,
)

/** One call log entry. [type] is incoming, outgoing, missed, rejected, blocked, voicemail or other. */
data class CallRow(
    val number: String?,
    val name: String?,
    val type: String,
    val dateMs: Long,
    val durationSec: Long,
)

data class CalendarRow(val id: String, val name: String, val account: String?, val primary: Boolean)

/** A granted folder tree. [uri] is what folder arguments name. */
data class FolderRow(val uri: String, val name: String, val writable: Boolean)

/** One entry inside a granted folder. */
data class DocumentRow(val name: String, val dir: Boolean, val mimeType: String?, val size: Long?, val modifiedMs: Long?)
