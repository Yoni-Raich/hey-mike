package dev.androidagent.remote

import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.EngineProfile
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real pinned Codex + real SSH loss. No turn/start, model request, account, or user workspace. */
class CodexRelayEngineIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 60_000) fun sshLossReattachesToTheSameCodexProcessAndLoadedThread() = runBlocking {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val exe = File(System.getenv("LOCALAPPDATA"), "HeyMike/codex/${WindowsHost.CODEX_VERSION}/bin/codex-app-server.exe")
        assumeTrue("Pinned Windows Codex is installed", exe.isFile)
        val folder = temp.newFolder("real relay")
        val home = temp.newFolder("empty codex home")
        val project = temp.newFolder("empty project")
        for (name in listOf("relay.cs", "relay.ps1")) {
            javaClass.getResourceAsStream("/codex-relay/$name")!!.use { source -> File(folder, name).outputStream().use(source::copyTo) }
        }
        val instance = CodexRelayScripts.Instance("a".repeat(64), folder.absolutePath.replace('\\', '/'))
        val host = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", instance.script, "-Mode", "host", "-Executable", exe.absolutePath,
            "-Token", instance.token, "-Metadata", instance.metadata).apply {
            environment()["CODEX_HOME"] = home.absolutePath
            environment().remove("OPENAI_API_KEY"); environment().remove("CODEX_API_KEY")
            redirectErrorStream(true)
        }.start()
        val server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(folder, "ssh-host-key.ser").toPath())
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "fixture" && password == "fixture" }
            commandFactory = ProcessShellCommandFactory.INSTANCE
        }
        server.start()
        val link = SshLink(SshTarget("127.0.0.1", server.port, "fixture", "fixture", null))
        val statuses = CopyOnWriteArrayList<String>()
        var relay: CodexRelayProcess? = null
        val runtime = object : RuntimeHost {
            override val status = MutableStateFlow(RuntimeStatus())
            override val homeDirectory = project
            override suspend fun prepare() = Unit
            override suspend fun startAppServer(): Process = CodexRelayProcess(
                attach = { after -> link.start(CodexRelayScripts.attach(instance, after)) },
                status = statuses::add, retryDelaysMs = longArrayOf(20), heartbeatMs = 100, stalledMs = 10_000,
            ).also { relay = it }
            override suspend fun stop() { relay?.destroy() }
        }
        val engine = CodexEngine(runtime, EngineProfile("Fixture only"), diagnosticSink = {})
        try {
            val metadata = File(instance.metadata)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!metadata.isFile) {
                check(host.isAlive) { "Relay startup failed" }
                check(System.nanoTime() < deadline) { "Relay startup timed out" }
                Thread.sleep(20)
            }
            assertFalse(engine.account().signedIn)
            val id = engine.openSessionAt(project.absolutePath, null, null, emptyList(), freshIfLost = false)
            assertTrue(engine.loadedThreadIds().contains(id))
            val before = metadata.readText()
            link.close() // The SSH session, not the broker or Codex, disappears.
            assertTrue(engine.loadedThreadIds().contains(id))
            assertEquals(before, metadata.readText())
            assertTrue(host.isAlive)
            assertTrue(statuses.any { it.contains("Connection restored") })
            // Recovery uses the existing loaded actor; it never sends another
            // thread/resume or creates a competing writer. A zero-turn thread
            // has no persisted rollout, so resume is deliberately not tested here.
            assertTrue(engine.loadedThreadIds().contains(id))
        } finally {
            engine.close()
            link.close()
            server.stop(true)
            // Explicitly stop only this test's private broker, even after a failed assertion.
            if (host.isAlive) {
                runCatching {
                    val stop = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", instance.script, "-Mode", "stop", "-Token", instance.token, "-Metadata", instance.metadata)
                        .redirectErrorStream(true).start()
                    stop.waitFor(15, TimeUnit.SECONDS)
                    stop.destroy()
                }
                assertTrue("The owned broker exits", host.waitFor(15, TimeUnit.SECONDS))
            }
        }
    }
}
