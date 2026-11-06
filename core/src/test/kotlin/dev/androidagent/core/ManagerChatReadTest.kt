/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core

import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManagerChatReadTest {
    private val chat = listOf(ChatMessage("s", "m", "system", "context", 1)) +
        (1..5).map { ChatMessage("u$it", "m", if (it % 2 == 1) "user" else "assistant", "line $it", it * 1_000L) } +
        ChatMessage("t", "m", "tool", "tool output", 9_000)

    private suspend fun tools(role: TaskChatRole, wired: Boolean = true): TaskTools {
        val store = InMemoryTaskBoardStore()
        return TaskTools(TaskBoard(store).apply { load() }, MemoryBook(store).apply { load() }, TeamRoster(store).apply { load() },
            { role }, { "unused" }, zone = { ZoneOffset.UTC }, managerMessages = if (wired) ({ chat }) else null)
    }

    private fun read(limit: Int, offset: Int = 0) = buildJsonObject { put("mode", "manager_chat"); put("limit", limit); put("offset", offset) }

    @Test fun anOrdinaryChatPagesTheManagersConversationNewestLast() = runTest {
        val first = tools(TaskChatRole.ORDINARY).invoke("pc", read(2), false)
        assertTrue(first.text, first.success)
        val page = Json.parseToJsonElement(first.text).jsonObject
        assertEquals(listOf("line 4", "line 5"), page["messages"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals("manager", page["messages"]!!.jsonArray[0].jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals(5, page["total"]!!.jsonPrimitive.int)
        assertEquals(2, page["nextOffset"]!!.jsonPrimitive.int)
        val older = Json.parseToJsonElement(tools(TaskChatRole.ORDINARY).invoke("pc", read(10, 2), false).text).jsonObject
        assertEquals(listOf("line 1", "line 2", "line 3"), older["messages"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertNull(older["nextOffset"])
    }

    @Test fun backgroundRolesAndUnwiredChatsCannotRead() = runTest {
        assertFalse(tools(TaskChatRole.SCOUT).invoke("scout", read(5), false).success)
        assertFalse(tools(TaskChatRole.ORDINARY, wired = false).invoke("pc", read(5), false).success)
    }
}
