package dev.androidagent.app.ui

import dev.androidagent.core.ChatSession
import dev.androidagent.core.EngineKind
import dev.androidagent.remote.ComputerConversation as CodexThread
import dev.androidagent.remote.RemoteBinding
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteProject
import org.junit.Assert.assertEquals
import org.junit.Test

class PcChatsTest {
    private val desk = RemoteComputer(id = "desk", label = "Desk", host = "", vpnHost = "100.64.0.5", user = "yoni")
    private val phoneChat = ChatSession("p1", "Photos", 1, 10)
    private val deskChat = ChatSession("d1", "Fix picker", 1, 50)

    @Test fun anotherMikesNameUpdatesAnAlreadyImportedChatAndSearch() {
        val session = deskChat.copy(title = "[Trusted Android Agent runtime context]", engineThreadId = "t1")
        val binding = RemoteBinding("desk", "C:\\src\\app", threadId = "t1")
        val named = CodexThread("t1", "תיקון שמות סשנים", binding.cwd, 50, name = "תיקון שמות סשנים")
        val sections = PcChats.sections(listOf(desk), "desk", emptyList(), mapOf("d1" to binding),
            listOf(session), mapOf("desk" to listOf(named)), query = "שמות")
        assertEquals("תיקון שמות סשנים", sections.single().projects.single().chats.single().title)
        assertEquals("תיקון שמות סשנים", PcChats.title(session, binding, listOf(named)))
        assertEquals(session.title, PcChats.title(session, binding, listOf(named.copy(name = null))))
        assertEquals(session.title, PcChats.title(session, binding.copy(threadId = "another"), listOf(named)))
    }

    @Test fun chatsGroupUnderTheirFolderAndPcOnlyThreadsJoinThem() {
        val sections = PcChats.sections(
            computers = listOf(desk),
            defaultComputerId = "desk",
            projects = listOf(RemoteProject("desk", "C:\\src\\empty")),
            bindings = mapOf("d1" to RemoteBinding("desk", "C:\\src\\app", threadId = "t1")),
            sessions = listOf(phoneChat, deskChat),
            threads = mapOf(
                "desk" to listOf(
                    // Already followed by d1: not listed twice.
                    CodexThread("t1", "Fix picker", "C:\\src\\app", 50),
                    // Same folder, spelled differently by Windows.
                    CodexThread("t2", "Review PR", "c:\\SRC\\app\\", 70),
                    CodexThread("t3", "Site copy", "C:\\web", 20),
                ),
            ),
        )
        val projects = sections.single().projects
        assertEquals(listOf("app", "web", "empty"), projects.map { it.name })
        assertEquals(listOf("thread-CODEX:t2", "chat-d1"), projects[0].chats.map { it.key })
        assertEquals(emptyList<PcChatEntry>(), projects[2].chats)
        assertEquals(listOf(phoneChat), PcChats.phoneSessions(listOf(phoneChat, deskChat), mapOf("d1" to RemoteBinding("desk", "C:\\src\\app"))))
    }

    @Test fun searchKeepsMatchingChatsAndMatchingProjects() {
        val sections = PcChats.sections(
            listOf(desk), "desk", listOf(RemoteProject("desk", "C:\\web")), emptyMap(), emptyList(),
            mapOf("desk" to listOf(CodexThread("t2", "Review PR", "C:\\src\\app", 70))),
            query = "web",
        )
        assertEquals(listOf("web"), sections.single().projects.map { it.name })
    }

    @Test fun aVpnOnlyComputerHasOneAddress() {
        assertEquals(listOf("100.64.0.5"), desk.hosts)
        assertEquals("yoni@100.64.0.5", desk.address)
    }

    @Test fun providerIdsDoNotHideEachOtherInTheSameProject() {
        val binding = RemoteBinding("desk", "C:\\src", threadId = "same-id")
        val rows = listOf(CodexThread("same-id", "Codex", binding.cwd, 5),
            CodexThread("same-id", "Claude", binding.cwd, 60, engine = EngineKind.CLAUDE))
        val project = PcChats.sections(listOf(desk), "desk", emptyList(), mapOf("d1" to binding),
            listOf(deskChat), mapOf("desk" to rows)).single().projects.single()
        assertEquals(listOf("thread-CLAUDE:same-id", "chat-d1"), project.chats.map { it.key })
    }
}
