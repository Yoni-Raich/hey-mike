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

import org.junit.Assert.*
import org.junit.Test

class AutomationOutputGateTest {
    private val condition = AutomationCondition(AutomationCondition.Kind.DEVICE_STATE,
        stateName = "bluetooth_headphones", equals = "connected", deviceAddress = "AA:BB:CC:DD:EE:01", profile = "hfp")
    private val selected = AutomationConnection("bluetooth_headphones", "AA:BB:CC:DD:EE:01", "Same name", setOf("hfp"))
    private fun snapshot(connection: AutomationConnection = selected, state: String = "connected") =
        AutomationDeviceSnapshot(mapOf("bluetooth_headphones" to state), listOf(connection))
    @Test fun replacingTheSelectedHeadphonesStopsSpeechEvenIfAnotherPairIsConnected() {
        val gate = AutomationOutputGate().apply { reset(listOf(condition)) }
        assertTrue(gate.allows(snapshot()))
        assertFalse(gate.allows(snapshot(selected.copy(address = "AA:BB:CC:DD:EE:02"))))
        assertFalse(gate.allows(snapshot())) // No automatic replay after reconnection.
        gate.reset(listOf(condition))
        assertTrue(gate.allows(snapshot()))
    }
    @Test fun losingTheRequiredProfileOrPermissionStopsOutput() {
        for (changed in listOf(snapshot(selected.copy(profiles = setOf("a2dp"))), snapshot(state = "unknown"))) {
            val gate = AutomationOutputGate().apply { reset(listOf(condition)) }
            assertFalse(gate.allows(changed))
            assertFalse(gate.allows(snapshot()))
        }
    }
    @Test fun wifiAndHeadphonesMustBothStillMatchBeforeTheOpening() {
        val wifi = AutomationCondition(AutomationCondition.Kind.DEVICE_STATE, stateName = "wifi", equals = "connected", ssid = "Home")
        val gate = AutomationOutputGate().apply { reset(listOf(condition, wifi)) }
        val live = snapshot().copy(states = snapshot().states + ("wifi" to "connected"),
            connections = listOf(selected, AutomationConnection("wifi", ssid = "Home", bssid = "AA:BB:CC:DD:EE:99")))
        assertTrue(gate.allows(live))
        assertFalse(gate.allows(live.copy(connections = listOf(selected, AutomationConnection("wifi", ssid = "Other")))))
    }
    @Test fun ordinaryVoiceHasNoConnectionRequirement() {
        assertTrue(AutomationOutputGate().allows(AutomationDeviceSnapshot()))
    }
    @Test fun notDisconnectedAlsoRequiresProtectedOutputAndStaleIdentitiesCannotProveConnection() {
        assertTrue(condition.copy(equals = "disconnected", negate = true).requiresHeadphonesOutput())
        val gate = AutomationOutputGate().apply { reset(listOf(condition)) }
        assertFalse(gate.allows(snapshot(state = "disconnected")))
    }
}
