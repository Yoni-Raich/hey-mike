// Hey Mike. Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
package dev.androidagent.core

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class VoiceChildUpdatesTest {
    private var time = 0L
    private val policy = VoiceChildUpdates { time }
    private fun update(text: String = "Found three options.", state: String = "complete", role: String = "assistant",
                       offset: Int = 0, replace: Boolean = false, status: String = "running", task: String = "one",
                       message: String = "message", allowed: (String) -> Boolean = { true }) =
        SessionAgentUpdate(SessionAgentTask(task, "request-$task", "parent", "Read notes", "Original task", EngineKind.CODEX,
            sessionId = "child-$task", createdAt = 0, runId = "run-$task", status = status,
            result = text.takeIf { status == "completed" }),
            listOf(SessionAgentOutput(message, role, text, state, offset, replace)), true, listOf("parent"), allowed)
    private fun receive(vararg updates: SessionAgentUpdate) = policy.receive(updates.toList(), "parent")
    private fun take() = policy.take("parent", VoicePhase.LISTENING)

    @Test fun streamingAndTechnicalActivityNeverCreateEmptyAnnouncements() {
        receive(update("Working", role = "remote_activity"), update("Working in session files", role = "assistant"))
        repeat(30) { receive(update("A".repeat(it + 1), "streaming")) }
        assertTrue(take().isEmpty())
        assertEquals(0, policy.state("parent").pendingChildren)
        receive(update("A useful complete result."))
        assertEquals("A useful complete result.", take().single().text)
    }

    @Test fun completeStreamingRevisionIsReassembledWithoutSpeakingEachFragment() {
        receive(update("Found ", "streaming"))
        receive(update("three ", "streaming", offset = 6))
        receive(update("options.", "complete", offset = 12))
        assertEquals("Found three options.", take().single().text)
    }

    @Test fun missingPrefixCannotBecomeASpokenSuffix() {
        receive(update("options.", offset = 12))
        assertTrue(take().isEmpty())
    }

    @Test fun repeatedNoFinalReplyDiagnosticsAcrossRunsRemainQuietContextOnly() = runTest {
        val diagnostic = "The run ended without a final reply. Check the activity details for the actions that completed."
        val contexts = mutableListOf<String>()
        repeat(10) { run ->
            val update = update(diagnostic, status = "completed").let { it.copy(task = it.task.copy(runId = "run-$run")) }
            update.deliverVoiceContext({ _, quote -> contexts += quote }, { true })
            receive(update)
            assertTrue(take().isEmpty())
            time += 10_000
        }
        assertEquals(10, contexts.size)
        assertTrue(contexts.all { it.contains(diagnostic) })
        assertEquals(0, policy.state("parent").pendingChildren)
    }

    @Test fun technicalOrNoChangeCompleteRevisionsDoNotRepeatOlderProgress() {
        receive(update()); take(); policy.assistantStarted(); policy.assistantFinished(); time += 10_000
        listOf("Working in session files...", "No changes.", "No new updates", "").forEach { text ->
            receive(update(text, status = "completed"))
            assertTrue(take().isEmpty())
        }
        receive(update("Found a new important result.", status = "completed"))
        assertEquals("Found a new important result.", take().single().text)
    }

    @Test fun replacementsAndSameRevisionAreSpokenAtMostOnce() {
        val corrected = update("Actually, two options.", replace = true)
        receive(update(), corrected, corrected)
        assertEquals("Actually, two options.", take().single().text)
        policy.assistantStarted(); policy.assistantFinished(); time += 10_000
        receive(corrected)
        assertTrue(take().isEmpty())
    }

    @Test fun onlyNewestCompleteMessageFromEachChildIsGroupedOnResume() {
        policy.setPaused(true)
        repeat(30) { receive(update("Useful progress $it", message = "message-$it")) }
        receive(update("Full final result", status = "completed"), update("Another final result", status = "completed", task = "two"))
        assertTrue(take().isEmpty())
        assertEquals(2, policy.state("parent").pendingChildren)
        policy.setPaused(false)
        val batch = take()
        assertEquals(listOf("Full final result", "Another final result"), batch.map { it.text })
        val handoff = VoiceChildUpdates.handoff(batch)
        assertFalse(handoff.contains("Useful progress"))
        assertTrue(handoff.contains("one brief spoken summary"))
    }

    @Test fun inputStartsBlockingBeforeTranscriptAndAPauseIsNotEndOfTurn() {
        receive(update())
        policy.input(0.8f); time += 130; policy.input(0.8f)
        time += 5_000; policy.input(0f)
        assertTrue(take().isEmpty())
        policy.userFinished()
        time += 10_000
        assertTrue(take().isEmpty()) // Mike still owes the user's reply.
        policy.assistantStarted(); time += 10_000
        assertTrue(take().isEmpty())
        policy.assistantFinished(); time += 1_499
        assertTrue(take().isEmpty())
        time++
        assertEquals(1, take().size)
    }

    @Test fun inputSpikeUsesQuietIntervalAndMuteCanReleaseUnfinalizedInput() {
        receive(update()); policy.input(0.8f)
        assertTrue(take().isEmpty())
        time += 1_500
        assertEquals(1, take().size)
        policy.reset(); receive(update())
        policy.userStarted(); time += 10_000
        assertTrue(take().isEmpty())
        policy.microphoneMuted(); time += 1_500
        assertEquals(1, take().size)
    }

    @Test fun delayedPlayoutAndShortOutputPauseCannotReleaseAnotherAnnouncement() {
        receive(update()); take()
        receive(update("New finding.", message = "second")); time += 30_000
        assertTrue(take().isEmpty()) // Submission without a response is not completion.
        policy.output(); time += 300
        assertTrue(take().isEmpty())
        policy.output(); time += 1_500
        assertEquals("New finding.", take().single().text)
    }

    @Test fun quietSuppressesCurrentChildPlaybackWithoutCancellingTheCall() {
        receive(update()); take(); policy.assistantStarted()
        policy.setPaused(true)
        assertTrue(policy.playbackSuppressed())
        receive(update("New result", status = "completed", task = "two"))
        policy.assistantFinished(); time += 20_000
        assertTrue(take().isEmpty())
        assertEquals(2, policy.state("parent").pendingChildren)
        policy.userStarted(); policy.userFinished(); policy.assistantStarted()
        assertFalse(policy.playbackSuppressed()) // A normal user reply can speak during quiet.
        assertTrue(policy.state("parent").paused)
    }

    @Test fun userBargeInAfterReservationSilencesTheChildAndBlocksSubmission() {
        receive(update()); take()
        policy.input(0.8f)
        assertTrue(policy.playbackSuppressed())
        assertFalse(policy.canSubmit(VoicePhase.LISTENING))
    }

    @Test fun audioOnlyUserReplyRestoresPlaybackWithoutAnAssistantTranscriptDelta() {
        receive(update()); take(); policy.input(0.8f)
        policy.setPaused(true)
        assertTrue(policy.playbackSuppressed())
        policy.userFinished() // A real status question; no assistant delta ever follows.
        assertFalse(policy.playbackSuppressed()) // Restore before the first reply audio frame.
        policy.output(); time += 10_000
        assertTrue(policy.state("parent").paused)
        assertTrue(take().isEmpty())
        policy.acknowledgePending("parent"); receive(update("Later useful result", task = "two"))
        policy.setPaused(false)
        assertEquals("Later useful result", take().single().text) // No stale awaiting-reply latch.
    }

    @Test fun doneOnlyUserReplyRestoresPlaybackButAQuietCommandDoesNot() {
        receive(update()); take(); policy.userStarted(); policy.setPaused(true)
        policy.userFinished(expectReply = false) // Saying quiet must leave the child muted.
        policy.assistantFinished(); time += 10_000
        assertTrue(policy.playbackSuppressed())
        assertTrue(take().isEmpty())
        policy.userStarted(); policy.userFinished() // The user asks a new normal question.
        assertFalse(policy.playbackSuppressed())
        policy.assistantFinished() // Only TranscriptDone; no TranscriptDelta.
        assertTrue(policy.state("parent").paused)
        assertTrue(take().isEmpty())
    }

    @Test fun finishedTranscriptDoesNotLeaveTheLastAudioOutsideQuietControl() {
        receive(update()); take(); policy.assistantStarted(); policy.output()
        policy.assistantFinished(); time += 400; policy.output()
        policy.setPaused(true)
        assertTrue(policy.playbackSuppressed())
        receive(update("Next useful result", task = "two"))
        time += 10_000
        assertTrue(take().isEmpty())
        assertTrue(policy.playbackSuppressed())
        policy.setPaused(false)
        assertEquals(listOf("Found three options.", "Next useful result"), take().map { it.text })
        assertFalse(policy.playbackSuppressed())
    }

    @Test fun mikeReplyHoldsAnnouncementsEvenWhenTheUiHasReturnedToListening() {
        receive(update())
        policy.assistantStarted(); policy.output(); time += 5_000
        assertTrue(take().isEmpty()) // A long pause is still inside Mike's transcript turn.
        policy.assistantFinished(); time += 1_500
        assertEquals(1, take().size)
    }

    @Test fun knownUnsubmittedBatchCanWaitButUnknownSubmissionNeverReplays() {
        receive(update()); val batch = take()
        policy.setPaused(true); policy.defer(batch, "parent")
        assertEquals(1, policy.state("parent").pendingChildren)
        policy.setPaused(false); time += 8_000
        assertEquals(1, take().size)
        time += 30_000
        assertTrue(take().isEmpty())
    }

    @Test fun finalAndUserGateRemainAvailableWhileQuietAndStopFencesWin() {
        var allowed = true
        policy.setPaused(true)
        receive(update("A full result.", status = "completed", allowed = { allowed }),
            update("", status = "waiting_for_user", task = "two").let { it.copy(task = it.task.copy(question = "Which folder?")) })
        assertEquals(2, policy.state("parent").pendingChildren)
        allowed = false
        assertEquals(1, policy.state("parent").pendingChildren)
        policy.setPaused(false)
        assertEquals("Which folder?", take().single().task.question)
    }

    @Test fun resetCannotMoveOldAnnouncementsIntoTheNextCall() {
        policy.setPaused(true); receive(update()); policy.userStarted()
        policy.reset()
        assertEquals(VoiceChildUpdateState(), policy.state("parent"))
        assertTrue(take().isEmpty())
        receive(update("Different call's result"))
        assertEquals(1, take().size)
    }

    @Test fun explicitStatusQuestionConsumesPendingWithoutChangingQuiet() {
        policy.setPaused(true); receive(update(), update("Other progress", task = "two"))
        policy.acknowledgePending("parent")
        assertEquals(VoiceChildUpdateState(paused = true), policy.state("parent"))
        policy.setPaused(false); receive(update(), update("Other progress", task = "two"))
        assertTrue(take().isEmpty())
    }

    @Test fun interruptedResultSurvivesDuplicateSignalsAndResumeUsesTheNewestRevision() {
        val first = update()
        receive(first); take(); policy.assistantStarted()
        policy.setPaused(true); receive(first)
        assertEquals(1, policy.state("parent").pendingChildren)
        receive(update("Full new final result", status = "completed"))
        policy.assistantFinished(); time += 10_000
        policy.setPaused(false)
        assertEquals("Full new final result", take().single().text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun batchDeliveryKeepsReceivingDuringBothSpeakersTurns() = runTest {
        val policy = VoiceChildUpdates { testScheduler.currentTime }
        val events = MutableSharedFlow<SessionAgentUpdate>()
        val phase = MutableStateFlow(VoicePhase.SPEAKING)
        val contexts = mutableListOf<String>()
        SessionAgentDelivery(backgroundScope, events, phase, { true }, { source, batch, allowed ->
            batch.forEach { it.deliverVoiceContext({ _, quote -> contexts += quote }, allowed) }
            policy.receive(batch, source)
            true
        })
        policy.assistantStarted(); runCurrent()
        repeat(30) { events.emit(update("Fragment $it", "streaming")) }
        events.emit(update("Full first result", status = "completed"))
        events.emit(update("Full second result", status = "completed", task = "two"))
        advanceTimeBy(501); runCurrent()
        assertEquals(32, contexts.size)
        assertTrue(contexts.last().contains("Full second result"))
        assertEquals(2, policy.state("parent").pendingChildren)
        assertTrue(policy.take("parent", phase.value).isEmpty())
        policy.assistantFinished(); policy.userStarted(); phase.value = VoicePhase.LISTENING
        advanceTimeBy(5_000); runCurrent()
        assertTrue(policy.take("parent", phase.value).isEmpty())
        policy.userFinished(); policy.assistantStarted(); policy.assistantFinished()
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf("Full first result", "Full second result"), policy.take("parent", phase.value).map { it.text })
    }

    @Test fun fullQuietContextStillIncludesStreamingActivityAndLongResults() = runTest {
        val contexts = mutableListOf<String>()
        val updates = listOf(update("Working", role = "remote_activity"), update("a fragment", "streaming"),
            update("אבג🙂".repeat(6_000) + "TAIL", status = "completed"))
        policy.userStarted(); policy.setPaused(true)
        updates.forEach { it.deliverVoiceContext({ guidance, quote ->
            assertTrue(guidance.contains("quiet context only")); contexts += quote
        }, { true }) }
        receive(*updates.toTypedArray())
        assertTrue(contexts.any { it.contains("a fragment") })
        assertTrue(contexts.last().contains("TAIL")); assertTrue(take().isEmpty())
        assertEquals(1, policy.state("parent").pendingChildren)
    }

    @Test fun commandsAreExactUserIntentsAndDoNotConsumeOtherText() {
        assertEquals(true, VoiceChildUpdates.command("Please be quiet!"))
        assertEquals(true, VoiceChildUpdates.command("שקט."))
        assertEquals(false, VoiceChildUpdates.command("Resume updates"))
        assertTrue(VoiceChildUpdates.statusRequest("מה העדכונים?"))
        assertNull(VoiceChildUpdates.command("The child said: be quiet"))
    }
}
