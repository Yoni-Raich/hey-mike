package dev.androidagent.devicetools

import dev.androidagent.core.LocationFreshness
import dev.androidagent.core.SituationFix
import dev.androidagent.core.SituationLocation
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Wall-clock time alone cannot establish a cached fix's age. */
internal object SituationLocationAdapter {
    const val MAX_AGE_MS = LocationFreshness.CURRENT_MS
    data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?,
        val elapsedMs: Long, val provider: String?)

    fun valid(fix: Fix, elapsedMs: Long): Boolean = fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
        fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
        fix.accuracyMeters?.let { it.isFinite() && it >= 0 } == true &&
        fix.elapsedMs > 0 && elapsedMs - fix.elapsedMs >= 0

    fun newest(fixes: List<Fix>, elapsedMs: Long): Fix? = fixes.filter { valid(it, elapsedMs) }
        .maxWithOrNull(compareBy<Fix> { it.elapsedMs }.thenBy { -it.accuracyMeters!! })

    fun read(fixes: List<Fix>, now: Long, elapsedMs: Long, profile: String?): SituationLocation {
        if (profile == null) return SituationLocation("profile unavailable", now, now)
        val usable = newest(fixes, elapsedMs)?.takeIf { elapsedMs - it.elapsedMs < MAX_AGE_MS }
            ?: return SituationLocation("no fresh fix", now, now)
        val observedAt = now - (elapsedMs - usable.elapsedMs)
        return SituationLocation("fresh", observedAt, observedAt + MAX_AGE_MS,
            usable.latitude, usable.longitude, usable.accuracyMeters, usable.provider?.take(80), profile, usable.elapsedMs)
    }
}

/**
 * One bounded, interactive location read: no subscription, no background work, no new permission.
 *
 * Order: a cached fix younger than [LocationFreshness.CURRENT_MS]; then one current-location request per
 * provider, fused first where Android has it, then network, then GPS only with the fine permission. The
 * whole read shares [budgetMs]; each provider gets an equal share of what is left, so a provider that
 * fails fast leaves its time to the next. Every request is cancelled when its share ends or the caller is
 * cancelled. A failure never becomes a fresh fix: an older cached fix is returned only as [SituationLocation.lastKnown].
 */
internal class SituationLocationReader(private val backend: Backend, private val budgetMs: Long = BUDGET_MS) {
    interface Backend {
        val sdk: Int
        /** "fine", "coarse", or null without a location permission. */
        fun permission(): String?
        fun profile(): String?
        fun bootCount(): Int?
        fun locationEnabled(): Boolean
        fun enabledProviders(): List<String>
        fun lastKnown(provider: String): SituationLocationAdapter.Fix?
        /** Starts one current-location request. [onResult] gets the fix, or null when the provider has none. Returns a cancel action. */
        fun current(provider: String, onResult: (SituationLocationAdapter.Fix?) -> Unit): () -> Unit
        fun now(): Long
        fun elapsed(): Long
    }

    private class Attempt(val fix: SituationLocationAdapter.Fix?)

