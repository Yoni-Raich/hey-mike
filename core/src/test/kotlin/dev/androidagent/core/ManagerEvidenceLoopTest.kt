package dev.androidagent.core

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManagerEvidenceLoopTest {
    private val clock = 1_000_000L
    private val zone = ZoneId.of("UTC")
    private val ref = TaskSourceRef("chat", sessionId = "manager", verified = true)

    @Test fun commonHebrewAndEnglishWordsDoNotPullUnrelatedProjectNotes() = runTest {
        val store = InMemoryTaskBoardStore()
        val memory = MemoryBook(store, now = { clock }).apply { load() }
        memory.remember("QuickCart", "fact", "יש לך את כל הנתונים וגם גישה לשיחה", "chat")
        memory.remember("Kling", "fact", "You have all the data in this conversation", "chat")
        val person = memory.remember("Dana", "fact", "Form was sent", "chat", scope = "person")
        assertTrue(TaskFollowThrough.context(memory.live, "לא אמור להיות לך את כל הנתונים האלו? גישה, נתוני שיחה?") { true }.isEmpty())
        assertTrue(memory.recall("you have all the data in this conversation").isEmpty())
        assertEquals(listOf(person.id), TaskFollowThrough.context(memory.live, "Dana form") { true }.map { it.id })
        assertTrue(TaskFollowThrough.context(memory.live, "", setOf("task")) { true }.isEmpty())
        assertEquals(listOf(person.id), TaskFollowThrough.context(listOf(person.copy(linkedTaskIds = listOf("task"))), "", setOf("task")) { true }.map { it.id })
    }

    @Test fun explicitReminderOnProposedCardDoesNotApproveOrExecuteIt() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store, now = { clock }).apply { load() }
        val task = (board.propose(TaskProposal("Fixture reminder", "why", "underlying work", usefulUntil = clock + 60_000, sourceRefs = listOf(ref)), "conversation", "manager") as ProposeOutcome.Created).task
        val memory = MemoryBook(store, now = { clock }).apply { load() }
        val tools = TaskTools(board, memory, TeamRoster(store).apply { load() }, { TaskChatRole.MANAGER }, { "unused" }, now = { clock }, zone = { zone })
        val args = buildJsonObject { put("mode", "followup"); put("id", task.id); put("until", Instant.ofEpochMilli(clock + 120_000).toString()) }
        assertFalse(tools.invoke("manager", args, false).success)
        assertTrue(tools.invoke("manager", args, true).success)
        val saved = TaskBoard(store, now = { clock }).apply { load() }.task(task.id)!!
        assertEquals(TaskStatus.PROPOSED, saved.status); assertNull(saved.decision); assertNull(saved.execution)
        assertEquals(clock + 120_000, saved.commitment!!.nextCheckAt)
        assertEquals(clock + 180_000, saved.usefulUntil)
        assertEquals(listOf(task.id), TaskFollowThrough.dueChecks(listOf(saved), clock + 120_000).map { it.id })
    }

    @Test fun probableResolutionSurvivesRestartWithoutClosingDecidedCard() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store, now = { clock }).apply { load() }
        val task = (board.propose(TaskProposal("Reply", "Question waiting", "Reply after approval", sourceRefs = listOf(ref)), "conversation", "manager") as ProposeOutcome.Created).task
        board.decide(task.id, "mine", "tap")
        val tools = TaskTools(board, MemoryBook(store).apply { load() }, TeamRoster(store).apply { load() }, { TaskChatRole.MANAGER }, { "unused" }, now = { clock })
        val result = tools.invoke("manager", buildJsonObject {
            put("mode", "observe"); put("id", task.id); put("looksResolved", "Probably answered; sender uncertain")
            put("evidence", buildJsonArray { add(buildJsonObject { put("source", "browser read"); put("detail", "A reply appears after the question; sender not exposed."); put("at", Instant.ofEpochMilli(clock).toString()) }) })
        }, false)
        assertTrue(result.text, result.success)
        val saved = TaskBoard(store).apply { load() }.task(task.id)!!
        assertEquals(TaskStatus.MINE, saved.status); assertNull(saved.execution)
        assertEquals("Probably answered; sender uncertain", saved.commitment!!.completionEvidence)
        assertEquals(clock, saved.evidence.last().at)
    }

    @Test fun attributedStateIsNotAVerifiedFactOrQuietInstructionAndHonoursRevocation() {
        val path = Files.createTempDirectory("manager-observation-test").resolve("state.json").toFile()
        try {
            val store = SharedSituationStore(path)
            store.refresh(emptyList(), clock, zone, false, false) { true }
            val observation = SituationObservation("availability", "quiet", clock - 100, clock + 1000, "inferred",
                listOf(TaskEvidence("browser read", "May be busy", at = clock - 100)), listOf(ref))
            store.observe(observation, clock)
            val restored = SharedSituationStore(path)
            val state = restored.snapshot(clock, zone, emptySet())
            assertEquals("inferred", state.observations.single().confidence)
            assertTrue("availability" in state.unknown); assertNull(SituationAttention.quietUntil(state, clock))
            assertTrue(restored.snapshot(clock, zone, emptySet(), allowedSource = { false }).observations.isEmpty())
            assertEquals("stale", restored.snapshot(clock + 1000, zone, emptySet()).observations.single().confidence)
            assertTrue(runCatching { store.observe(observation.copy(expiresAt = clock), clock) }.isFailure)
            assertTrue(runCatching { store.observe(observation.copy(observedAt = clock + 1), clock) }.isFailure)
            assertTrue(runCatching { store.observe(observation.copy(sourceRefs = emptyList()), clock) }.isFailure)
        } finally { path.parentFile.deleteRecursively() }
    }

    @Test fun deviceLocationExpiresAndIsHiddenImmediatelyWhenPermissionOrProfileChanges() {
        val path = Files.createTempDirectory("manager-location-test").resolve("state.json").toFile()
        try {
            val store = SharedSituationStore(path)
            store.refresh(emptyList(), clock, zone, false, false) { true }
            store.recordLocation(SituationLocation("fresh", clock, clock + 120_000, 32.0, 35.0, 1200f, "network", "current_android_user:0", 50_000, 1))
            val reopened = SharedSituationStore(path)
            val state = reopened.snapshot(clock, zone, emptySet(), calendarProfile = "current_android_user:0", locationAllowed = true, locationBootCount = 1, elapsedNowMs = 50_000)
            assertNotNull(state.currentLocation); assertFalse("current_location" in state.unknown)
            assertTrue("eta" in state.unknown); assertTrue("route" in state.unknown)
            assertNull(reopened.snapshot(clock, zone, emptySet(), calendarProfile = "current_android_user:0", locationAllowed = false, locationBootCount = 1, elapsedNowMs = 50_000).currentLocation)
            assertNull(reopened.snapshot(clock, zone, emptySet(), calendarProfile = "current_android_user:11", locationAllowed = true, locationBootCount = 1, elapsedNowMs = 50_000).currentLocation)
            assertNull(reopened.snapshot(clock + 120_000, zone, emptySet(), calendarProfile = "current_android_user:0", locationAllowed = true, locationBootCount = 1, elapsedNowMs = 50_000).currentLocation)
            assertNull(reopened.snapshot(clock, zone, emptySet(), calendarProfile = "current_android_user:0", locationAllowed = true, locationBootCount = 2, elapsedNowMs = 50_000).currentLocation)
            assertNull(reopened.snapshot(clock, zone, emptySet(), calendarProfile = "current_android_user:0", locationAllowed = true, locationBootCount = 1, elapsedNowMs = 170_000).currentLocation)
        } finally { path.parentFile.deleteRecursively() }
    }

    @Test fun locationRefreshCannotRunFromAutomationOrBackstageAndObservationKeepsCardSources() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store, now = { clock }).apply { load() }
        val memory = MemoryBook(store, now = { clock }).apply { load() }
        val roster = TeamRoster(store).apply { load() }
        var reads = 0
        var role = TaskChatRole.MANAGER
        var recorded: SituationObservation? = null
        val tools = TaskTools(board, memory, roster, { role }, { "unused" }, now = { clock }, zone = { zone },
            refreshLocation = { reads++; SituationLocation("permission denied", clock, clock) },
            observeSituation = { recorded = it; SharedSituation(observations = listOf(it)) })
        val refresh = buildJsonObject { put("mode", "situation"); put("operation", "refresh_location") }
        assertFalse(tools.invoke("manager", refresh, false).success); assertEquals(0, reads)
        role = TaskChatRole.COORDINATOR
        assertFalse(tools.invoke("review", refresh, true).success); assertEquals(0, reads)
        role = TaskChatRole.MANAGER
        val denied = tools.invoke("manager", refresh, true)
        assertTrue(denied.success); assertTrue(denied.text.contains("request_permissions")); assertEquals(1, reads)

        val evidenceRef = TaskSourceRef("notification", app = "fixture.app", conversation = "fixture", verified = true)
        val task = (board.propose(TaskProposal("Fixture task", "why", "plan", sourceRefs = listOf(evidenceRef)), "conversation", "manager") as ProposeOutcome.Created).task
        val observe = buildJsonObject {
            put("mode", "situation"); put("operation", "observe"); put("id", task.id)
            put("field", "travel_mode"); put("value", "May be walking"); put("confidence", "inferred")
            put("at", Instant.ofEpochMilli(clock).toString()); put("until", Instant.ofEpochMilli(clock + 60_000).toString())
            put("evidence", buildJsonArray { add(buildJsonObject { put("detail", "Ambiguous statement; not a device fix") }) })
        }
        role = TaskChatRole.ORDINARY
        assertFalse(tools.invoke("ordinary", observe, true).success); assertNull(recorded)
        role = TaskChatRole.MANAGER
        assertTrue(tools.invoke("manager", observe, true).success)
        assertTrue(evidenceRef in recorded!!.sourceRefs)
        assertTrue(tools.invoke("manager", buildJsonObject { put("mode", "status") }, true).text.contains("discoveryOn"))
        role = TaskChatRole.SCOUT
        assertFalse(tools.invoke("scout", buildJsonObject { put("mode", "status") }, true).success)
    }

    @Test fun pauseReasonSurvivesRestartWithoutAutomaticallyResumingDiscovery() = runTest {
        val store = InMemoryTaskBoardStore()
        val board = TaskBoard(store, now = { clock }).apply { load() }
        board.editSettings { it.copy(discoveryOn = false, pauseReason = "manager_chat_stop", pausedAt = clock) }
        val reloaded = TaskBoard(store, now = { clock }).apply { load() }
        assertFalse(reloaded.settings.value.discoveryOn)
        assertEquals("manager_chat_stop", reloaded.settings.value.pauseReason)
        assertEquals(clock, reloaded.settings.value.pausedAt)
    }

    @Test fun pausedBoardDoesNotShowYesterdaysBudgetAsTodaysUsage() {
        val stored = TaskBoardSettings(discoveryOn = false, passDay = "2026-11-03", passesToday = 48,
            backgroundPassesToday = 24, managerReviewDay = "2026-11-03", managerReviewsToday = 4)
        val today = DiscoverySchedule.settingsForDay(stored, "2026-11-04")
        assertEquals(0, today.passesToday); assertEquals(0, today.backgroundPassesToday); assertEquals(0, today.managerReviewsToday)
        assertFalse(today.discoveryOn); assertEquals(48, stored.passesToday)
        assertEquals(stored, DiscoverySchedule.settingsForDay(stored, "2026-11-03"))
    }
}
