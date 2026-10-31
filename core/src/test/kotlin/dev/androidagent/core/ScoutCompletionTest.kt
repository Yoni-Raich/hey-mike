/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScoutCompletionTest {
    @Test fun suppressionIsHandledWithoutCreatingACardOrAcknowledgingInputAutomatically() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store).apply { load() }
        board.editSettings { it.copy(discoveryOn = true, suppressed = listOf(TaskSuppression(kind = "delivery", at = 1))) }
        val inbox = SignalInbox(store).apply { load() }
        inbox.add(TaskSignal("input", "chat", text = "A parcel update", at = 1, capturedAt = 1))
        var finished = false
        val tools = TaskTools(board, MemoryBook(store).apply { load() }, TeamRoster(store).apply { load() },
            roleOf = { TaskChatRole.SCOUT }, roleChat = { "unused" }, finishPass = { _, ids ->
                require(ids == listOf("input")); finished = true
            })
        val result = tools.invoke("scout", proposal("delivery"), false)
        assertTrue(result.text, result.success)
        assertTrue(Json.parseToJsonElement(result.text).jsonObject["suppressed"]!!.jsonPrimitive.boolean)
        assertTrue(board.tasks.value.isEmpty())
        assertFalse(finished)
        assertEquals("input", inbox.pending.value.single().id)
        val receipt = tools.invoke("scout", buildJsonObject { put("mode", "finish"); put("signalIds", buildJsonArray { add("input") }) }, false)
        assertTrue(receipt.text, receipt.success)
        assertTrue(finished)
        assertEquals("delivery", board.settings.value.suppressed.single().kind)
    }

    @Test fun missingRequiredFieldsAndSourceExclusionsRemainRealFailures() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store).apply { load() }
        val memory = MemoryBook(store).apply { load() }
        val team = TeamRoster(store).apply { load() }
        val tools = TaskTools(board, memory, team, { TaskChatRole.SCOUT }, { "unused" })
        val missingPlan = tools.invoke("scout", JsonObject(proposal("reply").filterKeys { it != "plan" }), false)
        assertFalse(missingPlan.success)
        assertTrue(missingPlan.text.contains("plan"))
        val missingText = tools.invoke("scout", buildJsonObject { put("mode", "remember"); put("subject", "Project") }, false)
        assertFalse(missingText.success)
        assertTrue(missingText.text.contains("text field"))
        assertTrue(board.tasks.value.isEmpty())
        assertTrue(memory.live.isEmpty())
        val excluded = TaskTools(board, memory, team, { TaskChatRole.SCOUT }, { "unused" }, allowedSource = { false })
        assertFalse(excluded.invoke("scout", proposal("reply"), false).success)
    }

    @Test fun anOrdinaryChatStillReceivesTheExistingSuppressionRefusal() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store).apply { load(); editSettings { it.copy(suppressed = listOf(TaskSuppression(kind = "delivery", at = 1))) } }
        val tools = TaskTools(board, MemoryBook(store).apply { load() }, TeamRoster(store).apply { load() },
            { TaskChatRole.ORDINARY }, { "unused" })
        val result = tools.invoke("chat", proposal("delivery"), true)
        assertFalse(result.success)
        assertTrue(board.tasks.value.isEmpty())
    }

    private fun proposal(kind: String) = buildJsonObject {
        put("mode", "propose"); put("title", "Review the parcel update"); put("why", "An update arrived")
        put("plan", "Read the status and prepare a summary"); put("kind", kind)
        put("evidence", buildJsonArray { add(buildJsonObject { put("source", "chat"); put("detail", "A parcel update arrived"); put("signalId", "input") }) })
    }
}
