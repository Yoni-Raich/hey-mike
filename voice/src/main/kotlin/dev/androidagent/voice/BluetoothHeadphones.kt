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

package dev.androidagent.voice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.androidagent.core.AutomationCondition
import dev.androidagent.core.AutomationConnection

/** Audio endpoints, not bonded devices or generic Bluetooth/GATT connections. No names are stored. */
class BluetoothHeadphones(private val context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var callback: AudioDeviceCallback? = null

    fun state(): String = if (!permitted()) "unknown" else
        runCatching { if (connectedIds().isEmpty()) "disconnected" else "connected" }.getOrDefault("unknown")

    fun permitted(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Permission checked here; races fail closed at each caller.
    private fun connectedOutputs(): List<Pair<Int, AutomationConnection>> {
        if (!permitted()) throw SecurityException("Nearby devices permission is missing.")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() +
            if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices else emptyList()
        val identified = outputs.mapNotNull { output ->
            if (!isBluetoothType(output.type)) null else runCatching {
                val device = adapter.getRemoteDevice(output.address)
                if (!qualifies(output.type, device.bluetoothClass?.deviceClass)) null else
                    output.id to AutomationConnection("bluetooth_headphones", device.address, device.name,
                        setOf(when (output.type) {
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "a2dp"
                            AudioDeviceInfo.TYPE_BLE_HEADSET -> "le_audio"
                            else -> "hfp"
                        }))
            }.getOrNull()
        }
        val profiles = identified.groupBy { it.second.address }.mapValues { (_, entries) -> entries.flatMap { it.second.profiles }.toSet() }
        return identified.map { (id, connection) -> id to connection.copy(profiles = profiles[connection.address].orEmpty()) }
    }

    fun connections(): List<AutomationConnection> = connectedOutputs().map { it.second }.distinctBy { it.address }
    fun connectedIds(conditions: List<AutomationCondition> = emptyList()): Set<Int> = connectedOutputs()
        .filter { (_, connection) -> conditions.all { it.matchesConnection(connection) } }.map { it.first }.toSet()

    fun start(onChanged: () -> Unit) {
        if (callback != null) return
        val listener = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = onChanged()
        }
        manager.registerAudioDeviceCallback(listener, Handler(Looper.getMainLooper()))
        callback = listener
    }

    fun stop() {
        callback?.let { manager.unregisterAudioDeviceCallback(it) }
        callback = null
    }

    companion object {
        @SuppressLint("InlinedApi") // Integer type comparisons only; API 30 never reports a BLE-headset endpoint.
        internal fun isBluetoothType(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

        @SuppressLint("InlinedApi") // Pure classification of integer constants, no API 31 call.
        internal fun qualifies(type: Int, deviceClass: Int?): Boolean {
            if (!isBluetoothType(type)) return false
            // BLE headsets may have no classic class. Android's audio type identifies them.
            if (type == AudioDeviceInfo.TYPE_BLE_HEADSET && (deviceClass == null || deviceClass == 0)) return true
            return deviceClass in setOf(
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            )
        }
    }
}
