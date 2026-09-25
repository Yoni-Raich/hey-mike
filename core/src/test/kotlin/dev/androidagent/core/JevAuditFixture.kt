package dev.androidagent.core

import kotlinx.serialization.json.*

/** Protocol fixture only: it scripts answers, and does not judge actual task success. */
internal fun jevAuditFixture(request: JevDecisionRequest, satisfied: Boolean = true): JevDecisionResponse {
    val latest = request.state["taskLedger"]?.jsonObject?.get("latestEvidenceId")?.jsonPrimitive?.contentOrNull
    return JevDecisionResponse(request.questions.associate { question ->
        val selected = when {
            question.name.startsWith("scope_") -> "CURRENT"
            question.name.startsWith("witness_") -> latest?.takeIf { it in question.criteria } ?: "NONE"
            question.name == "write_dependency" -> "NAVIGATE_OR_EDIT"
            else -> if (satisfied) JevTaskLedger.SATISFIED else "PENDING"
        }
        question.name to JevDecision("choice", selected, 1.0,
            question.criteria.keys.associateWith { if (it == selected) 1.0 else 0.0 })
    }, "jev-test")
}
