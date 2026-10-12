package dev.androidagent.workspace

import dev.androidagent.core.TransferBlob
import dev.androidagent.core.TransferBlobCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** App-private, shared by all chats and retained across APK updates. Never expose editable cache paths. */
class TransferBlobStore(private val root: File, private val quota: Long = 1536L * 1024 * 1024) : TransferBlobCache {
    private val mutex = Mutex()
    override suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        root.mkdirs()
        // This process's mutex is the lease: eviction is only after the operation.
        try { block() } finally { evict() }
    }

    override fun temporary(): File = File(root, ".${UUID.randomUUID()}.part")

    /** Bootstrap after the first full APK update: the app can read its own installed signed APK. */
    suspend fun seedInstalled(source: File) = withContext(Dispatchers.IO) {
        locked {
            val part = temporary()
            try {
                if (!hasSpace(source.length())) return@locked
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input -> FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                val blob = TransferBlob(digest.digest().joinToString("") { "%02x".format(it) }, part.length())
                publish(part, blob)
            } finally { part.delete() }
        }
    }
    override fun hasSpace(bytes: Long): Boolean = bytes in 0..(Long.MAX_VALUE - 16L * 1024 * 1024) && root.usableSpace > bytes + 16L * 1024 * 1024

    override suspend fun candidates(): List<TransferBlob> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) && it.isFile }
            .sortedByDescending { it.lastModified() }.filter { f ->
                f.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
            }.take(4).mapNotNull { f ->
                val blob = TransferBlob(f.name, f.length())
                if (verified(blob) != null) blob else null
            }
    }

    override suspend fun verified(blob: TransferBlob): File? = withContext(Dispatchers.IO) {
        val file = File(root, blob.sha256)
        if (!file.isFile || file.length() != blob.size || digest(file) != blob.sha256) return@withContext null
        file.setLastModified(System.currentTimeMillis())
        file
    }

    override suspend fun publish(file: File, blob: TransferBlob): File = withContext(Dispatchers.IO) {
        require(file.parentFile.canonicalFile == root.canonicalFile) { "Not a transfer temporary file" }
        check(file.length() == blob.size && digest(file) == blob.sha256) { "Transfer checksum does not match" }
        currentCoroutineContext().ensureActive()
        val final = File(root, blob.sha256)
        verified(blob)?.let { return@withContext it }
        // Atomic replacement also repairs a corrupt cache entry, without deleting it first.
        FileOutputStream(file, true).use { it.fd.sync() }
        currentCoroutineContext().ensureActive()
        Files.move(file.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        final
    }

    override suspend fun materialize(blob: File, target: File) = withContext(Dispatchers.IO) {
        target.parentFile.mkdirs()
        check(target.parentFile.usableSpace > blob.length() + 16L * 1024 * 1024) { "Not enough storage at the destination" }
        val part = File(target.parentFile, ".${UUID.randomUUID()}.part")
        try {
            blob.inputStream().use { input -> FileOutputStream(part).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            currentCoroutineContext().ensureActive()
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
        Unit
    }

    private suspend fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun evict() {
        val files = root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) && it.isFile }.sortedByDescending { it.lastModified() }
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= 2 && bytes > quota) file.delete()
        }
        // Only owned parts, and only while no operation holds this cache's lease.
        root.listFiles().orEmpty().filter { it.name.matches(Regex("\\.[a-f0-9-]{36}\\.part")) }.forEach { it.delete() }
    }
}
