package dev.androidagent.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskReliabilityTest {
    @Test fun coachDoesNotMistakeUndoForApprovalOfItsEarlierRules() = runTest {
        val board = TaskBoard(InMemoryTaskBoardStore()).apply { load() }
        val report = (board.propose(proposal("Memory upkeep").copy(category = TaskCategory.REPORT,
            action = TaskAction(TaskAction.UNDO_SNAPSHOT, "night-a")), "upkeep", "librarian") as ProposeOutcome.Created).task
        val prompt = TaskPrompts.coachReview(listOf(report), 1, java.time.ZoneOffset.UTC, "night-b", "unused")
        assertTrue(prompt.contains("\"category\":\"report\""))
        assertTrue(prompt.contains("\"actionType\":\"undo_snapshot\""))
        assertTrue(prompt.contains("undo is a correction, not approval"))
    }

    @Test fun pruningALearnedFileFreesASlotAndAnUndoGenerationStaysIntact() {
        val home = java.nio.file.Files.createTempDirectory("rule-prune-").toFile()
        try {
            val root = TaskPlaybook(home).apply { seed() }
            val first = root.fork("one", listOf(TaskSourceRef("user", verified = true)))
            first.writeLearned("people/dana.md", "Keep the useful rule")
            first.publishGeneration("one")
            val second = root.generation("one").fork("two", listOf(TaskSourceRef("user", verified = true)))
            second.writeLearned("people/dana.md", "")
            assertFalse(second.list().contains("people/dana.md"))
            second.publishGeneration("two")
            assertEquals("Keep the useful rule", root.generation("one").read("people/dana.md"))
            assertFalse(root.generation("two").list().contains("people/dana.md"))
        } finally { home.deleteRecursively() }
    }

    @Test fun legacyExternalInputDoesNotGainProfileVerificationOnReload() = runTest {
        val legacy = TaskSignal("old", "whatsapp", app = "com.whatsapp", text = "Old notification", at = 1, capturedAt = 1)
        assertFalse(TaskSourcePolicy.allows(TaskSourcePolicy.of(legacy), TaskBoardSettings()))
        val inbox = SignalInbox(InMemoryTaskBoardStore()).apply { load() }
        val fresh = inbox.capture(CapturedMessage("com.whatsapp", "Dana", "Dana", "A current personal message", 2, false),
            TaskBoardSettings(discoveryOn = true), "own")!!
        assertTrue(fresh.sourceVerified)
        assertTrue(TaskSourcePolicy.allows(TaskSourcePolicy.of(fresh), TaskBoardSettings()))
        assertNotNull(TaskRolePolicy.refusal(TaskChatRole.COORDINATOR, "calendar", kotlinx.serialization.json.buildJsonObject { }))
    }

    @Test fun longNotificationsAreStoredInFullAndOverflowKeepsExistingInput() = runTest {
        val store = InMemoryTaskBoardStore()
        val inbox = SignalInbox(store).apply { load() }
        val text = "x".repeat(5_001)
        inbox.capture(CapturedMessage("com.whatsapp", "Dana", "Dana", text, 1, false), TaskBoardSettings(discoveryOn = true), "own")
        assertEquals(text, inbox.pending.value.joinToString("") { it.text })
        val remaining = SignalInbox.MAX_PENDING - inbox.pending.value.size
        repeat(remaining) { inbox.add(TaskSignal("s$it", "chat", text = "keep", at = 1, capturedAt = 1)) }
        assertTrue(runCatching { inbox.add(TaskSignal("overflow", "chat", text = "new", at = 1, capturedAt = 1)) }.isFailure)
        assertEquals(SignalInbox.MAX_PENDING, store.loadSignals().size)
        assertTrue(store.loadSignals().any { it.text == text.take(SignalInbox.MAX_TEXT) })
    }

    @Test fun publishedRulesAreReadOnlyAndTamperingIsDetected() {
        val home = java.nio.file.Files.createTempDirectory("task-rules-").toFile()
        try {
            val root = TaskPlaybook(home)
            root.seed()
            val staged = root.fork("night-a", listOf(TaskSourceRef("user", verified = true)))
            staged.writeLearned("kinds/forms.md", "Keep the PDF attached.")
            staged.publishGeneration("night-a")
            val active = root.generation("night-a")
            assertEquals("Keep the PDF attached.", active.read("kinds/forms.md"))
            assertTrue(runCatching { active.writeLearned("kinds/forms.md", "New") }.isFailure)
            java.io.File(active.directory, "kinds/forms.md").writeText("Changed outside the managed writer")
            assertTrue(runCatching { root.generation("night-a") }.isFailure)
        } finally { home.deleteRecursively() }
    }

    @Test fun forgottenSourceBlocksLearnedRulesButDoesNotChangeUserRules() {
        val home = java.nio.file.Files.createTempDirectory("task-forget-").toFile()
        var forgotten = false
        try {
            val root = TaskPlaybook(home, allowedSource = { !forgotten || it.noteId != "note-a" })
            root.seed(); root.addUserRule("Keep dates visible")
            val staged = root.fork("night", listOf(TaskSourceRef("memory", verified = true, noteId = "note-a")))
            staged.writeLearned("people/dana.md", "Dana works on the forms")
            staged.publishGeneration("night")
            forgotten = true
            val active = root.generation("night")
            assertTrue(runCatching { active.read("people/dana.md") }.isFailure)
            assertTrue(active.userRules().contains("Keep dates visible"))
            root.eraseForgotten(setOf("note-a"))
            assertFalse(java.io.File(active.directory, "people/dana.md").exists())
            assertFalse(root.generation("night").list().contains("people/dana.md"))
        } finally { home.deleteRecursively() }
    }

    private fun proposal(title: String = "Prepare form") = TaskProposal(title, "Due soon", "Draft the form", evidence = listOf(TaskEvidence("chat", "Please prepare it")))

    @Test fun returningAPostponedCardIsADefinedUserTransition() = runTest {
        val board = TaskBoard(InMemoryTaskBoardStore()).apply { load() }
        val card = (board.propose(proposal(), "conversation", "chat") as ProposeOutcome.Created).task
        board.decide(card.id, "snooze", "tap")
        board.decide(card.id, "wake", "tap")
        assertEquals(TaskStatus.PROPOSED, board.task(card.id)!!.status)
        assertEquals(listOf("snooze", "wake"), board.events.value.mapNotNull { it.decision?.choice })
    }

    @Test fun userNotesAndEarlierDecisionsSurviveDoneAndReopen() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store).apply { load() }
        val card = (board.propose(proposal(), "conversation", "chat") as ProposeOutcome.Created).task
        board.decide(card.id, "mine", "tap", note = "Use the new form")
        board.setNote(card.id, "Attach the PDF too")
        board.decide(card.id, "done", "chat")
        val reopened = TaskBoard(store).apply { load() }
        assertTrue(reopened.events.value.any { it.note == "Use the new form" })
        assertTrue(reopened.events.value.any { it.kind == "note" && it.note == "Attach the PDF too" })
        assertEquals(listOf("mine", "done"), reopened.events.value.mapNotNull { it.decision?.choice })
    }

    @Test fun nightlyReportsHaveRunIdentityRatherThanDisplayTitleIdentity() = runTest {
        val board = TaskBoard(InMemoryTaskBoardStore()).apply { load() }
        fun report(run: String) = proposal("Memory upkeep").copy(category = TaskCategory.REPORT, action = TaskAction(TaskAction.UNDO_SNAPSHOT, run))
        assertTrue(board.propose(report("night-a"), "upkeep", "librarian") is ProposeOutcome.Created)
        assertTrue(board.propose(report("night-b"), "upkeep", "librarian") is ProposeOutcome.Created)
        assertTrue(board.propose(report("night-a"), "upkeep", "librarian") is ProposeOutcome.Duplicate)
    }

    @Test fun fullInboxCannotAdvanceAReplayableSourceCursor() = runTest {
        val store = InMemoryTaskBoardStore()
        val pending = (1..SignalInbox.MAX_PENDING).map { TaskSignal("s$it", "chat", text = "message", at = 1, capturedAt = 1) }
        store.capturePage("chat", 0, 20, pending)
        assertEquals(20, store.sourceCursor("chat"))
        assertTrue(runCatching { store.capturePage("chat", 20, 21, listOf(TaskSignal("new", "chat", text = "new", at = 2, capturedAt = 2))) }.isFailure)
        assertEquals(20, store.sourceCursor("chat"))
        assertFalse(store.loadSignals().any { it.id == "new" })
    }

    @Test fun policyFollowsDerivedSourceReferencesAndDistinguishesUserRules() {
        val external = TaskSourceRef("whatsapp", "com.whatsapp", "Dana", verified = true)
        val settings = TaskBoardSettings(discoveryOn = true, excludedApps = setOf("com.whatsapp"))
        assertFalse(TaskSourcePolicy.allows(external, settings))
        assertFalse(TaskSourcePolicy.allows(TaskSourceRef("whatsapp"), settings.copy(excludedApps = emptySet())))
        assertTrue(TaskSourcePolicy.allows(TaskSourceRef("user", verified = true), settings))
    }
}
