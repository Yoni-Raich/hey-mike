/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license from the copyright holder.
 * See LICENSE, LICENSE-COMMERCIAL.md and NOTICE. Provided without warranty.
 */
package dev.androidagent.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadRefreshGateTest {
    @Test fun aBurstDoesNotQueueAnotherListingAndOtherComputersRemainIndependent() = runBlocking {
        val gate = ThreadRefreshGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async { gate.run("Pc", true) { entered.complete(Unit); finish.await() } }
        entered.await()
        repeat(100) { assertFalse(gate.run("Pc", true) { fail("duplicate request") }) }
        assertTrue(gate.run("Server", true) {})
        finish.complete(Unit)
        assertTrue(first.await())
    }

    @Test fun quietOpeningsReuseAnAttemptButManualRefreshAndExpiryCanRefresh() = runBlocking {
        var now = 0L
        val gate = ThreadRefreshGate(quietIntervalNs = 15, nowNs = { now })
        var calls = 0
        assertTrue(gate.run("Pc", false) { calls++ })
        now = 14
        assertFalse(gate.run("Pc", false) { calls++ })
        assertTrue(gate.run("Pc", true) { calls++ })
        now = 29
        assertTrue(gate.run("Pc", false) { calls++ })
        assertEquals(3, calls)
    }

    @Test fun cancellationReleasesTheSlotAndIsNeverSwallowed() = runBlocking {
        val gate = ThreadRefreshGate()
        val entered = CompletableDeferred<Unit>()
        val pending = async { gate.run("Pc", true) { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(gate.run("Pc", true) {})
    }
}
