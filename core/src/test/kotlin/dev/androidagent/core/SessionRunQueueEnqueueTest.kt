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

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The queue's own rules for a turn queued on purpose, with a runner that can refuse a start. */
class SessionRunQueueEnqueueTest {

    @Test fun aRefusedStartPutsTheTurnBackAtTheHead() = runTest {
        val rig = Rig(this)
        advanceUntilIdle()
        rig.runner.refuse = 1
        rig.queue.enqueue(QueuedTurn(sessionId = "one", prompt = "B"))
        rig.queue.enqueue(QueuedTurn(sessionId = "one", prompt = "C"))
        advanceUntilIdle()
        // Refused once and put back first; C, of the same chat, is not tried past it.
        assertEquals(listOf("B", "B"), rig.runner.offered)
        assertEquals(listOf("B"), rig.runner.started)
        assertEquals(listOf("C"), rig.queue.turns.value.map { it.prompt })
        rig.close()
    }

    @Test fun aRefusedChatWaitsForItsRunToEndAndDoesNotHoldUpAnotherChat() = runTest {
        val rig = Rig(this)
        advanceUntilIdle()
        rig.runner.refuseChat = "one"
        rig.queue.enqueue(QueuedTurn(sessionId = "one", prompt = "B"))
        rig.queue.enqueue(QueuedTurn(sessionId = "two", prompt = "X"))
        advanceUntilIdle()
        assertEquals(listOf("X"), rig.runner.started)
        assertEquals(listOf("B"), rig.queue.turns.value.map { it.prompt })

        rig.runner.refuseChat = null
        rig.runner.freed.emit(Unit); advanceUntilIdle()
        assertEquals(listOf("X", "B"), rig.runner.started)
        assertTrue(rig.queue.turns.value.isEmpty())
        rig.close()
    }

    @Test fun aFullQueueRefusesAnotherTurnAndKeepsTheRest() = runTest {
        val rig = Rig(this)
        rig.runner.running += "one"
        repeat(SessionRunQueue.MAX_QUEUED) { rig.queue.enqueue(QueuedTurn(sessionId = "one", prompt = "t$it")) }
        try {
            rig.queue.enqueue(QueuedTurn(sessionId = "one", prompt = "one too many"))
            fail("A full queue must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("full"))
        }
        assertEquals(SessionRunQueue.MAX_QUEUED, rig.queue.turns.value.size)
        assertTrue(rig.runner.steered.isEmpty())
        rig.close()
    }

    @Test fun aTurnForAChatThatIsGoneIsRefused() = runTest {
        val rig = Rig(this)
        try {
            rig.queue.enqueue(QueuedTurn(sessionId = "gone", prompt = "B"))
            fail("A missing chat must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(rig.queue.turns.value.isEmpty())
        }
        rig.close()
    }

    @Test fun queuedTurnsSurviveARestartWithTheirModelAndWaitForResume() = runTest {
        val rig = Rig(this)
        rig.runner.running += "one"
        rig.queue.enqueue(QueuedTurn(sessionId = "one", prompt = "B", model = "model-b", engine = EngineKind.CLAUDE))
        advanceUntilIdle()
        val again = SessionRunQueue(rig.scope, rig.runner, rig.store)
        rig.runner.running.clear()
        advanceUntilIdle()
        assertTrue(again.paused.value)
        assertTrue(rig.runner.started.isEmpty())
        again.resume(); advanceUntilIdle()
        assertEquals(listOf("B"), rig.runner.started)
        assertEquals("model-b", rig.runner.turns.single().model)
        assertEquals(EngineKind.CLAUDE, rig.runner.turns.single().engine)
        rig.close()
    }

    @Test fun aModelMoveWaitsUntilTheChatNeitherRunsNorHasQueuedTurns() = runTest {
        val rig = Rig(this)
        var moves = 0
        rig.runner.running += "one"
        assertEquals(false, rig.queue.ifIdle("one") { moves++ })
        rig.queue.enqueue(QueuedTurn(sessionId = "one", prompt = "B"))
        rig.runner.running.clear()
        rig.queue.pause()
        // Not running, but B waits with its own model: the move would run ahead of it.
        assertEquals(false, rig.queue.ifIdle("one") { moves++ })
        // Another chat's queue does not hold this one.
        assertEquals(true, rig.queue.ifIdle("two") { moves++ })
        rig.queue.cancel(rig.queue.turns.value.single().id)
        assertEquals(true, rig.queue.ifIdle("one") { moves++ })
        assertEquals(2, moves)
        rig.close()
    }

    private class Rig(test: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val runner = Runner()
        val store = Store()
        val queue = SessionRunQueue(scope, runner, store)
        fun close() = scope.cancel()
    }

    private class Runner : TurnRunner {
        override val freed = MutableSharedFlow<Unit>()
        val running = mutableSetOf<String>()
        var refuse = 0
        var refuseChat: String? = null
        val offered = mutableListOf<String>()
        val started = mutableListOf<String>()
        val turns = mutableListOf<QueuedTurn>()
        val steered = mutableListOf<String>()
        override fun canStart() = true
        override fun phaseOf(sessionId: String): RunPhase? = if (sessionId in running) RunPhase.THINKING else null
        override fun steer(sessionId: String, prompt: String) { steered += prompt }
        override fun start(turn: QueuedTurn): Boolean {
            offered += turn.prompt
            if (turn.sessionId == refuseChat) return false
            if (refuse > 0) { refuse--; return false }
            started += turn.prompt
            turns += turn
            running += turn.sessionId
            return true
        }
        override fun send(
            sessionId: String, prompt: String, images: List<File>, model: String?, reasoningEffort: String?,
            skill: AgentSkill?, planMode: Boolean, engineKind: EngineKind?,
        ) { started += prompt }
    }

    private class Store : SessionStore {
        private var queued = emptyList<QueuedTurn>()
        override suspend fun loadQueuedTurns() = queued
        override suspend fun saveQueuedTurns(turns: List<QueuedTurn>) { queued = turns }
        override val sessions = MutableStateFlow(listOf(ChatSession("one", "One", 0, 0), ChatSession("two", "Two", 0, 0)))
        override suspend fun createSession(engine: EngineKind) = sessions.value.first()
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File("session-$sessionId")
    }
}
