/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentMikeTest {
    @get:Rule val temp = TemporaryFolder()
    private fun stateFile() = File(temp.root, "mike/state.json")

    @Test fun correctionReplacesTheFactAndSurvivesRestart() {
        val first = MikeStateStore(stateFile())
        val memory = first.remember("reply.language", "English", "preference", "chat-a")
        MikeStateStore(stateFile()).remember("reply.language", "Hebrew", "preference", "chat-b", memory.revision)
        val saved = MikeStateStore(stateFile()).snapshot().memories.single()
        assertEquals("Hebrew", saved.text)
        assertEquals(2L, saved.revision)
        assertEquals("chat-b", saved.sourceSessionId)
    }

    @Test fun concurrentEditorsCannotOverwriteACorrection() {
        val first = MikeStateStore(stateFile())
        val second = MikeStateStore(stateFile())
        val saved = first.remember("food", "old", "fact", null)
        second.remember("food", "corrected", "fact", null, saved.revision)
        assertThrows(IllegalArgumentException::class.java) { first.remember("food", "stale edit", "fact", null, saved.revision) }
        assertEquals("corrected", first.snapshot().memories.single().text)
    }

    @Test fun keyboardCapitalizationDoesNotCreateASecondMemory() {
        val store = MikeStateStore(stateFile())
        val first = store.remember("QA.marker", "first", "fact", null)
        val corrected = store.remember("qa.marker", "corrected", "fact", null, first.revision)
        assertEquals("qa.marker", corrected.key)
        assertEquals(1, store.snapshot().memories.size)
        store.forget("QA.MARKER", corrected.revision)
        assertTrue(store.snapshot().memories.isEmpty())
    }

    @Test fun corruptStateIsPreservedAndCannotBeOverwritten() {
        val file = stateFile().apply { parentFile.mkdirs(); writeText("broken personal state") }
        assertThrows(Exception::class.java) { MikeStateStore(file).remember("fact", "new", "fact", null) }
        assertEquals("broken personal state", file.readText())
    }

    @Test fun aCorrectionReachesEveryNextTurnAndDeletingAChatDoesNotDeleteMemory() = runBlocking {
        val sessions = Chats(temp.root)
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { _, _ -> })
        val first = sessions.createSession()
        mike.store.remember("city", "Haifa", "fact", first.id)
        val second = sessions.createSession(EngineKind.CLAUDE)
        assertTrue(mike.context(second.id).contains("Haifa"))
        mike.store.remember("city", "Jerusalem", "fact", first.id)
        sessions.deleteSession(first.id)
        val current = mike.context(second.id)
        assertTrue(current.contains("Jerusalem"))
        assertFalse(current.contains("Haifa"))
    }

    @Test fun queuedIsNotDoneAndACompletedTurnWithoutTaskProofIsPaused() = runBlocking {
        val sessions = Chats(temp.root)
        val turns = mutableListOf<QueuedTurn>()
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { turn, _ -> turns += turn })
        val task = mike.createTask("Check weather", "Check tomorrow's weather")
        assertTrue(turns.isEmpty())
        assertEquals(MikeTaskStatus.QUEUED, mike.runTask(task.id).status)
        mike.started(task.sessionId)
        mike.finished(task.sessionId, "complete", "I need more information")
        assertEquals(MikeTaskStatus.PAUSED, mike.state.value.tasks.single().status)
        assertTrue(sessions.messages(mike.home().id).first().single().text.contains("paused"))
    }

    @Test fun resultReachesMainChatOnlyAfterSuccessfulFinish() = runBlocking {
        val sessions = Chats(temp.root)
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { _, _ -> })
        val task = mike.createTask("Research", "Read the files")
        mike.runTask(task.id); mike.started(task.sessionId)
        mike.checkpoint(task.sessionId, task.id, "done", "Found three useful files", null)
        assertEquals(MikeTaskStatus.RUNNING, mike.state.value.tasks.single().status)
        mike.finished(task.sessionId, "complete", "Done")
        assertEquals(MikeTaskStatus.DONE, mike.state.value.tasks.single().status)
        assertTrue(sessions.messages(mike.home().id).first().single().text.contains("Found three useful files"))
    }

    @Test fun failedTurnCannotKeepAPrematureDoneClaim() = runBlocking {
        val sessions = Chats(temp.root)
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { _, _ -> })
        val task = mike.createTask("Task", "Do something")
        mike.runTask(task.id); mike.started(task.sessionId)
        mike.checkpoint(task.sessionId, task.id, "done", "Done", null)
        mike.finished(task.sessionId, "error", "Tool failed")
        assertEquals(MikeTaskStatus.FAILED, mike.state.value.tasks.single().status)
    }

    @Test fun restartNeverReplaysAnUnknownSideEffect() = runBlocking {
        val sessions = Chats(temp.root)
        val turns = mutableListOf<QueuedTurn>()
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { turn, _ -> turns += turn })
        val task = mike.createTask("Send", "Send a message")
        mike.runTask(task.id); mike.started(task.sessionId)
        val restored = MikeStateStore(stateFile())
        restored.recover()
        val restarted = PersistentMike(restored, sessions, { turn, _ -> turns += turn })
        restarted.wakeDue()
        assertEquals(1, turns.size)
        assertEquals(MikeTaskStatus.UNKNOWN, restored.state.value.tasks.single().status)
    }

    @Test fun waitingTaskWakesOnceAndStopHoldsIt() = runBlocking {
        var clock = 1000L
        val sessions = Chats(temp.root)
        val turns = mutableListOf<QueuedTurn>()
        val mike = PersistentMike(MikeStateStore(stateFile()) { clock }, sessions, { turn, _ -> turns += turn }, now = { clock })
        val task = mike.createTask("Wait", "Check later")
        mike.runTask(task.id); mike.started(task.sessionId)
        mike.checkpoint(task.sessionId, task.id, "wait", "Check when ready", 2000L)
        mike.finished(task.sessionId, "complete", "Waiting")
        clock = 3000L
        mike.store.pauseAll(); mike.wakeDue()
        assertEquals(1, turns.count { it.sessionId == task.sessionId })
        mike.store.resume(); mike.wakeDue(); mike.wakeDue()
        assertEquals(2, turns.count { it.sessionId == task.sessionId })
    }

    @Test fun oneChatCannotFinishAnotherChatsTask() = runBlocking {
        val sessions = Chats(temp.root)
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { _, _ -> })
        val task = mike.createTask("Task", "Do something")
        mike.runTask(task.id); mike.started(task.sessionId)
        assertThrows(IllegalArgumentException::class.java) { mike.checkpoint("another", task.id, "done", "Done", null) }
        Unit
    }

    @Test fun corruptMemoryDoesNotPreventAnOrdinaryChatButIsNeverOverwritten() = runBlocking {
        stateFile().apply { parentFile.mkdirs(); writeText("damaged") }
        val store = MikeStateStore(stateFile())
        val mike = PersistentMike(store, Chats(temp.root), { _, _ -> })
        assertNotNull(store.error.value)
        assertTrue(mike.context("ordinary").contains("could not be read"))
        mike.started("ordinary")
        assertThrows(Exception::class.java) { store.remember("city", "Haifa", "fact", null) }
        assertEquals("damaged", stateFile().readText())
    }

    @Test fun wakeCannotRaceTheOriginalTurnAndCrashDuringWaitNeedsReview() = runBlocking {
        var clock = 1000L
        val sessions = Chats(temp.root)
        val turns = mutableListOf<QueuedTurn>()
        val store = MikeStateStore(stateFile()) { clock }
        val mike = PersistentMike(store, sessions, { turn, _ -> turns += turn }, now = { clock })
        val task = mike.createTask("Later", "Check later")
        mike.runTask(task.id); mike.started(task.sessionId)
        mike.checkpoint(task.sessionId, task.id, "wait", "Wait for result", 2000)
        clock = 3000
        assertNull(mike.nextWakeAt())
        mike.wakeDue()
        assertEquals(1, turns.size)
        store.recover()
        assertEquals(MikeTaskStatus.UNKNOWN, store.state.value.tasks.single().status)
        assertNull(mike.nextWakeAt())
    }

    @Test fun unknownTaskNeedsAnExplicitCheckedRetryAndStopIsIdempotent() = runBlocking {
        val sessions = Chats(temp.root)
        val turns = mutableListOf<Pair<QueuedTurn, Boolean>>()
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { turn, explicit -> turns += turn to explicit })
        val task = mike.createTask("Action", "Do the action")
        mike.runTask(task.id); mike.started(task.sessionId); mike.store.recover()
        var rejected = false
        try { mike.runTask(task.id) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        mike.runTask(task.id, checkedUnknown = true); mike.started(task.sessionId)
        mike.store.pauseAll()
        mike.finished(task.sessionId, "interrupted", "Stopped")
        mike.finished(task.sessionId, "complete", "late result")
        assertEquals(MikeTaskStatus.PAUSED, mike.state.value.tasks.single().status)
        assertFalse(mike.state.value.tasks.single().turnActive)
        assertEquals(2, turns.size)
        assertTrue(turns.all { it.second })
        assertEquals(1, sessions.messages(mike.home().id).first().size)
    }

    @Test fun taskResultsRequestASeparateMainTurnAndLessonsReachOtherChats() = runBlocking {
        val sessions = Chats(temp.root)
        val turns = mutableListOf<Pair<QueuedTurn, Boolean>>()
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { turn, explicit -> turns += turn to explicit })
        val task = mike.createTask("Files", "Read the files")
        mike.runTask(task.id); mike.started(task.sessionId)
        mike.checkpoint(task.sessionId, task.id, "done", "File was saved successfully", null)
        mike.finished(task.sessionId, "complete", "Finished")
        assertEquals(mike.home().id, turns.last().first.sessionId)
        assertFalse(turns.last().second)
        assertTrue(turns.last().first.prompt.contains("File was saved successfully"))
        val tools = MikeToolGateway(mike, sessions)
        val home = mike.home()
        tools.beginRun("run", sessions.workspace(home.id))
        val saved = tools.invoke("mike_memory", kotlinx.serialization.json.buildJsonObject {
            put("mode", kotlinx.serialization.json.JsonPrimitive("save")); put("key", kotlinx.serialization.json.JsonPrimitive("lesson.file"))
            put("text", kotlinx.serialization.json.JsonPrimitive("Read the file back after saving.")); put("kind", kotlinx.serialization.json.JsonPrimitive("lesson"))
        })
        assertTrue(saved.success)
        assertTrue(mike.context(sessions.createSession(EngineKind.CLAUDE).id).contains("Read the file back"))
        tools.revoke()
        assertFalse(tools.invoke("mike_memory", kotlinx.serialization.json.buildJsonObject { }).success)
    }

    @Test fun badRevisionCannotSilentlyDisableCorrectionProtection() = runBlocking {
        val sessions = Chats(temp.root)
        val mike = PersistentMike(MikeStateStore(stateFile()), sessions, { _, _ -> })
        val home = mike.home()
        val tools = MikeToolGateway(mike, sessions)
        tools.beginRun("run", sessions.workspace(home.id))
        val result = tools.invoke("mike_memory", kotlinx.serialization.json.buildJsonObject {
            put("mode", kotlinx.serialization.json.JsonPrimitive("save")); put("key", kotlinx.serialization.json.JsonPrimitive("city"))
            put("text", kotlinx.serialization.json.JsonPrimitive("Haifa")); put("expectedRevision", kotlinx.serialization.json.JsonPrimitive("invalid"))
        })
        assertFalse(result.success)
        assertTrue(mike.state.value.memories.isEmpty())
        mike.store.remember("city", "Haifa", "fact", home.id)
        val stale = tools.invoke("mike_memory", kotlinx.serialization.json.buildJsonObject {
            put("mode", kotlinx.serialization.json.JsonPrimitive("save")); put("key", kotlinx.serialization.json.JsonPrimitive("city"))
            put("text", kotlinx.serialization.json.JsonPrimitive("old turn"))
        })
        assertFalse(stale.success)
        assertEquals("Haifa", mike.state.value.memories.single().text)
    }

    @Test fun aDeletedWaitingChatDoesNotKeepRearmingAPastDueAlarm() = runBlocking {
        var clock = 1000L
        val sessions = Chats(temp.root)
        val mike = PersistentMike(MikeStateStore(stateFile()) { clock }, sessions, { _, _ -> }, now = { clock })
        val task = mike.createTask("Check", "Check later")
        mike.runTask(task.id); mike.started(task.sessionId)
        mike.checkpoint(task.sessionId, task.id, "wait", "Check later", 2000L)
        mike.finished(task.sessionId, "complete", "Waiting")
        sessions.deleteSession(task.sessionId)
        clock = 3000L; mike.wakeDue()
        assertEquals(MikeTaskStatus.PAUSED, mike.state.value.tasks.single().status)
        assertNull(mike.nextWakeAt())
        assertTrue(mike.state.value.tasks.single().nextStep.contains("deleted"))
    }

    private class Chats(private val root: File) : SessionStore {
        override val sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        private val histories = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()
        override suspend fun createSession(engine: EngineKind): ChatSession = ChatSession(UUID.randomUUID().toString(), "New chat", 1, 1, engine = engine).also { sessions.value += it }
        override suspend fun ensureMikeSession(engine: EngineKind) = sessions.value.firstOrNull { it.isMike } ?: createSession(engine).copy(isMike = true, title = "Mike").also { home -> sessions.value = sessions.value.map { if (it.id == home.id) home else it } }
        override suspend fun getSession(id: String) = sessions.value.find { it.id == id }
        override fun messages(sessionId: String) = histories.getOrPut(sessionId) { MutableStateFlow(emptyList()) }
        override suspend fun append(message: ChatMessage) { messages(message.sessionId).value += message }
        override suspend fun updateMessage(id: String, text: String, state: String) {}
        override suspend fun setThread(sessionId: String, threadId: String) {}
        override suspend fun rename(sessionId: String, title: String) { sessions.value = sessions.value.map { if (it.id == sessionId) it.copy(title = title) else it } }
        override suspend fun deleteSession(sessionId: String) { sessions.value = sessions.value.filterNot { it.id == sessionId }; histories.remove(sessionId) }
        override fun workspace(sessionId: String) = File(root, "$sessionId/workspace").apply { mkdirs() }
    }
}
