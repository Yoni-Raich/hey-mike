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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutomationPlacesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = AutomationPlaceStore(File(temp.root, "automations/places.json"))

    private fun place(
        id: String = "home",
        lat: Double = 32.0853,
        lng: Double = 34.7818,
        radius: Int = AutomationPlace.DEFAULT_RADIUS_M,
    ) = AutomationPlace(id, id, lat, lng, radius)

    // ---- the circle ----

    @Test fun distanceIsMetresOnTheGround() {
        val home = place()
        // Roughly one minute of latitude north: about 1.85km.
        val far = home.distanceMeters(32.0853 + 1.0 / 60.0, 34.7818)
        assertTrue("was $far", far in 1_700.0..2_000.0)
        assertEquals(0.0, home.distanceMeters(32.0853, 34.7818), 0.5)
    }

    @Test fun aRadiusTooSmallToDetectIsRefusedRatherThanAccepted() {
        val tooSmall = Json.parseToJsonElement(
            """{"id":"home","latitude":32.0,"longitude":34.0,"radiusMeters":10}""",
        ).jsonObject
        val error = runCatching { AutomationPlace.parse(tooSmall) }.exceptionOrNull()
        assertTrue(error is AutomationFormatException)
        // The reply says why, not just that it is out of range.
        assertTrue(error!!.message!!.contains("wanders"))
    }

    @Test fun aPointThatIsNotOnEarthIsRefused() {
        for (body in listOf(
            """{"id":"x","latitude":95.0,"longitude":34.0}""",
            """{"id":"x","latitude":32.0,"longitude":-181.0}""",
        )) {
            val error = runCatching { AutomationPlace.parse(Json.parseToJsonElement(body).jsonObject) }
                .exceptionOrNull()
            assertTrue(body, error is AutomationFormatException)
        }
    }

    @Test fun coordinatesWrittenAsStringsStillParse() {
        val parsed = AutomationPlace.parse(
            Json.parseToJsonElement("""{"id":"home","latitude":"32.0853","longitude":"34.7818"}""").jsonObject,
        )
        assertEquals(32.0853, parsed.latitude, 1e-9)
    }

    @Test fun anIdIsNormalisedRatherThanRefusedForItsPunctuation() {
        val parsed = AutomationPlace.parse(
            Json.parseToJsonElement("""{"id":"Mum's House","latitude":1.0,"longitude":2.0}""").jsonObject,
        )
        assertEquals("mum-s-house", parsed.id)
    }

    // ---- arriving and leaving ----

    @Test fun crossingTheCircleEntersOnce() {
        val places = listOf(place())
        val arriving = AutomationPlaceWatch.update(emptySet(), places, 32.0853, 34.7818, 20f)
        assertEquals(listOf("home"), arriving.entered.map { it.id })
        assertEquals(setOf("home"), arriving.inside)

        // Still there on the next fix: no second arrival.
        val staying = AutomationPlaceWatch.update(arriving.inside, places, 32.0853, 34.7818, 20f)
        assertTrue(staying.entered.isEmpty())
        assertTrue(staying.left.isEmpty())
    }

    @Test fun leavingNeedsMoreThanNotArriving() {
        val places = listOf(place(radius = 150))
        val inside = setOf("home")
        // 170m out: past the radius, inside the exit margin. Still home.
        val wobble = AutomationPlaceWatch.update(inside, places, 32.0853 + 170 / 111_320.0, 34.7818, 20f)
        assertTrue(wobble.left.isEmpty())
        assertEquals(setOf("home"), wobble.inside)

        // 400m out: actually gone.
        val gone = AutomationPlaceWatch.update(inside, places, 32.0853 + 400 / 111_320.0, 34.7818, 20f)
        assertEquals(listOf("home"), gone.left.map { it.id })
        assertTrue(gone.inside.isEmpty())
    }

    @Test fun aFixTooRoughToDecideWithChangesNothing() {
        val places = listOf(place(radius = 150))
        // 2km of accuracy cannot tell inside from outside a 150m circle.
        val fromOutside = AutomationPlaceWatch.update(emptySet(), places, 32.0853, 34.7818, 2_000f)
        assertTrue(fromOutside.entered.isEmpty())
        assertTrue(fromOutside.inside.isEmpty())

        val fromInside = AutomationPlaceWatch.update(setOf("home"), places, 33.0, 35.0, 2_000f)
        assertTrue(fromInside.left.isEmpty())
        assertEquals(setOf("home"), fromInside.inside)
    }

    @Test fun aFixWithNoAccuracyIsTrustedRatherThanIgnored() {
        val change = AutomationPlaceWatch.update(emptySet(), listOf(place()), 32.0853, 34.7818, null)
        assertEquals(listOf("home"), change.entered.map { it.id })
    }

    @Test fun forgettingAPlaceDoesNotReportLeavingIt() {
        // "home" is gone from the store; the phone was inside it.
        val change = AutomationPlaceWatch.update(setOf("home"), listOf(place(id = "office")), 0.0, 0.0, 10f)
        assertTrue(change.left.isEmpty())
        assertTrue(change.inside.isEmpty())
    }

    @Test fun overlappingPlacesAreBothEntered() {
        val places = listOf(place(id = "home", radius = 200), place(id = "block", radius = 1_000))
        val change = AutomationPlaceWatch.update(emptySet(), places, 32.0853, 34.7818, 10f)
        assertEquals(setOf("home", "block"), change.entered.map { it.id }.toSet())
    }

    // ---- the store ----

    @Test fun savedPlacesSurviveAndSortByName() {
        val store = store()
        store.save(place(id = "office"))
        store.save(place(id = "home"))
        assertEquals(listOf("home", "office"), store.all().map { it.id })
        assertEquals(listOf("home", "office"), AutomationPlaceStore(File(temp.root, "automations/places.json")).all().map { it.id })
    }

    @Test fun savingTheSameIdRefinesRatherThanDuplicates() {
        val store = store()
        store.save(place(id = "home", radius = 150))
        store.save(place(id = "home", radius = 400))
        assertEquals(1, store.all().size)
        assertEquals(400, store.all().single().radiusMeters)
    }

    @Test fun aPlaceIsFoundByItsIdOrByWhatTheUserCallsIt() {
        val store = store()
        store.save(AutomationPlace("mums-house", "Mum's house", 1.0, 2.0))
        assertEquals("mums-house", store.find("mums-house")?.id)
        assertEquals("mums-house", store.find("Mum's house")?.id)
        assertNull(store.find("the office"))
    }

    @Test fun forgettingRemovesOnlyThatPlace() {
        val store = store()
        store.save(place(id = "home"))
        store.save(place(id = "office"))
        assertTrue(store.delete("home"))
        assertFalse(store.delete("home"))
        assertEquals(listOf("office"), store.all().map { it.id })
    }

    @Test fun oneUnreadableEntryDoesNotHideTheRest() {
        val file = File(temp.root, "automations/places.json")
        file.parentFile.mkdirs()
        file.writeText(
            """{"home":{"id":"home","latitude":32.0,"longitude":34.0},"junk":{"id":"junk"}}""",
        )
        assertEquals(listOf("home"), AutomationPlaceStore(file).all().map { it.id })
    }

    @Test fun anEmptyStoreIsEmptyRatherThanAFailure() {
        assertEquals(emptyList<AutomationPlace>(), store().all())
    }
}
