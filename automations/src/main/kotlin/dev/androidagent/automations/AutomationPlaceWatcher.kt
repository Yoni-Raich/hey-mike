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
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.androidagent.core.AutomationPlace
import dev.androidagent.core.AutomationPlaceStore
import dev.androidagent.core.AutomationPlaceWatch

/**
 * Turns the phone's position into arrivals and departures.
 *
 * **Why not a geofence.** `LocationServices.getGeofencingClient` is the
 * comfortable answer and it needs Google Play Services, which this build
 * cannot assume: it ships outside Play and is expected to run on phones that
 * do not have it. `LocationManager.addProximityAlert` is the other obvious
 * answer and is both deprecated and unreliable enough that shipping it quietly
 * would be worse than the gap it fills. So this watches directly, cheaply, and
 * says out loud what it costs.
 *
 * **What it costs.** One coarse subscription on the network provider, asking
 * for a fix no more often than every [MIN_INTERVAL_MS] and only after the
 * phone has moved [MIN_DISTANCE_M]. The network provider is the cellular and
 * wifi estimate, not GPS: it is what other apps have already paid for, so the
 * marginal cost of listening is small, and it does not wake the GPS radio. The
 * price is resolution — arriving is noticed within a minute or two of
 * happening, not at the instant it does, and `save_place` says so.
 *
 * **Nothing is decided here.** Which circles a fix is inside, and what changed,
 * is [AutomationPlaceWatch] in `:core`, under test. This class is the
 * subscription, the permission check and the saved inside-set.
 */
internal class AutomationPlaceWatcher(
    private val context: Context,
    private val places: AutomationPlaceStore,
    private val onChange: (AutomationPlaceWatch.Change) -> Unit,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var listening = false

    /**
     * Where the phone was last known to be.
     *
     * Persisted, and this matters more than it looks: without it a reboot or a
     * process death would report the user arriving everywhere they already
     * are. "Welcome home" at 3am because Android killed the app is exactly the
     * failure that makes someone turn rules off.
     */
    private var inside: Set<String>
        get() = prefs.getStringSet(KEY_INSIDE, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_INSIDE, value).apply()
        }

    private val listener = LocationListener { fix -> onFix(fix) }

    /** Background location, which is what watching while the app is closed needs. */
    fun canWatch(): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
            // Before API 29 there is no separate background grant: foreground
            // coarse is the whole permission.
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        else ->
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    /**
     * Start or stop listening to match what is actually needed. Called when
     * rules change, when places change, and when the permission may have.
     *
     * Not listening when no rule watches a place is the point: a subscription
     * that runs for a feature nobody uses is a battery cost with no benefit,
     * and it is the kind of thing that never gets noticed.
     */
    fun sync(wanted: Boolean) {
        if (wanted && canWatch() && places.all().isNotEmpty()) start() else stop()
    }

    private fun start() {
        if (listening) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val started = runCatching {
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
        }
        if (started.isFailure) {
            // A revoked grant, or a phone with no network provider at all.
            Log.w(TAG, "Cannot watch places: ${started.exceptionOrNull()?.message}")
            return
        }
        listening = true
        // Seed from whatever is already cached, so a rule does not wait for
        // the first update to learn the phone is already somewhere.
        runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
            .getOrNull()
            ?.let(::onFix)
    }

    private fun stop() {
        if (!listening) return
        listening = false
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        runCatching { manager?.removeUpdates(listener) }
    }

    /**
     * What the phone is inside right now, for an `at_place` condition.
     *
     * The saved set rather than a fresh read: a condition is tested in the
     * middle of deciding whether some other trigger fires, and blocking that
     * on a location read would hold up every rule on the phone.
     */
    fun currentlyInside(): Set<String> = inside

    private fun onFix(fix: Location) {
        val known = places.all()
        if (known.isEmpty()) return
        val change = AutomationPlaceWatch.update(
            previous = inside,
            places = known,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = if (fix.hasAccuracy()) fix.accuracy else null,
        )
        // Written before the events go out, for the reason a rule's fire is
        // recorded before its first action: a crash between the two must not
        // replay the arrival on the next fix.
        inside = change.inside
        if (change.entered.isEmpty() && change.left.isEmpty()) return
        onChange(change)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AutomationPlaces"
        private const val PREFS = "automation_places"
        private const val KEY_INSIDE = "inside"

        /**
         * Two minutes and 100m. Tighter than either would buy resolution this
         * cannot honestly deliver anyway — a network fix is routinely 50-100m
         * off — while costing real battery on a phone that is moving.
         */
        const val MIN_INTERVAL_MS = 2L * 60 * 1000
        const val MIN_DISTANCE_M = 100f

        /** The smallest place this can hope to resolve, for the settings copy. */
        const val USEFUL_RADIUS_M = AutomationPlace.MIN_RADIUS_M
    }
}
