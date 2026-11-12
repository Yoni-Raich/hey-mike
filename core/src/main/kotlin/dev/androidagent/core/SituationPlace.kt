package dev.androidagent.core

import java.io.IOException
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/**
 * Address parts the platform geocoder returned for one fix. Stored with the fix, never shown as such:
 * [PlaceDetail] decides how much of it the fix's accuracy supports. There is deliberately no feature or
 * point-of-interest name: a coarse fix near a company building must not read as "at that company".
 */
@Serializable
data class PlaceAddress(val locality: String? = null, val subLocality: String? = null, val thoroughfare: String? = null,
    val subThoroughfare: String? = null, val subAdminArea: String? = null, val adminArea: String? = null,
    val countryName: String? = null, val countryCode: String? = null) {
    fun bounded() = PlaceAddress(locality.clip(), subLocality.clip(), thoroughfare.clip(), subThoroughfare.clip(),
        subAdminArea.clip(), adminArea.clip(), countryName.clip(), countryCode.clip())
    private fun String?.clip() = this?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
}

/**
 * The outcome of naming one fix: [RESOLVED] with [address], or [UNAVAILABLE] with [reason]
 * (no geocoder, service unavailable, timeout, no address, error, not resolved). [resolvedAt] is when the
 * name was looked up; it never changes the fix's own time.
 */
@Serializable
data class SituationPlace(val status: String, val reason: String? = null, val address: PlaceAddress? = null,
    val locale: String? = null, val resolvedAt: Long, val source: String = SOURCE) {
    val resolved: Boolean get() = status == RESOLVED && address != null
    companion object {
        const val SOURCE = "android_geocoder"
        const val RESOLVED = "resolved"
        const val UNAVAILABLE = "unavailable"
        fun unavailable(reason: String, at: Long, locale: String? = null) = SituationPlace(UNAVAILABLE, reason, null, locale, at)
    }
}

/** What the model sees for a fix: a written place at the precision its accuracy supports, never coordinates. */
@Serializable
data class LocationView(
    /** current or last_known. */
    val state: String,
    /** The written place, or null when [placeStatus] is unavailable. */
    val place: String? = null,
    /** address, street, neighborhood, city or region: how specific [place] is. */
    val precision: String? = null,
    val placeStatus: String,
    val placeReason: String? = null,
    val accuracyMeters: Int,
    /** The fix's own time. Naming the place later never changes it. */
    val fixAt: Long,
    val ageMinutes: Long,
    val provider: String? = null,
    val placeSource: String? = null,
    val note: String,
)

/** The result of one refresh_location read as the model sees it. */
@Serializable
data class LocationReadView(val status: String, val current: Boolean, val attemptedAt: Long? = null,
    val permission: String? = null, val tried: List<String> = emptyList(), val source: String? = null,
    val location: LocationView? = null, val lastKnown: LocationView? = null)

/** How much of an address a fix supports, and how it is written. */
object PlaceDetail {
    const val ADDRESS = "address"
    const val STREET = "street"
    const val NEIGHBORHOOD = "neighborhood"
    const val CITY = "city"
    const val REGION = "region"
    private val ORDER = listOf(REGION, CITY, NEIGHBORHOOD, STREET, ADDRESS)

    /**
     * Accuracy limits: a house number needs about 25 m, a street about 100 m, a neighborhood about 750 m.
     * Without the fine permission the detail stops at the city: Android coarsens such fixes (to about 2 km),
     * so a street the geocoder finds at that point says nothing about where the user is.
     */
    fun precisionFor(accuracyMeters: Float, permission: String?): String {
        val byAccuracy = when {
            accuracyMeters <= 25f -> ADDRESS
            accuracyMeters <= 100f -> STREET
            accuracyMeters <= 750f -> NEIGHBORHOOD
            accuracyMeters <= 15_000f -> CITY
            else -> REGION
        }
        return if (permission != "fine" && ORDER.indexOf(byAccuracy) > ORDER.indexOf(CITY)) CITY else byAccuracy
    }

