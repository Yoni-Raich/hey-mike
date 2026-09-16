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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Exposes the workflow engine as tools.
 *
 * The engine has to call other tools, and the composite that routes them also
 * contains this gateway — so the router is supplied lazily rather than through
 * the constructor. Evaluating it per call is also what makes a step reach
 * whichever backend currently serves that tool, instead of one captured at
 * wiring time.
 *
 * A workflow does **not** re-enter `AgentCoordinator.toolLock`: steps are
 * dispatched straight at the router, inside the single tool call the
 * coordinator already holds the lock for. That is why the engine's total
 * budget matters — the lock is held for the whole workflow, and Stop has to
 * stay responsive.
 */
class WorkflowToolGateway(
    private val store: WorkflowStore,
    /** The composite. Resolved per call, never captured. */
    private val router: () -> DeviceToolGateway,
    /** Where declarative workflow definitions live. Null leaves `workflow_runner` unadvertised. */
    private val library: WorkflowLibrary? = null,
    /**
     * Asks the user before a step marked `requiresConfirmation` runs. The
     * default refuses, so a host that wired no approval path never has a
     * sensitive step run unattended.
     */
    private val confirm: suspend (WorkflowConfirmation) -> WorkflowConfirmationOutcome =
        { WorkflowConfirmationOutcome.UNAVAILABLE },
    /**
     * The allowlist for `call` steps: plain metadata, so wiring passes names
     * without a construction cycle back through the composite that routes them.
     */
    private val callRegistry: WorkflowCallRegistry = WorkflowCallRegistry.EMPTY,
) : DeviceToolGateway {

    @Volatile private var revoked = true

    private val engine = WorkflowEngine(
        invokeTool = { name, args -> router().invoke(name, args) },
        store = store,
        isRevoked = { revoked },
    )

    private val runner = WorkflowRunner(
        invokeTool = { name, args -> router().invoke(name, args) },
        isRevoked = { revoked },
        confirm = confirm,
        callRegistry = callRegistry,
    )

    override val definitions: List<ToolDefinition> =
        // `act_plan` needs no library: its steps come from the model's own
        // reading of the screen, so it is always callable.
        (TOOL_DEFINITIONS + PLAN_DEFINITION) + if (library == null) emptyList() else listOf(RUNNER_DEFINITION)

    override fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        revoked = false
    }

    override fun revoke() {
        revoked = true
    }

    /**
     * A running workflow drives the screen, so it shows the control banner.
     * Saving and listing touch nothing.
     */
    override fun needsControl(name: String): Boolean =
        name == "run_workflow" || name == "workflow_runner" || name == "act_plan"

    /** Steps dispatch through the device backends; this gateway alone operates nothing. */
    override fun deviceBackendLive(): Boolean = false

    override fun statusLine(): String {
        val saved = store.all().size
        val defined = library?.all()?.size ?: 0
        val parts = buildList {
            if (defined > 0) add("$defined runnable")
            if (saved > 0) add("$saved saved")
        }
        return "Workflows: " + if (parts.isEmpty()) "none saved" else parts.joinToString(", ")
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (revoked) throw IllegalStateException("Run stopped. No device action was performed.")
        return when (name) {
            "run_workflow" -> engine.run(arguments)
            "workflow_runner" -> runWorkflowRunner(arguments)
            "act_plan" -> runPlan(arguments)
            "save_workflow" -> engine.save(arguments)
            "list_workflows" -> engine.list(arguments)
            else -> throw ToolNotServiceable(
                "workflow_unsupported",
                "The workflow engine does not implement \"$name\".",
            )
        }
    }

    override suspend fun cancel() {
        // Every step is bounded by withTimeoutOrNull and unwinds with the
        // cancelled coroutine; `revoked` stops the loop between steps.
    }

    /**
     * Load the named definition and run it, or say precisely why not.
     *
     * Every failure here happens before the phone is touched, so each one is a
     * plain refusal with the list of workflows that do exist: a model that
     * guessed a name gets the real ones back rather than a dead end.
     */
    private suspend fun runWorkflowRunner(arguments: JsonObject): ToolResult {
        val library = library ?: throw ToolNotServiceable(
            "workflow_library_unavailable",
            "No workflow library is configured, so there are no definitions to run.",
        )
        val mode = arguments.str("mode")?.lowercase() ?: "run"
        if (mode !in MODES) {
            return refusal(
                "unknown_mode",
                "\"$mode\" is not a mode. Use " + MODES.joinToString(", ") + ".",
            )
        }
        val requested = arguments.str("workflow") ?: arguments.str("id") ?: arguments.str("name")
        val packageName = arguments.str("package")
        if (mode == "list" || requested == null) {
            if (mode == "list") return listDefinitions(library, packageName)
            return refusal(
                "workflow_required",
                "\"workflow\" is required: name the workflow to run. " + known(library, packageName),
            )
        }
        val definition = when (val lookup = library.find(requested, packageName)) {
            is WorkflowLibrary.Lookup.Found -> lookup.definition
            is WorkflowLibrary.Lookup.Ambiguous -> return refusal(
                "workflow_ambiguous",
                "\"$requested\" matches ${lookup.candidates.joinToString(", ")}. Name one of them exactly.",
            )
            is WorkflowLibrary.Lookup.NotFound -> return refusal(
                "workflow_not_found",
                "There is no workflow called \"$requested\". " + known(library, packageName),
            )
        }
        if (mode == "describe") return describe(definition)

        // A resume without a starting step has nowhere to continue from, and
        // re-running the whole workflow could repeat committed calls.
        val startAt = arguments.str("startAt") ?: arguments.str("fromStep")
        if (mode == "resume" && startAt.isNullOrBlank()) {
            return refusal(
                "workflow_start_required",
                "mode=\"resume\" needs \"startAt\" naming the step to resume from. " +
                    "Use the \"resume\" arguments from the failure reply, which name it.",
            )
        }

        val params = when (val raw = arguments["params"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> return refusal("workflow_params_invalid", "params must be an object of parameter name to value.")
        }
        // Bound before anything runs: a missing or out-of-range value is a
        // refusal with nothing done, not a failure halfway through.
        val bound = try {
            definition.bind(params)
        } catch (invalid: WorkflowFormatException) {
            return refusal(
                invalid.errorType,
                invalid.message + if (definition.parameters.isEmpty()) "" else
                    " Parameters: " + definition.parametersOutline().toString(),
            )
        }
        val budget = arguments.millis("totalBudgetMs", WorkflowRunner.MIN_TOTAL_MS, WorkflowRunner.MAX_TOTAL_MS)
            ?: WorkflowRunner.DEFAULT_TOTAL_MS
        // Outputs a previous attempt captured, carried in `resume` so the
        // resumed run reuses them instead of re-running committed calls.
        // Validated here, before anything runs: resume state comes from the
        // model, so it is checked like any other argument.
        val priorOutputs = when (val raw = arguments["outputs"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> {
                validateResumeOutputs(mode, raw)?.let { return refusal("workflow_outputs_invalid", it) }
                raw
            }
            else -> return refusal("workflow_outputs_invalid", "\"outputs\" must be an object of name to value.")
        }
        return runner.run(
            bound,
            WorkflowRunner.Options(
                mode = mode,
                startAt = startAt,
                totalBudgetMs = budget,
                screenshotOnFailure = arguments.bool("screenshotOnFailure") ?: false,
                params = params,
                outputs = priorOutputs,
            ),
        )
    }

    /**
     * Run one inline plan the model wrote from the screen it just read.
     *
     * `read_ui` already told the model where the field and the button are, and
     * that a send is field, text, button. Spending a model turn per action
     * re-derives what the first observation said. This takes the whole sequence
     * in one call and executes it with [WorkflowRunner], so each step is still
     * resolved against the screen in front of it, settled and checked - the
     * plan is a plan, not a macro, and a step that does not land stops the run
     * with the prefix that did.
     *
     * Every refusal here happens before the phone is touched, and says what to
     * send instead: a plan is written by the model, so a malformed one is a
     * correctable mistake rather than a dead end.
     */
    private suspend fun runPlan(arguments: JsonObject): ToolResult {
        val steps = when (val raw = arguments["steps"]) {
            null, is JsonNull -> return refusal(
                "plan_steps_required",
                "\"steps\" is required: the actions to run, in order, e.g. " +
                    "steps=[{\"action\":\"tap\",\"target\":{\"text\":\"Message\"}}," +
                    "{\"action\":\"type_text\",\"target\":{\"class\":\"EditText\"},\"text\":\"on my way\"}," +
                    "{\"action\":\"tap\",\"target\":{\"contentDescription\":\"Send\"}}].",
            )
            is JsonArray -> raw
            else -> return refusal("plan_invalid", "\"steps\" must be an array of steps.")
        }
        if (steps.isEmpty()) return refusal("plan_invalid", "\"steps\" is empty, so there is nothing to run.")
        if (steps.size > MAX_PLAN_STEPS) {
            // A plan is what one screen's reading can justify. Longer than that
            // is a workflow: it deserves a definition file that can be read,
            // fixed and reused rather than being re-derived every chat.
            return refusal(
                "plan_too_long",
                "A plan has ${steps.size} steps; the limit is $MAX_PLAN_STEPS. Run the first " +
                    "$MAX_PLAN_STEPS, read the screen and plan again - or, if this sequence will be " +
                    "asked for again, save it as a workflow definition and run it with workflow_runner.",
            )
        }
        positionalComplaint(arguments, steps)?.let { return refusal("plan_positional", it) }

        val packageName = arguments.str("package") ?: inferPackage(steps) ?: return refusal(
            "plan_package_required",
            "\"package\" is required when the app in front cannot be read: pass the " +
                "activePackage from your last read_ui, or the package the first step opens. " +
                "If read_ui is failing too, no device backend is live - that is what to fix first.",
        )
        val definition = try {
            WorkflowDefinition.adHoc(packageName, steps)
        } catch (invalid: WorkflowFormatException) {
            return refusal(invalid.errorType, invalid.message + " Nothing ran; fix the step and call again.")
        }

        // Resuming a plan is the model resending its own steps with the step to
        // start at, so the committed prefix is skipped rather than repeated.
        val startAt = arguments.str("startAt") ?: arguments.str("fromStep")
        val mode = if (startAt == null) "run" else "resume"
        if (startAt != null && startIndex(definition, startAt) == null) {
            // Refused here rather than inside the run: a startAt that names
            // nothing would otherwise read as a failure of the plan itself.
            return refusal(
                "plan_start_unknown",
                "\"$startAt\" is not a step of this plan. Its steps are " +
                    definition.steps.joinToString(", ") { it.id } + ".",
            )
        }
        val priorOutputs = when (val raw = arguments["outputs"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> {
                validateResumeOutputs(mode, raw)?.let { return refusal("workflow_outputs_invalid", it) }
                raw
            }
            else -> return refusal("workflow_outputs_invalid", "\"outputs\" must be an object of name to value.")
        }
        val budget = arguments.millis("totalBudgetMs", WorkflowRunner.MIN_TOTAL_MS, WorkflowRunner.MAX_TOTAL_MS)
            ?: WorkflowRunner.DEFAULT_TOTAL_MS

        val result = runner.run(
            definition,
            WorkflowRunner.Options(
                mode = mode,
                startAt = startAt,
                totalBudgetMs = budget,
                screenshotOnFailure = arguments.bool("screenshotOnFailure") ?: false,
                outputs = priorOutputs,
                adHocTool = "act_plan",
            ),
        )
        // The runner's own reads never reach the model, so without this the
        // call that saved three round trips would cost one back to find out
        // where it ended up.
        return if (arguments.bool("observe") == false) result else withObservation(result)
    }

    /** The step [startAt] names, by id or by index, or null when it names none. */
    private fun startIndex(definition: WorkflowDefinition, startAt: String): Int? {
        val wanted = startAt.trim()
        definition.stepIndex(wanted)?.let { return it }
        return wanted.toIntOrNull()?.takeIf { it in definition.steps.indices }
    }

    /**
     * Why this plan cannot be run as written, or null when it can.
     *
     * A node id is minted by one `read_ui` and dies at the next, and the runner
     * reads the screen itself before every step - so a plan carrying ids would
     * be stale by the second step. The labels the model already has from that
     * same observation do survive, which is the whole reason a plan can be
     * written ahead. Said here rather than ignored: a silently dropped nodeId
     * would leave the model believing it named the target.
     */
    private fun positionalComplaint(arguments: JsonObject, steps: JsonArray): String? {
        if (arguments["observationId"] != null) {
            return "act_plan takes no \"observationId\": every step is resolved against the screen in " +
                "front of it, so ids from your last read_ui are neither needed nor accepted. Name each " +
                "target by its text, contentDescription, resourceId or class instead."
        }
        for ((index, element) in steps.withIndex()) {
            val target = (element as? JsonObject)?.get("target") as? JsonObject ?: continue
            val offender = POSITIONAL_TARGET_KEYS.firstOrNull { target[it] != null } ?: continue
            return "Step $index names its target by \"$offender\", which a plan cannot use: an id or a " +
                "coordinate describes one observation, and the runner re-reads the screen before every " +
                "step. Use the node's \"text\", \"contentDescription\", \"resourceId\" or \"class\" " +
                "from that same read_ui - those still identify it after the keyboard opens or the list " +
                "moves."
        }
        return null
    }

    /**
     * Which app the plan runs in: what a step opens, or what is in front.
     *
     * The package is only a default for `open_app` and the screen to return to
     * after an approval, so reading it costs one on-device observation and
     * saves the model from having to state what it just read.
     */
    private suspend fun inferPackage(steps: JsonArray): String? {
        steps.firstNotNullOfOrNull { element ->
            (element as? JsonObject)?.let { step ->
                (step["arguments"] as? JsonObject)?.str("package") ?: step.str("package")
            }
        }?.let { return it }
        val reply = try {
            router().invoke("read_ui", buildJsonObject { put("maxNodes", 1) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: Exception) {
            return null
        }
        return runCatching { Json.parseToJsonElement(reply.text).jsonObject.str("activePackage") }.getOrNull()
    }

    /**
     * The plan's report with the screen it ended on.
     *
     * `force` because unchanged-suppression answers "the same as revision N",
     * and the model never saw the runner's reads: N is not a list it holds.
     * A failed read is reported as one - the steps still ran, and a reply that
     * dropped the observation silently would read as if they had not.
     */
    private suspend fun withObservation(result: ToolResult): ToolResult {
        val report = runCatching { Json.parseToJsonElement(result.text).jsonObject }.getOrNull() ?: return result
        // Stop revokes new calls before it interrupts, so a stopped run reads
        // nothing more: the backends would refuse it, and asking anyway would
        // bury the report under an unreadable screen.
        if (report.str("errorType") == "stopped") return result
        val observation = try {
            val reply = router().invoke("read_ui", buildJsonObject { put("force", true) })
            runCatching { Json.parseToJsonElement(reply.text) }.getOrNull()
                ?: JsonPrimitive(reply.text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            buildJsonObject {
                put("ok", false)
                put("message", failure.message ?: "The screen could not be read after the plan.")
                put("note", "The steps above still ran. Do not repeat them; read the screen again.")
            }
        }
        return result.copy(text = JsonObject(report + ("observation" to observation)).toString())
    }

    private fun describe(definition: WorkflowDefinition): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("workflow", definition.id)
            put("package", definition.packageName)
            put("version", definition.version)
            if (definition.description.isNotEmpty()) put("description", definition.description)
            if (definition.parameters.isNotEmpty()) put("parameters", definition.parametersOutline())
            put("steps", definition.outline())
            put(
                "note",
                "Nothing ran. Call workflow_runner again with mode=\"run\" to execute these steps.",
            )
        }.toString(),
    )

    private fun listDefinitions(library: WorkflowLibrary, packageName: String?): ToolResult {
        val definitions = if (packageName.isNullOrBlank()) library.all() else library.forPackage(packageName)
        val broken = library.broken()
        return ToolResult(
            buildJsonObject {
                put("ok", true)
                put("count", definitions.size)
                put(
                    "workflows",
                    JsonArray(
                        definitions.map { definition ->
                            buildJsonObject {
                                put("workflow", definition.id)
                                put("package", definition.packageName)
                                if (definition.description.isNotEmpty()) put("description", definition.description)
                                put("steps", definition.steps.size)
                                if (definition.parameters.isNotEmpty()) {
                                    put("parameters", definition.parametersOutline())
                                }
                                if (definition.steps.any { it.requiresConfirmation }) {
                                    put("asksBeforeSensitiveSteps", true)
                                }
                            }
                        },
                    ),
                )
                if (broken.isNotEmpty()) {
                    // Named rather than hidden: a workflow the user wrote and
                    // cannot see in the list looks like it was ignored.
                    put(
                        "unreadable",
                        JsonArray(
                            broken.map { entry ->
                                buildJsonObject { put("file", entry.file); put("reason", entry.reason) }
                            },
                        ),
                    )
                }
                if (definitions.isEmpty() && !packageName.isNullOrBlank() && library.all().isNotEmpty()) {
                    // A phone reported "none installed" for a package filter
                    // while four definitions existed, and the model believed it.
                    put(
                        "hint",
                        "No workflow is defined for $packageName. Other workflows are installed; call " +
                            "workflow_runner(mode=\"list\") without package to see them.",
                    )
                } else if (definitions.isEmpty()) {
                    put(
                        "hint",
                        "No workflow definitions are installed. Work the sequence out with the device " +
                            "tools, then write it as a definition file (see the workflows skill) so the " +
                            "next chat does not rebuild it. save_workflow stores a different, older format " +
                            "that workflow_runner does not load.",
                    )
                }
            }.toString(),
        )
    }

    private fun known(library: WorkflowLibrary, packageName: String?): String {
        val ids = (if (packageName.isNullOrBlank()) library.all() else library.forPackage(packageName)).map { it.id }
        return if (ids.isEmpty()) {
            "No workflow definitions are installed."
        } else {
            "Installed workflows: " + ids.joinToString(", ") + "."
        }
    }

    /**
     * Resume state comes from the model, so it is bounded like a capture:
     * accepted only on resume, at most [WorkflowCallRegistry.MAX_OUTPUT_BINDINGS]
     * bindings under usable names, with no binding over
     * [WorkflowCallRegistry.MAX_CAPTURED_OUTPUT_CHARS] and no whole map over
     * [WorkflowCallRegistry.MAX_TOTAL_OUTPUT_CHARS] serialized.
     * Null when the outputs are acceptable.
     */
    private fun validateResumeOutputs(mode: String, outputs: JsonObject): String? {
        if (outputs.isEmpty()) return null
        if (mode != "resume") {
            return "\"outputs\" rides only in a resume, next to \"startAt\". " +
                "Start fresh without it, or resume from the failing step with the \"resume\" arguments."
        }
        if (outputs.size > WorkflowCallRegistry.MAX_OUTPUT_BINDINGS) {
            return "There are ${outputs.size} outputs; the limit is ${WorkflowCallRegistry.MAX_OUTPUT_BINDINGS}."
        }
        outputs.keys.firstOrNull { !WorkflowStep.OUTPUT_NAME_RE.matches(it) }?.let { bad ->
            return "\"$bad\" is not a usable output name: use letters, digits and \"_\"."
        }
        val cap = WorkflowCallRegistry.MAX_CAPTURED_OUTPUT_CHARS
        outputs.entries.firstOrNull { (_, value) -> value.toString().length > cap }?.let { (name, value) ->
            return "Output \"$name\" is ${value.toString().length} characters; the limit is $cap."
        }
        // The same total the runner enforces before storing a capture, so a
        // resume the runner emitted is always accepted back here.
        val totalCap = WorkflowCallRegistry.MAX_TOTAL_OUTPUT_CHARS
        if (outputs.toString().length > totalCap) {
            return "The outputs are ${outputs.toString().length} characters together; the limit is $totalCap."
        }
        return null
    }

    /** Nothing was dispatched, so the reply says so before anything else. */
    private fun refusal(errorType: String, message: String): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", false)
            put("errorType", errorType)
            put("message", message)
            put("nothingRan", true)
        }.toString(),
        success = false,
    )

    private companion object {
        val MODES = listOf("run", "resume", "describe", "list")

        /**
         * How long an inline plan may be.
         *
         * Short on purpose. A plan is what the model can justify from one
         * reading of the screen; past that the screen it was planned from is
         * gone, and the sequence is a workflow that deserves a definition file
         * rather than being re-derived in every chat.
         */
        const val MAX_PLAN_STEPS = 8

        /** Target fields that only ever meant one observation. */
        val POSITIONAL_TARGET_KEYS = listOf("nodeId", "observationId", "bounds", "x", "y")

        /**
         * The tool that turns one observation into one call.
         *
         * `read_ui` already told the model where the field and the button are,
         * and that sending is focus, type, press. Today that costs a model turn
         * per action; this takes the sequence the observation already implies
         * and runs it, with every step still resolved against the live screen.
         *
         * The description spends its length on the two things a plan gets wrong
         * otherwise: naming targets by label rather than by id, and what a
         * failure means - the prefix ran, so the model resumes instead of
         * starting again.
         */
        val PLAN_DEFINITION: ToolDefinition = tool(
            "act_plan",
            "Run a short sequence of actions you can already see in ONE call, with no model turn per " +
                "action. Use it as soon as read_ui shows you the whole sequence - focus a field, type, " +
                "press Send is one act_plan call, not three. Each step is " +
                "{action, target, text?}: action is tap, type_text, scroll, key, wait, observe, open_app, " +
                "open_intent or call; target names the element by \"text\", \"contentDescription\", " +
                "\"resourceId\" or \"class\" - NOT by nodeId, which belongs to one observation. The " +
                "steps are resolved against the screen in front of each one, not replayed, so the plan " +
                "still lands after the keyboard opens or a row moves. Add \"verify\" to a step to say " +
                "what must be true afterwards, \"optional\":true for a dialog that may not appear. " +
                "When a step waits on something slower than a screen opening - a video attaching, an " +
                "upload, an install - say how long you expect it to take: " +
                "\"verify\":{\"present\":{...},\"timeoutMs\":45000} waits up to 45s and stops the " +
                "moment the condition holds; raise totalBudgetMs to match. Each step reports where its " +
                "time went (resolve, act, settle, verify), so a slow plan says which part was slow. " +
                "A tap on Send still asks the user, exactly as it does on its own. The reply ends with " +
                "a fresh observation (observe=false drops it). On failure it names the failing step and " +
                "every step that already ran: resend the same steps with startAt to continue, or " +
                "re-plan - never repeat a step reported as done.",
            mapOf(
                "steps" to "array",
                "package" to "string",
                "startAt" to "string",
                "outputs" to "object",
                "observe" to "boolean",
                "totalBudgetMs" to "integer",
                "screenshotOnFailure" to "boolean",
            ),
            listOf("steps"),
        )

        /**
         * The one tool the model needs for a whole sequence.
         *
         * Deliberately small: a workflow id and a mode. Everything else — which
         * element each step means, what has to be true afterwards, which steps
         * stop and ask — is in the definition file, where it can be read,
         * reviewed and fixed without the model re-deriving it every time.
         */
        val RUNNER_DEFINITION: ToolDefinition = tool(
            "workflow_runner",
            "Run a saved workflow end to end in ONE call, with no model turn per step. Name the " +
                "workflow and it reads the screen, finds each element by id or label, acts, waits and " +
                "checks the result before moving on, so it keeps working when a row moves or an app " +
                "updates. mode=\"list\" names the installed workflows, mode=\"describe\" prints the " +
                "steps without running anything, mode=\"run\" executes them. A workflow that lists " +
                "parameters takes their values in params, e.g. params={\"minutes\":10}. A step marked " +
                "requiresConfirmation stops and asks the user. A step with action \"call\" invokes one " +
                "registered device tool with JSON args and may capture its result with \"output\" for " +
                "later steps as {{outputs.name}}. Captured outputs ride only in a resume, next to " +
                "startAt. On failure the reply names the exact step, " +
                "whether it may already have run, what is on screen and the arguments to resume from that " +
                "step - resume with those, never start again.",
            mapOf(
                "workflow" to "string",
                "package" to "string",
                "mode" to "string",
                "params" to "object",
                "outputs" to "object",
                "startAt" to "string",
                "totalBudgetMs" to "integer",
                "screenshotOnFailure" to "boolean",
            ),
            emptyList(),
        )

        val TOOL_DEFINITIONS: List<ToolDefinition> = listOf(
            tool(
                "run_workflow",
                "Run a known sequence of steps locally, without a model turn per step. Each step is " +
                    "{tool, arguments, timeoutMs}. Use it only for a sequence you already know works — " +
                    "it does not decide anything. If a step fails, the reply names the failing step and " +
                    "everything that already ran: continue from there, never restart, and never repeat a " +
                    "step reported as possibly committed.",
                mapOf("steps" to "array", "totalBudgetMs" to "integer"),
                listOf("steps"),
            ),
            tool(
                "save_workflow",
                "Store a sequence that ran cleanly, keyed by package, so a later chat can reuse it " +
                    "instead of rebuilding it step by step.",
                mapOf(
                    "name" to "string",
                    "package" to "string",
                    "description" to "string",
                    "steps" to "array",
                ),
                listOf("name", "package", "steps"),
            ),
            tool(
                "list_workflows",
                "List saved workflows, optionally for one package. Check here before working a " +
                    "sequence out from scratch. Read-only.",
                mapOf("package" to "string"),
                emptyList(),
            ),
        )

        fun tool(
            name: String,
            description: String,
            properties: Map<String, String>,
            required: List<String>,
        ): ToolDefinition {
            val props = buildJsonObject {
                for ((key, type) in properties) {
                    put(key, buildJsonObject {
                        put("type", type)
                        // A free-form map, such as workflow_runner's params.
                        if (type == "object") put("additionalProperties", true)
                    })
                }
            }
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", props)
                put("description", description)
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
            return ToolDefinition(name, description, schema)
        }
    }
}
