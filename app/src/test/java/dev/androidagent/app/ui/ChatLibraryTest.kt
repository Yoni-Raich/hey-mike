package dev.androidagent.app.ui

import dev.androidagent.core.ChatSession
import dev.androidagent.remote.ComputerConversation as CodexThread
import dev.androidagent.remote.RemoteBinding
import dev.androidagent.remote.RemoteComputer
import org.junit.Assert.*
import org.junit.Test

class ChatLibraryTest {
    private val phone = ChatSession("phone", "Phone task", 1, 10)
    private val imported = ChatSession("imported", "Imported chat", 1, 40)
    private val bindings = mapOf("imported" to RemoteBinding("desk", "C:\\src\\app", threadId = "shared-id"))
    private val sections = PcChats.sections(
        listOf(RemoteComputer("desk", "Desk", "", user = "test"), RemoteComputer("server", "Server", "", user = "test")),
        "desk", emptyList(), bindings, listOf(phone, imported),
        mapOf("desk" to listOf(CodexThread("shared-id", "Original", "C:\\src\\app", 40), CodexThread("second", "Desktop task", "C:\\web", 30)),
            "server" to listOf(CodexThread("second", "Server task", "/home/test/app", 20))),
    )

    @Test fun recentChatsKeepEveryLocationAndDoNotDuplicateAnImportedThread() {
        val rows = ChatLibrary.chats(listOf(phone, imported), bindings, sections)
        assertEquals(listOf("Imported chat", "Desktop task", "Server task", "Phone task"), rows.map { it.entry.title })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(listOf("Phone task"), ChatLibrary.chats(listOf(phone, imported), bindings, sections, ChatLibrary.PHONE).map { it.entry.title })
        assertEquals(listOf("Imported chat", "Desktop task"), ChatLibrary.chats(listOf(phone, imported), bindings, sections, "desk").map { it.entry.title })
    }

    @Test fun openingAProjectKeepsItsChatsOnTheCorrectComputer() {
        val project = sections.first().projects.first()
        val rows = ChatLibrary.chats(listOf(phone, imported), bindings, sections, projectKey = project.key)
        assertEquals(listOf("Imported chat"), rows.map { it.entry.title })
        assertTrue(rows.all { it.computerId == "desk" })
        assertTrue(ChatLibrary.chats(listOf(phone, imported), bindings, sections, "server", project.key).isEmpty())
    }

    @Test fun searchFindsAProjectOrComputerAndCanBeNarrowedToOneLocation() {
        assertEquals(listOf("Desktop task"), ChatLibrary.chats(listOf(phone, imported), bindings, sections, query = "WEB").map { it.entry.title })
        assertEquals(listOf("Server task"), ChatLibrary.chats(listOf(phone, imported), bindings, sections, query = "server").map { it.entry.title })
        assertTrue(ChatLibrary.chats(listOf(phone, imported), bindings, sections, ChatLibrary.PHONE, query = "server").isEmpty())
        assertEquals(listOf("web"), ChatLibrary.projects(sections, "desk", "Desktop task").map { it.name })
    }

    @Test fun pinnedShowsOnlyPinnedChatsAndProjectsFromEveryPlace() {
        val web = sections.first().projects.first { it.name == "web" }
        val pinnedChat = ChatLibrary.chats(listOf(phone, imported), bindings, sections).first { it.entry.title == "Server task" }
        val pins = setOf(pinnedChat.key, "this-phone:chat-phone", web.key)
        val rows = ChatLibrary.chats(listOf(phone, imported), bindings, sections, ChatLibrary.PINNED, pins = pins)
        assertEquals(setOf("Server task", "Phone task"), rows.map { it.entry.title }.toSet())
        assertEquals(listOf("web"), ChatLibrary.projects(sections, ChatLibrary.PINNED, "", pins).map { it.name })
        assertTrue(ChatLibrary.chats(listOf(phone, imported), bindings, sections, ChatLibrary.PINNED).isEmpty())
        assertTrue(ChatLibrary.projects(sections, ChatLibrary.PINNED, "").isEmpty())
    }
}
