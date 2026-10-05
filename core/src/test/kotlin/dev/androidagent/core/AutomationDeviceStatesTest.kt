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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AutomationDeviceStatesTest {
    @Test fun aSpecificGattWatchMatchesDeviceConditionAndNeverHeadphonesCondition() {
        val device = condition(""""state":"bluetooth_device","equals":"connected","deviceAddress":"AA:BB:CC:DD:EE:01","profile":"gatt"""")
        val headphones = condition(""""state":"bluetooth_headphones","equals":"connected"""")
        val context = AutomationContext(now, deviceState = mapOf("bluetooth_device" to "connected", "bluetooth_headphones" to "disconnected"),
            connections = listOf(AutomationConnection("bluetooth_device", "AA:BB:CC:DD:EE:01", "Watch", setOf("gatt"))))
        assertTrue(device.holds(AutomationEvent.Clock(now), context))
        assertFalse(headphones.holds(AutomationEvent.Clock(now), context))
    }
    @Test fun exactBluetoothIdentityAndProfileCannotMatchAnotherDeviceWithTheSameName() {
        val selected = condition(""""state":"bluetooth_headphones","equals":"connected","deviceAddress":"AA:BB:CC:DD:EE:01","profile":"hfp"""")
        val other = AutomationConnection("bluetooth_headphones", "AA:BB:CC:DD:EE:02", "My buds", setOf("hfp"))
        val wanted = other.copy(address = "aa:bb:cc:dd:ee:01")
        fun matches(entries: List<AutomationConnection>) = selected.holds(AutomationEvent.Clock(now),
            AutomationContext(now, deviceState = mapOf("bluetooth_headphones" to "connected"), connections = entries))
        assertFalse(matches(listOf(other)))
        assertFalse(matches(listOf(wanted.copy(profiles = setOf("a2dp")))))
        assertTrue(matches(listOf(wanted)))
        assertFalse(matches(emptyList()))
        assertEquals(selected, AutomationCondition.parse(selected.toJson(), 0, "roundtrip"))
    }
    @Test fun wifiSsidIsExactAndOptionalBssidPinsOneAccessPoint() {
        val selected = condition(""""state":"wifi","equals":"connected","ssid":" My Home ","bssid":"AA:BB:CC:DD:EE:01"""")
        fun matches(ssid: String, bssid: String, state: String = "connected") = selected.holds(AutomationEvent.Clock(now),
            AutomationContext(now, deviceState = mapOf("wifi" to state), connections = listOf(AutomationConnection("wifi", ssid = ssid, bssid = bssid))))
        assertTrue(matches(" My Home ", "aa:bb:cc:dd:ee:01"))
        assertFalse(matches(" My Home ", "AA:BB:CC:DD:EE:02"))
        assertFalse(matches("my home", "AA:BB:CC:DD:EE:01"))
        assertFalse(matches(" My Home ", "AA:BB:CC:DD:EE:01", "unknown"))
        assertEquals(selected, AutomationCondition.parse(selected.toJson(), 0, "roundtrip"))
    }
    @Test fun malformedOrWrongKindSelectorsCannotBeSilentlyIgnored() {
        for (fields in listOf(
            """"state":"wifi","equals":"connected","deviceAddress":"AA:BB:CC:DD:EE:01"""",
            """"state":"bluetooth_headphones","equals":"connected","ssid":"Home"""",
            """"state":"wifi","equals":"connected","ssid":null""",
            """"state":"wifi","equals":"connected","bssid":"02:00:00:00:00:00"""",
            """"state":"bluetooth_headphones","equals":"connected","profile":"made_up"""",
        )) assertTrue(fields, runCatching { condition(fields) }.isFailure)
    }
    private fun condition(fields: String) = AutomationCondition.parse(
        Json.parseToJsonElement("""{"type":"device_state",$fields}""").jsonObject, 0, "headphones",
    )
    private val now = ZonedDateTime.parse("2026-10-05T19:00:00+03:00")
    private fun holds(condition: AutomationCondition, state: String?) = condition.holds(
        AutomationEvent.Clock(now), AutomationContext(now, deviceState = state?.let {
            mapOf("bluetooth_headphones" to it)
        }.orEmpty()),
    )

    @Test fun rejectsFictitiousSignalsAndValues() {
        assertTrue(runCatching { condition(""""state":"bluetooth_audio","equals":"connected"""") }.isFailure)
        assertTrue(runCatching { condition(""""state":"bluetooth_headphones","equals":"paired"""") }.isFailure)
    }
    @Test fun rejectsIsTypoRatherThanTreatingItAsIsSet() {
        assertTrue(runCatching { condition(""""state":"bluetooth_headphones","is":"connected"""") }.isFailure)
        assertTrue(runCatching { condition(""""state":"bluetooth_headphones"""") }.isFailure)
    }
    @Test fun onlyConnectedIsTrueAndUnknownFailsClosedEvenWhenNegated() {
        val connected = condition(""""state":"bluetooth_headphones","equals":"connected"""")
        assertTrue(holds(connected, "connected"))
        assertFalse(holds(connected, "disconnected"))
        for (state in listOf(null, "unknown")) {
            assertFalse(holds(connected, state))
            assertFalse(holds(connected.copy(negate = true), state))
        }
    }
    @Test fun deviceStateTriggersAlsoRejectUnknownSignals() {
        assertTrue(runCatching { AutomationTrigger.parse(Json.parseToJsonElement(
            """{"type":"device_state","state":"made_up","is":"connected"}"""
        ).jsonObject, "bad") }.isFailure)
    }
}
