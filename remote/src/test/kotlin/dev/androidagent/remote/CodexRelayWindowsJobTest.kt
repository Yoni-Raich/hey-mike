package dev.androidagent.remote

import java.io.File
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the PRODUCT bootstrap inside the Windows job used by OpenSSH. */
class CodexRelayWindowsJobTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 45_000) fun productBootstrapSurvivesItsSshCommandJobAndStillDeliversInput() = withFiles { folder, instance ->
        val result = inJob(folder, CodexRelayScripts.start(instance))
        assertEquals(result.second, 0, result.first)
        val metadata = Json.parseToJsonElement(File(instance.metadata).readText()).jsonObject
        val port = metadata["port"]!!.jsonPrimitive.content.toInt()
        try {
            // The job has already closed and killed all ordinary descendants.
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val writer = socket.getOutputStream().bufferedWriter()
                val reader = socket.getInputStream().bufferedReader()
                fun send(line: String) { writer.write(line); writer.newLine(); writer.flush() }
                send("H1 ${instance.token} 0")
                assertEquals("H1", reader.readLine())
                val text = "{\"fixture\":\"שלום 🙂\"}\n"
                send(CodexRelayProcess.frame("I", 1, text.toByteArray(Charsets.UTF_8)))
                var output: String? = null
                var receipt = false
                while (!receipt || output == null) {
                    val line = reader.readLine() ?: error("Detached relay ended")
                    if (line == "A 1") receipt = true
                    if (line.startsWith("O ")) output = String(CodexRelayProcess.payload(line).second, Charsets.UTF_8)
                }
                assertEquals(text, output)
                send("X")
                assertEquals("X", reader.readLine())
            }
            await { File(instance.metadata).readText().contains("\"ended\":true") }
        } finally { stop(instance) }
    }

    @Test(timeout = 45_000) fun oldStartProcessDiesWithTheJobAndStaleMetadataFailsImmediately() = withFiles { folder, instance ->
        val arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File \"${instance.script}\" " +
            "-Mode host -LaunchConfig \"${instance.folder}/launch.json\" -Token ${instance.token} -Metadata \"${instance.metadata}\""
        val old = WindowsHost.powershell("""
            Start-Process powershell.exe -ArgumentList ${quote(arguments)} -WindowStyle Hidden | Out-Null
            ${'$'}deadline=[DateTime]::UtcNow.AddSeconds(20)
            while (-not (Test-Path -LiteralPath ${quote(instance.metadata)})) {
                if ([DateTime]::UtcNow -gt ${'$'}deadline) { exit 2 }
                Start-Sleep -Milliseconds 25
            }
        """.trimIndent())
        val result = inJob(folder, old)
        assertEquals(result.second, 0, result.first)
        Thread.sleep(200)
        val started = System.nanoTime()
        val attach = ProcessBuilder("cmd.exe", "/c", CodexRelayScripts.attach(instance, 0)).redirectErrorStream(true).start()
        try {
            assertTrue(attach.waitFor(8, TimeUnit.SECONDS))
            assertTrue(attach.inputStream.bufferedReader().readText().contains("F Relay ended"))
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 8)
        } finally { attach.destroy(); stop(instance) }
    }

    private fun withFiles(check: (File, CodexRelayScripts.Instance) -> Unit) {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val folder = temp.newFolder("SSH job with spaces")
        for (name in listOf("relay.cs", "relay.ps1")) javaClass.getResourceAsStream("/codex-relay/$name")!!.use {
            File(folder, name).outputStream().use(it::copyTo)
        }
        javaClass.getResourceAsStream("/codex-relay/job-fixture.cs")!!.use {
            File(folder, "job-fixture.cs").outputStream().use(it::copyTo)
        }
        val echo = """
            [Console]::InputEncoding=New-Object Text.UTF8Encoding(${ '$' }false)
            [Console]::OutputEncoding=New-Object Text.UTF8Encoding(${ '$' }false)
            while ((${ '$' }line=[Console]::ReadLine()) -ne ${ '$' }null) {
                [Console]::WriteLine(${ '$' }line); [Console]::Out.Flush()
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(echo.toByteArray(Charsets.UTF_16LE))
        val instance = CodexRelayScripts.Instance("f".repeat(64), folder.absolutePath.replace('\\', '/'))
        File(folder, "launch.json").writeText(CodexRelayScripts.launchConfig(
            "powershell.exe", listOf("-NoProfile", "-NonInteractive", "-EncodedCommand", encoded), folder.absolutePath, emptyMap(),
        ))
        try { check(folder, instance) } finally { stop(instance) }
    }

    private fun inJob(folder: File, command: String): Pair<Int, String> {
        val log = File(folder, "bootstrap.log")
        val childFile = File(folder, "bootstrap.ps1").apply {
            writeText("& $command *> ${quote(log.absolutePath)}; exit ${'$'}LASTEXITCODE")
        }
        val child = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"${childFile.absolutePath}\""
        val runner = File(folder, "job-runner.ps1").apply {
            writeText("Add-Type -Path ${quote(File(folder, "job-fixture.cs").absolutePath)}; " +
                "exit [MikeRelayJobFixture]::Run(${quote(child)})")
        }
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", runner.absolutePath).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            return process.exitValue() to (process.inputStream.bufferedReader().readText() + if (log.isFile) log.readText() else "")
        } finally { process.destroy() }
    }

    private fun stop(instance: CodexRelayScripts.Instance) {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", instance.script, "-Mode", "stop", "-Token", instance.token, "-Metadata", instance.metadata).redirectErrorStream(true).start()
        try { process.waitFor(8, TimeUnit.SECONDS) } finally { process.destroy() }
    }
    private fun quote(text: String) = "'" + text.replace("'", "''") + "'"
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) { check(System.nanoTime() < deadline) { "Relay did not stop" }; Thread.sleep(25) }
    }
}
