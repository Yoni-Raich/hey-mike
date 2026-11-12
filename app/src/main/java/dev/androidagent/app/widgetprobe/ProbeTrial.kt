package dev.androidagent.app.widgetprobe

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class ProbePhase { FOREGROUND, BACKGROUND, SCREEN_OFF }

/** Elapsed time bounds all collection, including callbacks delivered after deep sleep. */
internal class ProbeTrial(val startedElapsed: Long, val durationMs: Long = TEN_MINUTES) {
    init { require(durationMs in 1..TEN_MINUTES) }
    val deadlineElapsed = startedElapsed + durationMs
    private var stopped = false
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val seen = mutableSetOf<String>()

    fun accepting(now: Long) = !stopped && now < deadlineElapsed && now >= startedElapsed
    fun stop() { stopped = true; seen.clear(); key.fill(0) }
    fun rowId(parts: List<String>): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        // Length prefixes avoid ambiguous field boundaries.
        return mac.doFinal(parts.joinToString("") { "${it.length}:$it" }.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }
    fun firstSeen(id: String): Boolean = seen.size < 5000 && seen.add(id)

    companion object {
        const val TEN_MINUTES = 600_000L
        fun validMarker(value: String) = Regex("WAEXP_[A-Z0-9]{1,16}").matches(value)
        fun phase(screenOn: Boolean, appVisible: Boolean) = when {
            !screenOn -> ProbePhase.SCREEN_OFF
            appVisible -> ProbePhase.FOREGROUND
            else -> ProbePhase.BACKGROUND
        }
        fun configurationAccepted(stage: String, ok: Boolean, sameProvider: Boolean, returnedIdMatches: Boolean) =
            stage == "CONFIGURING" && ok && sameProvider && returnedIdMatches
    }
}
