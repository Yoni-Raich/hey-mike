package dev.androidagent.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads only the app-managed preferences file; never treats an address as a location fix. */
object SavedPlaces {
    data class Result(val places: Map<String, String> = emptyMap(), val reason: String? = null)

    fun read(file: File): Result = try {
        if (!file.isFile) Result(reason = "preferences file unavailable") else {
            val bytes = file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 65_536) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 65_536) Result(reason = "preferences file too large") else {
                val addresses = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["addresses"]?.jsonObject
                val places = listOf("home", "work").mapNotNull { key ->
                    val primitive = addresses?.get(key)?.jsonPrimitive
                    val value = primitive?.takeIf { it.isString }?.contentOrNull?.trim()
                    value?.takeIf { it.isNotEmpty() && it.length <= 300 }?.let { key to it }
                }.toMap()
                Result(places, if ("home" !in places) "saved home unavailable" else null)
            }
        }
    } catch (_: Exception) { Result(reason = "preferences could not be read or parsed") }

    const val SOURCE = "preferences.json"
    val KEYS = setOf("home", "work")

    /**
     * Saves one named place from the user's own words. Every other key in the file is kept, the write is
     * atomic, and a file that cannot be parsed is left untouched rather than replaced.
     */
    fun write(file: File, key: String, value: String) {
        require(key in KEYS) { "A saved place is home or work." }
        val place = value.trim()
        require(place.isNotEmpty() && place.length <= 300) { "Give a short place, up to 300 characters." }
        val root = if (file.isFile && file.length() > 0) {
            require(file.length() <= 65_536) { "The preferences file is too large to update." }
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? kotlinx.serialization.json.JsonObject
                ?: error("The preferences file is not a JSON object; it was left unchanged.")
        } else kotlinx.serialization.json.JsonObject(emptyMap())
        val addresses = (root["addresses"] as? kotlinx.serialization.json.JsonObject).orEmpty() + (key to kotlinx.serialization.json.JsonPrimitive(place))
        val text = Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(root + ("addresses" to kotlinx.serialization.json.JsonObject(addresses))))
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_536) { "The preferences file would become too large." }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { out -> out.write(bytes); out.fd.sync() }
        AtomicFiles.replace(temp, file)
    }
}

/** Local, versioned claims. A claim is evidence, never permission to act. */
@Serializable
data class SituationClaim(
    val field: String,
    val value: String,
    val source: String,
    val observedAt: Long,
    val expiresAt: Long?,
    val confidence: String,
)

/** A model's attributed conclusion is kept separate from a verified current fact. */
@Serializable
data class SituationObservation(val field: String, val value: String, val observedAt: Long, val expiresAt: Long,
    val confidence: String, val evidence: List<TaskEvidence>, val sourceRefs: List<TaskSourceRef>)

@Serializable
data class SituationLocation(val status: String, val observedAt: Long, val expiresAt: Long,
    val latitude: Double? = null, val longitude: Double? = null, val accuracyMeters: Float? = null,
    val provider: String? = null, val profile: String? = null,
    val elapsedRealtimeMs: Long? = null, val bootCount: Int? = null) {
    fun fresh(now: Long): Boolean = status == "fresh" && observedAt <= now && expiresAt > now &&
        expiresAt - observedAt in 1..120_000 && latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
        accuracyMeters?.let { it.isFinite() && it >= 0 } == true && profile != null
}

@Serializable
data class SituationCalendarEvent(val id: String, val title: String, val startMs: Long, val endMs: Long,
    val allDay: Boolean, val timezone: String?)

@Serializable
data class SituationCalendar(val status: String, val observedAt: Long, val expiresAt: Long,
    val windowStart: Long, val windowEnd: Long, val limit: Int, val profile: String? = null,
    val events: List<SituationCalendarEvent> = emptyList())

