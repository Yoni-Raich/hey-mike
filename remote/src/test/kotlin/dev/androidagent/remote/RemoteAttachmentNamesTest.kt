package dev.androidagent.remote

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteAttachmentNamesTest {
    @get:Rule val temp = TemporaryFolder()

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    @Test fun aNameBothWindowsAndLinuxAccept() {
        assertEquals("report 2026.pdf", RemoteHub.safeRemoteName("report 2026.pdf"))
        assertEquals("Photo 2026-09-30 15.04.11.jpg", RemoteHub.safeRemoteName("Photo 2026-09-30 15.04.11.jpg"))
        // A Hebrew name stays as it is: both systems keep Unicode names.
        assertEquals("חשבונית.pdf", RemoteHub.safeRemoteName("חשבונית.pdf"))
        // Separators and Windows' forbidden characters cannot climb out of the folder.
        assertEquals(".._.._x_y_.txt", RemoteHub.safeRemoteName("../..\\x:y?.txt"))
        assertEquals("attachment", RemoteHub.safeRemoteName(" . "))
    }

    @Test fun anAttachmentLandsInsideTheProjectInTheComputersOwnStyle() {
        val folder = ".hey-mike/attachments/20260930-150411"
        assertEquals(
            "C:\\src\\app\\.hey-mike\\attachments\\20260930-150411\\a.pdf",
            ComputerPlace.fullPath("$folder/a.pdf", "C:\\src\\app", "Pc"),
        )
        assertEquals(
            "/home/me/app/.hey-mike/attachments/20260930-150411/a.pdf",
            ComputerPlace.fullPath("$folder/a.pdf", "/home/me/app", "Server"),
        )
    }

    /** What a computer chat does with a file on send: it lands in the project, whole, and the prompt can name it. */
    @Test fun attachedFilesArriveInTheChatsProjectFolderOverSftp() = runBlocking {
        val remoteRoot = temp.newFolder("remote")
        File(remoteRoot, "proj").mkdirs()
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
            store.bind("chat-1", RemoteBinding("server", "/proj"))
            val hub = RemoteHub(store)

            val pdf = temp.newFile("local-a1b2-invoice.pdf").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
            val note = temp.newFile("local-c3d4-notes.txt").apply { writeText("hello") }
            // The name shown to the user is what lands on the computer, not the phone's unique file name.
            val landed = hub.sendAttachments("chat-1", listOf("invoice.pdf" to pdf, "a/b:notes.txt" to note))

            assertEquals(2, landed.size)
            assertTrue(landed[0], Regex("/proj/\\.hey-mike/attachments/\\d{8}-\\d{6}/invoice\\.pdf").matches(landed[0]))
            assertTrue(landed[1], landed[1].endsWith("/a_b_notes.txt"))
            // One folder for the message, so two files never collide.
            assertEquals(File(landed[0]).parent, File(landed[1]).parent)
            assertArrayEquals(pdf.readBytes(), File(remoteRoot, landed[0].removePrefix("/")).readBytes())
            assertEquals("hello", File(remoteRoot, landed[1].removePrefix("/")).readText())
            // No half-written file left beside them.
            assertTrue(File(remoteRoot, landed[0].removePrefix("/")).parentFile.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            server.stop(true)
        }
    }
}
