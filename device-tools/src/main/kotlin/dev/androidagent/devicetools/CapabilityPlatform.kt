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
}
