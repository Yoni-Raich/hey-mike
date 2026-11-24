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

package dev.androidagent.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPanelLookTest {
    private val normal = FloatingPanelOverlay.Look(fontScale = 1f, densityDpi = 440, widthDp = 393)

    @Test fun aNewFontSizeDrawsTheCardsAgain() {
        assertTrue(needsRebuild(normal, normal.copy(fontScale = 1.3f)))
        assertTrue(needsRebuild(normal, normal.copy(densityDpi = 480)))
        assertTrue(needsRebuild(normal, normal.copy(widthDp = 851)))
    }

    @Test fun theSameLookOrNoCardsDrawsNothing() {
        assertFalse(needsRebuild(normal, normal.copy()))
        assertFalse(needsRebuild(null, normal.copy(fontScale = 2f)))
    }
}
