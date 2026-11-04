package dev.androidagent.devicetools

import dev.androidagent.core.SituationLocation

/** Wall-clock time alone cannot establish a cached fix's age. */
internal object SituationLocationAdapter {
    const val MAX_AGE_MS = 120_000L
    data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?,
        val elapsedMs: Long, val provider: String?)

    fun read(fixes: List<Fix>, now: Long, elapsedMs: Long, profile: String?): SituationLocation {
        if (profile == null) return SituationLocation("profile unavailable", now, now)
        val usable = fixes.filter { fix ->
            fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
                fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
                fix.accuracyMeters?.let { it.isFinite() && it >= 0 } == true &&
                fix.elapsedMs > 0 && elapsedMs - fix.elapsedMs in 0 until MAX_AGE_MS
        }.maxWithOrNull(compareBy<Fix> { it.elapsedMs }.thenBy { -it.accuracyMeters!! })
            ?: return SituationLocation("no fresh fix", now, now)
        val observedAt = now - (elapsedMs - usable.elapsedMs)
        return SituationLocation("fresh", observedAt, observedAt + MAX_AGE_MS,
            usable.latitude, usable.longitude, usable.accuracyMeters, usable.provider?.take(80), profile, usable.elapsedMs)
    }
}
