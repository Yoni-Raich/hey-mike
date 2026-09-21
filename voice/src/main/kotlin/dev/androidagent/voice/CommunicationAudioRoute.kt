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
internal class CommunicationAudioRoute(private val manager: AudioManager) {
    private var active = false
    private var previous: AudioDeviceInfo? = null
    private var selectedId: Int? = null
    private var startedSco = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { select() }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) { select() }
    }

    fun start() {
        if (active) return
        active = true
        if (Build.VERSION.SDK_INT >= 31) previous = manager.communicationDevice
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        select()
    }

    @Suppress("DEPRECATION")
    private fun select() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = manager.availableCommunicationDevices
            val current = manager.communicationDevice
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
        if (!active) return
        active = false
        manager.unregisterAudioDeviceCallback(callback)
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
    }

    companion object {
        internal fun priority(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_HEARING_AID -> 2
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 10
            else -> 20
        }
    }
}
