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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A named circle a `place` trigger or an `at_place` condition refers to. */
data class AutomationPlace(val name: String, val latitude: Double, val longitude: Double, val radiusMeters: Int) {
    init {
        require(name.isNotBlank()) { "A place needs a name." }
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Latitude or longitude is out of range." }
        require(radiusMeters in MIN_RADIUS..MAX_RADIUS) { "radiusMeters must be $MIN_RADIUS to $MAX_RADIUS." }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name); put("latitude", latitude); put("longitude", longitude); put("radiusMeters", radiusMeters)
    }

    companion object {
        const val MIN_RADIUS = 50
        const val MAX_RADIUS = 50_000
        const val DEFAULT_RADIUS = 150

        fun fromJson(json: JsonObject): AutomationPlace = AutomationPlace(
            name = json["name"]?.jsonPrimitive?.content?.trim().orEmpty(),
            latitude = json["latitude"]?.jsonPrimitive?.doubleOrNull ?: throw IllegalArgumentException("A place needs latitude."),
            longitude = json["longitude"]?.jsonPrimitive?.doubleOrNull ?: throw IllegalArgumentException("A place needs longitude."),
            radiusMeters = json["radiusMeters"]?.jsonPrimitive?.intOrNull ?: DEFAULT_RADIUS,
        )
    }
}

/** The saved places, one small JSON file. Names match without case. */
class AutomationPlaces(private val file: File) {
    @Synchronized
    fun all(): List<AutomationPlace> = runCatching {
        if (!file.isFile) return emptyList()
        Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { runCatching { AutomationPlace.fromJson(it.jsonObject) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(place: AutomationPlace) {
        val rest = all().filterNot { it.name.equals(place.name, ignoreCase = true) }
        check(rest.size < MAX_PLACES) { "There are already $MAX_PLACES places. Delete one first." }
        write(rest + place)
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val all = all()
        val rest = all.filterNot { it.name.equals(name, ignoreCase = true) }
        if (rest.size == all.size) return false
        write(rest)
        return true
    }

    private fun write(places: List<AutomationPlace>) = AtomicFiles.writeText(file, JsonArray(places.map { it.toJson() }).toString())

    companion object {
        const val MAX_PLACES = 50
        fun fileIn(home: File) = File(home, "automation-places.json")
    }
}

/**
 * Turns location fixes into place enter and leave events.
 *
 * The first fix only sets where the phone is: starting the app at home must
 * not "enter" home. A coarse fix (accuracy worse than the place itself) is
 * not evidence either way. Leaving needs a margin past the edge, so a fix
 * that wobbles on the boundary does not fire enter, leave, enter.
 */
class PlaceTracker {
    private var inside: Set<String>? = null

    /** Names the phone is in now, or empty before the first usable fix. */
    @get:Synchronized
    val current: Set<String> get() = inside.orEmpty()

    @Synchronized
    fun onFix(places: List<AutomationPlace>, latitude: Double, longitude: Double, accuracyMeters: Float): List<Pair<String, AutomationTrigger.PlaceTransition>> {
        val before = inside
        val now = mutableSetOf<String>()
        for (place in places) {
            val distance = distanceMeters(latitude, longitude, place.latitude, place.longitude)
            val coarse = accuracyMeters > max(place.radiusMeters.toFloat(), MIN_USEFUL_ACCURACY)
            val wasInside = before?.any { it.equals(place.name, ignoreCase = true) } == true
            val isInside = when {
                coarse -> wasInside
                wasInside -> distance <= place.radiusMeters + max(LEAVE_MARGIN, accuracyMeters.toDouble())
                else -> distance <= place.radiusMeters
            }
            if (isInside) now += place.name
        }
        inside = now
        if (before == null) return emptyList()
        val entered = now.filter { name -> before.none { it.equals(name, ignoreCase = true) } }
        val left = before.filter { name -> now.none { it.equals(name, ignoreCase = true) } && places.any { it.name.equals(name, ignoreCase = true) } }
        return entered.map { it to AutomationTrigger.PlaceTransition.ENTER } + left.map { it to AutomationTrigger.PlaceTransition.EXIT }
    }

    companion object {
        const val LEAVE_MARGIN = 30.0
        const val MIN_USEFUL_ACCURACY = 100f

        /** Great-circle distance on a spherical Earth: plenty for a circle of 50 m or more. */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
