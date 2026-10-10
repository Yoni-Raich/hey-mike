package dev.androidagent.remote

import dev.androidagent.core.ChatMessage
import dev.androidagent.core.EngineKind
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClaudeHistorySyncTest {
    @get:Rule val temp = TemporaryFolder()
    private val thread = "11111111-1111-4111-8111-111111111111"
    private val source = "22222222-2222-4222-8222-222222222222"
    private val history = listOf(ComputerMessage(source, "assistant", "reply", 10))

    @Test fun refreshIsIdempotentAndPreservesLocalNotesAndAttachments() {
        val local = listOf(ChatMessage("note", "chat", "note", "keep me", 20),
            ChatMessage("user", "chat", "user", "question", 30, attachmentPaths = listOf("photo.png")))
        val added = ClaudeHistorySync.additions("chat", "pc", thread, history, local, emptySet())
        assertEquals(1, added.size)
        assertTrue(ClaudeHistorySync.additions("chat", "pc", thread, history + history, local + added, emptySet()).isEmpty())
        assertEquals(listOf("photo.png"), local.last().attachmentPaths)
        assertNotEquals(added.single().id, ClaudeHistorySync.messageId("other-pc", thread, source))
    }

    @Test fun nativeIdsSurviveRestartAndDoNotHideUnsavedOutput() {
        val ledger = ClaudeMessageLedger(temp.root)
        ledger.record("pc", thread, source)
        ledger.record("pc", thread, source)
        val saved = ClaudeMessageLedger(temp.root).ids("pc", thread)
        assertEquals(setOf(source), saved)
        assertTrue(ClaudeHistorySync.additions("chat", "pc", thread, history,
            listOf(ChatMessage("live", "chat", "assistant", "reply", 10)), saved).isEmpty())
        assertEquals(1, ClaudeHistorySync.additions("chat", "pc", thread, history, emptyList(), saved).size)
        assertEquals(1, ClaudeHistorySync.additions("chat", "pc", thread, history,
            listOf(ChatMessage("partial", "chat", "assistant", "re", 10, "streaming")), saved).size)
    }

    @Test fun readersArePackagedAndCommandsRejectInjection() {
        assertTrue(ClaudeSessionFiles.script(HostOs.WINDOWS).contains("ConvertFrom-Json"))
        assertTrue(ClaudeSessionFiles.script(HostOs.LINUX).contains("json.loads"))
        try { ClaudeSessionFiles.command(HostOs.WINDOWS, "C:\\reader.ps1", "read", "'; Get-Content secret") ; fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(ClaudeSessionFiles.command(HostOs.LINUX, "/a 'project'/reader.py", "read", thread).isNotBlank())
    }

    @Test fun repeatingAnAnswerDoesNotHideUnsavedNewOutputAndTextBlocksAreNotDoubled() {
        val next = "33333333-3333-4333-8333-333333333333"
        val repeated = history + ComputerMessage(next, "assistant", "reply", 20)
        val saved = listOf(ChatMessage("live", "chat", "assistant", "reply", 10))
        assertEquals(listOf(ClaudeHistorySync.messageId("pc", thread, next)),
            ClaudeHistorySync.additions("chat", "pc", thread, repeated, saved, setOf(source, next)).map { it.id })
        val blocks = listOf(ChatMessage("b1", "chat", "assistant", "one", 10), ChatMessage("b2", "chat", "assistant", "two", 11))
        assertTrue(ClaudeHistorySync.additions("chat", "pc", thread,
            listOf(ComputerMessage(source, "assistant", "one\ntwo", 10)), blocks, setOf(source)).isEmpty())
    }

    @Test fun legacyAdoptionRunsOnceAndFutureIdenticalExternalMessagesRemainNew() {
        val ledger = ClaudeMessageLedger(temp.root)
        val local = listOf(ChatMessage("old-mike", "chat", "assistant", "reply", 10))
        ledger.adoptLocalHistory("pc", thread, history, local)
        assertEquals(setOf(source), ledger.ids("pc", thread))
        val next = "33333333-3333-4333-8333-333333333333"
        val newHistory = history + ComputerMessage(next, "assistant", "reply", 20)
        ledger.adoptLocalHistory("pc", thread, newHistory, local)
        assertEquals(setOf(source), ledger.ids("pc", thread))
        assertEquals(1, ClaudeHistorySync.additions("chat", "pc", thread, newHistory, local, ledger.ids("pc", thread)).size)
    }

    @Test fun importedSessionsKeepTheirProvider() {
        val item = ClaudeSessionFiles.thread(Json.parseToJsonElement("""{"id":"$thread","cwd":"C:/src","title":"old chat","updatedAt":1,"busy":true}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(EngineKind.CLAUDE, item.engine)
        assertTrue(item.busy)
    }
}
