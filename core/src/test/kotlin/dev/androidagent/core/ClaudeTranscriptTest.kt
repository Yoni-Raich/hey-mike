package dev.androidagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeTranscriptTest {
    @get:Rule val temp = TemporaryFolder()
    private val session = "c6a97ade-8859-4601-b99d-12699742efd8"
    private fun record(uuid: String, parent: String) = """{"parentUuid":"$parent","type":"user","uuid":"$uuid","message":{"role":"user","content":"x"}}"""

    @Test fun aNewSessionIsWrittenInTheFolderClaudeCodeNamesForTheCwd() {
        val projects = temp.newFolder("projects")
        assertTrue(ClaudeTranscript.extend(projects, "/data/data/app/files/chat 1", session,
            listOf(record("a", ClaudeTranscript.LEAF), record("b", "a"))))
        val file = File(projects, "-data-data-app-files-chat-1/$session.jsonl")
        assertEquals(
            """{"parentUuid":null,"type":"user","uuid":"a","message":{"role":"user","content":"x"}}""" + "\n" + record("b", "a") + "\n",
            file.readText(),
        )
    }

    @Test fun anExistingSessionIsFoundInAnyFolderAndContinuedFromItsLastMessage() {
        val projects = temp.newFolder("projects")
        val file = File(projects, "elsewhere/$session.jsonl").apply { parentFile.mkdirs() }
        file.writeText(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"hi"}}""" + "\n" +
                """{"type":"assistant","uuid":"side","isSidechain":true}""" + "\n" +
                """{"type":"queue-operation","operation":"dequeue"}""",
        )
        assertTrue(ClaudeTranscript.extend(projects, "/other", session, listOf(record("n1", ClaudeTranscript.LEAF))))
        val lines = file.readLines()
        assertEquals(4, lines.size)
        assertEquals(record("n1", "u1"), lines.last())
        assertFalse(File(projects, "-other").exists())
    }

    @Test fun aPathTooLongForAPlainFolderNameIsRefused() {
        assertNull(ClaudeTranscript.folderName("/" + "a".repeat(250)))
        assertFalse(ClaudeTranscript.extend(temp.newFolder("projects"), "/" + "a".repeat(250), session, listOf(record("a", ClaudeTranscript.LEAF))))
    }
}
