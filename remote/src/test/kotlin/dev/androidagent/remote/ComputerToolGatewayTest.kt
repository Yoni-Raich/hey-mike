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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComputerToolGatewayTest {
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

    private val requests = MutableStateFlow<ComputerUiRequest?>(null)
    private var raised = 0

    private fun gateway(store: RemoteStore = RemoteStore(File(temp.root, "s.bin"), PlainBox)) =
        ComputerToolGateway(RemoteHub(store), NoSessions, requests) { raised++ }.also { it.beginRun("run", temp.root) }

    private fun call(gateway: ComputerToolGateway, vararg args: Pair<String, String>): JsonObject = runBlocking {
        val result = gateway.invoke("computers", JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))
        Json.parseToJsonElement(result.text).jsonObject
    }

    @Test fun addOnlyFillsTheFormAndNeverTakesAPassword() {
        val reply = call(gateway(), "mode" to "add", "vpnHost" to "100.64.0.5", "user" to "yoni", "name" to "Desk")
        assertEquals("true", reply["ok"]!!.jsonPrimitive.content)
        assertEquals(ComputerUiRequest.AddComputer("", "100.64.0.5", "yoni", "Desk"), requests.value)
        assertEquals(1, raised)
        val schema = ComputerToolGateway.DEFINITION.inputSchema["properties"]!!.jsonObject
        assertFalse(schema.keys.any { it.contains("password", ignoreCase = true) })
    }

    @Test fun addWithoutAnAddressIsRefused() {
        val reply = call(gateway(), "mode" to "add", "user" to "yoni")
        assertEquals("missing_address", reply["errorType"]!!.jsonPrimitive.content)
        assertNull(requests.value)
    }

    @Test fun statusWithNoComputerSaysHowToAddOne() {
        val reply = call(gateway(), "mode" to "status")
        assertTrue(reply["note"]!!.jsonPrimitive.content.contains("mode add"))
    }

    @Test fun anUnknownComputerOrProjectNamesWhatExists() {
        val store = RemoteStore(File(temp.root, "p.bin"), PlainBox)
        store.save(RemoteComputer(id = "pc", label = "Desk", host = "192.168.1.20", user = "yoni"), "secret")
        store.addProject("pc", "C:\\src\\app")
        val unknownComputer = call(gateway(store), "mode" to "browse", "computer" to "Laptop")
        assertEquals("unknown_computer", unknownComputer["errorType"]!!.jsonPrimitive.content)
        assertTrue(unknownComputer["message"]!!.jsonPrimitive.content.contains("Desk"))
        val unknownProject = call(gateway(store), "mode" to "open_chat", "project" to "site", "message" to "go")
        assertEquals("unknown_project", unknownProject["errorType"]!!.jsonPrimitive.content)
        assertTrue(unknownProject["message"]!!.jsonPrimitive.content.contains("app"))
    }

    @Test fun nothingRunsAfterStop() {
        val gateway = gateway()
        gateway.revoke()
        val stopped = runCatching { runBlocking { gateway.invoke("computers", JsonObject(emptyMap())) } }
        assertTrue(stopped.isFailure)
    }
}
