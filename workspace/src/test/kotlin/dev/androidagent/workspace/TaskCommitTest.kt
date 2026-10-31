package dev.androidagent.workspace

import dev.androidagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskCommitTest {
    @Test fun briefingIsAnAtomicOutboxAndConcurrentOwnerDecisionsWin() = runBlocking {
        val store = store()
        val board = TaskBoard(store, now = { 1_000_000L }).apply { load() }
        val task = (board.propose(TaskProposal("Form", "Tomorrow", "Draft form", sourceRefs = listOf(TaskSourceRef("chat"))), "conversation", "owner") as ProposeOutcome.Created).task
        val data = StagedTaskData.create(store)
        val staged = TaskBoard(data.store, now = { 1_000_000L }).apply { load() }
        staged.prepareBriefing(task.id, "The form is due tomorrow", listOf(TaskSourceRef("chat")))
        val changes = data.changes("brief", 0, null, null)
        board.decide(task.id, "mine", "tap")
        assertTrue(runCatching { store.commitChanges(changes, run("brief").copy(purpose = "manager")) }.isFailure)
        assertNull(store.loadTasks().single().briefing)
        val fresh = StagedTaskData.create(store)
        val freshBoard = TaskBoard(fresh.store, now = { 1_000_000L + TaskFollowThrough.DAY }).apply { load() }
        val pending = freshBoard.prepareBriefing(task.id, "You took this form. Is it ready?", listOf(TaskSourceRef("chat")))
        store.commitChanges(fresh.changes("follow", 0, null, null), run("follow").copy(purpose = "manager"))
        val reopened = LocalTaskBoardStore(RuntimeEnvironment.getApplication())
        assertEquals(pending.briefing!!.id, reopened.loadTasks().single().briefing!!.id)
        assertNull(reopened.loadTasks().single().briefing!!.deliveredAt)
    }
    @Test fun ownerFeedbackIsCommittedWithTheCursorAndSurvivesRawDeletion() = runBlocking {
        val store = store()
        val signal = TaskSignal("owner-msg", "chat", text = "Please keep replies short", at = 1, capturedAt = 1,
            sessionId = "manager", sourceVerified = true, ownerFeedback = true)
        store.capturePage("messages", 0, 1, listOf(signal))
        store.deleteSignals(listOf(signal.id))
        val reopened = LocalTaskBoardStore(RuntimeEnvironment.getApplication())
        assertEquals(1, reopened.sourceCursor("messages"))
        assertEquals(signal.text, reopened.loadEvents().single { it.kind == "feedback" }.note)
        assertTrue(runCatching { reopened.capturePage("messages", 0, 2, listOf(signal.copy(id = "bad"))) }.isFailure)
        assertFalse(reopened.loadEvents().any { it.id == "feedback:bad" })
    }
    @Test fun aFullLegacyInboxIsPreservedWithoutBlockingVerifiedNewCapture() = runBlocking {
        val store = store()
        repeat(SignalInbox.MAX_PENDING) { index -> store.insertSignal(TaskSignal("legacy-$index", "whatsapp", app = "com.whatsapp",
            text = "Old input $index", at = 1, capturedAt = 1)) }
        assertEquals(SignalInbox.MAX_PENDING, store.quarantineLegacySignals(2))
        assertTrue(store.loadSignals().isEmpty())
        assertEquals(SignalInbox.MAX_PENDING, store.quarantineLegacySignals(2))
        val fresh = TaskSignal("personal", "whatsapp", app = "com.whatsapp", text = "Current personal message", at = 2,
            capturedAt = 2, sourceVerified = true)
        store.capturePage("notification-capture", 0, 1, listOf(fresh))
        assertEquals(listOf(fresh), store.loadSignals())
        assertEquals(0, store.quarantineLegacySignals(SignalInbox.MAX_AGE_MS + 2))
        assertEquals(listOf(fresh), store.loadSignals())
    }

    @Test fun reviewReceiptsStayConsumedAfterTheSettingsWindowRollsOver() = runBlocking {
        val store = store()
        val events = (1..4_100).map { "event-$it" }
        store.commitChanges(TaskChangeSet("review", 0), run("review").copy(purpose = "manager", eventIds = events))
        assertEquals(4_000, store.loadSettings().managerReviewedEvents.size)
        assertEquals(events.toSet(), store.consumedEvents("manager"))
        val reopened = LocalTaskBoardStore(RuntimeEnvironment.getApplication())
        assertTrue("Old event must not become new again", "event-1" in reopened.consumedEvents("manager"))
        reopened.saveSettings(reopened.loadSettings().copy(policyRevision = 1))
        assertTrue(runCatching { reopened.commitChanges(TaskChangeSet("rejected", 0), run("rejected").copy(purpose = "coach", eventIds = listOf("unseen"))) }.isFailure)
        assertTrue(reopened.consumedEvents("coach").isEmpty())
    }

    private fun note(id: String, text: String) = MemoryNote(id, "Dana", "fact", text, source = "chat", at = 1,
        sourceRefs = listOf(TaskSourceRef("chat", verified = true)))
    private suspend fun store() = LocalTaskBoardStore(RuntimeEnvironment.getApplication()).apply {
        saveSettings(TaskBoardSettings(discoveryOn = true))
    }
    private fun run(id: String) = TaskRun(id, "librarian", "librarian", 0, emptyList(), 1)

    @Test fun undoRevertsOnlyThisRunAndKeepsLaterAdditionsAndEdits() = runBlocking {
        val store = store()
        val before = note("a", "Original")
        val after = before.copy(text = "Compacted")
        store.saveNotes(listOf(before))
        store.commitChanges(TaskChangeSet("night", 0, notes = listOf(NoteChange(before, after))), run("night"))
        val later = note("b", "Added after the night")
        store.saveNotes(listOf(later, after.copy(text = "The user's later edit")))
        val undo = store.undoChanges("night")
        assertEquals(1, undo.conflicts)
        assertEquals(setOf("The user's later edit", "Added after the night"), store.loadNotes().map { it.text }.toSet())
        assertEquals(TaskUndoResult(0, 0), store.undoChanges("night"))
    }

    @Test fun forgettingANotePreventsOldMaintenanceFromResurrectingIt() = runBlocking {
        val store = store()
        val before = note("a", "Original")
        val after = before.copy(retiredAt = 2)
        store.saveNotes(listOf(before))
        store.commitChanges(TaskChangeSet("night", 0, notes = listOf(NoteChange(before, after))), run("night"))
        store.forgetNotes(listOf("a"))
        assertTrue(store.changes("night")!!.notes.isEmpty())
        assertEquals(0, store.undoChanges("night").conflicts)
        assertTrue(store.loadNotes().isEmpty())
    }

    @Test fun policyChangeRejectsEveryPartOfAStagedCommitAndPreservesInput() = runBlocking {
        val store = store()
        val input = TaskSignal("signal", "chat", text = "Prepare form", at = 1, capturedAt = 1)
        store.insertSignal(input)
        store.saveSettings(TaskBoardSettings(discoveryOn = true, policyRevision = 1, readChats = false))
        val patch = TaskChangeSet("pass", 0, notes = listOf(NoteChange(after = note("a", "Learned"))))
        assertTrue(runCatching { store.commitChanges(patch, run("pass").copy(inputIds = listOf("signal"))) }.isFailure)
        assertTrue(store.loadNotes().isEmpty())
        assertEquals(listOf(input), store.loadSignals())
        assertNull(store.changes("pass"))
        assertTrue(runCatching { withContext(TaskWritePermit(0)) { store.saveNotes(listOf(note("b", "Late save"))) } }.isFailure)
    }

    @Test fun successfulCommitAcknowledgesInputWithItsOutputsAndIsIdempotent() = runBlocking {
        val store = store()
        store.insertSignal(TaskSignal("signal", "chat", text = "Form", at = 1, capturedAt = 1))
        val patch = TaskChangeSet("pass", 0, notes = listOf(NoteChange(after = note("a", "Useful fact"))))
        val run = run("pass").copy(inputIds = listOf("signal"))
        store.commitChanges(patch, run)
        store.commitChanges(patch, run)
        assertTrue(store.loadSignals().isEmpty())
        assertEquals(1, store.loadNotes().size)
        assertEquals("committed", store.loadRuns().single().state)
    }

    @Test fun fullInboxResumesCaptureAfterAnAtomicScoutCommitWithoutLosingTheFeedCursor() = runBlocking {
        val store = store()
        val pending = (1..SignalInbox.MAX_PENDING).map { TaskSignal("s$it", "chat", text = "Keep $it", at = 1, capturedAt = 1) }
        store.capturePage("messages", 0, 400, pending)
        val next = TaskSignal("next", "chat", text = "Next message", at = 2, capturedAt = 2)
        assertTrue(runCatching { store.capturePage("messages", 400, 401, listOf(next)) }.exceptionOrNull() is SignalInboxFullException)
        assertEquals(400, store.sourceCursor("messages"))
        val inbox = SignalInbox(store).apply { load() }
        val calendar = CalendarSignalBuffer().apply { changed() }
        assertNull(calendar.capture(inbox, 60_000, 60_000, enabled = true))
        assertEquals(pending, store.loadSignals())

        val batch = pending.take(DiscoverySchedule.MAX_BATCH)
        val patch = TaskChangeSet("scout", 0, notes = listOf(NoteChange(after = note("learned", "Useful result"))))
        val run = run("scout").copy(purpose = "scout", inputIds = batch.map { it.id })
        store.commitChanges(patch, run)
        store.commitChanges(patch, run)
        inbox.refresh()
        assertNotNull(calendar.capture(inbox, 120_000, 60_000, enabled = true))
        store.capturePage("messages", 400, 401, listOf(next))
        assertEquals(401, store.sourceCursor("messages"))
        assertEquals((pending.drop(batch.size).map { it.id } + "next").toSet(),
            store.loadSignals().filter { it.source != "calendar" }.map { it.id }.toSet())
        assertEquals("Useful result", store.loadNotes().single().text)
        assertEquals("committed", store.loadRuns().single().state)
    }

    @Test fun aConcurrentUserPinPreventsAStaleRetirement() = runBlocking {
        val store = store()
        val before = note("a", "Keep this")
        store.saveNotes(listOf(before.copy(pinned = true)))
        assertTrue(runCatching { store.commitChanges(TaskChangeSet("night", 0,
            notes = listOf(NoteChange(before, before.copy(retiredAt = 2)))), run("night")) }.isFailure)
        assertTrue(store.loadNotes().single().pinned)
        assertNull(store.loadNotes().single().retiredAt)
    }
}
