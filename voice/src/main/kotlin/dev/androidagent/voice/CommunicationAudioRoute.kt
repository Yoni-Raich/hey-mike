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

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Owns only routing selected by this voice session; restores it on release. */
internal class CommunicationAudioRoute(
    private val manager: AudioManager,
    private val headphones: BluetoothHeadphones,
    private val onBluetoothLost: () -> Unit,
) {
    private var active = false
    private var previous: AudioDeviceInfo? = null
    private var selectedId: Int? = null
    private var startedSco = false
    @Volatile private var bluetoothOnly = false
    @Volatile private var blocked = false
    @Volatile private var armed = false
    private var conditions: List<dev.androidagent.core.AutomationCondition> = emptyList()
    private val routeChanged = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnCommunicationDeviceChangedListener {
        if (bluetoothOnly && armed && !bluetoothOutputReady()) loseBluetooth()
    } else null
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { changed() }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) { changed() }
    }
    private fun changed() {
        if (bluetoothOnly && armed) { if (!bluetoothOutputReady()) loseBluetooth() } else select()
    }

    fun start() {
        if (active) return
        active = true
        if (Build.VERSION.SDK_INT >= 31) previous = manager.communicationDevice
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 31) routeChanged?.let { manager.addOnCommunicationDeviceChangedListener({ it.run() }, it) }
        headphones.start {
            if (bluetoothOnly && (runCatching { headphones.connectedIds(conditions).isEmpty() }.getOrDefault(true) ||
                    (armed && !bluetoothOutputReady()))) loseBluetooth()
        }
        select()
    }

    @Suppress("DEPRECATION")
    private fun select() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = manager.availableCommunicationDevices
            val current = manager.communicationDevice
            if (bluetoothOnly) {
                val allowed = runCatching { headphones.connectedIds(conditions) }.getOrDefault(emptySet())
                val preferred = chooseBluetooth(current?.id, devices.map { it.id }, allowed)
                    ?.let { id -> devices.first { it.id == id } }
                if (blocked || preferred == null || !manager.setCommunicationDevice(preferred)) loseBluetooth()
                else selectedId = preferred.id
                return
            }
            // Keep an external device chosen by the user, including platform UI switches.
            val preferred = current?.takeIf { priority(it.type) < 10 && devices.any { d -> d.id == it.id } }
                ?: devices.minByOrNull { priority(it.type) }
            if (preferred != null && preferred.id != selectedId && manager.setCommunicationDevice(preferred)) selectedId = preferred.id
        } else {
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val wired = devices.any { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET) }
            val bluetooth = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (bluetooth && !wired) {
                if (!startedSco) { manager.startBluetoothSco(); startedSco = true }
                manager.isBluetoothScoOn = true
            } else if (startedSco) {
                manager.stopBluetoothSco(); manager.isBluetoothScoOn = false; startedSco = false
            }
            manager.isSpeakerphoneOn = !wired && !bluetooth
        }
    }

    @Suppress("DEPRECATION")
    fun close() {
        if (!active) { bluetoothOnly = false; blocked = false; armed = false; conditions = emptyList(); return }
        active = false
        manager.unregisterAudioDeviceCallback(callback)
        headphones.stop()
        if (Build.VERSION.SDK_INT >= 31) routeChanged?.let { manager.removeOnCommunicationDeviceChangedListener(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            // Do not replace a different route the user chose during this session.
            if (manager.communicationDevice?.id == selectedId) {
                val restore = previous?.let { old -> manager.availableCommunicationDevices.firstOrNull { it.id == old.id } }
                if (restore == null || !manager.setCommunicationDevice(restore)) manager.clearCommunicationDevice()
            }
        } else if (startedSco) {
            manager.stopBluetoothSco(); manager.isBluetoothScoOn = false
        }
        startedSco = false
        previous = null
        selectedId = null
        bluetoothOnly = false
        blocked = false
        armed = false
        conditions = emptyList()
    }

    fun requireBluetooth() {
        check(Build.VERSION.SDK_INT >= 31) { "Protected Bluetooth voice needs Android 12 or newer." }
        bluetoothOnly = true
        if (active) select()
        check(!blocked && runCatching { headphones.state() == "connected" }.getOrDefault(false)) {
            "Bluetooth headphones are unavailable. Grant Nearby devices permission and connect headphones."
        }
    }

    fun outputReady(): Boolean = !bluetoothOnly || bluetoothOutputReady()
    fun arm() { armed = true; checkOutput() }

    fun restrictTo(conditions: List<dev.androidagent.core.AutomationCondition>) {
        this.conditions = conditions.filter { it.requiresHeadphonesOutput() }
        if (bluetoothOnly && active) select()
    }

    fun checkOutput() {
        if (bluetoothOnly && !bluetoothOutputReady()) loseBluetooth()
        check(!blocked) { "Bluetooth headphones disconnected; voice stopped." }
    }

    private fun bluetoothOutputReady(): Boolean = !blocked && Build.VERSION.SDK_INT >= 31 && runCatching {
        manager.communicationDevice?.id in headphones.connectedIds(conditions)
    }.getOrDefault(false)

    private fun loseBluetooth() {
        if (blocked) return
        blocked = true
        // Silence locally before any network stop or selecting a fallback route.
        onBluetoothLost()
    }

    companion object {
        internal fun chooseBluetooth(current: Int?, available: List<Int>, allowed: Set<Int>): Int? =
            current?.takeIf { it in available && it in allowed } ?: available.firstOrNull { it in allowed }
        internal fun priority(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_HEARING_AID -> 2
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 10
            else -> 20
        }
    }
}
