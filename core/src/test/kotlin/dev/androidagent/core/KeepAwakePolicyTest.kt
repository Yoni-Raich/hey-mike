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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAwakePolicyTest {
    @Test fun idleConversationDoesNotKeepAwake() {
        assertFalse(KeepAwakePolicy.shouldKeepAwake(RunState(), VoiceState()))
    }

    @Test fun everyNonTerminalRunPhaseKeepsAwake() {
        val activePhases = listOf(
            RunPhase.STARTING,
            RunPhase.THINKING,
            RunPhase.TOOL,
            RunPhase.CONTROLLING,
            RunPhase.STOPPING,
        )
        for (phase in activePhases) {
            assertTrue(
                "phase $phase should keep the screen awake",
                KeepAwakePolicy.shouldKeepAwake(RunState(phase = phase), VoiceState()),
            )
        }
    }

    @Test fun erroredRunReleasesAwake() {
        assertFalse(
            KeepAwakePolicy.shouldKeepAwake(
                RunState(phase = RunPhase.ERROR, status = "Run failed"),
                VoiceState(),
            ),
        )
    }

    @Test fun everyNonTerminalVoicePhaseKeepsAwake() {
        val activePhases = listOf(
            VoicePhase.STARTING,
            VoicePhase.LISTENING,
            VoicePhase.SPEAKING,
            VoicePhase.STOPPING,
        )
        for (phase in activePhases) {
            assertTrue(
                "voice $phase should keep the screen awake",
                KeepAwakePolicy.shouldKeepAwake(RunState(), VoiceState(phase = phase)),
            )
        }
    }

    @Test fun idleOrErroredVoiceReleasesAwake() {
        assertFalse(KeepAwakePolicy.shouldKeepAwake(RunState(), VoiceState(phase = VoicePhase.IDLE)))
        assertFalse(KeepAwakePolicy.shouldKeepAwake(RunState(), VoiceState(phase = VoicePhase.ERROR)))
    }

    @Test fun typedTextWhileVoiceIsActiveKeepsAwake() {
        assertTrue(
            KeepAwakePolicy.shouldKeepAwake(
                RunState(),
                VoiceState(phase = VoicePhase.LISTENING),
            ),
        )
    }

    @Test fun stoppingStillHoldsAwakeUntilTerminal() {
        assertTrue(
            KeepAwakePolicy.shouldKeepAwake(
                RunState(phase = RunPhase.STOPPING, status = "Stopping"),
                VoiceState(),
            ),
        )
        assertTrue(
            KeepAwakePolicy.shouldKeepAwake(
                RunState(),
                VoiceState(phase = VoicePhase.STOPPING),
            ),
        )
    }
}
