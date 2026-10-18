package dev.androidagent.workspace

import dev.androidagent.core.AtomicTransferFile
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Android denies app-private hard links. renameat2 preserves atomic no-replace semantics. */
class NativeAtomicPublisher {
    val available: Boolean get() = loaded

    fun commit(part: File, target: File, replace: Boolean) {
        if (replace) {
            AtomicTransferFile.commit(part, target, true)
            return
        }
        if (!loaded) throw IOException("Native atomic file publication is unavailable")
        val code = renameNoReplace(part.absolutePath.toByteArray(Charsets.UTF_8), target.absolutePath.toByteArray(Charsets.UTF_8))
        if (code == 17) throw FileAlreadyExistsException(target.absolutePath)
        if (code != 0) throw IOException("Atomic file publication failed (errno=$code): ${target.absolutePath}")
    }

    private external fun renameNoReplace(part: ByteArray, target: ByteArray): Int

    companion object {
        private val loaded = runCatching { System.loadLibrary("mike_delta") }.isSuccess
    }
}
