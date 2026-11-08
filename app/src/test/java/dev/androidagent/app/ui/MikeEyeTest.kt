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

class MikeEyeTest {
    @Test fun theChinShowsOneLightPerRunningTaskOnlyWhileWorking() {
        assertEquals(0, chinLights(EyeMood.IDLE, satellites = 3))
        assertEquals(0, chinLights(EyeMood.NEEDS, satellites = 3))
        assertEquals(1, chinLights(EyeMood.WORKING, satellites = 0))
        assertEquals(2, chinLights(EyeMood.WORKING, satellites = 2))
        assertEquals(4, chinLights(EyeMood.WORKING, satellites = 9))
    }

    @Test fun theHeadHopsOnlyLateInItsCycleAndLandsEachTime() {
        assertEquals(0f, hopLift(0f), 0f)
        assertEquals(0f, hopLift(0.59f), 0f)
        assertEquals(14f, hopLift(0.72f), 0.01f)
        assertEquals(0f, hopLift(0.84f), 0.01f)
        assertEquals(4f, hopLift(0.92f), 0.01f)
        assertEquals(0f, hopLift(1f), 0.01f)
    }

    @Test fun theGlanceStartsAndEndsOnCentre() {
        assertEquals(0f, glanceX(0f), 0f)
        assertEquals(0f, glanceX(1f), 0.001f)
        assertEquals(6f, glanceX(0.35f), 0.001f)
        assertEquals(-6f, glanceX(0.6f), 0.001f)
        assertEquals(0f, glanceY(1f), 0.001f)
    }

    @Test fun smoothStepClampsOutsideItsRange() {
        assertEquals(0f, smoothStep(0.2f, 0.4f, 0.1f), 0f)
        assertEquals(1f, smoothStep(0.2f, 0.4f, 0.9f), 0f)
        assertEquals(0.5f, smoothStep(0.2f, 0.4f, 0.3f), 0.001f)
    }
}
