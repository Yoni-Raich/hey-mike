package dev.androidagent.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskSafetyTest {
    private val proposal = TaskProposal("Prepare a draft", "A request arrived", "Write a draft for review",
        evidence = listOf(TaskEvidence("chat", "Please prepare a draft")))

    @Test fun uncertainStartKeepsItsKeyAndCannotBeReplayedAfterRestart() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store).apply { load() }
        var calls = 0
        board.executor = object : TaskExecutor {
            override suspend fun start(task: MikeTask, attempt: Int): TaskExecution { calls++; error("Reply lost after dispatch") }
            override suspend fun continueWork(task: MikeTask, message: String) = Unit
            override suspend fun stop(task: MikeTask) = Unit
            override suspend fun apply(task: MikeTask) = Unit
        }
        val card = (board.propose(proposal, "signal", "scout") as ProposeOutcome.Created).task
        val unknown = board.decide(card.id, "do", "tap")
        assertEquals(TaskStatus.UNKNOWN, unknown.status)
        assertEquals("${card.id}#1", unknown.execution!!.requestId)
        val restored = TaskBoard(store).apply { load(); executor = board.executor }
        try { restored.decide(card.id, "do", "tap"); fail("An uncertain start must not retry") } catch (_: IllegalStateException) {}
        assertEquals(1, calls)
    }

    @Test fun stopDuringStartCancelsTheLateChildAndWaitsForItsTerminalReceipt() = runTest {
        val board = TaskBoard(InMemoryTaskBoardStore()).apply { load() }
        val receipt = CompletableDeferred<TaskExecution>()
        val stops = mutableListOf<String?>()
        board.executor = object : TaskExecutor {
            override suspend fun start(task: MikeTask, attempt: Int) = receipt.await()
            override suspend fun continueWork(task: MikeTask, message: String) = Unit
            override suspend fun stop(task: MikeTask) { stops += task.execution?.childTaskId }
            override suspend fun apply(task: MikeTask) = Unit
        }
        val card = (board.propose(proposal, "signal", "scout") as ProposeOutcome.Created).task
        val starting = async { board.decide(card.id, "do", "tap") }
        runCurrent()
        assertEquals(TaskStatus.UNKNOWN, board.decide(card.id, "stop", "tap").status)
        receipt.complete(TaskExecution("${card.id}#1", childTaskId = "child", sessionId = "chat", startedAt = 1))
        starting.await()
        assertEquals("child", stops.last())
        assertEquals(TaskStatus.UNKNOWN, board.task(card.id)!!.status)
        board.syncChildren(listOf(SessionAgentTask("child", "${card.id}#1", "manager", "Draft", "draft", EngineKind.CODEX,
            createdAt = 1, sessionId = "chat", status = "cancelled")))
        assertEquals(TaskStatus.STOPPED, board.task(card.id)!!.status)
    }

    @Test fun processedNotificationCannotReturnWithItsNextRepostedBundle() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store).apply { load() }
        val message = CapturedMessage("com.whatsapp", "Dana", "Dana", "Prepare the draft", 100, false)
        val settings = TaskBoardSettings(discoveryOn = true)
        val signal = inbox.capture(message, settings, "mike")!!
        inbox.processed(listOf(signal.id))
        val restarted = SignalInbox(store).apply { load() }
        assertNull(restarted.capture(message, settings, "mike"))
        assertTrue(restarted.pending.value.isEmpty())
    }

    @Test fun learnedFilesCannotEscapeOrReplaceTheUsersRules() {
        val home = Files.createTempDirectory("mike-playbook-test").toFile()
        try {
            val book = TaskPlaybook(home).apply { seed(); addUserRule("Keep my rule") }
            for (path in listOf("../auth.json", "user.md", "sources/../../auth.json", "/tmp/file", "sources\\x.md")) {
                try { book.writeLearned(path, "unsafe"); fail(path) } catch (_: IllegalArgumentException) {}
            }
            book.writeLearned("sources/chat.md", "Keep useful requests. Evidence: card-1.")
            assertTrue(book.read("sources/chat.md").contains("card-1"))
            assertTrue(book.userRules().contains("Keep my rule"))
            assertFalse(File(home, "auth.json").exists())
        } finally { home.deleteRecursively() }
    }

    @Test fun trustedHomeIsNormalizedBeforeCheckingLearnedPaths() {
        val home = Files.createTempDirectory("mike-playbook-home").toFile()
        try {
            File(home, "parent").mkdirs()
            val book = TaskPlaybook(File(home, "parent/../parent")).apply { seed() }
            book.writeLearned("sources/chat.md", "Keep useful requests.")
            assertEquals("Keep useful requests.", book.read("sources/chat.md"))
            assertTrue(book.list().contains("sources/chat.md"))
        } finally { home.deleteRecursively() }
    }
}
