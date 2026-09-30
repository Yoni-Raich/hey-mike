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

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageMeterTest {
    // The bar beside the label fills with the used fraction, so the label
    // names the same number.
    @Test fun theLabelNamesWhatTheBarFills() {
        assertEquals("31% used", quotaLabel(0.31f))
        assertEquals("0% used", quotaLabel(0f))
        assertEquals("100% used", quotaLabel(1.2f))
        assertEquals("Unavailable", quotaLabel(null))
    }
}
