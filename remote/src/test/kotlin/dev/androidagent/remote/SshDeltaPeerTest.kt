package dev.androidagent.remote

import dev.androidagent.core.FileTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.Base64

/** Real exec/SFTP channels with a Python helper; no network downloads and no Android device. */
class SshDeltaPeerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: SshServer
    private val windows get() = System.getProperty("os.name").startsWith("Windows")
    private val os get() = if (windows) HostOs.WINDOWS else HostOs.LINUX

    @Before fun start() {
        val accessor = object : SftpFileSystemAccessor {
            override fun resolveLocalFilePath(subsystem: SftpSubsystemProxy, rootDir: Path, remotePath: String): Path {
                val path = if (remotePath == ".") temp.root.toPath() else if (windows && remotePath.matches(Regex("^/[A-Za-z]:(?:/.*)?$"))) Path.of(remotePath.substring(1).let { if(it.length==2) "$it/" else it }) else Path.of(remotePath)
                val base = temp.root.toPath().toAbsolutePath()
                val absolute = path.normalize().toAbsolutePath()
                check(absolute.startsWith(base) || (base.startsWith(absolute) && java.nio.file.Files.isDirectory(absolute))) { "Outside test workspace" }
                return path
            }
        }
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(temp.root,"host.key").toPath())
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ -> user=="fixture" && password=="fixture" }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().withFileSystemAccessor(accessor).build())
        }
        server.start()
    }
    @After fun stop() { server.stop(true) }
    private fun link() = SshLink(SshTarget("127.0.0.1",server.port,"fixture","fixture",null))
    private fun peer(link: SshLink) = SshDeltaPeer(link,os,temp.root.absolutePath,File(temp.root,"local-scratch"))

    @Test fun successfulReleaseKeepsAnotherLongRunningExecChannelAlive() = runBlocking {
        withTimeout(30_000) {
            val source = File(temp.root,"fixture.apk").apply { writeText("PK data") }
            val script = "import sys\nprint('READY',flush=True)\nfor line in sys.stdin: print(line.strip(),flush=True)"
            val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
            val command = if (windows) WindowsHost.powershell("& python -u -c \"import base64;exec(base64.b64decode('$encoded'))\"")
                else "python3 -u -c \"import base64;exec(base64.b64decode('$encoded'))\""
            link().use { link ->
                val control = link.start(command)
                try {
                    val reader = control.inputStream.bufferedReader()
                    assertEquals("READY",reader.readLine())
                    link.isolated().use { transfer ->
                        peer(transfer).use { peer ->
                            val manifest = peer.prepare(source.absolutePath,emptyList(),false) {}
                            peer.fetch(manifest.payloadPath,File(temp.root,"fetched.apk"),manifest.payload.size) { _,_-> }
                            assertEquals(2,server.activeSessions.size)
                        }
                    }
                    assertTrue(control.isAlive)
                    control.outputStream.write("CONTROL_ALIVE\n".toByteArray()); control.outputStream.flush()
                    assertEquals("CONTROL_ALIVE",reader.readLine())
                } finally { control.outputStream.close(); control.destroy() }
            }
        }
        Unit
    }

    @Test fun manifestLeaseSurvivesPrepareAndSftpFetchesTheImmutableSnapshot() = runBlocking {
        withTimeout(30_000) {
            val source=File(temp.root,"a name 'שלום.apk").apply { writeText("PK original signed fixture") }
            link().use { link ->
                peer(link).use { peer ->
                    val manifest=peer.prepare(source.absolutePath,emptyList(),false) {}
                    assertEquals(source.name,manifest.name)
                    source.writeText("new build at the same path")
                    assertEquals(manifest.target.size,link.size(manifest.payloadPath))
                    val fetched=File(temp.root,"fetched.apk")
                    peer.fetch(manifest.payloadPath,fetched,manifest.payload.size) { _,_-> }
                    assertEquals("PK original signed fixture",fetched.readText())
                }
                peer(link).use { next ->
                    val manifest=next.prepare(source.absolutePath,emptyList(),false) {}
                    assertEquals(source.length(),manifest.target.size)
                }
            }
        }
        Unit
    }

    @Test fun cancellingAQueuedHelperKeepsTheFirstLeaseAndSharedSshAlive() = runBlocking {
        withTimeout(30_000) {
            val source=File(temp.root,"fixture.apk").apply { writeText("PK data") }
            link().use { link ->
                peer(link).use { first ->
                    val manifest=first.prepare(source.absolutePath,emptyList(),false) {}
                    val waiting=CompletableDeferred<Unit>()
                    val second=peer(link)
                    val job=launch { try { second.prepare(source.absolutePath,emptyList(),false) { if(it==FileTransfer.Phase.PREPARING)waiting.complete(Unit) } } finally { second.close(stopped=true) } }
                    waiting.await(); job.cancelAndJoin()
                    assertEquals(manifest.target.size,link.size(manifest.payloadPath))
                }
                peer(link).use { next -> next.prepare(source.absolutePath,emptyList(),false) {} }
            }
        }
        Unit
    }
}
