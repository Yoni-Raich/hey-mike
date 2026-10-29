package dev.androidagent.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.TaskPlaybook
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Fixture emulator only: exercise Android's real app-home aliases and symlinks. */
class TaskPlaybookAndroidTest {
    @Test fun playbookAtTheProductionAppHomeCanBeRead() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentApplication
        val book = app.graph.taskDesk.playbook
        book.seed()
        assertTrue(book.list().contains("SKILL.md"))
        assertTrue(book.read("SKILL.md").isNotBlank())
        assertTrue(book.userRules().isNotBlank())
    }

    @Test fun trustedAncestorAliasWorksButLinksInsideTheBookStayBlocked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "playbook-path-").toFile()
        val alias = File(root, "alias")
        val linkedFile = File(root, "real/home/.agents/skills/mike-playbook/sources/linked.md")
        try {
            val real = File(root, "real").apply { mkdirs() }
            File(real, "home").mkdirs()
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            val book = TaskPlaybook(File(alias, "home"))
            book.seed()
            book.writeLearned("sources/chat.md", "A useful rule")
            assertEquals("A useful rule", book.read("sources/chat.md"))
            val outside = File(root, "outside.md").apply { writeText("Keep this") }
            Files.createSymbolicLink(linkedFile.toPath(), outside.toPath())
            assertTrue(runCatching { book.read("sources/linked.md") }.isFailure)
            assertTrue(runCatching { book.writeLearned("sources/linked.md", "Replace it") }.isFailure)
            assertTrue(runCatching { book.list() }.isFailure)
            assertEquals("Keep this", outside.readText())
        } finally {
            Files.deleteIfExists(linkedFile.toPath())
            Files.deleteIfExists(alias.toPath())
            root.deleteRecursively()
        }
    }
}
