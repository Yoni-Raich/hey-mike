package dev.androidagent.core

import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TaskBoardTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private var clock = 1_800_000_000_000L
    private var ids = 0
    private fun board(store: TaskBoardStore = InMemoryTaskBoardStore()) =
        TaskBoard(store, now = { clock }, zone = { zone }, newId = { "t${++ids}" })

    private fun proposal(title: String = "Send Dana the signed form", topic: String? = null, kind: String? = null) = TaskProposal(
        title = title, why = "Dana asked; she needs it before Tuesday", plan = "Find the PDF and draft a reply",
        topicKey = topic, kind = kind, evidence = listOf(TaskEvidence("whatsapp", "Can you send the signed form?", "Dana")),
        requestedBy = "Dana", sideEffects = listOf("sends"),
    )

    private class RecordingExecutor(var fail: Boolean = false) : TaskExecutor {
        val started = mutableListOf<Pair<String, Int>>()
        val stopped = mutableListOf<String>()
        val continued = mutableListOf<Pair<String, String>>()
        val applied = mutableListOf<String>()
        override suspend fun start(task: MikeTask, attempt: Int): TaskExecution {
            if (fail) throw TaskStartRejectedException("no engine; nothing dispatched")
            started += task.id to attempt
            return TaskExecution(requestId = "${task.id}#$attempt", childTaskId = "child-${task.id}", sessionId = "s-${task.id}", startedAt = 1)
        }
        override suspend fun continueWork(task: MikeTask, message: String) { continued += task.id to message }
        override suspend fun stop(task: MikeTask) { stopped += task.id }
        override suspend fun apply(task: MikeTask) { applied += task.id }
    }

    private fun created(outcome: ProposeOutcome) = (outcome as ProposeOutcome.Created).task

    @Test fun aProposalIsDeduplicatedByTopicWhileOpenAndForTwoWeeksAfterClosing() = runTest {
        val board = board(); board.load()
        val task = created(board.propose(proposal(topic = "dana-form"), "signal", "scout"))
        assertTrue(board.propose(proposal(title = "Reply to Dana about the form", topic = "Dana Form"), "signal", "scout") is ProposeOutcome.Duplicate)
        board.decide(task.id, "dismiss", "tap", reason = DismissReason.WRONG)
        clock += 13 * TaskBoard.DAY_MS
        assertTrue(board.propose(proposal(topic = "dana-form"), "signal", "scout") is ProposeOutcome.Duplicate)
        clock += 2 * TaskBoard.DAY_MS
        assertTrue(board.propose(proposal(topic = "dana-form"), "signal", "scout") is ProposeOutcome.Created)
    }

    @Test fun neverThisKindSuppressesTheTopicAndKindUntilRestored() = runTest {
        val board = board(); board.load()
        val task = created(board.propose(proposal(topic = "parcel-1", kind = "delivery tracking"), "signal", "scout"))
        board.decide(task.id, "dismiss", "tap", reason = DismissReason.NEVER_THIS_KIND)
        val other = board.propose(proposal(title = "Track the new parcel", topic = "parcel-2", kind = "delivery-tracking"), "signal", "scout")
        assertEquals("suppressed", (other as ProposeOutcome.Refused).reason)
        board.decide(task.id, "restore", "tap")
        assertEquals(TaskStatus.PROPOSED, board.task(task.id)!!.status)
        assertTrue(board.settings.value.suppressed.none { it.topicKey == "parcel-1" })
    }

    @Test fun signalProposalsNeedEvidenceAndTheBoardCapsOpenCards() = runTest {
        val board = board(); board.load()
        val bare = board.propose(proposal().copy(evidence = emptyList()), "signal", "scout")
        assertEquals("no_evidence", (bare as ProposeOutcome.Refused).reason)
        repeat(TaskBoard.MAX_OPEN) { created(board.propose(proposal(title = "Task number $it", topic = "topic-$it"), "signal", "scout")) }
        val full = board.propose(proposal(title = "One more", topic = "more"), "signal", "scout")
        assertEquals("board_full", (full as ProposeOutcome.Refused).reason)
    }

    @Test fun doStartsWorkOnceThroughTheExecutorAndChildStatusFollows() = runTest {
        val board = board(); board.load()
        val executor = RecordingExecutor(); board.executor = executor
        val task = created(board.propose(proposal(), "signal", "scout"))
        val running = board.decide(task.id, "do", "tap", note = "Attach the PDF, not a photo")
        assertEquals(TaskStatus.RUNNING, running.status)
        assertEquals(listOf(task.id to 1), executor.started)
        assertEquals("Attach the PDF, not a photo", running.note)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { board.decide(task.id, "do", "tap") } }
        assertEquals(1, executor.started.size)

        val child = SessionAgentTask("child-${task.id}", "${task.id}#1", "manager", task.title, "x", EngineKind.CODEX, createdAt = 1,
            sessionId = "s-${task.id}", status = "completed", result = "Sent the PDF to Dana.")
        board.syncChildren(listOf(child))
        assertEquals(TaskStatus.REVIEW, board.task(task.id)!!.status)
        assertEquals("Sent the PDF to Dana.", board.task(task.id)!!.result)
        board.decide(task.id, "done", "tap")
        board.syncChildren(listOf(child.copy(status = "running")))
        assertEquals("A closed card is not reopened by a late child", TaskStatus.DONE, board.task(task.id)!!.status)
    }

    @Test fun aFailedStartIsReportedAndAStoredApprovalNeverStartsWork() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = board(store); board.load()
        board.executor = RecordingExecutor(fail = true)
        val task = created(board.propose(proposal(), "signal", "scout"))
        assertEquals(TaskStatus.FAILED, board.decide(task.id, "do", "tap").status)
        // A status written behind the board's back, then a restart: nothing runs, the card says unknown.
        store.saveTask(board.task(task.id)!!.copy(status = TaskStatus.APPROVED, execution = null))
        val executor = RecordingExecutor()
        val restarted = board(store).also { it.executor = executor }
        restarted.load()
        assertEquals(TaskStatus.UNKNOWN, restarted.task(task.id)!!.status)
        assertTrue(executor.started.isEmpty())
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { restarted.decide(task.id, "do", "tap") } }
    }

    @Test fun retryAfterFailureIsANewAttempt() = runTest {
        val board = board(); board.load()
        val executor = RecordingExecutor(fail = true); board.executor = executor
        val task = created(board.propose(proposal(), "signal", "scout"))
        board.decide(task.id, "do", "tap")
        executor.fail = false
        board.decide(task.id, "do", "tap")
        assertEquals(listOf(task.id to 2), executor.started)
    }

    @Test fun notNowSnoozesAndSweepWakesAndExpires() = runTest {
        val board = board(); board.load()
        val a = created(board.propose(proposal(title = "Snooze me", topic = "a"), "signal", "scout"))
        val b = created(board.propose(proposal(title = "Expire me", topic = "b").copy(usefulUntil = clock + TaskBoard.HOUR_MS), "signal", "scout"))
        assertEquals(TaskStatus.SNOOZED, board.decide(a.id, "dismiss", "tap", reason = DismissReason.NOT_NOW).status)
        clock += 2 * TaskBoard.HOUR_MS
        board.sweep()
        assertEquals(TaskStatus.SNOOZED, board.task(a.id)!!.status)
        assertEquals(TaskStatus.EXPIRED, board.task(b.id)!!.status)
        clock += TaskBoard.DAY_MS
        board.sweep()
        assertEquals(TaskStatus.PROPOSED, board.task(a.id)!!.status)
    }

    @Test fun discoveryMayUpdateOnlyUndecidedCards() = runTest {
        val board = board(); board.load()
        val task = created(board.propose(proposal(), "signal", "scout"))
        board.update(task.id, evidence = listOf(TaskEvidence("whatsapp", "Any news?", "Dana")))
        assertEquals(2, board.task(task.id)!!.evidence.size)
        board.decide(task.id, "mine", "tap")
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { board.update(task.id, looksResolved = "sent") } }
        val other = created(board.propose(proposal(title = "Another", topic = "other"), "signal", "scout"))
        assertEquals(TaskStatus.LOOKS_RESOLVED, board.update(other.id, looksResolved = "She wrote: never mind").status)
    }

    @Test fun settingsCardsApplyTheirActionInsteadOfStartingAChat() = runTest {
        val board = board(); board.load()
        val executor = RecordingExecutor(); board.executor = executor
        val card = created(board.propose(TaskProposal("Exclude shop notifications", "14 of 15 dismissed", "Stop reading this app",
            category = TaskCategory.SETTINGS, action = TaskAction(TaskAction.EXCLUDE_APP, "com.shop")), "review", "coach"))
        assertEquals(TaskStatus.DONE, board.decide(card.id, "do", "tap").status)
        assertEquals(listOf(card.id), executor.applied)
        assertTrue(executor.started.isEmpty())
        val bad = board.propose(TaskProposal("Bad", "x", "y", category = TaskCategory.SETTINGS, action = TaskAction(TaskAction.UNDO_SNAPSHOT, "s")), "review", "coach")
        assertTrue(bad is ProposeOutcome.Refused)
    }

    @Test fun topicKeysKeepHebrewAndTimesParseInTheUsersZone() {
        assertEquals("טופס-של-דנה", TaskBoard.topicKey("  טופס של דנה! "))
        assertNotNull(TaskBoard.parseTime("2026-10-27T10:00:00+02:00", zone))
        assertNotNull(TaskBoard.parseTime("2026-10-27T10:00", zone))
        assertNotNull(TaskBoard.parseTime("2026-10-27", zone))
        assertNull(TaskBoard.parseTime("next tuesday", zone))
    }
}