    suspend fun read(): SituationLocation {
        val attemptedAt = backend.now()
        val tried = mutableListOf<String>()
        var level: String? = null
        var boot: Int? = null
        var lastKnown: SituationFix? = null
        fun fail(status: String) = SituationLocation(status, attemptedAt, attemptedAt, bootCount = boot, attemptedAt = attemptedAt,
            permission = level, tried = tried.toList(), lastKnown = lastKnown)
        try {
            level = backend.permission() ?: return fail("permission denied")
            val profile = backend.profile() ?: return fail("profile unavailable")
            boot = backend.bootCount() ?: return fail("device clock unavailable")
            if (!backend.locationEnabled()) return fail("location disabled")
            val enabled = backend.enabledProviders()
            val providers = buildList {
                if (backend.sdk >= 31 && FUSED in enabled) add(FUSED)
                if (NETWORK in enabled) add(NETWORK)
                if (level == FINE && GPS in enabled) add(GPS)
            }
            fun fresh(fix: SituationLocationAdapter.Fix, source: String, elapsed: Long): SituationLocation {
                val observedAt = backend.now() - (elapsed - fix.elapsedMs)
                return SituationLocation("fresh", observedAt, observedAt + LocationFreshness.CURRENT_MS, fix.latitude, fix.longitude,
                    fix.accuracyMeters, fix.provider?.take(80), profile, fix.elapsedMs, boot, attemptedAt, level, tried.toList(), source)
            }

            val cacheSources = providers + (if (level == FINE && PASSIVE in enabled) listOf(PASSIVE) else emptyList())
            val cached = cacheSources.mapNotNull { provider ->
                try { backend.lastKnown(provider) } catch (e: SecurityException) { throw e } catch (_: Exception) { null }
            }
            val now = backend.elapsed()
            val newest = SituationLocationAdapter.newest(cached, now)
            if (newest != null && now - newest.elapsedMs < LocationFreshness.CURRENT_MS) {
                tried += "cache:fresh"
                return fresh(newest, "cache", now)
            }
            tried += if (newest == null) "cache:none" else "cache:${(now - newest.elapsedMs) / 60_000}m old"
            lastKnown = newest?.takeIf { now - it.elapsedMs < LocationFreshness.LAST_KNOWN_MS }?.let {
                SituationFix(it.latitude, it.longitude, it.accuracyMeters!!, it.provider?.take(80),
                    backend.now() - (now - it.elapsedMs), it.elapsedMs, boot!!, profile)
            }
            if (providers.isEmpty()) return fail("provider unavailable")

            val deadline = backend.elapsed() + budgetMs
            val outcomes = mutableListOf<String>()
            for ((index, provider) in providers.withIndex()) {
                val left = deadline - backend.elapsed()
                if (left <= 0) { tried += "$provider:skipped"; outcomes += "timeout"; continue }
                val share = left / (providers.size - index)
                val outcome = try {
                    withTimeoutOrNull(share) {
                        suspendCancellableCoroutine<Attempt> { continuation ->
                            val cancel = backend.current(provider) { fix -> if (continuation.isActive) continuation.resume(Attempt(fix)) }
                            continuation.invokeOnCancellation { runCatching { cancel() } }
                        }
                    }?.let { attempt ->
                        val elapsed = backend.elapsed()
                        val fix = attempt.fix?.takeIf { SituationLocationAdapter.valid(it, elapsed) && elapsed - it.elapsedMs < LocationFreshness.CURRENT_MS }
                        if (fix != null) {
                            tried += "$provider:fix"
                            // The permission or the location switch may have changed while waiting.
                            if (backend.permission() == null) return fail("permission denied")
                            if (!backend.locationEnabled()) return fail("location disabled")
                            return fresh(fix, "live", elapsed)
                        }
                        "no fix"
                    } ?: "timeout"
                } catch (e: SecurityException) { throw e }
                  catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: IllegalArgumentException) { "unavailable" }
                  catch (_: Exception) { "error" }
                tried += "$provider:$outcome"
                outcomes += outcome
            }
            if (backend.permission() == null) return fail("permission denied")
            if (!backend.locationEnabled()) return fail("location disabled")
            return fail(when {
                "timeout" in outcomes -> "timeout"
                "no fix" in outcomes -> "no fix"
                outcomes.all { it == "unavailable" } -> "provider unavailable"
                else -> "provider error"
            })
        } catch (_: SecurityException) { return fail("permission denied") }
    }

    companion object {
        /** One read's total wait. Android allows a current-location request 30 s; a tool call may run 60 s. */
        const val BUDGET_MS = 30_000L
        const val FUSED = "fused"
        const val NETWORK = "network"
        const val GPS = "gps"
        const val PASSIVE = "passive"
        const val FINE = "fine"
        const val COARSE = "coarse"
    }
}
