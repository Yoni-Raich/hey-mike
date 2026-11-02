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

package dev.androidagent.app.ui.widget

import dev.androidagent.core.widget.ChartKind
import dev.androidagent.core.widget.WidgetFormat

private const val LEFT_TO_RIGHT_MARK = "‎"

/**
 * A number with its unit, kept in reading order inside right-to-left text:
 * "850 ms", not "ms 850". A unit in the reader's own script (פעולות) already
 * reads right, so only Latin and symbol units get the mark.
 */
internal fun measured(value: Double, style: String?, unit: String): String {
    val text = WidgetFormat.withUnit(value, style, unit)
    val hasRtl = text.any {
        val d = Character.getDirectionality(it)
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }
    return if (hasRtl) text else LEFT_TO_RIGHT_MARK + text
}

/** The few words the chart itself writes, in the language the widget reads in. */
internal object ChartWords {
    fun kind(kind: ChartKind, rtl: Boolean): String = if (rtl) {
        when (kind) {
            ChartKind.LINE -> "קו"
            ChartKind.BAR -> "עמודות"
            ChartKind.AREA -> "שטח"
            ChartKind.PIE -> "עוגה"
            ChartKind.DONUT -> "טבעת"
            ChartKind.SPARK -> "קו קטן"
        }
    } else {
        when (kind) {
            ChartKind.LINE -> "Line"
            ChartKind.BAR -> "Bar"
            ChartKind.AREA -> "Area"
            ChartKind.PIE -> "Pie"
            ChartKind.DONUT -> "Donut"
            ChartKind.SPARK -> "Spark"
        }
    }

    fun total(rtl: Boolean): String = if (rtl) "סה״כ" else "Total"

    fun noData(rtl: Boolean): String = if (rtl) "אין נתונים" else "No data"

    fun summary(kind: ChartKind, points: Int, series: String, rtl: Boolean): String =
        if (rtl) "גרף ${kind(kind, true)}, $points נקודות, $series" else "${kind(kind, false)} chart, $points points, $series"
}
