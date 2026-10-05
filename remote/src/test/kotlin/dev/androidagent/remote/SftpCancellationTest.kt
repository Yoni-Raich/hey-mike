package dev.androidagent.remote

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SftpCancellationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun stopClosesABlockedDownloadAndTheNextTransferCanRun() = cancelTransfer(download = true)
    @Test fun stopClosesABlockedUploadAndTheNextTransferCanRun() = cancelTransfer(download = false)

    private fun cancelTransfer(download: Boolean) = runBlocking {
        val remoteRoot = temp.newFolder("remote")
        val source = temp.newFile("source.bin").apply { writeBytes(ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }) }
        source.copyTo(File(remoteRoot, "large.bin"))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val logins = AtomicInteger()
        val factory = SftpSubsystemFactory().apply {
            addSftpEventListener(object : SftpEventListener {
                private fun pause() {
                    if (pauseOnce.compareAndSet(true, false)) {
                        entered.complete(Unit)
                        release.await(15, TimeUnit.SECONDS)
                    }
                }
                override fun reading(session: ServerSession, handle: String, file: FileHandle, offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) {
                    if (download) pause()
                }
                override fun writing(session: ServerSession, handle: String, file: FileHandle, offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int) {
                    if (!download) pause()
                }
            })
        }
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { _, _, _ -> logins.incrementAndGet(); true }
            fileSystemFactory = VirtualFileSystemFactory(remoteRoot.toPath())
            subsystemFactories = listOf(factory)
            start()
        }
        val link = SshLink(SshTarget("127.0.0.1", server.port, "test", "test-only", null))
        try {
            val completed = AtomicBoolean()
            val transfer = launch(Dispatchers.IO) {
                if (download) link.download("/large.bin", File(temp.root, "received.bin"), Long.MAX_VALUE)
                else link.upload(source, "/uploaded.bin")
                completed.set(true)
            }
            try {
                withTimeout(5_000) { entered.await() }
                // The server is still withholding a reply. Stop must release
                // the client without waiting for the stalled transfer to end.
                withTimeout(3_000) { transfer.cancelAndJoin() }
                assertFalse(completed.get())
                assertTrue(transfer.isCancelled)
                // A stopped download leaves nothing that could pass for the file.
                if (download) {
                    assertFalse(File(temp.root, "received.bin").exists())
                    assertFalse(File(temp.root, ".received.bin.part").exists())
                }
            } finally { release.countDown(); transfer.cancel() }

            val small = temp.newFile("small.txt").apply { writeText("next run") }
            val copied = temp.newFile("copied.txt")
            withTimeout(5_000) {
                link.upload(small, "/next.txt")
                link.download("/next.txt", copied, 100)
            }
            assertEquals("next run", copied.readText())
            assertEquals("Cancelling one transfer must preserve the shared SSH session", 1, logins.get())
        } finally {
            release.countDown()
            link.close()
            server.stop(true)
        }
    }
}
