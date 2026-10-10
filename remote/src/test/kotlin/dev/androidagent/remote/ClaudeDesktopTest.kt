package dev.androidagent.remote

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ClaudeDesktopTest {
    @get:Rule val temp = TemporaryFolder()
    private val thread = "11111111-1111-4111-8111-111111111111"

    @Test fun handoffUsesTheNativeIdAndQuotesProjectPaths() {
        val command = ClaudeDesktop.command("C:\\a 'folder'\\bridge.ps1")
        assertTrue(command.length < 8191)
        val script = ClaudeDesktop.script("C:\\a 'folder'\\claude.exe", "C:\\project & 'name'", thread, "C:\\scratch")
        assertTrue(script.contains("LogonType Interactive"))
        assertTrue(script.contains("Unregister-ScheduledTask"))
        assertTrue(script.contains("Remove-Item -LiteralPath"))
        val job = String(java.util.Base64.getDecoder().decode(script.substringAfter("FromBase64String('").substringBefore("')")), Charsets.UTF_8)
        assertTrue(job.contains("'--desktop','--resume','$thread'"))
        assertTrue(job.contains("C:\\project & ''name''"))
    }

    /** Opt in on a signed-in Windows test PC. The CLI fixture never opens Claude or calls a model. */
    @Test fun interactiveBridgeReportsTheFixtureResultAndCleansUp() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assumeTrue(System.getenv("HEYMIKE_TEST_DESKTOP_BRIDGE") == "true")
        val folder = File(temp.root, "a project with spaces").apply { mkdirs() }
        val captured = File(folder, "invocation.txt")
        val stub = File(folder, "claude-fixture.cmd").apply {
            writeText("@echo off\r\n> \"${captured.absolutePath}\" echo %*\r\n>> \"${captured.absolutePath}\" cd\r\nexit /b 0\r\n")
        }
        val bridge = File(folder, "desktop-bridge.ps1").apply { writeText(ClaudeDesktop.script(stub.absolutePath, folder.absolutePath, thread, folder.absolutePath)) }
        val process = ProcessBuilder("cmd.exe", "/c", ClaudeDesktop.command(bridge.absolutePath))
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(45, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        val row = ClaudeSessionFiles.payload(ExecResult(output, "", process.exitValue()))
        assertEquals("true", row["requested"].toString())
        assertTrue(captured.readText().contains("--desktop --resume $thread"))
        assertTrue(captured.readText().contains(folder.absolutePath))
        assertFalse(folder.listFiles().orEmpty().any { it.name.startsWith("desktop-") })
    }
}
