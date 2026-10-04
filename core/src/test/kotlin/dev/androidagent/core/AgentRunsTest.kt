package dev.androidagent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Several chats at once: each runs on its own, and the phone goes to one of them at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunsTest {
    @Test fun reconnectingOneChatKeepsBothChatsRunningUntilTheirOwnCompletion() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        rig.runs.send("two", "Second")
        runCurrent()
        val otherStatus = rig.runs.stateOf("two")!!.status
        rig.engine.emit(EngineEvent.Activity("Reconnecting... 2/5", "thread-one", "turn-thread-one"))
        runCurrent()
        assertEquals("Reconnecting... 2/5", rig.runs.stateOf("one")!!.status)
        assertEquals(otherStatus, rig.runs.stateOf("two")!!.status)
        assertTrue(rig.runs.stateOf("one")!!.active)
        assertTrue(rig.runs.stateOf("two")!!.active)
        assertTrue(rig.store.assistant("one").isEmpty())
        rig.engine.emit(EngineEvent.MessageCompleted("Recovered", "thread-one", "turn-thread-one", "a", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-one", turnId = "turn-thread-one"))
        runCurrent()
        assertEquals(listOf("Recovered"), rig.store.assistant("one"))
        assertTrue(rig.runs.stateOf("two")!!.active)
        rig.close()
    }

    @Test fun aTurnFailureLeavesTheOtherChatAndTheSharedEngineRunning() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        rig.runs.send("two", "Second")
        runCurrent()
        rig.engine.emit(EngineEvent.Failure("Usage limit", "thread-one", "turn-thread-one"))
        runCurrent()
        assertFalse(rig.runs.slots.first { it.state.value.sessionId == "one" }.state.value.active)
        assertTrue(rig.runs.stateOf("two")!!.active)
        assertFalse(rig.engine.closed)
        assertTrue(rig.store.assistant("two").isEmpty())
        rig.engine.emit(EngineEvent.MessageCompleted("Still running", "thread-two", "turn-thread-two", "b", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-two", turnId = "turn-thread-two"))
        runCurrent()
        assertEquals(listOf("Still running"), rig.store.assistant("two"))
        rig.close()
    }

    @Test fun aSharedConnectionFailureStillEndsBothChats() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        rig.runs.send("two", "Second")
        runCurrent()
        rig.engine.emit(EngineEvent.Failure("Codex connection ended"))
        runCurrent()
        assertTrue(rig.runs.slots.none { it.state.value.active })
        assertTrue(rig.store.assistant("one").single().contains("Codex connection ended"))
        assertTrue(rig.store.assistant("two").single().contains("Codex connection ended"))
        rig.close()
    }

    @Test fun aNewChatStartsWhileAnotherIsRunning() = runTest {
        val rig = Rig(this)
        rig.queue.submit(QueuedTurn(sessionId = "one", prompt = "Long task"))
        runCurrent()
        rig.queue.submit(QueuedTurn(sessionId = "two", prompt = "Quick question"))
        runCurrent()

        assertEquals(listOf("thread-one", "thread-two"), rig.engine.started)
        assertTrue(rig.queue.turns.value.isEmpty())
        assertTrue(rig.runs.stateOf("one")!!.active)
        assertTrue(rig.runs.stateOf("two")!!.active)

        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-two", turnId = "turn-thread-two"))
        runCurrent()
        assertNull(rig.runs.stateOf("two"))
        assertTrue(rig.runs.stateOf("one")!!.active)
        rig.close()
    }

    @Test fun eachChatGetsOnlyItsOwnWords() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        rig.runs.send("two", "Second")
        runCurrent()
        rig.engine.emit(EngineEvent.MessageCompleted("For one", "thread-one", "turn-thread-one", "a", "final_answer"))
        rig.engine.emit(EngineEvent.MessageCompleted("For two", "thread-two", "turn-thread-two", "b", "final_answer"))
        runCurrent()
        assertEquals(listOf("For one"), rig.store.assistant("one"))
        assertEquals(listOf("For two"), rig.store.assistant("two"))
        rig.close()
    }

    @Test fun thePhoneGoesToOneChatAtATime() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "Tap something")
        rig.runs.send("two", "Tap something else")
        runCurrent()

        rig.engine.emit(EngineEvent.ToolCall("r1", "tap", buildJsonObject {}, "thread-one", "turn-thread-one"))
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("r2", "tap", buildJsonObject {}, "thread-two", "turn-thread-two"))
        runCurrent()

        // One tapped; two waits for the phone and says so.
        assertEquals(listOf("r1"), rig.engine.answered)
        assertEquals("Waiting for the phone", rig.runs.stateOf("two")!!.status)

        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-one", turnId = "turn-thread-one"))
        runCurrent()
        assertEquals(listOf("r1", "r2"), rig.engine.answered)
        assertEquals(2, rig.tools.executions)
        rig.close()
    }

    @Test fun aChatThatOnlyThinksLeavesTheOtherChatsCardAlone() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "Tap something")
        rig.runs.send("two", "Just answer")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("r1", "tap", buildJsonObject {}, "thread-one", "turn-thread-one"))
        runCurrent()
        assertTrue(rig.overlay.visible)

        rig.engine.emit(EngineEvent.MessageCompleted("Answer", "thread-two", "turn-thread-two", "b", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-two", turnId = "turn-thread-two"))
        runCurrent()
        assertTrue(rig.overlay.visible)
        assertTrue(rig.overlay.finished.isEmpty())
        rig.close()
    }

    @Test fun stoppingOneChatLeavesTheOtherRunning() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        rig.runs.send("two", "Second")
        runCurrent()
        rig.runs.stop("one")
        runCurrent()
        assertNull(rig.runs.stateOf("one"))
        assertTrue(rig.runs.stateOf("two")!!.active)
        assertEquals(listOf("thread-one"), rig.engine.interrupted)
        assertFalse(rig.engine.closed)
        rig.close()
    }

    @Test fun aToolCallNoChatOwnsIsRefused() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("stray", "tap", buildJsonObject {}, "thread-gone", "turn-gone"))
        runCurrent()
        assertEquals(listOf("stray"), rig.engine.answered)
        assertEquals(0, rig.tools.executions)
        assertTrue(rig.runs.stateOf("one")!!.active)
        rig.close()
    }

    @Test fun chatsAreNotCapped() = runTest {
        val rig = Rig(this)
        val chats = (1..6).map { "c$it" }
        rig.store.sessions.value = chats.map { ChatSession(it, it, 0, 0) }
        chats.forEach { rig.queue.submit(QueuedTurn(sessionId = it, prompt = "Go $it")) }
        runCurrent()
        assertEquals(chats.map { "thread-$it" }, rig.engine.started)
        assertTrue(rig.queue.turns.value.isEmpty())
        assertEquals(6, rig.runs.slots.size)

        // A finished chat's coordinator is reused rather than another made.
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-c1", turnId = "turn-thread-c1"))
        runCurrent()
        rig.store.sessions.value = rig.store.sessions.value + ChatSession("c7", "c7", 0, 0)
        rig.queue.submit(QueuedTurn(sessionId = "c7", prompt = "Go c7"))
        runCurrent()
        assertEquals(6, rig.runs.slots.size)
        assertTrue(rig.runs.stateOf("c7")!!.active)
        rig.close()
    }

    @Test fun aCallThatLeavesTheScreenAloneDoesNotKeepThePhone() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "Look up a contact")
        rig.runs.send("two", "Tap something")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("r1", "contacts", buildJsonObject {}, "thread-one", "turn-thread-one"))
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("r2", "tap", buildJsonObject {}, "thread-two", "turn-thread-two"))
        runCurrent()
        // One is still thinking, but no longer holds the phone: two taps at once.
        assertEquals(listOf("r1", "r2"), rig.engine.answered)
        assertTrue(rig.runs.stateOf("one")!!.active)
        rig.close()
    }

    @Test fun aChatThatReadTheScreenKeepsThePhoneToItsEnd() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "Read the screen")
        rig.runs.send("two", "Look up a contact")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("r1", "read_ui", buildJsonObject {}, "thread-one", "turn-thread-one"))
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("r2", "contacts", buildJsonObject {}, "thread-two", "turn-thread-two"))
        runCurrent()
        // The handles one read are what its next tap uses, so two waits.
        assertEquals(listOf("r1"), rig.engine.answered)
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-one", turnId = "turn-thread-one"))
        runCurrent()
        assertEquals(listOf("r1", "r2"), rig.engine.answered)
        rig.close()
    }

    @Test fun aSecondTurnForARunningChatWaitsForThatChat() = runTest {
        val rig = Rig(this)
        rig.store.queued = listOf(QueuedTurn(sessionId = "one", prompt = "A"), QueuedTurn(sessionId = "one", prompt = "B"))
        val queue = SessionRunQueue(rig.scope, rig.runs, rig.store)
        runCurrent()
        queue.resume()
        runCurrent()
        assertEquals(listOf("thread-one"), rig.engine.started)
        assertEquals(listOf("B"), queue.turns.value.map { it.prompt })
        rig.close()
    }

    private class Rig(test: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val engine = Engine()
        val store = Store()
        val overlay = Overlay()
        val tools = Tools()
        val runs = AgentRuns(scope, engine) { share ->
            AgentCoordinator(scope, engine, store, tools, overlay, share = share)
        }
        val queue = SessionRunQueue(scope, runs, store)
        fun close() { scope.cancel() }
    }

    private class Engine : AgentEngine {
        private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val events = stream.asSharedFlow()
        val started = mutableListOf<String>()
        val answered = mutableListOf<String>()
        val interrupted = mutableListOf<String>()
        var closed = false
        suspend fun emit(value: EngineEvent) = stream.emit(value)
        override suspend fun connect() = Unit
        override suspend fun account() = AccountStatus(true, "Test")
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("test")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) =
            "thread-" + workspace.name.removePrefix("session-")
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String {
            started += threadId
            return "turn-$threadId"
        }
        override suspend fun steer(threadId: String, turnId: String, prompt: String) = Unit
        override suspend fun interrupt(threadId: String, turnId: String) { interrupted += threadId }
        override suspend fun answerTool(requestId: String, result: ToolResult) { answered += requestId }
        override suspend fun answerApproval(requestId: String, allow: Boolean) = Unit
        override suspend fun close() { closed = true }
    }

    private class Store : SessionStore {
        var queued = emptyList<QueuedTurn>()
        override suspend fun loadQueuedTurns() = queued
        override suspend fun saveQueuedTurns(turns: List<QueuedTurn>) { queued = turns }
        override val sessions = MutableStateFlow(listOf("one", "two", "three").map { ChatSession(it, it, 0, 0) })
        val messages = mutableListOf<ChatMessage>()
        fun assistant(sessionId: String) = messages.filter { it.sessionId == sessionId && it.role == "assistant" }.map { it.text }
        override suspend fun createSession(engine: EngineKind) = sessions.value.first()
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(messages.filter { it.sessionId == sessionId })
        override suspend fun append(message: ChatMessage) { messages.add(message) }
        override suspend fun updateMessage(id: String, text: String, state: String) {
            val i = messages.indexOfFirst { it.id == id }
            if (i >= 0) messages[i] = messages[i].copy(text = text, state = state)
        }
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File("session-$sessionId")
    }

    private class Tools : DeviceToolGateway {
        override val definitions = emptyList<ToolDefinition>()
        var revoked = true
        var executions = 0
        override fun beginRun(runId: String, workspace: File) { revoked = false }
        override fun revoke() { revoked = true }
        override fun needsControl(name: String) = name == "tap"
        override fun hidesOverlayDuringCapture(name: String) = name == "read_ui"
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
            check(!revoked)
            executions++
            return ToolResult("Done")
        }
        override suspend fun cancel() = Unit
    }

    private class Overlay : ControlOverlay {
        var visible = false
        val finished = mutableListOf<OverlayState>()
        override suspend fun show(status: String) { visible = true }
        override fun update(status: String) = Unit
        override fun hide() { visible = false }
        override fun finish(state: OverlayState) { finished += state; hide() }
    }
}
