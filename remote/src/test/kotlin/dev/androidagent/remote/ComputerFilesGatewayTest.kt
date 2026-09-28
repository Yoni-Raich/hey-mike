package dev.androidagent.remote

import dev.androidagent.core.AdbStatus
import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComputerFilesGatewayTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aRelativeNameIsInTheChatsFolderAndAnAbsoluteOneStaysAsGiven() {
        assertEquals("C:\\src\\app\\out\\a.png", ComputerFilesGateway.onComputer("C:\\src\\app\\", "out/a.png"))
        assertEquals("D:\\pics\\a.png", ComputerFilesGateway.onComputer("C:\\src\\app", "D:\\pics\\a.png"))
        assertEquals("C:/x/a.png", ComputerFilesGateway.onComputer("C:\\src", "C:/x/a.png"))
        assertEquals("a.png", ComputerFilesGateway.fileName("D:\\pics\\a.png"))
    }

    @Test fun sftpSpellsWindowsPathsFromTheRoot() {
        assertEquals("/C:/Users/yoni/a.png", SshLink.sftpPath("C:\\Users\\yoni\\a.png"))
    }

    @Test fun computerPushIsReadyWithAdbOffButPhonePushIsBlocked() {
        val store = RemoteStore(File(temp.root, "state.bin"), object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        })
        store.save(RemoteComputer("pc", "Desk", "localhost", user = "user"), "secret")
        store.bind("computer-chat", RemoteBinding("pc", "C:\\src"))
        val inner = object : DeviceToolGateway {
            override val definitions = listOf(ToolDefinition("push_file", "Copy a file", buildJsonObject {}))
            override fun beginRun(runId: String, workspace: File) = Unit
            override fun revoke() = Unit
            override fun needsControl(name: String) = true
            override suspend fun invoke(name: String, arguments: JsonObject) = ToolResult("ADB is off", success = false)
            override suspend fun cancel() = Unit
            override fun readyTools() = emptySet<String>()
            override fun deviceBackendLive() = false
        }
        val gateway = ComputerFilesGateway(inner, RemoteHub(store)) { _, _ -> "content://media/test" }
        val computerWorkspace = File(temp.root, "sessions/computer-chat/workspace").apply { mkdirs() }
        gateway.beginRun("run-1", computerWorkspace)
        val computerSnapshot = DeviceCapabilities.of(gateway, AdbStatus())
        assertTrue("push_file" in computerSnapshot.ready)
        assertFalse("push_file" in computerSnapshot.blocked)

        val phoneWorkspace = File(temp.root, "sessions/phone-chat/workspace").apply { mkdirs() }
        gateway.beginRun("run-2", phoneWorkspace)
        val phoneSnapshot = DeviceCapabilities.of(gateway, AdbStatus())
        assertFalse("push_file" in phoneSnapshot.ready)
        assertTrue("push_file" in phoneSnapshot.blocked)
    }

    @Test fun installInAComputerChatUsesTheStagedFileAndARetryCopiesNothing() = kotlinx.coroutines.runBlocking {
        val store = RemoteStore(File(temp.root, "state2.bin"), object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        })
        store.save(RemoteComputer("pc", "Desk", "unreachable.invalid", user = "user"), "secret")
        store.bind("computer-chat", RemoteBinding("pc", "C:\\src"))
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val inner = object : DeviceToolGateway {
            override val definitions = listOf(ToolDefinition("install_apk", "Install", buildJsonObject {}))
            override fun beginRun(runId: String, workspace: File) = Unit
            override fun revoke() = Unit
            override fun needsControl(name: String) = true
            override suspend fun invoke(name: String, arguments: JsonObject): ToolResult { calls += name to arguments; return ToolResult("Installed") }
            override suspend fun cancel() = Unit
        }
        val hub = RemoteHub(store)
        val gateway = ComputerFilesGateway(inner, hub) { _, _ -> error("not used") }
        val workspace = File(temp.root, "sessions/computer-chat/workspace").apply { mkdirs() }
        File(workspace, "from-computer/app.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64)) }
        gateway.beginRun("run-1", workspace)
        val args = JsonObject(mapOf("localName" to kotlinx.serialization.json.JsonPrimitive("from-computer/app.apk")))
        // The computer is unreachable: any copy would fail, so two passing
        // installs prove neither touched it.
        assertTrue(gateway.invoke("install_apk", args).success)
        assertTrue(gateway.invoke("install_apk", args).success)
        assertEquals(listOf("install_apk" to args, "install_apk" to args), calls)
        assertEquals(null, hub.transfer.value)
    }
}
