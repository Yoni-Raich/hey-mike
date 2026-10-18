package dev.androidagent.app

import dev.androidagent.core.*
import dev.androidagent.remote.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionAgentSetupTest {
    @get:Rule val temp = TemporaryFolder()
    private val codex = Engine(listOf(AgentModel("model-a", "Sol fixture", listOf(ReasoningEffortOption("high")))))
    private val claude = Engine(listOf(AgentModel("model-b", "Opus fixture", listOf(ReasoningEffortOption("medium")), engine = EngineKind.CLAUDE)))
    private fun setup(): SessionAgentSetup {
        val store = RemoteStore(File(temp.root, "remote"), object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        })
        return SessionAgentSetup(Sessions(), RemoteHub(store), codex, claude)
    }
    private fun task(engine: EngineKind = EngineKind.CODEX, model: String? = "model-a", effort: String? = "high") =
        SessionAgentTask("task", "request", "root", "Read notes", "Exact task", engine,
            model = model, sessionId = "child", createdAt = 0, reasoningEffort = effort)

    @Test fun optionsReturnEachTargetsModelIdsAndSupportedLevelsWithoutStartingWork() = runBlocking {
        val setup = setup()
        val a = setup.options("root", buildJsonObject { put("engine", "codex") })
        val b = setup.options("root", buildJsonObject { put("engine", "claude") })
        assertEquals("model-a", a.getValue("models").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(a.toString().contains("high"))
        assertEquals("model-b", b.getValue("models").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(b.toString().contains("medium"))
        assertEquals(0, codex.starts + claude.starts)
    }

    @Test fun unsupportedModelsAndThinkingLevelsAreRefusedWithoutFallbackOrDispatch() = runBlocking {
        val setup = setup()
        setup.prepare(task())
        setup.prepare(task(EngineKind.CLAUDE, "model-b", "medium"))
        assertTrue(runCatching { setup.prepare(task(model = "invented")) }.isFailure)
        assertTrue(runCatching { setup.prepare(task(effort = "medium")) }.isFailure)
        assertTrue(runCatching { setup.prepare(task(EngineKind.CLAUDE, "model-a", "high")) }.isFailure)
        assertEquals(0, codex.starts + claude.starts)
    }

    @Test fun defaultSettingsDoNotForceACatalogAndPhoneProjectRequestsAreRefused() = runBlocking {
        val setup = setup()
        setup.prepare(task(model = null, effort = null))
        assertEquals(0, codex.catalogReads)
        assertTrue(runCatching { setup.prepare(task().copy(computer = "phone", project = "X")) }.isFailure)
        assertTrue(runCatching { setup.options("root", buildJsonObject { put("engine", "unknown") }) }.isFailure)
        assertEquals(0, codex.catalogReads)
    }

    @Test fun failedRemotePreflightKeepsChildBoundToItsRequestedDestination() = runBlocking {
        val store = RemoteStore(File(temp.root, "offline-target"), object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        })
        store.save(RemoteComputer("pc", "Pc", "", user = "test"), null)
        val setup = SessionAgentSetup(Sessions(), RemoteHub(store), codex, claude,
            checkFolder = { _, _ -> error("Computer unavailable") })
        assertTrue(runCatching { setup.prepare(task().copy(computer = "pc", project = "C:\\projects\\X")) }.isFailure)
        val binding = store.state.value.bindings.getValue("child")
        assertEquals("pc", binding.computerId)
        assertEquals("C:\\projects\\X", binding.cwd)
        assertEquals(0, codex.starts + claude.starts)
    }

    private class Engine(private val catalog: List<AgentModel>) : AgentEngine {
        override val events = MutableSharedFlow<EngineEvent>()
        var catalogReads = 0; var starts = 0
        override suspend fun modelCatalog(): List<AgentModel> { catalogReads++; return catalog }
        override suspend fun connect() = Unit
        override suspend fun account() = AccountStatus(true, "Fixture")
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = catalog.map { it.id }
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) = "thread"
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String { starts++; return "turn" }
        override suspend fun steer(threadId: String, turnId: String, prompt: String) = Unit
        override suspend fun interrupt(threadId: String, turnId: String) = Unit
        override suspend fun answerTool(requestId: String, result: ToolResult) = Unit
        override suspend fun answerApproval(requestId: String, allow: Boolean) = Unit
        override suspend fun close() = Unit
    }
    private class Sessions : SessionStore {
        override val sessions = MutableStateFlow(listOf(ChatSession("root", "Root", 0, 0)))
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override suspend fun createSession(engine: EngineKind): ChatSession = error("No chat creation in catalog tests")
        override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(sessionId)
    }
}
