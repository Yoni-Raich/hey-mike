package dev.androidagent.remote

import dev.androidagent.core.RecoverableConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Reattach to the SAME relay after an SSH loss. Only unacknowledged frames
 * are resent; the host deduplicates them before writing to Codex's stdin.
 * A partial network frame is never passed to the engine's JSON reader.
 */
internal class CodexRelayProcess(
    private val attach: (after: Long) -> Process,
    private val status: (String) -> Unit = {},
    private val retryDelaysMs: LongArray = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000),
    private val recoveryMs: Long = 300_000,
    private val heartbeatMs: Long = 5_000,
    private val stalledMs: Long = 20_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : Process(), RecoverableConnection {
    private fun now() = clock()
    private val gate = Object()
    private val writes = Any()
    private val pending = linkedMapOf<Long, String>()
    private val stdout = BufferedInput(64 * 1024 * 1024)
    private val stderr = BufferedInput(1024 * 1024, discardOverflow = true)
    private var inputSeq = 0L
    private var outputSeq = 0L
    @Volatile private var stopped = false
    @Volatile private var ended = false
    @Volatile private var terminalFailure: IOException? = null
    @Volatile override var reconnecting = true
        private set
    @Volatile private var transport: Process? = null
    @Volatile private var lastContact = now()
    private var exit = -1

    private val input = object : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        override fun write(b: Int) { bytes.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes.write(b, off, len) }
        override fun flush() {
            val batch = bytes.toByteArray()
            if (batch.isEmpty()) return
            bytes.reset()
            if (batch.last() != '\n'.code.toByte()) throw IOException("Incomplete Codex input line")
            var start = 0
            for (index in batch.indices) if (batch[index] == '\n'.code.toByte()) {
                val line = batch.copyOfRange(start, index + 1)
                start = index + 1
                val seq = synchronized(gate) {
                    checkOpen()
                    val next = ++inputSeq
                    pending[next] = frame("I", next, line)
                    next
                }
                // The pump sends new and unacknowledged frames. The caller waits for
                // the broker's receipt, including if that receipt was lost in transit.
                synchronized(gate) {
                    gate.notifyAll()
                    while (pending.containsKey(seq) && !stopped && !ended) gate.wait(250)
                    checkOpen()
                }
            }
        }
        override fun close() { destroy() }
    }

    private val pump = thread(isDaemon = true, name = "codex-relay-reader") { pump() }
    private val heartbeat = thread(isDaemon = true, name = "codex-relay-heartbeat") {
      try {
        while (!stopped && !ended) {
            synchronized(gate) { if (!stopped && !ended) gate.wait(heartbeatMs) }
            if (stopped || ended) break
            val live = transport ?: continue
            if (now() - lastContact >= stalledMs) {
                live.destroy() // This detaches SSH; the relay retains Codex and its receipts.
                continue
            }
            try {
                send(live, "P")
                val batch = synchronized(gate) { pending.values.toList() }
                for (frame in batch) send(live, frame)
            } catch (_: IOException) { live.destroy() }
        }
      } catch (_: InterruptedException) { /* Local Stop wakes a sleeping heartbeat. */ }
    }

    private fun pump() {
        var disconnectedAt: Long? = null
        var attempts = 0
        try {
            while (!stopped) {
                var live: Process? = null
                try {
                    live = attach(outputSeq)
                    if (stopped) { live.destroy(); break }
                    transport = live
                    lastContact = now()
                    var hello = false
                    val monitored = object : FilterInputStream(live.inputStream) {
                        override fun read(): Int = super.read().also { if (it >= 0) lastContact = now() }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) lastContact = now() }
                    }
                    monitored.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (!stopped) {
                            val line = reader.readLine() ?: throw IOException("SSH attachment ended")
                            lastContact = now()
                            if (line == "H1") {
                                if (hello) throw IOException("Duplicate relay handshake")
                                hello = true
                                reconnecting = false
                                if (attempts > 0) status("Connection restored. Continuing the same task.")
                                synchronized(gate) { gate.notifyAll() }
                                for (frame in synchronized(gate) { pending.values.toList() }) send(live, frame)
                            } else {
                                if (line.startsWith("F ")) throw RelayEnded("Codex relay ended. Task outcome is unknown.")
                                if (!hello) throw IOException("Missing relay handshake")
                                when {
                                    line == "P" -> Unit
                                    line.startsWith("A ") -> {
                                        val seq = line.substring(2).toLongOrNull() ?: throw IOException("Invalid input acknowledgement")
                                        synchronized(gate) {
                                            if (seq > inputSeq || seq <= 0) throw IOException("Invalid input acknowledgement")
                                            pending.remove(seq)
                                            gate.notifyAll()
                                        }
                                    }
                                    line.startsWith("O ") || line.startsWith("D ") -> {
                                        val (seq, payload) = payload(line)
                                        if (seq > outputSeq) {
                                            if (seq != outputSeq + 1) throw RelayEnded("Codex relay output has a gap. Task outcome is unknown.")
                                            if (line[0] == 'O') stdout.add(payload) else stderr.add(payload)
                                            outputSeq = seq
                                        }
                                        send(live, "A $outputSeq")
                                    }
                                    line.startsWith("E ") -> {
                                        exit = line.substring(2).toIntOrNull() ?: -1
                                        ended = true
                                        return
                                    }
                                    line.startsWith("F ") -> throw RelayEnded("Codex relay could not restore the connection. Task outcome is unknown.")
                                    line == "X" -> { ended = true; return }
                                    else -> throw IOException("Invalid relay frame")
                                }
                                // A handshake alone must not reset the budget when the
                                // same truncated frame keeps failing on every attachment.
                                attempts = 0
                                disconnectedAt = null
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (stopped) break
                    if (error is RelayEnded || !canRetry(error)) throw error
                    reconnecting = true
                    val since = disconnectedAt ?: now().also { disconnectedAt = it }
                    if (now() - since >= recoveryMs) throw RelayEnded("The computer connection did not recover. Task outcome is unknown.")
                    attempts++
                    status("Connection lost. Reconnecting ($attempts); the same task is kept.")
                    val wait = retryDelaysMs[(attempts - 1).coerceAtMost(retryDelaysMs.lastIndex)]
                    synchronized(gate) { if (!stopped) gate.wait(wait) }
                } finally {
                    transport = null
                    if (!stopped) live?.destroy()
                }
            }
        } catch (error: Exception) {
            // No raw frame, host command, capability token or account data enters an error.
            val message = if (error is RelayEnded) "${error.message} The task was not resent."
                else "Computer connection could not recover. Task outcome is unknown; the task was not resent."
            terminalFailure = IOException(message)
            stdout.fail(checkNotNull(terminalFailure))
        } finally {
            ended = true
            reconnecting = false
            stdout.finish()
            stderr.finish()
            synchronized(gate) { gate.notifyAll() }
        }
    }

    private fun send(live: Process, frame: String) = synchronized(writes) {
        if (stopped) throw IOException("Codex relay closed")
        live.outputStream.write((frame + "\n").toByteArray(Charsets.UTF_8))
        live.outputStream.flush()
    }

    private fun checkOpen() {
        if (ended) throw terminalFailure ?: IOException("Codex relay closed; delivery may be unknown")
        if (stopped) throw IOException("Codex relay closed; delivery may be unknown")
    }
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun getOutputStream(): OutputStream = input
    override fun isAlive(): Boolean = !stopped && !ended
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException("Still running") else exit
    override fun waitFor(): Int { synchronized(gate) { while (isAlive) gate.wait(250) }; return exit }

    override fun destroy() {
        if (stopped) return
        stopped = true // Revoke writes before any network work.
        val live = transport
        // Stop owns only this relay's child. Offline hosts have a five-minute lease.
        thread(isDaemon = true, name = "codex-relay-stop") {
            try {
                if (live != null) synchronized(writes) {
                    live.outputStream.write("X\n".toByteArray(Charsets.UTF_8)); live.outputStream.flush()
                }
            } catch (_: Exception) { }
            finally { live?.destroy() }
        }
        stdout.finish()
        stderr.finish()
        synchronized(gate) { gate.notifyAll() }
        pump.interrupt()
        heartbeat.interrupt()
    }

    internal class RelayEnded(message: String) : IOException(message)
    private class BufferedInput(private val max: Int, private val discardOverflow: Boolean = false) : InputStream() {
        private val gate = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var offset = 0
        private var size = 0
        private var finished = false
        private var failure: IOException? = null
        fun add(bytes: ByteArray) = synchronized(gate) {
            if (finished) return@synchronized
            if (size.toLong() + bytes.size > max) {
                if (discardOverflow) return@synchronized
                throw RelayEnded("Unread Codex output exceeded the relay limit")
            }
            chunks.add(bytes); size += bytes.size; gate.notifyAll()
        }
        fun finish() = synchronized(gate) { finished = true; gate.notifyAll() }
        fun fail(error: IOException) = synchronized(gate) { failure = error; finished = true; gate.notifyAll() }
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(gate) {
            if (len == 0) return@synchronized 0
            while (chunks.isEmpty() && !finished) gate.wait()
            if (chunks.isEmpty()) { failure?.let { throw it }; return@synchronized -1 }
            val first = chunks.first
            val count = minOf(len, first.size - offset)
            first.copyInto(b, off, offset, offset + count)
            offset += count; size -= count
            if (offset == first.size) { chunks.removeFirst(); offset = 0 }
            count
        }
    }

    companion object {
        private fun canRetry(error: Exception): Boolean =
            error is IOException || error is SshUnreachable || error is com.jcraft.jsch.JSchException
        internal fun frame(kind: String, seq: Long, bytes: ByteArray): String {
            if (bytes.size > 32 * 1024 * 1024) throw IOException("Codex input exceeds relay limit")
            val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            return "$kind $seq ${bytes.size} $hash ${Base64.getEncoder().encodeToString(bytes)}"
        }
        internal fun payload(line: String): Pair<Long, ByteArray> {
            if (line.length > 64 * 1024 * 1024) throw IOException("Relay frame too large")
            val parts = line.split(' ', limit = 5)
            if (parts.size != 5) throw IOException("Incomplete relay frame")
            val seq = parts[1].toLongOrNull() ?: throw IOException("Invalid relay sequence")
            val length = parts[2].toIntOrNull() ?: throw IOException("Invalid relay length")
            if (seq <= 0 || length !in 1..32 * 1024 * 1024) throw IOException("Invalid relay length")
            val bytes = try { Base64.getDecoder().decode(parts[4]) } catch (_: IllegalArgumentException) { throw IOException("Incomplete relay payload") }
            val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            if (bytes.size != length || hash != parts[3]) throw IOException("Incomplete relay payload")
            return seq to bytes
        }
    }
}
