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

import kotlin.math.log10
import kotlin.math.sqrt

/** Which side of the conversation an audio level belongs to. */
enum class VoiceLevelSource { INPUT, OUTPUT }

/** Receives loudness updates, 0 (silence) to 1 (loud speech), on an audio thread. */
fun interface VoiceLevelListener {
    fun onLevel(source: VoiceLevelSource, level: Float)
}

/**
 * Loudness of little-endian 16-bit PCM on a 0..1 scale for the voice
 * visualiser. The scale is logarithmic so quiet speech still moves it.
 */
object VoiceLevelMeter {
    private const val FLOOR_DB = -60.0
    private const val CEILING_DB = -14.0

    fun level(pcm: ByteArray, byteCount: Int = pcm.size): Float {
        val samples = byteCount.coerceIn(0, pcm.size) / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (index in 0 until samples) {
            val sample = (pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
        }
        val rms = sqrt(sum / samples)
        if (rms < 1.0) return 0f
        val decibels = 20.0 * log10(rms / 32_768.0)
        return ((decibels - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
    }
}
