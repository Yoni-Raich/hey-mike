package dev.androidagent.remote

import dev.androidagent.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class ComputerToolGatewayTest {
    @get:Rule val temp = TemporaryFolder()

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    private object NoSessions : SessionStore {
        override val sessions: StateFlow<List<ChatSession>> = MutableStateFlow(emptyList())
        override suspend fun createSession(engine: EngineKind) = error("not used")
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

    @Test fun schemaKeepsDraftModeAndExposesSubagentLifecycleWithoutCredentials() {
        val definition = ComputerToolGateway.DEFINITION
        val props = definition.inputSchema["properties"]!!.jsonObject
        val modes = props["mode"]!!.jsonObject["enum"]!!.toString()
        for (mode in listOf("open_chat", "start_task", "task_status", "cancel_task")) assertTrue(modes.contains(mode))
        for (key in listOf("requestId", "taskId", "project", "message", "model")) assertTrue(key in props)
        assertFalse(props.keys.any { it.contains("password", true) || it.contains("token", true) })
        assertTrue(definition.description.contains("computer subagent"))
        assertTrue(definition.description.contains("the user sends it"))
        assertTrue(definition.description.contains("approval policy"))
    }

    @Test fun legacyOpenChatOnlyCreatesAnUnsentDraftAndSwitchesTheUi() = runTest {
        val store = RemoteStore(File(temp.root, "draft.bin"), PlainBox)
        val pc = RemoteComputer("pc", "Desk", "test.invalid", user = "test")
        store.save(pc, "fixture-only")
        store.addProject(pc.id, "C:\\src\\project")
        val sessions = Sessions(temp.root)
        val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> error("Draft must not prepare a task") }
        val hub = RemoteHub(store)
        markReady(hub, pc.id)
        val gateway = ComputerToolGateway(hub, sessions, requests, manager) { raised++ }
        gateway.beginRun("source-run", sessions.workspace("source"))
        val message = "  Please review\n בדיוק את הפרויקט  "
        val reply = invoke(gateway, "mode" to "open_chat", "project" to "project", "message" to message)
        runCurrent()
        assertEquals("true", reply["ok"]!!.jsonPrimitive.content)
        assertEquals(ComputerUiRequest.OpenChat("child-1", message), requests.value)
        assertEquals(message, sessions.composerDraft("child-1"))
        assertEquals("child-1", reply["sessionId"]!!.jsonPrimitive.content)
        assertEquals("true", reply["draftSaved"]!!.jsonPrimitive.content)
        assertEquals(1, raised)
        assertEquals(RemoteBinding(pc.id, "C:\\src\\project"), store.binding("child-1"))
        assertTrue(store.state.value.tasks.isEmpty())
        assertTrue(runner.sent.isEmpty())
        assertFalse(sessions.history.value.any { it.role == "user" })
    }

    @Test fun directTaskKeepsExactTextAndSourceUiAndRetriesOnlyOnce() = runTest {
        val store = RemoteStore(File(temp.root, "direct.bin"), PlainBox)
        val pc = RemoteComputer("pc", "Desk", "test.invalid", user = "test")
        store.save(pc, "fixture-only")
        store.addProject(pc.id, "C:\\src\\project")
        val sessions = Sessions(temp.root)
        val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val gateway = ComputerToolGateway(RemoteHub(store), sessions, requests, manager) { raised++ }
        gateway.beginRun("source-run", sessions.workspace("source"))
        val message = "  בדוק בדיוק\n  keep these spaces  "
        val args = arrayOf("mode" to "start_task", "project" to "project", "requestId" to "retry-key", "message" to message)
        val accepted = invoke(gateway, *args)["task"]!!.jsonObject
        val retry = invoke(gateway, *args)["task"]!!.jsonObject
        runCurrent()
        assertEquals(accepted["taskId"], retry["taskId"])
        assertEquals(listOf("child-1" to message), runner.sent)
        assertEquals(1, sessions.created)
        assertNull(requests.value)
        assertEquals(0, raised)
        assertEquals("source", accepted["originSessionId"]!!.jsonPrimitive.content)
        assertEquals("request_conflict", invoke(gateway, *args.filter { it.first != "message" }.toTypedArray(), "message" to "changed")["errorType"]!!.jsonPrimitive.content)
        val status = invoke(gateway, "mode" to "task_status", "requestId" to "retry-key")["task"]!!.jsonObject
        assertEquals("child-1", status["sessionId"]!!.jsonPrimitive.content)
        assertEquals("running", status["status"]!!.jsonPrimitive.content)
        invoke(gateway, "mode" to "cancel_task", "requestId" to "retry-key")
        assertEquals(listOf("child-1"), runner.stopped)
    }

    @Test fun taskIdsArePrivateToTheSourceAndNestedSubagentsAreRefused() = runTest {
        val store = RemoteStore(File(temp.root, "isolation.bin"), PlainBox)
        val pc = RemoteComputer("pc", "Desk", "test.invalid", user = "test")
        store.save(pc, "fixture-only")
        val sessions = Sessions(temp.root)
        val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val gateway = ComputerToolGateway(RemoteHub(store), sessions, requests, manager) { raised++ }
        gateway.beginRun("source-run", sessions.workspace("source"))
        val task = invoke(gateway, "mode" to "start_task", "project" to "/src", "requestId" to "key", "message" to "go")["task"]!!.jsonObject
        runCurrent()
        val id = task["taskId"]!!.jsonPrimitive.content
        gateway.beginRun("other-run", sessions.workspace("other"))
        for (mode in listOf("task_status", "cancel_task")) {
            assertEquals("unknown_task", invoke(gateway, "mode" to mode, "taskId" to id)["errorType"]!!.jsonPrimitive.content)
        }
        assertTrue(runner.stopped.isEmpty())
        gateway.beginRun("child-run", sessions.workspace("child-1"))
        assertEquals("nested_task", invoke(gateway, "mode" to "start_task", "project" to "/src", "requestId" to "nested", "message" to "go")["errorType"]!!.jsonPrimitive.content)
        assertEquals(1, runner.sent.size)
    }

    @Test fun directTaskRequiresAnOriginAndAStableRetryKey() = runTest {
        val store = RemoteStore(File(temp.root, "required.bin"), PlainBox)
        val pc = RemoteComputer("pc", "Desk", "test.invalid", user = "test")
        store.save(pc, "fixture-only")
        val sessions = Sessions(temp.root)
        val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val gateway = ComputerToolGateway(RemoteHub(store), sessions, requests, manager) { raised++ }
        gateway.beginRun("run", temp.root)
        val args = arrayOf("mode" to "start_task", "project" to "/src", "message" to "go")
        assertEquals("missing_source", invoke(gateway, *args, "requestId" to "key")["errorType"]!!.jsonPrimitive.content)
        gateway.beginRun("run", sessions.workspace("source"))
        assertEquals("missing_request_id", invoke(gateway, *args)["errorType"]!!.jsonPrimitive.content)
        assertTrue(store.state.value.tasks.isEmpty())
    }

    private suspend fun invoke(gateway: ComputerToolGateway, vararg args: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(gateway.invoke("computers", JsonObject(args.associate { it.first to JsonPrimitive(it.second) })).text).jsonObject

    /** A previously connected hub; this fixture deliberately does not open SSH. */
    @Suppress("UNCHECKED_CAST")
    private fun markReady(hub: RemoteHub, id: String) {
        val field = RemoteHub::class.java.getDeclaredField("mutableSetup").apply { isAccessible = true }
        val setup = field.get(hub) as MutableStateFlow<Map<String, RemoteSetup>>
        setup.value = mapOf(id to RemoteSetup.Ready(HostProbe("Desk", "C:\\Users\\test", "x86_64", "codex", installed = true), "Fixture"))
    }

    private class Sessions(private val root: File) : SessionStore {
        private val drafts = mutableMapOf<String, String>()
        override suspend fun saveComposerDraft(sessionId: String, text: String) { drafts[sessionId] = text }
        override suspend fun composerDraft(sessionId: String) = drafts[sessionId]
        override val sessions = MutableStateFlow(listOf("source", "other").map { ChatSession(it, it, 0, 0) })
        val history = MutableStateFlow<List<ChatMessage>>(emptyList())
        var created = 0
        override suspend fun createSession(engine: EngineKind) = ChatSession("child-${++created}", "Child", 0, 0, engine = engine).also { sessions.value += it }
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = history.map { it.filter { m -> m.sessionId == sessionId } }
        override suspend fun append(message: ChatMessage) { history.value += message }
        override suspend fun updateMessage(id: String, text: String, state: String) { history.value = history.value.map { if (it.id == id) it.copy(text = text, state = state) else it } }
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(root, "sessions/$sessionId/workspace")
    }

    private class Runner : ComputerTaskRunner {
        override val states = MutableStateFlow<Map<String, RunState>>(emptyMap())
        override val outcomes = MutableStateFlow<Map<String, RunState>>(emptyMap())
        val sent = mutableListOf<Pair<String, String>>()
        val stopped = mutableListOf<String>()
        override fun send(sessionId: String, originSessionId: String, message: String, model: String?, allowed: () -> Boolean): Boolean {
            if (!allowed()) return false
            sent += sessionId to message
            states.value += sessionId to RunState(RunPhase.THINKING, sessionId, "Working")
            return true
        }
        override fun stop(sessionId: String) { stopped += sessionId }
    }
}
