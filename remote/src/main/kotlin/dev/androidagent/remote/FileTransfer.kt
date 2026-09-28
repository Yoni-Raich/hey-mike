package dev.androidagent.remote

/**
 * One file moving between a computer and this phone, for the progress banner.
 * [total] is 0 while the size is not known yet.
 */
data class FileTransfer(
    val id: Long,
    val name: String,
    val computer: String,
    val toPhone: Boolean,
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
