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

import dev.androidagent.core.AgentSkill
import dev.androidagent.core.UsageLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerCommandsTest {
    private val briefing = AgentSkill(
        name = "daily-briefing",
        description = "Calendar, weather and unread messages",
        path = "/skills/daily-briefing/SKILL.md",
        scope = "user",
        displayName = "Daily briefing",
        defaultPrompt = "תכין לי תדריך בוקר",
    )

    @Test fun slashOpensEverythingAndDollarOnlySkills() {
        assertEquals(MenuQuery("", skillsOnly = false), menuQuery("/"))
        assertEquals(MenuQuery("co", skillsOnly = false), menuQuery("/co"))
        assertEquals(MenuQuery("da", skillsOnly = true), menuQuery("\$da"))
    }

    @Test fun theMenuClosesOnceTheDraftIsASentence() {
        assertNull(menuQuery(""))
        assertNull(menuQuery("hello"))
        assertNull(menuQuery("/compact now"))
        assertNull(menuQuery("/co\n"))
    }

    @Test fun onlyAWholeDraftNamesACommand() {
        assertEquals(ComposerCommand.COMPACT, exactCommand("/compact"))
        assertEquals(ComposerCommand.COMPACT, exactCommand(" /Compact "))
        assertNull(exactCommand("/compactx"))
        assertNull(exactCommand("compact"))
    }

    @Test fun skillsMatchTheirShownNameOrTheirId() {
        assertTrue(briefing.matches("brief"))
        assertTrue(briefing.matches("DAILY-"))
        assertFalse(briefing.matches("taxi"))
        assertTrue(ComposerCommand.RENAME.matches("re"))
        assertFalse(ComposerCommand.STATUS.matches("re"))
    }

    @Test fun pickingASkillBringsItsPromptButKeepsTypedWords() {
        assertEquals("תכין לי תדריך בוקר", draftAfterPicking(briefing, ""))
        assertEquals("תכין לי תדריך בוקר", draftAfterPicking(briefing, "/brief"))
        assertEquals("only the weather", draftAfterPicking(briefing, "only the weather"))
        assertEquals("", draftAfterPicking(briefing.copy(defaultPrompt = null), "\$da"))
    }

    @Test fun aSentSkillLeadsWithItsIdAsCodexExpects() {
        assertEquals("\$daily-briefing only the weather", withSkill(briefing, "only the weather"))
        assertEquals("just text", withSkill(null, "just text"))
    }

    @Test fun matchesAreFoundWhateverTheCase() {
        assertEquals(6..8, matchRange("Daily briefing", "BRI"))
        assertNull(matchRange("Daily briefing", "taxi"))
        assertNull(matchRange("Daily briefing", ""))
    }

    @Test fun onlyAFullHexColourIsKept() {
        assertEquals(0xFFF2B155L, brandArgb("#F2B155"))
        assertNull(brandArgb("orange"))
        assertNull(brandArgb("#FFF"))
        assertNull(brandArgb(null))
    }

    @Test fun statusSaysWhatIsSetAndWhatIsLeft() {
        val state = AgentUiState(
            selectedModel = "gpt-5.6-luna",
            selectedReasoningEffort = "low",
            planMode = true,
            usageLimits = listOf(UsageLimit("primary", usedPercent = 28.0, windowMinutes = 60)),
        )
        assertEquals("5.6-luna · low · Plan mode · Hourly 72% left · Phone control off", statusSummary(state, nowSeconds = 0))
        assertEquals("Default model · Phone control off", statusSummary(AgentUiState(), nowSeconds = 0))
    }
}
