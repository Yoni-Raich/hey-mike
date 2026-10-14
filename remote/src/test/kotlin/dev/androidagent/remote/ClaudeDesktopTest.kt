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
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun decoded(script: String): List<String> =
        Regex("FromBase64String\\('([A-Za-z0-9+/=]+)'\\)").findAll(script).map { match ->
            String(java.util.Base64.getDecoder().decode(match.groupValues[1]), Charsets.UTF_8)
        }.toList()

    @Test fun handoffUsesTheNativeIdAndCarriesPathsInAscii() {
        val command = ClaudeDesktop.command("C:\\a 'folder'\\bridge.ps1")
        assertTrue(command.length < 8191)
        val cwd = "C:\\פרויקטים\\project & 'name' [1]"
        val cli = "C:\\Users\\יוני\\.local\\bin\\claude.exe"
        val script = ClaudeDesktop.script(cli, cwd, thread, "C:\\Users\\יוני\\.hey-mike\\claude")
        assertTrue(script.contains("LogonType Interactive"))
        assertTrue(script.contains("Unregister-ScheduledTask"))
        assertTrue(script.contains("Remove-Item -LiteralPath"))
        // Windows PowerShell reads a script without a BOM in the ANSI code page.
        assertTrue(script.all { it.code < 128 })
        val job = decoded(script).single { it.contains("ProcessStartInfo") }.removePrefix("\uFEFF")
        assertTrue(job.all { it.code < 128 })
        assertTrue(job.contains("'--desktop --resume $thread'"))
        val values = decoded(job)
        assertTrue(cwd in values)
        assertTrue(cli in values)
        assertTrue(decoded(script).any { it.startsWith("C:\\Users\\יוני\\.hey-mike\\claude\\desktop-") && it.endsWith(".json.ps1") })
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), ClaudeDesktop.bytes("x").copyOf(3))
    }

    /** The reader is uploaded and run as a file too; ASCII reads the same in every code page. */
    @Test fun theWindowsReaderIsAscii() {
        assertTrue(ClaudeSessionFiles.script(HostOs.WINDOWS).all { it.code < 128 })
    }

    @Test fun aSessionIdMustBeAClaudeUuid() {
        assertThrows(IllegalArgumentException::class.java) { ClaudeDesktop.script("claude", "C:\\p", "abc' ; calc", "C:\\s") }
    }

    /** The JSON a Windows script prints reaches the phone in ASCII, whatever the console code page. */
    @Test fun windowsJsonIsPrintedInAscii() {
        assumeTrue(windows)
        val script = "${'$'}s = 'C:\\' + [char]0x05E9 + [char]0x05DC + ' x'\n" +
            WindowsHost.asciiJson("ConvertTo-Json -Compress -InputObject ([ordered]@{home=${'$'}s})")
        val process = ProcessBuilder("cmd.exe", "/c", WindowsHost.powershell(script)).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        val bytes = process.inputStream.readBytes()
        assertTrue(bytes.all { it >= 0 })
        val row = WindowsHost.payload(ExecResult(String(bytes, Charsets.UTF_8), "", process.exitValue()))
        assertEquals("C:\\של x", (row["home"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    /**
     * Opt in on a signed-in Windows test PC: it registers and removes a
     * one-off scheduled task. The CLI fixture never opens Claude or calls a
     * model. The folder has Hebrew letters, a quote and brackets.
     */
    @Test fun interactiveBridgeReportsTheFixtureResultAndCleansUp() {
        assumeTrue(windows)
        assumeTrue(System.getenv("HEYMIKE_TEST_DESKTOP_BRIDGE") == "true")
        val folder = File(temp.root, "פרויקט 'בדיקה' [1]").apply { mkdirs() }
        File(folder, "cwd-marker").writeText("x")
        val stub = File(folder, "claude-fixture.cmd").apply {
            // %~dp0 is the fixture's own folder; `if exist` proves the working folder.
            writeText("@echo off\r\n> \"%~dp0invocation.txt\" echo %*\r\nif exist cwd-marker (>> \"%~dp0invocation.txt\" echo cwd-ok)\r\nexit /b 0\r\n")
        }
        val bridge = File(folder, "desktop-bridge.ps1").apply { writeBytes(ClaudeDesktop.bytes(ClaudeDesktop.script(stub.absolutePath, folder.absolutePath, thread, folder.absolutePath))) }
        val process = ProcessBuilder("cmd.exe", "/c", ClaudeDesktop.command(bridge.absolutePath))
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        val row = ClaudeSessionFiles.payload(ExecResult(output, "", process.exitValue()))
        assertEquals("true", row["requested"].toString())
        val captured = File(folder, "invocation.txt").readText()
        assertTrue(captured.contains("--desktop --resume $thread"))
        assertTrue(captured.contains("cwd-ok"))
        assertFalse(folder.listFiles().orEmpty().any { it.name.startsWith("desktop-") })
    }
}
