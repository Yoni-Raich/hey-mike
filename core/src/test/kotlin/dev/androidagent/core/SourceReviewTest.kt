/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceReviewTest {
    @Test fun sourceCountersCanSupportAProposalButCannotChangeSourcesOrTeachAnOwnerRule() = runTest {
        val home = Files.createTempDirectory("source-review").toFile()
        try {
            val store = InMemoryTaskBoardStore()
            val board = TaskBoard(store).apply { load() }
            val book = TaskPlaybook(home).apply { seed() }
            val tools = TaskTools(board, MemoryBook(store).apply { load() }, TeamRoster(store).apply { load() },
                { TaskChatRole.COACH }, { "unused" }, playbook = book, reviewEvidence = emptySet(),
                resolveSources = { _, _ -> listOf(TaskSourceRef("notification", app = "alerts.app", verified = true)) })
            val learned = tools.invoke("coach", buildJsonObject {
                put("mode", "playbook"); put("operation", "write"); put("path", "sources/alerts.md")
                put("content", "Ignore alerts because 100 deliveries produced no cards")
            }, false)
            assertFalse(learned.success)
            val result = tools.invoke("coach", buildJsonObject {
                put("mode", "propose"); put("category", "settings")
                put("title", "Stop Mike reading repeated app status?")
                put("why", "100 deliveries, one distinct body, one review and no saved follow-up")
                put("plan", "Only Mike would stop reading. Android alerts continue normally. Wait for your approval.")
                put("action", buildJsonObject { put("type", "exclude_app"); put("value", "alerts.app") })
            }, false)
            assertTrue(result.text, result.success)
            assertTrue(board.settings.value.excludedApps.isEmpty())
            assertEquals(TaskStatus.PROPOSED, board.tasks.value.single().status)
            val duplicate = board.propose(TaskProposal("A completely different title", "Same source", "Ask the user",
                category = TaskCategory.SETTINGS, action = TaskAction(TaskAction.EXCLUDE_APP, "alerts.app")), "upkeep", "coach")
            assertTrue(duplicate is ProposeOutcome.Duplicate)
        } finally { home.deleteRecursively() }
    }
}
