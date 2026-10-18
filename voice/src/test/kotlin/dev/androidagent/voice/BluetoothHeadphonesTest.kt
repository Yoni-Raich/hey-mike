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

import android.bluetooth.BluetoothClass
import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class BluetoothHeadphonesTest {
    @Test fun classicHeadphonesUseAudioEndpointsAndHeadphoneClass() {
        assertTrue(BluetoothHeadphones.qualifies(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertTrue(BluetoothHeadphones.qualifies(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertFalse(BluetoothHeadphones.qualifies(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null))
    }
    @Test fun bleHeadsetNeedsNoClassicClassButWatchIsNeverAHeadset() {
        assertTrue(BluetoothHeadphones.qualifies(AudioDeviceInfo.TYPE_BLE_HEADSET, null))
        assertTrue(BluetoothHeadphones.qualifies(AudioDeviceInfo.TYPE_BLE_HEADSET, 0))
        for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            assertFalse(BluetoothHeadphones.qualifies(type, BluetoothClass.Device.WEARABLE_WRIST_WATCH))
        }
    }
    @Test fun speakerWiredAndBleSpeakerAreExcluded() {
        for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
            assertFalse(BluetoothHeadphones.qualifies(type, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        }
        assertFalse(BluetoothHeadphones.qualifies(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER))
    }
    @Test fun protectedRouteKeepsAnAllowedHeadsetAndNeverChoosesSpeakerAfterRemoval() {
        assertEquals(2, CommunicationAudioRoute.chooseBluetooth(2, listOf(1, 2), setOf(2)))
        assertEquals(3, CommunicationAudioRoute.chooseBluetooth(1, listOf(1, 3), setOf(3)))
        assertNull(CommunicationAudioRoute.chooseBluetooth(1, listOf(1), emptySet()))
        assertNull(CommunicationAudioRoute.chooseBluetooth(2, listOf(1), setOf(2)))
    }
}
