/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AgentCoordinatorTest {
    @Test fun stopRevokesBeforeWaitingForEngineAndBlocksAnActionWaitingForOverlay() = runTest {
        val rig = Rig(this)
        rig.overlay.waitForShow = CompletableDeferred()
        rig.engine.waitForInterrupt = CompletableDeferred()
        rig.coordinator.send("one", "Open settings")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("1", "tap", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        assertEquals(0, rig.tools.executions)
        rig.coordinator.stop()
        assertTrue(rig.tools.revoked)
        assertEquals(RunPhase.STOPPING, rig.coordinator.state.value.phase)
        assertTrue(rig.overlay.states.any { it.phase == OverlayPhase.STOPPING })
        rig.overlay.waitForShow!!.complete(Unit)
        runCurrent()
        assertEquals(0, rig.tools.executions)
        advanceTimeBy(2_001)
        runCurrent()
        assertTrue(rig.engine.closed)
        assertFalse(rig.coordinator.state.value.active)
        assertEquals(OverlayPhase.DONE, rig.overlay.finished.last().phase)
        rig.close()
    }

    @Test fun lateEventsAfterStopCannotRunToolsOrAppendAssistantText() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Read the screen")
        runCurrent()
        rig.coordinator.stop()
        runCurrent()
        rig.engine.emit(EngineEvent.TextDelta("late output"))
        rig.engine.emit(EngineEvent.ToolCall("late", "tap", buildJsonObject {}))
        runCurrent()
        assertEquals(0, rig.tools.executions)
        assertFalse(rig.store.messages.any { it.text.contains("late output") })
        assertTrue(rig.engine.answers.any { !it.success && it.text.contains("stopped") })
        rig.close()
    }

    @Test fun anotherSessionCannotTakeOverAnActiveDeviceRun() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "First")
        runCurrent()
        rig.coordinator.send("two", "Second")
        runCurrent()
        assertEquals("one", rig.coordinator.state.value.sessionId)
        assertEquals(1, rig.engine.turns)
        assertFalse(rig.store.messages.any { it.sessionId == "two" })
        rig.close()
    }

    @Test fun selectedReasoningEffortIsPassedToTheEngine() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Think carefully", reasoningEffort = "high")
        runCurrent()
        assertEquals("high", rig.engine.reasoningEffort)
        rig.close()
    }

    @Test fun explicitlyInvokedSkillIsPassedToTheEngine() = runTest {
        val rig = Rig(this)
        val skill = AgentSkill("device-automation", "Control Android", "/home/.agents/skills/device-automation/SKILL.md", "user")
        rig.coordinator.send("one", "\$device-automation Read the screen", skill = skill)
        runCurrent()
        assertEquals(skill, rig.engine.skill)
        rig.close()
    }

    @Test fun planModeAsksTheEngineForAPlanWithTheTurnsModel() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Clean up my inbox", model = "gpt-5.6-luna", planMode = true)
        runCurrent()
        assertEquals("gpt-5.6-luna", rig.engine.planModel)
        rig.close()
    }

    @Test fun aClaudeChatChecksTheClaudeSignInAndSaysClaude() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("claude", "Read my screen")
        assertEquals("Starting Claude", rig.coordinator.state.value.status)
        runCurrent()
        assertEquals(listOf(EngineKind.CLAUDE), rig.engine.connectedKinds)
        assertEquals(listOf(EngineKind.CLAUDE), rig.engine.accountKinds)
        assertEquals(1, rig.engine.turns)
        rig.close()
    }

    @Test fun aCodexChatStillChecksTheCodexSignIn() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Read my screen")
        assertEquals("Starting Codex", rig.coordinator.state.value.status)
        runCurrent()
        assertEquals(listOf(EngineKind.CODEX), rig.engine.accountKinds)
        rig.close()
    }

    @Test fun aTurnForTheOtherEngineMovesTheChatAndCarriesWhatWasSaid() = runTest {
        val rig = Rig(this)
        rig.engine.threads = mapOf(EngineKind.CODEX to "codex-thread", EngineKind.CLAUDE to "claude-thread")
        rig.coordinator.send("one", "Open my alarms")
        runCurrent()
        rig.engine.emit(EngineEvent.MessageCompleted("Your alarms are open.", "codex-thread", "turn", "item", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "codex-thread", turnId = "turn"))
        advanceUntilIdle()
        assertEquals(listOf("Open my alarms"), rig.engine.prompts)

        rig.coordinator.send("one", "Now set one for 7", model = "sonnet", engineKind = EngineKind.CLAUDE)
        assertEquals("Starting Claude", rig.coordinator.state.value.status)
        runCurrent()

        val session = rig.store.getSession("one")!!
        assertEquals(EngineKind.CLAUDE, session.engine)
        assertEquals("claude-thread", session.engineThreadId)
        assertEquals("codex-thread", session.parked[EngineKind.CODEX]?.threadId)
        assertEquals(listOf(EngineKind.CODEX, EngineKind.CLAUDE), rig.engine.accountKinds)
        // Claude never saw the chat: it is told what was said, then the new message.
        val prompt = rig.engine.prompts.last()
        assertTrue(prompt.startsWith("[Earlier in this chat]"))
        assertTrue("User: Open my alarms\nMike: Your alarms are open.\n" in prompt)
        assertTrue(prompt.endsWith("[End of earlier messages]\n\nNow set one for 7"))
        // The chat shows only what the user typed.
        assertEquals(listOf("Open my alarms", "Now set one for 7"), rig.store.messages.filter { it.role == "user" }.map { it.text })
        assertNull(session.catchUpFrom)
        rig.close()
    }

    @Test fun anEngineThatIsUpToDateGetsThePromptAsTyped() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "First")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        advanceUntilIdle()
        rig.coordinator.send("one", "Second", engineKind = EngineKind.CODEX)
        runCurrent()
        assertEquals(listOf("First", "Second"), rig.engine.prompts)
        rig.close()
    }

    @Test fun aThreadTheEngineLostIsGivenTheChatAgain() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "First")
        runCurrent()
        rig.engine.emit(EngineEvent.MessageCompleted("Done.", "thread", "turn", "item", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        advanceUntilIdle()
        // The engine cannot resume the stored thread and hands back a new one.
        rig.engine.threads = mapOf(EngineKind.CODEX to "fresh-thread")
        rig.coordinator.send("one", "Second")
        runCurrent()
        assertTrue(rig.engine.prompts.last().startsWith("[Earlier in this chat]"))
        assertTrue("User: First\nMike: Done.\n" in rig.engine.prompts.last())
        rig.close()
    }

    @Test fun aSignedOutClaudeChatAsksForTheClaudeSignIn() = runTest {
        val rig = Rig(this)
        rig.engine.signedOut += EngineKind.CLAUDE
        rig.coordinator.send("claude", "Read my screen")
        runCurrent()
        assertEquals(0, rig.engine.turns)
        assertTrue(rig.store.messages.any { it.role == "system" && it.text == "Sign in to Claude in Settings first." })
        rig.close()
    }

    @Test fun aClaudeTurnThatFailsWithoutAReasonNamesClaude() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("claude", "Read my screen")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("failed", threadId = "thread", turnId = "turn"))
        runCurrent()
        assertTrue(rig.store.messages.any { it.role == "system" && it.text == "Claude could not finish" })
        rig.close()
    }

    @Test fun anOrdinaryTurnIsNotAPlan() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Clean up my inbox", model = "gpt-5.6-luna")
        runCurrent()
        assertNull(rig.engine.planModel)
        assertEquals(1, rig.engine.turns)
        rig.close()
    }

    @Test fun currentAdbStatusIsSnapshottedForEachNewTurn() = runTest {
        val rig = Rig(this)
        rig.adbStatus.value = AdbStatus(ConnectionPhase.CONNECTED, "Connected", 37123)
        rig.coordinator.send("one", "Read the screen")
        runCurrent()

        assertEquals(ConnectionPhase.CONNECTED, rig.engine.adbStatus?.phase)
        assertEquals(37123, rig.engine.adbStatus?.port)
        rig.close()
    }

    @Test fun uiControlWaitsForOverlayAndBackendReadsDoNotShowIt() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Read and tap")
        runCurrent()
        assertEquals(0, rig.overlay.shown)
        assertTrue(rig.overlay.states.any { it.phase == OverlayPhase.THINKING })
        rig.engine.emit(EngineEvent.ToolCall("read", "read_ui", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        assertTrue(rig.overlay.states.any { it.phase == OverlayPhase.RUNNING && it.detail == "read ui" })
        rig.engine.emit(EngineEvent.ToolCall("tap", "tap", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        assertTrue(rig.overlay.states.any { it.phase == OverlayPhase.CONTROLLING && it.detail == "tap" })
        assertEquals(listOf("read_ui", "tap"), rig.tools.names)
        assertTrue(rig.tools.controlWasVisible)
        rig.close()
    }

    @Test fun completedRunShowsDoneThenReleasesOverlay() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Finish this")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()

        assertTrue(rig.overlay.states.any { it.phase == OverlayPhase.STARTING })
        assertTrue(rig.overlay.states.any { it.phase == OverlayPhase.THINKING })
        assertEquals(OverlayPhase.DONE, rig.overlay.finished.last().phase)
        assertFalse(rig.overlay.visible)
        rig.close()
    }

    @Test fun aRunThatUsedThePhoneEndsWithWhereItsTimeWent() = runTest {
        // The run that felt slow is the one someone will ask about, so the
        // answer belongs in the transcript they are reading.
        val rig = Rig(this)
        rig.tools.workMs = 3_000
        rig.coordinator.send("one", "Open settings")
        runCurrent()
        advanceTimeBy(2_000)
        rig.engine.emit(EngineEvent.ToolCall("1", "tap", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()

        val summary = rig.store.messages.last { it.role == "system" }
        assertTrue(summary.text, summary.text.startsWith("Run summary:"))
        assertTrue(summary.text, summary.text.contains("1 call"))
        val metrics = rig.coordinator.metrics.value["one"]!!
        assertEquals(1, metrics.toolCalls)
        assertEquals(0L, metrics.approvalMs)
        // The three seconds on the phone are the phone's, and the two before
        // the tool call are the model's.
        assertTrue("toolMs=${metrics.toolMs}", metrics.toolMs >= 3_000)
        assertTrue("thinkingMs=${metrics.thinkingMs}", metrics.thinkingMs >= 2_000)
        rig.close()
    }

    @Test fun aRunThatOnlyTalkedGetsNoSummaryLine() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "What time is it")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()

        assertTrue(rig.store.messages.none { it.text.startsWith("Run summary:") })
        rig.close()
    }

    @Test fun sessionTraceKeepsOrderedToolPayloadsResultsAndFollowingAssistantText() = runTest {
        val rig = Rig(this)
        val prompt = "Read the screen and explain what you found"
        val largeArgument = "argument-value-".repeat(500)
        val largeResult = "result-value-".repeat(500)
        val assistantText = "The screen shows the requested page. ".repeat(100)
        val arguments = buildJsonObject {
            put("package", "com.example.gym")
            put("payload", largeArgument)
        }
        rig.tools.nextResult = ToolResult(largeResult)

        rig.coordinator.send("one", prompt)
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("trace-request", "read_ui", arguments, "thread", "turn"))
        runCurrent()
        rig.engine.emit(EngineEvent.TextDelta(assistantText, "thread", "turn", "assistant-item"))
        rig.engine.emit(
            EngineEvent.MessageCompleted(
                assistantText, "thread", "turn", "assistant-item", phase = "final_answer",
            ),
        )
        runCurrent()

        val entries = rig.store.traces
        assertEquals(listOf("user", "tool_call", "tool_result", "assistant"), entries.map {
            it["type"]!!.jsonPrimitive.content
        })
        assertTrue("trace timestamps are missing", entries.all { it["timestampMs"] != null })
        val user = entries[0]
        assertEquals(prompt, user["text"]!!.jsonPrimitive.content)

        val call = entries[1]
        assertEquals("trace-request", call["requestId"]!!.jsonPrimitive.content)
        assertEquals("read_ui", call["name"]!!.jsonPrimitive.content)
        assertEquals(arguments, call["arguments"]!!.jsonObject)
        assertEquals(largeArgument, call["arguments"]!!.jsonObject["payload"]!!.jsonPrimitive.content)

        val result = entries[2]
        assertEquals("trace-request", result["requestId"]!!.jsonPrimitive.content)
        assertEquals("read_ui", result["name"]!!.jsonPrimitive.content)
        assertEquals(largeResult, result["text"]!!.jsonPrimitive.content)

        val assistant = entries[3]
        assertEquals(assistantText, assistant["text"]!!.jsonPrimitive.content)
        assertEquals("final_answer", assistant["phase"]!!.jsonPrimitive.content)
        rig.close()
    }

    @Test fun timeSpentWaitingForTheUserIsNotReportedAsTimeOnThePhone() = runTest {
        // A send approval happens inside the tool call that asks for it. Counted
        // as device time it would read as 20 seconds of a slow phone.
        val rig = Rig(this)
        rig.coordinator.send("one", "Send it")
        runCurrent()
        val approved = async {
            rig.coordinator.authorizeSend(
                SendRequest(packageName = "com.whatsapp", appLabel = "WhatsApp", recipient = "Amir", message = "on my way"),
            ) { ToolResult("sent") }
        }
        runCurrent()
        val request = rig.coordinator.state.value.approval!!.requestId
        advanceTimeBy(20_000)
        runCurrent()
        rig.coordinator.approve(request, true)
        runCurrent()
        assertTrue(approved.await().success)
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()

        val metrics = rig.coordinator.metrics.value["one"]!!
        assertTrue("approvalMs=${metrics.approvalMs}", metrics.approvalMs >= 20_000)
        assertTrue("toolMs=${metrics.toolMs}", metrics.toolMs < 20_000)
        rig.close()
    }

    @Test fun overlayFailureReturnsToolErrorWithoutExecutingDeviceAction() = runTest {
        val rig = Rig(this)
        rig.overlay.fail = true
        rig.coordinator.send("one", "Tap")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("tap", "tap", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        assertEquals(0, rig.tools.executions)
        assertTrue(rig.engine.answers.any { !it.success })
        assertTrue(rig.coordinator.state.value.active)
        rig.close()
    }

    @Test fun realtimeVoiceTurnsCanUseGatewayAndVoiceStopRevokesLocally() = runTest {
        val rig = Rig(this)
        rig.coordinator.beginVoice("one", "voice-thread", rig.store.workspace("one"))
        runCurrent()
        rig.engine.emit(EngineEvent.TurnStarted("voice-thread", "voice-turn"))
        runCurrent()

        rig.engine.emit(EngineEvent.ToolCall("voice-tool", "read_ui", buildJsonObject {}, "voice-thread", "voice-turn"))
        runCurrent()
        assertEquals(1, rig.tools.executions)
        assertTrue(rig.engine.answers.single().success)

        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "voice-thread", turnId = "voice-turn"))
        runCurrent()
        assertTrue(rig.coordinator.state.value.active)
        assertEquals("Listening", rig.coordinator.state.value.status)

        rig.coordinator.endVoice()
        assertTrue(rig.tools.revoked)
        runCurrent()
        assertFalse(rig.coordinator.state.value.active)
        assertFalse(rig.engine.closed)
        rig.close()
    }

    @Test fun assistantMessagesStayAfterTheirToolsAndFullFinalDoesNotDuplicateDeltas() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Read")
        runCurrent()
        rig.engine.emit(EngineEvent.TextDelta("I will read.", "thread", "turn", "commentary"))
        rig.engine.emit(EngineEvent.MessageCompleted("I will read.", "thread", "turn", "commentary", "commentary"))
        rig.engine.emit(EngineEvent.ToolCall("tool", "read_ui", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        rig.engine.emit(EngineEvent.TextDelta("The screen is ready.", "thread", "turn", "final"))
        rig.engine.emit(EngineEvent.MessageCompleted("The screen is ready.", "thread", "turn", "final", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()
        assertEquals(listOf("user", "assistant", "tool", "assistant"), rig.store.messages.map { it.role })
        assertEquals("The screen is ready.", rig.store.messages.last().text)
        assertEquals("complete", rig.store.messages.last().state)
        assertTrue(rig.coordinator.available.value)
        rig.close()
    }

    @Test fun whatTheAgentSaysAlsoReachesTheFloatingCard() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Read")
        runCurrent()
        rig.engine.emit(EngineEvent.TextDelta("I will read the screen.", "thread", "turn", "commentary"))
        rig.engine.emit(EngineEvent.MessageCompleted("I will read the screen.", "thread", "turn", "commentary", "commentary"))
        rig.engine.emit(EngineEvent.ToolCall("tool", "read_ui", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        // Said once, not again when the tool call flushes the same segment.
        assertEquals(listOf("I will read the screen."), rig.overlay.spoken)

        rig.engine.emit(EngineEvent.TextDelta("The screen is ready.", "thread", "turn", "final"))
        rig.engine.emit(EngineEvent.MessageCompleted("The screen is ready.", "thread", "turn", "final", "final_answer"))
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()
        assertEquals(listOf("I will read the screen.", "The screen is ready."), rig.overlay.spoken)
        rig.close()
    }

    @Test fun emptyFinalReportsMissingReplyWithoutClaimingSuccess() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Do it")
        runCurrent()
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()
        assertEquals("assistant", rig.store.messages.last().role)
        assertTrue(rig.store.messages.last().text.contains("without a final reply"))
        rig.close()
    }

    @Test fun queuedSessionsKeepExclusiveOwnershipAndCancelIndependently() = runTest {
        val rig = Rig(this)
        val queue = SessionRunQueue(rig.scope, rig.coordinator, rig.store)
        runCurrent()
        queue.submit(QueuedTurn(sessionId = "one", prompt = "First"))
        runCurrent()
        val cancelled = QueuedTurn(sessionId = "two", prompt = "Cancel me")
        queue.submit(cancelled)
        queue.submit(QueuedTurn(sessionId = "two", prompt = "Second"))
        queue.cancel(cancelled.id)
        assertEquals(1, rig.engine.turns)
        assertEquals(1, rig.store.queued.size)
        rig.engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))
        runCurrent()
        assertEquals(2, rig.engine.turns)
        assertEquals("two", rig.coordinator.state.value.sessionId)
        assertFalse(rig.store.messages.any { it.text == "Cancel me" })
        assertTrue(rig.store.queued.isEmpty())
        rig.close()
    }

    @Test fun restoredQueueAndLocalStopWaitForExplicitResume() = runTest {
        val rig = Rig(this)
        rig.store.queued = listOf(QueuedTurn(sessionId = "one", prompt = "Restored"))
        val queue = SessionRunQueue(rig.scope, rig.coordinator, rig.store)
        runCurrent()
        assertTrue(queue.paused.value)
        assertEquals(0, rig.engine.turns)
        queue.resume()
        runCurrent()
        queue.submit(QueuedTurn(sessionId = "two", prompt = "Later"))
        queue.pause()
        rig.coordinator.stop()
        runCurrent()
        assertEquals(1, rig.engine.turns)
        assertEquals(1, queue.turns.value.size)
        queue.resume()
        runCurrent()
        assertEquals(2, rig.engine.turns)
        rig.close()
    }

    @Test fun aMessageSentAfterAStopRunsWhileHeldWorkWaits() = runTest {
        val rig = Rig(this)
        val queue = SessionRunQueue(rig.scope, rig.coordinator, rig.store)
        runCurrent()
        queue.submit(QueuedTurn(sessionId = "one", prompt = "First"))
        runCurrent()
        queue.submit(QueuedTurn(sessionId = "two", prompt = "Held"))
        queue.pause()
        rig.coordinator.stop()
        runCurrent()
        assertEquals(1, rig.engine.turns)

        // Typed after the stop: it runs even though the queue is still paused.
        queue.submit(QueuedTurn(sessionId = "one", prompt = "Follow-up"))
        runCurrent()
        assertEquals(2, rig.engine.turns)
        assertEquals("one", rig.coordinator.state.value.sessionId)
        assertTrue(queue.paused.value)
        assertEquals(listOf("Held"), queue.turns.value.map { it.prompt })
        rig.close()
    }

    @Test fun theGatewayDecidesWhichToolsTakeTheOverlayOffTheCapturedSurface() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Read the screen")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("1", "read_ui", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        // read_ui captures the screen, so the overlay steps aside and comes back.
        assertEquals(listOf(true, false), rig.overlay.captureHistory)
        assertFalse(rig.overlay.captureHidden)

        rig.overlay.captureHistory.clear()
        rig.engine.emit(EngineEvent.ToolCall("2", "tap", buildJsonObject {}, "thread", "turn"))
        runCurrent()
        // tap captures nothing, so the card stays where it is.
        assertTrue(rig.overlay.captureHistory.isEmpty())
        rig.close()
    }

    @Test fun localIntentApprovalIsBoundToItsRequestAndDispatchesOnlyOnce() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Open the message")
        runCurrent()
        var dispatches = 0
        val result = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest(
                    action = "android.intent.action.VIEW",
                    uri = "mailto:test@example.com?subject=Hello",
                    packageName = "com.example.mail",
                    reason = "Open a prefilled message.",
                ),
            ) {
                dispatches++
                ToolResult("launched")
            }
        }
        runCurrent()

        val approval = checkNotNull(rig.coordinator.state.value.approval)
        assertEquals("open_intent", approval.method)
        assertEquals("mailto:test@example.com?subject=Hello", approval.details["uri"]!!.jsonPrimitive.content)
        rig.coordinator.approve("stale-id", true)
        runCurrent()
        assertFalse(result.isCompleted)

        rig.coordinator.approve(approval.requestId, true)
        runCurrent()
        assertTrue(result.await().success)
        assertEquals(1, dispatches)
        assertNull(rig.coordinator.state.value.approval)
        assertTrue(rig.engine.approvalAnswers.isEmpty())
        rig.coordinator.approve(approval.requestId, true)
        assertEquals(1, dispatches)
        rig.close()
    }

    @Test fun sayingYesOrNoAnswersTheWaitingApprovalInsteadOfSteering() = runTest {
        // The card can be out of sight (voice mode, another app in front), so
        // the user's own "כן" / "no" has to answer it.
        val rig = Rig(this)
        rig.coordinator.send("one", "Message my wife")
        runCurrent()
        var dispatches = 0
        val approved = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest("android.intent.action.VIEW", "https://wa.me/972500000000?text=hi", "com.whatsapp", "Send a message."),
            ) { dispatches++; ToolResult("launched") }
        }
        runCurrent()
        rig.coordinator.steer("כן")
        runCurrent()
        assertTrue(approved.await().success)
        assertEquals(1, dispatches)
        assertTrue("an answer is not an instruction to the agent", rig.engine.steers.isEmpty())

        val denied = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest("android.intent.action.VIEW", "https://wa.me/972500000000?text=hi", "com.whatsapp", "Send a message."),
            ) { dispatches++; ToolResult("launched") }
        }
        runCurrent()
        assertTrue(rig.coordinator.answerApprovalByReply("No", record = false))
        runCurrent()
        assertFalse(denied.await().success)
        assertEquals(1, dispatches)
        rig.close()
    }

    @Test fun aSendWaitsForTheUserAndAnAlwaysAnswerCoversOnlyWhatItNames() = runTest {
        val grants = InMemorySendGrantStore()
        val rig = Rig(this, grants)
        rig.coordinator.send("one", "Message my wife")
        runCurrent()
        val toWife = SendRequest("com.whatsapp", "WhatsApp", "My Wife", "hi")
        var sends = 0

        val first = async { rig.coordinator.authorizeSend(toWife) { sends++; ToolResult("sent") } }
        runCurrent()
        val approval = checkNotNull(rig.coordinator.state.value.approval)
        assertEquals("send_message", approval.method)
        assertEquals(0, sends)
        rig.coordinator.approve(approval.requestId, true, ApprovalScope.CONTACT)
        runCurrent()
        assertTrue(first.await().success)
        assertEquals(listOf(SendGrant("com.whatsapp", "WhatsApp", "My Wife")), grants.grants.value)

        // Remembered: the same contact goes straight through.
        assertTrue(rig.coordinator.authorizeSend(toWife.copy(message = "later")) { sends++; ToolResult("sent") }.success)
        assertEquals(2, sends)
        assertNull(rig.coordinator.state.value.approval)

        // Anyone else still asks, and a denial sends nothing.
        val other = async { rig.coordinator.authorizeSend(toWife.copy(recipient = "Boss")) { sends++; ToolResult("sent") } }
        runCurrent()
        rig.coordinator.approve(rig.coordinator.state.value.approval!!.requestId, false)
        runCurrent()
        assertFalse(other.await().success)
        assertEquals(2, sends)
        rig.close()
    }

    @Test fun aSpokenYesNeverGrantsAStandingPermission() = runTest {
        val grants = InMemorySendGrantStore()
        val rig = Rig(this, grants)
        rig.coordinator.send("one", "Message my wife")
        runCurrent()
        val sent = async { rig.coordinator.authorizeSend(SendRequest("com.whatsapp", "WhatsApp", "My Wife", "hi")) { ToolResult("sent") } }
        runCurrent()
        assertTrue(rig.coordinator.answerApprovalByReply("כן", record = false))
        runCurrent()
        assertTrue(sent.await().success)
        assertTrue(grants.grants.value.isEmpty())
        rig.close()
    }

    @Test fun withNoApprovalWaitingAYesIsAnOrdinaryInstruction() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Open the message")
        runCurrent()
        assertFalse(rig.coordinator.answerApprovalByReply("yes"))
        rig.close()
    }

    @Test fun denyingOrStoppingALocalIntentNeverDispatchesIt() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Open the message")
        runCurrent()
        var dispatches = 0
        val denied = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest("android.intent.action.SENDTO", "mailto:test@example.com", null, "Send a message."),
            ) {
                dispatches++
                ToolResult("launched")
            }
        }
        runCurrent()
        rig.coordinator.approve(rig.coordinator.state.value.approval!!.requestId, false)
        runCurrent()
        assertFalse(denied.await().success)
        assertEquals(0, dispatches)

        val stopped = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest("android.intent.action.VIEW", "https://pay.example/?amount=10", null, "Start a payment."),
            ) {
                dispatches++
                ToolResult("launched")
            }
        }
        runCurrent()
        assertNotNull(rig.coordinator.state.value.approval)
        rig.coordinator.stop()
        runCurrent()
        assertFalse(stopped.await().success)
        assertEquals(0, dispatches)
        rig.close()
    }

    @Test fun aWaitingIntentApprovalRaisesTheAppAndSaysWhereToAnswerIt() = runTest {
        // The approval card lives only in the app, and device control means the
        // app is not in front. Without raising it the user sees a floating card
        // that says "waiting" and has nothing to tap, and the tool call looks
        // to the model like it never returned.
        val rig = Rig(this)
        rig.coordinator.send("one", "Message Amir")
        runCurrent()
        val result = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest(
                    "android.intent.action.VIEW",
                    "https://wa.me/972500000000?text=on%20my%20way",
                    "com.whatsapp",
                    "Open wa.me with a prefilled message (text).",
                ),
            ) { ToolResult("launched") }
        }
        runCurrent()

        assertEquals(1, rig.foregroundRequests)
        assertTrue(rig.overlay.states.last().label.contains("Approve in Hey Mike"))
        assertEquals("Waiting for your approval", rig.coordinator.state.value.status)

        rig.coordinator.approve(rig.coordinator.state.value.approval!!.requestId, true)
        runCurrent()
        assertTrue(result.await().success)
        rig.close()
    }

    @Test fun anUnansweredApprovalExpiresWithADifferentErrorThanADenial() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Message Amir")
        runCurrent()
        val expired = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest("android.intent.action.VIEW", "https://wa.me/1?text=hi", null, "Send."),
            ) { ToolResult("launched") }
        }
        runCurrent()
        advanceTimeBy(AgentCoordinator.LOCAL_APPROVAL_TIMEOUT_MS + 1_000)
        runCurrent()

        val text = expired.await().text
        // "denied or expired" told the model nothing it could act on. Nobody
        // answering is a different situation from the user saying no.
        assertTrue(text, text.contains("\"errorType\":\"approval_timeout\""))
        assertTrue(text, text.contains("Hey Mike app"))
        assertNull(rig.coordinator.state.value.approval)

        val denied = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest("android.intent.action.VIEW", "https://wa.me/1?text=hi", null, "Send."),
            ) { ToolResult("launched") }
        }
        runCurrent()
        rig.coordinator.approve(rig.coordinator.state.value.approval!!.requestId, false)
        runCurrent()
        assertTrue(denied.await().text.contains("\"errorType\":\"intent_denied\""))
        rig.close()
    }

    @Test fun aStoppedApprovalDoesNotStrandItsCardAndBlockTheNextOne() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Message Amir")
        runCurrent()
        val stopped = async {
            rig.coordinator.authorizeLocalIntent(
                LocalIntentRequest("android.intent.action.VIEW", "https://wa.me/1?text=hi", null, "Send."),
            ) { ToolResult("launched") }
        }
        runCurrent()
        assertNotNull(rig.coordinator.state.value.approval)

        rig.coordinator.stop()
        runCurrent()
        assertFalse(stopped.await().success)
        // A card left behind here refuses every later approval, local or
        // engine, because one is already showing.
        assertNull(rig.coordinator.state.value.approval)
        rig.close()
    }

    @Test fun aTopicNameIsSavedOnTheThreadWithoutTakingThePhone() = runTest {
        val rig = Rig(this)
        rig.store.sessions.value = rig.store.sessions.value.map {
            if (it.id == "one") it.copy(title = "New chat", titlePending = true) else it
        }
        rig.coordinator.send("one", "Fix the session names")
        runCurrent()
        assertEquals("Fix the session names", rig.store.getSession("one")!!.title)
        assertTrue(rig.tools.revoked)
        rig.engine.emit(EngineEvent.ToolCall("title", ChatTools.TITLE,
            buildJsonObject { put("title", "Smart session names") }, "thread", "turn"))
        runCurrent()
        assertEquals("Smart session names", rig.store.getSession("one")!!.title)
        assertFalse(rig.store.getSession("one")!!.titlePending)
        assertEquals("thread" to "Smart session names", rig.engine.names.last())
        assertTrue(rig.engine.answers.single().success)
        assertTrue(rig.tools.revoked)
        assertEquals(0, rig.tools.executions)
        rig.close()
    }

    @Test fun aQuestionWaitsForTheUsersChoiceWithoutTakingThePhone() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Book a table")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("q", "ask_user", buildJsonObject {
            put("question", "Which evening?")
            put("options", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("Friday")); add(kotlinx.serialization.json.JsonPrimitive("Saturday"))
            })
        }, "thread", "turn"))
        runCurrent()

        val question = rig.coordinator.state.value.question!!
        assertEquals("Which evening?", question.question)
        assertEquals(listOf("Friday", "Saturday"), question.options)
        assertEquals("Waiting for your answer", rig.coordinator.state.value.status)
        // The gateway was never armed: a question must not hold the phone.
        assertTrue(rig.tools.revoked)
        assertEquals(0, rig.tools.executions)
        assertTrue(rig.engine.answers.isEmpty())

        // "2" is how the notification lists the second option.
        assertTrue(rig.coordinator.answerQuestion(question.id, "2"))
        runCurrent()
        assertNull(rig.coordinator.state.value.question)
        val answer = rig.engine.answers.single()
        assertTrue(answer.success)
        assertEquals("Saturday", Json.parseToJsonElement(answer.text).jsonObject["answer"]!!.jsonPrimitive.content)
        // Asked and answered, in the chat's own words.
        assertEquals(listOf("assistant" to "Which evening?", "user" to "Saturday"), rig.store.messages.takeLast(2).map { it.role to it.text })
        rig.close()
    }

    @Test fun whatTheUserTypesWhileAQuestionWaitsIsItsAnswerNotASteer() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Rename the file")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("q", "ask_user", buildJsonObject { put("question", "What name?") }, "thread", "turn"))
        runCurrent()
        rig.coordinator.steer("report-final.pdf")
        runCurrent()
        assertTrue(rig.engine.steers.isEmpty())
        assertTrue(rig.engine.answers.single().text.contains("report-final.pdf"))
        rig.close()
    }

    @Test fun aQuestionNobodyAnswersOrThatIsSkippedSaysWhich() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Plan")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("q1", "ask_user", buildJsonObject { put("question", "Go on?") }, "thread", "turn"))
        runCurrent()
        val first = rig.coordinator.state.value.question!!.id
        assertFalse(rig.coordinator.answerQuestion("not-this-one", "yes"))
        assertFalse(rig.coordinator.answerQuestion(first, "   "))
        assertTrue(rig.coordinator.answerQuestion(first, null))
        runCurrent()
        assertTrue(rig.engine.answers.single().text.contains("skipped"))

        rig.engine.emit(EngineEvent.ToolCall("q2", "ask_user", buildJsonObject { put("question", "Still there?") }, "thread", "turn"))
        runCurrent()
        advanceTimeBy(ChatTools.ASK_TIMEOUT_MS + 1_000)
        runCurrent()
        assertNull(rig.coordinator.state.value.question)
        assertTrue(rig.engine.answers.last().text.contains("no_answer"))
        assertTrue(rig.coordinator.state.value.active)
        rig.close()
    }

    @Test fun stoppingARunTakesItsQuestionDown() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Plan")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("q", "ask_user", buildJsonObject { put("question", "Go on?") }, "thread", "turn"))
        runCurrent()
        val id = rig.coordinator.state.value.question!!.id
        rig.coordinator.stop()
        assertNull(rig.coordinator.state.value.question)
        assertFalse(rig.coordinator.answerQuestion(id, "yes"))
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(rig.engine.answers.isEmpty())
        rig.close()
    }

    @Test fun showMediaPutsTheFilesInOneMessageAndLeavesOutWhatIsNotMedia() = runTest {
        val rig = Rig(this)
        rig.coordinator.send("one", "Show me the clip")
        runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("m", "show_media", buildJsonObject {
            put("files", kotlinx.serialization.json.buildJsonArray {
                listOf("Server:/clips/demo.mp4", "chat:shot.png", "notes.txt", "phone:gone.jpg").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            put("caption", "Here it is")
        }, "thread", "turn"))
        runCurrent()

        // notes.txt is refused by its name, before anything is fetched.
        assertEquals(listOf("Server:/clips/demo.mp4", "chat:shot.png", "phone:gone.jpg"), rig.fetched)
        val message = rig.store.messages.last()
        assertEquals("assistant", message.role)
        assertEquals("Here it is", message.text)
        assertEquals(listOf("demo.mp4", "shot.png"), message.attachmentPaths.map(ChatTools::displayName))
        // The computer's file is held as a reference with its size; only the chat's own file is a path.
        assertEquals(RemoteMediaRef("Server", "/clips/demo.mp4", 2_048), RemoteMediaRef.parse(message.attachmentPaths[0]))
        assertNull(RemoteMediaRef.parse(message.attachmentPaths[1]))
        val result = Json.parseToJsonElement(rig.engine.answers.single().text).jsonObject
        assertEquals(2, result["shown"]!!.jsonArray.size)
        assertEquals(listOf("demo.mp4"), result["notCopied"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, result["notShown"]!!.jsonArray.size)
        assertTrue(rig.tools.revoked)
        rig.close()
    }

    @Test fun freshContinuityReachesTheEngineAndStopSettlesItExactlyOnce() = runTest {
        val settled = mutableListOf<Pair<String, String>>()
        var began = 0
        val continuity = object : AgentContinuity {
            override suspend fun context(sessionId: String, threadId: String?) = "Current personal memory: Hebrew.\n"
            override suspend fun started(sessionId: String, prompt: String?) { began++ }
            override suspend fun finished(sessionId: String, outcome: String, reply: String) { settled += sessionId to outcome }
        }
        val rig = Rig(this, continuity = continuity)
        rig.coordinator.send("one", "Hello"); runCurrent()
        assertEquals(1, began)
        assertTrue(rig.engine.prompts.single().contains("Current personal memory: Hebrew."))
        assertEquals("Hello", rig.store.messages.first { it.role == "user" }.text)
        rig.coordinator.stop(); advanceUntilIdle()
        rig.engine.emit(EngineEvent.TurnFinished("completed", "thread", "turn")); advanceUntilIdle()
        assertEquals(listOf("one" to "interrupted"), settled)
        rig.close()
    }

    @Test fun mikesOwnToolsAnswerForTheChatThatAsksWithoutTakingThePhone() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        val own = object : SessionTools {
            override val names = setOf("mike_memory")
            override suspend fun invoke(sessionId: String, name: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult {
                asked += sessionId to name
                return ToolResult("saved")
            }
        }
        val rig = Rig(this, sessionTools = own)
        rig.coordinator.send("one", "Hello"); runCurrent()
        rig.engine.emit(EngineEvent.ToolCall("1", "mike_memory", buildJsonObject {}, "thread", "turn")); advanceUntilIdle()
        assertEquals(listOf("one" to "mike_memory"), asked)
        assertEquals("saved", rig.engine.answers.single().text)
        // The phone's gateway was never armed: nothing waited for the phone or took it.
        assertTrue(rig.tools.revoked)
        assertEquals(0, rig.tools.executions)
        assertTrue(rig.store.messages.any { it.role == "tool" && it.text.startsWith("mike_memory: saved") })
        // A device tool in the same run still goes through the phone.
        rig.engine.emit(EngineEvent.ToolCall("2", "read_ui", buildJsonObject {}, "thread", "turn")); advanceUntilIdle()
        assertEquals(listOf("read_ui"), rig.tools.names)
        rig.close()
    }

    private class Rig(test: TestScope, grants: SendGrantStore = InMemorySendGrantStore(), continuity: AgentContinuity = object : AgentContinuity {}, sessionTools: SessionTools? = null) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val engine = FakeEngine()
        val store = FakeStore()
        val overlay = FakeOverlay()
        val tools = FakeTools(overlay)
        val adbStatus = MutableStateFlow(AdbStatus())
        var foregroundRequests = 0
        /** Addresses show_media asked for, in order. */
        val fetched = mutableListOf<String>()
        val coordinator = AgentCoordinator(
            scope, engine, store, tools, overlay,
            sendGrants = grants,
            continuity = continuity,
            sessionTools = sessionTools,
            bringToForeground = { foregroundRequests++ },
            // The test's own clock, so a reported duration is exactly the time
            // the test advanced rather than how fast the machine ran.
            nowNanos = { test.testScheduler.currentTime * 1_000_000 },
            chatMedia = { address, workspace ->
                fetched += address
                require(!address.contains("gone")) { "No such file." }
                // A file on a computer is a reference, not a copy.
                if (address.startsWith("Server:")) RemoteMediaRef("Server", address.removePrefix("Server:"), 2_048).encode()
                else File(workspace, address.substringAfterLast(':').substringAfterLast('/')).path
            },
        ) { adbStatus.value }
        fun close() { scope.cancel() }
    }

    private class FakeEngine : AgentEngine {
        private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val events = stream.asSharedFlow()
        var turns = 0
        var reasoningEffort: String? = null
        var skill: AgentSkill? = null
        var adbStatus: AdbStatus? = null
        var planModel: String? = null
        var closed = false
        var waitForInterrupt: CompletableDeferred<Unit>? = null
        val answers = mutableListOf<ToolResult>()
        val names = mutableListOf<Pair<String, String>>()
        override suspend fun renameThread(threadId: String, title: String) { names += threadId to title }
        val approvalAnswers = mutableListOf<Pair<String, Boolean>>()
        val connectedKinds = mutableListOf<EngineKind>()
        val accountKinds = mutableListOf<EngineKind>()
        val signedOut = mutableSetOf<EngineKind>()
        /** The thread each engine opens; "thread" when not set. */
        var threads = emptyMap<EngineKind, String>()
        val prompts = mutableListOf<String>()
        private var kind = EngineKind.CODEX
        suspend fun emit(value: EngineEvent) = stream.emit(value)
        override suspend fun connect() = Unit
        override suspend fun connect(kind: EngineKind) { connectedKinds += kind; this.kind = kind }
        override suspend fun account() = AccountStatus(true, "Test")
        override suspend fun account(kind: EngineKind): AccountStatus {
            accountKinds += kind
            return AccountStatus(kind !in signedOut, "Test")
        }
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("test")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) = threads[kind] ?: "thread"
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String { turns++; prompts += prompt; return "turn" }
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>, reasoningEffort: String?): String {
            this.reasoningEffort = reasoningEffort
            return startTurn(threadId, prompt, images)
        }
        override suspend fun startTurn(
            threadId: String,
            prompt: String,
            images: List<File>,
            reasoningEffort: String?,
            skill: AgentSkill?,
        ): String {
            this.skill = skill
            return startTurn(threadId, prompt, images, reasoningEffort)
        }
        override suspend fun startTurn(
            threadId: String,
            prompt: String,
            images: List<File>,
            reasoningEffort: String?,
            skill: AgentSkill?,
            adbStatus: AdbStatus,
        ): String {
            this.adbStatus = adbStatus
            return startTurn(threadId, prompt, images, reasoningEffort, skill)
        }
        override suspend fun startTurn(
            threadId: String,
            prompt: String,
            images: List<File>,
            reasoningEffort: String?,
            skill: AgentSkill?,
            capabilities: DeviceCapabilities,
            planModel: String?,
        ): String {
            this.planModel = planModel
            return startTurn(threadId, prompt, images, reasoningEffort, skill, capabilities)
        }
        val steers = mutableListOf<String>()
        override suspend fun steer(threadId: String, turnId: String, prompt: String) { steers += prompt }
        override suspend fun interrupt(threadId: String, turnId: String) { waitForInterrupt?.await() }
        override suspend fun answerTool(requestId: String, result: ToolResult) { answers.add(result) }
        override suspend fun answerApproval(requestId: String, allow: Boolean) {
            approvalAnswers += requestId to allow
        }
        override suspend fun close() { closed = true }
    }
    private class FakeStore : SessionStore {
        var queued = emptyList<QueuedTurn>()
        override suspend fun loadQueuedTurns() = queued
        override suspend fun saveQueuedTurns(turns: List<QueuedTurn>) { queued = turns }
        override val sessions = MutableStateFlow(
            listOf(ChatSession("one", "One", 0, 0), ChatSession("two", "Two", 0, 0), ChatSession("claude", "Claude", 0, 0, engine = EngineKind.CLAUDE)),
        )
        val messages = mutableListOf<ChatMessage>()
        val traces = mutableListOf<JsonObject>()
        override suspend fun createSession(engine: EngineKind) = sessions.value.first()
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(messages.filter { it.sessionId == sessionId })
        override suspend fun append(message: ChatMessage) { messages.add(message) }
        override suspend fun appendTrace(sessionId: String, entry: JsonObject) { traces.add(entry) }
        override suspend fun updateMessage(id: String, text: String, state: String) { val i = messages.indexOfFirst { it.id == id }; if (i >= 0) messages[i] = messages[i].copy(text = text, state = state) }
        private fun change(sessionId: String, change: (ChatSession) -> ChatSession) {
            sessions.value = sessions.value.map { if (it.id == sessionId) change(it) else it }
        }
        override suspend fun setThread(sessionId: String, threadId: String) = change(sessionId) { it.copy(engineThreadId = threadId) }
        override suspend fun setEngine(sessionId: String, engine: EngineKind) =
            change(sessionId) { EngineSwitch.switch(it.copy(hasMessages = messages.any { m -> m.sessionId == sessionId }), engine, now = messages.size.toLong()) }
        override suspend fun markCaughtUp(sessionId: String) = change(sessionId) { it.copy(catchUpFrom = null) }
        override suspend fun rename(sessionId: String, title: String) = change(sessionId) { it.copy(title = title, titlePending = false) }
        override suspend fun setAutomaticTitle(sessionId: String, title: String, complete: Boolean): Boolean {
            if (getSession(sessionId)?.titlePending != true) return false
            change(sessionId) { it.copy(title = title, titlePending = !complete) }
            return true
        }
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File("session-$sessionId")
    }
    private class FakeTools(private val overlay: FakeOverlay) : DeviceToolGateway {
        override val definitions = emptyList<ToolDefinition>()
        var revoked = true
        var executions = 0
        var controlWasVisible = false
        val names = mutableListOf<String>()
        override fun beginRun(runId: String, workspace: File) { revoked = false }
        override fun revoke() { revoked = true }
        override fun needsControl(name: String) = name == "tap"
        override fun hidesOverlayDuringCapture(name: String) = name == "read_ui"
        /** How long a call takes on this fake phone, on the test's clock. */
        var workMs = 0L
        var nextResult = ToolResult("Done")
        override suspend fun invoke(name: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult {
            check(!revoked)
            if (needsControl(name)) controlWasVisible = overlay.visible
            executions++; names.add(name)
            if (workMs > 0) delay(workMs)
            return nextResult
        }
        override suspend fun cancel() = Unit
    }
    private class FakeOverlay : ControlOverlay {
        var waitForShow: CompletableDeferred<Unit>? = null
        var shown = 0
        var visible = false
        var fail = false
        var captureHidden = false
        val captureHistory = mutableListOf<Boolean>()
        val states = mutableListOf<OverlayState>()
        val finished = mutableListOf<OverlayState>()
        val spoken = mutableListOf<String>()
        override suspend fun show(status: String) { if (fail) error("Overlay permission required"); waitForShow?.await(); shown++; visible = true }
        override fun update(status: String) = Unit
        override suspend fun showState(state: OverlayState) { states += state; show(state.label) }
        override fun updateState(state: OverlayState) { states += state; update(state.label) }
        override fun finish(state: OverlayState) { finished += state; updateState(state); hide() }
        override fun hide() { visible = false }
        override fun say(text: String) { spoken += text }
        override suspend fun setCaptureHidden(hidden: Boolean) { captureHidden = hidden; captureHistory += hidden }
    }
}
