package dev.androidagent.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
    val connectTimeoutMs: Int = SshLink.CONNECT_TIMEOUT_MS,
)

/** The host key a server presented. */
data class HostKeySeen(val type: String, val key: String, val fingerprint: String)

data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

/** The computer answered with a different host key than the one trusted. */
/**
 * Tailscale SSH answered in place of the computer's own SSH server. It signs
 * in by Tailscale identity, not password; with a "check" rule it first wants
 * the user to approve in a browser at [url]. Once approved, connecting again
 * gets in for the rule's check period.
 */
class TailscaleCheck(val url: String?, message: String) : Exception(message)

class HostKeyChanged(val seen: HostKeySeen) : Exception(
    "This computer's identity changed (now ${seen.fingerprint}). " +
        "If you reinstalled it or changed its address on purpose, remove it and add it again.",
)

/** Nothing answered on the address: another address of the same computer may. */
class SshUnreachable(message: String, cause: Throwable) : Exception(message, cause)

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
    // Keep TOFU trust for this link too, including after close() or a lost session.
    private var trustedHostKey: String? = target.pinnedHostKey

    /** Connect if needed and return the host key the server presented. */
    @Synchronized
    fun connect(): HostKeySeen {
        session?.takeIf { it.isConnected }?.let { return seen!! }
        val keys = PinnedHostKeys(trustedHostKey)
        val prompts = PasswordOnly(target.password)
        val next = jsch.getSession(target.user, target.host, target.port).apply {
            setPassword(target.password)
            userInfo = prompts
            hostKeyRepository = keys
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            setServerAliveInterval(KEEPALIVE_MS)
            setServerAliveCountMax(KEEPALIVE_MISSES)
        }
        try {
            next.connect(target.connectTimeoutMs)
        } catch (error: JSchException) {
            val version = runCatching { next.serverVersion }.getOrNull()
            next.disconnect()
            keys.changed?.let { throw HostKeyChanged(it) }
            // Tailscale SSH answers on the tailnet address in place of the
            // computer's own SSH server. It takes no password and may wait for
            // a browser check, which reads as a timeout; say what it is.
            // Reading the version of a server that never sent one throws
            // inside JSch; an address that did not answer has none.
            if (isTailscaleSsh(version, prompts.banner)) {
                throw TailscaleCheck(approvalLink(prompts.banner), tailscaleSshMessage(prompts.banner))
            }
            if (unreachable(error)) throw SshUnreachable(describe(error), error)
            throw IllegalStateException(describe(error), error)
        }
        val presented = keys.presented ?: run {
            next.disconnect()
            error("The computer presented no host key")
        }
        session = next
        seen = presented
        trustedHostKey = presented.key
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

    /**
     * Copy a file from the computer to [target], refusing one over [maxBytes].
     * [progress] hears (bytes so far, total) as the copy goes.
     */
    suspend fun download(
        remotePath: String,
        target: File,
        maxBytes: Long,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = withSftp(remotePath) { sftp ->
        val path = sftpPath(remotePath)
        val size = sftp.stat(path).size
        check(size <= maxBytes) { "The file is ${size / (1024 * 1024)} MB; the limit is ${maxBytes / (1024 * 1024)} MB." }
        target.parentFile?.mkdirs()
        progress(0, size)
        target.outputStream().use { sftp.get(path, it, Counting(size, progress)) }
    }

    /** Copy [source] to the computer, replacing a file already at [remotePath]. */
    suspend fun upload(source: File, remotePath: String, progress: (Long, Long) -> Unit = { _, _ -> }) = withSftp(remotePath) { sftp ->
        val size = source.length()
        progress(0, size)
        source.inputStream().use { sftp.put(it, sftpPath(remotePath), Counting(size, progress), ChannelSftp.OVERWRITE) }
    }

    /** JSch's progress hook, adding up the chunks it reports. */
    private class Counting(private val total: Long, private val progress: (Long, Long) -> Unit) : com.jcraft.jsch.SftpProgressMonitor {
        private var done = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
        override fun count(count: Long): Boolean {
            done += count
            progress(done, total)
            return true
        }
        override fun end() = Unit
    }

    private suspend fun <T> withSftp(remotePath: String, block: (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        runInterruptible { connect() }
        val live = synchronized(this) { session } ?: error("Not connected")
        val sftp = live.openChannel("sftp") as ChannelSftp
        // Cancelling a coroutine alone does not close JSch's blocking I/O.
        // This child closes only this transfer channel, leaving Codex and
        // other channels on the shared SSH session available for Stop.
        val closeOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { sftp.disconnect() }
        }
        try {
            runInterruptible {
                sftp.connect(CONNECT_TIMEOUT_MS)
                block(sftp)
            }
        } catch (error: com.jcraft.jsch.SftpException) {
            // JSch wraps the interrupted/closed stream as SftpException.
            // Preserve Stop as cancellation rather than a failed child job.
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(
                if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) "No such file on the computer: $remotePath" else "SFTP failed: ${error.message}",
                error,
            )
        } finally {
            withContext(NonCancellable) { closeOnCancel.cancelAndJoin() }
        }
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

    /** Answers password and keyboard-interactive prompts with the one password, and keeps the server's banner. */
    private class PasswordOnly(private val password: String) : UserInfo, UIKeyboardInteractive {
        /** What the server showed before sign-in, if anything. */
        @Volatile var banner: String? = null
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) { if (!message.isNullOrBlank()) banner = (banner.orEmpty() + message).take(2_000) }
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

        /** Tailscale's own SSH server names itself in its version string and its banner. */
        internal fun isTailscaleSsh(serverVersion: String?, banner: String?): Boolean =
            serverVersion.orEmpty().contains("Tailscale", ignoreCase = true) ||
                banner.orEmpty().contains("Tailscale SSH", ignoreCase = true)

        internal fun approvalLink(banner: String?): String? =
            banner?.let { Regex("https://\\S+").find(it)?.value?.trimEnd('.', ',', ')') }

        internal fun tailscaleSshMessage(banner: String?): String {
            val link = approvalLink(banner)
            return buildString {
                append("This address is answered by Tailscale SSH, not the computer's own SSH server. ")
                append("Tailscale SSH does not take a password")
                if (link != null) append(" and is waiting for a check in the browser ($link)")
                append(". On the computer, run: sudo tailscale set --ssh=false. Its own SSH server then answers on the same address.")
            }
        }

        /** Windows OpenSSH's SFTP spells `C:\a\b` as `/C:/a/b`. */
        internal fun sftpPath(windows: String): String = "/" + windows.replace('\\', '/').trimStart('/')

        /** The address did not answer at all, as opposed to answering and refusing. */
        internal fun unreachable(error: JSchException): Boolean {
            val cause = error.cause
            return cause is java.net.UnknownHostException || cause is java.net.ConnectException ||
                cause is java.net.NoRouteToHostException || cause is java.net.SocketTimeoutException ||
                error.message.orEmpty().contains("timeout", ignoreCase = true)
        }

        /** One plain sentence for the reasons a connection usually fails. */
        internal fun describe(error: JSchException): String {
            val message = error.message.orEmpty()
            val cause = error.cause
            return when {
                message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true) ->
                    "The computer refused the user name or password."
                cause is java.net.UnknownHostException -> "The phone could not find that computer name. Try its IP address."
                cause is java.net.ConnectException -> "The computer is there, but nothing answered on that port. Is OpenSSH Server running on it?"
                cause is java.net.SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                    "The computer did not answer. It may be asleep, or on another network than the phone."
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
