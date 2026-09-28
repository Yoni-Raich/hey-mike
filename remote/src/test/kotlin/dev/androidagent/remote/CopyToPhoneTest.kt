package dev.androidagent.remote

import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ChatSession
import dev.androidagent.core.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** copy_to_phone destination chat against a real SFTP server: the whole file, then a localName to install. */
class CopyToPhoneTest {
    @get:Rule val temp = TemporaryFolder()

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private object NoSessions : SessionStore {
        override val sessions: StateFlow<List<ChatSession>> = MutableStateFlow(emptyList())
        override suspend fun createSession() = error("not used")
        override suspend fun getSession(id: String): ChatSession? = null
        override fun messages(sessionId: String): Flow<List<ChatMessage>> = flowOf(emptyList())
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File("/tmp")
    }

    @Test fun aStagedCopyIsCompleteAndNamedForInstall() = runBlocking {
        val remoteRoot = temp.newFolder("remote")
        val apk = ByteArray(300_000) { (it % 251).toByte() }
        File(remoteRoot, "build").mkdirs()
        File(remoteRoot, "build/app.apk").writeBytes(apk)
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
            store.save(RemoteComputer("pc", "Desk", "127.0.0.1", port = server.port, user = "test", os = HostOs.LINUX), "test-only")
            val hub = RemoteHub(store)
            val gateway = ComputerToolGateway(hub, NoSessions, MutableStateFlow(null)) {}
            val workspace = File(temp.root, "sessions/chat/workspace").apply { mkdirs() }
            gateway.beginRun("run", workspace)
            val result = gateway.invoke("computers", JsonObject(mapOf(
                "mode" to JsonPrimitive("copy_to_phone"),
                "path" to JsonPrimitive("/build/app.apk"),
                "destination" to JsonPrimitive("chat"),
            )))
            val reply = Json.parseToJsonElement(result.text).jsonObject
            assertEquals(result.text, "true", reply["complete"]!!.jsonPrimitive.content)
            assertEquals("from-computer/app.apk", reply["localName"]!!.jsonPrimitive.content)
            assertArrayEquals(apk, File(workspace, "from-computer/app.apk").readBytes())
            assertFalse(File(workspace, "from-computer/.app.apk.part").exists())
            assertEquals(FileTransfer.State.DONE, hub.transfer.value?.state)
        } finally {
            server.stop(true)
        }
    }
}
