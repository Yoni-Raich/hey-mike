package dev.androidagent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Several chats at once: each runs on its own, and the phone goes to one of them at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunsTest {
    @Test fun voiceReturnsTheScreenAtTheEndOfItsDelegatedTurn() = runTest {
        val rig = Rig(this)
        rig.runs.beginVoice("one", "voice-thread", rig.store.workspace("one"))
        runCurrent()
        rig.engine.emit(EngineEvent.TurnStarted("voice-thread", "voice-turn"))
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("read", "read_ui", buildJsonObject {}, "voice-thread", "voice-turn"))
        runCurrent()
        rig.runs.send("two", "Child phone task")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("child-tap", "tap", buildJsonObject {}, "thread-two", "turn-thread-two"))
        runCurrent()
        assertEquals("Waiting for the phone", rig.runs.stateOf("two")!!.status)
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "voice-thread", turnId = "voice-turn"))
        runCurrent()
        assertEquals(listOf("read", "child-tap"), rig.engine.answered)
        assertEquals("Listening", rig.runs.stateOf("one")!!.status)
        assertTrue(rig.runs.stateOf("one")!!.active)
        rig.close()
    }

    @Test fun voiceCanStartInspectSteerAndOpenAChildWhileAnotherChatOwnsTheScreen() = runTest {
        val rig = Rig(this)
        rig.runs.send("three", "Hold the screen")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("tap", "tap", buildJsonObject {}, "thread-three", "turn-thread-three"))
        runCurrent()
        rig.runs.beginVoice("one", "voice-thread", rig.store.workspace("one"))
        rig.engine.emit(EngineEvent.TurnStarted("voice-thread", "voice-turn"))
        runCurrent()
        val start = buildJsonObject {
            put("mode", "start"); put("requestId", "voice-task"); put("title", "Read notes"); put("task", "  Read notes\nexactly  ")
            put("engine", "codex"); put("model", "model-a"); put("reasoningEffort", "high"); put("project", "X")
        }
        rig.engine.emit(EngineEvent.ToolCall("start-child", SessionAgentTools.NAME, start, "voice-thread", "voice-turn"))
        runCurrent()
        val child = rig.agents.tasks.value.single().sessionId!!
        assertTrue(rig.engine.results.getValue("start-child").success)
        assertTrue(rig.engine.prompts.contains("  Read notes\nexactly  "))
        assertEquals("model-a", rig.engine.openedModels.last())
        assertEquals("high", rig.engine.efforts.last())
        assertEquals("one", rig.store.getSession(child)!!.parentSessionId)
        assertEquals(1, rig.tools.executions)
        rig.engine.emit(EngineEvent.ToolCall("update", SessionAgentTools.NAME, buildJsonObject {
            put("mode", "message"); put("sessionId", child); put("messageId", "m1"); put("message", "Also check the end")
        }, "voice-thread", "voice-turn"))
        runCurrent()
        assertEquals(listOf("Also check the end"), rig.engine.steered)
        rig.engine.emit(EngineEvent.MessageCompleted("Child result", "thread-$child", "turn-thread-$child", "answer", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-$child", turnId = "turn-thread-$child"))
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("status", SessionAgentTools.NAME, buildJsonObject {
            put("mode", "status"); put("sessionId", child)
        }, "voice-thread", "voice-turn"))
        rig.engine.emit(EngineEvent.ToolCall("open", SessionAgentTools.NAME, buildJsonObject {
            put("mode", "open"); put("sessionId", child)
        }, "voice-thread", "voice-turn"))
        runCurrent()
        assertTrue(rig.engine.results.getValue("status").text.contains("Child result"))
        assertEquals(listOf(child), rig.opened)
        assertTrue(rig.runs.stateOf("one")!!.active)
        assertTrue(rig.runs.stateOf("three")!!.active)
        assertEquals(1, rig.tools.executions)
        rig.close()
    }

    @Test fun stoppingARootInterruptsGrandchildrenAndKeepsAnotherRootRunning() = runTest {
        val rig = Rig(this)
        rig.store.sessions.value += ChatSession("four", "four", 0, 0)
        rig.runs.linkChild("two", "one")
        rig.runs.linkChild("three", "two")
        rig.runs.send("two", "Child")
        rig.runs.send("three", "Grandchild")
        rig.runs.send("four", "Other root")
        runCurrent()
        rig.runs.stop("one")
        runCurrent()
        assertEquals(setOf("thread-two", "thread-three"), rig.engine.interrupted.toSet())
        assertTrue(rig.runs.stateOf("four")!!.active)
        rig.close()
    }

    @Test fun sourceStopFencesAChildThatHasNotDispatchedYet() = runTest {
        var dispatchAllowed = true
        val stopped = mutableListOf<String?>()
        val rig = Rig(this, onStopSession = { id -> stopped += id; dispatchAllowed = false })
        rig.runs.stop("one")
        assertFalse(rig.runs.sendChild("two", "one", "Exact task", allowed = { dispatchAllowed }))
        runCurrent()
        assertEquals(listOf("one"), stopped)
        assertTrue(rig.engine.started.isEmpty())
        assertTrue(rig.runs.childrenOf("one").isEmpty())
        rig.close()
    }

    @Test fun repeatedChildDispatchDoesNotBecomeSteering() = runTest {
        val rig = Rig(this)
        assertTrue(rig.runs.sendChild("two", "one", "Exact task"))
        assertFalse(rig.runs.sendChild("two", "one", "Exact task"))
        runCurrent()
        assertEquals(listOf("thread-two"), rig.engine.started)
        assertTrue(rig.engine.steered.isEmpty())
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-two", turnId = "turn-thread-two"))
        runCurrent()
        assertFalse(rig.runs.sendChild("two", "one", "Exact task"))
        runCurrent()
        assertEquals(listOf("thread-two"), rig.engine.started)
        rig.close()
    }

    @Test fun childLinksAreIdempotentSupportTreesAndRefuseReparentingOrCycles() = runTest {
        val rig = Rig(this)
        rig.runs.linkChild("two", "one")
        rig.runs.linkChild("two", "one")
        assertEquals(listOf("two"), rig.runs.childrenOf("one"))
        assertTrue(runCatching { rig.runs.linkChild("two", "three") }.exceptionOrNull() is IllegalArgumentException)
        rig.runs.linkChild("three", "two")
        assertEquals(listOf("one", "two", "three"), rig.runs.family("one"))
        assertEquals(listOf("two", "one"), rig.runs.ancestorsOf("three"))
        assertTrue(runCatching { rig.runs.linkChild("one", "three") }.exceptionOrNull() is IllegalArgumentException)
        rig.runs.unlinkChild("two")
        assertEquals(listOf("one"), rig.runs.family("one"))
        assertEquals(listOf("two", "three"), rig.runs.family("two"))
        assertEquals(listOf("two"), rig.runs.ancestorsOf("three"))
        rig.close()
    }

    @Test fun sourceStopInterruptsItsChildAndLeavesUnrelatedChatsRunning() = runTest {
        val rig = Rig(this)
        rig.runs.linkChild("two", "one")
        rig.runs.send("two", "Computer task")
        rig.runs.send("three", "Unrelated task")
        runCurrent()
        rig.runs.stop("one")
        runCurrent()
        assertEquals(listOf("thread-two"), rig.engine.interrupted)
        assertTrue(rig.runs.stateOf("three")!!.active)
        assertFalse(rig.engine.closed)
        assertEquals("Stopped", rig.runs.outcomes.value["two"]!!.status)
        rig.close()
    }

    @Test fun userStopForAVoiceSourceStopsItsChildAndNotifiesPendingWorkWithoutStoppingAnotherChat() = runTest {
        val stopped = mutableListOf<String?>()
        val rig = Rig(this, onStopSession = { stopped += it })
        rig.runs.beginVoice("one", "voice-thread", rig.store.workspace("one"))
        rig.runs.sendChild("two", "one", "Computer task")
        rig.runs.send("three", "Unrelated task")
        runCurrent()
        // No voice turn is active; the child owns the only turn to interrupt.
        rig.runs.stop("one")
        runCurrent()
        assertEquals(listOf("one"), stopped)
        assertEquals(listOf("thread-two"), rig.engine.interrupted)
        assertNull(rig.runs.stateOf("one"))
        assertNull(rig.runs.stateOf("two"))
        assertTrue(rig.runs.stateOf("three")!!.active)
        assertEquals(true, rig.runs.outcomes.value["two"]!!.stopConfirmed)
        assertFalse(rig.engine.closed)
        rig.close()
    }

    @Test fun sourceStopInterruptsAStartedChildBeforeTheStartReplyArrives() = runTest {
        val rig = Rig(this)
        rig.engine.startGates["thread-two"] = CompletableDeferred()
        rig.runs.sendChild("two", "one", "Computer task")
        rig.runs.send("three", "Unrelated task")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnStarted("thread-two", "turn-thread-two"))
        rig.engine.emit(EngineEvent.ToolCall("early-tool", "tap", buildJsonObject {}, "thread-two", "turn-thread-two"))
        runCurrent()
        assertTrue(rig.engine.answered.isEmpty())
        rig.runs.stop("one")
        runCurrent()
        assertEquals(listOf("thread-two"), rig.engine.interrupted)
        assertEquals(true, rig.runs.outcomes.value["two"]!!.stopConfirmed)
        assertTrue(rig.runs.stateOf("three")!!.active)
        assertEquals(0, rig.tools.executions)
        assertFalse(rig.engine.closed)
        rig.close()
    }

    @Test fun aStartReplyAfterStopInterruptsTheOriginalTurnWithoutChangingAReusedSlot() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>()
        rig.engine.startGates["thread-two"] = gate
        rig.engine.nonCancellableStarts += "thread-two"
        rig.runs.sendChild("two", "one", "Computer task")
        rig.runs.send("three", "Unrelated task")
        runCurrent()
        rig.runs.stop("one")
        runCurrent()
        assertEquals(false, rig.runs.outcomes.value["two"]!!.stopConfirmed)
        assertTrue(rig.engine.interrupted.isEmpty())
        val newStart = CompletableDeferred<Unit>()
        rig.engine.startGates["thread-one"] = newStart
        rig.runs.send("one", "Use the stopped slot")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnStarted("thread-one", "turn-thread-one"))
        rig.engine.emit(EngineEvent.ToolCall("new-tool", "contacts", buildJsonObject {}, "thread-one", "turn-thread-one"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("thread-two"), rig.engine.interrupted)
        newStart.complete(Unit)
        runCurrent()
        assertEquals(listOf("new-tool"), rig.engine.answered)
        assertTrue(rig.runs.stateOf("one")!!.active)
        assertTrue(rig.runs.stateOf("three")!!.active)
        assertFalse(rig.engine.closed)
        rig.close()
    }

    @Test fun failedChildInterruptionDoesNotClaimConfirmedCancellationOrCloseAnotherChat() = runTest {
        val rig = Rig(this)
        rig.engine.failedInterrupts += "thread-two"
        rig.runs.sendChild("two", "one", "Computer task")
        rig.runs.send("three", "Unrelated task")
        runCurrent()
        rig.runs.stop("one")
        runCurrent()
        assertEquals(false, rig.runs.outcomes.value["two"]!!.stopConfirmed)
        assertTrue(rig.runs.outcomes.value["two"]!!.status.contains("could not be confirmed"))
        assertTrue(rig.runs.stateOf("three")!!.active)
        assertFalse(rig.engine.closed)
        rig.close()
    }

    @Test fun childApprovalCanBeAnsweredFromTheSourceWithoutAutoApproval() = runTest {
        val rig = Rig(this)
        rig.runs.linkChild("two", "one")
        rig.runs.send("two", "Computer task")
        runCurrent()
        rig.engine.emit(EngineEvent.Approval("request", "item/commandExecution/requestApproval", buildJsonObject {}, "thread-two", "turn-thread-two"))
        runCurrent()
        assertTrue(rig.engine.approvals.isEmpty())
        assertFalse(rig.runs.answerApprovalByReply("yes", sessionId = "three"))
        assertTrue(rig.runs.answerApprovalByReply("no", sessionId = "one"))
        runCurrent()
        assertEquals(listOf("request" to false), rig.engine.approvals)
        rig.close()
    }

    @Test fun aSourceReplyAnswersItsOwnApprovalBeforeAnOlderChildSlot() = runTest {
        val rig = Rig(this)
        rig.runs.sendChild("two", "one", "Computer task")
        rig.runs.send("one", "Source task")
        runCurrent()
        rig.engine.emit(EngineEvent.Approval("child", "item/commandExecution/requestApproval", buildJsonObject {}, "thread-two", "turn-thread-two"))
        rig.engine.emit(EngineEvent.Approval("source", "item/commandExecution/requestApproval", buildJsonObject {}, "thread-one", "turn-thread-one"))
        runCurrent()
        assertTrue(rig.runs.answerApprovalByReply("yes", sessionId = "one"))
        runCurrent()
        assertEquals(listOf("source" to true), rig.engine.approvals)
        assertEquals("child", rig.runs.stateOf("two")!!.approval!!.requestId)
        rig.close()
    }

    @Test fun firstChildQuestionKeepsAReplyFromAnsweringAnotherChildApproval() = runTest {
        val rig = Rig(this)
        // Link order differs from coordinator slot order.
        rig.runs.linkChild("three", "one")
        rig.runs.linkChild("two", "one")
        rig.runs.send("two", "Approval")
        rig.runs.send("three", "Question")
        runCurrent()
        rig.engine.emit(EngineEvent.Approval("child-approval", "item/commandExecution/requestApproval", buildJsonObject {}, "thread-two", "turn-thread-two"))
        rig.engine.emit(EngineEvent.ToolCall("child-question", ChatTools.ASK,
            buildJsonObject { put("question", "Should I continue?") }, "thread-three", "turn-thread-three"))
        runCurrent()
        assertFalse(rig.runs.answerApprovalByReply("yes", sessionId = "one"))
        assertTrue(rig.runs.answerQuestionByReply("yes", sessionId = "one"))
        runCurrent()
        assertTrue(rig.engine.approvals.isEmpty())
        assertTrue("child-question" in rig.engine.answered)
        assertEquals("yes", rig.store.messages.last { it.sessionId == "three" && it.role == "user" }.text)
        rig.close()
    }

    @Test fun sourceQuestionKeepsAReplyFromAnsweringAChildApproval() = runTest {
        val rig = Rig(this)
        rig.runs.sendChild("two", "one", "Computer task")
        rig.runs.send("one", "Source task")
        runCurrent()
        rig.engine.emit(EngineEvent.Approval("child-approval", "item/commandExecution/requestApproval", buildJsonObject {}, "thread-two", "turn-thread-two"))
        rig.engine.emit(EngineEvent.ToolCall("source-question", ChatTools.ASK,
            buildJsonObject { put("question", "Should I continue?") }, "thread-one", "turn-thread-one"))
        runCurrent()
        assertFalse(rig.runs.answerApprovalByReply("no", sessionId = "one"))
        assertTrue(rig.runs.answerQuestionByReply("no", sessionId = "one"))
        runCurrent()
        assertTrue(rig.engine.approvals.isEmpty())
        assertTrue("source-question" in rig.engine.answered)
        rig.close()
    }

    @Test fun finishedOutcomeSurvivesSlotReuse() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-one", turnId = "turn-thread-one"))
        runCurrent()
        rig.runs.send("two", "Next")
        runCurrent()
        assertEquals(1, rig.runs.slots.size)
        assertEquals("Ready", rig.runs.outcomes.value["one"]!!.status)
        rig.close()
    }

    @Test fun newRunDoesNotExposeAnOldOutcomeFromAnotherCoordinator() = runTest {
        val rig = Rig(this)
        rig.runs.send("one", "First")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-one", turnId = "turn-thread-one"))
        runCurrent()
        rig.runs.send("two", "Use the old slot")
        rig.runs.send("one", "Next run in a new slot")
        runCurrent()
        assertEquals(2, rig.runs.slots.size)
        assertFalse(rig.runs.outcomes.value.containsKey("one"))
        rig.engine.emit(EngineEvent.Failure("Second run failed", "thread-one", "turn-thread-one"))
        runCurrent()
        assertEquals(RunPhase.ERROR, rig.runs.outcomes.value["one"]!!.phase)
        assertEquals("Second run failed", rig.runs.outcomes.value["one"]!!.status)
        rig.close()
    }

    @Test fun anOutcomeIsPublishedAfterTheFinalHistoryWriteCompletes() = runTest {
        val rig = Rig(this)
        rig.runs.sendChild("two", "one", "Computer task")
        runCurrent()
        rig.engine.emit(EngineEvent.TextDelta("Final result", "thread-two", "turn-thread-two", "answer"))
        runCurrent()
        val saved = CompletableDeferred<Unit>()
        rig.store.updateGate = saved
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread-two", turnId = "turn-thread-two"))
        runCurrent()
        assertFalse(rig.runs.outcomes.value.containsKey("two"))
        saved.complete(Unit)
        runCurrent()
        assertEquals("Final result", rig.store.assistant("two").single())
        assertEquals("Ready", rig.runs.outcomes.value["two"]!!.status)
        rig.close()
    }

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

    @Test fun uncertainComputerFailureRetainsUnknownOutcomeOnlyForItsChild() = runTest {
        val rig = Rig(this)
        rig.runs.sendChild("two", "one", "Computer task")
        rig.runs.send("three", "Unrelated task")
        runCurrent()
        rig.engine.emit(EngineEvent.Failure("Computer disconnected", "thread-two", uncertain = true))
        runCurrent()
        assertTrue(rig.runs.outcomes.value["two"]!!.outcomeUnknown)
        assertEquals(RunPhase.ERROR, rig.runs.outcomes.value["two"]!!.phase)
        assertTrue(rig.runs.stateOf("three")!!.active)
        assertFalse(rig.engine.closed)
        rig.close()
    }

    @Test fun uncertainDisconnectEndsAChildWhoseStartReplyIsStillPending() = runTest {
        val rig = Rig(this)
        rig.engine.startGates["thread-two"] = CompletableDeferred()
        rig.runs.sendChild("two", "one", "Computer task")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnStarted("thread-two", "turn-thread-two"))
        rig.engine.emit(EngineEvent.Failure("Computer disconnected", "thread-two", uncertain = true))
        runCurrent()
        // The start call is still blocked. A transport normally fails it too.
        assertEquals(RunPhase.ERROR, rig.runs.slots.first().state.value.phase)
        assertTrue(rig.runs.slots.first().state.value.outcomeUnknown)
        rig.engine.startGates.getValue("thread-two").complete(Unit)
        runCurrent()
        assertTrue(rig.runs.outcomes.value["two"]!!.outcomeUnknown)
        rig.close()
    }

    @Test fun lostStartReplyIsUnknownButAFailureBeforeOpeningTheThreadIsKnown() = runTest {
        val rig = Rig(this)
        rig.engine.failedStarts += "thread-two"
        rig.runs.sendChild("two", "one", "Computer task")
        runCurrent()
        assertTrue(rig.runs.outcomes.value["two"]!!.outcomeUnknown)
        rig.engine.failedOpens += "session-three"
        rig.runs.send("three", "Cannot open")
        runCurrent()
        assertFalse(rig.runs.outcomes.value["three"]!!.outcomeUnknown)
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

    private class Rig(test: TestScope, onStopSession: (String?) -> Unit = {}) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val engine = Engine()
        val store = Store()
        val overlay = Overlay()
        val tools = Tools()
        lateinit var agents: SessionAgents
        val runs = AgentRuns(scope, engine, onStopSession = { id -> agents.stop(id); onStopSession(id) }) { share ->
            AgentCoordinator(scope, engine, store, tools, overlay, share = share, sessionAgents = { agents })
        }
        val opened = mutableListOf<String>()
        init { agents = SessionAgents(store, scope, { RunsSessionAgentRunner(runs) }, openChat = { opened += it }) }
        val queue = SessionRunQueue(scope, runs, store)
        fun close() { scope.cancel() }
    }

    private class Engine : AgentEngine {
        private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val events = stream.asSharedFlow()
        val started = mutableListOf<String>()
        val answered = mutableListOf<String>()
        val results = mutableMapOf<String, ToolResult>()
        val prompts = mutableListOf<String>()
        val openedModels = mutableListOf<String?>()
        val efforts = mutableListOf<String?>()
        val interrupted = mutableListOf<String>()
        val steered = mutableListOf<String>()
        val approvals = mutableListOf<Pair<String, Boolean>>()
        val startGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val nonCancellableStarts = mutableSetOf<String>()
        val failedInterrupts = mutableSetOf<String>()
        val failedStarts = mutableSetOf<String>()
        val failedOpens = mutableSetOf<String>()
        var closed = false
        suspend fun emit(value: EngineEvent) = stream.emit(value)
        override suspend fun connect() = Unit
        override suspend fun account() = AccountStatus(true, "Test")
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("test")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>): String {
            check(workspace.name !in failedOpens) { "Project could not be opened" }
            openedModels += model
            return "thread-" + workspace.name.removePrefix("session-")
        }
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String {
            started += threadId
            prompts += prompt
            check(threadId !in failedStarts) { "Computer disconnected before the start reply" }
            startGates[threadId]?.let { gate ->
                if (threadId in nonCancellableStarts) withContext(NonCancellable) { gate.await() }
                else gate.await()
            }
            return "turn-$threadId"
        }
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>, reasoningEffort: String?): String {
            efforts += reasoningEffort
            return startTurn(threadId, prompt, images)
        }
        override suspend fun steer(threadId: String, turnId: String, prompt: String) { steered += prompt }
        override suspend fun interrupt(threadId: String, turnId: String) {
            interrupted += threadId
            check(threadId !in failedInterrupts) { "Computer connection ended" }
        }
        override suspend fun answerTool(requestId: String, result: ToolResult) { answered += requestId; results[requestId] = result }
        override suspend fun answerApproval(requestId: String, allow: Boolean) { approvals += requestId to allow }
        override suspend fun close() { closed = true }
    }

    private class Store : SessionStore {
        var queued = emptyList<QueuedTurn>()
        override suspend fun loadQueuedTurns() = queued
        override suspend fun saveQueuedTurns(turns: List<QueuedTurn>) { queued = turns }
        override val sessions = MutableStateFlow(listOf("one", "two", "three").map { ChatSession(it, it, 0, 0) })
        val messages = mutableListOf<ChatMessage>()
        var updateGate: CompletableDeferred<Unit>? = null
        fun assistant(sessionId: String) = messages.filter { it.sessionId == sessionId && it.role == "assistant" }.map { it.text }
        override suspend fun createSession(engine: EngineKind) = sessions.value.first()
        private val tasks = mutableMapOf<String, SessionAgentTask>()
        override suspend fun loadAgentTasks() = tasks.values.toList()
        override suspend fun saveAgentTask(task: SessionAgentTask) { tasks[task.id] = task }
        override suspend fun createChildSession(parentSessionId: String, engine: EngineKind, title: String): ChatSession {
            val child = ChatSession("child${tasks.size}", title, 0, 0, engine = engine, parentSessionId = parentSessionId)
            sessions.value = sessions.value + child
            return child
        }
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(messages.filter { it.sessionId == sessionId })
        override suspend fun append(message: ChatMessage) { messages.add(message) }
        override suspend fun updateMessage(id: String, text: String, state: String) {
            updateGate?.await()
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