    /** The written place and the precision actually reached; falls back to a coarser level when a part is missing. */
    fun label(address: PlaceAddress, wanted: String): Pair<String, String>? {
        val city = address.locality
        fun withCity(part: String) = if (city != null && city != part) "$part, $city" else part
        for (level in ORDER.subList(0, ORDER.indexOf(wanted) + 1).reversed()) {
            val text = when (level) {
                ADDRESS -> address.thoroughfare?.let { street -> address.subThoroughfare?.let { withCity("$street $it") } }
                STREET -> address.thoroughfare?.let(::withCity)
                NEIGHBORHOOD -> address.subLocality?.let(::withCity)
                CITY -> city
                else -> address.subAdminArea ?: address.adminArea ?: address.countryName
            }
            if (text != null) return text to level
        }
        return null
    }

    /** Hebrew when any of the phone's languages is Hebrew, else the phone's first language. */
    fun locale(tags: List<String>): String {
        val normal = tags.map { normalize(it) }.filter { it.isNotBlank() }
        return normal.firstOrNull { it == "he" || it.startsWith("he-") } ?: normal.firstOrNull() ?: "en"
    }

    /** Java and older Android write Hebrew as "iw". */
    fun normalize(tag: String): String = tag.trim().replace('_', '-').let { if (it == "iw" || it.startsWith("iw-")) "he" + it.drop(2) else it }

    fun view(fix: SituationFix, state: String, ageMs: Long): LocationView {
        val accuracy = fix.accuracyMeters.toInt()
        val age = maxOf(0L, ageMs) / 60_000
        val place = fix.place
        val written = place?.takeIf { it.resolved }?.let { label(it.address!!, precisionFor(fix.accuracyMeters, fix.permission)) }
        val lead = if (state == CURRENT) "Current device location" else "Last known location, $age min old, not the current location"
        val never = if (state == CURRENT) "" else " Never use it for travel, route, ETA or departure."
        if (written == null) {
            val reason = place?.reason ?: if (place?.resolved == true) "no address" else "not resolved"
            return LocationView(state, null, null, SituationPlace.UNAVAILABLE, reason, accuracy, fix.fixAt, age, fix.provider,
                place?.source, "$lead: the phone has a fix (about ±$accuracy m) but the place name is unavailable ($reason). " +
                    "Do not guess a place; if the request needs it, ask the user.$never")
        }
        val (text, precision) = written
        val scope = when (precision) {
            ADDRESS -> "at or next to this address"
            STREET -> "on or near this street; the building is not known"
            NEIGHBORHOOD -> "in or near this neighborhood; the street is not known"
            CITY -> "in or near this city; the street and building are not known"
            else -> "somewhere in this region"
        }
        return LocationView(state, text, precision, SituationPlace.RESOLVED, null, accuracy, fix.fixAt, age, fix.provider, place!!.source,
            "$lead: $scope (about ±$accuracy m). A saved home or work address is not where the user is unless they say so.$never")
    }

    /** The refresh_location result without coordinates. */
    fun read(location: SituationLocation, now: Long): LocationReadView {
        val current = location.fresh(now)
        return LocationReadView(location.status, current, location.attemptedAt, location.permission, location.tried, location.source,
            location.asFix()?.takeIf { current }?.let { view(it, CURRENT, now - it.fixAt) },
            location.lastKnown?.takeIf { it.valid() }?.let { view(it, LAST_KNOWN, now - it.fixAt) })
    }

    const val CURRENT = "current"
    const val LAST_KNOWN = "last_known"
}

/** One platform lookup. [error] is set when the service failed; an empty [addresses] alone means no address. */
data class PlaceLookup(val addresses: List<PlaceAddress>, val error: String? = null)

/**
 * Names fixes through the platform geocoder: bounded by [timeoutMs], never throws, and cached per locale and
 * area so the same place is not looked up again. Timeouts and service errors are not cached; "no address" is,
 * for an hour. The cache is location history: [clear] it whenever collected data is forgotten.
 */