/** One missing-information question the agent put to the user, or the user's refusal to answer it. */
@Serializable
data class SituationAsk(val field: String, val purpose: String, val at: Long, val status: String)

/** Outcome of one interactive location read, without coordinates. Used to stop repeated permission prompts. */
@Serializable
data class LocationAttempt(val status: String, val at: Long)

@Serializable
data class AttentionDecision(val action: String, val reason: String, val sourceIds: List<String>, val asOf: Long, val nextCheck: Long? = null)

@Serializable
data class SharedSituation(
    val version: Int = 1,
    val revision: Long = 0,
    val asOf: Long = 0,
    /** Last provider/board refresh, distinct from the time of a snapshot read. */
    val lastRefreshAt: Long = 0,
    val timezone: String = "UTC",
    val localTime: String = "",
    val claims: List<SituationClaim> = emptyList(),
    val observations: List<SituationObservation> = emptyList(),
    val location: SituationLocation? = null,
    val commitments: List<SituationCommitment> = emptyList(),
    val unknown: List<String> = listOf("availability", "current_location", "travel_mode", "route", "eta", "calendar_plan", "departure_or_return", "errands_on_route"),
    val sourceGaps: List<String> = listOf("situation providers have not been refreshed"),
    val conflicts: List<String> = emptyList(),
    val calendar: SituationCalendar? = null,
    val attention: List<AttentionDecision> = emptyList(),
    val asks: List<SituationAsk> = emptyList(),
    val locationAttempts: List<LocationAttempt> = emptyList(),
)

