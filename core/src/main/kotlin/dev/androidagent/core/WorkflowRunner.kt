/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the user is being asked to allow before a sensitive step runs. */
data class WorkflowConfirmation(
    val workflowId: String,
    val stepId: String,
    val action: String,
    /** One line a person can check: what this step is about to do. */
    val summary: String,
    val packageName: String,
    val appLabel: String? = null,
)

/** The answer. Denied and unanswered are different, and so are their remedies. */
enum class WorkflowConfirmationOutcome { ALLOWED, DENIED, TIMED_OUT, UNAVAILABLE }

/**
 * Executes a [WorkflowDefinition] against the live screen.
 *
 * `run_workflow` replays tool calls. This resolves what each step *means*
 * against the screen in front of it, every time it runs:
 *
 *  1. read the screen,
 *  2. find the node the step describes, by id, label or description,
 *  3. act on that node — by handle where the backend has one, by its current
 *     centre where it does not,
 *  4. wait for the screen to settle,
 *  5. check the step's own condition before calling it done.
 *
 * Nothing positional is carried between runs, which is what makes a workflow
 * survive a moved row, a different screen size or a renamed view id. `nodeId`
 * and `observationId` are minted by step 1 and used within the same step; they
 * are never written to a definition.
 *
 * The safety rules are [WorkflowEngine]'s, extended rather than changed:
 * a committed step is never re-run because a later one failed, revoke aborts
 * between phases, and everything is bounded. A sensitive step stops and asks
 * the user first, and when nothing can ask, it does not run at all.
 */
