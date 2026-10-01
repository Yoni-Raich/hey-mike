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

import dev.androidagent.core.AgentModel
import dev.androidagent.core.ChatSession
import dev.androidagent.core.EngineKind
import dev.androidagent.core.ReasoningEffortOption
import dev.androidagent.core.UsageLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineChoicesTest {
    private val sonnet = AgentModel("sonnet", reasoningEfforts = listOf(ReasoningEffortOption("low"), ReasoningEffortOption("high")), defaultReasoningEffort = "low")
    private val opus = AgentModel("opus", reasoningEfforts = listOf(ReasoningEffortOption("max")))
    private val plain = AgentModel("haiku")

    @Test fun aSavedPickSurvivesACatalogThatStillHasIt() {
        val choice = ModelChoice(model = "sonnet", effort = "high").withCatalog(listOf(sonnet, opus))
        assertEquals("sonnet", choice.model)
        assertEquals("high", choice.effort)
    }

    @Test fun aPickTheCatalogDroppedIsCleared() {
        val choice = ModelChoice(model = "gpt-5", effort = "high").withCatalog(listOf(sonnet, opus))
        assertNull(choice.model)
        assertNull(choice.effort)
    }

    @Test fun anEmptyCatalogKeepsThePickUntilTheEngineIsReady() {
        val choice = ModelChoice(model = "sonnet", effort = "high").withCatalog(emptyList())
        assertEquals("sonnet", choice.model)
        assertEquals("high", choice.effort)
    }

    @Test fun anotherModelKeepsTheEffortOnlyWhenItOffersIt() {
        val base = ModelChoice(listOf(sonnet, opus), "sonnet", "high")
        assertNull(base.select("opus").effort)
        assertEquals("high", base.select("sonnet").effort)
        assertEquals("opus", base.select("opus").model)
    }

    @Test fun anEffortTheModelDoesNotOfferIsRefused() {
        val base = ModelChoice(listOf(sonnet, opus), "sonnet")
        assertEquals("high", base.selectEffort("high").effort)
        assertNull(base.selectEffort("max").effort)
        assertNull(ModelChoice(listOf(sonnet)).selectEffort("high").effort)
    }

    @Test fun aTurnSendsThePickedEffortElseTheModelsDefault() {
        assertEquals("high", ModelChoice(listOf(sonnet), "sonnet", "high").turnEffort)
        assertEquals("low", ModelChoice(listOf(sonnet), "sonnet").turnEffort)
        assertNull(ModelChoice(listOf(plain), "haiku").turnEffort)
        assertNull(ModelChoice(listOf(sonnet)).turnEffort)
    }

    @Test fun planModeRidesOnTheFirstModelWhenNoneIsPicked() {
        assertEquals("sonnet", ModelChoice(listOf(sonnet, opus)).modelFor(planMode = true))
        assertNull(ModelChoice(listOf(sonnet, opus)).modelFor(planMode = false))
        assertEquals("opus", ModelChoice(listOf(sonnet, opus), "opus").modelFor(planMode = true))
    }

    @Test fun eachEngineKeepsItsOwnPickAndQuota() {
        val limits = listOf(UsageLimit("5-hour", 40.0, windowMinutes = 300))
        val engines = EngineChoices()
            .update(EngineKind.CODEX) { it.withCatalog(listOf(AgentModel("gpt-5"))).select("gpt-5") }
            .update(EngineKind.CLAUDE) { it.withCatalog(listOf(sonnet)).select("sonnet") }
            .withLimits(EngineKind.CLAUDE, limits)
        assertEquals("gpt-5", engines.of(EngineKind.CODEX).model)
        assertEquals("sonnet", engines.of(EngineKind.CLAUDE).model)
        assertEquals(limits, engines.limits(EngineKind.CLAUDE))
        assertTrue(engines.limits(EngineKind.CODEX).isEmpty())
    }

    @Test fun aChatsEngineIsReadFromItsSession() {
        val sessions = listOf(ChatSession("a", "A", 0, 0), ChatSession("b", "B", 0, 0, engine = EngineKind.CLAUDE))
        assertEquals(EngineKind.CLAUDE, ChatEngines.of(sessions, "b"))
        assertEquals(EngineKind.CODEX, ChatEngines.of(sessions, "a"))
        assertEquals(EngineKind.CODEX, ChatEngines.of(sessions, "gone"))
        assertEquals(EngineKind.CODEX, ChatEngines.of(sessions, null))
    }

    @Test fun theModelListHoldsBothEnginesCodexFirst() {
        val engines = EngineChoices()
            .update(EngineKind.CLAUDE) { it.withCatalog(listOf(sonnet.copy(engine = EngineKind.CLAUDE))) }
            .update(EngineKind.CODEX) { it.withCatalog(listOf(AgentModel("gpt-5"))) }

        assertEquals(listOf("gpt-5", "sonnet"), engines.offered(EngineKind.entries).map { it.id })
        assertEquals(listOf(EngineKind.CODEX, EngineKind.CLAUDE), engines.offered(EngineKind.entries).map { it.engine })
        // A chat that cannot run Claude is not offered its models.
        assertEquals(listOf("gpt-5"), engines.offered(setOf(EngineKind.CODEX)).map { it.id })
    }

    @Test fun aModelIsTaggedWithTheEngineWhoseListItIsIn() {
        // The fake and older engines do not tag their models.
        val engines = EngineChoices().update(EngineKind.CLAUDE) { it.withCatalog(listOf(sonnet)) }
        assertEquals(listOf(EngineKind.CLAUDE), engines.offered(EngineKind.entries).map { it.engine })
    }

    @Test fun pickingAModelPicksItsEngine() {
        val offered = listOf(AgentModel("gpt-5"), sonnet.copy(engine = EngineKind.CLAUDE))
        assertEquals(EngineKind.CLAUDE, ChatEngines.engineOfModel(offered, "sonnet", active = EngineKind.CODEX))
        assertEquals(EngineKind.CODEX, ChatEngines.engineOfModel(offered, "gpt-5", active = EngineKind.CLAUDE))
        // An id nobody lists stays on the chat's engine.
        assertEquals(EngineKind.CLAUDE, ChatEngines.engineOfModel(offered, "gone", active = EngineKind.CLAUDE))
    }

    @Test fun aChatOnAnotherEngineCanTalkOnceCodexIsSignedIn() {
        assertTrue(ChatEngines.hasVoice(EngineKind.CODEX, codexSignedIn = false))
        assertTrue(ChatEngines.hasVoice(EngineKind.CLAUDE, codexSignedIn = true))
        assertFalse(ChatEngines.hasVoice(EngineKind.CLAUDE, codexSignedIn = false))
    }

    @Test fun theDefaultEngineIsReadBackAndFallsBackToCodex() {
        assertEquals(EngineKind.CLAUDE, ChatEngines.parse("CLAUDE"))
        assertEquals(EngineKind.CODEX, ChatEngines.parse("something else"))
        assertEquals(EngineKind.CODEX, ChatEngines.parse(null))
    }
}
