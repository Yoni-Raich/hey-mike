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

    @Test fun turnsWaitOnlyWhenEveryRunIsBusy() = runTest {
        val rig = Rig(this, size = 2)
        rig.queue.submit(QueuedTurn(sessionId = "one", prompt = "A"))
        rig.queue.submit(QueuedTurn(sessionId = "two", prompt = "B"))
        rig.queue.submit(QueuedTurn(sessionId = "three", prompt = "C"))
        runCurrent()
        assertEquals(2, rig.engine.started.size)
        assertEquals(listOf("C"), rig.queue.turns.value.map { it.prompt })

        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-one", turnId = "turn-thread-one"))
        runCurrent()
        assertEquals(listOf("thread-one", "thread-two", "thread-three"), rig.engine.started)
        assertTrue(rig.queue.turns.value.isEmpty())
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

    private class Rig(test: TestScope, size: Int = 3) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val engine = Engine()
        val store = Store()
        val overlay = Overlay()
        val tools = Tools()
        val runs = AgentRuns(scope, engine, size) { share ->
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
        override suspend fun createSession() = sessions.value.first()
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
