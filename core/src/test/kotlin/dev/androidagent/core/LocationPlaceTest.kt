package dev.androidagent.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The model sees a written place at the precision the fix supports, never coordinates; naming never blocks or renews a fix. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationPlaceTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val profile = "current_android_user:0"
    private val boot = 104
    private val t0 = 1_794_492_000_000L
    private val e0 = 50_000_000L
    private val minute = 60_000L
    private val dir: File = Files.createTempDirectory("location-place-test").toFile()
    private val file = File(dir, "situation.json")
    private val store = SharedSituationStore(file)
    private var clock = t0
    private val city = "פתח תקווה"
    /** What a geocoder may return next to a coarse fix: a real street and house the user is not known to be at. */
    private val nearIntel = PlaceAddress(locality = city, subLocality = "קריית אריה", thoroughfare = "הרי יהודה", subThoroughfare = "94",
        subAdminArea = "מחוז המרכז", adminArea = "מחוז המרכז", countryName = "ישראל", countryCode = "IL")

    @After fun cleanUp() { dir.deleteRecursively() }

    private fun fix(accuracy: Float, permission: String? = "coarse", place: SituationPlace? = null, at: Long = t0, elapsed: Long = e0) =
        SituationFix(32.08, 34.78, accuracy, "fused", at, elapsed, boot, profile, permission, place)
    private fun resolved(address: PlaceAddress = nearIntel) = SituationPlace(SituationPlace.RESOLVED, null, address, "he", t0)
    private fun fresh(at: Long = t0, elapsed: Long = e0, accuracy: Float = 2000f, permission: String = "coarse") =
        SituationLocation("fresh", at, at + LocationFreshness.CURRENT_MS, 32.08, 34.78, accuracy, "fused", profile, elapsed, boot,
            attemptedAt = at, permission = permission, tried = listOf("cache:none", "fused:fix"), source = "live")
    private fun failed(at: Long, lastKnown: SituationFix?) = SituationLocation("timeout", at, at, bootCount = boot, attemptedAt = at,
        permission = "coarse", tried = listOf("cache:9m old", "fused:timeout"), lastKnown = lastKnown)
    private fun snap(later: Long) = store.snapshot(t0 + later, zone, emptySet(), calendarProfile = profile, locationAllowed = true,
        locationBootCount = boot, elapsedNowMs = e0 + later)

    private class Geo(var answer: suspend () -> PlaceLookup) {
        val calls = mutableListOf<String>()
        suspend fun lookup(lat: Double, lon: Double, tag: String): PlaceLookup { calls += tag; return answer() }
    }
    private fun namer(geo: Geo, tag: () -> String = { "he" }) = PlaceNamer(geo::lookup, tag, { clock })

    // Detail follows accuracy.

    @Test fun aCoarseFixShowsTheCityAndNoStreetEvenWhenTheGeocoderFoundOne() {
        val view = PlaceDetail.view(fix(2000f, place = resolved()), PlaceDetail.CURRENT, 0)
        assertEquals(city, view.place)
        assertEquals(PlaceDetail.CITY, view.precision)
        assertEquals(2000, view.accuracyMeters)
        assertTrue(view.note.contains("the street and building are not known"))
        assertTrue(view.note.contains("saved home or work address is not where the user is"))
        val text = Json.encodeToString(LocationView.serializer(), view)
        assertFalse(text.contains("הרי יהודה") || text.contains("Intel") || text.contains("קריית אריה"))
        // Without the fine permission even a good accuracy number stays at the city.
        assertEquals(city, PlaceDetail.view(fix(15f, "coarse", resolved()), PlaceDetail.CURRENT, 0).place)
        assertEquals(PlaceDetail.CITY, PlaceDetail.precisionFor(15f, null))
    }

    @Test fun aFineAccurateFixShowsTheAddressAndLessAccurateOnesStepDown() {
        assertEquals("הרי יהודה 94, $city" to PlaceDetail.ADDRESS, PlaceDetail.label(nearIntel, PlaceDetail.precisionFor(15f, "fine")))
        assertEquals("הרי יהודה, $city" to PlaceDetail.STREET, PlaceDetail.label(nearIntel, PlaceDetail.precisionFor(80f, "fine")))
        assertEquals("קריית אריה, $city" to PlaceDetail.NEIGHBORHOOD, PlaceDetail.label(nearIntel, PlaceDetail.precisionFor(500f, "fine")))
        assertEquals(PlaceDetail.REGION, PlaceDetail.precisionFor(40_000f, "fine"))
        val view = PlaceDetail.view(fix(12f, "fine", resolved()), PlaceDetail.CURRENT, 0)
        assertEquals("הרי יהודה 94, $city", view.place)
        assertEquals(PlaceDetail.ADDRESS, view.precision)
    }

    @Test fun missingPartsFallBackToACoarserPlaceNeverAFinerOne() {
        assertEquals(city to PlaceDetail.CITY, PlaceDetail.label(nearIntel.copy(thoroughfare = null, subLocality = null), PlaceDetail.ADDRESS))
        assertEquals("מחוז המרכז" to PlaceDetail.REGION, PlaceDetail.label(PlaceAddress(subAdminArea = "מחוז המרכז", thoroughfare = "x"), PlaceDetail.CITY))
        assertEquals("ישראל" to PlaceDetail.REGION, PlaceDetail.label(PlaceAddress(countryName = "ישראל"), PlaceDetail.CITY))
        val empty = PlaceDetail.view(fix(2000f, place = resolved(PlaceAddress(countryCode = "IL"))), PlaceDetail.CURRENT, 0)
        assertEquals(SituationPlace.UNAVAILABLE, empty.placeStatus)
        assertNull(empty.place)
    }

    @Test fun anUnnamedFixSaysThePlaceIsUnavailableAndNeverInventsOne() {
        val view = PlaceDetail.view(fix(2000f, place = SituationPlace.unavailable("timeout", t0)), PlaceDetail.CURRENT, 0)
        assertNull(view.place)
        assertEquals(SituationPlace.UNAVAILABLE, view.placeStatus)
        assertEquals("timeout", view.placeReason)
        assertTrue(view.note.contains("place name is unavailable (timeout)") && view.note.contains("Do not guess"))
        assertEquals("not resolved", PlaceDetail.view(fix(2000f), PlaceDetail.CURRENT, 0).placeReason)
    }

    @Test fun theLanguageIsHebrewWhenThePhoneHasHebrew() {
        assertEquals("he-IL", PlaceDetail.locale(listOf("en-US", "iw-IL")))
        assertEquals("he", PlaceDetail.locale(listOf("fr", "iw")))
        assertEquals("he-IL", PlaceDetail.locale(listOf("he_IL")))
        assertEquals("en-US", PlaceDetail.locale(listOf("en-US", "fr")))
        assertEquals("en", PlaceDetail.locale(emptyList()))
    }

    // Bounded, cached naming.

    @Test fun aSlowGeocoderIsCutAtTheTimeoutAndTheTimeoutIsNotCached() = runTest {
        val geo = Geo { awaitCancellation() }
        val namer = namer(geo)
        val place = namer.name(fix(2000f))
        assertEquals(PlaceNamer.TIMEOUT_MS, currentTime)
        assertEquals("timeout", place.reason)
        assertFalse(place.resolved)
        geo.answer = { PlaceLookup(listOf(nearIntel)) }
        assertTrue(namer.name(fix(2000f)).resolved)
        assertEquals(2, geo.calls.size)
    }

    @Test fun failuresAreNamedAndNeverThrow() = runTest {
        assertEquals("service unavailable", namer(Geo { throw IOException("Service not Available") }).name(fix(2000f)).reason)
        assertEquals("error", namer(Geo { throw IllegalStateException("binder") }).name(fix(2000f)).reason)
        assertEquals("no geocoder", namer(Geo { PlaceLookup(emptyList(), "no geocoder") }).name(fix(2000f)).reason)
        assertEquals("no address", namer(Geo { PlaceLookup(emptyList()) }).name(fix(2000f)).reason)
        assertEquals("no address", namer(Geo { PlaceLookup(listOf(PlaceAddress())) }).name(fix(2000f)).reason)
    }

    @Test fun theCacheIsPerAreaAndLanguageAndIsClearedWithCollectedData() = runTest {
        var tag = "he"
        val geo = Geo { PlaceLookup(listOf(nearIntel)) }
        val namer = namer(geo) { tag }
        val here = fix(2000f).copy(latitude = 32.085, longitude = 34.885) // away from a cell edge
        namer.name(here)
        namer.name(here.copy(latitude = 32.086, accuracyMeters = 1800f)) // the same city cell
        assertEquals(1, geo.calls.size)
        tag = "iw"; namer.name(here) // iw is he
        assertEquals(1, geo.calls.size)
        tag = "en"; namer.name(here)
        assertEquals(listOf("he", "en"), geo.calls)
        tag = "he"
        namer.name(here.copy(latitude = 32.2)) // another city
        assertEquals(3, geo.calls.size)
        namer.name(here.copy(accuracyMeters = 10f, permission = "fine")) // more detail needs its own lookup
        assertEquals(4, geo.calls.size)
        namer.clear(); namer.name(here)
        assertEquals(5, geo.calls.size)
        clock += PlaceNamer.RESOLVED_TTL_MS; namer.name(here)
        assertEquals(6, geo.calls.size)
    }

    @Test fun noAddressIsCachedForAnHourOnly() = runTest {
        val geo = Geo { PlaceLookup(emptyList()) }
        val namer = namer(geo)
        namer.name(fix(2000f)); namer.name(fix(2000f))
        assertEquals(1, geo.calls.size)
        clock += PlaceNamer.NO_ADDRESS_TTL_MS; namer.name(fix(2000f))
        assertEquals(2, geo.calls.size)
    }

    // The refresh path.

    @Test fun aNamedFreshFixKeepsItsTimesAndIsShownAsACurrentCity() = runTest {
        val read = fresh()
        val result = LocationRefresh.run(store, namer(Geo { PlaceLookup(listOf(nearIntel)) }), { t0 + 5_000 }) { read }
        assertTrue(result.place!!.resolved)
        assertEquals(read.observedAt, result.observedAt)
        assertEquals(read.expiresAt, result.expiresAt)
        assertEquals(read.elapsedRealtimeMs, result.elapsedRealtimeMs)
        val stored = store.newestFix()!!
        assertEquals(t0, stored.fixAt)
        assertEquals(e0, stored.elapsedRealtimeMs)
        val current = snap(30_000).currentLocation!!
        assertEquals(PlaceDetail.CURRENT, current.state)
        assertEquals(city, current.place)
        assertEquals(t0, current.fixAt)
        assertTrue(snap(30_000).sourceGaps.any { it.contains("at city level") })
        val need = SituationNeeds.resolve("current_location", "user asked where they are", snap(30_000), t0 + 30_000, true)
        assertEquals("known", need.status)
        assertEquals(city, need.value)
        assertEquals(t0 + LocationFreshness.CURRENT_MS, need.until)
    }

    @Test fun aFailedNameNeverBlocksOrRemovesTheFix() = runTest {
        val result = LocationRefresh.run(store, namer(Geo { awaitCancellation() }), { t0 }) { fresh() }
        assertEquals("fresh", result.status)
        assertEquals("timeout", result.place!!.reason)
        val current = snap(10_000).currentLocation!!
        assertEquals(SituationPlace.UNAVAILABLE, current.placeStatus)
        assertEquals("timeout", current.placeReason)
        assertFalse("current_location" in snap(10_000).unknown)
        val need = SituationNeeds.resolve("current_location", "user asked where they are", snap(10_000), t0 + 10_000, true)
        assertEquals("known", need.status)
        assertEquals("place name unavailable", need.value)
        assertTrue(need.next.contains("Do not guess"))
        // A later success names the same fix; a later failure never takes a known name away.
        store.attachPlace(boot, e0, resolved())
        store.attachPlace(boot, e0, SituationPlace.unavailable("service unavailable", t0))
        assertEquals(city, snap(20_000).currentLocation!!.place)
    }

    @Test fun namingAStaleFixNeverMakesItCurrent() = runTest {
        val cache = fix(2000f, at = t0 - 9 * minute, elapsed = e0 - 9 * minute)
        val result = LocationRefresh.run(store, namer(Geo { PlaceLookup(listOf(nearIntel)) }), { t0 }) { failed(t0, cache) }
        assertFalse(result.fresh(t0))
        val read = PlaceDetail.read(result, t0)
        assertFalse(read.current)
        assertNull(read.location)
        assertEquals(PlaceDetail.LAST_KNOWN, read.lastKnown!!.state)
        assertEquals(city, read.lastKnown!!.place)
        assertEquals(t0 - 9 * minute, read.lastKnown!!.fixAt)
        val situation = snap(0)
        assertNull(situation.currentLocation)
        assertTrue("current_location" in situation.unknown)
        assertEquals(t0 - 9 * minute, situation.lastKnownLocation!!.fixAt)
        assertEquals(9, situation.lastKnownLocation!!.ageMinutes)
        assertTrue(situation.lastKnownLocation!!.note.contains("not the current location"))
        assertNotEquals("known", SituationNeeds.resolve("current_location", "where am I", situation, t0, true).status)
    }

    @Test fun aCurrentFixNamedEarlierAgesIntoLastKnownWithItsName() = runTest {
        LocationRefresh.run(store, namer(Geo { PlaceLookup(listOf(nearIntel)) }), { t0 }) { fresh() }
        val later = snap(5 * minute)
        assertNull(later.currentLocation)
        assertEquals(city, later.lastKnownLocation!!.place)
        assertEquals(t0, later.lastKnownLocation!!.fixAt)
        // An already named fix is not looked up again.
        val geo = Geo { PlaceLookup(listOf(nearIntel)) }
        LocationRefresh.run(store, namer(geo), { t0 + 5 * minute }) { failed(t0 + 5 * minute, null) }
        assertTrue(geo.calls.isEmpty())
    }

    @Test fun forgettingWhileNamingAttachesNothing() = runTest {
        val geo = Geo { store.forgetObserved(); PlaceLookup(listOf(nearIntel)) }
        LocationRefresh.run(store, namer(geo), { t0 }) { fresh() }
        assertNull(store.newestFix())
        assertNull(snap(10_000).lastKnownLocation)
        assertFalse(file.readText().contains("הרי יהודה"))
    }

    @Test fun theNameSurvivesAReloadAndAnOlderFileStillLoads() = runTest {
        LocationRefresh.run(store, namer(Geo { PlaceLookup(listOf(nearIntel)) }), { t0 }) { fresh() }
        val reloaded = SharedSituationStore(file).snapshot(t0 + 10_000, zone, emptySet(), calendarProfile = profile,
            locationAllowed = true, locationBootCount = boot, elapsedNowMs = e0 + 10_000)
        assertEquals(city, reloaded.currentLocation!!.place)

        // A file written by 1213: a stored fix with no permission and no place.
        file.writeText("""{"version":1,"revision":2,"lastFix":{"latitude":32.08,"longitude":34.78,"accuracyMeters":1500.0,""" +
            """"provider":"network","fixAt":$t0,"elapsedRealtimeMs":$e0,"bootCount":$boot,"profile":"$profile"}}""")
        val old = SharedSituationStore(file).snapshot(t0 + 4 * minute, zone, emptySet(), calendarProfile = profile,
            locationAllowed = true, locationBootCount = boot, elapsedNowMs = e0 + 4 * minute)
        val last = old.lastKnownLocation!!
        assertEquals("not resolved", last.placeReason)
        assertEquals(t0, last.fixAt)
        assertNull(old.currentLocation)
    }

    // No coordinates leave through any situation output.

    @Test fun noSituationOutputCarriesCoordinates() = runTest {
        val preferences = File(dir, "preferences.json").apply { writeText("""{"addresses":{"work":"Intel Petah Tikva"}}""") }
        var next: SituationLocation = fresh()
        var later = 10_000L
        val geo = Geo { PlaceLookup(listOf(nearIntel)) }
        fun live() = snap(later)
        val backing = InMemoryTaskBoardStore()
        val board = TaskBoard(backing, now = { clock }); val memory = MemoryBook(backing, now = { clock }); val roster = TeamRoster(backing)
        board.load(); memory.load(); roster.load()
        val tools = TaskTools(board, memory, roster, { TaskChatRole.MANAGER }, { "unused" }, now = { clock }, zone = { zone },
            situation = ::live,
            correctSituation = { field, value, until -> store.correct(field, value, clock, until); live() },
            refreshLocation = { LocationRefresh.run(store, namer(geo), { clock }) { next } },
            recordSituationAsk = { field, purpose -> store.recordAsk(field, purpose, clock); live() },
            declineSituation = { field, purpose -> store.decline(field, purpose, clock); live() },
            savePlace = { key, value -> SavedPlaces.write(preferences, key, value); store.answered("saved_place_$key", clock); live() })
        val outputs = mutableListOf<String>()
        suspend fun call(vararg pairs: Pair<String, String>) {
            val result = tools.invoke("manager", buildJsonObject { put("mode", "situation"); pairs.forEach { (k, v) -> put(k, v) } }, true)
            assertTrue(result.text, result.success)
            outputs += result.text
        }
        call("operation" to "refresh_location")
        assertTrue(outputs.last(), outputs.last().contains(city))
        call("operation" to "read")
        call("operation" to "need", "field" to "current_location", "purpose" to "user asked where they are")
        outputs += Json.encodeToString(SharedSituation.serializer(), live()) // what the injection sends
        clock = t0 + 9 * minute; later = 9 * minute
        next = failed(clock, fix(2000f)) // the platform cache still holds the first fix
        call("operation" to "refresh_location")
        assertTrue(outputs.last(), outputs.last().contains("last_known") && outputs.last().contains(city))
        call("operation" to "need", "field" to "current_location", "purpose" to "user asked where they are")
        call("operation" to "correct", "field" to "travel_mode", "value" to "car")
        call("operation" to "decline", "field" to "eta", "purpose" to "user asked when they arrive")
        call("operation" to "save_place", "field" to "home", "value" to "Hertzl 1, Haifa")
        outputs += Json.encodeToString(SharedSituation.serializer(), live())

        val forbidden = setOf("latitude", "longitude", "elapsedRealtimeMs", "lastFix", "thoroughfare", "subThoroughfare", "address")
        for (text in outputs) {
            fun walk(element: JsonElement) {
                when (element) {
                    is JsonObject -> element.forEach { (key, value) -> assertFalse("$key leaked in $text", key in forbidden); walk(value) }
                    is JsonArray -> element.forEach(::walk)
                    else -> Unit
                }
            }
            walk(Json.parseToJsonElement(text))
            assertFalse(text, text.contains("32.08") || text.contains("34.78") || text.contains("הרי יהודה"))
        }
    }
}
