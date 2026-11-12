package dev.androidagent.core

import kotlinx.serialization.Serializable

/**
 * What to do when the current request needs a situation field the app does not know.
 *
 * The order is fixed: use what is known, try an allowed source once, then ask the user one
 * focused question and save the answer where it belongs. A source failure leads to a question,
 * never to a dead end. Background turns never ask. A field the request does not need is not
 * resolved at all, so unknowns alone never cause questions or alerts.
 */
object SituationNeeds {
    const val DAY_MS = 86_400_000L
    const val ASK_HISTORY_MS = 30 * DAY_MS
    /** An unanswered question is not repeated for the same purpose inside this window. */
    const val ASKED_WINDOW_MS = 10 * 60_000L
    /** A source read younger than this is not repeated; its outcome decides the next step. */
    const val ATTEMPT_WINDOW_MS = 2 * 60_000L

    /**
     * [lasting] facts belong in a lasting store; the others are temporary state with an expiry.
     * [defaultTtlMs] applies when the user did not say how long a temporary answer holds.
     * [declineMs] is how long a refusal keeps the agent from asking again.
     */
    data class Field(val lasting: Boolean, val defaultTtlMs: Long?, val declineMs: Long, val saveWith: String)

    val FIELDS: Map<String, Field> = mapOf(
        "current_location" to Field(false, 2 * 3_600_000L, DAY_MS, "situation operation=correct field=current_location"),
        "travel_mode" to Field(false, 6 * 3_600_000L, DAY_MS, "situation operation=correct field=travel_mode"),
        "departure_or_return" to Field(false, 12 * 3_600_000L, DAY_MS, "situation operation=correct field=departure_or_return"),
        "errands_on_route" to Field(false, DAY_MS, DAY_MS, "situation operation=correct field=errands_on_route"),
        "availability" to Field(false, null, DAY_MS, "situation operation=correct field=availability value=available|quiet with until"),
        "saved_place_home" to Field(true, null, ASK_HISTORY_MS, "situation operation=save_place field=saved_place_home"),
        "saved_place_work" to Field(true, null, ASK_HISTORY_MS, "situation operation=save_place field=saved_place_work"),
        "eta" to Field(false, null, DAY_MS, "nothing: use the answer for this request only"),
        "route" to Field(false, null, DAY_MS, "nothing: use the answer for this request only"),
        "calendar_plan" to Field(false, null, DAY_MS, "nothing: the calendar is the source"),
    )

    @Serializable
    data class Resolution(
        val field: String,
        /** known, observed, try_source, ask, asked_recently, declined or unresolved. */
        val status: String,
        val next: String,
        val value: String? = null,
        val source: String? = null,
        val until: Long? = null,
        val saveWith: String? = null,
        val note: String? = null,
    ) {
        /** Only an "ask" on an interactive turn is recorded, so the next turn does not repeat it. */
        val asks: Boolean get() = status == "ask"
    }

    /** Default expiry for a temporary answer the user gave without a duration; null when one is required. */
    fun defaultUntil(field: String, now: Long): Long? = FIELDS[field]?.defaultTtlMs?.let { now + it }

    fun resolve(field: String, purpose: String, situation: SharedSituation, now: Long, interactive: Boolean): Resolution {
        val spec = FIELDS[field] ?: error("Unsupported situation field. Supported: ${FIELDS.keys.joinToString()}.")
        val why = purpose.trim().take(160)
        val placesNote = situation.claims.filter { it.source == SavedPlaces.SOURCE }.takeIf { field == "current_location" && it.isNotEmpty() }
            ?.let { "Saved ${it.joinToString { c -> c.field.removePrefix("saved_place_") }} address is not where the user is now." }

        known(field, situation, now)?.let { return it.copy(note = placesNote ?: it.note) }
        situation.observations.lastOrNull { it.field == field && it.confidence != "stale" && it.expiresAt > now }?.let {
            return Resolution(field, "observed", "Use it as an attributed conclusion (${it.confidence}), not a verified fact. " +
                "If the decision depends on it, confirm it with the user in one short question.", it.value, "observation", it.expiresAt, spec.saveWith)
        }
        val ask = situation.asks.lastOrNull { it.field == field }
        if (ask?.status == "declined" && now - ask.at in 0 until spec.declineMs) return Resolution(field, "declined",
            "The user declined to give this at ${ask.at}. Do not ask again; continue without it and say what that limits.", note = placesNote)
        if (!interactive) return Resolution(field, "unresolved",
            "Background turn: leave it unknown. Do not ask, alert or notify the user about missing information.", note = placesNote)

        if (field == "current_location") locationSource(situation, now)?.let { return it.copy(note = placesNote) }
        if (field == "calendar_plan") {
            val recent = situation.calendar
            if (recent == null || recent.status != "complete") return Resolution(field, "try_source",
                "Read the calendar with the calendar tool (operation list) for the window this request needs. " +
                    "If it is unavailable, say so; ask the user only for the one event this request depends on.")
        }
        if (ask?.status == "asked" && now - ask.at in 0 until ASKED_WINDOW_MS && ask.purpose.equals(why.ifEmpty { "unspecified" }, ignoreCase = true))
            return Resolution(field, "asked_recently", "You asked at ${ask.at}. If the user's latest message answers it, save it with " +
                "${spec.saveWith} now. Otherwise say it is still unknown; do not ask again yet.", saveWith = spec.saveWith, note = placesNote)
        return Resolution(field, "ask", "Ask the user one short, focused question for $field (ask_user where available, otherwise in your reply), " +
            "say why this request needs it, and wait. Save the answer at once with ${spec.saveWith}" +
            (if (spec.lasting) "; it is lasting, so save it only when the user states it as their $field." else
                if (spec.defaultTtlMs != null) "; it is temporary and expires by default unless the user says how long." else "."),
            saveWith = spec.saveWith, until = defaultUntil(field, now), note = placesNote)
    }

