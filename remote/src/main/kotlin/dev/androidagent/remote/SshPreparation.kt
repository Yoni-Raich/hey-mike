package dev.androidagent.remote

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.concurrent.thread

/** Retry only files/probes prepared before launch. Every attempt owns its SSH link. */
internal object SshPreparation {
    suspend fun <T> start(
        open: suspend () -> SshLink,
        prepare: suspend (SshLink) -> T,
        launch: suspend (SshLink, T) -> Process,
        diagnostic: (phase: String, reason: String) -> Unit = { _, _ -> },
        retryDelayMs: Long = 250,
    ): Process = withContext(Dispatchers.IO) {
        for (attempt in 0..1) {
            var link: SshLink? = null
            var launching = false
            var handedOff = false
            try {
                currentCoroutineContext().ensureActive()
                link = open()
                runInterruptible { link.connect() }
                val prepared = prepare(link)
                currentCoroutineContext().ensureActive()
                // Once launch may have reached the server, no exception permits a retry.
                launching = true
                val process = launch(link, prepared)
                try { currentCoroutineContext().ensureActive() }
                catch (cancelled: CancellationException) { process.destroy(); throw cancelled }
                // Keep the recovery marker used by the engine. A retained relay no
                // longer needs the preparation link and owns its attachments itself.
                val owned = if (process is dev.androidagent.core.RecoverableConnection) {
                    link.close()
                    process
                } else own(process, link)
                handedOff = true
                return@withContext owned
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val retry = !launching && attempt == 0 && transportFailure(error)
                diagnostic(if (launching) "launch" else "preparation", if (retry) "transport-retry" else "failed")
                if (!retry) throw error
            } finally {
                if (!handedOff) link?.close()
            }
            delay(retryDelayMs)
        }
        error("SSH preparation attempts exhausted")
    }

    /** Only transport loss, never permissions, authentication, identity, or parsing. */
    internal fun transportFailure(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        if (causes.any { it is CancellationException || it is HostKeyChanged || it is TailscaleCheck || it is IllegalArgumentException }) return false
        if (causes.filterIsInstance<SftpException>().any { it.id == 2 || it.id == 3 }) return false
        return causes.any {
            it is IOException || it is SshUnreachable ||
                it is SftpException && it.id in setOf(6, 7) ||
                it is JSchException && listOf("session is down", "session is not connected", "channel is not opened", "connection is closed")
                    .any { text -> it.message.orEmpty().contains(text, ignoreCase = true) }
        }
    }

    /** Closing or losing this process never closes a different operation's link. */
    internal fun own(process: Process, link: SshLink): Process {
        thread(isDaemon = true, name = "ssh-operation-lifetime") {
            try { process.waitFor() } finally { link.close() }
        }
        return object : Process() {
            override fun getInputStream() = process.inputStream
            override fun getErrorStream() = process.errorStream
            override fun getOutputStream() = process.outputStream
            override fun waitFor(): Int = process.waitFor()
            override fun exitValue(): Int = process.exitValue()
            override fun isAlive(): Boolean = process.isAlive
            override fun destroy() { try { process.destroy() } finally { link.close() } }
        }
    }
}
