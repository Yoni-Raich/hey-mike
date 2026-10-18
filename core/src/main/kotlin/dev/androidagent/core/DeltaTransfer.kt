package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Wire identity is content, never a filename, version number or chat id. */
data class TransferBlob(val sha256: String, val size: Long) {
    init { require(sha256.matches(Regex("[a-f0-9]{64}")) && size >= 0) { "Invalid transfer identity" } }
}

data class DeltaManifest(
    val target: TransferBlob,
    val payload: TransferBlob,
    val payloadPath: String,
    val name: String,
    val base: TransferBlob? = null,
    val format: String = "full",
) {
    fun validate() {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        require(payloadPath.isNotBlank() && '\u0000' !in payloadPath)
        require(format == "full" || format == FORMAT)
        require((format == "full") == (base == null))
        if (base == null) require(payload == target) else require(payload.size < target.size)
    }
    companion object { const val FORMAT = "HDIFFW26-zstd-v5.1.3-s64-w2m-step256k" }
}

interface DeltaPeer {
    suspend fun prepare(path: String, bases: List<TransferBlob>, allowDelta: Boolean, phase: (FileTransfer.Phase) -> Unit = {}): DeltaManifest
    suspend fun fetch(path: String, target: File, limit: Long, progress: (Long, Long) -> Unit)
}

/** The implementation holds a cache lease until the entire operation has finished. */
interface TransferBlobCache {
    suspend fun <T> locked(block: suspend () -> T): T
    suspend fun candidates(): List<TransferBlob>
    suspend fun verified(blob: TransferBlob): File?
    fun temporary(): File
    suspend fun publish(file: File, blob: TransferBlob): File
    suspend fun materialize(blob: File, target: File)
    fun hasSpace(bytes: Long): Boolean
}

interface DeltaApplier {
    val available: Boolean
    suspend fun apply(base: File, patch: File, output: File, expected: TransferBlob, progress: (Long, Long) -> Unit)
}

/** All negotiation and recovery happen locally; the model still calls copy_file once. */
class DeltaTransfers(private val cache: TransferBlobCache, private val applier: DeltaApplier) {
    // Serializes cache leases/eviction and bounds native memory across concurrent chats.
    private val gate = Mutex()

    suspend fun download(peer: DeltaPeer, path: String, target: File, progress: (Long, Long) -> Unit): FilePlace.Fetched = gate.withLock {
        cache.locked {
            currentCoroutineContext().ensureActive()
            progress.transferPhase(FileTransfer.Phase.PREPARING)
            progress.transferPhase(FileTransfer.Phase.HASHING)
            val candidates = if (applier.available) cache.candidates() else emptyList()
            val planned = peer.prepare(path, candidates, applier.available) { progress.transferPhase(it) }.also { it.validate() }
            cache.verified(planned.target)?.let { ready ->
                currentCoroutineContext().ensureActive()
                progress.transferPhase(FileTransfer.Phase.PUBLISHING)
                cache.materialize(ready, target)
                return@locked FilePlace.Fetched(planned.target.size, planned.name)
            }
            var manifest = planned
            var ready: File? = null
            if (manifest.base != null) {
                try {
                    ready = receive(peer, manifest, progress)
                } catch (stopped: CancellationException) {
                    throw stopped // Stop never causes full transfer.
                } catch (failed: Exception) {
                    currentCoroutineContext().ensureActive()
                    // Re-prepare the same immutable target, never a changing build path.
                    manifest = peer.prepare("sha256:" + planned.target.sha256, emptyList(), false) { progress.transferPhase(it) }.also { it.validate() }
                    check(manifest.target == planned.target && manifest.base == null) { "Fallback changed the target" }
                }
            }
            val blob = ready ?: receive(peer, manifest, progress)
            currentCoroutineContext().ensureActive()
            progress.transferPhase(FileTransfer.Phase.PUBLISHING)
            cache.materialize(blob, target)
            FilePlace.Fetched(planned.target.size, planned.name)
        }
    }

    private suspend fun receive(peer: DeltaPeer, manifest: DeltaManifest, progress: (Long, Long) -> Unit): File {
        check(manifest.payload.size <= Long.MAX_VALUE - manifest.target.size) { "Transfer size overflow" }
        check(cache.hasSpace(manifest.payload.size + manifest.target.size)) { "Not enough storage for the APK" }
        val payload = cache.temporary()
        val output = cache.temporary()
        try {
            currentCoroutineContext().ensureActive()
            progress.transferPhase(FileTransfer.Phase.MOVING)
            peer.fetch(manifest.payloadPath, payload, manifest.payload.size, progress)
            progress.transferPhase(FileTransfer.Phase.VERIFYING)
            if (manifest.base == null) return cache.publish(payload, manifest.target)
            val base = cache.verified(manifest.base) ?: error("The delta base is missing or changed")
            // Publishing the payload as an APK is forbidden; verify its own identity first.
            val patch = cache.publish(payload, manifest.payload)
            progress.transferPhase(FileTransfer.Phase.PATCHING)
            applier.apply(base, patch, output, manifest.target, progress)
            currentCoroutineContext().ensureActive()
            progress.transferPhase(FileTransfer.Phase.VERIFYING)
            return cache.publish(output, manifest.target)
        } finally {
            payload.delete()
            output.delete()
        }
    }
}

interface TransferProgress : (Long, Long) -> Unit {
    fun phase(value: FileTransfer.Phase)
}

fun ((Long, Long) -> Unit).transferPhase(value: FileTransfer.Phase) { (this as? TransferProgress)?.phase(value) }
