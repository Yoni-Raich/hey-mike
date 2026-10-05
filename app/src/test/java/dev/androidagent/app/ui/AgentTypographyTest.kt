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

package dev.androidagent.app.ui

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTypographyTest {
    // Unspecified follows the layout, so on a Hebrew phone an English
    // sentence read right to left: "?Which account should I use".
    @Test fun everyTextStyleTakesItsDirectionFromItsWords() {
        val type = agentTypography()
        val styles = mapOf(
            "displayLarge" to type.displayLarge, "displayMedium" to type.displayMedium, "displaySmall" to type.displaySmall,
            "headlineLarge" to type.headlineLarge, "headlineMedium" to type.headlineMedium, "headlineSmall" to type.headlineSmall,
            "titleLarge" to type.titleLarge, "titleMedium" to type.titleMedium, "titleSmall" to type.titleSmall,
            "bodyLarge" to type.bodyLarge, "bodyMedium" to type.bodyMedium, "bodySmall" to type.bodySmall,
            "labelLarge" to type.labelLarge, "labelMedium" to type.labelMedium, "labelSmall" to type.labelSmall,
        )
        styles.forEach { (name, style) -> assertEquals(name, TextDirection.Content, style.textDirection) }
    }

    @Test fun theAppSizesStay() {
        val type = agentTypography()
        assertEquals(17.sp, type.bodyLarge.fontSize)
        assertEquals(27.sp, type.bodyLarge.lineHeight)
        assertEquals(14.sp, type.bodyMedium.fontSize)
        assertEquals(14.sp, type.labelLarge.fontSize)
    }
}
