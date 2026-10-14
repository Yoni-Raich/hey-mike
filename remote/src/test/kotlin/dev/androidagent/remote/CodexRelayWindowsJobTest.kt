package dev.androidagent.remote

import java.io.File
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test(timeout = 45_000) fun productTunnelReattachesToTheSameNativeBrokerWithoutRepeatingInput() = withFiles { folder, instance ->
        val result = inJob(folder, CodexRelayScripts.start(instance))
        assertEquals(result.second, 0, result.first)
        val metadata = File(instance.metadata).readText()
        val server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(folder, "host-key.ser").toPath())
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "fixture" && password == "fixture" }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        server.start()
        val link = SshLink(SshTarget("127.0.0.1", server.port, "fixture", "fixture", null))
        val connections = CopyOnWriteArrayList<Process>()
        val attach = CodexRelayProcess(attach = { after ->
            CodexRelayScripts.attach(link, instance, after).also { connections += it }
        }, retryDelaysMs = longArrayOf(10), heartbeatMs = 100, stalledMs = 10_000)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = attach.outputStream.bufferedWriter()
            val reader = attach.inputStream.bufferedReader()
            fun read(): String = executor.submit<String> { reader.readLine() ?: error("Attachment closed") }.get(3, TimeUnit.SECONDS)
            fun send(line: String) { writer.write(line); writer.newLine(); writer.flush() }
            send("first שלום 🙂")
            assertEquals("first שלום 🙂", read())
            connections.single().destroy()
            send("second שלום 🙂")
            assertEquals("second שלום 🙂", read())
            assertTrue(connections.size >= 2)
            assertEquals("The retained broker identity cannot change", metadata, File(instance.metadata).readText())
        } finally {
            attach.destroy(); executor.shutdownNow(); stop(instance); link.close(); server.stop(true)
        }
    }

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

    @Test(timeout = 150_000) fun aReplacementEndsTheEarlierRelayOfTheSameOwnerEvenWhileItIsAttached() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val owner = File(temp.root, "owners with spaces/chat.lock").absolutePath.replace('\\', '/')
        val firstFolder = temp.newFolder("first relay")
        val secondFolder = temp.newFolder("second relay")
        val first = prepare(firstFolder, "a".repeat(64), owner)
        val second = prepare(secondFolder, "b".repeat(64), owner)
        try {
            val started = inJob(firstFolder, CodexRelayScripts.start(first))
            assertEquals(started.second, 0, started.first)
            val earlier = Json.parseToJsonElement(File(first.metadata).readText()).jsonObject
            val port = earlier["port"]!!.jsonPrimitive.content.toInt()
            // A phone that went away mid-attachment: the host serves it until its read timeout.
            Socket("127.0.0.1", port).use { stale ->
                stale.getOutputStream().write("H1 ${first.token} 0\n".toByteArray())
                stale.getOutputStream().flush()
                assertEquals("H1", stale.getInputStream().bufferedReader().readLine())
                val replaced = inJob(secondFolder, CodexRelayScripts.start(second), waitSeconds = 60)
                assertEquals(replaced.second, 0, replaced.first)
            }
            for (pid in listOf("pid", "child")) {
                assertFalse("The earlier relay's $pid still runs", running(earlier[pid]!!.jsonPrimitive.content.toLong()))
            }
            assertTrue(File(second.metadata).readText().contains("\"child\""))
            assertEquals(second.metadata, File("$owner.relay").readText())
        } finally { stop(first); stop(second) }
    }

    @Test(timeout = 60_000) fun aDifferentOwnerLeavesTheEarlierRelayRunning() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val owners = File(temp.root, "owners").absolutePath.replace('\\', '/')
        val firstFolder = temp.newFolder("first")
        val secondFolder = temp.newFolder("second")
        val first = prepare(firstFolder, "c".repeat(64), "$owners/one.lock")
        val second = prepare(secondFolder, "d".repeat(64), "$owners/two.lock")
        try {
            assertEquals(0, inJob(firstFolder, CodexRelayScripts.start(first)).first)
            assertEquals(0, inJob(secondFolder, CodexRelayScripts.start(second)).first)
            assertTrue(File(first.metadata).readText().contains("\"pid\""))
            assertTrue(File(second.metadata).readText().contains("\"pid\""))
        } finally { stop(first); stop(second) }
    }

    private fun prepare(folder: File, token: String, owner: String? = null): CodexRelayScripts.Instance {
        for (name in listOf("relay.cs", "relay.ps1", "job-fixture.cs")) javaClass.getResourceAsStream("/codex-relay/$name")!!.use {
            File(folder, name).outputStream().use(it::copyTo)
        }
        val echoFile = File(folder, "echo.ps1").apply { writeText(ECHO) }
        File(folder, "launch.json").writeText(CodexRelayScripts.launchConfig(
            "powershell.exe", listOf("-NoProfile", "-NonInteractive", "-File", echoFile.absolutePath), folder.absolutePath, emptyMap(), owner,
        ))
        return CodexRelayScripts.Instance(token, folder.absolutePath.replace('\\', '/'))
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
        val echoFile = File(folder, "echo.ps1").apply { writeText(echo) }
        val instance = CodexRelayScripts.Instance("f".repeat(64), folder.absolutePath.replace('\\', '/'))
        File(folder, "launch.json").writeText(CodexRelayScripts.launchConfig(
            "powershell.exe", listOf("-NoProfile", "-NonInteractive", "-File", echoFile.absolutePath), folder.absolutePath, emptyMap(),
        ))
        try { check(folder, instance) } finally { stop(instance) }
    }

    private fun inJob(folder: File, command: String, waitSeconds: Long = 30): Pair<Int, String> {
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
            assertTrue(process.waitFor(waitSeconds, TimeUnit.SECONDS))
            return process.exitValue() to (process.inputStream.bufferedReader().readText() + if (log.isFile) log.readText() else "")
        } finally { process.destroy() }
    }

    private fun stop(instance: CodexRelayScripts.Instance) {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", instance.script, "-Mode", "stop", "-Token", instance.token, "-Metadata", instance.metadata).redirectErrorStream(true).start()
        try { process.waitFor(8, TimeUnit.SECONDS) } finally { process.destroy() }
    }
    private fun running(pid: Long): Boolean {
        val list = ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/NH", "/FO", "CSV").redirectErrorStream(true).start()
        return list.inputStream.bufferedReader().readText().contains("\"$pid\"")
    }
    private fun quote(text: String) = "'" + text.replace("'", "''") + "'"
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) { check(System.nanoTime() < deadline) { "Relay did not stop" }; Thread.sleep(25) }
    }

    private companion object {
        val ECHO = """
            [Console]::InputEncoding=New-Object Text.UTF8Encoding(${ '$' }false)
            [Console]::OutputEncoding=New-Object Text.UTF8Encoding(${ '$' }false)
            while ((${ '$' }line=[Console]::ReadLine()) -ne ${ '$' }null) {
                [Console]::WriteLine(${ '$' }line); [Console]::Out.Flush()
            }
        """.trimIndent()
    }
}
