package dev.androidagent.remote

import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.EngineProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The SSH client against a real SSH server in the same process, and the
 * Codex app-server spoken to over that SSH channel.
 */
class SshLinkTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: SshServer

    @Before fun start() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(temp.newFile("host.ser").toPath().also { it.toFile().delete() })
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            commandFactory = ProcessShellCommandFactory.INSTANCE
        }
        server.start()
    }

    @After fun stop() { server.stop(true) }

    private fun target(password: String = PASSWORD, pinned: String? = null) =
        SshTarget("127.0.0.1", server.port, USER, password, pinned)

    @Test fun aCommandRunsAndItsHostKeyIsReportedForPinning() {
        SshLink(target()).use { link ->
            val seen = link.connect()
            assertTrue(seen.fingerprint.startsWith("SHA256:"))
            val result = link.run("echo hello")
            assertEquals("hello", result.stdout.trim())
            assertEquals(0, result.exitCode)
            assertEquals(1, link.run("false").exitCode)
        }
    }

    @Test fun thePinnedKeyIsAcceptedAndAnyOtherIsRefused() {
        val first = SshLink(target()).use { it.connect() }
        SshLink(target(pinned = first.key)).use { assertEquals(first.fingerprint, it.connect().fingerprint) }
        try {
            SshLink(target(pinned = "AAAAC3NzaC1lZDI1NTE5AAAAIOtherKeyOtherKeyOtherKeyOtherKeyOther")).use { it.connect() }
            fail("A different host key must be refused")
        } catch (changed: HostKeyChanged) {
            assertEquals(first.fingerprint, changed.seen.fingerprint)
        }
    }

    @Test fun aWrongPasswordSaysSo() {
        try {
            SshLink(target(password = "wrong")).use { it.connect() }
            fail("A wrong password must not connect")
        } catch (error: IllegalStateException) {
            assertEquals("The computer refused the user name or password.", error.message)
        }
    }

    /**
     * The real Codex app-server behind an SSH channel: JSON-RPC over the
     * channel's streams, and the skills of the folder on the far side.
     */
    @Test fun codexAnswersOverSshAndFindsTheProjectsSkills() {
        val binary = File("../.codex-work/runtime/package-x86_64/bin/codex-app-server").absoluteFile
        assumeTrue("staged x86_64 app-server not present", binary.canExecute() && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        val project = temp.newFolder("project")
        File(project, ".git").mkdirs()
        File(project, ".agents/skills/computer-skill").mkdirs()
        File(project, ".agents/skills/computer-skill/SKILL.md").writeText(
            "---\nname: computer-skill\ndescription: A skill that lives on the computer.\n---\n\nSay hello.\n",
        )
        val codexHome = temp.newFolder("codex-home")
        val link = SshLink(target())
        val runtime = object : RuntimeHost {
            override val status: StateFlow<RuntimeStatus> = MutableStateFlow(RuntimeStatus())
            override val homeDirectory: File = project
            override suspend fun prepare() = Unit
            override suspend fun startAppServer(): Process =
                link.start("env CODEX_HOME=${codexHome.absolutePath} ${binary.absolutePath} --listen stdio://")
            override suspend fun stop() = link.close()
        }
        val engine = CodexEngine(runtime, EngineProfile("test instructions", inlineImages = true))
        runBlocking {
            withTimeout(90_000) {
                assertEquals(false, engine.account().signedIn)
                val skills = engine.skillCatalogAt(project.absolutePath, forceReload = true)
                assertTrue("skills: ${skills.map { it.name }}", skills.any { it.name == "computer-skill" })
                engine.close()
            }
        }
    }

    private companion object {
        const val USER = "yoni"
        const val PASSWORD = "secret"
    }
}
