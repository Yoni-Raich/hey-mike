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

import dev.androidagent.core.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbMotionTest {
    private val frame = 1f / 60f

    /** Runs the orb's frame loop as AgentOrb does; returns the frames drawn, capped. */
    private fun run(motion: OrbMotion, resting: Boolean, target: Float, maxFrames: Int, animations: Boolean = true): Int {
        var frames = 0
        while (frames < maxFrames && orbNeedsFrames(animations, resting, motion, target)) {
            motion.step(frame, target, resting)
            frames++
        }
        return frames
    }

    @Test fun onlyIdleAndErrorAreRestAndControllingNeverIs() {
        assertTrue(runAtRest(RunPhase.IDLE, controlling = false))
        assertTrue(runAtRest(RunPhase.ERROR, controlling = false))
        assertFalse(runAtRest(RunPhase.IDLE, controlling = true))
        for (phase in listOf(RunPhase.STARTING, RunPhase.THINKING, RunPhase.TOOL, RunPhase.CONTROLLING, RunPhase.STOPPING)) {
            assertFalse(phase.name, runAtRest(phase, controlling = false))
        }
    }

    @Test fun anIdleOrbSettlesAndStopsAskingForFrames() {
        val motion = OrbMotion()
        // A fresh orb on an idle chat: about 2.5 s of gliding, then nothing.
        val frames = run(motion, resting = true, target = ORB_REST_ACTIVITY, maxFrames = 60 * 10)
        assertTrue("settled after $frames frames", frames in 60..60 * 4)
        assertTrue(motion.settled(ORB_REST_ACTIVITY))
        assertFalse(orbNeedsFrames(true, resting = true, motion = motion, target = ORB_REST_ACTIVITY))
    }

    @Test fun settlingAfterARunGlidesInsteadOfFreezing() {
        val motion = OrbMotion()
        run(motion, resting = false, target = 0.42f, maxFrames = 60 * 3)
        assertEquals(1f, motion.pace, 0.001f)
        // Half a second into rest it is still moving, slower, and still drawing.
        run(motion, resting = true, target = ORB_REST_ACTIVITY, maxFrames = 30)
        assertTrue(motion.pace in 0.2f..0.8f)
        assertTrue(orbNeedsFrames(true, resting = true, motion = motion, target = ORB_REST_ACTIVITY))
        val spin = motion.spin
        val frames = run(motion, resting = true, target = ORB_REST_ACTIVITY, maxFrames = 60 * 10)
        assertTrue("settled after $frames more frames", frames < 60 * 4)
        assertTrue(motion.spin > spin)
        assertEquals(ORB_REST_ACTIVITY, motion.level, 0.005f)
    }

    @Test fun aSettledOrbHoldsStillWhileItRests() {
        val motion = OrbMotion()
        run(motion, resting = true, target = ORB_REST_ACTIVITY, maxFrames = 60 * 10)
        // Even if something steps it again, the picture does not move.
        val spin = motion.spin
        val seconds = motion.seconds
        motion.step(frame, ORB_REST_ACTIVITY, resting = true)
        assertEquals(spin, motion.spin, 0.0005f)
        assertEquals(seconds, motion.seconds, 0.0005f)
    }

    @Test fun anActiveRunAlwaysGetsFramesAndResumesFromRest() {
        val motion = OrbMotion()
        run(motion, resting = true, target = ORB_REST_ACTIVITY, maxFrames = 60 * 10)
        assertFalse(orbNeedsFrames(true, resting = true, motion = motion, target = ORB_REST_ACTIVITY))
        // A run starts: frames come back at once and never stop on their own.
        assertTrue(orbNeedsFrames(true, resting = false, motion = motion, target = 0.26f))
        assertEquals(60 * 5, run(motion, resting = false, target = 0.26f, maxFrames = 60 * 5))
        assertEquals(1f, motion.pace, 0.001f)
        assertEquals(0.26f, motion.level, 0.005f)
    }

    @Test fun withAnimationsOffTheOrbNeverAsksForFrames() {
        val motion = OrbMotion()
        assertFalse(orbNeedsFrames(false, resting = true, motion = motion, target = ORB_REST_ACTIVITY))
        assertFalse(orbNeedsFrames(false, resting = false, motion = motion, target = 0.42f))
        assertEquals(0, run(motion, resting = false, target = 0.42f, maxFrames = 100, animations = false))
    }
}
