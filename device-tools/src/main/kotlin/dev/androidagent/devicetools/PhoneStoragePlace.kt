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
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.androidagent.adb.AdbFileTransport
import dev.androidagent.core.AdbTransport
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.FilePlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * `phone:`, the phone's shared storage, reached through MediaStore as the app
 * itself, so no Wireless ADB is needed. Writes go into the matching
 * collection (Pictures, Movies, Music, else Download) and appear whole or not
 * at all. Reading a file another app saved may need media access; Wireless
 * ADB, when connected, is the fallback for that.
 */
class PhoneStoragePlace(
    context: Context,
    private val adb: AdbTransport? = null,
) : FilePlace {
    private val resolver = context.applicationContext.contentResolver

    override val name: String = "phone"

    override suspend fun download(path: String, base: String?, target: File, progress: (Long, Long) -> Unit): FilePlace.Fetched =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                val (size, name) = describe(uri)
                val input = resolver.openInputStream(uri) ?: error("The phone would not open $path.")
                input.use { copy(it, target, size, progress) }
                return@withContext FilePlace.Fetched(target.length(), name ?: "file")
            }
            val shared = sharedPath(path)
            val fileName = shared.substringAfterLast('/')
            val found = runCatching { findShared(shared) }.getOrNull()
            if (found != null) {
                val (size, _) = describe(found)
                val input = runCatching { resolver.openInputStream(found) }.getOrNull()
                if (input != null) {
                    input.use { copy(it, target, size, progress) }
                    return@withContext FilePlace.Fetched(target.length(), fileName)
                }
            }
            val native = adbFiles() ?: error(
                "The phone did not let the app read $shared. Grant media access (files_media permission_status) or turn on Wireless ADB.",
            )
            progress(0, 0)
            val result = native.pullFile(target, "/sdcard/$shared", PULL_TIMEOUT_MS)
            check(result.exitCode == 0 && target.isFile) { "Could not read /sdcard/$shared: ${result.output.take(200)}" }
            progress(target.length(), target.length())
            FilePlace.Fetched(target.length(), fileName)
        }

    override suspend fun upload(source: File, path: String, base: String?, replace: Boolean, progress: (Long, Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            require(source.isFile) { "The file to save is missing." }
            val shared = sharedPath(path.ifBlank { "Download/${source.name}" })
            val parts = shared.split('/')
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
            runCatching { findShared("$relative/$name") }.getOrNull()?.let { existing ->
                if (!replace) error("phone:$relative/$name already exists. Pass replace: true to overwrite it.")
                // Only a file this app saved can be removed; another stays and the new one gets a new name.
                runCatching { resolver.delete(existing, null, null) }
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
                val output = resolver.openOutputStream(uri) ?: error("Could not write the file on the phone.")
                output.use { out -> source.inputStream().use { copy(it, out, source.length(), progress) } }
                currentCoroutineContext().ensureActive()
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0) {
                    "Could not finish saving the file on the phone."
                }
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            "phone:$uri"
        }

    /** `Download/a.pdf`, from any of the ways the model spells shared storage. */
    internal fun sharedPath(path: String): String {
        val shared = path.trim()
            .removePrefix("/storage/emulated/0/")
            .removePrefix("/sdcard/")
            .removePrefix("sdcard/")
            .trimStart('/')
        val parts = shared.split('/')
        require(shared.isNotBlank() && parts.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && it.none(Character::isISOControl) }) {
            "\"$path\" is not a shared phone path. Use one such as phone:Download/report.pdf."
        }
        return shared
    }

    private fun findShared(shared: String): Uri? {
        val folder = shared.substringBeforeLast('/', "")
        val name = shared.substringAfterLast('/')
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            files,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$folder/", name),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return Uri.withAppendedPath(files, cursor.getLong(0).toString())
        }
        return null
    }

    private fun describe(uri: Uri): Pair<Long, String?> {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val size = if (cursor.isNull(0)) 0L else cursor.getLong(0)
                return size to cursor.getString(1)
            }
        }
        return 0L to null
    }

    private fun adbFiles(): AdbFileTransport? {
        val transport = adb ?: return null
        if (transport.status.value.phase != ConnectionPhase.CONNECTED) return null
        return transport as? AdbFileTransport
    }

    private suspend fun copy(input: InputStream, target: File, size: Long, progress: (Long, Long) -> Unit) {
        target.outputStream().use { copy(input, it, size, progress) }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, size: Long, progress: (Long, Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        progress(0, size)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            done += count
            progress(done, maxOf(size, done))
        }
    }

    private companion object {
        const val PULL_TIMEOUT_MS = 30 * 60_000L
    }
}
