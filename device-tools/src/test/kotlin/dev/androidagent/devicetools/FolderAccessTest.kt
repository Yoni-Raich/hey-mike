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
import dev.androidagent.core.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FolderAccessTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocs"

    /** A granted tree held in memory: path -> bytes. */
    private class Folders(private val base: CapabilityPlatform = CapabilityToolsTest.FakePlatform()) : CapabilityPlatform by base {
        val files = mutableMapOf("notes/a.txt" to "hello".toByteArray())
        var pickerOpened = false
        override suspend fun grantedFolders() = listOf(FolderRow("content://com.android.externalstorage.documents/tree/primary%3ADocs", "Docs", true))
        override suspend fun openFolderPicker(): Boolean { pickerOpened = true; return true }
        override suspend fun listFolder(folder: String, path: String, limit: Int): List<DocumentRow>? =
            if (path == "notes") files.keys.filter { it.startsWith("notes/") }.map { DocumentRow(it.removePrefix("notes/"), false, "text/plain", 5, 1) } else null
        override suspend fun importDocument(folder: String, path: String, target: File, maxBytes: Long): Long? {
            val bytes = files[path] ?: return null
            target.parentFile?.mkdirs(); target.writeBytes(bytes); return bytes.size.toLong()
        }
        override suspend fun exportDocument(folder: String, path: String, source: File, mimeType: String): Boolean {
            if (!path.startsWith("notes/")) return false
            files[path] = source.readBytes(); return true
        }
        override suspend fun documentExists(folder: String, path: String) = path in files
    }

    private val ws: File = Files.createTempDirectory("ws").toFile()

    private fun call(
        platform: Folders,
        args: JsonObject,
        change: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = { _, dispatch -> dispatch() },
    ): JsonObject {
        val tools = AndroidCapabilityTools(platform, authorizeChange = change)
        tools.beginRun("r", ws)
        return Json.parseToJsonElement(runBlocking { tools.invoke("files_media", args) }.text).let { it as JsonObject }
    }

    private fun op(name: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("operation", name); body() }

    @Test fun listsGrantedFoldersAndOpensPicker() {
        val folders = Folders()
        val listed = call(folders, op("folders"))
        assertEquals("Docs", listed["folders"]!!.jsonArray[0].let { (it as JsonObject)["name"]!!.jsonPrimitive.content })
        call(folders, op("pick_folder"))
        assertTrue(folders.pickerOpened)
    }

    @Test fun importsIntoWorkspaceAndExportsBack() {
        val folders = Folders()
        val listed = call(folders, op("folder_list") { put("folder", tree); put("path", "notes") })
        assertEquals("a.txt", (listed["items"] ?: listed["results"] ?: listed.values.first { it is kotlinx.serialization.json.JsonArray }).jsonArray.let { (it[0] as JsonObject)["name"]!!.jsonPrimitive.content })
        val imported = call(folders, op("folder_import") { put("folder", tree); put("path", "notes/a.txt") })
        assertEquals("imports/a.txt", imported["path"]!!.jsonPrimitive.content)
        assertEquals("hello", File(ws, "imports/a.txt").readText())
        File(ws, "out.txt").writeText("new")
        val exported = call(folders, op("folder_export") { put("folder", tree); put("path", "notes/b.txt"); put("from", "out.txt") })
        assertEquals("true", exported["ok"]!!.jsonPrimitive.content)
        assertEquals("new", String(folders.files.getValue("notes/b.txt")))
    }

    @Test fun replacingAFileNeedsOverwriteAndApproval() {
        val folders = Folders()
        File(ws, "out.txt").writeText("new")
        val refused = call(folders, op("folder_export") { put("folder", tree); put("path", "notes/a.txt"); put("from", "out.txt") })
        assertEquals("exists", refused["errorType"]!!.jsonPrimitive.content)
        var asked = false
        val declined = call(folders, op("folder_export") { put("folder", tree); put("path", "notes/a.txt"); put("from", "out.txt"); put("overwrite", true) }) { _, _ ->
            asked = true; ToolResult("{\"ok\":false,\"errorType\":\"declined\"}", success = false)
        }
        assertTrue(asked)
        assertFalse(declined["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("hello", String(folders.files.getValue("notes/a.txt")))
        call(folders, op("folder_export") { put("folder", tree); put("path", "notes/a.txt"); put("from", "out.txt"); put("overwrite", true) })
        assertEquals("new", String(folders.files.getValue("notes/a.txt")))
    }

    @Test fun importCannotEscapeTheWorkspace() {
        val result = call(Folders(), op("folder_import") { put("folder", tree); put("path", "notes/a.txt"); put("to", "../x.txt") })
        assertFalse(result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(File(ws.parentFile, "x.txt").exists())
    }
}
