package dev.androidagent.core

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** A fix older than current is kept apart as the last known location; it never becomes a fresh one. */
class LastKnownLocationTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val profile = "current_android_user:0"
    private val boot = 104
    private val t0 = 1_794_492_000_000L
    private val e0 = 50_000_000L
    private val dir: File = Files.createTempDirectory("last-known-test").toFile()
    private val file = File(dir, "situation.json")
    private val store = SharedSituationStore(file)
    private val minute = 60_000L

    @After fun cleanUp() { dir.deleteRecursively() }

    private fun fresh(at: Long = t0, elapsed: Long = e0, lat: Double = 32.08) = SituationLocation("fresh", at, at + LocationFreshness.CURRENT_MS,
        lat, 34.78, 1500f, "network", profile, elapsed, boot, attemptedAt = at, permission = "coarse", tried = listOf("network:fix"), source = "live")

    private fun failed(status: String, at: Long, lastKnown: SituationFix? = null) =
        SituationLocation(status, at, at, bootCount = boot, attemptedAt = at, permission = "coarse", tried = listOf("cache:none", "network:timeout"), lastKnown = lastKnown)

    /** [later] ms after the first fix, on the same boot unless told otherwise. */
    private fun snap(later: Long, allowed: Boolean = true, bootNow: Int? = boot, profileNow: String? = profile) =
        store.snapshot(t0 + later, zone, emptySet(), calendarProfile = profileNow, locationAllowed = allowed,
            locationBootCount = bootNow, elapsedNowMs = e0 + later)

    @Test fun aFixOlderThanTwoMinutesIsOnlyTheLastKnownLocation() {
        store.recordLocation(fresh(), t0)
        val current = snap(minute)
        assertNotNull(current.currentLocation)
        assertNull(current.lastKnownLocation)
        assertFalse("current_location" in current.unknown)

        val later = snap(3 * minute)
        assertNull(later.currentLocation)
        val last = later.lastKnownLocation!!
        assertEquals(3, last.ageMinutes)
        assertEquals(t0, last.fixAt) // its own time, never renewed
        assertEquals(1500, last.accuracyMeters)
        assertTrue("current_location" in later.unknown)
        assertTrue("eta" in later.unknown && "route" in later.unknown && "travel_mode" in later.unknown)
        assertTrue(later.sourceGaps.any { it.contains("not the current location") })
    }

    @Test fun theInjectedSituationNeverCarriesTheStoredFixOrAStaleLocation() {
        store.recordLocation(fresh(), t0)
        val injected = Json.parseToJsonElement(Json.encodeToString(SharedSituation.serializer(), snap(10 * minute))).jsonObject
        assertFalse("location" in injected)
        assertFalse("lastFix" in injected)
        val last = injected["lastKnownLocation"]!!.jsonObject
        assertEquals(10, last["ageMinutes"]!!.jsonPrimitive.int)
        assertTrue(last["note"]!!.jsonPrimitive.content.contains("not the current location"))
    }

    @Test fun theNeedForCurrentLocationStaysOpenAndLabelsTheLastKnownFix() {
        store.recordLocation(fresh(), t0)
        store.recordLocation(failed("timeout", t0 + 5 * minute), t0 + 5 * minute)
        val situation = snap(8 * minute)
        val need = SituationNeeds.resolve("current_location", "user asked where they are", situation, t0 + 8 * minute, true)
        assertNotEquals("known", need.status)
        assertNull(need.value)
        assertEquals(8, need.lastKnown!!.ageMinutes)
        assertEquals("timeout", need.lastRead!!.status)
        assertTrue(need.next.contains(SituationNeeds.LAST_KNOWN_USE))
        // An ETA is never derived from it.
        val eta = SituationNeeds.resolve("eta", "user asked when they arrive", situation, t0 + 8 * minute, true)
        assertNull(eta.lastKnown)
        assertNotEquals("known", eta.status)
    }

    @Test fun aFailedReadNeverRenewsTheFixAndAnOlderCacheNeverReplacesANewerFix() {
        store.recordLocation(fresh(), t0)
        val olderCache = SituationFix(31.0, 35.0, 3000f, "network", t0 - 20 * minute, e0 - 20 * minute, boot, profile)
        store.recordLocation(failed("timeout", t0 + 6 * minute, olderCache), t0 + 6 * minute)
        val last = snap(7 * minute).lastKnownLocation!!
        assertEquals(t0, last.fixAt)
        assertEquals(1500, last.accuracyMeters) // the newer fix, not the 3000 m cache
        assertEquals(7, last.ageMinutes)
    }

    @Test fun aCachedFixFromAFailedReadIsShownOnlyAsLastKnown() {
        val cache = SituationFix(32.1, 34.8, 2000f, "fused", t0 - 9 * minute, e0 - 9 * minute, boot, profile)
        store.recordLocation(failed("timeout", t0, cache), t0)
        val situation = snap(0)
        assertNull(situation.currentLocation)
        assertEquals(9, situation.lastKnownLocation!!.ageMinutes)
        assertEquals(t0 - 9 * minute, situation.lastKnownLocation!!.fixAt)
    }

    @Test fun anotherBootProfileOrPolicyOrAgeHidesTheLastKnownFix() {
        store.recordLocation(fresh(), t0)
        assertNull(snap(5 * minute, bootNow = boot + 1).lastKnownLocation)
        assertNull(snap(5 * minute, bootNow = null).lastKnownLocation)
        assertNull(snap(5 * minute, profileNow = "work_profile:11").lastKnownLocation)
        assertNull(snap(5 * minute, allowed = false).lastKnownLocation)
        assertNull(snap(LocationFreshness.LAST_KNOWN_MS).lastKnownLocation)
        assertNotNull(snap(LocationFreshness.LAST_KNOWN_MS - 1).lastKnownLocation)
        // A clock that went backwards proves nothing about age.
        assertNull(store.snapshot(t0, zone, emptySet(), calendarProfile = profile, locationAllowed = true,
            locationBootCount = boot, elapsedNowMs = e0 - 1).lastKnownLocation)
    }

    @Test fun aFixOfAnotherBootIsNotCurrentEvenWhenTheWallClockSaysSo() {
        store.recordLocation(fresh(), t0)
        val rebooted = snap(minute, bootNow = boot + 1)
        assertNull(rebooted.currentLocation)
        assertTrue("current_location" in rebooted.unknown)
    }

    @Test fun forgettingObservedDataDropsTheStoredFix() {
        store.recordLocation(fresh(), t0)
        store.forgetObserved()
        assertNull(snap(3 * minute).lastKnownLocation)
        assertFalse(file.readText().contains("lastFix"))
    }

    @Test fun aSituationFileWrittenBeforeThisChangeStillLoads() {
        file.writeText("""{"version":1,"revision":3,"location":{"status":"no fresh fix","observedAt":$t0,"expiresAt":$t0},""" +
            """"locationAttempts":[{"status":"no fresh fix","at":$t0}]}""")
        val reloaded = SharedSituationStore(file).snapshot(t0 + minute, zone, emptySet(), calendarProfile = profile,
            locationAllowed = true, locationBootCount = boot, elapsedNowMs = e0)
        assertNull(reloaded.currentLocation)
        assertNull(reloaded.lastKnownLocation)
        assertEquals(listOf<String>(), reloaded.locationAttempts.single().tried)
    }

    @Test fun eachFailureGetsItsOwnNextStepAndNoneBlamesTheUser() {
        val now = t0
        val timeout = SituationNeeds.afterLocationRefresh(failed("timeout", now), emptyList(), now)!!
        assertTrue(timeout.contains("device-side failure"))
        assertTrue(timeout.contains("Tried: cache:none, network:timeout"))
        assertTrue(timeout.contains("ask them where they are"))
        assertFalse(timeout.contains(SituationNeeds.LAST_KNOWN_USE))

        val withLast = SituationNeeds.afterLocationRefresh(failed("timeout", now,
            SituationFix(32.1, 34.8, 2000f, "fused", now - 9 * minute, e0 - 9 * minute, boot, profile)), emptyList(), now)!!
        assertTrue(withLast.contains(SituationNeeds.LAST_KNOWN_USE))

        val off = SituationNeeds.afterLocationRefresh(failed("location disabled", now), emptyList(), now)!!
        assertTrue(off.contains("Location services are off"))
        assertFalse(off.contains("request_permissions"))

        val none = SituationNeeds.afterLocationRefresh(failed("provider unavailable", now), emptyList(), now)!!
        assertTrue(none.contains("No location provider"))

        val denied = SituationNeeds.afterLocationRefresh(failed("permission denied", now), emptyList(), now)!!
        assertTrue(denied.contains("request_permissions"))
        assertNull(SituationNeeds.afterLocationRefresh(fresh(now), emptyList(), now + minute))
        // The same fix three minutes later is no longer current.
        assertNotNull(SituationNeeds.afterLocationRefresh(fresh(now), emptyList(), now + 3 * minute))
    }
}
