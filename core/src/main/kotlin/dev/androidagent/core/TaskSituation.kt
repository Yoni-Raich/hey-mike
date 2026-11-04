package dev.androidagent.core

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** A bounded projection of the durable board, not a location or activity sensor. */
@Serializable
data class SituationCommitment(
    val taskId: String,
    val title: String,
    val status: String,
    val source: String = "task_board",
    val observedAt: Long,
    val expiresAt: Long? = null,
    val nextCheckAt: Long? = null,
    val deadlineAt: Long? = null,
)

@Serializable
data class SituationSnapshot(
    val asOf: Long,
    val timezone: String,
    val localTime: String,
    val commitments: List<SituationCommitment>,
    val unknown: List<String>,
    val sourceGaps: List<String>,
)

object TaskSituation {
    fun snapshot(tasks: List<MikeTask>, now: Long, zone: ZoneId, calendarEnabled: Boolean,
        allowed: (MikeTask) -> Boolean): SituationSnapshot {
        val commitments = tasks.asSequence()
            .filter { it.category == TaskCategory.TASK && !it.status.closed && allowed(it) &&
                !(it.status.undecided && it.usefulUntil != null && it.usefulUntil <= now) }
            .sortedWith(compareBy<MikeTask> { minOf(it.deadlineAt ?: Long.MAX_VALUE, it.commitment?.nextCheckAt ?: Long.MAX_VALUE) }
                .thenByDescending { it.updatedAt })
            .take(10)
            .map { SituationCommitment(it.id.take(120), it.title.take(160), it.status.wire, observedAt = it.updatedAt,
                expiresAt = it.usefulUntil, nextCheckAt = it.commitment?.nextCheckAt, deadlineAt = it.deadlineAt) }.toList()
        return SituationSnapshot(now, zone.id, Instant.ofEpochMilli(now).atZone(zone).toString(), commitments,
            listOf("current_location", "travel_mode", "route", "eta", "calendar_plan"),
            listOf(if (calendarEnabled) "calendar plan is not loaded into this projection" else "calendar source is disabled",
                "no location or travel provider"))
    }
}
