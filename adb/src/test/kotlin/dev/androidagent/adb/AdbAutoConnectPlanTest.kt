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

package dev.androidagent.adb

import dev.androidagent.core.AdbEndpoint
import org.junit.Assert.*
import org.junit.Test

class AdbAutoConnectPlanTest {

    private fun connect(port: Int) = AdbEndpoint(port, pairing = false)
    private fun pairing(port: Int) = AdbEndpoint(port, pairing = true)

    @Test fun theSavedPortIsTriedFirst() {
        val target = AdbAutoConnectPlan.target(41231, listOf(connect(37199)), tried = emptySet())
        assertEquals(41231, target)
    }

    @Test fun aPortAlreadyTriedIsNeverOfferedAgain() {
        // Without this the loop would keep dialling the same refused port for
        // all six attempts and never reach the one that works.
        val endpoints = listOf(connect(37199), connect(45001))
        assertEquals(45001, AdbAutoConnectPlan.target(null, endpoints, tried = setOf(37199)))
        assertNull(AdbAutoConnectPlan.target(null, endpoints, tried = setOf(37199, 45001)))
    }

    @Test fun aStaleSavedPortIsDroppedOnceItFails() {
        val endpoints = listOf(connect(45001))
        assertEquals(45001, AdbAutoConnectPlan.target(41231, endpoints, tried = setOf(41231)))
    }

    @Test fun pairingEndpointsAreNeverConnectTargets() {
        // Connecting to the pairing port fails in a way that looks like a
        // network problem, so it must not be reachable from here at all.
        assertNull(AdbAutoConnectPlan.target(null, listOf(pairing(37199)), tried = emptySet()))
    }

    @Test fun portsOutsideTheAdbRangeAreRejected() {
        assertNull(AdbAutoConnectPlan.target(80, listOf(connect(22)), tried = emptySet()))
    }

    @Test fun nothingAdvertisedYieldsNoTarget() {
        assertNull(AdbAutoConnectPlan.target(null, emptyList(), tried = emptySet()))
    }

    @Test fun progressMessagesNameThePortAndTheStage() {
        assertTrue(AdbAutoConnectPlan.attemptMessage(0, 41231).contains("41231"))
        assertTrue(AdbAutoConnectPlan.attemptMessage(2, 41231).contains("41231"))
        assertTrue(AdbAutoConnectPlan.attemptMessage(0, null).contains("Looking"))
        assertTrue(AdbAutoConnectPlan.attemptMessage(3, null).contains("Still"))
    }

    @Test fun givingUpSaysPairingSurvived() {
        // Pairing is what costs the user a trip to the system dialog. Every
        // give-up message has to make clear it does not need repeating.
        for (enabled in listOf(true, false, null)) {
            assertTrue(AdbAutoConnectPlan.giveUpMessage(enabled).startsWith("Paired"))
        }
        assertTrue(AdbAutoConnectPlan.giveUpMessage(false).contains("switched off"))
        assertTrue(AdbAutoConnectPlan.giveUpMessage(true).contains("background"))
    }

    @Test fun theRetryBudgetCoversEveryAttempt() {
        val backoff = (0 until AdbAutoConnectPlan.MAX_ATTEMPTS).sumOf { AdbReconnectPolicy.retryDelayMs(it) }
        val discovery = AdbAutoConnectPlan.MAX_ATTEMPTS * AdbServiceDiscovery.DEFAULT_TIMEOUT_MS
        assertTrue(
            "budget ${AdbAutoConnectPlan.TOTAL_BUDGET_MS}ms must cover ${backoff + discovery}ms of work",
            AdbAutoConnectPlan.TOTAL_BUDGET_MS >= backoff + discovery,
        )
    }
}
