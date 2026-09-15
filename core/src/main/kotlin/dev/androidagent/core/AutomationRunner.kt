package dev.androidagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime

/**
 * What a fired rule can actually do, as the host serves it.
 *
 * An interface rather than direct calls because every one of these means
 * something different on a phone and nothing at all off one: `runWorkflow` goes
 * through the composite, `agentTurn` goes through the run queue, `voiceCall`
 * raises an activity. Keeping them behind six methods is what lets
 * [AutomationRunner] — which owns the order, the approvals and the reporting —
 * be tested without any of it.
 */
interface AutomationActions {
    suspend fun runWorkflow(workflow: String, parameters: JsonObject): AutomationActionResult
    suspend fun openIntent(arguments: JsonObject): AutomationActionResult
    suspend fun notify(title: String?, text: String): AutomationActionResult
    /**
     * Queue a turn. [validUntil] is epoch millis after which the turn is the
     * wrong action rather than a late one, and the queue drops it — see
     * [AutomationGuard.validForMs].
     */
    suspend fun agentTurn(prompt: String, ruleId: String, validUntil: Long): AutomationActionResult
    suspend fun voiceCall(opening: String): AutomationActionResult

    /**
     * Ask the user a yes/no question and wait for the answer.
     *
     * Returning false must mean "no" or "nobody answered" — never "this host
     * has no way to ask". A host that cannot ask returns
     * [AutomationActionResult.unavailable] shaped refusal by overriding
     * [canAsk], because a gate that silently passes when unwired is not a gate.
     */
    suspend fun ask(question: String): Boolean

    /** False when nothing on this host can put a question to the user. */
    fun canAsk(): Boolean = true
}

data class AutomationActionResult(
    val ok: Boolean,
    val detail: String = "",
    /**
     * True when this action may already have changed something. Reported
     * verbatim; a failure after one of these is never described as undone.
     */
    val committed: Boolean = false,
) {
    companion object {
        fun ok(detail: String = "") = AutomationActionResult(true, detail)
        fun failed(detail: String, committed: Boolean = false) = AutomationActionResult(false, detail, committed)
    }
}

/** What happened when a rule ran. Rendered into a notification, a log line, or a test. */
data class AutomationRunReport(
    val ruleId: String,
    val ok: Boolean,
    val completed: List<String>,
    val stoppedAt: Int? = null,
    val errorType: String? = null,
    val message: String? = null,
    val committed: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rule", ruleId)
        put("ok", ok)
        put("completed", JsonArray(completed.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        stoppedAt?.let { put("stoppedAtAction", it) }
        errorType?.let { put("errorType", it) }
        message?.let { put("message", it) }
        if (!ok) put("earlierActionsAlreadyRan", completed.isNotEmpty())
        if (!ok && committed) put("stoppedActionMayHaveRun", true)
    }
}

/**
 * Runs the actions of a rule that has already been decided to fire.
 *
 * Deciding is [AutomationEvaluator]'s job and happens before this; by the time
 * the runner sees an [AutomationEvaluator.Outcome.Fired] the placeholders are
 * filled and the guards have passed. The split matters because the runner is
 * the only half with side effects, and it is the half a dry run must not reach.
 *
 * **The fire is recorded before the first action, not after.** This reverses
 * what the first draft of this file did, for the reason `SessionRunQueue`
 * already dequeues a turn before it starts: a crash between acting and
 * recording would let the same rule post the same thing again on the next
 * trigger, and for a standing rule a double post is worse than a missed one.
 * The cooldown exists to stop runaway repeats, so it has to be armed by the
 * attempt rather than by its success.
 *
 * **A failure never claims the earlier actions were undone.** The report names
 * the action that stopped it, everything that had already run, and whether the
 * failing one may itself have committed — the same contract `WorkflowEngine`
 * reports a failed step with, and for the same reason: nothing here can roll a
 * phone back.
 */
class AutomationRunner(
    private val actions: AutomationActions,
    private val history: AutomationHistory,
    private val now: () -> ZonedDateTime,
) {

    suspend fun run(fired: AutomationEvaluator.Outcome.Fired): AutomationRunReport {
        val rule = fired.rule

        // Armed by the attempt. See the class comment.
        history.record(rule.id, now())

        val completed = mutableListOf<String>()
        val validUntil = now().toInstant().toEpochMilli() + rule.guard.validForMs
        for ((index, action) in fired.actions.withIndex()) {
            if (action.requiresApproval || action.kind == AutomationActionKind.ASK) {
                if (!actions.canAsk()) {
                    return AutomationRunReport(
                        rule.id, ok = false, completed = completed, stoppedAt = index,
                        errorType = "confirmation_unavailable",
                        message = "Action $index needs to ask you first, and nothing on this phone " +
                            "can put the question to you right now. It did not run.",
                    )
                }
                val question = action.raw.str("question")
                    ?: "\"${rule.id}\" wants to ${action.describe()}. Go ahead?"
                if (!actions.ask(question)) {
                    return AutomationRunReport(
                        rule.id, ok = false, completed = completed, stoppedAt = index,
                        errorType = "declined",
                        message = "You said no, so action $index did not run." +
                            if (completed.isEmpty()) "" else " What had already run is listed.",
                    )
                }
                // An `ask` is the gate itself: approving it completes it.
                if (action.kind == AutomationActionKind.ASK) {
                    completed += action.describe()
                    continue
                }
            }

            val result = try {
                perform(action, rule.id, validUntil)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AutomationActionResult.failed(
                    failure.message ?: failure::class.java.simpleName,
                    committed = action.kind != AutomationActionKind.NOTIFY,
                )
            }
            if (!result.ok) {
                return AutomationRunReport(
                    rule.id, ok = false, completed = completed, stoppedAt = index,
                    errorType = "action_failed",
                    message = "Action $index (${action.kind.wire}) did not run: ${result.detail}",
                    committed = result.committed,
                )
            }
            completed += action.describe()
        }
        return AutomationRunReport(rule.id, ok = true, completed = completed)
    }

    private suspend fun perform(action: AutomationAction, ruleId: String, validUntil: Long): AutomationActionResult =
        when (action.kind) {
            AutomationActionKind.RUN_WORKFLOW -> actions.runWorkflow(
                action.raw.str("workflow").orEmpty(),
                action.raw["parameters"] as? JsonObject ?: JsonObject(emptyMap()),
            )

            // The intent goes out through the same gateway the model calls, so
            // it meets the same policy and the same approval card. A rule is
            // not a way around either.
            AutomationActionKind.OPEN_INTENT -> actions.openIntent(action.raw)

            AutomationActionKind.NOTIFY -> actions.notify(
                action.raw.str("title"),
                action.raw.str("text").orEmpty(),
            )

            AutomationActionKind.AGENT_TURN -> actions.agentTurn(
                action.raw.str("prompt").orEmpty(),
                ruleId,
                validUntil,
            )

            AutomationActionKind.VOICE_CALL -> actions.voiceCall(action.raw.str("opening").orEmpty())

            // Handled by the approval gate above; reaching here means it was approved.
            AutomationActionKind.ASK -> AutomationActionResult.ok()
        }
}
