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

package dev.androidagent.core.widget

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** How a widget writes numbers, and the colors it draws with. Pure, so it is tested without a phone. */
object WidgetFormat {
    private val symbols = DecimalFormatSymbols(Locale.US)

    fun plain(value: Any): String = when (value) {
        is Double -> number(value)
        is Number -> number(value.toDouble())
        else -> value.toString()
    }

    /** A whole number without a decimal point, anything else with at most two decimals. */
    fun number(value: Double): String {
        if (!value.isFinite()) return "0"
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return DecimalFormat("0.##", symbols).format(value)
    }

    /**
     * [style] is `int` (thousands separators, no decimals), `1` or `decimal`
     * (one decimal), `2` (two decimals), `compact` (1.2K, 3.4M), `signed`
     * (+3 / -3) or `percent` (a whole number with %). Anything else is
     * written as [number].
     */
    fun format(value: Double, style: String?): String {
        if (!value.isFinite()) return "0"
        return when (style?.trim()?.lowercase()) {
            "int", "integer" -> DecimalFormat("#,##0", symbols).format(value)
            "1", "decimal", "decimal1" -> DecimalFormat("#,##0.0", symbols).format(value)
            "2", "decimal2" -> DecimalFormat("#,##0.00", symbols).format(value)
            "percent", "pct" -> DecimalFormat("#,##0", symbols).format(value) + "%"
            "signed" -> (if (value > 0) "+" else "") + number(value)
            "compact" -> compact(value)
            else -> number(value)
        }
    }

    private fun compact(value: Double): String {
        val magnitude = abs(value)
        val (scaled, suffix) = when {
            magnitude >= 1e9 -> value / 1e9 to "B"
            magnitude >= 1e6 -> value / 1e6 to "M"
            magnitude >= 1e3 -> value / 1e3 to "K"
            else -> return number(value)
        }
        return DecimalFormat("0.#", symbols).format(scaled) + suffix
    }

    /** A value with its unit: `88` and `%` read `88%`, `850` and ` ms` read `850 ms`. */
    fun withUnit(value: Double, style: String?, unit: String): String = format(value, style) + unit

    val PALETTE: List<Int> = listOf(
        0xFF3B82F6.toInt(), // blue
        0xFFA855F7.toInt(), // purple
        0xFFF97316.toInt(), // orange
        0xFF22C55E.toInt(), // green
        0xFFEF4444.toInt(), // red
        0xFF06B6D4.toInt(), // cyan
        0xFFEAB308.toInt(), // yellow
        0xFFEC4899.toInt(), // pink
    )

    private val NAMED = mapOf(
        "blue" to PALETTE[0],
        "purple" to PALETTE[1],
        "orange" to PALETTE[2],
        "green" to PALETTE[3],
        "red" to PALETTE[4],
        "cyan" to PALETTE[5],
        "yellow" to PALETTE[6],
        "pink" to PALETTE[7],
        "gray" to 0xFF9CA3AF.toInt(),
        "grey" to 0xFF9CA3AF.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
    )

    /** `#RGB`, `#RRGGBB`, `#AARRGGBB` or one of the palette names; null for anything else. */
    fun parseColor(value: String?): Int? {
        val text = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        NAMED[text]?.let { return it }
        if (!text.startsWith("#")) return null
        val hex = text.drop(1)
        if (hex.any { it !in "0123456789abcdef" }) return null
        return when (hex.length) {
            3 -> (0xFF000000 or hex.map { (it.digitToInt(16) * 17).toLong() }.fold(0L) { acc, c -> (acc shl 8) or c }).toInt()
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    }

    /** The scale a chart draws on: round gridline values that cover [min]..[max]. */
    data class Scale(val min: Double, val max: Double, val step: Double) {
        val ticks: List<Double>
            get() {
                val out = ArrayList<Double>()
                var v = min
                var guard = 0
                while (v <= max + step * 1e-6 && guard++ < 64) {
                    out += v
                    v += step
                }
                return out
            }
    }

    fun niceScale(min: Double, max: Double, wanted: Int = 4): Scale {
        var low = min
        var high = max
        if (high - low < 1e-9) {
            val pad = if (abs(high) < 1e-9) 1.0 else abs(high) * 0.5
            low -= pad
            high += pad
        }
        val rough = (high - low) / wanted.coerceAtLeast(1)
        val magnitude = 10.0.pow(floor(log10(rough)))
        val residual = rough / magnitude
        val step = magnitude * when {
            residual <= 1.0 -> 1.0
            residual <= 2.0 -> 2.0
            residual <= 5.0 -> 5.0
            else -> 10.0
        }
        return Scale(floor(low / step) * step, ceil(high / step) * step, step)
    }
}
