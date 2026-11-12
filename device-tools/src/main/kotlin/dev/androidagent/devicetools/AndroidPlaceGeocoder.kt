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

package dev.androidagent.devicetools

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dev.androidagent.core.PlaceAddress
import dev.androidagent.core.PlaceLookup
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reverse geocoding through the platform [Geocoder] (on most phones backed by Google Play services; it
 * sends the coordinates to that backend and needs a network). One lookup per call, no tracking, no
 * permission. The caller bounds the time ([dev.androidagent.core.PlaceNamer]); an abandoned lookup is ignored.
 */
class AndroidPlaceGeocoder(private val context: Context) {
    suspend fun lookup(latitude: Double, longitude: Double, languageTag: String): PlaceLookup {
        if (!Geocoder.isPresent()) return PlaceLookup(emptyList(), "no geocoder")
        val geocoder = Geocoder(context, Locale.forLanguageTag(languageTag))
        return if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(latitude, longitude, MAX_RESULTS, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(PlaceLookup(addresses.map(::part)))
                }
                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resume(PlaceLookup(emptyList(), "service unavailable"))
                }
            })
        } else blocking(geocoder, latitude, longitude)
    }

    /** Android 11-12 only has the blocking call: run it on its own thread so a timeout can walk away from it. */
    private suspend fun blocking(geocoder: Geocoder, latitude: Double, longitude: Double): PlaceLookup =
        suspendCancellableCoroutine { continuation ->
            Thread({
                val result = try {
                    @Suppress("DEPRECATION")
                    PlaceLookup(geocoder.getFromLocation(latitude, longitude, MAX_RESULTS).orEmpty().map(::part))
                } catch (_: IOException) { PlaceLookup(emptyList(), "service unavailable") }
                  catch (_: Exception) { PlaceLookup(emptyList(), "error") }
                if (continuation.isActive) continuation.resume(result)
            }, "place-geocoder").apply { isDaemon = true }.start()
        }

    /** Address parts only. featureName and address lines are left out: they can name a building the fix does not support. */
    private fun part(address: Address) = PlaceAddress(address.locality, address.subLocality, address.thoroughfare,
        address.subThoroughfare, address.subAdminArea, address.adminArea, address.countryName, address.countryCode)

    companion object {
        private const val MAX_RESULTS = 1

        /** The phone's language tags, in its order of preference. */
        fun languageTags(context: Context): List<String> = context.resources.configuration.locales.let { list ->
            (0 until list.size()).map { list[it].toLanguageTag() }
        }
    }
}
