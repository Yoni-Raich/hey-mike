package dev.androidagent.devicetools

import dev.androidagent.core.SituationCalendar
import dev.androidagent.core.SituationCalendarEvent

/** The provider boundary is deliberately small so its privacy and cursor behavior can be tested. */
internal interface SituationCalendarSource {
    fun permitted(): Boolean
    /** Null means that the current user is unknown or is not a verified personal user. */
    fun personalProfile(): String?
    fun query(startMs: Long, endMs: Long): SituationCalendarCursor?
}

internal interface SituationCalendarCursor : AutoCloseable {
    fun moveToNext(): Boolean
    fun isNull(column: Int): Boolean
    fun string(column: Int): String?
    fun long(column: Int): Long
    fun int(column: Int): Int
}

internal object SituationCalendarAdapter {
    fun personalProfile(isPersonal: Boolean?, serial: Long): String? =
        if (isPersonal == true && serial >= 0) "current_android_user:$serial" else null

    fun read(source: SituationCalendarSource, startMs: Long, endMs: Long, limit: Int, observedAt: Long): SituationCalendar {
        fun result(status: String, profile: String? = null, events: List<SituationCalendarEvent> = emptyList()) =
            SituationCalendar(status, observedAt, observedAt + 120_000, startMs, endMs, limit, profile, events)
        return try {
            if (!source.permitted()) return result("permission denied")
            if (limit !in 1..100 || endMs <= startMs) return result("invalid query")
            // Never ask the provider for event content until the profile is verified.
            val profile = source.personalProfile() ?: return result("profile unknown or excluded")
            val cursor = source.query(startMs, endMs) ?: return result("provider returned no cursor")
            cursor.use {
                val events = mutableListOf<SituationCalendarEvent>()
                var truncated = false
                while (it.moveToNext()) {
                    if (events.size == limit) { truncated = true; break }
                    if (it.isNull(0) || it.isNull(2) || it.isNull(3) || it.isNull(4)) return result("invalid event metadata")
                    events += SituationCalendarEvent(it.long(0).toString(), (it.string(1) ?: "(no title)").take(160),
                        it.long(2), it.long(3), it.int(4) != 0, it.string(5)?.take(100))
                }
                result(if (truncated) "truncated" else "complete", profile, events)
            }
        } catch (_: SecurityException) { result("permission denied") }
          catch (_: Exception) { result("provider error") }
    }
}
