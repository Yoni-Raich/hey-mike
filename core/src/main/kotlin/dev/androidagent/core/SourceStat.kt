/* Copyright (C) 2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/** App-owned daily counters. No notification words, titles, people or conversation names. */
@Serializable
data class SourceStat(
    val app: String,
    val day: String,
    val received: Int = 0,
    val distinct: Int = 0,
    val reviewed: Int = 0,
    val useful: Int = 0,
    val fromPeople: Int = 0,
    val lostPeople: Int = 0,
    val updatedAt: Long,
) {
    val key: String get() = "$app|$day"
    fun plus(delta: SourceStat) = copy(received = received + delta.received, distinct = distinct + delta.distinct,
        reviewed = reviewed + delta.reviewed, useful = useful + delta.useful, fromPeople = fromPeople + delta.fromPeople, lostPeople = lostPeople + delta.lostPeople,
        updatedAt = maxOf(updatedAt, delta.updatedAt))
    companion object {
        const val RETENTION_MS = 7 * 86_400_000L
        fun day(now: Long): String = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()
        fun base(app: String, now: Long) = SourceStat(app, day(now), updatedAt = now)
    }
}