/** One owner serializes all writes. A failed write leaves the prior snapshot intact. */
class SharedSituationStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: SharedSituation = if (file.exists()) {
        json.decodeFromString(SharedSituation.serializer(), file.readText())
            .also { require(it.version == 1) { "Unsupported situation version" } }
    } else SharedSituation()

    @Synchronized
    fun refresh(tasks: List<MikeTask>, now: Long, zone: ZoneId, calendarEnabled: Boolean,
        calendarGranted: Boolean, places: SavedPlaces.Result = SavedPlaces.Result(reason = "preferences not loaded"),
        calendar: SituationCalendar? = null,
        allowed: (MikeTask) -> Boolean): SharedSituation {
        val projected = TaskSituation.snapshot(tasks, now, zone, calendarEnabled, allowed)
        val kept = state.claims.filter { it.source == "user" }.takeLast(40) + places.places.map { (name, value) ->
            SituationClaim("saved_place_$name", value, SavedPlaces.SOURCE, now, now + 120_000, "user_saved")
        }
        val next = state.copy(revision = state.revision + 1, asOf = now, lastRefreshAt = now, timezone = zone.id,
            localTime = Instant.ofEpochMilli(now).atZone(zone).toString(), claims = kept,
            commitments = projected.commitments,
            calendar = calendar?.takeIf { calendarEnabled && calendarGranted && it.profile != null && it.status in setOf("complete", "truncated") },
            unknown = listOf("saved_place_home", "saved_place_work", "availability", "current_location", "travel_mode", "route", "eta", "calendar_plan", "departure_or_return", "errands_on_route"),
            sourceGaps = listOf(when {
                !calendarEnabled -> "calendar source disabled"
                !calendarGranted -> "calendar permission unavailable"
                calendar?.profile != null && calendar.status == "complete" -> "calendar plan loaded"
                calendar?.profile != null && calendar.status == "truncated" -> "calendar plan truncated"
                else -> "calendar plan unavailable: ${calendar?.status ?: "not loaded"}"
            }, "travel and ETA providers unavailable") + listOfNotNull(places.reason))
        return save(next)
    }

    /** Only explicit user corrections enter here; temporary facts require expiry. */
    @Synchronized
    fun correct(field: String, value: String, now: Long, expiresAt: Long?): SharedSituation {
        require(field in USER_FIELDS) { "Unsupported situation field." }
        require(value.isNotBlank() && value.length <= 300) { "Give a short value." }
        require(field != "availability" || value.trim() in setOf("available", "quiet")) { "Availability must be available or quiet." }
        require(expiresAt != null && expiresAt > now && expiresAt <= now + 7 * 86_400_000L) { "Give an expiry within seven days." }
        val claim = SituationClaim(field, value.trim(), "user", now, expiresAt, "user_supplied")
        return save(state.copy(revision = state.revision + 1, claims = (state.claims.filterNot { it.field == field && it.source == "user" } + claim).takeLast(40),
            asks = answeredAsks(field, now)))
    }

    /** Records that the agent asked the user for [field]; an answer or refusal replaces it. */
    @Synchronized
    fun recordAsk(field: String, purpose: String, now: Long): SharedSituation = ask(field, purpose, now, "asked")

    /** The user's own refusal to give [field]. It keeps the agent from asking again for a while. */
    @Synchronized
    fun decline(field: String, purpose: String, now: Long): SharedSituation = ask(field, purpose, now, "declined")

    /** A lasting answer saved elsewhere (for example a saved place) closes the open question. */
    @Synchronized
    fun answered(field: String, now: Long): SharedSituation = save(state.copy(revision = state.revision + 1, asks = answeredAsks(field, now)))

    private fun ask(field: String, purpose: String, now: Long, status: String): SharedSituation {
        require(field in SituationNeeds.FIELDS) { "Unsupported situation field." }
        val why = purpose.trim().take(160).ifEmpty { "unspecified" }
        val kept = state.asks.filterNot { it.field == field }
        return save(state.copy(revision = state.revision + 1, asks = (kept + SituationAsk(field, why, now, status)).takeLast(40)))
    }

    private fun answeredAsks(field: String, now: Long): List<SituationAsk> =
        state.asks.map { if (it.field == field && it.status != "answered") it.copy(status = "answered", at = now) else it }

    @Synchronized
    fun observe(observation: SituationObservation, now: Long): SharedSituation {
        require(observation.field in USER_FIELDS) { "Unsupported situation field." }
        require(observation.value.isNotBlank() && observation.value.length <= 300) { "Give a short value." }
        require(observation.confidence in setOf("reported", "inferred")) { "Confidence must be reported or inferred." }
        require(observation.observedAt in (now - 7 * 86_400_000L)..now && observation.expiresAt > now &&
            observation.expiresAt <= observation.observedAt + 7 * 86_400_000L) { "Give the source time and an expiry within seven days of it." }
        require(observation.evidence.isNotEmpty() && observation.evidence.size <= 10 &&
            observation.evidence.all { it.detail.isNotBlank() && it.detail.length <= 1200 }) { "Give bounded source evidence." }
        require(observation.sourceRefs.isNotEmpty() && observation.sourceRefs.size <= 64) { "Cite the observation's sources." }
        val kept = state.observations.filterNot { it.field == observation.field && it.sourceRefs == observation.sourceRefs }
        return save(state.copy(revision = state.revision + 1, observations = (kept + observation).takeLast(40)))
    }

    @Synchronized
    /** [readAt] is when the read happened; a returned fix carries its own, older time. */
    fun recordLocation(location: SituationLocation, readAt: Long = location.observedAt): SharedSituation =
        save(state.copy(revision = state.revision + 1, location = location,
            locationAttempts = (state.locationAttempts + LocationAttempt(location.status.take(60), maxOf(location.observedAt, readAt))).takeLast(10),
            asks = if (location.status == "fresh") answeredAsks("current_location", location.observedAt) else state.asks))

    /** Collected data about the user goes; the user's own corrections stay. */
    @Synchronized
    fun forgetObserved() { save(state.copy(revision = state.revision + 1, observations = emptyList(), location = null,
        asks = emptyList(), locationAttempts = emptyList())) }

    @Synchronized
    fun recordDecision(decision: AttentionDecision) {
        require(decision.action in setOf("send", "defer", "silence"))
        val safe = decision.copy(reason = decision.reason.take(200), sourceIds = decision.sourceIds.take(10).map { it.take(120) })
        val previous = state.attention.lastOrNull()
        if (previous != null && previous.action == safe.action && previous.reason == safe.reason &&
            previous.sourceIds == safe.sourceIds && previous.nextCheck == safe.nextCheck) return
        save(state.copy(revision = state.revision + 1, attention = (state.attention + safe).takeLast(100)))
    }

    /** Read-time freshness and source filtering also apply after restart or policy changes. */
    @Synchronized
    fun snapshot(now: Long, zone: ZoneId, allowedTaskIds: Set<String>, calendarAllowed: Boolean = true,
        calendarProfile: String? = null, locationAllowed: Boolean = false, locationBootCount: Int? = null, elapsedNowMs: Long? = null,
        allowedSource: (TaskSourceRef) -> Boolean = { true }): SharedSituation {
        // Saved places mirror the lasting preferences file on every refresh; a late tick does not make them unknown.
        val live = state.claims.filter { it.observedAt <= now && (it.expiresAt == null || it.expiresAt > now || it.source == SavedPlaces.SOURCE) }
        val stale = state.claims.filterNot { it in live }
        val conflicts = live.groupBy { it.field }.filterValues { claims -> claims.map { it.value }.distinct().size > 1 }.keys.sorted()
        val calendar = state.calendar?.takeIf { calendarAllowed && (calendarProfile == null || it.profile == calendarProfile) &&
            it.observedAt <= now && it.expiresAt > now }
        val location = state.location?.takeIf { locationAllowed && it.fresh(now) &&
            calendarProfile != null && it.profile == calendarProfile && locationBootCount != null && it.bootCount == locationBootCount &&
            elapsedNowMs != null && it.elapsedRealtimeMs != null && elapsedNowMs - it.elapsedRealtimeMs in 0 until 120_000 }
        return state.copy(asOf = now, timezone = zone.id, localTime = Instant.ofEpochMilli(now).atZone(zone).toString(), calendar = calendar,
            location = location,
            observations = state.observations.filter { it.sourceRefs.isNotEmpty() && it.sourceRefs.all(allowedSource) && it.observedAt <= now }
                .map { if (it.expiresAt <= now) it.copy(confidence = "stale") else it },
            attention = state.attention.filter { it.sourceIds.all { id -> id in allowedTaskIds } },
            sourceGaps = (if (calendar == null) state.sourceGaps.filterNot { it == "calendar plan loaded" || it == "calendar plan truncated" } +
                "calendar unavailable, expired or not allowed" else state.sourceGaps)
                .filterNot { it == "location, travel and ETA providers unavailable" } +
                listOf(if (location == null) "location unknown; an interactive refresh_location can request one fix" else "fresh device location; travel and ETA remain unknown"),
            claims = live + stale.map { it.copy(confidence = "stale") },
            commitments = state.commitments.filter { it.taskId in allowedTaskIds && (it.expiresAt == null || it.expiresAt > now) },
            asks = state.asks.filter { it.at <= now && now - it.at < SituationNeeds.ASK_HISTORY_MS },
            locationAttempts = state.locationAttempts.filter { it.at <= now && now - it.at < SituationNeeds.DAY_MS },
            unknown = state.unknown.filterNot { field -> (field == "current_location" && location != null) ||
                (field == "calendar_plan" && calendar?.status == "complete") ||
                (live.any { it.field == field } && field !in conflicts) }, conflicts = conflicts)
    }

    private fun save(next: SharedSituation): SharedSituation {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { out ->
            out.write(json.encodeToString(SharedSituation.serializer(), next).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = next
        return next
    }

    companion object {
        val USER_FIELDS = setOf("current_location", "travel_mode", "departure_or_return", "errands_on_route", "availability")
    }
}
