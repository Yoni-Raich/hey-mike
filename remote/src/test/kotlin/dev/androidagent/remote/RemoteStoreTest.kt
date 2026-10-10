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
import dev.androidagent.core.EngineKind
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

    @Test fun importedThreadOriginSurvivesUntilMikeOwnsTheChat() {
        val box = SoftwareBox()
        val file = File(temp.root, "origin.bin")
        val store = RemoteStore(file, box)
        store.save(computer, "secret")
        val imported = RemoteBinding("pc", "C:\\src", threadId = "thread-1", importedFromPc = true)
        store.bind("chat-1", imported)
        assertEquals(true, RemoteStore(file, box).binding("chat-1")?.importedFromPc)
        store.bind("chat-1", imported.copy(importedFromPc = false))
        assertEquals(false, RemoteStore(file, box).binding("chat-1")?.importedFromPc)
    }

    @Test fun claudeBindingKeepsItsNativeIdAndEngineAfterRestart() {
        val box = SoftwareBox()
        val file = File(temp.root, "claude.bin")
        val store = RemoteStore(file, box)
        store.save(computer, "secret")
        val binding = RemoteBinding("pc", "C:\\src", "native-id", true, EngineKind.CLAUDE)
        store.bind("chat", binding)
        assertEquals(binding, RemoteStore(file, box).binding("chat"))
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

    @Test fun theFirstComputerIsTheDefaultAndRemovingItPassesItOn() {
        val box = SoftwareBox()
        val file = File(temp.root, "d.bin")
        val store = RemoteStore(file, box)
        store.save(computer, "secret")
        store.save(computer.copy(id = "laptop", label = "Laptop"), "other")
        assertEquals("pc", store.state.value.defaultComputerId)
        store.setDefault("laptop")
        assertEquals("laptop", RemoteStore(file, box).state.value.defaultComputerId)
        store.remove("laptop")
        assertEquals("pc", store.state.value.defaultComputerId)
    }

    @Test fun aVpnAddressIsKeptAndTriedAfterTheHomeOne() {
        val box = SoftwareBox()
        val file = File(temp.root, "v.bin")
        val store = RemoteStore(file, box)
        store.save(computer.copy(hostKey = "KEY", fingerprint = "SHA256:x"), "secret")
        // A new VPN address is the same machine: the pinned key must still match.
        val saved = store.save(store.computer("pc")!!.copy(vpnHost = "100.64.0.5"), null)
        assertEquals("KEY", saved.hostKey)
        assertEquals(listOf("192.168.1.20", "100.64.0.5"), RemoteStore(file, box).computer("pc")!!.hosts)
        assertEquals(listOf("192.168.1.20"), computer.copy(vpnHost = " ").hosts)
    }

    @Test fun projectsAreKeptOnceAndGoWithTheirComputer() {
        val box = SoftwareBox()
        val file = File(temp.root, "p.bin")
        val store = RemoteStore(file, box)
        store.save(computer, "secret")
        store.addProject("pc", "C:\\src\\app")
        store.addProject("pc", "C:\\src\\app")
        assertEquals(listOf(RemoteProject("pc", "C:\\src\\app")), RemoteStore(file, box).state.value.projects)
        store.remove("pc")
        assertEquals(emptyList<RemoteProject>(), store.state.value.projects)
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
                    "\"exe\":\"C:\\\\Users\\\\Yoni Raich\\\\AppData\\\\Local\\\\HeyMike\\\\codex\\\\0.159.2\\\\bin\\\\codex-app-server.exe\"," +
                    "\"installed\":true,\"shell\":\"\"}\r\n",
                "", 0,
            ),
        )
        assertTrue(probe.installed)
        assertFalse(probe.powerShellDefault)
        assertEquals(
            "\"C:\\Users\\Yoni Raich\\AppData\\Local\\HeyMike\\codex\\0.159.2\\bin\\codex-app-server.exe\" --listen stdio://",
            WindowsHost.appServerCommand(probe),
        )
        assertEquals(
            "& 'C:\\Users\\Yoni Raich\\AppData\\Local\\HeyMike\\codex\\0.159.2\\bin\\codex-app-server.exe' --listen stdio://",
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

    @Test fun computerSubagentReceiptsAndResultsSurviveInTheSealedStore() {
        val box = SoftwareBox()
        val file = File(temp.root, "receipts.bin")
        val store = RemoteStore(file, box)
        store.save(computer, "secret")
        val task = ComputerTask("task-1", "retry-key", "source", "pc", "C:\\src", "exact private request\n  ",
            model = "model", sessionId = "child", threadId = "remote-thread", status = "completed",
            progress = "Ready", result = "private final result", activity = "checked private file")
        store.saveTask(task)
        val reloaded = RemoteStore(file, box).state.value.tasks.getValue("task-1")
        assertEquals(task, reloaded)
        val sealed = file.readText(Charsets.ISO_8859_1)
        assertFalse(sealed.contains("exact private request"))
        assertFalse(sealed.contains("private final result"))
        assertFalse(sealed.contains("retry-key"))
        assertEquals("computer_subagent", reloaded.toJson()["kind"].toString().trim('"'))
        assertEquals("source", reloaded.toJson()["parentSessionId"].toString().trim('"'))
    }

    @Test fun removalKeepsRetryReceiptsAndLateProgressCannotReviveThem() {
        val box = SoftwareBox()
        val file = File(temp.root, "removed.bin")
        val store = RemoteStore(file, box)
        store.save(computer, "secret")
        val task = ComputerTask("task-1", "key", "source", "pc", "C:\\src", "go", sessionId = "child", status = "running")
        store.saveTask(task)
        store.remove("pc")
        val removed = store.state.value.tasks.getValue(task.id)
        assertEquals("unknown", removed.status)
        assertEquals(removed, store.saveTask(task.copy(progress = "late progress")))
        assertEquals(removed, RemoteStore(file, box).state.value.tasks.getValue(task.id))
    }

    @Test fun aReceiptCannotChangeOwnershipDestinationOrExactMessage() {
        val store = RemoteStore(File(temp.root, "immutable.bin"), SoftwareBox())
        store.save(computer, "secret")
        val task = ComputerTask("task-1", "key", "source", "pc", "C:\\src", " go ")
        store.saveTask(task)
        assertTrue(runCatching { store.saveTask(task.copy(originSessionId = "other")) }.isFailure)
        assertTrue(runCatching { store.saveTask(task.copy(project = "C:\\other")) }.isFailure)
        assertTrue(runCatching { store.saveTask(task.copy(message = "go")) }.isFailure)
        assertTrue(runCatching { store.saveTask(task.copy(requestId = "new-key")) }.isFailure)
        assertEquals(task, store.state.value.tasks.getValue(task.id))
    }

    @Test fun eachRetryKeyIsReservedOncePerSourceChat() {
        val store = RemoteStore(File(temp.root, "unique.bin"), SoftwareBox())
        store.save(computer, "secret")
        val first = ComputerTask("task-1", "key", "source", "pc", "C:\\src", "go")
        store.saveTask(first)
        assertTrue(runCatching { store.saveTask(first.copy(id = "task-2")) }.isFailure)
        val otherSource = first.copy(id = "task-3", originSessionId = "other-source")
        store.saveTask(otherSource)
        assertEquals(2, store.state.value.tasks.size)
    }
}
