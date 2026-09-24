package dev.androidagent.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Where to connect and how to prove who we are. */
data class SshTarget(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    /** The trusted host key (base64 blob), or null to trust the first one seen. */
    val pinnedHostKey: String?,
)

/** The host key a server presented. */
data class HostKeySeen(val type: String, val key: String, val fingerprint: String)

data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

/** The computer answered with a different host key than the one trusted. */
class HostKeyChanged(val seen: HostKeySeen) : Exception(
    "This computer's identity changed (now ${seen.fingerprint}). " +
        "If you reinstalled it or changed its address on purpose, remove it and add it again.",
)

/**
 * One SSH connection to a computer, shared by setup commands and the app-server.
 *
 * Blocking; callers run it on an IO dispatcher. Host keys are trusted on first
 * use and pinned after: a later mismatch refuses the connection before the
 * password is sent.
 */
class SshLink(private val target: SshTarget) : Closeable {

    private val jsch = JSch()
    private var session: Session? = null
    private var seen: HostKeySeen? = null

    /** Connect if needed and return the host key the server presented. */
    @Synchronized
    fun connect(): HostKeySeen {
        session?.takeIf { it.isConnected }?.let { return seen!! }
        val keys = PinnedHostKeys(target.pinnedHostKey)
        val next = jsch.getSession(target.user, target.host, target.port).apply {
            setPassword(target.password)
            userInfo = PasswordOnly(target.password)
            hostKeyRepository = keys
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            setServerAliveInterval(KEEPALIVE_MS)
            setServerAliveCountMax(KEEPALIVE_MISSES)
        }
        try {
            next.connect(CONNECT_TIMEOUT_MS)
        } catch (error: JSchException) {
            keys.changed?.let { throw HostKeyChanged(it) }
            throw IllegalStateException(describe(error), error)
        }
        val presented = keys.presented ?: run {
            next.disconnect()
            error("The computer presented no host key")
        }
        session = next
        seen = presented
        return presented
    }

    /** Run one command to completion. Standard input is closed at once. */
    fun run(command: String, timeoutMs: Long = 60_000): ExecResult {
        val channel = openExec(command)
        channel.setInputStream(ByteArrayInputStream(ByteArray(0)))
        val out = channel.inputStream
        val err = channel.extInputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val readers = listOf(
            thread(isDaemon = true, name = "ssh-stdout") { runCatching { out.copyTo(stdout) } },
            thread(isDaemon = true, name = "ssh-stderr") { runCatching { err.copyTo(stderr) } },
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            readers.forEach { reader ->
                reader.join(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1))
            }
            while (!channel.isClosed && System.nanoTime() < deadline) Thread.sleep(20)
            if (!channel.isClosed || readers.any { it.isAlive }) error("The computer did not answer within ${timeoutMs / 1000} s")
            return ExecResult(stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8), channel.exitStatus)
        } finally {
            channel.disconnect()
        }
    }

    /** Start a long-running command whose streams the caller owns. */
    fun start(command: String): Process {
        val channel = openExec(command)
        val stdout = channel.inputStream
        val stderr = channel.extInputStream
        val stdin = channel.outputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        return SshProcess(channel, stdin, stdout, stderr)
    }

    @Synchronized
    override fun close() {
        session?.disconnect()
        session = null
    }

    private fun openExec(command: String): ChannelExec {
        connect()
        val live = synchronized(this) { session } ?: error("Not connected")
        return (live.openChannel("exec") as ChannelExec).apply { setCommand(command.toByteArray(Charsets.UTF_8)) }
    }

    /** Trust on first use, then only the pinned key. */
    private class PinnedHostKeys(private val pinned: String?) : HostKeyRepository {
        @Volatile var presented: HostKeySeen? = null
        @Volatile var changed: HostKeySeen? = null

        override fun check(host: String?, key: ByteArray): Int {
            val offered = describeKey(key)
            presented = offered
            return when {
                pinned == null || pinned == offered.key -> HostKeyRepository.OK
                else -> { changed = offered; HostKeyRepository.CHANGED }
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "hey-mike-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers password and keyboard-interactive prompts with the one password. */
    private class PasswordOnly(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_MS = 20_000
        private const val KEEPALIVE_MISSES = 3

        /** Key type, base64 blob and the OpenSSH `SHA256:` fingerprint. */
        fun describeKey(blob: ByteArray): HostKeySeen {
            val type = runCatching {
                val buffer = ByteBuffer.wrap(blob)
                val length = buffer.int
                require(length in 1..64 && length <= buffer.remaining())
                String(blob, 4, length, Charsets.US_ASCII)
            }.getOrDefault("unknown")
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return HostKeySeen(
                type = type,
                key = Base64.getEncoder().encodeToString(blob),
                fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest),
            )
        }

        /** One plain sentence for the reasons a connection usually fails. */
        internal fun describe(error: JSchException): String {
            val message = error.message.orEmpty()
            val cause = error.cause
            return when {
                message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true) ->
                    "The computer refused the user name or password."
                cause is java.net.UnknownHostException -> "The phone could not find that computer name. Try its IP address."
                cause is java.net.ConnectException -> "Nothing answered on that address and port. Is OpenSSH Server running on the computer?"
                cause is java.net.SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                    "The computer did not answer. Check that the phone and the computer are on the same network."
                else -> "SSH failed: ${message.ifBlank { error.toString() }}"
            }
        }
    }
}

/** A remote command seen through [Process], so the engine treats it like a local one. */
internal class SshProcess(
    private val channel: ChannelExec,
    private val stdin: OutputStream,
    private val stdout: InputStream,
    private val stderr: InputStream,
) : Process() {
    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int {
        while (!channel.isClosed) Thread.sleep(100)
        return channel.exitStatus
    }

    override fun exitValue(): Int {
        if (!channel.isClosed) throw IllegalThreadStateException("Still running")
        return channel.exitStatus
    }

    override fun isAlive(): Boolean = channel.isConnected && !channel.isClosed

    override fun destroy() {
        runCatching { stdin.close() }
        channel.disconnect()
    }
}
