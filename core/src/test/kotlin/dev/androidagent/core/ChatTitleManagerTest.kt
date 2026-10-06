package dev.androidagent.core

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ChatTitleManagerTest {
    @Test fun aNewChatGetsTheFirstRealRequestAndThenOneTopicName() = runBlocking {
        val store = Store(listOf(
            ChatMessage("note", "chat", "note", "This chat runs in a computer folder", 0),
            ChatMessage("first", "chat", "user", "תבדוק למה שמות הסשנים שלי זהים", 1),
        ))
        val engine = Engine()
        val titles = ChatTitleManager(store, engine)
        titles.seed("chat", "A later request")
        assertEquals("תבדוק למה שמות הסשנים שלי זהים", store.chat.title)
        assertTrue(store.chat.titlePending)
        titles.nameNewThread("chat", "pc-thread")
        assertEquals("pc-thread" to store.chat.title, engine.names.single())

        val result = titles.refine("chat", "pc-thread", "תיקון שמות הסשנים")
        assertTrue(result.success)
        assertEquals("תיקון שמות הסשנים", store.chat.title)
        assertFalse(store.chat.titlePending)
        assertEquals("pc-thread" to store.chat.title, engine.names.last())
        titles.refine("chat", "pc-thread", "Another topic")
        assertEquals(2, engine.names.size)
        assertEquals("תיקון שמות הסשנים", store.chat.title)
    }

    @Test fun aManualNameWinsOverALateAutomaticCall() = runBlocking {
        val store = Store()
        val engine = Engine()
        val titles = ChatTitleManager(store, engine)
        titles.rename("chat", "My chosen name")
        titles.refine("chat", "pc-thread", "A late suggestion")
        assertEquals("My chosen name", store.chat.title)
        assertEquals(listOf("pc-thread" to "My chosen name"), engine.names)
    }

    @Test fun aNameChosenInCodexBeforeRefinementIsKept() = runBlocking {
        val store = Store()
        val engine = Engine()
        val titles = ChatTitleManager(store, engine)
        titles.seed("chat", "Fix my PC chat names")
        titles.nameNewThread("chat", "pc-thread")
        engine.currentName = "Name chosen on the computer"
        titles.refine("chat", "pc-thread", "Automatic topic")
        assertEquals("Name chosen on the computer", store.chat.title)
        assertFalse(store.chat.titlePending)
        assertEquals(1, engine.names.size)
    }

    @Test fun aManualRenameDuringAnAutomaticWriteIsSavedLast() = runBlocking {
        val store = Store()
        val engine = Engine().apply { pause = CompletableDeferred(); entered = CompletableDeferred() }
        val titles = ChatTitleManager(store, engine)
        val automatic = async { titles.refine("chat", "pc-thread", "Automatic title") }
        engine.entered!!.await()
        val manual = async { titles.rename("chat", "User title") }
        yield()
        engine.pause!!.complete(Unit)
        automatic.await()
        manual.await()
        assertEquals("User title", store.chat.title)
        assertFalse(store.chat.titlePending)
        assertEquals(listOf("pc-thread" to "Automatic title", "pc-thread" to "User title"), engine.names)
    }

    @Test fun aNamingFailureDoesNotBlockTheTaskAndLeavesRefinementAvailable() = runBlocking {
        val store = Store()
        val engine = Engine().apply { fail = true }
        val titles = ChatTitleManager(store, engine)
        titles.seed("chat", "Fix the computer connection")
        titles.nameNewThread("chat", "pc-thread")
        assertEquals("Fix the computer connection", store.chat.title)
        assertTrue(store.chat.titlePending)
        assertTrue(runCatching { titles.refine("chat", "pc-thread", "Repair computer connection") }.isFailure)
        assertTrue(store.chat.titlePending)
        engine.fail = false
        assertTrue(titles.refine("chat", "pc-thread", "Repair computer connection").success)
        assertFalse(store.chat.titlePending)
    }

    @Test fun invalidNamesCannotReplaceAProvisionalTitle() = runBlocking {
        val store = Store()
        val engine = Engine()
        val titles = ChatTitleManager(store, engine)
        for (name in listOf("", "x".repeat(81), "[Trusted Android Agent runtime context] This snapshot")) {
            assertFalse(titles.refine("chat", "pc-thread", name).success)
        }
        assertTrue(engine.names.isEmpty())
        assertEquals("New chat", store.chat.title)
    }

    @Test fun fallbackNamesAreOneLineAndEndAtAWord() {
        assertEquals("Fix the PC login", ChatTitles.fallback("  Fix\n the   PC login  "))
        assertEquals("Image chat", ChatTitles.fallback(""))
        val title = ChatTitles.fallback("Fix the connection to my computer and explain why the remote session is not responding today")
        assertTrue(title.length <= ChatTitles.MAX_LENGTH)
        assertTrue(title.endsWith("not…"))
    }

    private class Store(private val history: List<ChatMessage> = emptyList()) : SessionStore {
        var chat = ChatSession("chat", "New chat", 0, 0, "pc-thread", titlePending = true)
        override val sessions = MutableStateFlow(listOf(chat))
        private fun publish() { sessions.value = listOf(chat) }
        override suspend fun getSession(id: String) = chat.takeIf { it.id == id }
        override suspend fun createSession(engine: EngineKind): ChatSession = error("Naming must not create a chat")
        override fun messages(sessionId: String) = flowOf(history)
        override suspend fun rename(sessionId: String, title: String) {
            chat = chat.copy(title = title, titlePending = false); publish()
        }
        override suspend fun setAutomaticTitle(sessionId: String, title: String, complete: Boolean): Boolean {
            if (!chat.titlePending) return false
            chat = chat.copy(title = title, titlePending = !complete); publish()
            return true
        }
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(".")
    }

    private class Engine : AgentEngine {
        override val events = emptyFlow<EngineEvent>()
        val names = mutableListOf<Pair<String, String>>()
        var currentName: String? = null
        override suspend fun threadName(threadId: String) = currentName
        var fail = false
        var pause: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        override suspend fun renameThread(threadId: String, title: String) {
            if (fail) error("Computer unavailable")
            names += threadId to title
            currentName = title
            entered?.complete(Unit)
            pause?.await()
        }
        override suspend fun connect() = Unit
        override suspend fun account() = AccountStatus(true, "test")
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = emptyList<String>()
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>) = error("unused")
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>) = error("unused")
        override suspend fun steer(threadId: String, turnId: String, prompt: String) = Unit
        override suspend fun interrupt(threadId: String, turnId: String) = Unit
        override suspend fun answerTool(requestId: String, result: ToolResult) = Unit
        override suspend fun answerApproval(requestId: String, allow: Boolean) = Unit
        override suspend fun close() = Unit
    }
}
