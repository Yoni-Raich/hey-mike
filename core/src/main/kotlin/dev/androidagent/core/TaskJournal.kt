package dev.androidagent.core

import kotlinx.serialization.Serializable

/** A small record of a card change, not a replacement for the current card. */
@Serializable
data class TaskEvent(
    val id: String,
    val taskId: String,
    val revision: Long,
    val kind: String,
    val actor: String,
    val at: Long,
    val decision: TaskDecision? = null,
    val note: String? = null,
    val reason: DismissReason? = null,
    val outcome: String? = null,
    val result: String? = null,
    val sourceRefs: List<TaskSourceRef> = emptyList(),
)

/** The input the user approved. A later card edit cannot widen this attempt. */
@Serializable
data class ApprovedTaskPlan(
    val revision: Long,
    val hash: String,
    val title: String,
    val plan: String,
    val deliverable: String? = null,
    val computer: String? = null,
    val project: String? = null,
    val note: String? = null,
    val sideEffects: List<String> = emptyList(),
    val engine: EngineKind? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
)
