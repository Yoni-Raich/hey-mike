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

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real [CapabilityPlatform] over app Context, ContentResolver and PackageManager.
 *
 * Every visible intent goes out with FLAG_ACTIVITY_NEW_TASK from the
 * application Context and only after resolveActivity says something handles
 * it. Extras are fixed keys with dispatcher-bounded strings: model JSON never
 * becomes an arbitrary intent. Reads use ContactsContract, CalendarContract,
 * MediaStore and PackageManager directly.
 */
class AndroidCapabilityPlatform(
    context: Context,
) : CapabilityPlatform {
    private val context: Context = context.applicationContext ?: context

    override fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    override fun mediaReadState(kind: String): MediaReadState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // API 30-32 gates media behind READ_EXTERNAL_STORAGE, not the
            // READ_MEDIA_* set (which does not exist there). Report and
            // request the storage grant on those versions.
            if (hasPermission(CapabilityPolicy.PERM_STORAGE)) {
                return MediaReadState(granted = true)
            }
            return MediaReadState(granted = false, missing = listOf(CapabilityPolicy.PERM_STORAGE))
        }
        val missing = CapabilityPolicy.mediaMissingFor(kind, ::hasPermission)
        return MediaReadState(granted = missing.isEmpty(), missing = missing)
    }

    override fun mediaStatusPermissions(): Map<String, Boolean> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            mapOf(CapabilityPolicy.PERM_STORAGE to hasPermission(CapabilityPolicy.PERM_STORAGE))
        } else {
            listOf(
                CapabilityPolicy.PERM_MEDIA_IMAGES,
                CapabilityPolicy.PERM_MEDIA_VIDEO,
                CapabilityPolicy.PERM_MEDIA_AUDIO,
                CapabilityPolicy.PERM_MEDIA_SELECTED,
            ).associateWith(::hasPermission)
        }

    // ---- contacts ----

    override suspend fun queryContacts(query: String?, limit: Int): List<ContactRow> = withContext(Dispatchers.IO) {
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        val selection = if (query != null) {
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?" + CapabilityPolicy.LIKE_ESCAPE
        } else {
            null
        }
        val args = if (query != null) arrayOf(CapabilityPolicy.likePattern(query)) else null
        val rows = mutableListOf<ContactRow>()
        context.contentResolver.query(
            uri, projection, selection, args,
            // No SQL LIMIT suffix: providers differ in what they accept, and
            // the cursor loop below enforces the bound.
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext() && rows.size < limit) {
                rows += ContactRow(
                    id = cursor.getString(idCol) ?: continue,
                    displayName = cursor.getString(nameCol) ?: "(no name)",
                )
            }
        }
        rows
    }

    override suspend fun getContact(id: String): ContactRow? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id.toLongOrNull() ?: return@withContext null)
        var name: String? = null
        val found = resolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                true
            } else {
                false
            }
        } ?: false
        // An empty cursor means no such contact: never invent one.
        if (!found) return@withContext null
        var phone: String? = null
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id), null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) phone = cursor.getString(0)
        }
        var email: String? = null
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(id), null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) email = cursor.getString(0)
        }
        ContactRow(id = id, displayName = name ?: "(no name)", phone = phone, email = email)
    }

    // ---- calendar ----

    override suspend fun queryEvents(startMs: Long, endMs: Long, limit: Int): List<EventRow> =
        withContext(Dispatchers.IO) {
            // Instances expands recurring events into occurrences inside the
            // range. A plain Events overlap query returns the series row only
            // when it overlaps, hiding every detached occurrence.
            val range = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(range, startMs)
            ContentUris.appendId(range, endMs)
            val rows = mutableListOf<EventRow>()
            context.contentResolver.query(
                range.build(),
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val startCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val locCol = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                while (cursor.moveToNext() && rows.size < limit) {
                    rows += EventRow(
                        id = cursor.getLong(idCol).toString(),
                        title = cursor.getString(titleCol) ?: "(no title)",
                        startMs = cursor.getLong(startCol),
                        endMs = if (cursor.isNull(endCol)) null else cursor.getLong(endCol),
                        location = cursor.getString(locCol),
                    )
                }
            }
            rows
        }

    override suspend fun getEvent(id: String): EventRow? = withContext(Dispatchers.IO) {
        val numeric = id.toLongOrNull() ?: return@withContext null
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, numeric)
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
            ),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            EventRow(
                id = id,
                title = cursor.getString(1) ?: "(no title)",
                startMs = cursor.getLong(2),
                endMs = if (cursor.isNull(3)) null else cursor.getLong(3),
                location = cursor.getString(4),
            )
        }
    }

    // ---- media ----

    private data class MediaTable(val uri: Uri, val kind: String)

    private fun mediaTables(kind: String): List<MediaTable> {
        val images = MediaTable(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")
        val video = MediaTable(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
        val audio = MediaTable(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")
        val wanted = when (kind) {
            "image" -> listOf(images)
            "video" -> listOf(video)
            "audio" -> listOf(audio)
            else -> listOf(images, video, audio)
        }
        if (kind != "any" || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return wanted
        // kind=any on API 33+: query only permitted tables, so image-only or
        // selected-only access never probes video/audio and throws.
        val readable = CapabilityPolicy.anyQueryKinds(CapabilityPolicy.readableKinds(::hasPermission)).toSet()
        return wanted.filter { it.kind in readable }
    }

    override suspend fun queryMedia(kind: String, nameQuery: String?, limit: Int): List<MediaRow> =
        withContext(Dispatchers.IO) {
            val rows = mutableListOf<MediaRow>()
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            val selection = if (nameQuery != null) {
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" + CapabilityPolicy.LIKE_ESCAPE
            } else {
                null
            }
            val args = if (nameQuery != null) arrayOf(CapabilityPolicy.likePattern(nameQuery)) else null
            for (table in mediaTables(kind)) {
                if (rows.size >= limit) break
                context.contentResolver.query(
                    table.uri, projection, selection, args,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext() && rows.size < limit) {
                        val contentUri = ContentUris.withAppendedId(table.uri, cursor.getLong(idCol))
                        rows += MediaRow(
                            uri = contentUri.toString(),
                            displayName = cursor.getString(nameCol) ?: "(no name)",
                            mimeType = cursor.getString(mimeCol),
                            sizeBytes = if (cursor.isNull(sizeCol)) null else cursor.getLong(sizeCol),
                            dateMs = if (cursor.isNull(dateCol)) null else cursor.getLong(dateCol) * 1000,
                        )
                    }
                }
            }
            rows.sortedByDescending { it.dateMs ?: 0 }.take(limit)
        }

    override suspend fun mediaInfo(uri: String): MediaRow? = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null
        val found: Pair<String?, Long?> = try {
            context.contentResolver.query(
                parsed,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                Pair(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getLong(1))
            } ?: return@withContext null
        } catch (_: SecurityException) {
            return@withContext null
        }
        MediaRow(
            uri = uri,
            displayName = found.first ?: "(no name)",
            mimeType = runCatching { context.contentResolver.getType(parsed) }.getOrNull(),
            sizeBytes = found.second,
        )
    }

    override suspend fun openContentUri(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val mime = runCatching { context.contentResolver.getType(parsed) }.getOrNull() ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(parsed, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return startVisible(intent)
    }

    override suspend fun shareContentUri(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val mime = runCatching { context.contentResolver.getType(parsed) }.getOrNull() ?: "*/*"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, parsed)
        // ClipData carries the grant to the target; EXTRA_STREAM alone
        // does not confer read access on all targets.
        send.clipData = ClipData.newUri(context.contentResolver, "media", parsed)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startVisible(chooser, checkTarget = send)
    }

    // ---- drafts (visible editors, system confirms) ----

    override suspend fun insertContactDraft(name: String?, phone: String?, email: String?): Boolean {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION)
            .setType(ContactsContract.RawContacts.CONTENT_TYPE)
        if (name != null) intent.putExtra(ContactsContract.Intents.Insert.NAME, name)
        if (phone != null) intent.putExtra(ContactsContract.Intents.Insert.PHONE, phone)
        if (email != null) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, email)
        return startVisible(intent)
    }

    override suspend fun insertEventDraft(
        title: String,
        description: String?,
        location: String?,
        beginMs: Long?,
        endMs: Long?,
        allDay: Boolean,
    ): Boolean {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        if (description != null) intent.putExtra(CalendarContract.Events.DESCRIPTION, description)
        if (location != null) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        if (beginMs != null) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
        if (endMs != null) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
        return startVisible(intent)
    }

    override suspend fun draftSms(to: String?, body: String?): Boolean {
        val data = if (to != null) Uri.fromParts("smsto", to, null) else Uri.parse("smsto:")
        val intent = Intent(Intent.ACTION_SENDTO, data)
        if (body != null) intent.putExtra("sms_body", body)
        return startVisible(intent)
    }

    override suspend fun draftEmail(to: List<String>, subject: String?, body: String?): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        if (to.isNotEmpty()) intent.putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
        if (subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        if (body != null) intent.putExtra(Intent.EXTRA_TEXT, body)
        return startVisible(intent)
    }

    override suspend fun dial(number: String?): Boolean {
        // ACTION_DIAL opens the dialer. ACTION_CALL is never used here.
        val data = if (number != null) Uri.fromParts("tel", number, null) else null
        return startVisible(Intent(Intent.ACTION_DIAL, data))
    }

    override fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val pkg = context.packageName
        return flat.split(':').any { part ->
            runCatching { ComponentName.unflattenFromString(part)?.packageName == pkg }.getOrDefault(false)
        }
    }

    override suspend fun openNotificationAccessSettings(): Boolean =
        startVisible(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    // ---- apps and settings ----

    override suspend fun queryApps(query: String?, limit: Int, includeSystem: Boolean): List<AppRow> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { info ->
                    AppRow(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info)?.toString() ?: info.packageName,
                        system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .filter { query == null || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
                .sortedBy { it.label.lowercase() }
                .take(limit)
                .toList()
        }

    override suspend fun appInfo(packageName: String): AppRow? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext null
        }
        val pkg = try {
            pm.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext null
        }
        AppRow(
            packageName = packageName,
            label = pm.getApplicationLabel(info)?.toString() ?: packageName,
            versionName = pkg.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(pkg),
            system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }

    override suspend fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return startVisible(intent)
    }

    override suspend fun openAppSettings(packageName: String): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        return startVisible(intent)
    }

    override suspend fun openSpecialAccess(kind: String, packageName: String?): Boolean {
        val pkgUri = packageName?.let { Uri.fromParts("package", it, null) }
        val candidates = when (kind) {
            "overlay" -> listOfNotNull(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).takeIf { pkgUri != null },
            )
            "install_unknown" -> listOfNotNull(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).takeIf { pkgUri != null },
            )
            "all_files" -> listOfNotNull(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            "notification_listener" -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "write_settings" -> listOfNotNull(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri),
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).takeIf { pkgUri != null },
            )
            else -> return false
        }
        return candidates.any { startVisible(it) }
    }

    override suspend fun openSystemSetting(action: String): Boolean {
        // The action already passed the strict allowlist in the dispatcher.
        if (action !in CapabilityPolicy.SYSTEM_SETTINGS.values) return false
        return startVisible(Intent(action))
    }

    // ---- location ----

    private val locationManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun locationEnabled(): Boolean {
        val manager = locationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * The best cached fix across the providers this request may use, never a
     * new one. `getLastKnownLocation` returns immediately; requesting a fix
     * would hold the run's tool lock for as long as the sky takes, which is
     * the wrong trade for a question the model asked mid-sentence.
     *
     * "Best" is newest, not most accurate: an old precise fix is a worse
     * answer to "where am I" than a recent rough one, and the accuracy travels
     * with the row either way.
     */
    override suspend fun lastLocation(maxAgeMs: Long, precise: Boolean): LocationFix? =
        withContext(Dispatchers.IO) {
            val manager = locationManager ?: return@withContext null
            val providers = buildList {
                if (precise) add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.PASSIVE_PROVIDER)
            }
            val now = System.currentTimeMillis()
            val best: Location? = providers
                .mapNotNull { provider ->
                    // A provider this build has no grant for throws rather
                    // than returning null; the dispatcher checked the grant,
                    // but a revoke between the two is still possible.
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .filter { now - it.time in 0..maxAgeMs }
                .maxByOrNull { it.time }
            best?.let { fix ->
                LocationFix(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyMeters = if (fix.hasAccuracy()) fix.accuracy else null,
                    ageMs = (now - fix.time).coerceAtLeast(0L),
                    provider = fix.provider,
                )
            }
        }

    // ---- intent plumbing ----

    private fun canHandle(intent: Intent): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0) != null
        }
    }

    /**
     * Start a visible screen. Application Context plus NEW_TASK, resolved
     * first, with launch failures reported as false instead of a crash.
     */
    private fun startVisible(intent: Intent, checkTarget: Intent = intent): Boolean {
        if (!canHandle(checkTarget)) return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
