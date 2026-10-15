package dev.androidagent.remote

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class CodexRelayProcessTest {
    @Test(timeout = 15_000) fun aLostInputReceiptDoesNotRepeatTheTurnAndOutputsKeepTheirOrder() {
        val forwarded = CopyOnWriteArrayList<String>()
        val attaches = AtomicInteger()
        val receipts = mutableSetOf<Long>()
        val status = CopyOnWriteArrayList<String>()
        val process = CodexRelayProcess(attach = { after ->
            val attempt = attaches.incrementAndGet()
            Peer().apply {
                start {
                    send("H1")
                    eachInput { line ->
                        if (line.startsWith("I ")) {
                            val (seq, bytes) = CodexRelayProcess.payload(line)
                            synchronized(receipts) { if (receipts.add(seq)) forwarded.add(String(bytes, Charsets.UTF_8)) }
                            if (attempt == 1) { endOutput(); return@eachInput }
                            send("A $seq")
                            if (after < 1) send(CodexRelayProcess.frame("O", 1, "first\n".toByteArray()))
                            if (after < 2) send(CodexRelayProcess.frame("O", 2, "second\n".toByteArray()))
                        } else if (line == "P") send("P")
                    }
                }
            }
        }, status = status::add, retryDelaysMs = longArrayOf(5), heartbeatMs = 10, stalledMs = 1_000)
        try {
            val writer = process.outputStream.bufferedWriter()
            writer.write("{\"method\":\"turn/start\"}\n"); writer.flush()
            val reader = process.inputStream.bufferedReader()
            assertEquals("first", reader.readLine())
            assertEquals("second", reader.readLine())
            assertEquals(listOf("{\"method\":\"turn/start\"}\n"), forwarded.toList())
            assertTrue(attaches.get() >= 2)
            assertTrue(status.any { it.contains("Connection restored") })
        } finally { process.destroy() }
    }

    @Test(timeout = 15_000) fun partialFramesAreNotPassedToTheJsonReaderAndReplayedOutputIsNotDuplicated() {
        val attaches = AtomicInteger()
        val text = "שלום 🙂 ".repeat(15_000) + "\n"
        val frame = CodexRelayProcess.frame("O", 1, text.toByteArray(Charsets.UTF_8))
        val process = CodexRelayProcess(attach = { after ->
            val n = attaches.incrementAndGet()
            Peer().apply { start {
                send("H1")
                when (n) {
                    1 -> { send(frame.substring(0, frame.length / 2)); endOutput() }
                    2 -> { assertEquals(0L, after); send(frame); endOutput() }
                    else -> {
                        assertEquals(1L, after)
                        send(frame) // An old attachment's last output can be replayed.
                        send(CodexRelayProcess.frame("O", 2, "done\n".toByteArray()))
                        eachInput { if (it == "P") send("P") }
                    }
                }
            } }
        }, retryDelaysMs = longArrayOf(5), heartbeatMs = 10, stalledMs = 1_000)
        try {
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            assertEquals(text.trimEnd('\n'), reader.readLine())
            assertEquals("done", reader.readLine())
            assertTrue(attaches.get() >= 3)
        } finally { process.destroy() }
    }

    @Test(timeout = 15_000) fun stopWakesAnOfflineWriterAndDoesNotCreateAnotherConnection() {
        val attempts = AtomicInteger()
        val attempted = CountDownLatch(1)
        val process = CodexRelayProcess(attach = {
            attempts.incrementAndGet(); attempted.countDown()
            throw IOException("offline")
        }, retryDelaysMs = longArrayOf(1_000), heartbeatMs = 10)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val write = pool.submit<Boolean> {
                try { process.outputStream.write("{}\n".toByteArray()); process.outputStream.flush(); false }
                catch (_: IOException) { true }
            }
            assertTrue(attempted.await(1, TimeUnit.SECONDS))
            process.destroy()
            assertTrue(write.get(1, TimeUnit.SECONDS))
            val count = attempts.get()
            Thread.sleep(50)
            assertEquals(count, attempts.get())
            assertFalse(process.isAlive)
        } finally { process.destroy(); pool.shutdownNow() }
    }

    @Test(timeout = 15_000) fun aChangedHostKeyIsNotRetriedOrExposedAsAFrame() {
        val attempts = AtomicInteger()
        val process = CodexRelayProcess(attach = {
            attempts.incrementAndGet()
            throw HostKeyChanged(HostKeySeen("ssh-ed25519", "secret-capability", "SHA256:fixture"))
        }, retryDelaysMs = longArrayOf(1), heartbeatMs = 10)
        try {
            try { process.inputStream.read(); fail("Must report a terminal failure") }
            catch (error: IOException) { assertFalse(error.message!!.contains("secret-capability")) }
            assertEquals(1, attempts.get())
        } finally { process.destroy() }
    }

    @Test(timeout = 15_000) fun anExpiredRelayNeverStartsAReplacementTask() {
        val attempts = AtomicInteger()
        val process = CodexRelayProcess(attach = {
            attempts.incrementAndGet()
            Peer().apply { start { send("F Relay ended"); endOutput() } }
        }, retryDelaysMs = longArrayOf(1), heartbeatMs = 10)
        try {
            try { process.inputStream.read(); fail("Must report expired relay") }
            catch (error: IOException) { assertTrue(error.message!!.contains("not resent")) }
            assertEquals(1, attempts.get())
            val lateWrite = runCatching {
                process.outputStream.write("{}\n".toByteArray()); process.outputStream.flush()
            }.exceptionOrNull()
            assertTrue(lateWrite is IOException)
            assertTrue(lateWrite!!.message!!.contains("not resent"))
        } finally { process.destroy() }
    }

    @Test fun aValidBase64TailStillNeedsItsLengthAndChecksum() {
        val frame = CodexRelayProcess.frame("O", 1, "complete JSON message\n".toByteArray())
        assertTrue(runCatching { CodexRelayProcess.payload(frame.dropLast(4)) }.isFailure)
        assertTrue(runCatching { CodexRelayProcess.payload(frame.replace("O 1 ", "O 0 ")) }.isFailure)
    }

    @Test(timeout = 15_000) fun repeatedHandshakesDoNotResetTheInvalidFrameRecoveryBudget() {
        val attaches = AtomicInteger()
        val clock = AtomicLong()
        val process = CodexRelayProcess(attach = {
            attaches.incrementAndGet()
            clock.addAndGet(40)
            Peer().apply { start { send("H1"); send("O 1 broken"); endOutput() } }
        }, retryDelaysMs = longArrayOf(5), heartbeatMs = 1_000, recoveryMs = 100, clock = clock::get)
        try {
            try { process.inputStream.read(); fail("Must end the bounded recovery") }
            catch (error: IOException) { assertTrue(error.message!!.contains("not resent")) }
            assertTrue(attaches.get() > 1)
            assertFalse(process.isAlive)
        } finally { process.destroy() }
    }

    private class Peer : Process() {
        private val incoming = PipedInputStream(1024 * 1024)
        private val input = PipedOutputStream(incoming)
        private val outgoing = PipedInputStream(1024 * 1024)
        private val output = PipedOutputStream(outgoing)
        private val writer = output.bufferedWriter(Charsets.UTF_8)
        @Volatile private var alive = true
        fun start(work: Peer.() -> Unit) = thread(isDaemon = true) {
            try { work() } catch (_: IOException) { }
        }
        fun send(line: String) = synchronized(writer) { writer.write(line); writer.newLine(); writer.flush() }
        fun endOutput() = output.close()
        fun eachInput(work: (String) -> Unit) = incoming.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(work) }
        override fun getOutputStream() = input
        override fun getInputStream() = outgoing
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = alive
        override fun waitFor() = 0
        override fun exitValue() = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() {
            alive = false
            listOf(incoming, input, outgoing, output).forEach { runCatching { it.close() } }
        }
    }
}
