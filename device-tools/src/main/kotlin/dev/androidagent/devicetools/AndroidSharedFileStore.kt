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

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Saves a file received from a computer to phone storage without Wireless ADB. */
class AndroidSharedFileStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun save(source: File, remotePath: String): String = withContext(Dispatchers.IO) {
        require(source.isFile) { "The file copied from the computer is missing." }
        val path = when {
            remotePath.isBlank() -> "Download/${source.name}"
            remotePath.startsWith("/sdcard/") -> remotePath.removePrefix("/sdcard/")
            remotePath.startsWith("/storage/emulated/0/") -> remotePath.removePrefix("/storage/emulated/0/")
            else -> error("Use a shared phone path such as /sdcard/Download/${source.name}.")
        }
        val parts = path.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && it.none(Character::isISOControl) }) {
            "The phone path contains an invalid name."
        }
        val name = parts.last()
        val folder = parts.dropLast(1).joinToString("/")
        val top = parts.first()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val (collection, relative) = when {
            mime.startsWith("image/") && top in setOf("Pictures", "DCIM") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to folder
            mime.startsWith("video/") && top in setOf("Movies", "DCIM") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to folder
            mime.startsWith("audio/") && top in setOf("Music", "Podcasts", "Ringtones", "Alarms", "Notifications", "Recordings") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to folder
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                (if (top == "Download") folder else "Download")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relative/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        currentCoroutineContext().ensureActive()
        val uri = resolver.insert(collection, values) ?: error("The phone did not accept the file in $relative.")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
                ?: error("Could not write the file on the phone.")
            currentCoroutineContext().ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0) {
                "Could not finish saving the file on the phone."
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        uri.toString()
    }
}
