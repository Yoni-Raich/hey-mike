package dev.androidagent.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

data class JevProviderState(
    val enabled: Boolean = false,
    val tokenConfigured: Boolean = false,
) {
    val ready: Boolean get() = enabled && tokenConfigured
}

data class JevChoiceQuestion(
    val name: String,
    val instructions: String,
    val criteria: Map<String, String>,
)

data class JevDecisionRequest(
    val state: JsonObject,
    val questions: List<JevChoiceQuestion>,
)

data class JevDecision(
    val type: String?,
    val choice: String?,
    val confidence: Double?,
    val probabilities: Map<String, Double>,
)

data class JevDecisionResponse(
    val answers: Map<String, JevDecision>,
    val model: String,
)

interface JevDecisionProvider {
    val state: StateFlow<JevProviderState>
    /** Changes on a settings switch, including off-then-on during one task. */
    val availabilityEpoch: Long get() = 0L
    suspend fun choose(request: JevDecisionRequest): JevDecisionResponse
    fun beginRun() = Unit
    fun cancelActiveRequest() = Unit
}

/** A Jev opt-out is a handoff to Codex, not cancellation of the whole agent run. */
class JevDisabledException(message: String = "Jev was disabled. Continue with ordinary device tools.") : IllegalStateException(message)


internal fun validateJevChoice(answer: JevDecision?, criteria: Map<String, String>, name: String): String {
    require(answer?.type == "choice") { "Jev returned an invalid $name answer type." }
    val choice = answer.choice
    require(choice != null && choice in criteria) { "Jev returned an unknown $name choice." }
    val probabilities = answer.probabilities
    require(probabilities.keys == criteria.keys) { "Jev returned an incomplete $name distribution." }
    val values = probabilities.values
    require(answer.confidence != null && listOf(answer.confidence).plus(values).all { it.isFinite() && it in 0.0..1.0 }) {
        "Jev returned invalid $name probabilities."
    }
    require(abs(values.sum() - 1.0) <= 0.025) { "Jev returned a $name distribution that does not sum to one." }
    require(probabilities.getValue(choice) + 1e-6 >= values.maxOrNull()!!) { "Jev choice is not the maximum-probability $name answer." }
    return choice
}
