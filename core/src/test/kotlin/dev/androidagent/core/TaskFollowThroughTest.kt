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

    @Test fun dueCheckIsDistinctFromUnchangedWaitingStatus() {
        val task = MikeTask("a", "form", "why", "plan", originKind = "chat", topicKey = "a", createdAt = clock, updatedAt = clock,
            status = TaskStatus.WAITING, commitment = TaskCommitment("user", clock + 60_000))
        val first = TaskFollowThrough.occurrence(task, clock)!!
        val alreadyBriefed = task.copy(briefing = TaskBriefing("id", first, "waiting", clock, listOf(ref)))
        assertFalse(TaskFollowThrough.needsBrief(alreadyBriefed, clock))
        assertTrue(TaskFollowThrough.needsBrief(alreadyBriefed, clock + 60_000))
    }

    @Test fun unrelatedProjectPreferenceDoesNotEnterOwnerContext() {
        val notes = listOf(MemoryNote("opus", "children story", "preference", "Use Opus for this story", source = "chat", at = 1),
            MemoryNote("dana", "Dana", "fact", "Signed form", source = "chat", at = 2))
        assertEquals(listOf("dana"), TaskFollowThrough.context(notes, "Dana form") { true }.map { it.id })
    }

    @Test fun explicitCheckRearmsAcrossRestartAndClose() = runTest {
        val store = InMemoryTaskBoardStore()
        val original = MikeTask("a", "form", "why", "plan", originKind = "chat", topicKey = "a",
            createdAt = clock, updatedAt = clock, status = TaskStatus.WAITING,
            commitment = TaskCommitment("user", clock + 60_000))
        store.saveTask(original)
        var current = board(store)
        val first = current.prepareBriefing("a", "Waiting for an answer", listOf(ref))
        current.delivered("a", first.briefing!!.id, "saved")
        val due = clock + 120_000
        current.checkLater("a", due)
        val revision = current.task("a")!!.commitment!!.checkRevision
        current.checkLater("a", due)
        assertEquals(revision, current.task("a")!!.commitment!!.checkRevision)
        current = board(store)
        clock = due
        assertTrue(TaskFollowThrough.needsBrief(current.task("a")!!, clock))
        val second = current.prepareBriefing("a", "Check the answer", listOf(ref))
        assertNotEquals(first.briefing!!.id, second.briefing!!.id)
        current.delivered("a", second.briefing!!.id, "saved")
        assertFalse(TaskFollowThrough.needsBrief(board(store).task("a")!!, clock))
        current.checkLater("a", clock + 120_000)
        assertFalse(TaskFollowThrough.needsBrief(current.task("a")!!, clock))
        clock += 120_000
        assertTrue(TaskFollowThrough.needsBrief(board(store).task("a")!!, clock))
        store.saveTask(current.task("a")!!.copy(status = TaskStatus.DONE))
        assertNull(TaskFollowThrough.occurrence(board(store).task("a")!!, clock))
    }

    @Test fun localDueChecksBypassReviewLimitsAndKeepReceiptsWhenClockMovesBack() = runTest {
        val store = InMemoryTaskBoardStore()
        val statuses = listOf(TaskStatus.WAITING, TaskStatus.REVIEW, TaskStatus.FAILED, TaskStatus.UNKNOWN)
        statuses.forEachIndexed { i, status ->
            store.saveTask(MikeTask("task$i", "form", "why", "plan", originKind = "chat", topicKey = "task$i",
                createdAt = clock, updatedAt = clock, status = status,
                commitment = TaskCommitment("user", clock + 60_000)))
        }
        var current = board(store)
        val beforeDue = clock
        clock += 60_000
        // Local selection has no AI quota, daily cap, review interval or network input.
        val due = TaskFollowThrough.dueChecks(current.tasks.value, clock)
        assertEquals(statuses.size, due.size)
        due.forEach { task ->
            val prepared = current.prepareBriefing(task.id, "Time to check", listOf(ref))
            current.delivered(task.id, prepared.briefing!!.id, "saved")
        }
        current = board(store)
        assertTrue(TaskFollowThrough.dueChecks(current.tasks.value, clock).isEmpty())
        // A backward wall-clock change can make a status current again. Its receipt
        // must never overwrite the already handled check occurrence.
        clock = beforeDue
        due.forEach { task ->
            val statusBrief = current.prepareBriefing(task.id, "Still waiting", listOf(ref))
            current.delivered(task.id, statusBrief.briefing!!.id, "saved")
        }
        clock += 60_000
        current = board(store)
        assertTrue(TaskFollowThrough.dueChecks(current.tasks.value, clock).isEmpty())
        val closed = current.task("task0")!!.copy(status = TaskStatus.DONE)
        store.saveTask(closed.copy(commitment = closed.commitment!!.copy(nextCheckAt = clock - 1, checkRevision = 2)))
        assertFalse(TaskFollowThrough.dueChecks(board(store).tasks.value, clock).any { it.id == closed.id })
    }

    @Test fun recallHonoursLevelWithoutTreatingSelectionAsUse() = runTest {
        val store = InMemoryTaskBoardStore()
        val book = MemoryBook(store, now = { clock }).apply { load() }
        book.remember("Dana", "fact", "Form detail", "chat", level = "detail")
        val profile = book.remember("Dana", "profile", "Form profile", "chat", level = "profile")
        assertEquals(listOf(profile.id), book.recall("form", level = "profile").map { it.id })
        assertEquals(0, book.live.first { it.id == profile.id }.useCount)
        val tools = TaskTools(TaskBoard(store).apply { load() }, book, TeamRoster(store).apply { load() },
            roleOf = { TaskChatRole.MANAGER }, roleChat = { "unused" })
        val result = tools.invoke("manager", buildJsonObject { put("mode", "recall"); put("query", "form"); put("level", "profile") }, false)
        assertTrue(result.text, result.success)
        val returned = Json.parseToJsonElement(result.text).jsonObject["notes"]!!.jsonArray
        assertEquals(listOf(profile.id), returned.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(0, book.live.first { it.id == profile.id }.useCount)
    }

    @Test fun rememberToolPreservesRequestedLevelAndScope() = runTest {
        val store = InMemoryTaskBoardStore()
        val book = MemoryBook(store, now = { clock }).apply { load() }
        val tools = TaskTools(TaskBoard(store).apply { load() }, book, TeamRoster(store).apply { load() },
            roleOf = { TaskChatRole.MANAGER }, roleChat = { "unused" })
        val result = tools.invoke("manager", buildJsonObject {
            put("mode", "remember"); put("subject", "Dana"); put("text", "Needs a form")
            put("level", "profile"); put("scope", "person")
        }, false)
        assertTrue(result.text, result.success)
        assertEquals("profile", book.live.single().level)
        assertEquals("person", book.live.single().scope)
        assertEquals(1, book.recall("form", level = "profile").size)
        assertTrue(book.recall("form", level = "detail").isEmpty())
    }

    @Test fun projectNoteDoesNotMatchOnlyGenericContentWords() {
        val note = MemoryNote("story", "children story", "preference", "Use Opus for this project",
            source = "chat", at = 1, scope = "project")
        assertTrue(TaskFollowThrough.context(listOf(note), "use this for doctor appointment") { true }.isEmpty())
        assertEquals(listOf("story"), TaskFollowThrough.context(listOf(note), "continue children story") { true }.map { it.id })
    }

    @Test fun coachMustAccountForEveryEventAndLeavesRetriesPending() {
        val events = listOf("a", "b", "c", "d")
        fun choice(id: String, value: String) = CoachDisposition(id, value, "Clear reason", "Owner event", if (value == "applied") listOf("kinds/example.md") else emptyList())
        val decisions = listOf(choice("a", "applied"), choice("b", "no_change"),
            choice("c", "defer"), choice("d", "retry"))
        assertEquals(setOf("a", "b"), CoachReview.completed(events, decisions, hasChange = true))
        assertTrue(runCatching { CoachReview.completed(events, emptyList(), true) }.isFailure)
        assertTrue(runCatching { CoachReview.completed(events, decisions, false) }.isFailure)
        assertTrue(runCatching { CoachReview.completed(events, decisions.dropLast(1), true) }.isFailure)
        assertEquals(setOf("b"), CoachReview.completed(listOf("b"), listOf(choice("b", "no_change")), false))
    }

    @Test fun situationProjectsOnlyAllowedOpenCardsAndKeepsMissingSensorsUnknown() {
        val zone = java.time.ZoneId.of("Asia/Jerusalem")
        val task = MikeTask("a", "Appointment", "why", "plan", originKind = "chat", topicKey = "a",
            createdAt = clock, updatedAt = clock, status = TaskStatus.MINE,
            commitment = TaskCommitment("user", clock + 60_000))
        val hidden = task.copy(id = "hidden")
        val closed = task.copy(id = "closed", status = TaskStatus.DONE)
        val snapshot = TaskSituation.snapshot(listOf(task, hidden, closed), clock, zone, false) { it.id != "hidden" }
        assertEquals(listOf("a"), snapshot.commitments.map { it.taskId })
        assertEquals("Asia/Jerusalem", snapshot.timezone)
        assertTrue(snapshot.localTime.contains("+02:00"))
        assertTrue("current_location" in snapshot.unknown)
        assertTrue(snapshot.sourceGaps.any { it.contains("calendar source is disabled") })
        assertEquals(clock + 60_000, snapshot.commitments.single().nextCheckAt)
    }

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

    @Test fun chatAndNotificationReservationsSurviveRestartWithoutRepeatingAttempt() = runTest {
        val store = InMemoryTaskBoardStore()
        var current = board(store)
        val task = card(current)
        val brief = current.prepareBriefing(task.id, "Please review this", listOf(ref)).briefing!!
        assertFalse(current.beginBriefingNotification(task.id, brief.id))
        current.briefingChatSaved(task.id, brief.id)
        current = board(store)
        assertNotNull(current.task(task.id)!!.briefing!!.chatSavedAt)
        assertTrue(current.beginBriefingNotification(task.id, brief.id))
        current = board(store)
        assertFalse(current.beginBriefingNotification(task.id, brief.id))
        current.delivered(task.id, brief.id, "Outcome unknown after interruption", "unknown")
        val after = board(store).task(task.id)!!.briefing!!
        assertEquals("unknown", after.notificationState)
        assertNotNull(after.notificationAttemptedAt)
        assertNotNull(after.deliveredAt)
    }

    @Test fun appendChecksSourceAndCardBeforeWriting() = runTest {
        val board = board(); val task = card(board)
        val brief = board.prepareBriefing(task.id, "Please review this", listOf(ref)).briefing!!
        var writes = 0
        board.editSettings { it.copy(readChats = false) }
        val denied = board.appendBriefingIfCurrent(task.id, brief.id,
            { _, b -> b.sourceRefs.all { TaskSourcePolicy.allows(it, board.settings.value) } }) { writes++ }
        assertFalse(denied)
        assertEquals(0, writes)
        board.editSettings { it.copy(readChats = true) }
        board.decide(task.id, "done", "tap")
        assertFalse(board.appendBriefingIfCurrent(task.id, brief.id, { _, _ -> true }) { writes++ })
        assertEquals(0, writes)
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
            MemoryNote("b", "User", "preference", "Short replies", source = "chat", at = 2, scope = "global"),
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
            val suppressed = tools.invoke("scout", args("delivery"), false)
            assertTrue(suppressed.text, suppressed.success)
            assertTrue(Json.parseToJsonElement(suppressed.text).jsonObject["suppressed"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(board.tasks.value.isEmpty())
            val accepted = tools.invoke("scout", args("reply"), false)
            assertTrue(accepted.text, accepted.success)
            assertEquals(1, board.tasks.value.size)
            java.io.File(book.directory, "user.md").appendText("\nShow parcel tasks again.\n")
            val afterOwnerCorrection = tools.invoke("scout", args("delivery"), false)
            assertTrue(afterOwnerCorrection.text, afterOwnerCorrection.success)
        } finally { home.deleteRecursively() }
    }
}
