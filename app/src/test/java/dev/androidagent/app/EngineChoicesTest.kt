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

    @Test fun theEngineCanChangeOnlyBeforeTheFirstMessageOnThisPhone() {
        val fresh = ChatSession("a", "New chat", 0, 0)
        assertTrue(ChatEngines.canChange(fresh, hasMessages = false, boundToComputer = false, running = false))
        assertFalse(ChatEngines.canChange(fresh, hasMessages = true, boundToComputer = false, running = false))
        assertFalse(ChatEngines.canChange(fresh.copy(engineThreadId = "t"), hasMessages = false, boundToComputer = false, running = false))
        assertFalse(ChatEngines.canChange(fresh, hasMessages = false, boundToComputer = true, running = false))
        assertFalse(ChatEngines.canChange(fresh, hasMessages = false, boundToComputer = false, running = true))
        assertFalse(ChatEngines.canChange(null, hasMessages = false, boundToComputer = false, running = false))
    }

    @Test fun onlyCodexChatsHaveVoice() {
        assertTrue(ChatEngines.hasVoice(EngineKind.CODEX))
        assertFalse(ChatEngines.hasVoice(EngineKind.CLAUDE))
    }

    @Test fun theDefaultEngineIsReadBackAndFallsBackToCodex() {
        assertEquals(EngineKind.CLAUDE, ChatEngines.parse("CLAUDE"))
        assertEquals(EngineKind.CODEX, ChatEngines.parse("something else"))
        assertEquals(EngineKind.CODEX, ChatEngines.parse(null))
    }
}
