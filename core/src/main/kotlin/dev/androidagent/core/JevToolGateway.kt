package dev.androidagent.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

data class JevProviderState(
    val enabled: Boolean = false,
    val tokenConfigured: Boolean = false,
) {
    val ready: Boolean get() = enabled && tokenConfigured
}

data class JevCandidate(val key: String, val description: String)

data class JevDecisionRequest(
    val goal: String,
    val observation: String,
    val candidates: List<JevCandidate>,
)

data class JevDecision(
    val choice: String,
    val confidence: Double,
    val probabilities: Map<String, Double>,
    val model: String,
)

/** App-owned provider boundary. Implementations keep credentials outside core. */
interface JevDecisionProvider {
    val state: StateFlow<JevProviderState>
    suspend fun choose(request: JevDecisionRequest): JevDecision
}

/**
 * Exposes Jev as a bounded, read-only decision tool.
 *
 * The gateway builds the candidate catalog from a fresh read_ui result. The
 * agent supplies only the goal; it cannot smuggle arbitrary actions or
 * coordinates into the Jev request. The selected candidate is still returned
 * as a suggestion and must be executed by the existing device gateway.
 */
class JevToolGateway(
    private val provider: JevDecisionProvider,
    private val readUi: suspend () -> ToolResult,
) : DeviceToolGateway {
    @Volatile private var revoked = true

    override val definitions: List<ToolDefinition> = listOf(TOOL_DEFINITION)

    override fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        revoked = false
    }

    override fun revoke() {
        revoked = true
    }

    override fun needsControl(name: String): Boolean = false
    override fun deviceBackendLive(): Boolean = false

    override fun readyTools(): Set<String> =
        if (provider.state.value.ready) setOf(TOOL_NAME) else emptySet()

    override fun statusLine(): String {
        val state = provider.state.value
        return when {
            !state.enabled -> "Jev: off"
            !state.tokenConfigured -> "Jev: enabled, token missing"
            else -> "Jev: ready"
        }
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (name != TOOL_NAME) throw ToolNotServiceable("jev_unsupported", "Jev does not implement \"$name\".")
        if (revoked) throw IllegalStateException("Run stopped. Jev was not called.")
        val state = provider.state.value
        if (!state.enabled) throw ToolNotServiceable("jev_disabled", "Jev is disabled in Hey Mike settings.")
        if (!state.tokenConfigured) throw ToolNotServiceable("jev_not_configured", "Jev is enabled but its API token is missing.")

        val goal = arguments.string("goal") ?: throw IllegalArgumentException("goal is required")
        require(goal.length <= MAX_GOAL_CHARS) { "goal is too long" }
        val observationResult = readUi()
        if (!observationResult.success) return observationResult
        val observed = runCatching { Json.parseToJsonElement(observationResult.text).jsonObject }
            .getOrElse { return ToolResult("The UI observation was not valid JSON. No action was performed.", success = false) }
        val observationId = observed.string("observationId")
        if (observed["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
            return escalation("The UI observation was truncated. Narrow or page read_ui before asking Jev.", observationId)
        }

        val actionMap = linkedMapOf<String, SafeAction>()
        val candidates = buildCandidates(observed, actionMap)
        if (candidates.size > MAX_ACTIONS) return escalation("Too many UI actions were visible. Narrow the screen before asking Jev.", observationId)
        if (candidates.isEmpty()) return escalation("No safe UI action was visible.", observationId)

        val decision = provider.choose(
            JevDecisionRequest(goal, observationResult.text.take(MAX_OBSERVATION_CHARS), candidates),
        )
        val chosen = actionMap[decision.choice]
            ?: return escalation("Jev returned an unknown action. No device action was performed.", observationId)
        val margin = decision.probabilities.values.sortedDescending().let { values ->
            if (values.size < 2) values.firstOrNull() ?: 0.0 else values[0] - values[1]
        }
        // Experiment mode records confidence and margin but does not turn
        // either into a policy gate yet. The next phase will measure failures
        // first, then decide which thresholds are justified.
        val canSuggestAction = chosen.executable
        return ToolResult(
            buildJsonObject {
                put("choice", decision.choice)
                put("confidence", decision.confidence)
                put("margin", margin)
                put("model", decision.model)
                put("observationId", observationId ?: "")
                put("canSuggestAction", canSuggestAction)
                chosen.tool?.let { put("tool", it) }
                chosen.arguments?.let { put("arguments", it) }
                put(
                    "safetyNote",
                    if (canSuggestAction) "Execute through the named device tool and verify fresh UI. Confidence and margin are recorded for the experiment."
                    else "This choice is not a device action. Re-read the UI or ask the user.",
                )
                put("probabilities", buildJsonObject { decision.probabilities.forEach { (key, value) -> put(key, value) } })
            }.toString(),
        )
    }

    override suspend fun cancel() {
        // Stop revokes this gateway first. The app client also has a bounded timeout.
    }

    private fun buildCandidates(observed: JsonObject, actionMap: MutableMap<String, SafeAction>): List<JevCandidate> {
        val observationId = observed.string("observationId") ?: return emptyList()
        val nodes = observed["nodes"]?.jsonArray ?: return emptyList()
        val candidates = mutableListOf<JevCandidate>()
        var index = 0
        for (element in nodes) {
            val node = element.jsonObject
            val id = node.string("nodeId") ?: continue
            val enabled = node["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            if (!enabled) continue
            val label = node.string("text") ?: node.string("contentDescription") ?: node.string("resourceId") ?: node.string("class") ?: "control"
            val clickable = node["clickable"]?.jsonPrimitive?.booleanOrNull == true || node["clickableAncestor"] != null
            if (clickable) {
                val key = "A${index.toString().padStart(3, '0')}"
                index++
                candidates += JevCandidate(key, "Tap the enabled UI control labeled ${label.take(160)}")
                actionMap[key] = SafeAction("tap_node", buildJsonObject { put("nodeId", id); put("observationId", observationId) })
            }
            if (node["scrollable"]?.jsonPrimitive?.booleanOrNull == true) {
                val key = "A${index.toString().padStart(3, '0')}"
                index++
                candidates += JevCandidate(key, "Scroll the enabled UI container labeled ${label.take(160)} downward")
                actionMap[key] = SafeAction("scroll_node", buildJsonObject {
                    put("nodeId", id); put("observationId", observationId); put("direction", "down")
                })
                if (index < MAX_ACTIONS - 3) {
                    val backwardKey = "A${index.toString().padStart(3, '0')}"
                    index++
                    candidates += JevCandidate(backwardKey, "Scroll the enabled UI container labeled ${label.take(160)} upward")
                    actionMap[backwardKey] = SafeAction("scroll_node", buildJsonObject {
                        put("nodeId", id); put("observationId", observationId); put("direction", "up")
                    })
                }
            }
            if (index >= MAX_ACTIONS - 4) break
        }
        listOf(
            SafeAction("key", buildJsonObject { put("keycode", "BACK") }) to ("BACK" to "Navigate one screen back"),
            SafeAction(null, null, false) to ("WAIT" to "Wait for the current Settings screen to settle"),
            SafeAction(null, null, false) to ("DONE" to "The visible Settings state appears to meet the goal; verification is still required"),
            SafeAction(null, null, false) to ("ESCALATE" to "No safe candidate fits; ask the user or use the normal agent path"),
        ).forEach { (action, pair) ->
            candidates += JevCandidate(pair.first, pair.second)
            actionMap[pair.first] = action
        }
        return candidates
    }

    private fun escalation(message: String, observationId: String?): ToolResult = ToolResult(
        buildJsonObject {
            put("canSuggestAction", false); put("choice", "ESCALATE"); put("message", message)
            observationId?.let { put("observationId", it) }
        }.toString(),
        success = false,
    )

    private data class SafeAction(val tool: String?, val arguments: JsonObject?, val executable: Boolean = tool != null)

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TOOL_NAME = "jev_choose_ui_action"
        const val MAX_ACTIONS = 255
        const val MAX_GOAL_CHARS = 500
        const val MAX_OBSERVATION_CHARS = 20_000

        val TOOL_DEFINITION: ToolDefinition = ToolDefinition(
            TOOL_NAME,
            "Read the current Android UI and ask Jev to suggest one bounded navigation action. " +
                "This tool does not execute actions. It supports taps, scrolling, and Back, but never text entry, shell, coordinates, permissions, or external side effects.",
            buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("goal", buildJsonObject {
                        put("type", "string"); put("minLength", 1); put("maxLength", MAX_GOAL_CHARS)
                        put("description", "The current UI navigation goal. Text entry is handled by the normal agent path.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("goal"))))
            },
        )
    }
}
