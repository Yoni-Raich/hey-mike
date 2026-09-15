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

package dev.androidagent.core

import kotlin.math.roundToLong

/** One quota window, ready to draw: how full it is and when it resets. */
data class UsageWindow(
    val limit: UsageLimit,
    /** 0..1, or null when the engine did not report a percentage. */
    val fraction: Float?,
    /** "Hourly" / "Weekly" / the raw name when the window length is unknown. */
    val label: String,
    /** "Resets in 2h 14m" / "Resets in 3d 4h", or null when no reset time is known. */
    val resetText: String?,
)

/**
 * Pure shaping for the usage meter. Percentages and reset times arrive from the
 * engine in whatever windows the account has, so the UI must not assume there
 * are exactly two of them.
 */
object UsageSummary {

    private const val MINUTES_PER_DAY = 60L * 24L

    fun windows(limits: List<UsageLimit>, nowSeconds: Long): List<UsageWindow> {
        val labels = limits.map(::label)
        // An account can carry two windows that both round to "Weekly". Two
        // rows with the same name and different numbers read as a bug, so any
        // collision falls back to the exact length.
        val duplicated = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return limits.mapIndexed { index, limit ->
            val label = labels[index]
            UsageWindow(
                limit = limit,
                fraction = limit.usedPercent?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
                label = if (label in duplicated) exactLabel(limit, label) else label,
                resetText = resetText(limit.resetsAt, nowSeconds),
            )
        }.sortedBy { it.limit.windowMinutes ?: Long.MAX_VALUE }
    }

    /**
     * The engine's own name wins here: an account can carry two windows of the
     * same length that mean different things, and only the name tells them
     * apart. The exact length is the fallback when there is no name.
     */
    private fun exactLabel(limit: UsageLimit, fallback: String): String {
        limit.name.trim().takeIf { it.isNotEmpty() }?.let { name ->
            // Engine names arrive as identifiers ("base_model_inference").
            return name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        val minutes = limit.windowMinutes ?: return fallback
        return when {
            minutes % MINUTES_PER_DAY == 0L -> "${minutes / MINUTES_PER_DAY}-day"
            minutes % 60L == 0L -> "${minutes / 60}-hour"
            else -> "$minutes-minute"
        }
    }

    /**
     * The window the meter should show: the fullest one, because that is the
     * one that will stop the next run. Windows without a percentage cannot
     * constrain anything the UI can draw.
     */
    fun primary(windows: List<UsageWindow>): UsageWindow? =
        windows.filter { it.fraction != null }.maxByOrNull { it.fraction!! }

    fun label(limit: UsageLimit): String = when (val minutes = limit.windowMinutes) {
        null -> limit.name.ifBlank { "Usage" }
        in 1..90 -> "Hourly"
        in 91..(MINUTES_PER_DAY + 120) -> "Daily"
        in (MINUTES_PER_DAY + 121)..(MINUTES_PER_DAY * 8) -> "Weekly"
        else -> "${minutes / MINUTES_PER_DAY}-day"
    }

    /**
     * Coarse by design: an exact countdown to the second would redraw every
     * frame and tell the user nothing they can act on.
     */
    fun resetText(resetsAt: Long?, nowSeconds: Long): String? {
        if (resetsAt == null || resetsAt <= 0L) return null
        val remaining = resetsAt - nowSeconds
        if (remaining <= 0L) return "Resetting now"
        val minutes = (remaining / 60.0).roundToLong().coerceAtLeast(1L)
        val days = minutes / MINUTES_PER_DAY
        val hours = (minutes % MINUTES_PER_DAY) / 60
        val leftover = minutes % 60
        return when {
            days > 0 -> "Resets in ${days}d ${hours}h"
            hours > 0 -> "Resets in ${hours}h ${leftover}m"
            else -> "Resets in ${leftover}m"
        }
    }

    /** Short text for the meter's accessibility description. */
    fun spoken(window: UsageWindow?): String {
        if (window?.fraction == null) return "Usage unavailable"
        val percent = (window.fraction * 100).roundToLong()
        val reset = window.resetText?.let { ". $it" }.orEmpty()
        return "${window.label} quota ${100 - percent}% left$reset"
    }
}