class TaskToolsTest {
    private var clock = 1_800_000_000_000L
    private val store = InMemoryTaskBoardStore()
    private val board = TaskBoard(store, now = { clock })
    private val memory = MemoryBook(store, now = { clock })
    private val team = TeamRoster(store, now = { clock })
    private val roles = mutableMapOf<String, TaskChatRole>()
    private val tools = TaskTools(board, memory, team, roleOf = { roles[it] ?: TaskChatRole.ORDINARY }, roleChat = { "chat-${it.id}" }, now = { clock })

    private suspend fun call(session: String, fromUser: Boolean = true, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        Json.parseToJsonElement(tools.invoke(session, buildJsonObject(body), fromUser).text).jsonObject

    private suspend fun setUp() {
        board.load(); memory.load(); team.load()
        team.ensureBuiltIns { member, existing -> existing ?: "chat-${member.id}" }
        roles["chat-manager"] = TaskChatRole.MANAGER
        roles["chat-scout"] = TaskChatRole.SCOUT
        roles["chat-coach"] = TaskChatRole.COACH
        board.executor = object : TaskExecutor {
            override suspend fun start(task: MikeTask, attempt: Int) = TaskExecution("r", "c", "s", startedAt = 0)
            override suspend fun continueWork(task: MikeTask, message: String) {}
            override suspend fun stop(task: MikeTask) {}
            override suspend fun apply(task: MikeTask) {}
        }
    }

    private suspend fun proposeFrom(session: String): JsonObject = call(session) {
        put("mode", "propose"); put("title", "Prepare notes for Thursday's review"); put("why", "Meeting in 2 days, no notes")
        put("plan", "Read the project's last commits and write BRIEF.md"); put("topicKey", "thursday-review-notes"); put("kind", "meeting-prep")
        put("evidence", buildJsonArray { add(buildJsonObject { put("source", "calendar"); put("detail", "Review, Thu 10:00") }) })
    }

    @Test fun onlyTheUsersOwnWordsInTheManagerChatDecide() = runTest {
        setUp()
        val id = proposeFrom("chat-scout")["created"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val fromContext = call("chat-manager", fromUser = false) { put("mode", "decide"); put("id", id); put("choice", "do") }
        assertEquals("not_the_user", fromContext["errorType"]!!.jsonPrimitive.content)
        val ordinary = call("some-chat") { put("mode", "decide"); put("id", id); put("choice", "do") }
        assertEquals("not_here", ordinary["errorType"]!!.jsonPrimitive.content)
        val scout = call("chat-scout") { put("mode", "decide"); put("id", id); put("choice", "do") }
        assertEquals("not_here", scout["errorType"]!!.jsonPrimitive.content)
        assertEquals(TaskStatus.PROPOSED, board.task(id)!!.status)
        val decided = call("chat-manager") { put("mode", "decide"); put("ids", buildJsonArray { add(JsonPrimitive(id)) }); put("choice", "do") }
        assertEquals(true, decided["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(TaskStatus.RUNNING, board.task(id)!!.status)
    }

    @Test fun onlyBackstageReviewersPostSettingsCards() = runTest {
        setUp()
        val body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {
            put("mode", "propose"); put("category", "settings"); put("title", "Exclude a noisy app"); put("why", "All dismissed")
            put("plan", "Stop reading com.noise"); put("action", buildJsonObject { put("type", "exclude_app"); put("value", "com.noise") })
        }
        assertEquals("not_here", call("chat-scout", body = body)["errorType"]!!.jsonPrimitive.content)
        assertTrue(call("chat-coach", body = body).containsKey("created"))
    }

    @Test fun aNewRoleTakesOpenCardsOfItsKindAndTheTeamIsCapped() = runTest {
        setUp()
        val id = proposeFrom("chat-scout")["created"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val made = call("chat-manager") {
            put("mode", "team"); put("teamAction", "create"); put("name", "Meetings"); put("role", "Prepares meetings")
            put("kinds", buildJsonArray { add(JsonPrimitive("meeting-prep")) })
        }
        val memberId = made["member"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(memberId, board.task(id)!!.assignee)
        repeat(TeamRoster.MAX_ROLES - 1) { n ->
            call("chat-manager") { put("mode", "team"); put("teamAction", "create"); put("name", "Role $n"); put("role", "r")
                put("kinds", buildJsonArray { add(JsonPrimitive("k$n")) }) }
        }
        val over = call("chat-manager") { put("mode", "team"); put("teamAction", "create"); put("name", "One too many"); put("role", "r")
            put("kinds", buildJsonArray { add(JsonPrimitive("z")) }) }
        assertEquals(false, over["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun backstageChatsMayOnlyRead() {
        fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { put(it.first, it.second) } }
        val scout = TaskChatRole.SCOUT
        assertNull(TaskRolePolicy.refusal(scout, "tasks", args("mode" to "propose")))
        assertNull(TaskRolePolicy.refusal(scout, "calendar", args("operation" to "list")))
        assertNotNull(TaskRolePolicy.refusal(scout, "calendar", args("operation" to "create")))
        assertNotNull(TaskRolePolicy.refusal(scout, "calendar", args("mode" to "list")))
        listOf("tap", "type_text", "open_intent", "open_app", "communications", "computers", "session_agents", "ask_user", "workflow_runner", "browser", "shell")
            .forEach { assertNotNull(it, TaskRolePolicy.refusal(scout, it, args())) }
        assertNull(TaskRolePolicy.refusal(TaskChatRole.MANAGER, "tap", args()))
        assertNull(TaskRolePolicy.refusal(TaskChatRole.ORDINARY, "open_intent", args()))
    }

    @Test fun theToolSchemaDescribesItself() {
        assertEquals(emptyList<String>(), ToolSchemaAudit.complaints(listOf(TaskTools.DEFINITION)))
        assertTrue(TaskTools.NAME in ChatTools.NAMES)
    }
}

class TaskSignalsTest {
    private var clock = 1_800_000_000_000L
    private val on = TaskBoardSettings(discoveryOn = true)

    private fun message(text: String, app: String = "com.whatsapp", conversation: String = "Dana", group: Boolean = false, at: Long = clock) =
        CapturedMessage(app, conversation, conversation, text, at, group)

    @Test fun codesPaymentsExcludedAppsAndTheOffSwitchKeepTextOut() = runTest {
        val inbox = SignalInbox(InMemoryTaskBoardStore(), now = { clock }); inbox.load()
        assertNull(inbox.capture(message("Your verification code is 482913"), on, "dev.androidagent.app"))
        assertNull(inbox.capture(message("קוד האימות שלך הוא 5521"), on, "dev.androidagent.app"))
        assertNull(inbox.capture(message("Card ending 4421 was charged 120 ILS"), on, "dev.androidagent.app"))
        assertNull(inbox.capture(message("Can you send the form?", app = "com.shop"), on.copy(excludedApps = setOf("com.shop")), "x"))
        assertNull(inbox.capture(message("Can you send the form?"), TaskBoardSettings(discoveryOn = false), "x"))
        assertNull(inbox.capture(message("Dinner?", group = true), on.copy(includeGroups = false), "x"))
        assertNull(inbox.capture(message("Hi", conversation = "Boss"), on.copy(excludedConversations = setOf("com.whatsapp|Boss")), "x"))
        assertNull(inbox.capture(message("Hi", app = "dev.androidagent.app"), on, "dev.androidagent.app"))
        assertTrue(inbox.pending.value.isEmpty())
        assertNotNull(inbox.capture(message("Can you send the signed form before Tuesday?"), on, "x"))
        assertNull("The same message twice is stored once", inbox.capture(message("Can you send the signed form before Tuesday?"), on, "x"))
        assertEquals(1, inbox.pending.value.size)
    }

    @Test fun aProcessedBatchLosesItsTextAndOldOrFailingOnesArePruned() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store, now = { clock }); inbox.load()
        val a = inbox.capture(message("one"), on, "x")!!
        val b = inbox.capture(message("two", at = clock + 1), on, "x")!!
        inbox.processed(listOf(a.id))
        assertEquals(listOf(b.id), store.loadSignals().map { it.id })
        repeat(SignalInbox.MAX_ATTEMPTS) { inbox.failed(listOf(b.id)) }
        inbox.prune(true)
        assertTrue(store.loadSignals().isEmpty())
        inbox.capture(message("three"), on, "x")
        clock += SignalInbox.MAX_AGE_MS + 1
        inbox.prune(true)
        assertTrue(store.loadSignals().isEmpty())
    }

    @Test fun aBurstBecomesOnePassAfterAQuietSpellAndPassesKeepTheirDistance() {
        fun signal(id: String, at: Long, source: String = "whatsapp", conversation: String = "Dana") =
            TaskSignal(id, source, "com.whatsapp", conversation, conversation, "text $id", at = at, capturedAt = at)
        val burst = listOf(signal("a", clock), signal("b", clock + 60_000), signal("c", clock + 120_000))
        val early = DiscoverySchedule.plan(burst, clock + 5 * 60_000, null, 0, 48, busy = false)
        assertFalse(early.due)
        assertEquals(clock + 120_000 + 10 * 60_000, early.nextCheckAt)
        val quiet = DiscoverySchedule.plan(burst, clock + 13 * 60_000, null, 0, 48, busy = false)
        assertTrue(quiet.due); assertEquals(3, quiet.batch.size)
        val tooSoon = DiscoverySchedule.plan(burst, clock + 13 * 60_000, clock + 5 * 60_000, 0, 48, busy = false)
        assertFalse(tooSoon.due)
        assertFalse(DiscoverySchedule.plan(burst, clock + 13 * 60_000, null, 48, 48, busy = false).due)
        assertFalse(DiscoverySchedule.plan(burst, clock + 13 * 60_000, null, 0, 48, busy = true).due)
        // A conversation that never goes quiet still gets a pass within the hour.
        val chatty = (0 until 70).map { signal("m$it", clock + it * 60_000L) }
        assertTrue(DiscoverySchedule.plan(chatty, clock + 61 * 60_000, null, 0, 48, busy = false).due)
        // A chat that went quiet is already idle-gated, so it is ready at once.
        assertTrue(DiscoverySchedule.plan(listOf(signal("x", clock, source = "chat")), clock, null, 0, 48, busy = false).due)
    }
}

class MemoryBookTest {
    private var clock = 1_800_000_000_000L
    private var n = 0
    private val book = MemoryBook(InMemoryTaskBoardStore(), now = { clock }, newId = { "n${++n}" })

    @Test fun rememberDeduplicatesAndRecallCountsUse() = runTest {
        book.load()
        val a = book.remember("Dana", "request", "Asked for the signed form before Tuesday", "whatsapp", sender = "Dana")
        assertEquals(a.id, book.remember("dana", "request", "asked for the signed form before tuesday", "whatsapp").id)
        book.remember("Ben", "fact", "Ben is the plumber", "chat")
        val found = book.recall("signed form")
        assertEquals(listOf(a.id), found.map { it.id })
        assertEquals(1, found.single().useCount)
        assertEquals(1, book.recall(null, subject = "Ben").size)
    }

    @Test fun compactionSupersedesAndRetireKeepsPinnedAndLinkedNotes() = runTest {
        book.load()
        val a = book.remember("Dana", "request", "Sent form A", "whatsapp")
        val b = book.remember("Dana", "request", "Sent form B", "whatsapp")
        val c = book.remember("Dana", "fact", "Dana is the accountant", "chat")
        val pinned = book.remember("Ben", "fact", "Ben's gate code is in the notes app", "chat").also { book.setPinned(it.id, true) }
        val linked = book.remember("Ben", "promise", "Promised to call Ben back", "chat", linkedTaskIds = listOf("open-card"))
        val profile = book.merge(listOf(a.id, b.id, c.id), "Dana", "Dana: accountant; sends forms by WhatsApp", "profile", emptySet())
        assertEquals(listOf(profile.id, pinned.id, linked.id).toSet(), book.live.map { it.id }.toSet())
        val kept = book.retire(listOf(pinned.id, linked.id), "old", setOf("open-card"))
        assertEquals(2, kept.size)
        clock += MemoryBook.TRASH_MS + 1
        assertEquals(3, book.emptyTrash())
    }
}

class TeamRosterTest {
    @Test fun builtInsAreCreatedOnceWithTheirChats() = runTest {
        val roster = TeamRoster(InMemoryTaskBoardStore()); roster.load()
        var made = 0
        roster.ensureBuiltIns { member, existing -> existing ?: "chat-${member.id}-${++made}" }
        roster.ensureBuiltIns { member, existing -> existing ?: "chat-${member.id}-${++made}" }
        assertEquals(4, made)
        assertEquals(MemberKind.MANAGER, roster.bySession(roster.of(MemberKind.MANAGER)!!.sessionId!!)!!.kind)
        assertTrue(roster.members.value.filter { it.kind != MemberKind.MANAGER }.all { it.kind.backstage })
    }
}

class TaskPlaybookTest {
    @Test fun seedNeverOverwritesAndUndoKeepsTheUsersOwnRules() {
        val home = kotlin.io.path.createTempDirectory("playbook").toFile()
        try {
            val playbook = TaskPlaybook(home)
            playbook.seed()
            val index = java.io.File(playbook.directory, "SKILL.md")
            index.writeText("learned")
            playbook.seed()
            assertEquals("learned", index.readText())
            val note = MemoryNote("n1", "Dana", "fact", "Accountant", source = "chat", at = 1)
            val snapshot = playbook.snapshot("review", listOf(note), 1_800_000_000_000L)
            index.writeText("changed tonight")
            playbook.addUserRule("Never suggest delivery tracking")
            val restored = playbook.restore(snapshot)
            assertEquals("learned", index.readText())
            assertTrue(playbook.userRules().contains("Never suggest delivery tracking"))
            assertEquals(listOf(note), restored)
            assertFalse(playbook.exists("../../etc"))
        } finally { home.deleteRecursively() }
    }
}
