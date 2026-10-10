package dev.androidagent.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAgentsTest {
    private fun start(key: String = "r1", task: String = "  exact\ntask  ") = buildJsonObject {
        put("mode", "start"); put("requestId", key); put("title", "Notes"); put("task", task)
    }
    private fun operation(mode: String, child: String) = buildJsonObject { put("mode", mode); put("sessionId", child) }

    @Test fun retriesReuseAChildAndRejectDifferentWorkWithoutRewritingTheTask() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertTrue(rig.agents.invoke("root", start()) { true }.success)
        val task = rig.agents.tasks.value.single()
        assertTrue(rig.agents.invoke("root", start()) { true }.success)
        assertFalse(rig.agents.invoke("root", start(task = "Different")) { true }.success)
        assertEquals(1, rig.runner.sent.size)
        assertEquals("  exact\ntask  ", rig.runner.sent.single().second)
        assertEquals(EngineKind.CLAUDE, task.engine)
        assertEquals("root", rig.store.getSession(task.sessionId!!)!!.parentSessionId)
        assertEquals(2, rig.store.sessions.value.size)
        rig.close()
    }

    @Test fun aMessageIsIdempotentAndAnIdleChildContinuesInItsSameSession() = runTest {
        val rig = Rig(this); runCurrent()
        rig.agents.invoke("root", start()) { true }
        val child = rig.agents.tasks.value.single().sessionId!!
        rig.runner.complete(child, rig.store, "First result"); runCurrent()
        assertEquals("completed", rig.agents.tasks.value.single().status)
        val update = buildJsonObject { put("mode", "message"); put("sessionId", child); put("messageId", "m1"); put("message", "  Continue\nexactly ") }
        assertTrue(rig.agents.invoke("root", update) { true }.success)
        assertTrue(rig.agents.invoke("root", update) { true }.success)
        assertEquals(2, rig.runner.sent.size)
        assertEquals(child, rig.runner.sent.last().first)
        assertEquals("  Continue\nexactly ", rig.runner.sent.last().second)
        assertEquals("running", rig.agents.tasks.value.single().status)
        runCurrent()
        assertEquals("running", rig.agents.tasks.value.single().status)
        rig.runner.complete(child, rig.store, "Second result"); runCurrent()
        assertEquals("Second result", rig.agents.tasks.value.single().result)
        rig.close()
    }

    @Test fun unrelatedChatsCannotInspectMessageCancelOrOpenAChild() = runTest {
        val rig = Rig(this); runCurrent()
        rig.store.sessions.value += ChatSession("other", "Other", 0, 0)
        rig.agents.invoke("root", start()) { true }
        val child = rig.agents.tasks.value.single().sessionId!!
        listOf("status", "cancel", "open", "message").forEach { mode ->
            assertFalse(rig.agents.invoke("other", operation(mode, child)) { true }.success)
        }
        assertTrue(rig.runner.stopped.isEmpty())
        assertTrue(rig.opened.isEmpty())
        rig.close()
    }

    @Test fun stopDuringPlaceSetupPreventsDispatchEvenBeforeAChildOwnsARun() = runTest {
        val place = CompletableDeferred<Unit>()
        val rig = Rig(this, place = { place.await() }); runCurrent()
        val request = async { rig.agents.invoke("root", start()) { true } }
        runCurrent()
        assertEquals("starting", rig.agents.tasks.value.single().status)
        rig.agents.stop("root")
        place.complete(Unit)
        runCurrent()
        assertTrue(request.await().success)
        assertEquals("cancelled", rig.agents.tasks.value.single().status)
        assertTrue(rig.runner.sent.isEmpty())
        rig.close()
    }

    @Test fun stoppingPreparingChildItselfPreventsDispatchWithoutStoppingItsParent() = runTest {
        val place = CompletableDeferred<Unit>()
        val rig = Rig(this, place = { place.await() }); runCurrent()
        val request = async { rig.agents.invoke("root", start()) { true } }
        runCurrent()
        val child = rig.agents.tasks.value.single().sessionId!!
        rig.agents.stop(child)
        place.complete(Unit); runCurrent()
        assertTrue(request.await().success)
        assertEquals("cancelled", rig.agents.tasks.value.single().status)
        assertTrue(rig.runner.sent.isEmpty())
        rig.close()
    }

    @Test fun restartMarksUnfinishedWorkUnknownAndDoesNotReplayStartOrMessage() = runTest {
        val first = Rig(this); runCurrent()
        first.agents.invoke("root", start()) { true }
        val child = first.agents.tasks.value.single().sessionId!!
        first.close()
        val restarted = Rig(this, first.store); runCurrent()
        assertEquals("unknown", restarted.agents.tasks.value.single().status)
        assertTrue(restarted.agents.invoke("root", start()) { true }.success)
        assertFalse(restarted.agents.invoke("root", buildJsonObject {
            put("mode", "message"); put("sessionId", child); put("messageId", "m1"); put("message", "Retry")
        }) { true }.success)
        assertTrue(restarted.runner.sent.isEmpty())
        assertEquals(listOf(child to "root"), restarted.runner.links)
        restarted.close()
    }

    @Test fun userContinuationIsObservedAndUnconfirmedStopStaysUnknown() = runTest {
        val rig = Rig(this); runCurrent()
        rig.agents.invoke("root", start()) { true }
        val child = rig.agents.tasks.value.single().sessionId!!
        rig.runner.complete(child, rig.store, "First result"); runCurrent()
        rig.runner.dispatch(child, "root", "User continuation", null, EngineKind.CLAUDE, false) { true }
        runCurrent()
        assertEquals("running", rig.agents.tasks.value.single().status)
        rig.runner.finish(child, RunState(sessionId = child, status = "Stopped locally", stopConfirmed = false,
            runId = rig.runner.state(child)!!.runId)); runCurrent()
        assertEquals("unknown", rig.agents.tasks.value.single().status)
        rig.close()
    }

    @Test fun childNeedsUserStateAndResultArePublishedInItsParentWithoutAutoApproval() = runTest {
        val rig = Rig(this); runCurrent()
        rig.agents.invoke("root", start()) { true }
        val child = rig.agents.tasks.value.single().sessionId!!
        rig.runner.live[child] = rig.runner.live.getValue(child).copy(question = UserQuestion("q1", "Which file?"))
        rig.runner.tick(); runCurrent()
        assertEquals("waiting_for_user", rig.agents.tasks.value.single().status)
        assertEquals("Which file?", rig.runner.state(child)!!.question!!.question)
        val status = rig.agents.invoke("root", operation("status", child)) { true }
        assertTrue(status.text.contains("Which file?"))
        assertTrue(rig.agents.invoke("root", buildJsonObject {
            put("mode", "message"); put("sessionId", child); put("messageId", "m1"); put("message", "Also check notes")
        }) { true }.success)
        assertTrue(rig.agents.invoke("root", operation("status", child)) { true }.text.contains("waiting_for_user"))
        assertEquals("Which file?", rig.runner.state(child)!!.question!!.question)
        assertTrue(rig.agents.invoke("root", operation("open", child)) { true }.success)
        assertEquals(listOf(child), rig.opened)
        assertEquals(1, rig.store.history.count { it.sessionId == "root" && it.role == "subagent" })
        rig.close()
    }

    @Test fun depthLimitIsBoundedAndRootCanManageAGrandchild() = runTest {
        val rig = Rig(this); runCurrent()
        var source = "root"
        repeat(4) { index ->
            assertTrue(rig.agents.invoke(source, start("r$index")) { true }.success)
            source = rig.agents.tasks.value.last().sessionId!!
        }
        assertFalse(rig.agents.invoke(source, start("too-deep")) { true }.success)
        assertTrue(rig.agents.invoke("root", operation("status", source)) { true }.success)
        assertEquals(4, rig.runner.sent.size)
        rig.close()
    }

    @Test fun lostDispatchReplyAndUpdateReplyNeverReplayTheReservedRequest() = runTest {
        val rig = Rig(this); runCurrent()
        rig.runner.failNext = true
        assertFalse(rig.agents.invoke("root", start()) { true }.success)
        assertEquals("unknown", rig.agents.tasks.value.single().status)
        assertTrue(rig.agents.invoke("root", start()) { true }.success)
        assertEquals(1, rig.runner.sent.size)
        assertTrue(rig.agents.invoke("root", start("second")) { true }.success)
        val child = rig.agents.tasks.value.last().sessionId!!
        rig.runner.complete(child, rig.store, "Done"); runCurrent()
        val update = buildJsonObject { put("mode", "message"); put("sessionId", child); put("messageId", "m1"); put("message", "Follow up") }
        rig.runner.failNext = true
        assertFalse(rig.agents.invoke("root", update) { true }.success)
        assertEquals("unknown", rig.agents.tasks.value.last().status)
        assertTrue(rig.agents.invoke("root", update) { true }.success)
        assertEquals(3, rig.runner.sent.size)
        rig.close()
    }

    @Test fun followingUpUsesTheChildsCurrentEngineAndDeletedParentDetachesLiveLinks() = runTest {
        val rig = Rig(this); runCurrent()
        rig.agents.invoke("root", start()) { true }
        val child = rig.agents.tasks.value.single().sessionId!!
        rig.runner.complete(child, rig.store, "Done"); runCurrent()
        rig.store.sessions.value = rig.store.sessions.value.map { if (it.id == child) it.copy(engine = EngineKind.CODEX) else it }
        assertTrue(rig.agents.invoke("root", buildJsonObject {
            put("mode", "message"); put("sessionId", child); put("messageId", "m1"); put("message", "Continue")
        }) { true }.success)
        assertEquals(EngineKind.CODEX, rig.runner.engines.last())
        rig.store.sessions.value = rig.store.sessions.value.filter { it.id != "root" }.map { it.copy(parentSessionId = null) }
        runCurrent()
        assertTrue(rig.runner.links.isEmpty())
        rig.close()
    }

    @Test fun twoProjectsKeepIndependentModelEffortAndFollowupsUseCurrentSessionChoices() = runTest {
        val rig = Rig(this); runCurrent()
        fun configured(key: String, engine: String, model: String, effort: String, project: String) = buildJsonObject {
            start(key).forEach { (k, v) -> put(k, v) }
            put("engine", engine); put("model", model); put("reasoningEffort", effort); put("computer", "Pc"); put("project", project)
        }
        val first = configured("x", "codex", "model-a", "high", "X")
        assertTrue(rig.agents.invoke("root", first) { true }.success)
        assertTrue(rig.agents.invoke("root", configured("y", "claude", "model-b", "medium", "Y")) { true }.success)
        val x = rig.agents.tasks.value.first(); val y = rig.agents.tasks.value.last()
        assertEquals(listOf("high", "medium"), rig.runner.efforts)
        assertEquals(listOf("model-a", "model-b"), rig.runner.models)
        assertEquals("X", x.project); assertEquals("Y", y.project)
        assertEquals("high", rig.store.getSession(x.sessionId!!)!!.reasoningEffort)
        assertEquals("medium", rig.store.getSession(y.sessionId!!)!!.reasoningEffort)
        listOf(configured("x", "codex", "model-a", "low", "X"), configured("x", "codex", "model-a", "high", "Y"),
            configured("x", "codex", "model-c", "high", "X")).forEach {
            assertFalse(rig.agents.invoke("root", it) { true }.success)
        }
        assertEquals(2, rig.runner.sent.size)
        rig.runner.complete(x.sessionId, rig.store, "Done"); runCurrent()
        rig.store.setModelChoice(x.sessionId, "model-c", null) // User selected Auto.
        assertTrue(rig.agents.invoke("root", buildJsonObject {
            put("mode", "message"); put("sessionId", x.sessionId); put("messageId", "follow"); put("message", "Continue")
        }) { true }.success)
        assertEquals("model-c", rig.runner.models.last())
        assertNull(rig.runner.efforts.last())
        rig.close()
    }

    @Test fun concurrentRetriesStartOnceAndRevokedCallsCannotOpenOrDispatch() = runTest {
        val rig = Rig(this); runCurrent()
        val replies = listOf(async { rig.agents.invoke("root", start()) { true } }, async { rig.agents.invoke("root", start()) { true } })
        replies.forEach { assertTrue(it.await().success) }
        assertEquals(1, rig.runner.sent.size)
        val child = rig.agents.tasks.value.single().sessionId!!
        assertFalse(rig.agents.invoke("root", operation("open", child)) { false }.success)
        assertFalse(rig.agents.invoke("root", start("blocked")) { false }.success)
        assertTrue(rig.opened.isEmpty())
        assertEquals(2, rig.store.sessions.value.size)
        rig.close()
    }

    private class Rig(test: TestScope, val store: Store = Store(), place: suspend () -> Unit = {}) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val runner = Runner()
        val opened = mutableListOf<String>()
        val agents = SessionAgents(store, scope, { runner }, prepareChild = { place() }, openChat = { opened += it })
        fun close() = scope.cancel()
    }
    private class Runner : SessionAgentRunner {
        private val signals = MutableStateFlow(0)
        override val changes = signals
        val live = mutableMapOf<String, RunState>()
        val ended = mutableMapOf<String, RunState>()
        val sent = mutableListOf<Pair<String, String>>()
        val engines = mutableListOf<EngineKind>()
        val models = mutableListOf<String?>()
        val efforts = mutableListOf<String?>()
        var failNext = false
        val stopped = mutableListOf<String>()
        val links = mutableListOf<Pair<String, String>>()
        fun tick() { signals.value++ }
        override fun link(child: String, parent: String) { if (child to parent !in links) links += child to parent }
        override fun unlink(child: String) { links.removeAll { it.first == child } }
        override fun state(sessionId: String) = live[sessionId]
        override fun outcome(sessionId: String) = ended[sessionId]
        override fun dispatch(child: String, parent: String, message: String, model: String?, engine: EngineKind, first: Boolean, reasoningEffort: String?, allowed: () -> Boolean): String? {
            if (!allowed()) return null
            sent += child to message
            engines += engine
            models += model; efforts += reasoningEffort
            if (failNext) { failNext = false; throw IllegalStateException("Reply lost") }
            val run = live[child]?.runId ?: "run-${sent.size}"
            live[child] = live[child] ?: RunState(RunPhase.THINKING, child, "Working", runId = run)
            tick(); return run
        }
        override fun stop(sessionId: String) { stopped += sessionId; live[sessionId]?.let { finish(sessionId, it.copy(phase = RunPhase.IDLE, status = "Stopped", stopConfirmed = true)) } }
        fun finish(child: String, state: RunState) { live.remove(child); ended[child] = state; tick() }
        suspend fun complete(child: String, store: Store, result: String) {
            store.append(ChatMessage("reply-${sent.size}", child, "assistant", result, sent.size.toLong()))
            finish(child, RunState(sessionId = child, runId = live.getValue(child).runId))
        }
    }
    private class Store : SessionStore {
        override val sessions = MutableStateFlow(listOf(ChatSession("root", "Root", 0, 0, engine = EngineKind.CLAUDE)))
        val receipts = mutableMapOf<String, SessionAgentTask>()
        val history = mutableListOf<ChatMessage>()
        override suspend fun createSession(engine: EngineKind): ChatSession = error("Use child creation")
        override suspend fun createChildSession(parentSessionId: String, engine: EngineKind, title: String): ChatSession =
            ChatSession("child-${sessions.value.size}", title, 0, 0, engine = engine, parentSessionId = parentSessionId).also { sessions.value += it }
        override suspend fun loadAgentTasks() = receipts.values.toList()
        override suspend fun saveAgentTask(task: SessionAgentTask) { receipts[task.id] = task }
        override suspend fun setModelChoice(sessionId: String, model: String?, reasoningEffort: String?) {
            sessions.value = sessions.value.map { if (it.id == sessionId) it.copy(model = model, reasoningEffort = reasoningEffort) else it }
        }
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(history.filter { it.sessionId == sessionId })
        override suspend fun append(message: ChatMessage) { history += message }
        override suspend fun updateMessage(id: String, text: String, state: String) { val i = history.indexOfFirst { it.id == id }; if (i >= 0) history[i] = history[i].copy(text = text, state = state) }
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(sessionId)
    }
}
