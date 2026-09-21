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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelMeterTest {
    @Test fun silenceIsZero() {
        assertEquals(0f, VoiceLevelMeter.level(ByteArray(960)), 0f)
    }

    @Test fun fullScaleIsOne() {
        assertEquals(1f, VoiceLevelMeter.level(pcm(480) { if (it % 2 == 0) 32_767 else -32_768 }), 0f)
    }

    @Test fun conversationalSpeechSitsInTheMiddle() {
        // About -30 dBFS, a normal speaking voice at arm's length.
        val level = VoiceLevelMeter.level(pcm(480) { if (it % 2 == 0) 1_036 else -1_036 })
        assertTrue("level was $level", level in 0.55f..0.75f)
    }

    @Test fun onlyTheGivenBytesAreMeasured() {
        val data = ByteArray(960) + pcm(480) { 32_767 }
        assertEquals(0f, VoiceLevelMeter.level(data, 960), 0f)
    }

    @Test fun aTrailingHalfSampleIsIgnored() {
        assertEquals(0f, VoiceLevelMeter.level(byteArrayOf(0, 0, 0x7F)), 0f)
    }

    private fun pcm(samples: Int, value: (Int) -> Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        for (index in 0 until samples) {
            val sample = value(index)
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }
}
