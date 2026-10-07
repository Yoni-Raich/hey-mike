package dev.androidagent.remote

import dev.androidagent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ComputerTasksTest {
    @get:Rule val temp = TemporaryFolder()
    private object Box : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }
    private val computer = RemoteComputer("pc", "Desk", "test.invalid", user = "test")
    private fun store() = RemoteStore(File(temp.root, "sealed"), Box).also { it.save(computer, "not-a-real-password") }

    private class Sessions(private val root: File) : SessionStore {
        override val sessions = MutableStateFlow(listOf(ChatSession("source", "Source", 0, 0)))
        val history = MutableStateFlow<List<ChatMessage>>(emptyList())
        var created = 0
        var beforeCreate: suspend () -> Unit = {}
        var beforeLookup: suspend () -> Unit = {}
        override suspend fun createSession(engine: EngineKind): ChatSession {
            beforeCreate()
            val chat = ChatSession("child-${++created}", "Child", 0, 0, engine = engine)
            sessions.value += chat
            return chat
        }
        override suspend fun getSession(id: String): ChatSession? {
            beforeLookup()
            return sessions.value.firstOrNull { it.id == id }
        }
        override fun messages(sessionId: String) = history.map { it.filter { m -> m.sessionId == sessionId } }
        override suspend fun append(message: ChatMessage) { history.value += message }
        override suspend fun updateMessage(id: String, text: String, state: String) {
            history.value = history.value.map { if (it.id == id) it.copy(text = text, state = state) else it }
        }
        override suspend fun setThread(sessionId: String, threadId: String) {
            sessions.value = sessions.value.map { if (it.id == sessionId) it.copy(engineThreadId = threadId) else it }
        }
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) { sessions.value = sessions.value.filterNot { it.id == sessionId } }
        override fun workspace(sessionId: String) = File(root, "sessions/$sessionId/workspace")
    }
    private class Runner : ComputerTaskRunner {
        override val states = MutableStateFlow<Map<String, RunState>>(emptyMap())
        override val outcomes = MutableStateFlow<Map<String, RunState>>(emptyMap())
        val sent = mutableListOf<Pair<String, String>>()
        val stops = mutableListOf<String>()
        val children = mutableMapOf<String, MutableList<String>>()
        var beforeDispatch: () -> Unit = {}
        override fun send(sessionId: String, originSessionId: String, message: String, model: String?, allowed: () -> Boolean): Boolean {
            beforeDispatch()
            if (!allowed()) return false
            children.getOrPut(originSessionId) { mutableListOf() }.add(sessionId)
            sent += sessionId to message
            states.value += sessionId to RunState(RunPhase.THINKING, sessionId, "Working")
            return true
        }
        override fun stop(sessionId: String) { stops += sessionId }
        override fun childrenOf(originSessionId: String) = children[originSessionId].orEmpty()
    }

    @Test fun retryIsOneSessionOneSendAndPreservesTheMessage() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val message = "  משימה\nwith exact spaces  "
        val first = manager.start("source", "key", computer, "C:\\src", message, null) { true }
        val second = manager.start("source", "key", computer, "c:/SRC/", message, null) { true }
        assertEquals(first.id, second.id)
        runCurrent()
        assertEquals(1, sessions.created)
        assertEquals(listOf("child-1" to message), runner.sent)
        assertEquals(RemoteBinding("pc", "C:\\src"), store.binding("child-1"))
        assertEquals(first.id, manager.start("source", "key", computer, "C:\\src", message, null) { true }.id)
        assertEquals(1, runner.sent.size)
        assertTrue(runCatching { manager.start("source", "key", computer, "C:\\src", "different", null) { true } }.isFailure)
    }

    @Test fun progressApprovalAndFinalResultReturnToTheSource() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        store.bind("child-1", RemoteBinding("pc", "/src", "real-thread"))
        val approval = EngineEvent.Approval("remote|pc|9", "command", buildJsonObject {}, "real-thread", "turn")
        runner.states.value = mapOf("child-1" to RunState(RunPhase.THINKING, "child-1", "Needs approval", approval = approval))
        runCurrent()
        assertEquals("waiting_for_user", manager.find("source", task.id, null)!!.status)
        assertEquals(approval, manager.sourceState("source", runner.states.value).approval)
        assertEquals("real-thread", manager.find("source", task.id, null)!!.threadId)
        sessions.append(ChatMessage("answer", "child-1", "assistant", "Done: result", 1))
        runner.outcomes.value = mapOf("child-1" to RunState(sessionId = "child-1"))
        // A slot can be reused before a watcher reads it: retained outcomes win.
        runner.states.value = emptyMap()
        runCurrent()
        val ended = manager.find("source", task.id, null)!!
        assertEquals("completed", ended.status)
        assertEquals("Done: result", ended.result)
        assertTrue(sessions.history.value.single { it.id == task.id }.text.contains("Done: result"))
        assertFalse(manager.sourceState("source", emptyMap()).active)
    }

    @Test fun stopDuringConnectionNeverSendsAnything() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val gate = CompletableDeferred<Unit>()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> gate.await() }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        manager.cancel("source", task.id, null)
        gate.complete(Unit); runCurrent()
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
        assertEquals(0, sessions.created)
        assertTrue(runner.sent.isEmpty())
    }

    @Test fun cancelTargetsOnlyTheChildAndWaitsForItsOutcome() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        assertNull(manager.find("other-source", task.id, null))
        assertEquals("cancelling", manager.cancel("source", task.id, null).status)
        assertEquals(listOf("child-1"), runner.stops)
        runner.outcomes.value = mapOf("child-1" to RunState(sessionId = "child-1", status = "Stopped"))
        runCurrent()
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
        manager.cancel("source", task.id, null)
        assertEquals(1, runner.stops.size)
    }

    @Test fun restartKeepsTheReceiptAndDoesNotReplayAnUncertainDispatch() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        val reloaded = RemoteStore(File(temp.root, "sealed"), Box)
        val next = ComputerTasks(reloaded, sessions, backgroundScope, { runner }) { _, _ -> error("never reconnect") }
        val receipt = next.start("source", "key", computer, "/src", "go", null) { true }
        assertEquals(task.id, receipt.id)
        assertEquals("unknown", receipt.status)
        assertEquals(1, runner.sent.size)
        assertEquals("child-1", receipt.sessionId)
    }

    @Test fun connectionFailureReturnsFailureWithoutAChildOrRetrySend() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> error("SSH refused") }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        assertEquals("failed", manager.find("source", task.id, null)!!.status)
        assertEquals(0, sessions.created)
        assertTrue(runner.sent.isEmpty())
        assertEquals("failed", manager.start("source", "key", computer, "/src", "go", null) { true }.status)
    }

    @Test fun stoppedCallerCannotReserveATask() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        assertTrue(runCatching { manager.start("source", "key", computer, "/src", "go", null) { false } }.isFailure)
        assertTrue(store.state.value.tasks.isEmpty())
    }

    @Test fun sourceStopWhileCreatingTheChildClosesTheDispatchFence() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val gate = CompletableDeferred<Unit>()
        sessions.beforeCreate = { gate.await() }
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        manager.stopOrigin("source")
        runCurrent() // cancel waits on the setup mutex, but its fence is already closed.
        gate.complete(Unit); runCurrent()
        assertTrue(runner.sent.isEmpty())
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
    }

    @Test fun sourceStopImmediatelyBeforeSendIsNotLost() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        runner.beforeDispatch = { manager.stopOrigin("source") }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
        assertTrue(runner.sent.isEmpty())
    }

    @Test fun sourceStopBeforeReceiptReservationIsRemembered() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        sessions.beforeLookup = {
            sessions.beforeLookup = {}
            manager.stopOrigin("source") // No task exists for Stop to enumerate yet.
        }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        assertEquals("cancelled", task.status)
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
        assertTrue(runner.sent.isEmpty())
        assertEquals(0, sessions.created)
    }

    @Test fun globalStopBeforeReceiptReservationIsRemembered() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        sessions.beforeLookup = {
            sessions.beforeLookup = {}
            manager.stopAll() // No source/task exists in the receipt list yet.
        }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        assertEquals("cancelled", task.status)
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
        assertTrue(runner.sent.isEmpty())
        assertEquals(0, sessions.created)
    }

    @Test fun globalStopImmediatelyBeforeSendClosesTheDispatchFence() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        runner.beforeDispatch = { manager.stopAll() }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
        assertTrue(runner.sent.isEmpty())
    }

    @Test fun aNewAuthorizedTaskCanStartAfterThePreviousGlobalStop() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        manager.stopAll()
        val task = manager.start("source", "fresh-key", computer, "/src", "new work", null) { true }
        runCurrent()
        assertEquals("running", manager.find("source", task.id, null)!!.status)
        assertEquals(listOf("child-1" to "new work"), runner.sent)
    }

    @Test fun removingTheComputerStopsSetupWithoutSending() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val gate = CompletableDeferred<Unit>()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> gate.await() }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        store.remove(computer.id); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(runner.sent.isEmpty())
        assertEquals(0, sessions.created)
        assertEquals("unknown", manager.find("source", task.id, null)!!.status)
        assertTrue(sessions.history.value.single { it.id == task.id }.text.contains("Computer removed"))
    }

    @Test fun removingTheComputerKeepsUnknownEvenWhenALateOutcomeArrives() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        store.remove(computer.id); runCurrent()
        runner.outcomes.value = mapOf("child-1" to RunState(sessionId = "child-1"))
        runCurrent()
        assertEquals("unknown", manager.find("source", task.id, null)!!.status)
        assertEquals(listOf("child-1"), runner.stops)
        assertFalse(manager.sourceState("source", runner.states.value).active)
    }

    @Test fun removingTheSourceStopsSetupAndRefusesFutureDispatch() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val gate = CompletableDeferred<Unit>()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> gate.await() }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        sessions.deleteSession("source"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("cancelled", manager.find("source", task.id, null)!!.status)
        assertTrue(runner.sent.isEmpty())
    }

    @Test fun aFinishedSourceDoesNotCancelItsIndependentChild() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        runner.outcomes.value = mapOf("source" to RunState(sessionId = "source"))
        runCurrent()
        assertEquals("running", manager.find("source", task.id, null)!!.status)
        assertTrue(runner.stops.isEmpty())
        assertTrue(manager.sourceState("source", runner.states.value).delegated)
        sessions.append(ChatMessage("answer", "child-1", "assistant", "Finished independently", 1))
        runner.outcomes.value += "child-1" to RunState(sessionId = "child-1")
        runCurrent()
        assertEquals("completed", manager.find("source", task.id, null)!!.status)
    }

    @Test fun sourceGatesWinAndChildGatesFollowActualDispatchOrder() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val gate = CompletableDeferred<Unit>()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, project -> if (project == "/slow") gate.await() }
        manager.start("source", "slow", computer, "/slow", "slow", null) { true }
        manager.start("source", "fast", computer, "/fast", "fast", null) { true }
        runCurrent()
        gate.complete(Unit); runCurrent()
        val childQuestion = UserQuestion("child-question", "Choose", emptyList())
        val sourceQuestion = UserQuestion("source-question", "Source choice", emptyList())
        val approval = EngineEvent.Approval("remote|pc|9", "command", buildJsonObject {}, "thread", "turn")
        runner.states.value = mapOf(
            "child-1" to RunState(RunPhase.THINKING, "child-1", "First child asks", question = childQuestion),
            "child-2" to RunState(RunPhase.THINKING, "child-2", "Later child approval", approval = approval),
        )
        runCurrent()
        val childGate = manager.sourceState("source", runner.states.value)
        assertEquals(childQuestion, childGate.question)
        assertNull(childGate.approval)
        assertTrue(childGate.delegated)
        val source = RunState(RunPhase.THINKING, "source", "Source asks", question = sourceQuestion)
        val ownGate = manager.sourceState("source", runner.states.value + ("source" to source))
        assertEquals(sourceQuestion, ownGate.question)
        assertFalse(ownGate.delegated)
    }

    @Test fun completedOutcomeCannotBeReportedAsCancelledBecauseWatcherWasLate() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        sessions.append(ChatMessage("answer", "child-1", "assistant", "Already finished", 1))
        runner.outcomes.value = mapOf("child-1" to RunState(sessionId = "child-1"))
        manager.cancel("source", task.id, null)
        runCurrent()
        assertEquals("completed", manager.find("source", task.id, null)!!.status)
        assertEquals("Already finished", manager.find("source", task.id, null)!!.result)
    }

    @Test fun restartRecreatesMissingSourceReceiptWithDurableActivity() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val task = ComputerTask("receipt", "key", "source", "pc", "/src", "go", sessionId = "child-1",
            status = "running", activity = "Checked project files")
        store.saveTask(task)
        val manager = ComputerTasks(RemoteStore(File(temp.root, "sealed"), Box), sessions, backgroundScope, { runner }) { _, _ -> error("never connect") }
        runCurrent()
        assertEquals("unknown", manager.find("source", task.id, null)!!.status)
        val note = sessions.history.value.single { it.id == task.id }
        assertTrue(note.text.contains("Checked project files"))
        assertEquals("complete", note.state)
        assertEquals("unknown", manager.cancel("source", task.id, null).status)
        assertTrue(runner.sent.isEmpty())
        assertTrue(runner.stops.isEmpty())
    }

    @Test fun failureOutcomeIsMirroredAsFailureInsteadOfSuccess() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        sessions.append(ChatMessage("failure", "child-1", "system", "SSH channel lost", 1))
        runner.outcomes.value = mapOf("child-1" to RunState(RunPhase.ERROR, "child-1", "SSH channel lost"))
        runCurrent()
        assertEquals("failed", manager.find("source", task.id, null)!!.status)
        assertEquals("SSH channel lost", manager.find("source", task.id, null)!!.result)
    }

    @Test fun unconfirmedComputerInterruptionIsUnknownAndCannotBeReplayed() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        manager.cancel("source", task.id, null)
        runner.outcomes.value = mapOf("child-1" to RunState(sessionId = "child-1", status = "Stopped", stopConfirmed = false))
        runCurrent()
        val unknown = manager.find("source", task.id, null)!!
        assertEquals("unknown", unknown.status)
        assertTrue(unknown.progress.contains("could not be confirmed"))
        assertEquals(task.id, manager.start("source", "key", computer, "/src", "go", null) { true }.id)
        assertEquals(1, runner.sent.size)
    }

    @Test fun aLostComputerConnectionKeepsAnUnknownReceiptWithoutReplacement() = runTest {
        val store = store(); val sessions = Sessions(temp.root); val runner = Runner()
        val manager = ComputerTasks(store, sessions, backgroundScope, { runner }) { _, _ -> }
        val task = manager.start("source", "key", computer, "/src", "go", null) { true }
        runCurrent()
        sessions.append(ChatMessage("failure", "child-1", "system", "Computer connection closed", 1))
        runner.outcomes.value = mapOf("child-1" to RunState(RunPhase.ERROR, "child-1", "Computer connection closed", outcomeUnknown = true))
        runCurrent()
        val unknown = manager.find("source", task.id, null)!!
        assertEquals("unknown", unknown.status)
        assertTrue(unknown.progress.contains("remote outcome is unknown"))
        assertEquals(task.id, manager.start("source", "key", computer, "/src", "go", null) { true }.id)
        assertEquals(1, sessions.created)
        assertEquals(1, runner.sent.size)
    }
}
