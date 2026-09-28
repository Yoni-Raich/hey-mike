package dev.androidagent.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CopyFileGatewayTest {
    @get:Rule val temp = TemporaryFolder()

    /** A place backed by a folder, standing in for the phone's storage or a computer. */
    private class FolderPlace(override val name: String, val root: File, override val aliases: Set<String> = emptySet()) : FilePlace {
        val downloads = mutableListOf<String>()
        override suspend fun download(path: String, base: String?, target: File, progress: (Long, Long) -> Unit): FilePlace.Fetched {
            downloads += path
            val file = if (path.startsWith("/")) File(root, path) else File(root, "${base.orEmpty()}/$path")
            file.copyTo(target, overwrite = true)
            progress(file.length(), file.length())
            return FilePlace.Fetched(file.length(), file.name)
        }
        override suspend fun upload(source: File, path: String, base: String?, replace: Boolean, progress: (Long, Long) -> Unit): String {
            val file = File(root, path)
            check(!file.exists() || replace) { "$path already exists" }
            file.parentFile?.mkdirs()
            source.copyTo(file, overwrite = true)
            return if (name == "phone") "phone:content://media/external/downloads/7" else "$name:$path"
        }
    }

    private val phoneRoot by lazy { temp.newFolder("phone") }
    private val pcRoot by lazy { temp.newFolder("pc") }
    private val phone by lazy { FolderPlace("phone", phoneRoot) }
    private val pc by lazy { FolderPlace("Pc", pcRoot, setOf("pc-id")) }
    private val meter = TransferMeter(CoroutineScope(Dispatchers.Unconfined))

    private var chats = 0

    private fun gateway(home: FileHome? = null): Pair<CopyFileGateway, File> {
        chats++
        val workspace = temp.newFolder("sessions", "chat$chats", "workspace")
        val gateway = CopyFileGateway(phone, { listOf(pc) }, { home }, meter, File(temp.root, "scratch"))
        gateway.beginRun("run", workspace)
        return gateway to workspace
    }

    private fun copy(gateway: CopyFileGateway, from: String, to: String, replace: Boolean = false): JsonObject = runBlocking {
        val args = mutableMapOf("from" to JsonPrimitive(from), "to" to JsonPrimitive(to))
        if (replace) args["replace"] = JsonPrimitive(true)
        Json.parseToJsonElement(gateway.invoke("copy_file", JsonObject(args)).text).jsonObject
    }

    @Test fun aComputerFileComesIntoTheChatWholeAndKeepsItsName() {
        File(pcRoot, "build").mkdirs()
        File(pcRoot, "build/app.apk").writeText("apk bytes")
        val (gateway, ws) = gateway()
        val reply = copy(gateway, "Pc:/build/app.apk", "chat:")
        assertEquals(reply.toString(), "chat:app.apk", reply["to"]!!.jsonPrimitive.content)
        assertEquals("apk bytes", File(ws, "app.apk").readText())
        assertTrue(ws.listFiles()!!.none { it.name.endsWith(".part") })
        assertEquals(FileTransfer.State.DONE, meter.current.value?.state)
        assertEquals("Pc", meter.current.value?.from)
    }

    @Test fun theIdIsAnotherNameForAComputer() {
        File(pcRoot, "a.txt").writeText("a")
        val (gateway, ws) = gateway()
        copy(gateway, "pc-id:/a.txt", "chat:in/")
        assertEquals("a", File(ws, "in/a.txt").readText())
    }

    @Test fun twoOutsidePlacesPassThroughThePhoneAndReturnAUri() {
        File(pcRoot, "report.pdf").writeText("pdf")
        val (gateway, _) = gateway()
        val reply = copy(gateway, "Pc:/report.pdf", "phone:Download/")
        assertEquals("content://media/external/downloads/7", reply["uri"]!!.jsonPrimitive.content)
        assertEquals("pdf", File(phoneRoot, "Download/report.pdf").readText())
        assertTrue(temp.root.resolve("scratch").listFiles().orEmpty().isEmpty())
    }

    @Test fun nothingIsOverwrittenUnlessAsked() {
        File(pcRoot, "a.txt").writeText("new")
        val (gateway, ws) = gateway()
        File(ws, "a.txt").writeText("old")
        val refused = copy(gateway, "Pc:/a.txt", "chat:a.txt")
        assertEquals("exists", refused["errorType"]!!.jsonPrimitive.content)
        assertEquals("old", File(ws, "a.txt").readText())
        copy(gateway, "Pc:/a.txt", "chat:a.txt", replace = true)
        assertEquals("new", File(ws, "a.txt").readText())
    }

    @Test fun aBarePathIsWhereTheShellRuns() {
        File(pcRoot, "proj").mkdirs()
        File(pcRoot, "proj/notes.md").writeText("notes")
        // A computer chat: bare paths are the project folder on the computer.
        val (onPc, pcWs) = gateway(FileHome("Pc", "proj"))
        copy(onPc, "notes.md", "chat:")
        assertEquals(listOf("notes.md"), pc.downloads)
        assertEquals("notes", File(pcWs, "notes.md").readText())
        // A Windows path in a phone chat names no place: say so instead of guessing.
        val (onPhone, _) = gateway()
        assertEquals("which_place", copy(onPhone, "C:\\Users\\me\\a.txt", "chat:")["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun theChatFolderCannotBeLeft() {
        val (gateway, _) = gateway()
        File(temp.root, "outside.txt").writeText("secret")
        val reply = copy(gateway, "chat:../../../outside.txt", "Pc:/leak.txt")
        assertFalse(reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(File(pcRoot, "leak.txt").exists())
    }

    @Test fun anInstallGetsAPhoneFileAndNeverStartsACopyFromAComputer() = runBlocking {
        val (gateway, ws) = gateway()
        File(ws, "app.apk").writeText("apk")
        assertEquals(File(ws, "app.apk").canonicalPath, gateway.phoneFile("chat:app.apk", ws).canonicalPath)
        File(pcRoot, "app.apk").writeText("apk")
        val refused = runCatching { gateway.phoneFile("Pc:/app.apk", ws) }.exceptionOrNull()
        assertTrue(refused?.message.orEmpty().contains("copy_file"))
        assertTrue(pc.downloads.isEmpty())
    }

    @Test fun nothingIsCopiedAfterStop() {
        val (gateway, _) = gateway()
        gateway.revoke()
        assertTrue(runCatching { copy(gateway, "Pc:/a.txt", "chat:") }.isFailure)
    }
}
