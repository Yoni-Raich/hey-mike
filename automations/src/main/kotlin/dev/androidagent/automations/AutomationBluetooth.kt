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
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.androidagent.core.AutomationConnection
import dev.androidagent.core.AutomationDeviceSnapshot
import java.util.concurrent.ConcurrentHashMap

/** Public connected-profile APIs. A paired device or an ACL broadcast is never proof of connection. */
internal class AutomationBluetooth(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val requested = ConcurrentHashMap.newKeySet<Int>()
    private var started = false
    private var changed: () -> Unit = {}
    fun permitted() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!started) { manager.adapter?.closeProfileProxy(profile, proxy); return }
            proxies[profile] = proxy
            changed()
        }
        override fun onServiceDisconnected(profile: Int) { proxies.remove(profile); requested.remove(profile); changed() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changed() }
    }
    fun start(onChanged: () -> Unit) {
        if (started) return
        started = true
        changed = onChanged
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
        }
        // Bluetooth broadcasts can come from a privileged process outside the system UID.
        // Intent data is ignored: each callback queries Android's current state again.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }
    fun stop() {
        started = false
        changed = {}
        runCatching { context.unregisterReceiver(receiver) }
        proxies.forEach { (profile, proxy) -> manager.adapter?.closeProfileProxy(profile, proxy) }
        proxies.clear(); requested.clear()
    }
    @SuppressLint("MissingPermission") // Checked at entry; permission revocation and binder failures produce unknown.
    fun snapshot(): AutomationDeviceSnapshot = runCatching {
        if (!permitted()) return AutomationDeviceSnapshot(mapOf("bluetooth_device" to "unknown"))
        val adapter = manager.adapter ?: return AutomationDeviceSnapshot(mapOf("bluetooth_device" to "disconnected"))
        if (!adapter.isEnabled) return AutomationDeviceSnapshot(mapOf("bluetooth_device" to "disconnected"))
        val profiles = mutableMapOf(BluetoothProfile.HEADSET to "hfp", BluetoothProfile.A2DP to "a2dp")
        if (Build.VERSION.SDK_INT >= 33 && adapter.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED) profiles[BluetoothProfile.LE_AUDIO] = "le_audio"
        if (started) for (profile in profiles.keys) if (requested.add(profile)) {
            if (!adapter.getProfileProxy(context, listener, profile)) requested.remove(profile)
        }
        val entries = manager.getConnectedDevices(BluetoothProfile.GATT).map { device ->
            AutomationConnection("bluetooth_device", device.address, device.name, setOf("gatt"))
        }.toMutableList()
        for ((profile, name) in profiles) proxies[profile]?.connectedDevices?.forEach { device ->
            entries += AutomationConnection("bluetooth_device", device.address, device.name, setOf(name))
        }
        val connections = entries.groupBy { it.address }.values.map { list ->
            list.first().copy(profiles = list.flatMap { it.profiles }.toSet())
        }
        val known = profiles.keys.all { proxies.containsKey(it) }
        AutomationDeviceSnapshot(mapOf("bluetooth_device" to if (!known) "unknown" else if (connections.isEmpty()) "disconnected" else "connected"), connections)
    }.getOrDefault(AutomationDeviceSnapshot(mapOf("bluetooth_device" to "unknown")))
}
