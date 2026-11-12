package dev.androidagent.core

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManagerBriefingDeliveryTest {
    private val clock = 200_000L
    private val ref = TaskSourceRef("chat", verified = true)

    private suspend fun board(store: InMemoryTaskBoardStore) = TaskBoard(store, now = { clock }).apply { load() }

    private suspend fun add(store: InMemoryTaskBoardStore, id: String, created: Long) {
        store.saveTask(MikeTask(id, id, "why", "plan", originKind = "chat", topicKey = id,
            createdAt = created, updatedAt = created, status = TaskStatus.MINE,
            commitment = TaskCommitment("user", 100_000), sourceRefs = listOf(ref)))
    }

    private fun delivery(board: TaskBoard, chat: FakeChat,
        quiet: suspend (MikeTask) -> Long? = { null },
        notify: suspend (String, String, String, String) -> Pair<String, String> = { _, _, _, _ -> "posted" to "posted" },
        failure: (Exception) -> Unit = {}) = ManagerBriefingDelivery(board = board, sessions = chat, now = { clock }, active = { true },
        taskAllowed = { task -> task.sourceRefs.all { TaskSourcePolicy.allows(it, board.settings.value) } },
        sourceAllowed = { TaskSourcePolicy.allows(it, board.settings.value) }, quietUntil = quiet,
        recorded = {}, notify = notify, onFailure = failure)

    @Test fun failedItemDoesNotBlockNextEligibleItem() = runTest {
        val store = InMemoryTaskBoardStore()
        add(store, "bad", 2); add(store, "good", 1)
        val board = board(store)
        board.prepareBriefing("bad", "Bad message", emptyList())
        board.prepareBriefing("good", "Good message", emptyList())
        val chat = FakeChat().apply { failTitle = "bad" }
        val notices = mutableListOf<String>()
        var errors = 0
        delivery(board, chat, notify = { _, title, _, _ -> notices += title; "posted" to "posted" },
            failure = { errors++ }).deliver("manager")
        assertEquals(1, errors)
        assertEquals(listOf("good"), notices)
        assertNull(board.task("bad")!!.briefing!!.deliveredAt)
        assertEquals("posted", board.task("good")!!.briefing!!.notificationState)
        assertEquals("good\n\nGood message", chat.saved.single().text)
    }

    @Test fun aTeamStatusBriefingIsSavedOnceAndNeverPostedWhileTheUsersFollowUpStillIs() = runTest {
        val store = InMemoryTaskBoardStore()
        add(store, "follow-up", 1)
        // A card the team is waiting on, with no follow-up time of the user's: its briefing is a status update.
        store.saveTask(MikeTask("status", "status", "why", "plan", originKind = "chat", topicKey = "status",
            createdAt = 2, updatedAt = 2, status = TaskStatus.WAITING, sourceRefs = listOf(ref)))
        val board = board(store)
        board.prepareBriefing("follow-up", "Time to check", emptyList())
        board.prepareBriefing("status", "Still waiting for a reply", emptyList())
        assertFalse(TaskBriefingDelivery.reminder(board.task("status")!!.briefing!!))
        assertTrue(TaskBriefingDelivery.reminder(board.task("follow-up")!!.briefing!!))
        val chat = FakeChat()
        val posted = mutableListOf<String>()
        repeat(3) { delivery(board, chat, notify = { _, title, _, _ -> posted += title; "posted" to "posted" }).deliver("manager") }
        assertEquals(listOf("follow-up"), posted)
        assertEquals(2, chat.saved.size)
        val status = board.task("status")!!.briefing!!
        assertNotNull(status.deliveredAt)
        assertNull(status.notificationAttemptedAt)
        assertEquals(ManagerBriefingDelivery.CHAT_ONLY, status.notificationState)
        // After a restart there is nothing left to post or retry.
        delivery(board(store), chat, notify = { _, title, _, _ -> posted += title; "posted" to "posted" }).deliver("manager")
        assertEquals(listOf("follow-up"), posted)
        assertEquals(2, chat.saved.size)
    }

    @Test fun sourceAndCardRevokedBeforeGateNeverAppend() = runTest {
        for (revokeSource in listOf(true, false)) {
            val store = InMemoryTaskBoardStore(); add(store, "card", 1)
            val board = board(store); board.prepareBriefing("card", "Review this", emptyList())
            val chat = FakeChat()
            val before = board.settings.value.policyRevision
            delivery(board, chat, quiet = {
                if (revokeSource) board.editSettings { settings -> settings.copy(readChats = false) }
                else board.decide("card", "done", "tap")
                null
            }).deliver("manager")
            if (revokeSource) assertTrue(board.settings.value.policyRevision > before)
            assertTrue(chat.saved.isEmpty())
            assertEquals("cancelled", board.task("card")!!.briefing!!.notificationState)
        }
    }

    @Test fun interruptedInsertReplaysSameIdAndContentAndUnknownNoticeIsNotRetried() = runTest {
        val store = InMemoryTaskBoardStore(); add(store, "card", 1)
        var board = board(store); val brief = board.prepareBriefing("card", "Review this", emptyList()).briefing!!
        val chat = FakeChat().apply { crashAfterInsert = true }
        var calls = 0
        delivery(board, chat, notify = { _, _, _, _ -> calls++; throw IllegalStateException("OS result unknown") }).deliver("manager")
        assertNull(board.task("card")!!.briefing!!.chatSavedAt)
        board = board(store)
        delivery(board, chat, notify = { _, _, _, _ -> calls++; throw IllegalStateException("OS result unknown") }).deliver("manager")
        assertEquals(1, calls)
        assertEquals(2, chat.attempted.size)
        assertEquals(chat.attempted[0].id, chat.attempted[1].id)
        assertEquals(chat.attempted[0].text, chat.attempted[1].text)
        assertEquals("manager-brief:${brief.id}", chat.saved.single().id)
        board = board(store)
        delivery(board, chat, notify = { _, _, _, _ -> calls++; "posted" to "posted" }).deliver("manager")
        assertEquals(1, calls)
        assertEquals("unknown", board.task("card")!!.briefing!!.notificationState)
    }

    @Test fun policyEditWaitsForInProgressGatedAppend() = runTest {
        val store = InMemoryTaskBoardStore(); add(store, "card", 1)
        val board = board(store); board.prepareBriefing("card", "Review this", emptyList())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val chat = FakeChat().apply { beforeInsert = { entered.complete(Unit); release.await() } }
        val work = async { delivery(board, chat, quiet = { null }).deliver("manager") }
        entered.await()
        val policy = async { board.editSettings { it.copy(readChats = false) } }
        runCurrent()
        assertFalse(policy.isCompleted)
        release.complete(Unit)
        policy.await(); work.await()
        assertEquals(1, chat.saved.size)
        assertFalse(board.settings.value.readChats)
    }

    private class FakeChat : SessionStore {
        override val sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        val saved = mutableListOf<ChatMessage>()
        val attempted = mutableListOf<ChatMessage>()
        var failTitle: String? = null
        var crashAfterInsert = false
        var beforeInsert: suspend () -> Unit = {}
        override suspend fun appendOnce(message: ChatMessage) {
            attempted += message
            if (message.text.startsWith("$failTitle\n\n")) error("fixture failure")
            beforeInsert()
            val existing = saved.firstOrNull { it.id == message.id }
            if (existing != null) check(existing.sessionId == message.sessionId && existing.role == message.role && existing.text == message.text)
            else saved += message
            if (crashAfterInsert) { crashAfterInsert = false; error("crash after insert") }
        }
        override fun messages(sessionId: String) = flowOf(saved.toList())
        override suspend fun createSession(engine: EngineKind): ChatSession = error("unused")
        override suspend fun getSession(id: String): ChatSession? = null
        override suspend fun append(message: ChatMessage) = error("unused")
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(".")
    }
}
