/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A named spot on the map, as a rule means it.
 *
 * A rule says `"place": "home"` and nothing in the format says where home is —
 * a place is a name until something maps it to a circle. This is that circle,
 * and it is stored beside the rules rather than inside one so that "home" means
 * the same thing to every rule that mentions it.
 *
 * The user's own words are kept separately from the id. `id` is what a rule
 * matches on and has to stay stable; `label` is what a person is shown, and
 * renaming it must not silently break every rule naming the old one.
 */
data class AutomationPlace(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_M,
) {

    /**
     * Metres from this place's centre. Haversine rather than a flat
     * approximation: the error is negligible at these distances either way,
     * but the flat version is wrong near the poles in a way nobody would ever
     * find, and this is not the place to save four multiplications.
     */
    fun distanceMeters(lat: Double, lng: Double): Double {
        val dLat = Math.toRadians(lat - latitude)
        val dLng = Math.toRadians(lng - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("label", label)
        put("latitude", latitude)
        put("longitude", longitude)
        put("radiusMeters", radiusMeters)
    }

    companion object {
        const val DEFAULT_RADIUS_M = 150
        /**
         * Below this, a phone that is standing still reports itself in and out
         * of the circle as the fix wanders, and the rule fires all night. A
         * network fix is routinely 50-100m off; 80 is the smallest radius that
         * is not simply a lie about what this can detect.
         */
        const val MIN_RADIUS_M = 80
        const val MAX_RADIUS_M = 5_000
        private const val EARTH_RADIUS_M = 6_371_000.0

        val ID_RE = Regex("[a-z0-9][a-z0-9_-]{0,47}")

        fun normalizeId(raw: String): String =
            raw.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(48)

        /** Parse one stored place, refusing anything that is not a real point on earth. */
        fun parse(json: JsonObject): AutomationPlace {
            fun bad(reason: String): Nothing = throw AutomationFormatException("place_invalid", reason)
            val id = json.str("id")?.let(::normalizeId).orEmpty()
            if (!ID_RE.matches(id)) bad("A place needs an \"id\" of lowercase letters, digits, \"-\" or \"_\".")
            val lat = json.dbl("latitude") ?: bad("\"$id\" needs a \"latitude\".")
            val lng = json.dbl("longitude") ?: bad("\"$id\" needs a \"longitude\".")
            if (lat !in -90.0..90.0) bad("\"latitude\" must be between -90 and 90.")
            if (lng !in -180.0..180.0) bad("\"longitude\" must be between -180 and 180.")
            val radius = (json.dbl("radiusMeters")?.toInt() ?: DEFAULT_RADIUS_M)
            if (radius < MIN_RADIUS_M || radius > MAX_RADIUS_M) {
                bad(
                    "\"radiusMeters\" must be between $MIN_RADIUS_M and $MAX_RADIUS_M. " +
                        "Below $MIN_RADIUS_M a still phone wanders in and out of the circle all night.",
                )
            }
            return AutomationPlace(
                id = id,
                label = json.str("label")?.takeIf { it.isNotBlank() } ?: id,
                latitude = lat,
                longitude = lng,
                radiusMeters = radius,
            )
        }
    }
}

/**
 * Which places a phone is inside, and what changed since last time.
 *
 * Pure, so the whole of the enter/exit decision is unit-tested and the Android
 * side only has to deliver fixes.
 *
 * **Leaving needs more than not-arriving.** The exit radius is larger than the
 * entry radius, so a phone sitting on a table at the edge of the circle does
 * not report itself arriving and leaving all night as the fix wanders. Without
 * that hysteresis a `place` rule is worse than no rule: it fires repeatedly
 * when nothing happened.
 *
 * **A fix too rough to decide with decides nothing.** A fix whose accuracy is
 * wider than the place itself cannot tell inside from outside, so it leaves the
 * previous answer alone rather than guessing. Reporting an arrival on a
 * 2km-accurate fix into a 150m circle would be a coin toss with the user's
 * lights.
 */
object AutomationPlaceWatch {

    /** How much further than the radius the phone must be before it counts as gone. */
    const val EXIT_MARGIN_M = 60.0

    data class Change(
        val inside: Set<String>,
        val entered: List<AutomationPlace>,
        val left: List<AutomationPlace>,
    )

    /**
     * [previous] is what the phone was last known to be inside. A place that
     * no longer exists simply drops out: the rules naming it are dormant
     * anyway, and reporting an exit nobody caused would fire them.
     */
    fun update(
        previous: Set<String>,
        places: List<AutomationPlace>,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
    ): Change {
        val known = places.map { it.id }.toSet()
        val carried = previous.filter { it in known }.toSet()
        val inside = mutableSetOf<String>()
        val entered = mutableListOf<AutomationPlace>()
        val left = mutableListOf<AutomationPlace>()
        for (place in places) {
            val was = place.id in carried
            val distance = place.distanceMeters(latitude, longitude)
            val tooRough = accuracyMeters != null && accuracyMeters > place.radiusMeters
            val now = when {
                tooRough -> was
                was -> distance <= place.radiusMeters + EXIT_MARGIN_M
                else -> distance <= place.radiusMeters
            }
            if (now) inside += place.id
            if (now && !was) entered += place
            if (!now && was) left += place
        }
        return Change(inside, entered, left)
    }
}

/**
 * Where places live: one JSON file, beside the rules.
 *
 * One file rather than one per place, unlike [AutomationLibrary]: a place is
 * four numbers and a name, there are a handful of them, and every read wants
 * all of them at once to decide which circles a fix is inside.
 */
class AutomationPlaceStore(private val file: File) {

    fun all(): List<AutomationPlace> = read().values.sortedBy { it.id }

    fun find(name: String): AutomationPlace? {
        val wanted = AutomationPlace.normalizeId(name)
        if (wanted.isEmpty()) return null
        val places = read()
        return places[wanted] ?: places.values.firstOrNull { it.label.equals(name.trim(), ignoreCase = true) }
    }

    /** Which of these places the phone is inside, by id, for an `at_place` test. */
    fun inside(ids: Set<String>): Set<String> = read().keys.intersect(ids)

    fun save(place: AutomationPlace): AutomationPlace {
        val places = read().toMutableMap()
        require(places.size < MAX_PLACES || places.containsKey(place.id)) {
            "This phone already holds $MAX_PLACES places; forget one before naming another."
        }
        places[place.id] = place
        write(places)
        return place
    }

    fun delete(id: String): Boolean {
        val places = read().toMutableMap()
        val removed = places.remove(AutomationPlace.normalizeId(id)) != null
        if (removed) write(places)
        return removed
    }

    private fun read(): Map<String, AutomationPlace> {
        if (!file.isFile) return emptyMap()
        val parsed = runCatching {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return emptyMap()
        return parsed.values.mapNotNull { entry ->
            runCatching { AutomationPlace.parse((entry as JsonObject)) }.getOrNull()
        }.associateBy { it.id }
    }

    private fun write(places: Map<String, AutomationPlace>) {
        file.parentFile?.mkdirs()
        val body = buildJsonObject { places.forEach { (id, place) -> put(id, place.toJson()) } }.toString()
        val temp = File(file.parentFile, "." + file.name + ".tmp")
        temp.writeText(body, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(body, Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        const val MAX_PLACES = 32

        fun fileIn(home: File): File = File(File(home, "automations"), "places.json")
    }
}
