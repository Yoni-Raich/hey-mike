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

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AutomationPlacesTest {
    private val home = AutomationPlace("Home", 32.0853, 34.7818, 150)

    @Test fun firstFixSetsTheBaselineWithoutAnEvent() {
        val tracker = PlaceTracker()
        assertTrue(tracker.onFix(listOf(home), 32.0853, 34.7818, 20f).isEmpty())
        assertEquals(setOf("Home"), tracker.current)
    }

    @Test fun leavingAndEnteringFireOnceWithAMarginAtTheEdge() {
        val tracker = PlaceTracker()
        tracker.onFix(listOf(home), 32.0853, 34.7818, 20f)
        // About 160 m north: past the radius but inside the leave margin.
        assertTrue(tracker.onFix(listOf(home), 32.08674, 34.7818, 20f).isEmpty())
        // About 1 km away: left.
        assertEquals(listOf("Home" to AutomationTrigger.PlaceTransition.EXIT), tracker.onFix(listOf(home), 32.0943, 34.7818, 20f))
        assertEquals(listOf("Home" to AutomationTrigger.PlaceTransition.ENTER), tracker.onFix(listOf(home), 32.0854, 34.7818, 20f))
    }

    @Test fun aCoarseFixIsNotEvidence() {
        val tracker = PlaceTracker()
        tracker.onFix(listOf(home), 32.0853, 34.7818, 20f)
        assertTrue(tracker.onFix(listOf(home), 32.2, 34.7818, 2_000f).isEmpty())
        assertEquals(setOf("Home"), tracker.current)
    }

    @Test fun gatewaySavesListsAndDeletesPlaces() {
        val dir = Files.createTempDirectory("auto").toFile()
        val places = AutomationPlaces(AutomationPlaces.fileIn(dir))
        val gateway = AutomationToolGateway(
            library = AutomationLibrary(AutomationLibrary.directoryIn(dir)),
            history = AutomationJournal(AutomationJournal.fileIn(dir)),
            places = places,
            currentLocation = { 32.0 to 34.0 },
            insidePlaces = { setOf("work") },
        )
        gateway.beginRun("r", dir)
        fun call(args: JsonObject) = Json.parseToJsonElement(runBlocking { gateway.invoke("automation_rule", args) }.text).jsonObject

        val saved = call(buildJsonObject { put("mode", "save_place"); put("place", buildJsonObject { put("name", "Work") }) })
        assertEquals("true", saved["ok"]!!.jsonPrimitive.content)
        assertEquals(32.0, places.all().single().latitude, 0.0)

        val listed = call(buildJsonObject { put("mode", "places") })
        val first = listed["places"]!!.jsonArray[0].jsonObject
        assertEquals("true", first["inside"]!!.jsonPrimitive.content)

        val bad = call(buildJsonObject { put("mode", "save_place"); put("place", buildJsonObject { put("name", "X"); put("latitude", 200.0); put("longitude", 0.0) }) })
        assertEquals("place_invalid", bad["errorType"]!!.jsonPrimitive.content)

        call(buildJsonObject { put("mode", "delete_place"); put("name", "work") })
        assertTrue(places.all().isEmpty())
    }
}
