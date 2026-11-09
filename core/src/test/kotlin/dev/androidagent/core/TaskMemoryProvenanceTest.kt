package dev.androidagent.core

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskMemoryProvenanceTest {
    @Test fun scopedRecallAndLinkedContextAreRelevantWithoutCountingAsUse() = runTest {
        val store = InMemoryTaskBoardStore()
        val memory = MemoryBook(store).apply { load() }
        val story = memory.remember("children story", "preference", "Use Opus for this project", "chat", scope = "project", linkedTaskIds = listOf("story-card"))
        val global = memory.remember("owner", "preference", "Short replies", "chat", scope = "global", level = "profile")
        assertTrue(memory.recall("use this for doctor", scope = "project").isEmpty())
        assertEquals(listOf(story.id), memory.recall("children story", scope = "project").map { it.id })
        assertEquals(listOf(global.id), memory.recall(null, level = "profile", scope = "global").map { it.id })
        assertTrue(TaskFollowThrough.context(memory.live, "doctor", allowed = { true }).none { it.id == story.id })
        assertTrue(TaskFollowThrough.context(memory.live, "continue", setOf("story-card")) { true }.any { it.id == story.id })
        assertEquals(0, memory.live.first { it.id == story.id }.useCount)
    }

    @Test fun recalledNotesDoNotBecomeSourcesOfUnrelatedClaims() = runTest {
        val store = InMemoryTaskBoardStore()
        val memory = MemoryBook(store).apply { load() }
        val sourceA = TaskSourceRef("chat", sessionId = "source-a", verified = true)
        val sourceB = TaskSourceRef("chat", sessionId = "source-b", verified = true)
        val a = memory.remember("Ada", "fact", "Ada form", "chat", sourceRefs = listOf(sourceA))
        val b = memory.remember("Bela", "fact", "Bela form", "chat", sourceRefs = listOf(sourceB))
        val tools = TaskTools(TaskBoard(store).apply { load() }, memory, TeamRoster(store).apply { load() },
            { TaskChatRole.MANAGER }, { "unused" })
        assertTrue(tools.invoke("manager", buildJsonObject { put("mode", "recall"); put("query", "form") }, false).success)
        assertTrue(tools.invoke("manager", buildJsonObject {
            put("mode", "remember"); put("subject", "Ada"); put("text", "Ada signed the form")
            put("noteIds", buildJsonArray { add(a.id) })
        }, false).success)
        val saved = memory.live.single { it.text == "Ada signed the form" }
        assertTrue(sourceA in saved.sourceRefs)
        assertFalse(sourceB in saved.sourceRefs)
        assertTrue(saved.sourceRefs.any { it.noteId == a.id })
        assertFalse(saved.sourceRefs.any { it.noteId == b.id })
        assertEquals(0, memory.live.first { it.id == a.id }.useCount)
        val oversized = (1..65).map { TaskSourceRef("chat", sessionId = "source-$it", verified = true) }
        assertTrue(runCatching { memory.remember("Other", "fact", "A bounded fact", "chat", sourceRefs = oversized) }.isFailure)
        assertFalse(memory.live.any { it.subject == "Other" })
    }

    @Test fun coachAppliedRequiresFileCitingThatExactEvent() = runTest {
        val store = InMemoryTaskBoardStore()
        val home = Files.createTempDirectory("coach-citation-").toFile()
        val book = TaskPlaybook(home).apply { seed() }
        val recorded = mutableListOf<CoachDisposition>()
        val tools = TaskTools(TaskBoard(store).apply { load() }, MemoryBook(store).apply { load() }, TeamRoster(store).apply { load() },
            { TaskChatRole.COACH }, { "unused" }, playbook = book, reviewEvidence = setOf("event-a", "event-b"),
            recordCoachDisposition = { recorded += it })
        fun decision(id: String) = buildJsonObject {
            put("mode", "coach_review"); put("id", id); put("disposition", "applied"); put("note", "Clear change"); put("reviewEvidence", "Owner feedback")
        }
        assertFalse(tools.invoke("coach", decision("event-a"), false).success)
        assertTrue(tools.invoke("coach", buildJsonObject {
            put("mode", "playbook"); put("operation", "write"); put("path", "kinds/example.md")
            put("content", "event-a: show useful reminders")
            put("eventIds", buildJsonArray { add("event-a") })
        }, false).success)
        assertFalse(tools.invoke("coach", decision("event-b"), false).success)
        assertTrue(tools.invoke("coach", decision("event-a"), false).success)
        assertEquals(listOf("kinds/example.md"), recorded.single().appliedFiles)
    }

    @Test fun coachAppliedNeedsFinalChangedFileAndExactEventSource() {
        val path = "kinds/example.md"
        val decision = CoachDisposition("event-a", "applied", "Clear change", "Owner feedback", listOf(path))
        val ref = TaskSourceRef("user", verified = true, eventId = "event-a")
        fun valid(before: String?, after: String?, refs: List<TaskSourceRef>) = runCatching {
            CoachReview.validateFinalFiles(listOf(decision), { before }, { after }, { refs })
        }.isSuccess
        assertTrue(valid("old", "event-a: new rule", listOf(ref)))
        assertFalse(valid("event-a: old", "event-a: old", listOf(ref)))
        assertFalse(valid("old", "event-ab: unrelated change", listOf(ref)))
        assertFalse(valid("old", "event-a: new rule", emptyList()))
        assertFalse(valid("old", null, listOf(ref)))
    }

    @Test fun cardSourceLimitPreservesExistingAncestryAndOwnerControl() = runTest {
        val store = InMemoryTaskBoardStore()
        val refs = (1..64).map { TaskSourceRef("chat", sessionId = "source-$it", verified = true) }
        val task = MikeTask("card", "Form", "Why", "Plan", originKind = "chat", topicKey = "form",
            createdAt = 1, updatedAt = 1, sourceRefs = refs)
        store.saveTask(task)
        val board = TaskBoard(store, now = { 1_000_000L }).apply { load() }
        val newRef = TaskSourceRef("chat", sessionId = "new-source", verified = true)
        assertTrue(runCatching { board.update(task.id, why = "new reason", sourceRefs = listOf(newRef)) }.isFailure)
        assertEquals(refs, store.loadTasks().single().sourceRefs)
        board.update(task.id, why = "owner can still update")
        assertEquals(refs, store.loadTasks().single().sourceRefs)
        val legacy = store.loadTasks().single().copy(sourceRefs = refs + newRef)
        store.saveTask(legacy)
        val reopened = TaskBoard(store, now = { 1_000_000L }).apply { load() }
        reopened.setNote(task.id, "Owner correction")
        assertEquals(legacy.sourceRefs, store.loadTasks().single().sourceRefs)
        assertTrue(runCatching { reopened.update(task.id, sourceRefs = listOf(TaskSourceRef("chat", sessionId = "extra", verified = true))) }.isFailure)
    }

    @Test fun situationReadAndCorrectionUseOwnerAndExpiry() = runTest {
        val store = InMemoryTaskBoardStore()
        val shared = SharedSituation(revision = 3)
        var correction: Triple<String, String, Long?>? = null
        val tools = TaskTools(TaskBoard(store).apply { load() }, MemoryBook(store).apply { load() }, TeamRoster(store).apply { load() },
            { TaskChatRole.MANAGER }, { "unused" }, situation = { shared }, correctSituation = { field, value, until ->
                correction = Triple(field, value, until); shared.copy(revision = 4)
            })
        val read = tools.invoke("manager", buildJsonObject { put("mode", "situation") }, false)
        assertTrue(read.text, read.success)
        assertEquals(3, Json.parseToJsonElement(read.text).jsonObject["situation"]!!.jsonObject["revision"]!!.jsonPrimitive.int)
        val edit = buildJsonObject { put("mode", "situation"); put("field", "travel_mode"); put("value", "train"); put("until", "2026-11-02T13:00:00Z") }
        assertFalse(tools.invoke("manager", edit, false).success)
        assertNull(correction)
        assertTrue(tools.invoke("manager", edit, true).success)
        assertEquals(Triple("travel_mode", "train", java.time.Instant.parse("2026-11-02T13:00:00Z").toEpochMilli()), correction)
    }
}
