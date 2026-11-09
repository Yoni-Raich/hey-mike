package dev.androidagent.devicetools

import org.junit.Assert.*
import org.junit.Test

class SituationLocationAdapterTest {
    private val fix = SituationLocationAdapter.Fix(32.0, 35.0, 1200f, 100_000, "network")
    @Test fun coarseFixHasItsRealAgeAccuracyAndProfile() {
        val result = SituationLocationAdapter.read(listOf(fix), 1_000_000, 130_000, "current_android_user:0")
        assertTrue(result.fresh(1_000_000)); assertEquals(970_000L, result.observedAt)
        assertEquals(1_090_000L, result.expiresAt); assertEquals(1200f, result.accuracyMeters)
        assertFalse(result.fresh(1_090_000)); assertFalse(result.fresh(969_999))
    }
    @Test fun refusesOldFutureOrInvalidFixesWithoutReturningCoordinates() {
        listOf(fix.copy(elapsedMs = 1), fix.copy(elapsedMs = 300_000), fix.copy(accuracyMeters = null),
            fix.copy(latitude = Double.NaN), fix.copy(longitude = 190.0), fix.copy(accuracyMeters = -1f)).forEach {
            val result = SituationLocationAdapter.read(listOf(it), 1_000_000, 230_000, "current_android_user:0")
            assertNull(result.latitude); assertEquals("no fresh fix", result.status)
        }
    }
    @Test fun profileMustBeVerifiedAndNewestValidFixWins() {
        assertNull(SituationLocationAdapter.read(listOf(fix), 1_000_000, 130_000, null).latitude)
        val result = SituationLocationAdapter.read(listOf(fix, fix.copy(elapsedMs = 120_000, provider = "gps")),
            1_000_000, 130_000, "current_android_user:0")
        assertEquals("gps", result.provider)
    }
}
