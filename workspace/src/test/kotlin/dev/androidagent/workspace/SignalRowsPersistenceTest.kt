/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.workspace

import dev.androidagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignalRowsPersistenceTest {
    private val clock = 1_800_000_000_000L
    private suspend fun store() = LocalTaskBoardStore(RuntimeEnvironment.getApplication()).apply {
        saveSettings(TaskBoardSettings(discoveryOn = true))
    }

    @Test fun aCommitCannotDeleteNewInputMergedWhileScoutWasReading() = runBlocking {
        val store = store()
        val inbox = SignalInbox(store, { clock }).apply { load() }
        val first = inbox.capture(CapturedMessage("com.whatsapp", "Dana", "Dana", "First", clock, false, fromPerson = true), store.loadSettings(), "mike")!!
        val run = TaskRun("pass", "scout", "scout", 0, listOf(first.id), clock, inputVersions = mapOf(first.id to first.revision))
        inbox.capture(CapturedMessage("com.whatsapp", "Dana", "Dana", "Second", clock + 1, false, fromPerson = true), store.loadSettings(), "mike")
        val changed = store.loadSignals().single()
        assertTrue(changed.revision > first.revision)
        store.commitChanges(TaskChangeSet("pass", 0), run)
        assertEquals(listOf("First", "Second"), store.loadSignals().single().items.map { it.text })
        // A repeated commit is idempotent, including source counters.
        store.commitChanges(TaskChangeSet("pass", 0), run)
        assertEquals(1, store.loadSourceStats().single().reviewed)
        store.commitChanges(TaskChangeSet("next", 0), run.copy(id = "next", inputVersions = mapOf(first.id to changed.revision)))
        assertTrue(store.loadSignals().isEmpty())
        val reopened = LocalTaskBoardStore(RuntimeEnvironment.getApplication())
        assertEquals(2, reopened.loadSourceStats().single().received)
        assertEquals(2, reopened.loadSourceStats().single().reviewed)
        assertTrue(reopened.loadSignals().isEmpty())
    }

    @Test fun captureCursorCountersAndRowsRollBackTogether() = runBlocking {
        val store = store()
        val row = TaskSignal("a", "chat", text = "", at = clock, capturedAt = clock, lane = SignalLane.BACKGROUND, revision = 1)
        val delta = SourceStat.base("app", clock).copy(received = 1, distinct = 1)
        store.capturePage("feed", 0, 1, listOf(row), stats = listOf(delta))
        assertTrue(runCatching { store.capturePage("feed", 0, 2, listOf(row.copy(id = "b")), stats = listOf(delta)) }.isFailure)
        assertEquals(1, store.sourceCursor("feed"))
        assertEquals(listOf("a"), store.loadSignals().map { it.id })
        assertEquals(1, store.loadSourceStats().single().received)
        store.saveSettings(store.loadSettings().copy(excludedApps = setOf("app")))
        assertTrue(runCatching { store.capturePage("feed", 1, 2, emptyList(), stats = listOf(delta)) }.isFailure)
        assertEquals(1, store.sourceCursor("feed"))
        assertEquals(1, store.loadSourceStats().single().received)
    }

    @Test fun aFinalPhasePromotionReachesBothTheLiveTranscriptAndDurableFeed() = runBlocking {
        val store = LocalSessionStore(RuntimeEnvironment.getApplication())
        val chat = store.createSession()
        store.append(ChatMessage("m", chat.id, "assistant", "A reply", clock))
        val previous = store.messageChanges(0).last().sequence
        store.setMessagePhase("m", "commentary")
        assertEquals("commentary", store.messages(chat.id).first().single().phase)
        assertTrue(store.messageChanges(previous).isEmpty())
        store.setMessagePhase("m", "final_answer")
        assertEquals("final_answer", store.messages(chat.id).first().single().phase)
        assertEquals("final_answer", store.messageChanges(previous).single().message.phase)
        val reopened = LocalSessionStore(RuntimeEnvironment.getApplication())
        assertEquals("final_answer", reopened.messages(chat.id).first().single().phase)
        store.setMessagePhase("m", "final_answer")
        assertEquals(1, store.messageChanges(previous).size)
    }

    @Test fun migrationAndSeenStatusReceiptsSurviveReopenWithoutKeepingChatTextInTheQueue() = runBlocking {
        val store = store()
        store.insertSignal(TaskSignal("old", "chat", sessionId = "oldchat", sender = "User", text = "Old discussion",
            at = clock - 1, capturedAt = clock))
        store.insertSignal(TaskSignal("notice", "notification", app = "app", text = "Status", at = clock,
            capturedAt = clock, sourceVerified = true))
        SignalInbox(store, { clock }).apply { load(); migrate(clock) { false } }
        val reopened = LocalTaskBoardStore(RuntimeEnvironment.getApplication())
        val row = reopened.loadSignals().single()
        assertEquals("app", row.app)
        assertEquals("Status", row.items.single().text)
        val inbox = SignalInbox(reopened, { clock }).apply { load() }
        inbox.processed(listOf(row.id))
        assertNull(inbox.capture(CapturedMessage("app", null, null, "Status", clock + 1, false), reopened.loadSettings(), "mike"))
        assertTrue(reopened.loadSignals().isEmpty())
    }
}
