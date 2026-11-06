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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGuideTest {
    @Test fun theGuideExampleIsADrawableWidgetWithoutWarnings() {
        val parts = splitMessage(WidgetGuide.TEXT, streaming = false)
        val widgets = parts.filterIsInstance<MessagePart.Widget>()
        assertEquals(1, widgets.size)
        assertEquals(emptyList<String>(), widgets.single().spec.warnings)
        assertTrue(parts.none { it is MessagePart.Broken })
    }

    @Test fun theGuideStaysShortEnoughForEverySessionPrompt() {
        assertTrue("guide is ${WidgetGuide.TEXT.length} characters", WidgetGuide.TEXT.length < 3_000)
    }
}
