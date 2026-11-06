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

package dev.androidagent.core.proactive

import dev.androidagent.core.EngineKind
import dev.androidagent.core.SignalLane
import dev.androidagent.core.TaskSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextRouterTest {
    private val gmail = AccountConnector(EngineKind.CLAUDE, "claude.ai Gmail", "connected",
        listOf("mcp__claude_ai_Gmail__search_threads", "mcp__claude_ai_Gmail__get_thread"), writeTools = 12)
    private val drive = AccountConnector(EngineKind.CLAUDE, "claude.ai Google Drive", "needs-auth",
        listOf("mcp__claude_ai_Google_Drive__read_file_content"), writeTools = 2)

    private fun row(id: String, app: String) =
        TaskSignal(id, "notification", app, "Inbox", "Dana", "Contract", at = 1L, capturedAt = 1L, lane = SignalLane.PEOPLE)

    @Test
    fun onlyConnectedSwitchedOnConnectorsOfThePassEngineAreUsed() {
        val on = setOf(ContextRouter.key(gmail), ContextRouter.key(drive))
        assertEquals(listOf(gmail), ContextRouter.usable(listOf(gmail, drive), EngineKind.CLAUDE) { it in on })
        assertTrue(ContextRouter.usable(listOf(gmail, drive), EngineKind.CODEX) { it in on }.isEmpty())
        assertTrue(ContextRouter.usable(listOf(gmail), EngineKind.CLAUDE) { false }.isEmpty())
        assertEquals(gmail.readTools.sorted(), ContextRouter.backgroundTools(listOf(gmail, drive), EngineKind.CLAUDE) { it in on })
    }

    @Test
    fun aGmailNotificationGetsANoteOnlyWhenNothingExplainedItAndTheSourceIsAllowed() {
        val rows = listOf(row("a", "com.google.android.gm"), row("b", "com.google.android.gm"), row("c", "com.whatsapp"), row("d", "com.google.android.gm"))
        val notes = ContextRouter.accountNotes(rows, explained = setOf("b"), usable = listOf(gmail)) { _, _ -> true }
        assertEquals(listOf("a", "d"), notes.map { it.signalId })
        assertTrue(notes.first().note!!.contains("search_threads"))
        assertNull(notes.first().context)
        assertTrue(ContextRouter.accountNotes(rows, emptySet(), listOf(gmail)) { _, _ -> false }.isEmpty())
    }

    @Test
    fun theProductNameDropsTheAccountPrefix() {
        assertEquals("Gmail", gmail.name)
        assertEquals("account:claude:claude.ai Gmail", ContextRouter.key(gmail))
        assertNull(ContextRouter.accountFor("com.whatsapp", listOf(gmail)))
    }
}
