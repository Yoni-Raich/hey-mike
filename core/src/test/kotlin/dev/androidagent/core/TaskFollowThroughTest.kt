package dev.androidagent.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskFollowThroughTest {
    private var clock = 1_000_000L
    private val ref = TaskSourceRef("chat", sessionId = "owner", verified = true)
    private suspend fun board(store: TaskBoardStore = InMemoryTaskBoardStore()) = TaskBoard(store, now = { clock }).apply { load() }
    private suspend fun card(board: TaskBoard) = (board.propose(TaskProposal("Dana form", "Due tomorrow", "Prepare form",
        sourceRefs = listOf(ref)), "conversation", "owner") as ProposeOutcome.Created).task

    @Test fun takingOwnershipSurvivesRestartAndDoesNotExecute() = runTest {
        val store = InMemoryTaskBoardStore(); val board = board(store); val task = card(board)
        board.decide(task.id, "mine", "tap", until = clock + 7_200_000)
        val reopened = board(store).task(task.id)!!
        assertEquals("user", reopened.commitment!!.owner)
        assertEquals(clock + 7_200_000, reopened.commitment.nextCheckAt)
        assertNull(reopened.execution)
        assertFalse(TaskFollowThrough.needsBrief(reopened, clock))
        clock += 7_200_000
        assertTrue(TaskFollowThrough.needsBrief(reopened, clock))
    }

    @Test fun evidenceNeverChangesApprovalOrClosesAnOwnedCommitment() = runTest {
        val board = board(); val task = card(board)
        board.decide(task.id, "mine", "chat")
        val before = board.task(task.id)!!
        val after = board.observe(task.id, listOf(TaskEvidence("chat", "Sent it")), "The message says sent", listOf(ref))
        assertEquals(before.decision, after.decision); assertEquals(before.plan, after.plan)
        assertEquals(TaskStatus.MINE, after.status)
        assertNotNull(after.commitment!!.completionEvidence)
        assertTrue(runCatching { board.update(task.id, why = "Execute a different plan") }.isFailure)
    }

    @Test fun briefingReceiptIsStableAcrossRestartAndBookkeeping() = runTest {
        val store = InMemoryTaskBoardStore(); val board = board(store); val task = card(board)
        val first = board.prepareBriefing(task.id, "The form is due tomorrow. Shall I draft it?", listOf(ref))
        board.delivered(task.id, first.briefing!!.id, "chat")
        val reopened = board(store)
        assertFalse(TaskFollowThrough.needsBrief(reopened.task(task.id)!!, clock))
        assertEquals(first.briefing.id, reopened.prepareBriefing(task.id, "Do not resend", listOf(ref)).briefing!!.id)
        assertNotNull(reopened.task(task.id)!!.briefing!!.deliveredAt)
        reopened.decide(task.id, "done", "tap")
        assertNull(TaskFollowThrough.occurrence(reopened.task(task.id)!!, clock))
    }

    @Test fun completionEvidenceStopsPeriodicChecksAndNextTimeIsUserControlled() = runTest {
        val board = board(); val task = card(board); board.decide(task.id, "mine", "tap")
        val after = board.observe(task.id, listOf(TaskEvidence("chat", "Done")), "Possibly done", listOf(ref))
        val brief = board.prepareBriefing(task.id, "Looks complete, please confirm.", listOf(ref))
        board.delivered(task.id, brief.briefing!!.id, "chat")
        clock += 5 * TaskFollowThrough.DAY
        assertFalse(TaskFollowThrough.needsBrief(board.task(task.id)!!, clock))
        assertTrue(runCatching { board.checkLater(task.id, clock - 1) }.isFailure)
        assertEquals(clock + TaskFollowThrough.DAY, board.checkLater(after.id, clock + TaskFollowThrough.DAY).commitment!!.nextCheckAt)
    }

    @Test fun captureDistinguishesOwnerInputFromAutomatedMessages() = runTest {
        assertTrue(TaskFollowThrough.captureBoardMessage("user", true, false))
        assertFalse(TaskFollowThrough.captureBoardMessage("assistant", true, false))
        assertFalse(TaskFollowThrough.captureBoardMessage("system", true, false))
        assertFalse(TaskFollowThrough.captureBoardMessage("user", false, false))
        assertTrue(TaskFollowThrough.captureBoardMessage("assistant", false, true))
        val store = InMemoryTaskBoardStore()
        val signal = TaskSignal("owner-1", "chat", text = "No work calls on Friday", at = clock, capturedAt = clock,
            sessionId = "owner", sourceVerified = true, ownerFeedback = true)
        store.capturePage("messages", 0, 1, listOf(signal))
        store.deleteSignals(listOf(signal.id))
        assertEquals(signal.text, store.loadEvents().single().note)
        store.capturePage("messages", 1, 2, listOf(signal))
        assertEquals(1, store.loadEvents().size)
        assertNull(TaskFollowThrough.feedback(signal.copy(source = "whatsapp")))
    }

    @Test fun personalContextRespectsExclusionsAndFindsRelevantMemories() {
        val notes = listOf(
            MemoryNote("a", "Dana", "fact", "Needs signed form", source = "chat", at = 1),
            MemoryNote("b", "User", "preference", "Short replies", source = "chat", at = 2),
            MemoryNote("c", "Dana", "fact", "Excluded secret", source = "whatsapp", at = 3),
            MemoryNote("d", "Dana", "fact", "Old form", source = "chat", at = 4, retiredAt = 5))
        val found = TaskFollowThrough.context(notes, "Dana form") { it.source != "whatsapp" }
        assertEquals(listOf("a", "b"), found.map { it.id })
    }

    @Test fun engineOverrideCannotBorrowAnotherEnginesModel() {
        val parent = ChatSession("p", "Manager", 0, 0, model = "codex-model", reasoningEffort = "high")
        assertEquals("codex-model", TaskFollowThrough.executionProfile(null, parent).model)
        val plan = ApprovedTaskPlan(1, "hash", "title", "plan", engine = EngineKind.CLAUDE)
        val chosen = TaskFollowThrough.executionProfile(plan, parent)
        assertEquals(EngineKind.CLAUDE, chosen.engine); assertNull(chosen.model); assertNull(chosen.effort)
    }

    @Test fun realDeadlineIsSeparateFromProposalExpiry() {
        val task = MikeTask("a", "t", "w", "p", originKind = "chat", topicKey = "a", createdAt = clock, updatedAt = clock,
            usefulUntil = clock + 60_000)
        val commitment = TaskFollowThrough.accepted(task, "user", clock)
        assertNull(commitment.deadlineAt)
        assertEquals(clock + TaskFollowThrough.DAY, commitment.nextCheckAt)
    }

    @Test fun candidateFilterMustPassBothExamplesAndCounterexamples() {
        val filter = TaskSuggestionFilter(kind = "delivery", examples = listOf(
            TaskSuggestionFilter.Example("Parcel update", "delivery", ignored = true),
            TaskSuggestionFilter.Example("Appointment", "meeting", ignored = false)))
        filter.check()
        assertTrue(runCatching { filter.copy(kind = "meeting").check() }.isFailure)
        assertTrue(runCatching { filter.copy(examples = filter.examples.take(1)).check() }.isFailure)
        assertTrue(runCatching { filter.copy(kind = null).check() }.isFailure)
    }

    @Test fun generatedContextCannotChangeFollowupTimeOrApproveAPlan() = runTest {
        val board = board(); val task = card(board); board.decide(task.id, "mine", "tap")
        val tools = TaskTools(board, MemoryBook(InMemoryTaskBoardStore()), TeamRoster(InMemoryTaskBoardStore()),
            roleOf = { TaskChatRole.MANAGER }, roleChat = { "unused" }, now = { clock })
        val result = tools.invoke("manager", buildJsonObject {
            put("mode", "followup"); put("id", task.id); put("until", "2027-01-01T00:00:00Z")
        }, fromUser = false)
        assertFalse(result.success)
        assertEquals(TaskStatus.MINE, board.task(task.id)!!.status)
    }

    @Test fun frozenExecutionModelSurvivesSettingsChangesAndEvidence() = runTest {
        val board = board(); val task = card(board)
        board.editSettings { it.copy(executionEngine = EngineKind.CLAUDE, executionModel = "selected-model", executionEffort = "high") }
        var started: MikeTask? = null
        board.executor = object : TaskExecutor {
            override suspend fun start(task: MikeTask, attempt: Int): TaskExecution {
                started = task
                board.editSettings { it.copy(executionModel = "later-model") }
                return TaskExecution(task.execution!!.requestId, startedAt = clock)
            }
            override suspend fun continueWork(task: MikeTask, message: String) = Unit
            override suspend fun stop(task: MikeTask) = Unit
            override suspend fun apply(task: MikeTask) = Unit
        }
        board.decide(task.id, "do", "tap")
        val snapshot = board.task(task.id)!!.execution!!.approvedPlan
        assertEquals("selected-model", snapshot!!.model)
        board.observe(task.id, listOf(TaskEvidence("chat", "The form changed")), null, listOf(ref))
        assertEquals(snapshot, board.task(task.id)!!.execution!!.approvedPlan)
        assertEquals("selected-model", started!!.execution!!.approvedPlan!!.model)
    }

    @Test fun learnedFilterActuallySuppressesOnlyMatchingScoutProposals() = runTest {
        val home = java.nio.file.Files.createTempDirectory("filters-").toFile()
        try {
            val book = TaskPlaybook(home).apply { seed() }
            val filter = TaskSuggestionFilter(kind = "delivery", examples = listOf(
                TaskSuggestionFilter.Example("Parcel update", "delivery", ignored = true),
                TaskSuggestionFilter.Example("Dana form", "reply", ignored = false)))
            val ruleText = "```mike-filter\n${Json.encodeToString(TaskSuggestionFilter.serializer(), filter)}\n```"
            book.writeLearned("kinds/delivery.md", TaskSuggestionFilter.stamp(ruleText, book.read("user.md")), listOf(ref))
            val store = InMemoryTaskBoardStore(); val board = board(store)
            val tools = TaskTools(board, MemoryBook(store), TeamRoster(store), { TaskChatRole.SCOUT }, { "none" }, playbook = book)
            fun args(kind: String) = buildJsonObject { put("mode", "propose"); put("title", "Useful request"); put("why", "Now"); put("plan", "Prepare it"); put("kind", kind)
                put("evidence", buildJsonArray { add(buildJsonObject { put("source", "chat"); put("detail", "User requested preparation") }) }) }
            assertFalse(tools.invoke("scout", args("delivery"), false).success)
            val accepted = tools.invoke("scout", args("reply"), false)
            assertTrue(accepted.text, accepted.success)
            assertEquals(1, board.tasks.value.size)
            java.io.File(book.directory, "user.md").appendText("\nShow parcel tasks again.\n")
            val afterOwnerCorrection = tools.invoke("scout", args("delivery"), false)
            assertTrue(afterOwnerCorrection.text, afterOwnerCorrection.success)
        } finally { home.deleteRecursively() }
    }
}
