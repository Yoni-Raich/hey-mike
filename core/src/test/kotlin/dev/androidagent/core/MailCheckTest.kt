/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core

import dev.androidagent.core.proactive.AccountConnector
import dev.androidagent.core.proactive.ContextRouter
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MailCheckTest {
    private val noon = ZonedDateTime.of(2026, 11, 6, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    @Test fun aMailCheckRowIsAddedEveryFewHoursWhileAwakeAndOnlyOnce() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore()).apply { load() }
        assertNull(MailCheck.capture(inbox, noon, null, enabled = false, ZoneOffset.UTC))
        assertNull(MailCheck.capture(inbox, noon - 7 * hour, null, enabled = true, ZoneOffset.UTC)) // 05:00
        assertNull(MailCheck.capture(inbox, noon, noon - hour, enabled = true, ZoneOffset.UTC))

        val row = MailCheck.capture(inbox, noon, noon - 4 * hour, enabled = true, ZoneOffset.UTC)!!
        assertEquals(SignalRows.MAIL_CHECK, row.reason)
        assertEquals(ContextRouter.GMAIL_APP, row.app)
        assertTrue(row.sourceVerified)
        assertTrue(row.text, row.text.contains("${(noon - 4 * hour) / 1000}"))
        assertNull(MailCheck.capture(inbox, noon + 4 * hour, noon - 4 * hour, enabled = true, ZoneOffset.UTC))

        // The first check reads back one day at most.
        val fresh = SignalInbox(InMemoryTaskBoardStore()).apply { load() }
        assertTrue(MailCheck.capture(fresh, noon, null, enabled = true, ZoneOffset.UTC)!!.text.contains("${(noon - 24 * hour) / 1000}"))
    }

    @Test fun theConnectionsNoteNamesOnlyRoutesThisChatCanUse() {
        val claude = AccountConnector(EngineKind.CLAUDE, "claude.ai Gmail", "connected", listOf("mcp__claude_ai_Gmail__search_threads"), 3)
        val codex = AccountConnector(EngineKind.CODEX, "ChatGPT Gmail", "connected", listOf("gmail_search"), 2)
        assertNull(ContextRouter.connectionsNote(emptyList(), emptyList(), EngineKind.CLAUDE))
        val inCodex = ContextRouter.connectionsNote(listOf("WhatsApp Web"), listOf(claude, codex), EngineKind.CODEX)!!
        assertTrue(inCodex.contains("WhatsApp Web"))
        assertTrue(inCodex.contains("account_read"))
        assertFalse(inCodex.contains("search_threads"))
        val inClaude = ContextRouter.connectionsNote(emptyList(), listOf(claude), EngineKind.CLAUDE)!!
        assertTrue(inClaude.contains("search_threads"))
        assertTrue(ContextRouter.readsMail(listOf(claude)))
        assertFalse(ContextRouter.readsMail(emptyList()))
    }

    @Test fun theScoutIsToldHowToReadAMailCheckRow() {
        val row = TaskSignal("m", "email", app = ContextRouter.GMAIL_APP, text = "Mail check", at = noon, capturedAt = noon,
            lane = SignalLane.PEOPLE, reason = SignalRows.MAIL_CHECK)
        val prompt = TaskPrompts.scoutPass(listOf(row), noon, ZoneOffset.UTC, false, "/playbook")
        assertTrue(prompt.contains("mail_check row"))
        assertFalse(TaskPrompts.scoutPass(listOf(row.copy(reason = SignalRows.PERSON_MESSAGE)), noon, ZoneOffset.UTC, false, "/playbook").contains("mail_check row"))
    }
}
