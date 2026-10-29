package dev.androidagent.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback SSH/SFTP only: never connects to a saved computer or runs an AI task. */
class SshRobustnessTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: SshServer
    private lateinit var remote: File

    @Before fun start() {
        remote = temp.newFolder("remote")
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(temp.root, "host.key").toPath())
            setPasswordAuthenticator { user, password, _ -> user == "fixture" && password == "fixture" }
            fileSystemFactory = VirtualFileSystemFactory(remote.toPath())
            commandFactory = ProcessShellCommandFactory.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory())
            start()
        }
    }
    @After fun stop() { server.stop(true) }
    private fun link(password: String = "fixture") = SshLink(SshTarget("127.0.0.1", server.port, "fixture", password, null))
    private fun source(name: String = "source", byte: Byte = 42) = temp.newFile(name).apply { writeBytes(ByteArray(512 * 1024) { byte }) }

    @Test(timeout = 20_000) fun closingAFailedProbeCannotCloseConcurrentPreparation() = runBlocking {
        link().use { seed ->
            seed.connect()
            seed.isolated().use { probe ->
                probe.connect()
                val input = source()
                val process = SshPreparation.start(
                    open = { seed.isolated() },
                    prepare = { preparation ->
                        preparation.upload(input, "/ready.py") { done, _ -> if (done > 0) probe.close() }
                    },
                    launch = { launch, _ -> launch.start("echo ready") },
                )
                try { assertEquals(0, process.waitFor()) } finally { process.destroy() }
                assertArrayEquals(input.readBytes(), File(remote, "ready.py").readBytes())
                assertEquals("still-connected", seed.run("echo still-connected").stdout.trim())
            }
        }
    }

    @Test(timeout = 20_000) fun serverDisconnectDuringPreparationRetriesOnceAndLaunchesOnce() = runBlocking {
        link().use { seed ->
            seed.connect()
            val seedSession = server.activeSessions.single()
            val attempts = AtomicInteger()
            val launches = AtomicInteger()
            val input = source()
            val process = SshPreparation.start(
                open = { attempts.incrementAndGet(); seed.isolated() },
                prepare = { preparation ->
                    preparation.upload(input, "/ready.py") { done, _ ->
                        if (attempts.get() == 1 && done > 0) {
                            server.activeSessions.filter { it != seedSession }.forEach { it.close(true).await(2_000) }
                        }
                    }
                },
                launch = { launch, _ -> launches.incrementAndGet(); launch.start("echo ready") },
                retryDelayMs = 1,
            )
            try { assertEquals(0, process.waitFor()) } finally { process.destroy() }
            assertEquals(2, attempts.get())
            assertEquals(1, launches.get())
            assertArrayEquals(input.readBytes(), File(remote, "ready.py").readBytes())
            assertEquals("alive", seed.run("echo alive").stdout.trim())
        }
    }

    @Test(timeout = 20_000) fun localDisconnectAfterLaunchNeverRepeatsTheLaunch() = runBlocking {
        val launches = AtomicInteger()
        val preparations = AtomicInteger()
        try {
            SshPreparation.start(
                open = { link() },
                prepare = { preparations.incrementAndGet() },
                launch = { link, _ ->
                    launches.incrementAndGet()
                    assertEquals(0, link.run("echo launched").exitCode)
                    link.close()
                    throw IOException("Lost startup receipt")
                },
                retryDelayMs = 1,
            )
            fail("An unknown launch must fail without replacement")
        } catch (_: IOException) { }
        assertEquals(1, preparations.get())
        assertEquals(1, launches.get())
    }

    @Test(timeout = 20_000) fun repeatedTransportLossHasOnlyTwoPreparationAttempts() = runBlocking {
        val attempts = AtomicInteger()
        val launches = AtomicInteger()
        try {
            SshPreparation.start(
                open = { link() },
                prepare = { attempts.incrementAndGet(); throw IOException("fixture disconnect") },
                launch = { link, _ -> launches.incrementAndGet(); link.start("echo forbidden") },
                retryDelayMs = 1,
            )
            fail("Recovery must be bounded")
        } catch (_: IOException) { }
        assertEquals(2, attempts.get())
        assertEquals(0, launches.get())
    }

    @Test(timeout = 20_000) fun authenticationAndPermissionFailuresNeverRetry() = runBlocking {
        val opens = AtomicInteger()
        try {
            SshPreparation.start(
                open = { opens.incrementAndGet(); link("wrong") },
                prepare = { Unit },
                launch = { link, _ -> link.start("echo forbidden") },
                retryDelayMs = 1,
            )
            fail("Wrong password")
        } catch (_: IllegalStateException) { }
        assertEquals(1, opens.get())
        assertFalse(SshPreparation.transportFailure(com.jcraft.jsch.SftpException(3, "Permission denied")))
        assertFalse(SshPreparation.transportFailure(HostKeyChanged(HostKeySeen("fixture", "secret", "fixture"))))
    }

    @Test(timeout = 20_000) fun stopDuringPreparationNeverRetriesOrLaunchesAndKeepsOtherLinkAlive() = runBlocking {
        link().use { seed ->
            seed.connect()
            val ready = CompletableDeferred<Unit>()
            val opens = AtomicInteger()
            val launches = AtomicInteger()
            val job = launch {
                SshPreparation.start(
                    open = { opens.incrementAndGet(); seed.isolated() },
                    prepare = { ready.complete(Unit); awaitCancellation() },
                    launch = { link, _ -> launches.incrementAndGet(); link.start("echo forbidden") },
                )
            }
            ready.await()
            job.cancelAndJoin()
            assertEquals(1, opens.get())
            assertEquals(0, launches.get())
            assertEquals("alive", seed.run("echo alive").stdout.trim())
        }
    }

    @Test(timeout = 20_000) fun stopDuringRetryDelayPreventsTheSecondConnection() = runBlocking {
        val retry = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        val job = launch {
            SshPreparation.start(
                open = { opens.incrementAndGet(); link() },
                prepare = { throw IOException("fixture disconnect") },
                launch = { _, _ -> error("Must never launch") },
                diagnostic = { _, reason -> if (reason == "transport-retry") retry.complete(Unit) },
                retryDelayMs = 30_000,
            )
        }
        retry.await()
        job.cancelAndJoin()
        assertEquals(1, opens.get())
    }

    @Test(timeout = 20_000) fun retainedProcessKeepsItsRecoveryMarkerAndDoesNotKeepPreparationSsh() = runBlocking {
        val process = SshPreparation.start(
            open = { link() },
            prepare = { Unit },
            launch = { _, _ -> CodexRelayProcess(attach = { throw IOException("offline fixture") }, retryDelaysMs = longArrayOf(100)) },
        )
        try {
            assertTrue(process is dev.androidagent.core.RecoverableConnection)
            assertTrue((process as dev.androidagent.core.RecoverableConnection).reconnecting)
            // Opening a fresh link proves the preparation socket was closed, not handed to the relay.
            link().use { fresh -> fresh.connect(); assertTrue(server.activeSessions.count { it.isAuthenticated && !it.isClosing } <= 1) }
        } finally { process.destroy() }
    }

    @Test(timeout = 20_000) fun concurrentRefuseOverwriteUploadsPublishExactlyOneWholeFile() = runBlocking {
        val inputs = (1..8).map { source("source-$it", it.toByte()) }
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        inputs.map { input -> async(Dispatchers.IO) {
            link().use { link ->
                try { link.upload(input, "/once.py", replace = false); successes.incrementAndGet() }
                catch (error: IllegalStateException) { check(error.message!!.contains("already exists")); failures.incrementAndGet() }
            }
        } }.awaitAll()
        assertEquals(1, successes.get())
        assertEquals(7, failures.get())
        val published = File(remote, "once.py").readBytes()
        assertTrue(inputs.any { it.readBytes().contentEquals(published) })
        assertTrue(remote.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test(timeout = 20_000) fun sameDestinationUploadsAcrossIsolatedLinksAreWholeAndUseDifferentParts() = runBlocking {
        link().use { seed ->
            seed.connect()
            val first = source("one", 1)
            val second = source("two", 2)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val parts = java.util.concurrent.CopyOnWriteArrayList<String>()
            val firstJob = async(Dispatchers.IO) {
                seed.isolated().use { link -> link.upload(first, "/script.py") { done, _ ->
                    if (done > 0 && entered.count > 0) {
                        parts += remote.listFiles()!!.single { it.name.endsWith(".part") }.name
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                } }
            }
            check(entered.await(3, TimeUnit.SECONDS))
            val secondJob = async(Dispatchers.IO) {
                seed.isolated().use { link -> link.upload(second, "/script.py") { done, _ ->
                    if (done > 0 && parts.size == 1) parts += remote.listFiles()!!.single { it.name.endsWith(".part") }.name
                } }
            }
            release.countDown()
            awaitAll(firstJob, secondJob)
            assertEquals(2, parts.distinct().size)
            assertArrayEquals(second.readBytes(), File(remote, "script.py").readBytes())
            assertTrue(remote.listFiles()!!.none { it.name.endsWith(".part") })
            // A refused overwrite must keep the first whole file.
            try { seed.upload(first, "/script.py", replace = false); fail("Existing target") }
            catch (_: IllegalStateException) { }
            assertArrayEquals(second.readBytes(), File(remote, "script.py").readBytes())
        }
    }

    @Test(timeout = 20_000) fun stopWhileWaitingForDestinationDoesNotPublishOrBlockTheNextUpload() = runBlocking {
        link().use { seed ->
            seed.connect()
            val first = source("first", 1)
            val next = source("next", 2)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val firstJob = async(Dispatchers.IO) { seed.isolated().use { link -> link.upload(first, "/script.py") { done, _ ->
                if (done > 0 && entered.count > 0) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            } } }
            check(entered.await(3, TimeUnit.SECONDS))
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { seed.isolated().use { it.upload(next, "/script.py") } }
            waiting.cancelAndJoin()
            release.countDown()
            firstJob.await()
            assertArrayEquals(first.readBytes(), File(remote, "script.py").readBytes())
            seed.upload(next, "/script.py")
            assertArrayEquals(next.readBytes(), File(remote, "script.py").readBytes())
        }
    }
}
