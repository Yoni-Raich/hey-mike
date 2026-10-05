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
import org.junit.Assert.assertNull
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
        val stats = mutableListOf<String>()
        override suspend fun stat(path: String, base: String?): FilePlace.Stat {
            stats += path
            val full = if (path.startsWith("/")) path else "/${base.orEmpty()}/$path"
            val file = File(root, full)
            require(file.isFile) { "No file at $full." }
            return FilePlace.Stat(file.length(), full, file.name)
        }
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

    @Test fun mediaToShowIsCopiedIntoTheChatOnceAndAChatFileIsUsedWhereItIs() = runBlocking {
        File(pcRoot, "clips").mkdirs()
        File(pcRoot, "clips/demo.mp4").writeText("video")
        val workspace = temp.newFolder("sessions", "show", "workspace")
        // Never armed with beginRun: a chat that does not hold the phone can still show media.
        val gateway = CopyFileGateway(phone, { listOf(pc) }, { null }, meter, File(temp.root, "scratch"))

        val first = gateway.chatCopy("Pc:/clips/demo.mp4", workspace)
        assertEquals(File(workspace, "media/demo.mp4").absoluteFile, first.absoluteFile)
        assertEquals("video", first.readText())
        // The same name again does not replace what an earlier message shows.
        val second = gateway.chatCopy("Pc:/clips/demo.mp4", workspace)
        assertEquals("demo-1.mp4", second.name)
        assertTrue(File(workspace, "media").listFiles()!!.none { it.name.endsWith(".part") })

        File(workspace, "shot.png").writeText("png")
        assertEquals(File(workspace, "shot.png").absoluteFile, gateway.chatCopy("chat:shot.png", workspace).absoluteFile)
        assertEquals(2, pc.downloads.size)

        val missing = runCatching { gateway.chatCopy("chat:nothing.png", workspace) }.exceptionOrNull()
        assertTrue(missing.toString(), missing is IllegalArgumentException)
        val outside = runCatching { gateway.chatCopy("chat:../../secret.png", workspace) }.exceptionOrNull()
        assertTrue(outside.toString(), outside is IllegalArgumentException)
    }

    @Test fun aComputersMediaIsNotCopiedOnlyMeasured() = runBlocking {
        File(pcRoot, "clips").mkdirs()
        File(pcRoot, "clips/demo.mp4").writeText("video bytes")
        File(pcRoot, "proj").mkdirs()
        File(pcRoot, "proj/shot.png").writeText("png")
        val workspace = temp.newFolder("sessions", "lazy", "workspace")
        val gateway = CopyFileGateway(phone, { listOf(pc) }, { FileHome("Pc", "proj") }, meter, File(temp.root, "scratch"))

        val named = RemoteMediaRef.parse(gateway.chatMedia("Pc:/clips/demo.mp4", workspace))!!
        assertEquals(RemoteMediaRef("Pc", "/clips/demo.mp4", 11), named)
        // A bare path in a computer chat means that computer's project folder, and the reference says where exactly.
        val bare = RemoteMediaRef.parse(gateway.chatMedia("shot.png", workspace))!!
        assertEquals(RemoteMediaRef("Pc", "/proj/shot.png", 3), bare)
        assertTrue("nothing moved", pc.downloads.isEmpty())
        assertTrue(File(workspace, "media").listFiles().orEmpty().isEmpty())

        val missing = runCatching { gateway.chatMedia("Pc:/clips/none.mp4", workspace) }.exceptionOrNull()
        assertTrue(missing.toString(), missing is IllegalArgumentException)

        // The bytes move when the user looks: fetchRemote reads exactly the file the reference names.
        val target = File(temp.root, "cache/demo.mp4")
        gateway.fetchRemote(named, target) { _, _ -> }
        assertEquals("video bytes", target.readText())
        assertEquals(listOf("/clips/demo.mp4"), pc.downloads)
        val gone = runCatching { gateway.fetchRemote(named.copy(place = "Nowhere"), target) { _, _ -> } }.exceptionOrNull()
        assertTrue(gone.toString(), gone is IllegalArgumentException)

        // Save puts it on the phone, in the folder the reference picks.
        val saved = gateway.saveToPhone(target, named.savePath)
        assertTrue(saved, saved.startsWith("phone:"))
        assertEquals("video bytes", File(phoneRoot, "Movies/Hey Mike/demo.mp4").readText())
    }

    @Test fun phoneMediaStillComesIntoTheChatAsAFile() = runBlocking {
        File(phoneRoot, "Pictures").mkdirs()
        File(phoneRoot, "Pictures/p.png").writeText("png")
        val workspace = temp.newFolder("sessions", "phonefile", "workspace")
        val gateway = CopyFileGateway(phone, { listOf(pc) }, { null }, meter, File(temp.root, "scratch"))
        val path = gateway.chatMedia("phone:/Pictures/p.png", workspace)
        assertNull(RemoteMediaRef.parse(path))
        assertEquals("png", File(path).readText())
        assertTrue(phone.stats.isEmpty())
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
