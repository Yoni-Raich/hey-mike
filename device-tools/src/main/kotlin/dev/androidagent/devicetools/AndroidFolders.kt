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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File

/**
 * Folders the user picked in the system picker. Only trees with a lasting
 * grant are reachable; a path walks child names, so nothing outside a picked
 * tree can be named.
 */
internal class AndroidFolders(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun granted(): List<FolderRow> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { FolderRow(it.uri.toString(), displayName(it.uri) ?: it.uri.lastPathSegment.orEmpty(), it.isWritePermission) }

    fun openPicker(): Boolean = try {
        context.startActivity(Intent(context, FolderPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun list(folder: String, path: String, limit: Int): List<DocumentRow>? {
        val tree = tree(folder)
        val dir = resolve(tree, path) ?: return null
        if (dir.mimeType != Document.MIME_TYPE_DIR) return null
        return children(tree, dir.id).take(limit).map { it.row }
    }

    fun import(folder: String, path: String, target: File, maxBytes: Long): Long? {
        val tree = tree(folder)
        val doc = resolve(tree, path)?.takeIf { it.mimeType != Document.MIME_TYPE_DIR } ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)
        target.parentFile?.mkdirs()
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > maxBytes) throw PolicyException("too_large", "The file is over ${maxBytes / (1024 * 1024)} MB.")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null
        } catch (failure: Exception) {
            target.delete(); throw failure
        }
        return target.length()
    }

    fun exists(folder: String, path: String): Boolean = resolve(tree(folder), path) != null

    fun export(folder: String, path: String, source: File, mimeType: String): Boolean {
        val tree = tree(folder)
        val parts = split(path)
        require(parts.isNotEmpty()) { "Path must name a file." }
        val parent = resolve(tree, parts.dropLast(1).joinToString("/"))?.takeIf { it.mimeType == Document.MIME_TYPE_DIR } ?: return false
        val name = parts.last()
        val existing = children(tree, parent.id).firstOrNull { it.row.name == name }
        val uri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, existing.id)
        } else {
            DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent.id), mimeType, name)
                ?: return false
        }
        resolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: return false
        return true
    }

    private class Entry(val id: String, val mimeType: String, val row: DocumentRow)

    /** Only a tree the user granted; anything else is refused before any query. */
    private fun tree(folder: String): Uri {
        val uri = Uri.parse(folder)
        val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!granted) throw PolicyException("not_granted", "That folder was not picked by the user. Use folders, or pick_folder.")
        return uri
    }

    private fun split(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || it.contains('\u0000') }) throw PolicyException("invalid_argument", "Path must not contain . or ..")
        return parts
    }

    private fun resolve(tree: Uri, path: String): Entry? {
        var current = Entry(DocumentsContract.getTreeDocumentId(tree), Document.MIME_TYPE_DIR, DocumentRow("", true, Document.MIME_TYPE_DIR, null, null))
        for (name in split(path)) {
            current = children(tree, current.id).firstOrNull { it.row.name == name } ?: return null
        }
        return current
    }

    private fun children(tree: Uri, parentId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val out = ArrayList<Entry>()
        resolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: "application/octet-stream"
                val dir = mime == Document.MIME_TYPE_DIR
                out += Entry(
                    c.getString(0), mime,
                    DocumentRow(
                        c.getString(1) ?: "", dir, if (dir) null else mime,
                        if (dir || c.isNull(3)) null else c.getLong(3),
                        if (c.isNull(4)) null else c.getLong(4),
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ !it.row.dir }, { it.row.name.lowercase() }))
    }

    private fun displayName(tree: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}

/** Shows the system folder picker and keeps a lasting read and write grant on the pick. */
class FolderPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST)
        } catch (_: ActivityNotFoundException) {
            finish()
        }
    }

    @Deprecated("Activity result API is not available on a plain Activity.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST && resultCode == RESULT_OK && uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                .recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        finish()
    }

    private companion object { const val REQUEST = 7 }
}
