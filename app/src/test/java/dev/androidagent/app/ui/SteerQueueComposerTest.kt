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

import dev.androidagent.core.AgentModel
import dev.androidagent.core.EngineKind
import dev.androidagent.core.QueuedTurn
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a message typed while the chat runs turns into, and how a queued one reads. */
class SteerQueueComposerTest {

    @Test fun aMessageTypedDuringARunSteersUnlessQueueIsChosen() {
        assertEquals(Delivery.STEER, deliveryOf(Delivery.STEER, hasAttachments = false, starting = false))
        assertEquals(Delivery.QUEUE, deliveryOf(Delivery.QUEUE, hasAttachments = false, starting = false))
    }

    @Test fun filesAndATurnStillStartingAlwaysQueue() {
        // Steering carries text only, and a turn that has not started cannot take it yet.
        assertEquals(Delivery.QUEUE, deliveryOf(Delivery.STEER, hasAttachments = true, starting = false))
        assertEquals(Delivery.QUEUE, deliveryOf(Delivery.STEER, hasAttachments = false, starting = true))
    }

    @Test fun aQueuedTurnNamesTheModelItWillRunWith() {
        val state = AgentUiState(
            activeEngine = EngineKind.CODEX,
            modelCatalog = listOf(
                AgentModel("gpt-6-luna"),
                AgentModel("opus", displayName = "Opus 4.7", engine = EngineKind.CLAUDE),
            ),
        )
        val claude = QueuedTurn(sessionId = "c", prompt = "B", model = "opus", effort = "high", engine = EngineKind.CLAUDE,
            imagePaths = listOf("a.png", "b.png"))
        assertEquals("Opus 4.7 · High · 2 pictures", queuedTurnDetail(state, claude))
        val codex = QueuedTurn(sessionId = "c", prompt = "C", model = "gpt-6-luna", effort = "xhigh", planMode = true)
        assertEquals("6 Luna · Extra High · Plan", queuedTurnDetail(state, codex))
        assertEquals("", queuedTurnDetail(state, QueuedTurn(sessionId = "c", prompt = "D")))
    }

    @Test fun aModelOfTheOtherEnginePickedDuringARunIsWhatTheNextMessageUses() {
        val running = AgentUiState(activeEngine = EngineKind.CODEX)
        assertEquals(EngineKind.CODEX, running.selectedEngine)
        assertEquals(EngineKind.CLAUDE, running.copy(nextEngine = EngineKind.CLAUDE).selectedEngine)
    }
}
