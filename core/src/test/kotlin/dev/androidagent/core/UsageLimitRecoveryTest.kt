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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UsageLimitRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    private val codexA = LimitTarget(EngineKind.CODEX, "a")
    private val codexB = LimitTarget(EngineKind.CODEX, "b")
    private val claude = LimitTarget(EngineKind.CLAUDE)
    private val base = 1_800_000_000_000L

    private class FakeHost(var current: LimitTarget) : LimitRecoveryHost {
        val switches = mutableListOf<LimitTarget>()
        val continued = mutableListOf<EngineKind>()
        val notes = mutableListOf<String>()
        val readings = mutableMapOf<String, List<UsageLimit>>()
        val unavailable = mutableSetOf<String>()
        var failSwitch = false
        var switchReady: CompletableDeferred<Unit>? = null
        var releaseSwitch: CompletableDeferred<Unit>? = null
        var wake: Long? = null
        override suspend fun current(sessionId: String) = current
        override suspend fun canRun(sessionId: String, target: LimitTarget) = target.key !in unavailable
        override fun readings(target: LimitTarget) = readings[target.key].orEmpty()
        override suspend fun switchTo(sessionId: String, target: LimitTarget, allowed: () -> Boolean) {
            switchReady?.complete(Unit)
            releaseSwitch?.await()
            if (!allowed()) return
            if (failSwitch) error("busy")
            switches += target
            current = target
        }
        override suspend fun continueChat(sessionId: String, engine: EngineKind, allowed: () -> Boolean) {
            if (allowed()) continued += engine
        }
        override suspend fun note(sessionId: String, text: String) { notes += text }
        override fun wakeAt(atMillis: Long?) { wake = atMillis }
        override fun label(target: LimitTarget) = target.key
    }

    private fun TestScope.recovery(host: FakeHost, scope: CoroutineScope = backgroundScope) =
        UsageLimitRecovery(scope, LimitBook(temp.root.resolve("limits.json")), host) { base + testScheduler.currentTime }

    private fun limited(run: String, resetsAt: Long? = null) =
        RunState(phase = RunPhase.ERROR, runId = run, limit = LimitReached(resetsAt = resetsAt))

    @Test
    fun recognizesLimitTextButNotOrdinaryErrors() {
        assertNotNull(UsageLimitText.recognize("You've hit your usage limit. Upgrade to Pro or try again later."))
        assertNotNull(UsageLimitText.recognize("Codex error: usageLimitExceeded"))
        assertNotNull(UsageLimitText.recognize("Weekly limit reached ∙ resets Nov 2"))
        assertEquals(1_800_000_500L, UsageLimitText.recognize("Claude AI usage limit reached|1800000500")?.resetsAt)
        assertNull(UsageLimitText.recognize("Context window limit exceeded"))
        assertNull(UsageLimitText.recognize("Rate limited, retrying"))
        assertNull(UsageLimitText.recognize(null))
    }

    @Test
    fun withoutPlanTheStopWaitsForTheUser() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.finished("s", limited("r1"))
        val stop = recovery.stops.value.getValue("s")
        assertEquals(LimitPhase.STOPPED, stop.phase)
        assertEquals(codexA, stop.target)
        assertTrue(host.continued.isEmpty())
    }

    @Test
    fun switchesToTheFirstFreeTargetInOrder() = runTest {
        val host = FakeHost(codexA)
        host.unavailable += codexB.key
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(switchTo = listOf(codexB, claude)))
        recovery.finished("s", limited("r1"))
        assertEquals(listOf(claude), host.switches)
        assertEquals(listOf(EngineKind.CLAUDE), host.continued)
        assertEquals(LimitPhase.CONTINUING, recovery.stops.value.getValue("s").phase)
    }

    @Test
    fun doesNotGoBackToAnAccountThatIsStillOut() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(switchTo = listOf(codexA, codexB)))
        recovery.finished("s", limited("r1", resetsAt = (base / 1000) + 3600))
        assertEquals(listOf(codexB), host.switches)
        // B runs out too: A is still out, nothing is left, no wait in the plan.
        recovery.finished("s", limited("r2"))
        assertEquals(listOf(codexB), host.switches)
        val stop = recovery.stops.value.getValue("s")
        assertEquals(LimitPhase.STOPPED, stop.phase)
        assertNotNull(stop.reason)
    }

    @Test
    fun waitsForTheResetThenContinues() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(continueAtReset = true))
        val reset = base + 2 * 3_600_000L
        recovery.finished("s", limited("r1", resetsAt = reset / 1000))
        val waiting = recovery.stops.value.getValue("s")
        assertEquals(LimitPhase.WAITING, waiting.phase)
        assertEquals(reset + UsageLimitRecovery.RESET_GRACE_MS, waiting.continueAt)
        assertEquals(waiting.continueAt, host.wake)
        advanceTimeBy(2 * 3_600_000L)
        runCurrent()
        assertTrue(host.continued.isEmpty())
        advanceTimeBy(UsageLimitRecovery.RESET_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf(EngineKind.CODEX), host.continued)
        assertTrue(host.switches.isEmpty())
        assertEquals(LimitPhase.CONTINUING, recovery.stops.value.getValue("s").phase)
    }

    @Test
    fun aFinishedTurnClearsTheStop() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(continueAtReset = true))
        recovery.finished("s", limited("r1"))
        recovery.continueNow("s")
        recovery.finished("s", RunState(phase = RunPhase.IDLE, runId = "r2"))
        assertNull(recovery.stops.value["s"])
        assertNull(host.wake)
    }

    @Test
    fun theSameRunIsHandledOnce() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(switchTo = listOf(claude)))
        recovery.finished("s", limited("r1"))
        recovery.finished("s", limited("r1"))
        assertEquals(1, host.continued.size)
    }

    @Test
    fun aFailedSwitchFallsBackToWaiting() = runTest {
        val host = FakeHost(codexA)
        host.failSwitch = true
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(continueAtReset = true, switchTo = listOf(claude)))
        recovery.finished("s", limited("r1"))
        assertEquals(LimitPhase.WAITING, recovery.stops.value.getValue("s").phase)
        assertTrue(host.notes.any { it.startsWith("Could not move") })
    }

    @Test
    fun aPlanSetAfterTheStopActsAtOnce() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.finished("s", limited("r1"))
        recovery.setPlan("s", LimitPlan(switchTo = listOf(claude)))
        assertEquals(listOf(EngineKind.CLAUDE), host.continued)
    }

    @Test
    fun cancelWaitLeavesTheChatStopped() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.finished("s", limited("r1"))
        recovery.continueAtReset("s")
        assertEquals(LimitPhase.WAITING, recovery.stops.value.getValue("s").phase)
        recovery.cancelWait("s")
        assertEquals(LimitPhase.STOPPED, recovery.stops.value.getValue("s").phase)
        advanceTimeBy(UsageLimitRecovery.UNKNOWN_RESET_MS * 2)
        runCurrent()
        assertTrue(host.continued.isEmpty())
    }

    @Test
    fun stopRevokesAnAlarmBeforeAsynchronousCleanupAndSurvivesRestart() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(continueAtReset = true))
        recovery.finished("s", limited("r1"))
        recovery.stop("s")
        advanceTimeBy(UsageLimitRecovery.UNKNOWN_RESET_MS * 2)
        recovery.dispatchDue()
        runCurrent()
        assertTrue(host.continued.isEmpty())
        assertEquals(LimitPhase.STOPPED, recovery.stops.value.getValue("s").phase)
        assertNull(host.wake)
        val restartedHost = FakeHost(codexA)
        val restarted = recovery(restartedHost)
        runCurrent()
        restarted.dispatchDue()
        assertTrue(restartedHost.continued.isEmpty())
    }

    @Test
    fun stopDuringAnAccountSwitchDoesNotSwitchOrQueueAContinuation() = runTest {
        val host = FakeHost(codexA).apply {
            switchReady = CompletableDeferred()
            releaseSwitch = CompletableDeferred()
        }
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(switchTo = listOf(codexB)))
        val switching = launch { recovery.finished("s", limited("r1")) }
        host.switchReady!!.await()
        recovery.stop("s")
        host.releaseSwitch!!.complete(Unit)
        switching.join()
        runCurrent()
        assertTrue(host.switches.isEmpty())
        assertTrue(host.continued.isEmpty())
    }

    @Test
    fun globalStopLeavesOtherChatsPausedWhenOneIsExplicitlyResumed() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        for (id in listOf("one", "two")) {
            recovery.setPlan(id, LimitPlan(continueAtReset = true))
            recovery.finished(id, limited(id))
        }
        recovery.stop()
        recovery.continueNow("one")
        advanceTimeBy(UsageLimitRecovery.UNKNOWN_RESET_MS * 2)
        recovery.dispatchDue()
        runCurrent()
        assertEquals(listOf(EngineKind.CODEX), host.continued)
        assertEquals(LimitPhase.STOPPED, recovery.stops.value.getValue("two").phase)
    }

    @Test
    fun aWaitSurvivesARestart() = runTest {
        val host = FakeHost(codexA)
        val first = recovery(host)
        first.setPlan("s", LimitPlan(continueAtReset = true))
        first.finished("s", limited("r1"))
        val continueAt = first.stops.value.getValue("s").continueAt!!
        val second = recovery(FakeHost(codexA))
        runCurrent()
        assertEquals(LimitPhase.WAITING, second.stops.value.getValue("s").phase)
        assertEquals(continueAt, second.stops.value.getValue("s").continueAt)
        assertEquals(LimitPlan(continueAtReset = true), second.planOf("s"))
    }

    @Test
    fun anInterruptedSwitchIsNotReplayedAfterRestart() = runTest {
        val book = LimitBook(temp.root.resolve("limits.json"))
        book.write(LimitBook.Saved(
            plans = mapOf("s" to LimitPlan(switchTo = listOf(codexB))),
            stops = mapOf("s" to LimitStop("s", codexA, stoppedAt = base, phase = LimitPhase.SWITCHING)),
        ))
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        runCurrent()
        assertEquals(LimitPhase.STOPPED, recovery.stops.value.getValue("s").phase)
        assertTrue(host.switches.isEmpty())
        assertTrue(host.continued.isEmpty())
    }

    @Test
    fun fullReadingTellsWhenTheAccountFreesUp() {
        val now = 1_000_000L
        val limits = listOf(
            UsageLimit("5h", 100.0, resetsAt = 2_000L),
            UsageLimit("weekly", 40.0, resetsAt = 9_000L),
        )
        assertEquals(2_000_000L, UsageLimitRecovery.fullUntil(limits, now))
        assertNull(UsageLimitRecovery.fullUntil(listOf(UsageLimit("5h", 99.0, 2_000L)), now))
    }

    @Test
    fun stopsAfterTooManyAutomaticTries() = runTest {
        val host = FakeHost(codexA)
        val recovery = recovery(host)
        recovery.setPlan("s", LimitPlan(continueAtReset = true))
        repeat(UsageLimitRecovery.MAX_ATTEMPTS + 1) { i ->
            recovery.finished("s", limited("r$i"))
            advanceTimeBy(UsageLimitRecovery.UNKNOWN_RESET_MS + UsageLimitRecovery.RESET_GRACE_MS + 1)
            runCurrent()
        }
        assertEquals(UsageLimitRecovery.MAX_ATTEMPTS, host.continued.size)
        assertEquals(LimitPhase.STOPPED, recovery.stops.value.getValue("s").phase)
        // Continue now is the user's decision, and it starts the count again.
        recovery.continueNow("s")
        assertEquals(UsageLimitRecovery.MAX_ATTEMPTS + 1, host.continued.size)
    }
}
