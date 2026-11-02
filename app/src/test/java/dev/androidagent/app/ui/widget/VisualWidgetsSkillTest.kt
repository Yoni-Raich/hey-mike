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

package dev.androidagent.app.ui.widget

import dev.androidagent.core.widget.MessagePart
import dev.androidagent.core.widget.WidgetGuide
import dev.androidagent.core.widget.splitMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skill is what teaches the model the format. If its examples stop parsing,
 * the model learns to write widgets the chat cannot draw.
 */
class VisualWidgetsSkillTest {
    private val skill = File("src/main/assets/agent_stack/skills/visual-widgets/SKILL.md").readText().replace("\r\n", "\n")

    @Test fun everyExampleInTheSkillIsADrawableWidgetWithoutWarnings() {
        val widgets = splitMessage(skill, streaming = false).filterIsInstance<MessagePart.Widget>()
        assertTrue("the skill must show at least one example", widgets.isNotEmpty())
        for (widget in widgets) assertEquals(emptyList<String>(), widget.spec.warnings)
        assertTrue(splitMessage(skill, streaming = false).none { it is MessagePart.Broken })
    }

    @Test fun theSkillSaysWhenItIsUsed() {
        assertTrue(skill.startsWith("---\nname: visual-widgets\n"))
        assertTrue(skill.contains("mike-widget"))
    }

    @Test fun theCompactGuideAndTheSkillAgreeOnTheFence() {
        assertTrue(WidgetGuide.TEXT.contains("```mike-widget"))
        for (type in listOf("slider", "chart", "gauge", "stats", "dots", "button")) {
            assertTrue("guide must name $type", WidgetGuide.TEXT.contains(type))
            assertTrue("skill must name $type", skill.contains(type))
        }
    }
}
