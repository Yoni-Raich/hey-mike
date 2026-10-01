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

package dev.androidagent.app.widget

import dev.androidagent.core.AccountUsageRow
import dev.androidagent.core.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageWidgetTest {

    private fun row(id: String, engine: EngineKind = EngineKind.CODEX, live: Boolean = false, readText: String? = null) =
        AccountUsageRow(id, id, live, window = null, readText = readText, engine = engine)

    @Test fun fewRowsAreAllShown() {
        val rows = listOf(row("a"), row("b"), row("claude", EngineKind.CLAUDE))
        assertEquals(rows, UsageWidget.fit(rows))
    }

    @Test fun claudeKeepsItsPlaceWhenCodexAccountsAreTooMany() {
        val rows = listOf(row("a"), row("b"), row("c"), row("d"), row("claude", EngineKind.CLAUDE))
        assertEquals(listOf("a", "b", "c", "claude"), UsageWidget.fit(rows).map { it.accountId })
    }

    @Test fun codexAccountsFillTheWidgetWithoutClaude() {
        val rows = (1..6).map { row("a$it") }
        assertEquals(listOf("a1", "a2", "a3", "a4"), UsageWidget.fit(rows).map { it.accountId })
    }

    @Test fun theStatusNamesClaudeAndItsAge() {
        assertEquals("In use", UsageWidget.statusText(row("a", live = true)))
        assertEquals("Updated 3h ago", UsageWidget.statusText(row("a", readText = "Updated 3h ago")))
        assertEquals("Switch to read", UsageWidget.statusText(row("a")))
        assertEquals("Claude · Updated 3h ago", UsageWidget.statusText(row("c", EngineKind.CLAUDE, readText = "Updated 3h ago")))
        assertEquals("Claude", UsageWidget.statusText(row("c", EngineKind.CLAUDE)))
    }
}