class PlaceNamer(
    private val lookup: suspend (latitude: Double, longitude: Double, locale: String) -> PlaceLookup,
    private val locale: () -> String,
    private val now: () -> Long,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private data class Entry(val place: SituationPlace, val until: Long)
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }

    @Synchronized fun clear() = cache.clear()

    suspend fun name(fix: SituationFix): SituationPlace {
        val tag = runCatching { PlaceDetail.normalize(locale()) }.getOrDefault("en")
        val key = key(fix, tag)
        synchronized(this) { cache[key]?.takeIf { it.until > now() }?.let { return it.place } }
        val result = try {
            withTimeoutOrNull(timeoutMs) { lookup(fix.latitude, fix.longitude, tag) }
        } catch (e: CancellationException) { throw e }
          catch (_: IOException) { PlaceLookup(emptyList(), "service unavailable") }
          catch (_: Exception) { PlaceLookup(emptyList(), "error") }
        val at = now()
        val place = when {
            result == null -> SituationPlace.unavailable("timeout", at, tag)
            result.error != null -> SituationPlace.unavailable(result.error.take(60), at, tag)
            else -> result.addresses.firstOrNull()?.bounded()?.takeIf { it != PlaceAddress() }
                ?.let { SituationPlace(SituationPlace.RESOLVED, null, it, tag, at) }
                ?: SituationPlace.unavailable("no address", at, tag)
        }
        val keep = when {
            place.resolved -> RESOLVED_TTL_MS
            place.reason == "no address" -> NO_ADDRESS_TTL_MS
            else -> 0L
        }
        if (keep > 0) synchronized(this) { cache[key] = Entry(place, at + keep) }
        return place
    }

    /** Cells a little smaller than the detail shown, so a cached name never claims more than the new fix supports. */
    private fun key(fix: SituationFix, tag: String): String {
        val precision = PlaceDetail.precisionFor(fix.accuracyMeters, fix.permission)
        val cell = when (precision) {
            PlaceDetail.ADDRESS -> 0.0002
            PlaceDetail.STREET -> 0.0005
            PlaceDetail.NEIGHBORHOOD -> 0.003
            PlaceDetail.CITY -> 0.01
            else -> 0.05
        }
        return "$tag|$precision|${floor(fix.latitude / cell).toLong()}|${floor(fix.longitude / cell).toLong()}"
    }

    companion object {
        /** Geocoding normally answers in about a second; the read's 30 s plus this stays under the tool's 60 s. */
        const val TIMEOUT_MS = 6_000L
        const val RESOLVED_TTL_MS = 24 * 3_600_000L
        const val NO_ADDRESS_TTL_MS = 3_600_000L
        const val MAX_ENTRIES = 32
    }
}

/**
 * One interactive refresh: read, record the fix at once, then name the newest fix in a bounded step and
 * attach the name to that same fix. A naming failure never blocks or removes the fix.
 */
object LocationRefresh {
    suspend fun run(store: SharedSituationStore, namer: PlaceNamer?, now: () -> Long, read: suspend () -> SituationLocation): SituationLocation {
        val location = read()
        store.recordLocation(location, now())
        val target = store.newestFix()?.takeIf { it.place?.resolved != true && now() - it.fixAt in 0 until LocationFreshness.LAST_KNOWN_MS }
            ?: return withPlace(location, store.newestFix())
        namer ?: return location
        val place = namer.name(target)
        store.attachPlace(target.bootCount, target.elapsedRealtimeMs, place)
        return withPlace(location, store.newestFix())
    }

    /** Copies the stored name onto the read's own fix, when it is the same fix. Times stay as they are. */
    private fun withPlace(location: SituationLocation, named: SituationFix?): SituationLocation {
        val place = named?.place ?: return location
        fun same(boot: Int?, elapsed: Long?) = boot == named.bootCount && elapsed == named.elapsedRealtimeMs
        return when {
            location.status == "fresh" && same(location.bootCount, location.elapsedRealtimeMs) -> location.copy(place = place)
            location.lastKnown != null && same(location.lastKnown.bootCount, location.lastKnown.elapsedRealtimeMs) ->
                location.copy(lastKnown = location.lastKnown.copy(place = place))
            else -> location
        }
    }
}
