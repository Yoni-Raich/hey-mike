package dev.androidagent.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class RemoteStoreTest {
    @get:Rule val temp = TemporaryFolder()

    /** The Keystore box's algorithm with a software key. */
    private class SoftwareBox : SecretBox {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
                .doFinal(sealed, 12, sealed.size - 12)
    }

    private val computer = RemoteComputer(id = "pc", label = "Desk", host = "192.168.1.20", user = "yoni")

    @Test fun computersPasswordsAndBindingsSurviveARestart() {
        val box = SoftwareBox()
        val file = File(temp.root, "remote/state.bin")
        RemoteStore(file, box).apply {
            save(computer, "secret")
            bind("chat-1", RemoteBinding("pc", "C:\\Users\\yoni\\app"))
        }
        val reopened = RemoteStore(file, box)
        assertEquals(computer, reopened.computer("pc"))
        assertEquals("secret", reopened.password("pc"))
        assertEquals("C:\\Users\\yoni\\app", reopened.binding("chat-1")?.cwd)
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("secret"))
    }

    @Test fun anEditedFileIsNotTrustedAtAll() {
        val box = SoftwareBox()
        val file = File(temp.root, "state.bin")
        RemoteStore(file, box).save(computer, "secret")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val reopened = RemoteStore(file, box)
        assertTrue(reopened.state.value.unreadable)
        assertNull(reopened.password("pc"))
    }

    @Test fun aNewAddressForgetsTheOldHostKey() {
        val store = RemoteStore(File(temp.root, "s.bin"), SoftwareBox())
        store.save(computer.copy(hostKey = "KEY", fingerprint = "SHA256:x"), "secret")
        val moved = store.save(store.computer("pc")!!.copy(host = "192.168.1.21"), null)
        assertNull(moved.hostKey)
        assertEquals("secret", store.password("pc"))
    }

    @Test fun removingAComputerRemovesItsChats() {
        val store = RemoteStore(File(temp.root, "s.bin"), SoftwareBox())
        store.save(computer, "secret")
        store.bind("chat-1", RemoteBinding("pc", "C:\\x", threadId = "t1"))
        assertEquals("chat-1", store.bindingForThread("t1")?.first)
        store.remove("pc")
        assertNull(store.binding("chat-1"))
        assertNull(store.password("pc"))
    }

    @Test fun requestIdsFromTwoAppServersStayApart() {
        val tagged = RoutingAgentEngine.tag("pc", "7")
        assertEquals("pc" to "7", RoutingAgentEngine.untag(tagged))
        assertNull(RoutingAgentEngine.untag("7"))
        assertEquals("\"abc\"", RoutingAgentEngine.untag(RoutingAgentEngine.tag("pc", "\"abc\""))?.second)
    }

    @Test fun aChatIsFoundFromItsFolder() {
        assertEquals("chat-1", RoutingAgentEngine.sessionIdOf(File("/data/sessions/chat-1/workspace")))
        assertNull(RoutingAgentEngine.sessionIdOf(File("/data/sessions/chat-1")))
    }

    @Test fun windowsAnswersAreReadFromTheMarkerLine() {
        val probe = WindowsHost.parseProbe(
            ExecResult(
                "Welcome banner\r\nHEYMIKE {\"computer\":\"DESK\",\"home\":\"C:\\\\Users\\\\Yoni Raich\",\"arch\":\"x86_64\"," +
                    "\"exe\":\"C:\\\\Users\\\\Yoni Raich\\\\AppData\\\\Local\\\\HeyMike\\\\codex\\\\0.156.0\\\\bin\\\\codex-app-server.exe\"," +
                    "\"installed\":true,\"shell\":\"\"}\r\n",
                "", 0,
            ),
        )
        assertTrue(probe.installed)
        assertFalse(probe.powerShellDefault)
        assertEquals(
            "\"C:\\Users\\Yoni Raich\\AppData\\Local\\HeyMike\\codex\\0.156.0\\bin\\codex-app-server.exe\" --listen stdio://",
            WindowsHost.appServerCommand(probe),
        )
        assertEquals(
            "& 'C:\\Users\\Yoni Raich\\AppData\\Local\\HeyMike\\codex\\0.156.0\\bin\\codex-app-server.exe' --listen stdio://",
            WindowsHost.appServerCommand(probe.copy(powerShellDefault = true)),
        )
        val listing = WindowsHost.parseListing(
            ExecResult("HEYMIKE {\"path\":\"C:\\\\src\",\"parent\":\"C:\\\\\",\"dirs\":\"only\",\"drives\":[\"C:\\\\\",\"D:\\\\\"],\"git\":false}", "", 0),
        )
        assertEquals(listOf("only"), listing.folders)
        assertEquals(listOf("C:\\", "D:\\"), listing.drives)
    }

    @Test fun aPowerShellErrorReadsAsASentence() {
        val clixml = "#< CLIXML\r\n<Objs Version=\"1.1.0.1\"><S S=\"Error\">Cannot find path 'C:\\nope' because it does not exist._x000D__x000A_</S></Objs>"
        val error = runCatching { WindowsHost.parseListing(ExecResult("", clixml, 1)) }.exceptionOrNull()
        assertEquals("Cannot find path 'C:\\nope' because it does not exist.", error?.message)
    }

    @Test fun scriptsAreSentAsUtf16EncodedCommands() {
        val command = WindowsHost.powershell("'HEYMIKE ' + 1")
        val encoded = command.substringAfter("-EncodedCommand ")
        assertEquals("'HEYMIKE ' + 1", String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE))
        assertTrue(WindowsHost.powershell(WindowsHost.installScript()).length < 8_000)
    }
}