class WorkflowRunner(
    /** Invokes one device tool. The composite, so a step reaches whichever backend serves it. */
    private val invokeTool: suspend (String, JsonObject) -> ToolResult,
    /** True once the run has been revoked; checked between every phase. */
    private val isRevoked: () -> Boolean,
    /**
     * Asks the user about a sensitive step. The default refuses: a host that
     * cannot ask must not have sensitive steps run silently on its behalf.
     */
    private val confirm: suspend (WorkflowConfirmation) -> WorkflowConfirmationOutcome =
        { WorkflowConfirmationOutcome.UNAVAILABLE },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * The allowlist for `call` steps. Unknown names fail before dispatch;
     * blocked names can never run even when registered by mistake.
     */
    private val callRegistry: WorkflowCallRegistry = WorkflowCallRegistry.EMPTY,
) {

    /** The largest box seen on the screen, so a gesture is never aimed at an assumed resolution. */
    private var viewport: List<Int>? = null

    /**
     * Where the step being run is spending its time. Reset per step.
     *
     * One `elapsedMs` per step says a step was slow; it does not say whether
     * the element took finding, the app took acting, or the screen took
     * settling - and those have different fixes. Single-run state like
     * [viewport]: the coordinator serializes tool calls, so one run holds this
     * runner at a time.
     */
    private var stepTiming = StepTiming()

    /** One run's request, after the gateway has read the tool arguments. */
    data class Options(
        val mode: String = "run",
        /** Step id, or index, to begin at. Everything before it is reported as skipped. */
        val startAt: String? = null,
        val totalBudgetMs: Long = DEFAULT_TOTAL_MS,
        /** Attach a screenshot of where it stopped. Off by default: an image is expensive. */
        val screenshotOnFailure: Boolean = false,
        /** The values the definition was bound with, handed back in `resume` so a resumed run gets the same ones. */
        val params: JsonObject = JsonObject(emptyMap()),
        /**
         * Outputs earlier `call` steps captured, handed back in `resume` so a
         * resumed run reuses them instead of re-running the committed prefix
         * that produced them.
         */
        val outputs: JsonObject = JsonObject(emptyMap()),
        /**
         * The tool this run came from when it is not a saved workflow: the
         * inline plan `act_plan` builds for one screen.
         *
         * A failure has to hand back arguments the model can actually call
         * again, and an inline plan has no name in the library to resume by.
         * With this set the resume block names that tool and its own steps
         * instead of a workflow id that does not exist.
         */
        val adHocTool: String? = null,
    )

    suspend fun run(definition: WorkflowDefinition, options: Options): ToolResult {
        val startIndex = resolveStart(definition, options.startAt)
            ?: return failure(
                definition, options, emptyList(), null, -1,
                "unknown_step",
                "\"${options.startAt}\" is not a step of \"${definition.id}\". Its steps are " +
                    definition.steps.joinToString(", ") { it.id } + ".",
                outputs = options.outputs.toMap(),
            )
        val startedAt = nowMs()
        var confirmationMs = 0L
        // Steps before startAt never re-run: their captured outputs arrive in
        // options.outputs instead, which is what makes resuming a committed
        // `call` prefix safe.
        val captured: MutableMap<String, JsonElement> = options.outputs.toMutableMap()
        val records = mutableListOf<StepRecord>()
        for (index in 0 until startIndex) {
            records += StepRecord(definition.steps[index], "skipped", "before startAt", 0L)
        }

        for (index in startIndex until definition.steps.size) {
            currentCoroutineContext().ensureActive()
            val step = definition.steps[index]
            if (isRevoked()) {
                return failure(
                    definition, options, records, step, index, "stopped",
                    "Run stopped before step \"${step.id}\". Nothing at or after it ran.",
                    outputs = captured,
                )
            }
            val elapsed = nowMs() - startedAt - confirmationMs
            if (elapsed + MIN_STEP_HEADROOM_MS >= options.totalBudgetMs) {
                return failure(
                    definition, options, records, step, index, "budget_exhausted",
                    "The ${options.totalBudgetMs}ms budget ran out before step \"${step.id}\", which did not run. " +
                        "Resume from it rather than starting again.",
                    outputs = captured,
                )
            }
            val stepStarted = nowMs()
            stepTiming = StepTiming()

            // A step whose condition already holds is not repeated. This is
            // what makes resuming safe — flipping a switch that is already on
            // turns it back off — and it runs before the confirmation, so
            // nobody is asked to allow something that has already happened.
            if (step.skipIfVerified && step.verify != null) {
                val screen = readScreen()
                if (screen.ok && verifiedOnScreen(screen, step.verify, step.target) == null) {
                    records += StepRecord(
                        step, "skipped", "already true before the step ran", nowMs() - stepStarted,
                    )
                    continue
                }
            }

            // Asking raises this app over the one being driven, so it happens
            // before anything is resolved: a node handle read beforehand would
            // be stale by the time the user answered. A call never asks on its
            // own: the called tool owns its approval, so there is one card and
            // one foregrounding path whether it is called directly or here.
            if (step.requiresConfirmation) {
                val askedAt = nowMs()
                val outcome = try {
                    confirm(confirmationFor(definition, step))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    WorkflowConfirmationOutcome.UNAVAILABLE
                }
                confirmationMs += nowMs() - askedAt
                if (outcome != WorkflowConfirmationOutcome.ALLOWED) {
                    return failure(
                        definition, options, records, step, index,
                        errorType = when (outcome) {
                            WorkflowConfirmationOutcome.DENIED -> "confirmation_denied"
                            WorkflowConfirmationOutcome.TIMED_OUT -> "confirmation_timeout"
                            else -> "confirmation_unavailable"
                        },
                        message = when (outcome) {
                            WorkflowConfirmationOutcome.DENIED ->
                                "The user did not allow step \"${step.id}\" (${describe(step)}). It did not run. " +
                                    "Do not retry it; ask what they want instead."
                            WorkflowConfirmationOutcome.TIMED_OUT ->
                                "Nobody answered the confirmation for step \"${step.id}\" (${describe(step)}), " +
                                    "so it did not run. Ask the user, then resume from that step."
                            else ->
                                "Step \"${step.id}\" (${describe(step)}) needs the user's confirmation and " +
                                    "nothing here can ask for it. It did not run. Do this step yourself, " +
                                    "with the user's agreement, then resume from the next one."
                        },
                        outputs = captured,
                    )
                }
                // The approval screen was in front. Put the app being driven
                // back before reading anything off the screen.
                returnToApp(definition, step)
            }

            val outcome = try {
                runStep(definition, step, options, captured)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                StepOutcome.Failed(
                    errorType = "step_failed",
                    message = "Step \"${step.id}\" (${describe(step)}) failed: ${error.message}",
                    committed = committedOf(step),
                )
            }
            val stepMs = nowMs() - stepStarted
            when (outcome) {
                is StepOutcome.Done ->
                    records += StepRecord(step, "done", outcome.note, stepMs, outcome.verified, stepTiming.toJson())
                is StepOutcome.Skipped -> records += StepRecord(step, "skipped", outcome.reason, stepMs)
                is StepOutcome.Failed -> return failure(
                    definition, options, records, step, index,
                    outcome.errorType, outcome.message,
                    committed = outcome.committed,
                    screen = outcome.screen,
                    outputs = captured,
                    // Where the failing step spent its time is the first
                    // question about a slow or timed-out step, and the ledger
                    // does not carry the step that did not finish.
                    timing = stepTiming.toJson(),
                )
            }
        }
        return success(definition, options, records, nowMs() - startedAt, outputs = captured)
    }

    /** Whether a failure after this step counts as possibly committed. A call follows its registry entry. */
    private fun committedOf(step: WorkflowStep): Boolean =
        if (step.action == WorkflowAction.CALL && step.callTool != null) {
            callRegistry.commits(step.callTool)
        } else {
            step.action.commits
        }

    // ---- one step ----

    private sealed interface StepOutcome {
        data class Done(val note: String?, val verified: Boolean) : StepOutcome
        data class Skipped(val reason: String) : StepOutcome
        data class Failed(
            val errorType: String,
            val message: String,
            val committed: Boolean,
            val screen: Screen? = null,
        ) : StepOutcome
    }

    private suspend fun runStep(
        definition: WorkflowDefinition,
        step: WorkflowStep,
        options: Options,
        captured: MutableMap<String, JsonElement>,
    ): StepOutcome {
        if (step.action == WorkflowAction.CALL) return runCall(step, captured)
        // A scroll that names nothing means "the list on this screen", so the
        // scrollable node is resolved like any other target rather than
        // guessed at with a swipe across assumed coordinates.
        val target = step.target
            ?: WorkflowSelector(scrollable = true).takeIf { step.action == WorkflowAction.SCROLL }
        val resolved = if (target != null) {
            val resolveStarted = nowMs()
            val found = resolve(target, step)
            stepTiming.resolveMs = nowMs() - resolveStarted
            when (found) {
                is Resolution.Found -> found
                is Resolution.Missing -> {
                    if (step.optional) {
                        return StepOutcome.Skipped("optional target not on screen (${target.describe()})")
                    }
                    // A scroll can still be attempted as a gesture: a map or
                    // a canvas has no scrollable node and never will. The
                    // swipe is measured off the screen just read, never off an
                    // assumed resolution.
                    if (step.action == WorkflowAction.SCROLL && step.target == null) {
                        return afterAction(step, invokeTool("swipe", swipeFor(direction(step), null)), null)
                    }
                    return StepOutcome.Failed(
                        errorType = if (found.screen.ok) "target_not_found" else "screen_unreadable",
                        message = if (found.screen.ok) {
                            "Step \"${step.id}\" could not find ${target.describe()} on the screen " +
                                "showing ${found.screen.activePackage ?: "an unknown app"}. Nothing was tapped."
                        } else {
                            "Step \"${step.id}\" could not read the screen: ${found.screen.failure}. " +
                                "Nothing was done."
                        },
                        committed = false,
                        screen = found.screen,
                    )
                }
            }
        } else {
            null
        }

        val actStarted = nowMs()
        val action = try {
            act(definition, step, resolved, captured)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (missing: WorkflowOutputException) {
            // An unresolvable reference fails before dispatch: nothing ran.
            return StepOutcome.Failed(
                missing.errorType,
                "Step \"${step.id}\": ${missing.message}",
                committed = false,
            )
        } catch (error: Exception) {
            return StepOutcome.Failed(
                "step_failed",
                "Step \"${step.id}\" (${describe(step)}) failed: ${error.message}",
                committed = committedOf(step),
            )
        } finally {
            stepTiming.actMs = nowMs() - actStarted
        }
        return afterAction(step, action, resolved)
    }

    /**
     * Invoke one registered device tool and optionally capture its result for
     * later steps.
     *
     * The registry is consulted before substitution and dispatch, so an
     * unknown name fails with nothing done. Commit reporting follows the
     * resolved arguments, so a read-only operation of a mixed tool is not
     * reported as possibly committed. A result larger than the capture
     * cap fails instead of being stored: silently truncating JSON would hand
     * later steps data that parses into something else.
     */
    private suspend fun runCall(
        step: WorkflowStep,
        captured: MutableMap<String, JsonElement>,
    ): StepOutcome {
        val name = step.callTool ?: return StepOutcome.Failed(
            "step_failed",
            "Step \"${step.id}\" names no tool to call.",
            committed = false,
        )
        if (name in WorkflowCallRegistry.BLOCKED_CALL_TOOLS) {
            return StepOutcome.Failed(
                "tool_not_allowed",
                "Step \"${step.id}\": \"$name\" cannot run inside a workflow.",
                committed = false,
            )
        }
        val metadata = callRegistry.resolve(name) ?: return StepOutcome.Failed(
            "unknown_tool",
            "Step \"${step.id}\": \"$name\" is not a workflow-callable tool. " +
                if (callRegistry.names.isEmpty()) "No tool is registered for workflow calls."
                else "Registered tools: " + callRegistry.names.sorted().joinToString(", ") + ".",
            committed = false,
        )
        val args = try {
            WorkflowOutputRefs.resolve(step.arguments, captured) as? JsonObject ?: step.arguments
        } catch (missing: WorkflowOutputException) {
            return StepOutcome.Failed(
                missing.errorType,
                "Step \"${step.id}\": ${missing.message}",
                committed = false,
            )
        }
        // Classified from the exact resolved arguments: a read-only operation
        // of a mixed tool reports nothing committed, while a missing or
        // unknown operation stays conservative.
        val mayCommit = metadata.mayCommit(args)
        if (isRevoked()) {
            return StepOutcome.Failed(
                "stopped",
                "Run stopped before step \"${step.id}\". It did not run.",
                committed = false,
            )
        }
        val callStarted = nowMs()
        val result = try {
            invokeTool(name, args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return StepOutcome.Failed(
                "step_failed",
                "Step \"${step.id}\" (${describe(step)}) failed: ${error.message}",
                committed = mayCommit,
            )
        } finally {
            stepTiming.actMs = nowMs() - callStarted
        }
        if (!result.success) {
            return afterAction(step, result, resolved = null, committed = mayCommit)
        }
        var note: String? = null
        step.output?.let { binding ->
            if (binding !in captured && captured.size >= WorkflowCallRegistry.MAX_OUTPUT_BINDINGS) {
                return StepOutcome.Failed(
                    "too_many_outputs",
                    "Step \"${step.id}\": the run already holds ${captured.size} captured outputs; " +
                        "the limit is ${WorkflowCallRegistry.MAX_OUTPUT_BINDINGS}.",
                    committed = mayCommit,
                )
            }
            // Parsed before any size check, so the total below measures the
            // value as it would be stored and re-serialized, not the raw text.
            val value = runCatching { Json.parseToJsonElement(result.text) }
                .getOrDefault(JsonPrimitive(result.text))
            if (value.toString().length > WorkflowCallRegistry.MAX_CAPTURED_OUTPUT_CHARS) {
                return StepOutcome.Failed(
                    "output_too_large",
                    "Step \"${step.id}\": the result is ${value.toString().length} characters, over the " +
                        "${WorkflowCallRegistry.MAX_CAPTURED_OUTPUT_CHARS} limit, so it was not captured. " +
                        "Narrow the call instead.",
                    committed = mayCommit,
                )
            }
            if (JsonObject(captured + (binding to value)).toString().length >
                WorkflowCallRegistry.MAX_TOTAL_OUTPUT_CHARS
            ) {
                return StepOutcome.Failed(
                    "output_too_large",
                    "Step \"${step.id}\": the outputs together pass the " +
                        "${WorkflowCallRegistry.MAX_TOTAL_OUTPUT_CHARS} character limit, so " +
                        "\"$binding\" was not captured. Resume from the next step without it, " +
                        "or narrow the calls.",
                    committed = mayCommit,
                )
            }
            captured[binding] = value
            note = "captured output \"$binding\""
        }
        val checked = afterAction(step, result, resolved = null, committed = mayCommit)
        if (checked is StepOutcome.Done && note != null && checked.note == null) return checked.copy(note = note)
        return checked
    }

    /** Settle, then hold the step to its own condition. Shared by every action path. */
    private suspend fun afterAction(
        step: WorkflowStep,
        action: ToolResult,
        resolved: Resolution.Found?,
        committed: Boolean = step.action.commits,
    ): StepOutcome {
        if (!action.success) {
            return StepOutcome.Failed(
                "step_failed",
                "Step \"${step.id}\" (${describe(step)}) did not take: ${action.text.take(MAX_STEP_TEXT)}",
                // Dispatched and refused. The tools report refusal without
                // acting, but a tap that landed somewhere useless reports the
                // same way, so a committing step is still flagged.
                committed = committed,
            )
        }

        if (step.waitForChange) {
            val settleStarted = nowMs()
            settle(step)
            stepTiming.settleMs = nowMs() - settleStarted
        }

        val verification = step.verify ?: return StepOutcome.Done(null, verified = false)
        val verifyStarted = nowMs()
        val deadline = verifyStarted + verification.timeoutMs
        var complaint: String? = "the screen could not be read"
        var lastScreen: Screen? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isRevoked()) {
                return StepOutcome.Failed(
                    "stopped",
                    "Run stopped while checking step \"${step.id}\". The step itself had already run.",
                    committed = committed,
                )
            }
            val screen = readScreen()
            lastScreen = screen
            if (screen.ok) {
                complaint = verifiedOnScreen(screen, verification, step.target)
                if (complaint == null) {
                    stepTiming.verifyMs = nowMs() - verifyStarted
                    return StepOutcome.Done(null, verified = true)
                }
            }
            if (nowMs() >= deadline) break
            delay(VERIFY_POLL_MS.coerceAtMost((deadline - nowMs()).coerceAtLeast(1L)))
        }
        stepTiming.verifyMs = nowMs() - verifyStarted
        return StepOutcome.Failed(
            errorType = "verification_failed",
            // What the action itself reported is the first thing anyone fixing
            // the definition needs: "Tapped n213" and "did not accept a click"
            // are different failures with the same symptom.
            message = "Step \"${step.id}\" (${describe(step)}) ran, but ${verification.describe()} " +
                "did not become true within ${verification.timeoutMs}ms: $complaint. " +
                "The action reported: ${action.text.take(MAX_STEP_TEXT)}" +
                (resolved?.let { " (target ${it.nodeId} in ${it.observationId})" } ?: ""),
            committed = committed,
            screen = lastScreen,
        )
    }

    /** Dispatch the step's action, preferring a node handle and falling back to its current centre. */
    private suspend fun act(
        definition: WorkflowDefinition,
        step: WorkflowStep,
        resolved: Resolution.Found?,
        captured: Map<String, JsonElement>,
    ): ToolResult = when (step.action) {
        WorkflowAction.OPEN_APP -> invokeTool(
            "open_app",
            buildJsonObject {
                put("package", step.arguments.str("package") ?: definition.packageName)
                step.arguments.str("activity")?.let { put("activity", it) }
            },
        )

        // Through the tool, not around it: IntentPolicy and its approval card
        // judge a workflow's intent exactly as they judge the model's.
        // Captured outputs resolve here too, so a call's result can address
        // the intent that follows it.
        WorkflowAction.OPEN_INTENT -> invokeTool(
            "open_intent",
            WorkflowOutputRefs.resolve(step.arguments, captured) as? JsonObject ?: step.arguments,
        )

        WorkflowAction.TAP -> {
            val node = requireNotNull(resolved) { "tap needs a target" }
            val byHandle = attempt("tap_node", node.handleArguments())
            byHandle ?: invokeTool("tap", node.centreArguments())
        }

        WorkflowAction.TYPE_TEXT -> {
            val template = requireNotNull(step.text) { "type_text needs text" }
            // A captured output may supply what is typed, e.g. a code an
            // earlier call read. A missing reference throws before dispatch.
            val text = WorkflowOutputRefs.resolveText(template, captured)
            if (resolved == null) {
                invokeTool("type_text", buildJsonObject { put("text", text); put("mode", "replace"); if (step.submit) put("submit", true) })
            } else {
                val byHandle = attempt(
                    "set_text",
                    buildJsonObject {
                        put("nodeId", resolved.nodeId)
                        put("observationId", resolved.observationId)
                        put("text", text)
                        if (step.submit) put("submit", true)
                    },
                )
                // No node addressing on this backend: focus the field the way
                // a person would, then type into whatever holds focus.
                byHandle ?: run {
                    val focused = invokeTool("tap", resolved.centreArguments())
                    if (!focused.success) focused
                    else invokeTool(
                        "type_text",
                        buildJsonObject { put("text", text); put("mode", "replace"); if (step.submit) put("submit", true) },
                    )
                }
            }
        }

        WorkflowAction.SCROLL -> {
            val direction = direction(step)
            val byHandle = resolved?.let {
                attempt(
                    "scroll_node",
                    buildJsonObject {
                        put("nodeId", it.nodeId)
                        put("observationId", it.observationId)
                        put("direction", direction)
                    },
                )
            }
            byHandle ?: invokeTool("swipe", swipeFor(direction, resolved))
        }

        WorkflowAction.KEY -> invokeTool(
            "key",
            buildJsonObject { put("keycode", step.arguments.str("keycode") ?: "BACK") },
        )

        WorkflowAction.WAIT -> {
            val timeout = step.timeoutMs ?: DEFAULT_WAIT_MS
            attempt("wait_for_change", buildJsonObject { put("timeoutMs", timeout) })
                ?: run { delay(timeout); ToolResult("Waited ${timeout}ms") }
        }

        WorkflowAction.OBSERVE -> {
            val screen = readScreen()
            ToolResult(
                "Read ${screen.nodes.size} nodes from ${screen.activePackage ?: "an unknown app"}",
                success = screen.ok,
            )
        }

        // Calls run through runCall, never through act: the registry check,
        // the output capture and the per-tool committed flag live there.
        WorkflowAction.CALL -> error("call steps run through runCall")
    }

    /**
     * Call a tool that only some backends serve, returning null when none does.
     *
     * Null means nothing was dispatched, so the caller may fall back. A tool
     * that ran and failed is returned as itself: retrying that on another path
     * could double a tap.
     */
    private suspend fun attempt(name: String, arguments: JsonObject): ToolResult? = try {
        val result = invokeTool(name, arguments)
        // The composite answers an unroutable name with a failure rather than
        // an exception, and the fallback must still be reachable.
        if (!result.success && looksUnserviceable(result.text)) null else result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (absent: ToolNotServiceable) {
        null
    }

    /**
     * True when the reply says no backend dispatched the call.
     *
     * Read from the typed `errorType` rather than the prose: "the switch
     * refused the text" and "no backend serves set_text" both come back as a
     * failed [ToolResult], and only the second one may be retried another way.
     */
    private fun looksUnserviceable(text: String): Boolean {
        if (text.startsWith(UNKNOWN_TOOL)) return true
        val errorType = runCatching { Json.parseToJsonElement(text).jsonObject.str("errorType") }.getOrNull()
        return errorType in NOT_DISPATCHED
    }

    private suspend fun settle(step: WorkflowStep) {
        val timeout = step.timeoutMs ?: SETTLE_MS
        val waited = attempt("wait_for_change", buildJsonObject { put("timeoutMs", timeout) })
        // Nothing here serves wait_for_change, so give the screen the same
        // grace by hand rather than reading it mid-transition.
        if (waited == null) delay(SETTLE_FALLBACK_MS)
    }

    /** After an approval the app being driven is behind Hey Mike. Put it back in front. */
    private suspend fun returnToApp(definition: WorkflowDefinition, step: WorkflowStep) {
        val expected = step.arguments.str("package") ?: definition.packageName
        // The host steps the approval screen back, which uncovers the app on
        // the sub-screen the workflow reached. Give that transition time before
        // relaunching: a launcher intent resets a single-task app like Settings
        // to its home page, and the step then looks for its target on the
        // wrong screen.
        val deadline = nowMs() + RETURN_WAIT_MS
        while (true) {
            val screen = readScreen()
            if (screen.ok && screen.activePackage?.contains(expected, ignoreCase = true) == true) return
            if (nowMs() >= deadline) break
            delay(VERIFY_POLL_MS)
        }
        runCatching { invokeTool("open_app", buildJsonObject { put("package", expected) }) }
        settle(step)
    }

    // ---- resolving a selector against the live screen ----

    private sealed interface Resolution {
        data class Found(
            val nodeId: String,
            val observationId: String,
            val node: ScreenNode,
            val score: Int,
        ) : Resolution {
            /**
             * The handle to click. A label that is not itself clickable hands
             * over its clickable row: on a phone, clicking the "Wireless
             * debugging" title in Settings search fell back to a gesture that
             * landed while the result list was being rebuilt and did nothing,
             * while an accessibility click on the row opened it.
             */
            fun handleArguments(): JsonObject = buildJsonObject {
                put("nodeId", if (!node.clickable && node.ancestorNodeId != null) node.ancestorNodeId else nodeId)
                put("observationId", observationId)
            }

            /**
             * The node's centre *as it is right now*, never a stored coordinate.
             * A label that is not itself clickable hands over its clickable
             * ancestor's box, which is the one a finger would hit.
             */
            fun centreArguments(): JsonObject {
                val box = if (!node.clickable && node.ancestorBounds != null) node.ancestorBounds else node.bounds
                val bounds = box ?: error("$nodeId has no bounds to tap")
                return buildJsonObject {
                    put("x", (bounds[0] + bounds[2]) / 2)
                    put("y", (bounds[1] + bounds[3]) / 2)
                }
            }
        }

        data class Missing(val screen: Screen) : Resolution
    }

    private suspend fun resolve(selector: WorkflowSelector, step: WorkflowStep): Resolution {
        var screen = readScreen()
        pick(screen, selector)?.let { return it }
        if (!screen.ok) return Resolution.Missing(screen)

        // The node may simply not be listed yet: a screen bigger than one reply
        // is paged, and a filtered read looks past the page boundary. The
        // filtered reply is used only to pick from: it lists the matches and
        // not the list around them, so the scroll hunt below keeps the full read.
        if (screen.truncated) {
            for (query in narrowQueries(selector)) {
                currentCoroutineContext().ensureActive()
                val narrowed = readScreen(query)
                if (narrowed.ok) pick(narrowed, selector)?.let { return it }
            }
        }

        if (selector.scrollIntoView) {
            repeat(MAX_SCROLL_HUNT) {
                currentCoroutineContext().ensureActive()
                if (isRevoked()) return Resolution.Missing(screen)
                val scrollable = screen.nodes.firstOrNull { it.scrollable } ?: return Resolution.Missing(screen)
                val observationId = screen.observationId
                val scrolled = observationId?.let {
                    attempt(
                        "scroll_node",
                        buildJsonObject {
                            put("nodeId", scrollable.nodeId)
                            put("observationId", it)
                            put("direction", direction(step))
                        },
                    )
                } ?: invokeTool("swipe", swipeFor("forward", null))
                if (!scrolled.success) return Resolution.Missing(screen)
                settle(step)
                screen = readScreen()
                pick(screen, selector)?.let { return it }
            }
        }
        return Resolution.Missing(screen)
    }

    private fun pick(screen: Screen, selector: WorkflowSelector): Resolution.Found? {
        val observationId = screen.observationId ?: return null
        val ranked = screen.nodes
            .mapNotNull { node -> score(node, selector)?.let { node to it } }
            .sortedByDescending { it.second }
        val chosen = ranked.getOrNull(selector.index) ?: return null
        return Resolution.Found(chosen.first.nodeId, observationId, chosen.first, chosen.second)
    }

    /**
     * How well one node answers a selector, or null when it does not.
     *
     * Criteria are scored rather than ANDed on purpose. A definition that names
     * both the view id and the visible label still resolves after the app
     * renames the id, because the label alone identifies the node — which is
     * the difference between a workflow that survives an app update and a macro
     * that does not.
     */
    private fun score(node: ScreenNode, selector: WorkflowSelector): Int? {
        if (selector.packageName != null &&
            node.packageName?.contains(selector.packageName, ignoreCase = true) != true
        ) {
            return null
        }
        if (selector.clickable && !node.clickable && node.ancestorBounds == null) return null
        if (selector.scrollable && !node.scrollable) return null

        var score = 0
        var matched = 0
        var named = 0
        // Of the fields that identify one element — id, label, description —
        // how many the selector names and how many this node answers.
        var identifyingNamed = 0
        var identifyingMatched = 0

        selector.resourceId?.let { wanted ->
            named++
            identifyingNamed++
            val actual = node.resourceId
            val points = when {
                actual == null -> 0
                actual.equals(wanted, ignoreCase = true) -> 100
                // `switchWidget` for `com.android.settings:id/switchWidget`:
                // the local half of an id is what a definition usually names.
                actual.substringAfterLast('/').equals(wanted.substringAfterLast('/'), ignoreCase = true) -> 90
                // AOSP spells the same widget `switch_widget` in one layout and
                // `switchWidget` in another.
                localIdKey(actual) == localIdKey(wanted) -> 85
                !selector.exact && actual.contains(wanted, ignoreCase = true) -> 60
                else -> 0
            }
            if (points > 0) { matched++; identifyingMatched++ }
            score += points
        }
        selector.text?.let { wanted ->
            named++
            identifyingNamed++
            val points = maxOf(textScore(node.text, wanted, selector.exact), textScore(node.contentDescription, wanted, selector.exact))
            if (points > 0) { matched++; identifyingMatched++ }
            score += points
        }
        selector.contentDescription?.let { wanted ->
            named++
            identifyingNamed++
            val points = textScore(node.contentDescription, wanted, selector.exact)
            if (points > 0) { matched++; identifyingMatched++ }
            score += points
        }
        selector.className?.let { wanted ->
            named++
            val actual = node.className
            val points = when {
                actual == null -> 0
                // Worth enough to put the Switch ahead of the row title that
                // carries the same label; it can no longer match on its own.
                actual.equals(wanted, ignoreCase = true) -> 50
                !selector.exact && actual.contains(wanted, ignoreCase = true) -> 40
                else -> 0
            }
            if (points > 0) matched++
            score += points
        }
        // An `exact` selector is a promise that every field it names is the
        // node's own value, so one miss disqualifies it.
        if (selector.exact && matched < named) return null
        if (named > 0 && matched == 0) return null
        // A class alone describes a kind of element, not one element. When the
        // selector says which one it means, a node that answers only the class
        // is some other element of that kind: every Switch on a Developer
        // options screen is a `Switch`, and tapping the first one flips a
        // setting nobody asked about.
        if (identifyingNamed > 0 && identifyingMatched == 0) return null
        if (named == 0 && !selector.scrollable) return null

        if (node.clickable) score += 5
        if (node.enabled) score += 3
        return score
    }

    private fun localIdKey(id: String): String =
        id.substringAfterLast('/').replace("_", "").lowercase()

    private fun textScore(actual: String?, wanted: String, exact: Boolean): Int = when {
        actual == null -> 0
        actual.equals(wanted, ignoreCase = true) -> 80
        exact -> 0
        actual.contains(wanted, ignoreCase = true) -> 45
        // A label the app shortened ("Wireless debug") still names the same
        // row when the definition's text contains it — but only when it keeps
        // most of that text. "Off" is inside "Turn off now" and names nothing.
        wanted.contains(actual, ignoreCase = true) &&
            actual.length >= MIN_REVERSE_MATCH_CHARS &&
            actual.length * 2 >= wanted.length -> 25
        else -> 0
    }

    /** One targeted read per criterion, for a screen too big to arrive in one reply. */
    private fun narrowQueries(selector: WorkflowSelector): List<JsonObject> = buildList {
        selector.resourceId?.let { add(buildJsonObject { put("resourceId", it) }) }
        selector.text?.let { add(buildJsonObject { put("text", it) }) }
        selector.contentDescription?.let { add(buildJsonObject { put("text", it) }) }
        if (isEmpty()) selector.className?.let { add(buildJsonObject { put("class", it) }) }
    }

    private fun direction(step: WorkflowStep): String = step.arguments.str("direction") ?: "forward"

    private fun swipeFor(direction: String, resolved: Resolution.Found?): JsonObject {
        // Measured off the box in front of us: the target's own bounds, or the
        // largest box on the screen just read. The constant is a last resort
        // for a screen that reported no bounds at all.
        val bounds = resolved?.node?.bounds ?: viewport ?: DEFAULT_SWIPE_BOUNDS
        val left = bounds[0]
        val top = bounds[1]
        val width = (bounds[2] - bounds[0]).coerceAtLeast(1)
        val height = (bounds[3] - bounds[1]).coerceAtLeast(1)
        fun x(fraction: Double) = left + (width * fraction).toInt()
        fun y(fraction: Double) = top + (height * fraction).toInt()
        return buildJsonObject {
            when (direction.lowercase()) {
                "backward", "up" -> {
                    put("x1", x(0.5)); put("y1", y(0.3)); put("x2", x(0.5)); put("y2", y(0.7))
                }
                "left" -> {
                    put("x1", x(0.2)); put("y1", y(0.5)); put("x2", x(0.8)); put("y2", y(0.5))
                }
                "right" -> {
                    put("x1", x(0.8)); put("y1", y(0.5)); put("x2", x(0.2)); put("y2", y(0.5))
                }
                else -> {
                    put("x1", x(0.5)); put("y1", y(0.7)); put("x2", x(0.5)); put("y2", y(0.3))
                }
            }
            put("durationMs", SWIPE_MS)
        }
    }

    // ---- verification ----

    /**
     * [verified], looking past the page boundary when the screen was paged.
     *
     * A condition about one element must not fail because that element was on
     * page two: each selector the condition names gets its own filtered read,
     * and the condition is judged against everything those reads found.
     */
    private suspend fun verifiedOnScreen(
        screen: Screen,
        verification: WorkflowVerification,
        target: WorkflowSelector?,
    ): String? {
        val complaint = verified(screen, verification, target) ?: return null
        if (!screen.truncated) return complaint
        val selectors = listOfNotNull(
            verification.present,
            verification.absent,
            (verification.checkedOf ?: target).takeIf { verification.checked != null },
        )
        if (selectors.isEmpty()) return complaint
        val extra = mutableListOf<ScreenNode>()
        for (query in selectors.flatMap(::narrowQueries)) {
            currentCoroutineContext().ensureActive()
            val narrowed = readScreenOnce(query)
            if (narrowed.ok) extra += narrowed.nodes
        }
        if (extra.isEmpty()) return complaint
        return verified(screen.copy(nodes = screen.nodes + extra), verification, target)
    }

    /** Null when the condition holds, otherwise the reason it does not. */
    private fun verified(
        screen: Screen,
        verification: WorkflowVerification,
        target: WorkflowSelector?,
    ): String? {
        verification.packageName?.let { wanted ->
            val actual = screen.activePackage
            if (actual?.contains(wanted, ignoreCase = true) != true) {
                return "the app in front is ${actual ?: "unknown"}, not $wanted"
            }
        }
        verification.present?.let { selector ->
            if (screen.nodes.none { score(it, selector) != null }) {
                return "${selector.describe()} is not on screen"
            }
        }
        verification.absent?.let { selector ->
            if (screen.nodes.any { score(it, selector) != null }) {
                return "${selector.describe()} is still on screen"
            }
        }
        verification.checked?.let { wanted ->
            val selector = verification.checkedOf ?: target
                ?: return "no element was named to check the on/off state of"
            val candidates = screen.nodes
                .mapNotNull { node -> score(node, selector)?.let { node to it } }
                .sortedByDescending { it.second }
            if (candidates.isEmpty()) return "${selector.describe()} is not on screen"
            // The labelled row and the switch inside it both match; only one of
            // them reports a state, so prefer it over the higher-scoring label.
            val stateful = candidates.firstOrNull { it.first.checkable }
                ?: return "${selector.describe()} matched, but nothing there reports an on/off state"
            if (stateful.first.checked != wanted) {
                return "it is ${if (stateful.first.checked) "on" else "off"}, not ${if (wanted) "on" else "off"}"
            }
        }
        return null
    }

    // ---- reading the screen ----

    /**
     * What one `read_ui` reply says, parsed for the runner alone.
     *
     * None of this reaches the model: the point of the runner is that a whole
     * sequence costs one turn, so an observation is read, used and dropped.
     */
    private data class Screen(
        val ok: Boolean,
        val observationId: String?,
        val activePackage: String?,
        val nodes: List<ScreenNode>,
        val truncated: Boolean = false,
        val failure: String? = null,
    )

    private data class ScreenNode(
        val nodeId: String,
        val text: String?,
        val contentDescription: String?,
        val resourceId: String?,
        val className: String?,
        val packageName: String?,
        val bounds: List<Int>?,
        val enabled: Boolean,
        val clickable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val ancestorBounds: List<Int>?,
        val ancestorNodeId: String? = null,
    )

    private suspend fun readScreen(query: JsonObject = JsonObject(emptyMap())): Screen {
        val screen = readScreenOnce(query)
        // An unfiltered page is filled in window order, and the status bar
        // comes first: on a real phone the first page of Settings search was
        // 81 systemui nodes and not one node of the app in front. Read again
        // scoped to that app, so a step is resolved and checked against the
        // screen it is about.
        val active = screen.activePackage
        if (!screen.ok || !screen.truncated || query.isNotEmpty() || active == null) return screen
        val scoped = readScreenOnce(buildJsonObject { put("package", active) })
        return if (scoped.ok) scoped else screen
    }

    private suspend fun readScreenOnce(query: JsonObject): Screen {
        val arguments = buildJsonObject {
            for ((key, value) in query) put(key, value)
            // Always a full reply: unchanged-suppression saves the model
            // characters, and the runner has no earlier node list to reuse.
            put("force", true)
        }
        val result = try {
            invokeTool("read_ui", arguments)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Screen(false, null, null, emptyList(), failure = error.message ?: "read_ui failed")
        }
        val json = runCatching { Json.parseToJsonElement(result.text).jsonObject }.getOrNull()
            ?: return Screen(false, null, null, emptyList(), failure = result.text.take(MAX_STEP_TEXT))
        if (json["ok"]?.jsonPrimitive?.booleanOrNull == false || !result.success) {
            return Screen(
                ok = false,
                observationId = json.str("observationId"),
                activePackage = json.str("activePackage"),
                nodes = emptyList(),
                failure = json.str("message") ?: json.str("errorType") ?: result.text.take(MAX_STEP_TEXT),
            )
        }
        val nodes = (json["nodes"] as? JsonArray).orEmpty().mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val id = node.str("nodeId") ?: return@mapNotNull null
            ScreenNode(
                nodeId = id,
                text = node.str("text"),
                contentDescription = node.str("contentDescription"),
                resourceId = node.str("resourceId"),
                className = node.str("class"),
                packageName = node.str("package") ?: json.str("activePackage"),
                bounds = node.bounds("bounds"),
                enabled = node.bool("enabled") ?: true,
                clickable = node.bool("clickable") ?: false,
                scrollable = node.bool("scrollable") ?: false,
                checkable = node.bool("checkable") ?: false,
                checked = node.bool("checked") ?: false,
                ancestorBounds = (node["clickableAncestor"] as? JsonObject)?.bounds("bounds"),
                ancestorNodeId = (node["clickableAncestor"] as? JsonObject)?.str("nodeId"),
            )
        }
        // What a gesture has to aim inside, taken from the screen rather than
        // assumed: a swipe computed for a 1080x2400 phone misses on any other.
        nodes.mapNotNull { it.bounds }
            .maxByOrNull { (it[2] - it[0]).toLong() * (it[3] - it[1]).toLong() }
            ?.let { viewport = it }
        return Screen(
            ok = true,
            observationId = json.str("observationId"),
            activePackage = json.str("activePackage"),
            nodes = nodes,
            truncated = json["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun JsonObject.bounds(key: String): List<Int>? =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.takeIf { it.size == 4 }

    // ---- reporting ----

    private data class StepRecord(
        val step: WorkflowStep,
        val status: String,
        val note: String?,
        val elapsedMs: Long,
        val verified: Boolean = false,
        /** Where that time went, when any phase was slow enough to measure. */
        val timing: JsonObject? = null,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", step.id)
            put("action", step.action.wire)
            put("status", status)
            if (status == "done") put("verified", verified)
            note?.let { put("note", it) }
            if (elapsedMs > 0) put("elapsedMs", elapsedMs)
            timing?.let { put("timing", it) }
        }
    }

    /**
     * One step's time, split by phase.
     *
     * "The step took 14 seconds" and "finding the element took 12 of them" ask
     * for different fixes: a better selector, a slower app, or a screen that
     * never settles. Phases below [MIN_REPORTED_PHASE_MS] are left out, so a
     * fast step still reports as one line.
     */
    private class StepTiming(
        /** Reading the screen and finding the node, including re-reads and any scroll hunt. */
        var resolveMs: Long = 0,
        /** The device call itself. */
        var actMs: Long = 0,
        /** Waiting for the screen to stop moving afterwards. */
        var settleMs: Long = 0,
        /** Polling until the step's own condition held, or until it timed out. */
        var verifyMs: Long = 0,
    ) {
        fun toJson(): JsonObject? {
            val parts = listOf("resolve" to resolveMs, "act" to actMs, "settle" to settleMs, "verify" to verifyMs)
                .filter { it.second >= MIN_REPORTED_PHASE_MS }
            if (parts.isEmpty()) return null
            return buildJsonObject { parts.forEach { (name, value) -> put(name, value) } }
        }
    }

    private fun confirmationFor(definition: WorkflowDefinition, step: WorkflowStep) = WorkflowConfirmation(
        workflowId = definition.id,
        stepId = step.id,
        action = step.action.wire,
        summary = describe(step),
        packageName = step.arguments.str("package") ?: definition.packageName,
    )

    /** One line naming what a step does, for an approval card and for a failure. */
    private fun describe(step: WorkflowStep): String = when (step.action) {
        WorkflowAction.OPEN_APP -> "open ${step.arguments.str("package") ?: "the app"}"
        WorkflowAction.OPEN_INTENT -> buildList {
            add("open ${step.arguments.str("action") ?: step.arguments.str("uri") ?: "an intent"}")
            step.arguments.str("uri")?.takeIf { step.arguments.str("action") != null }?.let { add(it.take(MAX_SUMMARY_TEXT)) }
            (step.arguments["extras"] as? JsonObject)?.let { extras ->
                (IntentExtras.parse(extras) as? IntentExtras.Parsed.Ok)?.extras?.takeIf { it.isNotEmpty() }
                    ?.let { add("with ${IntentExtras.describe(it)}") }
            }
        }.joinToString(" ")
        WorkflowAction.TAP -> "tap ${step.target?.describe() ?: "the target"}"
        WorkflowAction.TYPE_TEXT -> "type \"${step.text?.take(MAX_SUMMARY_TEXT).orEmpty()}\"" +
            if (step.submit) " and submit it" else ""
        WorkflowAction.SCROLL -> "scroll ${direction(step)}"
        WorkflowAction.KEY -> "press ${step.arguments.str("keycode") ?: "a key"}"
        WorkflowAction.WAIT -> "wait for the screen to settle"
        WorkflowAction.OBSERVE -> "read the screen"
        WorkflowAction.CALL -> buildString {
            append("call ${step.callTool ?: "a tool"}")
            // The arguments are what the call will do: an approval card or a
            // failure must show them, not just the tool name.
            if (step.arguments.isNotEmpty()) append(" ${step.arguments.toString().take(MAX_SUMMARY_TEXT)}")
            step.output?.let { append(" as $it") }
        }
    }

    private fun resolveStart(definition: WorkflowDefinition, startAt: String?): Int? {
        if (startAt.isNullOrBlank()) return 0
        definition.stepIndex(startAt.trim())?.let { return it }
        val index = startAt.trim().toIntOrNull() ?: return null
        return index.takeIf { it in definition.steps.indices }
    }

    private fun success(
        definition: WorkflowDefinition,
        options: Options,
        records: List<StepRecord>,
        elapsedMs: Long,
        outputs: Map<String, JsonElement> = emptyMap(),
    ): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("workflow", definition.id)
            put("package", definition.packageName)
            put("mode", options.mode)
            put("steps", JsonArray(records.map { it.toJson() }))
            put("ranSteps", records.count { it.status == "done" })
            put("skippedSteps", records.count { it.status == "skipped" })
            put("elapsedMs", elapsedMs)
            if (outputs.isNotEmpty()) put("outputs", JsonObject(outputs))
            put(
                "note",
                "Every step ran and every condition that was declared held. Nothing here needs to be repeated.",
            )
        }.toString(),
    )

    private suspend fun failure(
        definition: WorkflowDefinition,
        options: Options,
        records: List<StepRecord>,
        step: WorkflowStep?,
        index: Int,
        errorType: String,
        message: String,
        committed: Boolean = false,
        screen: Screen? = null,
        outputs: Map<String, JsonElement> = emptyMap(),
        timing: JsonObject? = null,
    ): ToolResult {
        val shot = if (options.screenshotOnFailure) {
            runCatching { invokeTool("screenshot", JsonObject(emptyMap())) }.getOrNull()?.takeIf { it.success }
        } else {
            null
        }
        return ToolResult(
            buildJsonObject {
                put("ok", false)
                put("workflow", definition.id)
                put("package", definition.packageName)
                put("mode", options.mode)
                put("errorType", errorType)
                put("message", message)
                step?.let {
                    put("failedStep", it.id)
                    put("failedStepIndex", index)
                    put("failedStepDoes", describe(it))
                    timing?.let { spent -> put("failedStepTiming", spent) }
                }
                put("steps", JsonArray(records.map { record -> record.toJson() }))
                put("ranSteps", records.count { it.status == "done" })
                // The question the model always has to answer next, named
                // rather than left to be inferred from the error type.
                put("stepMayAlreadyHaveRun", committed)
                screen?.let { put("screen", it.summary()) }
                // What earlier calls captured. A resumed run receives these
                // instead of re-running the committed prefix that made them.
                if (outputs.isNotEmpty()) put("outputs", JsonObject(outputs))
                step?.let {
                    val adHoc = options.adHocTool
                    put(
                        "resume",
                        buildJsonObject {
                            put("tool", adHoc ?: "workflow_runner")
                            put(
                                "arguments",
                                buildJsonObject {
                                    // An inline plan is not in the library, so
                                    // there is nothing to name it by: the
                                    // caller resends its own steps.
                                    if (adHoc == null) {
                                        put("workflow", definition.id)
                                        put("mode", "resume")
                                    }
                                    put("startAt", it.id)
                                    if (options.params.isNotEmpty()) put("params", options.params)
                                    if (outputs.isNotEmpty()) put("outputs", JsonObject(outputs))
                                },
                            )
                            if (adHoc != null) {
                                put(
                                    "note",
                                    "Send the same steps again with this startAt. The steps listed as done " +
                                        "are skipped, never re-run. Re-plan instead if the screen has moved on.",
                                )
                            }
                        },
                    )
                }
                put(
                    "remedy",
                    when {
                        errorType == "confirmation_denied" ->
                            "The user said no. Do not retry this workflow; ask what they want instead."
                        committed ->
                            "The failing step may already have changed the phone. Read the screen before " +
                                "deciding: the steps listed as done must not be repeated, and this one must " +
                                "not be repeated blindly. Fix what is in the way, then resume from the next " +
                                "step, or finish by hand."
                        else ->
                            "The failing step changed nothing. The steps listed as done have already run and " +
                                "must not be repeated: deal with what is in the way, then resume from the " +
                                "failing step with the \"resume\" arguments above."
                    },
                )
            }.toString(),
            imageBase64 = shot?.imageBase64,
            success = false,
            attachmentPaths = shot?.attachmentPaths.orEmpty(),
        )
    }

    private fun Screen.summary(): JsonObject = buildJsonObject {
        put("ok", ok)
        activePackage?.let { put("activePackage", it) }
        failure?.let { put("failure", it) }
        put("nodeCount", nodes.size)
        if (truncated) put("truncated", true)
        // Enough of the screen to see why a selector missed, and no more: the
        // whole point of the runner is that screens do not reach the model.
        put(
            "visible",
            buildJsonArray {
                nodes.asSequence()
                    .filter { it.text != null || it.contentDescription != null || it.resourceId != null }
                    .take(MAX_REPORTED_NODES)
                    .forEach { node ->
                        add(
                            buildJsonObject {
                                node.text?.let { put("text", it.take(MAX_REPORTED_FIELD)) }
                                node.contentDescription?.let { put("contentDescription", it.take(MAX_REPORTED_FIELD)) }
                                node.resourceId?.let { put("resourceId", it.take(MAX_REPORTED_FIELD)) }
                                if (node.checkable) put("checked", node.checked)
                                if (node.clickable) put("clickable", true)
                            },
                        )
                    }
            },
        )
    }

    companion object {
        const val DEFAULT_TOTAL_MS = 90_000L
        const val MIN_TOTAL_MS = 5_000L

        /**
         * The coordinator holds one lock for the whole tool call, so a run that
         * lasted minutes would make Stop feel broken. Three minutes is the hard
         * ceiling, and running out of budget is recoverable in one more call
         * because the reply says which step to resume from.
         */
        const val MAX_TOTAL_MS = 180_000L

        private const val MIN_STEP_HEADROOM_MS = 1_000L
        private const val DEFAULT_WAIT_MS = 3_000L
        private const val SETTLE_MS = 3_000L
        private const val SETTLE_FALLBACK_MS = 600L
        private const val VERIFY_POLL_MS = 400L
        private const val RETURN_WAIT_MS = 3_000L
        private const val SWIPE_MS = 350
        private const val MAX_SCROLL_HUNT = 6
        private const val MAX_STEP_TEXT = 400
        private const val MAX_SUMMARY_TEXT = 120
        private const val MAX_REPORTED_NODES = 24

        /** Below this a phase is noise, and every step would carry four numbers. */
        private const val MIN_REPORTED_PHASE_MS = 50L
        private const val MAX_REPORTED_FIELD = 80

        /** Below this a reverse match is noise: "on" is inside half the labels on a screen. */
        private const val MIN_REVERSE_MATCH_CHARS = 3

        /**
         * The last resort for a gesture on a screen that reported no bounds at
         * all. Replaced by the real extent the moment any node carries one.
         */
        private val DEFAULT_SWIPE_BOUNDS = listOf(0, 0, 1080, 2000)

        /** What the composite answers a name no backend advertises with. */
        private const val UNKNOWN_TOOL = "Unknown tool:"

        /**
         * Failures that mean the call never reached the device, so another path
         * may still be tried. Anything else ran and failed, and retrying it
         * elsewhere could commit the same action twice.
         */
        private val NOT_DISPATCHED = setOf(
            "backend_unavailable",
            "a11y_unavailable",
            "a11y_unsupported",
            "adb_not_connected",
            "workflow_unsupported",
        )
    }
}
