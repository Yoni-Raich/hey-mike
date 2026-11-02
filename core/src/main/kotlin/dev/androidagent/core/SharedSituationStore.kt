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

@Serializable
data class SituationCalendarEvent(val id: String, val title: String, val startMs: Long, val endMs: Long,
    val allDay: Boolean, val timezone: String?)

@Serializable
data class SituationCalendar(val status: String, val observedAt: Long, val expiresAt: Long,
    val windowStart: Long, val windowEnd: Long, val limit: Int, val profile: String? = null,
    val events: List<SituationCalendarEvent> = emptyList())

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
    val commitments: List<SituationCommitment> = emptyList(),
    val unknown: List<String> = listOf("availability", "current_location", "travel_mode", "route", "eta", "calendar_plan", "departure_or_return", "errands_on_route"),
    val sourceGaps: List<String> = listOf("situation providers have not been refreshed"),
    val conflicts: List<String> = emptyList(),
    val calendar: SituationCalendar? = null,
    val attention: List<AttentionDecision> = emptyList(),
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
            SituationClaim("saved_place_$name", value, "preferences.json", now, now + 120_000, "user_saved")
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
            }, "location, travel and ETA providers unavailable") + listOfNotNull(places.reason))
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
        return save(state.copy(revision = state.revision + 1, claims = (state.claims.filterNot { it.field == field && it.source == "user" } + claim).takeLast(40)))
    }

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
        calendarProfile: String? = null): SharedSituation {
        val live = state.claims.filter { it.observedAt <= now && (it.expiresAt == null || it.expiresAt > now) }
        val stale = state.claims.filterNot { it in live }
        val conflicts = live.groupBy { it.field }.filterValues { claims -> claims.map { it.value }.distinct().size > 1 }.keys.sorted()
        val calendar = state.calendar?.takeIf { calendarAllowed && (calendarProfile == null || it.profile == calendarProfile) &&
            it.observedAt <= now && it.expiresAt > now }
        return state.copy(asOf = now, timezone = zone.id, localTime = Instant.ofEpochMilli(now).atZone(zone).toString(), calendar = calendar,
            attention = state.attention.filter { it.sourceIds.all { id -> id in allowedTaskIds } },
            sourceGaps = if (calendar == null) state.sourceGaps.filterNot { it == "calendar plan loaded" || it == "calendar plan truncated" } +
                "calendar unavailable, expired or not allowed" else state.sourceGaps,
            claims = live + stale.map { it.copy(confidence = "stale") },
            commitments = state.commitments.filter { it.taskId in allowedTaskIds && (it.expiresAt == null || it.expiresAt > now) },
            unknown = state.unknown.filterNot { field -> (field == "calendar_plan" && calendar?.status == "complete") ||
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
