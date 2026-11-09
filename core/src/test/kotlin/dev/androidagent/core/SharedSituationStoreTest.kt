package dev.androidagent.core

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SharedSituationStoreTest {
    @Test fun calendarPermissionFreshnessAndAuditSurviveRestart() {
        val file = Files.createTempDirectory("calendar-state-test").resolve("state.json").toFile()
        val zone = ZoneId.of("Europe/London")
        val start = Instant.parse("2026-03-29T00:00:00Z").toEpochMilli()
        val event = SituationCalendarEvent("1", "Day", start, start + 86_400_000, true, "UTC")
        val calendar = SituationCalendar("complete", start, start + 1000, start, start + 172_800_000, 50, "current_android_user:0", listOf(event))
        val store = SharedSituationStore(file)
        store.refresh(emptyList(), start, zone, true, true, calendar = calendar) { true }
        store.recordDecision(AttentionDecision("silence", "no due followup", emptyList(), start, start + 500))
        store.recordDecision(AttentionDecision("silence", "no due followup", emptyList(), start + 1, start + 500))
        val reopened = SharedSituationStore(file)
        assertEquals(event, reopened.snapshot(start + 1, zone, emptySet()).calendar!!.events.single())
        assertEquals(1, reopened.snapshot(start + 1, zone, emptySet()).attention.size)
        assertNull(reopened.snapshot(start + 1, zone, emptySet(), calendarAllowed = false).calendar)
        assertNull(reopened.snapshot(start + 1, zone, emptySet(), calendarProfile = "current_android_user:1").calendar)
        assertNull(reopened.snapshot(start + 1000, zone, emptySet()).calendar)
        store.refresh(emptyList(), start + 2, zone, true, true, calendar = calendar.copy(profile = null)) { true }
        assertNull(store.snapshot(start + 3, zone, emptySet()).calendar)
        store.refresh(emptyList(), start + 3, zone, false, true, calendar = calendar) { true }
        assertNull(store.snapshot(start + 4, zone, emptySet()).calendar)
        store.refresh(emptyList(), start + 4, zone, true, true, calendar = calendar.copy(events = emptyList())) { true }
        val empty = store.snapshot(start + 5, zone, emptySet())
        assertTrue(empty.calendar!!.events.isEmpty())
        assertFalse("calendar_plan" in empty.unknown)
        store.refresh(emptyList(), start + 5, zone, true, true, calendar = calendar.copy(status = "truncated")) { true }
        assertTrue("calendar_plan" in store.snapshot(start + 6, zone, emptySet()).unknown)
        store.refresh(emptyList(), start + 6, zone, true, false, calendar = calendar) { true }
        assertNull(store.snapshot(start + 7, zone, emptySet()).calendar)
    }
    @Test fun savedPlacesRefreshWithoutClaimingCurrentLocation() {
        val dir = Files.createTempDirectory("places-test")
        val preferences = dir.resolve("preferences.json").toFile()
        val state = dir.resolve("state.json").toFile()
        preferences.writeText("""{"addresses":{"home":"Home address","work":"Work address"}}""")
        val store = SharedSituationStore(state)
        store.refresh(emptyList(), 100, ZoneId.of("UTC"), false, false, SavedPlaces.read(preferences)) { true }
        val snapshot = store.snapshot(101, ZoneId.of("UTC"), emptySet())
        assertEquals("Home address", snapshot.claims.first { it.field == "saved_place_home" }.value)
        assertTrue("current_location" in snapshot.unknown)
        preferences.writeText("{broken")
        store.refresh(emptyList(), 200, ZoneId.of("UTC"), false, false, SavedPlaces.read(preferences)) { true }
        val failed = SharedSituationStore(state).snapshot(201, ZoneId.of("UTC"), emptySet())
        assertTrue(failed.claims.none { it.source == "preferences.json" })
        assertTrue("saved_place_home" in failed.unknown)
        assertTrue(failed.sourceGaps.any { it.contains("parsed") })
        preferences.writeText("x".repeat(65_537))
        assertTrue(SavedPlaces.read(preferences).reason!!.contains("large"))
    }
    @Test fun persistsCorrectionsAndRecomputesFreshnessAndZoneAfterRestart() {
        val file = Files.createTempDirectory("situation-test").resolve("state.json").toFile()
        val now = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli()
        val zone = ZoneId.of("Europe/London")
        val store = SharedSituationStore(file)
        store.refresh(emptyList(), now, zone, true, true) { true }
        store.correct("travel_mode", "walking", now, now + 60_000)
        val reopened = SharedSituationStore(file)
        val fresh = reopened.snapshot(now + 1, zone, emptySet())
        assertEquals("walking", fresh.claims.single().value)
        assertFalse("travel_mode" in fresh.unknown)
        val stale = reopened.snapshot(now + 60_000, ZoneId.of("Asia/Jerusalem"), emptySet())
        assertEquals("stale", stale.claims.single().confidence)
        assertTrue("travel_mode" in stale.unknown)
        assertEquals("Asia/Jerusalem", stale.timezone)
        assertTrue(stale.sourceGaps.any { it.contains("not loaded") })
        assertTrue(runCatching { reopened.correct("travel_mode", "car", now, null) }.isFailure)
    }

    @Test fun filtersExcludedAndClosedCardsAtWriteAndPolicyChangesAtRead() {
        val file = Files.createTempDirectory("situation-test").resolve("state.json").toFile()
        val task = MikeTask("visible", "Appointment", "why", "plan", originKind = "chat", topicKey = "a",
            createdAt = 1, updatedAt = 1, status = TaskStatus.MINE,
            commitment = TaskCommitment("user", 100))
        val store = SharedSituationStore(file)
        val written = store.refresh(listOf(task, task.copy(id = "excluded"), task.copy(id = "closed", status = TaskStatus.DONE)),
            10, ZoneId.of("UTC"), false, false) { it.id != "excluded" }
        assertEquals(listOf("visible"), written.commitments.map { it.taskId })
        assertTrue(written.sourceGaps.any { it.contains("disabled") })
        assertTrue(SharedSituationStore(file).snapshot(11, ZoneId.of("UTC"), emptySet()).commitments.isEmpty())
    }

    @Test fun conflictingOrFutureDatedClaimsAreNotPresentedAsKnown() {
        val file = Files.createTempDirectory("situation-test").resolve("state.json").toFile()
        val claims = listOf(SituationClaim("travel_mode", "walk", "one", 10, 100, "reported"),
            SituationClaim("travel_mode", "car", "two", 11, 100, "reported"),
            SituationClaim("current_location", "office", "user", 30, 100, "user_supplied"))
        file.writeText(Json.encodeToString(SharedSituation.serializer(), SharedSituation(claims = claims,
            unknown = listOf("travel_mode", "current_location"))))
        val snapshot = SharedSituationStore(file).snapshot(20, ZoneId.of("UTC"), emptySet())
        assertEquals(listOf("travel_mode"), snapshot.conflicts)
        assertTrue("travel_mode" in snapshot.unknown)
        assertTrue("current_location" in snapshot.unknown)
        assertEquals("stale", snapshot.claims.first { it.field == "current_location" }.confidence)
    }
}
