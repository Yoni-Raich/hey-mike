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

package dev.androidagent.app

import dev.androidagent.core.RunPhase
import dev.androidagent.core.RunState
import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationKeepAwakeControllerTest {
    @Test fun idleStateDoesNotAcquireTheScreenLock() {
        val lock = FakeScreenWakeLock()
        ConversationKeepAwakeController(lock).update(RunState(), VoiceState())

        assertFalse(lock.isHeld)
        assertEquals(0, lock.acquireCount)
    }

    @Test fun activeRunAcquiresOnceAndTerminalRunReleases() {
        val lock = FakeScreenWakeLock()
        val controller = ConversationKeepAwakeController(lock)

        controller.update(RunState(RunPhase.THINKING), VoiceState())
        controller.update(RunState(RunPhase.TOOL), VoiceState())
        assertTrue(lock.isHeld)
        assertEquals(1, lock.acquireCount)

        controller.update(RunState(), VoiceState())
        assertFalse(lock.isHeld)
        assertEquals(1, lock.releaseCount)
    }

    @Test fun voiceAndStoppingStatesKeepTheScreenLockHeld() {
        val lock = FakeScreenWakeLock()
        val controller = ConversationKeepAwakeController(lock)

        controller.update(RunState(), VoiceState(VoicePhase.LISTENING))
        controller.update(RunState(RunPhase.STOPPING), VoiceState())

        assertTrue(lock.isHeld)
        assertEquals(1, lock.acquireCount)
        assertEquals(0, lock.releaseCount)
    }

    @Test fun explicitReleaseIsIdempotent() {
        val lock = FakeScreenWakeLock()
        val controller = ConversationKeepAwakeController(lock)
        controller.update(RunState(RunPhase.STARTING), VoiceState())

        controller.release()
        controller.release()

        assertFalse(lock.isHeld)
        assertEquals(1, lock.releaseCount)
    }

    private class FakeScreenWakeLock : ScreenWakeLock {
        override var isHeld = false
        var acquireCount = 0
        var releaseCount = 0

        override fun acquire() {
            isHeld = true
            acquireCount++
        }

        override fun release() {
            isHeld = false
            releaseCount++
        }
    }
}
