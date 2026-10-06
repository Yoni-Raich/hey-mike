package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AgentEngine
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ChatSession
import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.EngineKind
import dev.androidagent.core.RealtimeTransport
import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.core.SessionStore
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.ExternalChatgptTokens
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutingAgentEngineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aRenameAfterRestartIsPersistedOnTheCorrectComputerOnly() = runBlocking {
        val box = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val store = RemoteStore(File(temp.root, "computers.bin"), box)
        val peers = mutableListOf<ReplyRuntime>()
        val profiles = mutableListOf<dev.androidagent.enginecodex.EngineProfile>()
        for (pc in listOf("one", "two")) {
            store.save(RemoteComputer(pc, pc, "localhost", user = "test"), "test-only")
            store.bind("chat-$pc", RemoteBinding(pc, "C:\\project", "thread-$pc"))
        }
        val local = ReplyRuntime()
        val hub = RemoteHub(store, { _, _ -> ExternalChatgptTokens("test-token", "test-account") }) { _, profile ->
            profiles += profile
            CodexEngine(ReplyRuntime().also { peers += it }, profile)
        }
        val router = RoutingAgentEngine(CodexEngine(local), hub)
        try {
            router.renameThread("thread-two", "תיקון שמות הסשנים")
            assertTrue(local.requests.isEmpty())
            assertEquals(1, peers.size)
            assertTrue(profiles.single().developerInstructions.contains("computer \"two\""))
            val request = peers.single().requests.single { it["method"]?.jsonPrimitive?.content == "thread/name/set" }
            assertEquals("thread-two", request["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content)
            assertEquals("תיקון שמות הסשנים", request["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        } finally { router.close() }
    }

    @Test fun resumingAnImportedThreadKeepsItsOriginAcrossReconnects() = runBlocking {
        val box = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val stateFile = File(temp.root, "computers.bin")
        val store = RemoteStore(stateFile, box)
        store.save(RemoteComputer("pc", "PC", "localhost", user = "test"), "test-only")
        var phoneTokens = ExternalChatgptTokens("test-token", "test-account")
        val remoteRuntimes = mutableListOf<ReplyRuntime>()
        val hub = RemoteHub(store, { _, _ -> phoneTokens }) { _, profile ->
            CodexEngine(ReplyRuntime().also { remoteRuntimes += it }, profile)
        }
        val router = RoutingAgentEngine(CodexEngine(ReplyRuntime()), hub)
        try {
            for (origin in listOf(true, null, false)) {
                val chat = "chat-$origin"
                val threadId = "thread-$origin"
                val binding = RemoteBinding("pc", "C:\\project", threadId, importedFromPc = origin)
                store.bind(chat, binding)
                val workspace = File(temp.root, "sessions/$chat/workspace").apply { mkdirs() }
                repeat(2) {
                    assertEquals(threadId, withTimeout(5_000) { router.openSession(workspace, threadId, null, emptyList()) })
                    assertTrue(remoteRuntimes.any { runtime -> runtime.requests.any {
                        it["method"]?.jsonPrimitive?.content == "account/login/start" &&
                            it["params"]?.jsonObject?.get("chatgptAccountId")?.jsonPrimitive?.content == "test-account"
                    } })
                    assertEquals(binding, RemoteStore(stateFile, box).binding(chat))
                    hub.disconnect("pc")
                }
            }
            val workspace = File(temp.root, "sessions/new/workspace").apply { mkdirs() }
            store.bind("new", RemoteBinding("pc", "C:\\project"))
            assertEquals("new-thread", withTimeout(5_000) { router.openSession(workspace, null, null, emptyList()) })
            assertEquals(false, store.binding("new")?.importedFromPc)
            assertEquals("new-thread", store.binding("new")?.threadId)
            phoneTokens = ExternalChatgptTokens("second-token", "second-account")
            assertEquals("new-thread", withTimeout(5_000) { router.openSession(workspace, "new-thread", "gpt-6-sol", emptyList()) })
            assertTrue(remoteRuntimes.any { runtime -> runtime.requests.any {
                it["method"]?.jsonPrimitive?.content == "account/login/start" &&
                    it["params"]?.jsonObject?.get("chatgptAccountId")?.jsonPrimitive?.content == "second-account"
            } })
            assertTrue(remoteRuntimes.any { runtime -> runtime.requests.any {
                it["method"]?.jsonPrimitive?.content == "thread/resume" &&
                    it["params"]?.jsonObject?.get("model")?.jsonPrimitive?.content == "gpt-6-sol"
            } })
        } finally { router.close() }
    }

    private fun claudeRig(sessions: List<ChatSession>): Triple<RoutingAgentEngine, FakeClaude, File> {
        val box = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val store = RemoteStore(File(temp.root, "computers.bin"), box)
        val hub = RemoteHub(store, { _, _ -> ExternalChatgptTokens("t", "a") }) { _, profile -> CodexEngine(ReplyRuntime(), profile) }
        val claude = FakeClaude()
        val router = RoutingAgentEngine(CodexEngine(ReplyRuntime()), hub, claude, FakeSessions(sessions))
        return Triple(router, claude, File(temp.root, "sessions"))
    }

    @Test fun aClaudeChatOpensOnClaudeAndEveryLaterCallStaysThere() = runBlocking {
        val (router, claude, root) = claudeRig(listOf(ChatSession("c1", "C", 0, 0, engine = EngineKind.CLAUDE)))
        try {
            val workspace = File(root, "c1/workspace").apply { mkdirs() }
            val thread = router.openSession(workspace, null, "sonnet", emptyList())
            assertEquals("claude-thread", thread)
            assertEquals(listOf("sonnet"), claude.openedModels)
            router.startTurn(thread, "hi", emptyList(), "high", null, DeviceCapabilities(), null)
            router.steer(thread, "turn", "more")
            router.interrupt(thread, "turn")
            router.compact(thread)
            assertEquals(1, claude.turns)
            assertEquals(listOf("more"), claude.steers)
            assertEquals(listOf(thread), claude.interrupts)
            assertEquals(listOf(thread), claude.compacted)
            router.connect(EngineKind.CLAUDE)
            assertEquals("Claude user", router.account(EngineKind.CLAUDE).label)
            assertEquals(1, claude.connects)
        } finally { router.close() }
        assertTrue(claude.closed)
    }

    @Test fun aCodexChatBesideAClaudeChatStillOpensOnThePhonesCodex() = runBlocking {
        val (router, claude, root) = claudeRig(listOf(ChatSession("p1", "P", 0, 0)))
        try {
            val workspace = File(root, "p1/workspace").apply { mkdirs() }
            assertEquals("new-thread", withTimeout(5_000) { router.openSession(workspace, null, null, emptyList()) })
            assertTrue(claude.openedModels.isEmpty())
        } finally { router.close() }
    }

    @Test fun claudeToolRequestsAreTaggedSoTheyCannotMeetCodexIds() = runBlocking {
        val (router, claude, root) = claudeRig(listOf(ChatSession("c1", "C", 0, 0, engine = EngineKind.CLAUDE)))
        try {
            val first = async(Dispatchers.Default) {
                withTimeout(5_000) { router.events.first { it is EngineEvent.ToolCall } as EngineEvent.ToolCall }
            }
            // The fake's flow has no replay: wait until the router listens, or a busy build drops the event.
            withTimeout(5_000) { claude.listeners.first { it > 0 } }
            claude.emit(EngineEvent.ToolCall("1", "tap", JsonObject(emptyMap()), "claude-thread", "turn"))
            val call = first.await()
            assertNotEquals("1", call.requestId)
            router.answerTool(call.requestId, ToolResult("ok"))
            assertEquals(listOf("1"), claude.answered)
            // An untagged id is Codex's, never Claude's. This Codex is not
            // connected, so it refuses; what matters is that Claude never hears it.
            runCatching { router.answerTool("1", ToolResult("ok")) }
            assertEquals(listOf("1"), claude.answered)
        } finally { router.close() }
    }

    @Test fun afterARestartAClaudeThreadIsFoundThroughItsChat() = runBlocking {
        val (router, claude, _) = claudeRig(
            listOf(ChatSession("c2", "C", 0, 0, engineThreadId = "old-claude", engine = EngineKind.CLAUDE)),
        )
        try {
            router.compact("old-claude")
            assertEquals(listOf("old-claude"), claude.compacted)
        } finally { router.close() }
    }

    @Test fun voiceIsRefusedInAClaudeChat() = runBlocking {
        val (router, _, root) = claudeRig(listOf(ChatSession("c1", "C", 0, 0, engine = EngineKind.CLAUDE)))
        try {
            val thread = router.openSession(File(root, "c1/workspace").apply { mkdirs() }, null, null, emptyList())
            val failure = runCatching { router.startVoice(thread, null, RealtimeTransport.WEBSOCKET, null) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        } finally { router.close() }
    }

    private class FakeClaude : AgentEngine {
        private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val events = stream.asSharedFlow()
        val listeners get() = stream.subscriptionCount
        val openedModels = mutableListOf<String?>()
        val steers = mutableListOf<String>()
        val interrupts = mutableListOf<String>()
        val compacted = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var turns = 0
        var connects = 0
        var closed = false
        suspend fun emit(event: EngineEvent) = stream.emit(event)
        override suspend fun connect() { connects++ }
        override suspend fun account() = AccountStatus(true, "Claude user")
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("sonnet")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>): String {
            openedModels += model
            return threadId ?: "claude-thread"
        }
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String { turns++; return "turn" }
        override suspend fun compact(threadId: String) { compacted += threadId }
        override suspend fun steer(threadId: String, turnId: String, prompt: String) { steers += prompt }
        override suspend fun interrupt(threadId: String, turnId: String) { interrupts += threadId }
        override suspend fun answerTool(requestId: String, result: ToolResult) { answered += requestId }
        override suspend fun answerApproval(requestId: String, allow: Boolean) = Unit
        override suspend fun close() { closed = true }
    }

    private class FakeSessions(list: List<ChatSession>) : SessionStore {
        override val sessions = MutableStateFlow(list)
        override suspend fun createSession(engine: EngineKind) = error("not used")
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(sessionId)
    }

    /** A protocol peer which resumes the requested thread; no SSH or account is used. */
    private class ReplyRuntime : RuntimeHost {
        val requests = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        override val status = MutableStateFlow(RuntimeStatus())
        override val homeDirectory = File(".")
        private val input = PipedInputStream()
        private val replies = PipedOutputStream(input)
        private val output = object : ByteArrayOutputStream() {
            override fun flush() {
                val lines = toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
                reset()
                lines.forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    requests.add(request)
                    val id = request["id"] ?: return@forEach
                    val method = request["method"]?.jsonPrimitive?.content
                    val thread = (request["params"] as? JsonObject)?.get("threadId") ?: JsonPrimitive("new-thread")
                    val reply = buildJsonObject {
                        put("id", id)
                        put("result", if (method == "initialize") buildJsonObject {} else buildJsonObject {
                            put("thread", buildJsonObject { put("id", thread) })
                        })
                    }
                    replies.write((reply.toString() + "\n").toByteArray())
                    replies.flush()
                }
            }
        }
        override suspend fun prepare() = Unit
        override suspend fun startAppServer(): Process = object : Process() {
            override fun getInputStream() = input
            override fun getOutputStream() = output
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun isAlive() = true
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
        }
        override suspend fun stop() { replies.close(); input.close() }
    }
}
