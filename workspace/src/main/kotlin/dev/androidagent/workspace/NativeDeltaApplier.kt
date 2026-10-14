package dev.androidagent.workspace

import dev.androidagent.core.DeltaApplier
import dev.androidagent.core.TransferBlob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NativeDeltaApplier : DeltaApplier {
    override val available: Boolean get() = loaded

    override suspend fun apply(base: File, patch: File, output: File, expected: TransferBlob, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(loaded) { "Native APK delta support is unavailable" }
        val control = Control(progress)
        // A native call is blocking: signal its read/write callbacks before waiting for it to finish.
        val stop = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { control.stopped.set(true) }
        }
        try {
            currentCoroutineContext().ensureActive()
            val result = patch(base.absolutePath, patch.absolutePath, output.absolutePath, base.length(), expected.size, control)
            currentCoroutineContext().ensureActive()
            check(result == 0) { "APK delta reconstruction failed ($result)" }
        } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
    }

    class Control(private val progress: (Long, Long) -> Unit) {
        val stopped = AtomicBoolean()
        fun cancelled(): Boolean = stopped.get()
        fun progress(bytes: Long, total: Long) { if (!stopped.get()) progress.invoke(bytes, total) }
    }

    private external fun patch(old: String, diff: String, output: String, oldSize: Long, newSize: Long, control: Control): Int
    companion object {
        private val loaded = runCatching { System.loadLibrary("mike_delta") }.isSuccess
    }
}
