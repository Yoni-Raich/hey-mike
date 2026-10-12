package dev.androidagent.remote

import dev.androidagent.core.DeltaManifest
import dev.androidagent.core.DeltaPeer
import dev.androidagent.core.TransferBlob
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A helper holds its host cache lease on this request's stdin until close. */
internal class SshDeltaPeer(private val link: SshLink, private val os: HostOs, private val home: String, private val scratch: File) : DeltaPeer, Closeable {
    private var process: Process? = null
    private val folder = if (os == HostOs.WINDOWS) home.trimEnd('\\', '/') + "\\.hey-mike\\file-transfer\\v1" else home.trimEnd('/') + "/.hey-mike/file-transfer/v1"
    private val scriptPath = folder + (if (os == HostOs.WINDOWS) "\\" else "/") + "helper-$digest.py"

    override suspend fun prepare(path: String, bases: List<TransferBlob>, allowDelta: Boolean, phase: (dev.androidagent.core.FileTransfer.Phase) -> Unit): DeltaManifest = withContext(Dispatchers.IO) {
        close()
        currentCoroutineContext().ensureActive()
        // Missing Python disables the optimization, not ordinary SFTP copying.
        val pythonProbe = if (os == HostOs.WINDOWS) WindowsHost.powershell("if (Get-Command python -ErrorAction SilentlyContinue) { 'HEYMIKE_PYTHON' }") else "command -v python3 >/dev/null && printf HEYMIKE_PYTHON"
        val python = runInterruptible { link.run(pythonProbe, 15_000) }
        if (!python.stdout.contains("HEYMIKE_PYTHON")) throw DeltaHelperUnavailable()
        val staged = File.createTempFile("delta-helper-", ".py", scratch.apply { mkdirs() })
        try {
            staged.writeBytes(script)
            val size = try { link.size(scriptPath) } catch (stopped: CancellationException) { throw stopped } catch (_: Exception) { null }
            if (size != script.size.toLong()) {
                link.upload(staged, scriptPath, replace = true)
            }
        } finally { staged.delete() }
        val launcher = "import base64,hashlib,runpy\np=base64.b64decode('${encode(scriptPath)}').decode('utf-8')\nif hashlib.sha256(open(p,'rb').read()).hexdigest()!='$digest': raise RuntimeError('Transfer helper checksum mismatch')\nrunpy.run_path(p,run_name='__main__')"
        val command = if (os == HostOs.WINDOWS) WindowsHost.powershell("& python -u -c \"import base64;exec(base64.b64decode('${encode(launcher)}'))\"")
            else "python3 -u -c \"import base64;exec(base64.b64decode('${encode(launcher)}'))\""
        val running = runInterruptible { link.start(command) }.also { process = it }
        var prepared = false
        val stop = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                if (!prepared) { runCatching { running.outputStream.close() }; running.destroy() }
            }
        }
        try {
            val request = buildJsonObject {
                put("protocol", 1); put("path", path); put("allowDelta", allowDelta)
                put("bases", buildJsonArray { bases.take(4).forEach { add(blobJson(it)) } })
            }.toString()
            running.outputStream.write((request + "\n").toByteArray(Charsets.UTF_8)); running.outputStream.flush()
            val payload = withTimeout(240_000) { runInterruptible {
                val input = running.inputStream.buffered()
                val line = StringBuilder()
                var total = 0
                while (true) {
                    val next = input.read()
                    check(next >= 0) { "The computer's delta helper did not answer. Python 3 is required." }
                    check(++total <= 128 * 1024) { "Transfer reply too large" }
                    if (next == 10) {
                        val text = line.toString().trim()
                        if (text.startsWith("HEYMIKE_PHASE ")) {
                            runCatching { dev.androidagent.core.FileTransfer.Phase.valueOf(text.removePrefix("HEYMIKE_PHASE ")) }.getOrNull()?.let(phase)
                        }
                        if (text.startsWith("HEYMIKE ")) return@runInterruptible Json.parseToJsonElement(text.removePrefix("HEYMIKE ")).jsonObject
                        line.setLength(0)
                    } else line.append(next.toChar())
                }
                @Suppress("UNREACHABLE_CODE") error("Missing transfer manifest")
            } }
            payload["error"]?.let { error(it.jsonPrimitive.content) }
            check(payload["protocol"]?.jsonPrimitive?.content == "1") { "Unsupported transfer protocol" }
            val payloadPath = payload.getValue("payloadPath").jsonPrimitive.content
            val normalizedRoot = folder.replace('\\', '/').trimEnd('/') + "/"
            val normalized = payloadPath.replace('\\', '/')
            check(normalized.startsWith(normalizedRoot) && normalized.removePrefix(normalizedRoot).matches(Regex("(?:blobs|patches)/[a-f0-9]{64}"))) { "Transfer artifact outside the host cache" }
            DeltaManifest(parseBlob(payload.getValue("target").jsonObject), parseBlob(payload.getValue("payload").jsonObject),
                payloadPath, payload.getValue("name").jsonPrimitive.content, payload["base"]?.let { parseBlob(it.jsonObject) }, payload.getValue("format").jsonPrimitive.content)
                .also { it.validate(); currentCoroutineContext().ensureActive(); prepared = true }
        } catch (error: Exception) {
            // Java streams report interrupted reads as IOException, not coroutine cancellation.
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            // This child only guards prepare. The enclosing operation keeps the process for its lease.
            withContext(NonCancellable) { stop.cancelAndJoin() }
        }
    }

    override suspend fun fetch(path: String, target: File, limit: Long, progress: (Long, Long) -> Unit) = link.download(path, target, limit, progress)

    fun close(stopped: Boolean) {
        process?.let { finishDeltaProcess(it, stopped) }
        process = null
    }
    override fun close() = close(false)

    companion object {
        private val script = checkNotNull(SshDeltaPeer::class.java.getResourceAsStream("/file-transfer/host_transfer.py")).use { it.readBytes() }
        private val digest = MessageDigest.getInstance("SHA-256").digest(script).joinToString("") { "%02x".format(it) }
        private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun blobJson(blob: TransferBlob) = buildJsonObject { put("sha256", blob.sha256); put("size", blob.size) }
        private fun parseBlob(value: JsonObject) = TransferBlob(value.getValue("sha256").jsonPrimitive.content, value.getValue("size").jsonPrimitive.long)
    }
}

internal class DeltaHelperUnavailable : Exception("Python 3 is unavailable on the computer")

/** A successful helper exits itself. Do not disconnect a live Windows exec channel on success. */
internal fun finishDeltaProcess(running: Process, stopped: Boolean) {
    if (!stopped) runCatching {
        running.outputStream.write("release\n".toByteArray(Charsets.UTF_8))
        running.outputStream.flush()
    }
    runCatching { running.outputStream.close() }
    if (stopped) runCatching { running.destroy() }
    else runCatching { running.waitFor(3, TimeUnit.SECONDS) }
}
