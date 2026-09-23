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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Device ownership for a firing rule.
 *
 * A rule that drives the screen needs the same exclusive claim a person's turn
 * has — one screen cannot be shared — and Stop has to keep working through it.
 */
class CoordinatorAutomationTest {

    @Test fun aRuleTakesTheDeviceAndGivesItBack() = runTest {
        val rig = Rig(this)
        assertTrue(rig.coordinator.available.value)

        val ran = rig.coordinator.runAutomation("Automation · post", File("ws")) {
            assertFalse("the device is claimed while the rule runs", rig.coordinator.available.value)
            assertTrue("the gateways are armed", rig.tools.armed)
            assertTrue("the control card is up", rig.overlay.visible)
            "done"
        }

        assertEquals("done", ran)
        assertTrue("released", rig.coordinator.available.value)
        assertFalse("the gateways are revoked again", rig.tools.armed)
        rig.close()
    }

    @Test fun aRuleFiredFromInsideATurnWaitsForThatTurnInsteadOfReportingBusy() = runTest {
        // The common case is not a collision: it is the agent firing a rule
        // from a turn that already owns the device. Refusing there would make
        // "run my evening rule now" always answer "the phone is busy", with the
        // busy run being the one that asked.
        val rig = Rig(this)
        rig.occupy()
        advanceUntilIdle()
        assertFalse(rig.coordinator.available.value)

        val claimed = async {
            rig.coordinator.runAutomation("Automation · post", File("ws")) { "ran" }
        }
        advanceTimeBy(5_000)
        assertFalse("still waiting while the turn holds the device", claimed.isCompleted)

        rig.release()
        advanceUntilIdle()
        assertEquals("ran", claimed.await())
        rig.close()
    }

    @Test fun aRuleGivesUpRatherThanSurfacingLongAfterItsMoment() = runTest {
        val rig = Rig(this)
        rig.occupy()
        advanceUntilIdle()

        val claimed = async {
            rig.coordinator.runAutomation("Automation · post", File("ws"), waitMs = 30_000) { "ran" }
        }
        advanceTimeBy(31_000)
        advanceUntilIdle()

        assertNull("the device never came free, so nothing ran", claimed.await())
        rig.close()
    }

    @Test fun withNoWaitABusyPhoneRefusesImmediately() = runTest {
        val rig = Rig(this)
        rig.occupy()
        advanceUntilIdle()
        assertNull(rig.coordinator.runAutomation("Automation", File("ws"), waitMs = 0) { "ran" })
        rig.close()
    }

    @Test fun stopDuringARuleRevokesTheToolsAndOwnsTheTeardown() = runTest {
        // Revoking is what aborts a workflow between steps, so the rule stops
        // where it is rather than driving on.
        val rig = Rig(this)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val claimed = async {
            rig.coordinator.runAutomation("Automation · post", File("ws")) {
                entered.complete(Unit)
                finish.await()
                rig.tools.armed
            }
        }
        advanceUntilIdle()
        entered.await()

        rig.coordinator.stop()
        advanceUntilIdle()
        assertFalse("Stop revoked the gateways mid-rule", rig.tools.armed)

        finish.complete(Unit)
        advanceUntilIdle()
        claimed.await()
        // Stop owns the teardown from here; the rule must not re-release and
        // hand the device back a second time.
        assertTrue(rig.coordinator.available.value)
        rig.close()
    }

    @Test fun aRuleThatThrowsStillGivesTheDeviceBack() = runTest {
        val rig = Rig(this)
        val failed = runCatching {
            rig.coordinator.runAutomation<Unit>("Automation", File("ws")) { error("the workflow blew up") }
        }
        advanceUntilIdle()
        assertTrue(failed.isFailure)
        assertTrue("released even on a failure", rig.coordinator.available.value)
        assertFalse(rig.tools.armed)
        rig.close()
    }

    private class Rig(test: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val engine = QuietEngine()
        val store = QuietStore()
        val tools = ArmableTools()
        val overlay = VisibleOverlay()
        val coordinator = AgentCoordinator(scope, engine, store, tools, overlay)

        fun occupy() = coordinator.send("one", "occupying")
        suspend fun release() =
            engine.emit(EngineEvent.TurnFinished("completed", threadId = "thread", turnId = "turn"))

        fun close() = scope.cancel()
    }

    private class QuietEngine : AgentEngine {
        private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val events = stream.asSharedFlow()
        suspend fun emit(event: EngineEvent) { stream.emit(event) }
        override suspend fun connect() = Unit
        override suspend fun account() = AccountStatus(true, "test")
        override suspend fun login() = AccountStatus(true, "test")
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("test")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) = "thread"
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String {
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
        override val sessions = MutableStateFlow(listOf(ChatSession("one", "One", 0, 0)))
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

    private class ArmableTools : DeviceToolGateway {
        var armed = false
        override val definitions = emptyList<ToolDefinition>()
        override fun beginRun(runId: String, workspace: File) { armed = true }
        override fun revoke() { armed = false }
        override fun needsControl(name: String) = false
        override suspend fun invoke(name: String, arguments: JsonObject) = ToolResult("")
        override suspend fun cancel() = Unit
    }

    private class VisibleOverlay : ControlOverlay {
        var visible = false
        override suspend fun show(status: String) { visible = true }
        override fun update(status: String) = Unit
        override fun hide() { visible = false }
    }
}
