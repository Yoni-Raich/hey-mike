package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * One file moving between two places, for the progress banner. [from] and
 * [to] are the places' names as the user knows them ("Pc", "phone").
 * [total] is 0 while the size is not known yet.
 */
data class FileTransfer(
    val id: Long,
    val name: String,
    val from: String,
    val to: String,
    val bytes: Long,
    val total: Long,
    val startedAt: Long,
    val state: State = State.MOVING,
    val error: String? = null,
) {
    enum class State { MOVING, DONE, FAILED }

    /** 0..1, or null while the size is unknown. */
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null

    /** Bytes per second so far; null in the first half second, when it means nothing. */
    fun rate(now: Long): Long? {
        val elapsed = now - startedAt
        return if (elapsed < 500 || bytes <= 0) null else bytes * 1000 / elapsed
    }

    /** Seconds left at the rate so far. */
    fun secondsLeft(now: Long): Long? {
        val rate = rate(now)?.takeIf { it > 0 } ?: return null
        if (total <= 0) return null
        return ((total - bytes).coerceAtLeast(0) + rate - 1) / rate
    }
}

/**
 * The copy happening now, whichever places it joins, so one banner serves
 * every transfer. Progress is published at most every 120 ms; a finished
 * copy stays up for a moment so the user sees it land.
 */
class TransferMeter(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow<FileTransfer?>(null)
    val current: StateFlow<FileTransfer?> = mutable.asStateFlow()
    private val ids = AtomicLong()

    suspend fun <T> track(name: String, from: String, to: String, copy: suspend (progress: (Long, Long) -> Unit) -> T): T {
        val id = ids.incrementAndGet()
        val start = clock()
        var last = 0L
        mutable.value = FileTransfer(id, name, from, to, 0, 0, start)
        val progress: (Long, Long) -> Unit = { bytes, total ->
            val now = clock()
            if (now - last >= 120 || bytes >= total) {
                last = now
                mutable.value = FileTransfer(id, name, from, to, bytes, total, start)
            }
        }
        val result = try {
            copy(progress)
        } catch (error: Throwable) {
            mutable.value?.takeIf { it.id == id }?.let { failed ->
                mutable.value = failed.copy(
                    state = FileTransfer.State.FAILED,
                    error = if (error is CancellationException) "Stopped" else error.message,
                )
                clearLater(id, 4_000)
            }
            throw error
        }
        mutable.value?.takeIf { it.id == id }?.let { now ->
            mutable.value = now.copy(bytes = maxOf(now.bytes, now.total), state = FileTransfer.State.DONE)
        }
        clearLater(id, 2_500)
        return result
    }

    private fun clearLater(id: Long, afterMs: Long) {
        scope.launch {
            delay(afterMs)
            mutable.value?.takeIf { it.id == id }?.let { mutable.compareAndSet(it, null) }
        }
    }
}