    private fun known(field: String, situation: SharedSituation, now: Long): Resolution? {
        if (field in situation.conflicts) return null
        if (field == "current_location") situation.location?.takeIf { it.fresh(now) }?.let {
            return Resolution(field, "known", "Use the device fix.", "${it.latitude},${it.longitude} ±${it.accuracyMeters?.toInt()}m",
                "device_location", it.expiresAt)
        }
        if (field == "calendar_plan" && situation.calendar?.status == "complete")
            return Resolution(field, "known", "Use the loaded calendar plan.", source = "calendar", until = situation.calendar.expiresAt)
        val claim = situation.claims.lastOrNull { it.field == field && it.confidence != "stale" && it.observedAt <= now &&
            (it.expiresAt == null || it.expiresAt > now || it.source == SavedPlaces.SOURCE) } ?: return null
        return Resolution(field, "known", if (claim.source == SavedPlaces.SOURCE) "Use the saved place." else "Use the user's statement until it expires.",
            claim.value, claim.source, claim.expiresAt.takeIf { claim.source != SavedPlaces.SOURCE })
    }

    /** The next step after the device location source, based on this phone's recent read outcomes. */
    private fun locationSource(situation: SharedSituation, now: Long): Resolution? {
        val recent = situation.locationAttempts.filter { now - it.at in 0 until DAY_MS }
        val last = recent.lastOrNull()
        val denials = recent.count { it.status == "permission denied" }
        if (denials >= 2) return null // permission was already requested and refused; asking is next
        if (last == null || now - last.at >= ATTEMPT_WINDOW_MS) return Resolution("current_location", "try_source",
            "Read one device fix with situation operation=refresh_location, then follow its next step.")
        if (last.status == "permission denied") return Resolution("current_location", "try_source",
            "Location permission is missing. Request it once with apps_settings request_permissions " +
                "android.permission.ACCESS_COARSE_LOCATION, then retry refresh_location. If it is not granted, ask the user where they are.")
        return null // the read just failed (no fresh fix, location off, ...): ask
    }

    /** The step that follows one refresh_location read, so a failed source never ends the turn at "unknown". */
    fun afterLocationRefresh(location: SituationLocation, earlier: List<LocationAttempt>, now: Long): String? {
        if (location.fresh(now)) return null
        val ask = "If the user's request needs their location, ask them where they are in one short question and save the answer with " +
            "situation operation=correct field=current_location (it expires by default). Never use a saved home or work address instead."
        val deniedBefore = earlier.any { it.status == "permission denied" && now - it.at in 0 until DAY_MS && it.at < location.observedAt }
        return when {
            location.status == "permission denied" && !deniedBefore -> "Location permission is missing. Request it once with apps_settings " +
                "request_permissions android.permission.ACCESS_COARSE_LOCATION, then retry refresh_location once. If it is not granted: $ask"
            location.status == "permission denied" -> "The permission was already requested and is still missing; do not request it again. $ask"
            else -> "The device has no usable fix (${location.status}); do not retry now. $ask"
        }
    }

    /** Policy text for an interactive chat that receives the situation; one place, shared by every caller. */
    fun guidance(): String =
        "Missing information: work from what the user's current request needs, not from every unknown field. " +
            "When the request or a decision depends on a field that is unknown, stale or in conflict, call tasks mode=situation operation=need " +
            "field=<field> purpose=<short reason> and follow its next step: use the known value, try the named source once, or ask the user one " +
            "short, focused question (ask_user where available, otherwise in your reply). A failed source is not the end: the next step is to ask. " +
            "Save the answer at once: temporary state with operation=correct (default expiry if the user gave none), home or work with " +
            "operation=save_place, other lasting facts with remember and their source. If the user will not say, record operation=decline. " +
            "Never ask again what is answered or declined, never turn a saved address into the current location, and never infer a lasting habit from one answer."
}
