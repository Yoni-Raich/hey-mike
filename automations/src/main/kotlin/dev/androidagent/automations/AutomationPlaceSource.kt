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

package dev.androidagent.automations

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Location fixes for place triggers, from the framework [LocationManager]
 * (no Play Services: the app ships outside Play).
 *
 * Network fixes every few minutes or 50 m of movement, plus every fix another
 * app asks for through the passive provider, so the battery cost stays low.
 * A place event can therefore land a few minutes late; it is never invented.
 * Needs location "all the time": without it Android stops fixes once Mike is
 * in the background, so [permitted] keeps the trigger dormant instead.
 */
internal class AutomationPlaceSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    fun permitted(): Boolean {
        val foreground = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return foreground && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && manager != null
    }

    @SuppressLint("MissingPermission")
    fun start(onFix: (Location) -> Unit) {
        if (listener != null || manager == null || !permitted()) return
        val callback = LocationListener { onFix(it) }
        listener = callback
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            if (runCatching { manager.allProviders.contains(provider) }.getOrDefault(false)) {
                runCatching { manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_DISTANCE_M, callback, Looper.getMainLooper()) }
            }
        }
        lastKnown()?.let(onFix)
    }

    fun stop() {
        listener?.let { runCatching { manager?.removeUpdates(it) } }
        listener = null
    }

    /** The freshest fix any provider holds, or null. Never starts the GPS. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (manager == null || !(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))) return null
        return runCatching {
            manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val INTERVAL_MS = 3 * 60_000L
        const val MIN_DISTANCE_M = 50f
    }
}
