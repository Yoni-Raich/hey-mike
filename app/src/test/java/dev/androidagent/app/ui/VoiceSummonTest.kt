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

import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSummonTest {
    @Test fun aPressPutsTheVoiceScreenUpBeforeTheCallExists() {
        val voice = AgentUiState(voiceSummon = "Waking Mike").shownVoice()
        assertTrue(voiceModeShown(voice))
        assertEquals(VoicePhase.STARTING, voice.phase)
        assertEquals("Waking Mike", voice.message)
    }

    @Test fun theRealCallTakesOverOnceItStarts() {
        val call = VoiceState(VoicePhase.LISTENING, "Listening", "thread")
        assertEquals(call, AgentUiState(voiceSummon = "Opening your conversation", voiceState = call).shownVoice())
    }

    @Test fun withoutAPressTheScreenFollowsTheCallAlone() {
        assertFalse(voiceModeShown(AgentUiState().shownVoice()))
    }
}
