/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core.proactive

import dev.androidagent.core.EngineKind
import dev.androidagent.core.SignalLane
import dev.androidagent.core.TaskSignal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerConnectionsTest {
    private val gmail = AccountConnector(EngineKind.CLAUDE, "claude.ai Gmail", "connected",
        listOf("mcp__claude_ai_Gmail__search_threads"), writeTools = 5)

    private class Source(override val engine: EngineKind, val list: List<AccountConnector>) : AccountConnectorSource {
        var allowed: List<String>? = null
        var asked = 0
        override suspend fun accountConnectors(): List<AccountConnector> { asked++; return list }
        override fun allowAccountTools(tools: List<String>) { allowed = tools }
    }

    private fun row(id: String, app: String) =
        TaskSignal(id, "notification", app, "Inbox", "Dana", "Contract", at = 1L, capturedAt = 1L, lane = SignalLane.PEOPLE)

    @Test
    fun switchingAConnectorTellsTheEngineAtOnce() = runTest {
        val on = mutableSetOf<String>()
        val claude = Source(EngineKind.CLAUDE, listOf(gmail))
        val connections = ManagerConnections({ it in on }, { _, _ -> true }).apply { accountSources = listOf(claude) }
        var changes = 0
        connections.refresh { changes++ }
        assertEquals(2, changes) // the connector states, then the account list
        assertTrue(connections.accountsChecked)
        assertEquals(emptyList<String>(), claude.allowed)
        assertFalse(connections.mailReadable(EngineKind.CLAUDE))

        connections.switched(ContextRouter.key(gmail), true)
        assertEquals(gmail.readTools, claude.allowed)
        on += ContextRouter.key(gmail)
        assertTrue(connections.mailReadable(EngineKind.CLAUDE))
        assertFalse(connections.mailReadable(EngineKind.CODEX))
        assertTrue(connections.note(EngineKind.CLAUDE)!!.contains("search_threads"))
    }

    @Test
    fun aPassAsksTheEnginesOnceAndNotesOnlyUnexplainedRows() = runTest {
        val claude = Source(EngineKind.CLAUDE, listOf(gmail))
        val connections = ManagerConnections({ true }, { _, _ -> true }).apply { accountSources = listOf(claude) }
        val rows = listOf(row("a", ContextRouter.GMAIL_APP), row("b", ContextRouter.GMAIL_APP))
        val pulled = listOf(RowContext("b", ConversationContext("whatsapp-web", "Inbox", emptyList(), "title", hasEarlier = false), null), RowContext("a", null, "not found"))
        val contexts = connections.passContext(rows, pulled, EngineKind.CLAUDE)
        assertEquals(listOf("b", "a"), contexts.map { it.signalId })
        assertTrue(contexts.last().note!!.contains("search_threads"))
        connections.passContext(rows, pulled, EngineKind.CLAUDE)
        assertEquals(1, claude.asked)
        assertTrue(connections.passContext(rows, pulled, EngineKind.CODEX).none { it.note?.contains("search_threads") == true })
    }
}
