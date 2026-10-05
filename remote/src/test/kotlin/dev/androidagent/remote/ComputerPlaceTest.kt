package dev.androidagent.remote

import dev.androidagent.core.CopyFileGateway
import dev.androidagent.core.FileHome
import dev.androidagent.core.TransferMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComputerPlaceTest {
    @get:Rule val temp = TemporaryFolder()

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    @Test fun relativePathsJoinTheProjectFolderInTheComputersOwnStyle() {
        assertEquals("C:\\src\\app\\out\\a.png", ComputerPlace.fullPath("out/a.png", "C:\\src\\app\\", "Pc"))
        assertEquals("D:\\pics\\a.png", ComputerPlace.fullPath("D:\\pics\\a.png", "C:\\src\\app", "Pc"))
        assertEquals("/home/me/app/a.txt", ComputerPlace.fullPath("a.txt", "/home/me/app", "Server"))
        // A phone chat naming a computer has no folder there to be relative to.
        assertTrue(runCatching { ComputerPlace.fullPath("a.txt", null, "Pc") }.exceptionOrNull()!!.message!!.contains("full path"))
        assertEquals("a.png", ComputerPlace.fileName("D:\\pics\\a.png"))
        assertEquals("/C:/Users/yoni/a.png", SshLink.sftpPath("C:\\Users\\yoni\\a.png"))
    }

    /** copy_file against a real SFTP server, both ways, and a retry that copies nothing. */
    @Test fun copyFileMovesWholeFilesBothWaysOverSftp() = runBlocking {
        val remoteRoot = temp.newFolder("remote")
        File(remoteRoot, "proj").mkdirs()
        val apk = ByteArray(300_000) { (it % 251).toByte() }
        File(remoteRoot, "proj/app.apk").writeBytes(apk)
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { _, _, _ -> true }
            fileSystemFactory = VirtualFileSystemFactory(remoteRoot.toPath())
            subsystemFactories = listOf(SftpSubsystemFactory())
            start()
        }
        try {
            val store = RemoteStore(File(temp.root, "state.bin"), PlainBox)
            store.save(RemoteComputer("server", "Server", "127.0.0.1", port = server.port, user = "test", os = HostOs.LINUX), "test-only")
            val hub = RemoteHub(store)
            val meter = TransferMeter(CoroutineScope(Dispatchers.Unconfined))
            val gateway = CopyFileGateway(null, { hub.filePlaces() }, { FileHome("Server", "/proj") }, meter, temp.newFolder("scratch"))
            val workspace = File(temp.root, "sessions/chat/workspace").apply { mkdirs() }
            gateway.beginRun("run", workspace)
            fun copy(from: String, to: String, replace: Boolean = false) = runBlocking {
                val args = mutableMapOf("from" to JsonPrimitive(from), "to" to JsonPrimitive(to))
                if (replace) args["replace"] = JsonPrimitive(true)
                Json.parseToJsonElement(gateway.invoke("copy_file", JsonObject(args)).text).jsonObject
            }

            // Computer to phone: a bare path is the project folder there.
            val down = copy("app.apk", "chat:")
            assertEquals(down.toString(), "chat:app.apk", down["to"]!!.jsonPrimitive.content)
            assertArrayEquals(apk, File(workspace, "app.apk").readBytes())
            assertTrue(workspace.listFiles()!!.none { it.name.endsWith(".part") })

            // An install of that copy reads the phone file; the computer is not asked again.
            assertEquals(File(workspace, "app.apk").canonicalPath, gateway.phoneFile("chat:app.apk", workspace).canonicalPath)

            // Phone to computer, into a folder, whole and without a part file left.
            File(workspace, "notes.txt").writeText("from the phone")
            val up = copy("chat:notes.txt", "Server:/proj/in/")
            assertEquals(up.toString(), "Server:/proj/in/notes.txt", up["to"]?.jsonPrimitive?.content)
            assertEquals("from the phone", File(remoteRoot, "proj/in/notes.txt").readText())
            assertFalse(File(remoteRoot, "proj/in/.notes.txt.part").exists())

            // Nothing is overwritten on the computer unless asked.
            File(workspace, "notes.txt").writeText("changed")
            assertEquals("copy_failed", copy("chat:notes.txt", "Server:/proj/in/notes.txt")["errorType"]!!.jsonPrimitive.content)
            assertEquals("from the phone", File(remoteRoot, "proj/in/notes.txt").readText())
            copy("chat:notes.txt", "Server:/proj/in/notes.txt", replace = true)
            assertEquals("changed", File(remoteRoot, "proj/in/notes.txt").readText())
        } finally {
            server.stop(true)
        }
    }
}
