package dev.androidagent.remote

import java.io.File
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs the shipped Windows broker with a harmless echo child, without Codex or an account. */
class CodexRelayHostTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun disconnectKeepsTheChildAndAnUnacknowledgedInputRunsOnlyOnce() = withHost { host ->
        val text = "{\"method\":\"turn/start\",\"text\":\"שלום 🙂\"}\n"
        val input = CodexRelayProcess.frame("I", 1, text.toByteArray(Charsets.UTF_8))
        host.client(0).use { first ->
            first.send(input)
            await { host.count.readText().isNotEmpty() }
            // Deliberately lose the input receipt and the output acknowledgement.
        }
        assertTrue(host.process.isAlive)
        host.client(0).use { second ->
            second.send(input)
            var receipt = false
            var output: String? = null
            while (!receipt || output == null) {
                val line = second.read()
                if (line == "A 1") receipt = true
                if (line.startsWith("O ")) {
                    val (seq, bytes) = CodexRelayProcess.payload(line)
                    assertTrue(seq > 0)
                    output = String(bytes, Charsets.UTF_8)
                    second.send("A $seq")
                } else if (line.startsWith("D ")) {
                    second.send("A ${CodexRelayProcess.payload(line).first}")
                }
            }
            assertEquals(text, output)
            second.send("X")
            assertEquals("X", second.read())
        }
        assertEquals("1", host.count.readText())
        await { !host.process.isAlive }
        assertFalse(processExists(host.childPid.readText().trim().toLong()))
    }

    @Test fun onlyTheCapabilityOwnerCanAttachAndTheOfflineLeaseEndsTheOwnedChild() = withHost(lease = 2) { host ->
        Socket("127.0.0.1", host.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("H1 wrong-capability 0\n".toByteArray())
            socket.getOutputStream().flush()
            assertNull(socket.getInputStream().bufferedReader().readLine())
        }
        assertTrue(host.process.isAlive)
        await { host.childPid.exists() }
        val pid = host.childPid.readText().trim().toLong()
        await(8_000) { !host.process.isAlive }
        assertFalse(processExists(pid))
        assertTrue(host.metadata.readText().contains("\"ended\":true"))
    }

    @Test fun nativeLaunchPreservesWorkingFolderAndEnvironmentAndASecondOwnerEndsTheFirst() = withHost(configured = true) { host ->
        await { host.settings.isFile && host.childPid.isFile && host.childPid.readText().isNotBlank() }
        val settings = host.settings.readText()
        assertTrue(settings.contains("relay with spaces"))
        assertTrue(settings.contains("שלום 100% 🙂"))
        val child = host.childPid.readText().trim().toLong()
        val secondMetadata = File(host.metadata.parentFile, "second-connection.json")
        val secondCommand = host.command.toMutableList().apply { this[indexOf("-Metadata") + 1] = secondMetadata.absolutePath }
        val second = ProcessBuilder(secondCommand).redirectErrorStream(true).start()
        try {
            // One owner at a time: the earlier relay and its child end before the new one starts its own.
            await(25_000) { !host.process.isAlive }
            assertFalse(processExists(child))
            await(20_000) { secondMetadata.isFile && secondMetadata.readText().contains("\"child\"") }
            assertTrue(second.isAlive)
            assertEquals("", host.count.readText())
        } finally {
            // This second host and its echo child were created by this test only.
            runCatching {
                val port = Json.parseToJsonElement(secondMetadata.readText()).jsonObject["port"]!!.jsonPrimitive.content.toInt()
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 8_000
                    socket.getOutputStream().write("S ${host.token}\n".toByteArray())
                    socket.getOutputStream().flush()
                    socket.getInputStream().bufferedReader().readLine()
                }
            }
            if (!second.waitFor(8, TimeUnit.SECONDS)) second.destroyForcibly()
        }
    }

    private fun withHost(lease: Int = 30, configured: Boolean = false, check: (Host) -> Unit) {
        assumeTrue("Windows helper execution", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val folder = temp.newFolder("relay with spaces")
        for (name in listOf("relay.cs", "relay.ps1")) {
            javaClass.getResourceAsStream("/codex-relay/$name").use { source ->
                assertNotNull("Packaged relay resource $name", source)
                File(folder, name).outputStream().use { source!!.copyTo(it) }
            }
        }
        val count = File(folder, "count.txt").apply { writeText("") }
        val childPid = File(folder, "child-pid.txt")
        val settings = File(folder, "settings.txt")
        fun quote(path: File) = "'" + path.absolutePath.replace("'", "''") + "'"
        val echo = """
            [Console]::InputEncoding = New-Object Text.UTF8Encoding(${ '$' }false)
            [Console]::OutputEncoding = New-Object Text.UTF8Encoding(${ '$' }false)
            [IO.File]::WriteAllText(${quote(childPid)}, [Diagnostics.Process]::GetCurrentProcess().Id.ToString())
            [IO.File]::WriteAllText(${quote(settings)}, [Environment]::CurrentDirectory + "`n" + ${ '$' }env:MIKE_RELAY_TEST)
            while ((${ '$' }line=[Console]::ReadLine()) -ne ${ '$' }null) {
                [IO.File]::AppendAllText(${quote(count)}, '1')
                [Console]::WriteLine(${ '$' }line); [Console]::Out.Flush()
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(echo.toByteArray(Charsets.UTF_16LE))
        val metadata = File(folder, "connection.json")
        val token = UUID.randomUUID().toString().replace("-", "")
        val launch = if (configured) {
            val config = File(folder, "launch.json").apply { writeText(CodexRelayScripts.launchConfig(
                "powershell.exe", listOf("-NoProfile", "-NonInteractive", "-EncodedCommand", encoded), folder.absolutePath,
                mapOf("MIKE_RELAY_TEST" to "שלום 100% 🙂"), File(folder, "owner.lock").absolutePath,
            )) }
            listOf("-LaunchConfig", config.absolutePath)
        } else listOf("-Executable", "powershell.exe", "-Arguments", "-NoProfile -NonInteractive -EncodedCommand $encoded")
        val command = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", File(folder, "relay.ps1").absolutePath, "-Mode", "host") + launch + listOf("-Token", token,
            "-Metadata", metadata.absolutePath, "-LeaseSeconds", lease.toString())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            await(20_000) {
                kotlin.check(process.isAlive) { "Relay failed: ${process.inputStream.bufferedReader().readText()}" }
                metadata.isFile && runCatching { Json.parseToJsonElement(metadata.readText()).jsonObject["port"]!!.jsonPrimitive.content.toInt() > 0 }.getOrDefault(false)
            }
            val port = Json.parseToJsonElement(metadata.readText()).jsonObject["port"]!!.jsonPrimitive.content.toInt()
            check(Host(process, port, token, metadata, count, childPid, settings, command))
        } finally {
            // This host and echo child were created by this test only.
            if (process.isAlive) {
                runCatching {
                    val port = Json.parseToJsonElement(metadata.readText()).jsonObject["port"]!!.jsonPrimitive.content.toInt()
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 8_000
                        socket.getOutputStream().write("S $token\n".toByteArray())
                        socket.getOutputStream().flush()
                        assertEquals("X", socket.getInputStream().bufferedReader().readLine())
                    }
                }
                if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    private data class Host(val process: Process, val port: Int, val token: String, val metadata: File, val count: File, val childPid: File,
                            val settings: File, val command: List<String>) {
        fun client(after: Long) = Client(port, token, after)
    }
    private class Client(port: Int, token: String, after: Long) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 8_000 }
        private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        init { send("H1 $token $after"); assertEquals("H1", read()) }
        fun send(line: String) { writer.write(line); writer.newLine(); writer.flush() }
        fun read() = reader.readLine() ?: throw IllegalStateException("Relay closed")
        override fun close() { socket.close() }
    }
    private fun await(timeout: Long = 5_000, check: () -> Boolean) {
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (!check()) {
            kotlin.check(System.nanoTime() < deadline) { "Relay condition timed out" }
            Thread.sleep(20)
        }
    }
    private fun processExists(pid: Long): Boolean {
        // The Android compile stubs omit ProcessHandle; the unit-test JVM has it.
        val handle = Class.forName("java.lang.ProcessHandle")
        return (handle.getMethod("of", java.lang.Long.TYPE).invoke(null, pid) as java.util.Optional<*>).isPresent
    }
}
