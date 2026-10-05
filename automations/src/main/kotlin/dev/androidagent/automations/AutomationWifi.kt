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
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.androidagent.core.AutomationConnection
import dev.androidagent.core.AutomationDeviceSnapshot

/** Reads the current association. Never scans, connects, or reads passwords. */
internal class AutomationWifi(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    fun permitted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    @Suppress("DEPRECATION") // Compatibility snapshot on API 30+, rechecked on every event and launch.
    @SuppressLint("MissingPermission") // Explicit permission check; redacted fields and races fail closed.
    fun snapshot(): AutomationDeviceSnapshot = runCatching {
        if (!permitted()) return AutomationDeviceSnapshot(mapOf("wifi" to "unknown"))
        val connected = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (!connected) return AutomationDeviceSnapshot(mapOf("wifi" to "disconnected"))
        val info = context.getSystemService(WifiManager::class.java).connectionInfo
        val ssid = info.ssid?.removeSurrounding("\"")
        val bssid = info.bssid
        if (ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID || bssid == null || bssid == "02:00:00:00:00:00") {
            AutomationDeviceSnapshot(mapOf("wifi" to "unknown"))
        } else AutomationDeviceSnapshot(mapOf("wifi" to "connected"), listOf(AutomationConnection("wifi", ssid = ssid, bssid = bssid)))
    }.getOrDefault(AutomationDeviceSnapshot(mapOf("wifi" to "unknown")))

    fun start(onChanged: () -> Unit) {
        if (callback != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChanged()
            override fun onLost(network: Network) = onChanged()
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), listener)
        callback = listener
    }
    fun stop() { callback?.let { connectivity.unregisterNetworkCallback(it) }; callback = null }
}
