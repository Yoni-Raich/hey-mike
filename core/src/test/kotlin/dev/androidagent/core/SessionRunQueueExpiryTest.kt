package dev.androidagent.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A turn a rule queued has a moment, and the moment can pass while the phone is
 * busy or stopped. These cover the deadline that stops "post this at 19:00"
 * from running at 23:40; everything without a deadline is unchanged.
 */
class SessionRunQueueExpiryTest {

    @Test fun aTurnWithNoDeadlineIsNeverDropped() = runTest {
        val rig = Rig(this)
        rig.queue.submit(QueuedTurn(sessionId = "one", prompt = "hello"))
        advanceUntilIdle()
        assertEquals(listOf("hello"), rig.sent)
        assertTrue(rig.expired.isEmpty())
        rig.close()
    }

    @Test fun aTurnPastItsDeadlineIsDroppedAndReportedRatherThanRunLate() = runTest {
        val rig = Rig(this)
        rig.occupy()
        advanceUntilIdle()
        rig.queue.submit(QueuedTurn(sessionId = "two", prompt = "post the update", validUntil = rig.clock - 1))
        advanceUntilIdle()
        rig.release()
        advanceUntilIdle()
        assertEquals(listOf("occupying"), rig.sent)
        assertEquals(listOf("post the update"), rig.expired.map { it.prompt })
        assertTrue(rig.queue.turns.value.isEmpty())
        rig.close()
    }

    @Test fun aTurnStillInsideItsDeadlineRuns() = runTest {
        val rig = Rig(this)
        rig.queue.submit(QueuedTurn(sessionId = "one", prompt = "post", validUntil = rig.clock + 60_000))
        advanceUntilIdle()
        assertEquals(listOf("post"), rig.sent)
        rig.close()
    }

    @Test fun anExpiredTurnAtTheHeadDoesNotHoldUpTheOneBehindIt() = runTest {
        // The whole point of filtering before choosing rather than after.
        val rig = Rig(this)
        rig.occupy()
        advanceUntilIdle()
        rig.queue.submit(QueuedTurn(sessionId = "two", prompt = "stale", validUntil = rig.clock - 1))
        rig.queue.submit(QueuedTurn(sessionId = "two", prompt = "fresh"))
        advanceUntilIdle()
        rig.release()
        advanceUntilIdle()
        assertEquals(listOf("occupying", "fresh"), rig.sent)
        assertEquals(listOf("stale"), rig.expired.map { it.prompt })
        rig.close()
    }

    private class Rig(test: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val engine = QuietEngine()
        val store = QuietStore()
        val sent = mutableListOf<String>()
        val expired = mutableListOf<QueuedTurn>()
        var clock = 1_000_000L
        val coordinator = AgentCoordinator(scope, engine, store, QuietTools(), QuietOverlay())
        val queue = SessionRunQueue(
            scope,
            coordinator,
            store,
            clock = { clock },
            onExpired = { expired += it },
        )

        init {
            engine.onTurn = { prompt -> sent += prompt }
        }

        /**
         * Take the device with a run that does not end, so later turns queue
         * instead of dispatching the moment they are submitted.
         *
         * On chat "one", because submitting into the chat that is already
         * running steers that run rather than queueing — every queued turn
         * below is on "two", which is also where a rule's turn would land.
         */
        fun occupy() {
            coordinator.send("one", "occupying")
        }

        /** Finish that run. The queue dispatches on `available` turning true. */
        suspend fun release() {
            engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        }

        fun close() = scope.cancel()
    }

    private class QuietEngine : AgentEngine {
        private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val events = stream.asSharedFlow()
        var onTurn: (String) -> Unit = {}
        suspend fun emit(event: EngineEvent) { stream.emit(event) }
        override suspend fun connect() = Unit
        override suspend fun account() = AccountStatus(true, "test")
        override suspend fun login() = AccountStatus(true, "test")
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("test")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) = "thread"
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String {
            onTurn(prompt)
            stream.emit(EngineEvent.TurnStarted("thread", "turn"))
            return "turn"
        }
        override suspend fun steer(threadId: String, turnId: String, prompt: String) = Unit
        override suspend fun interrupt(threadId: String, turnId: String) = Unit
        override suspend fun answerTool(requestId: String, result: ToolResult) = Unit
        override suspend fun answerApproval(requestId: String, allow: Boolean) = Unit
        override suspend fun close() = Unit
    }

    private class QuietStore : SessionStore {
        private var queued = emptyList<QueuedTurn>()
        override suspend fun loadQueuedTurns() = queued
        override suspend fun saveQueuedTurns(turns: List<QueuedTurn>) { queued = turns }
        override val sessions = MutableStateFlow(
            listOf(ChatSession("one", "One", 0, 0), ChatSession("two", "Two", 0, 0)),
        )
        override suspend fun createSession() = sessions.value.first()
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File("session-$sessionId")
    }

    private class QuietTools : DeviceToolGateway {
        override val definitions = emptyList<ToolDefinition>()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = false
        override suspend fun invoke(name: String, arguments: JsonObject) = ToolResult("")
        override suspend fun cancel() = Unit
    }

    private class QuietOverlay : ControlOverlay {
        override suspend fun show(status: String) = Unit
        override fun update(status: String) = Unit
        override fun hide() = Unit
    }
}
