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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private class Rig(test: TestScope, grants: SendGrantStore = InMemorySendGrantStore()) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val engine = FakeEngine()
        val store = FakeStore()
        val overlay = FakeOverlay()
        val tools = FakeTools(overlay)
        val adbStatus = MutableStateFlow(AdbStatus())
        var foregroundRequests = 0
        val coordinator = AgentCoordinator(
            scope, engine, store, tools, overlay,
            sendGrants = grants,
            bringToForeground = { foregroundRequests++ },
            // The test's own clock, so a reported duration is exactly the time
            // the test advanced rather than how fast the machine ran.
            nowNanos = { test.testScheduler.currentTime * 1_000_000 },
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
        val approvalAnswers = mutableListOf<Pair<String, Boolean>>()
        suspend fun emit(value: EngineEvent) = stream.emit(value)
        override suspend fun connect() = Unit
        override suspend fun account() = AccountStatus(true, "Test")
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("test")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) = "thread"
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String { turns++; return "turn" }
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
        override val sessions = MutableStateFlow(listOf(ChatSession("one", "One", 0, 0), ChatSession("two", "Two", 0, 0)))
        val messages = mutableListOf<ChatMessage>()
        override suspend fun createSession() = sessions.value.first()
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(messages.filter { it.sessionId == sessionId })
        override suspend fun append(message: ChatMessage) { messages.add(message) }
        override suspend fun updateMessage(id: String, text: String, state: String) { val i = messages.indexOfFirst { it.id == id }; if (i >= 0) messages[i] = messages[i].copy(text = text, state = state) }
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
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
        override suspend fun invoke(name: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult {
            check(!revoked)
            if (needsControl(name)) controlWasVisible = overlay.visible
            executions++; names.add(name)
            if (workMs > 0) delay(workMs)
            return ToolResult("Done")
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
