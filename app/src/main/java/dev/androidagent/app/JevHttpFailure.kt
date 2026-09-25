package dev.androidagent.app

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class JevProtocolFailure(message: String) : IOException(message)

internal class JevHttpFailure(val status: Int, val retryAfterMs: Long?) : IOException("Jev request failed with HTTP $status") {
    val retryable: Boolean get() = status in setOf(408, 429, 500, 502, 503, 504, 529)
}

internal fun retryAfterMillis(value: String?): Long? {
    val raw = value?.trim() ?: return null
    raw.toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
    return runCatching {
        (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis())
            .coerceAtLeast(0L)
    }.getOrNull()
}
