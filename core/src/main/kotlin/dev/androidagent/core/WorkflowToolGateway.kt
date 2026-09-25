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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The plan every `act_plan` refusal and doc example shows.
 *
 * One constant rather than three copies: an example that drifts from what the
 * validator accepts is how a caller ends up writing a call that cannot run, and
 * `ActPlanTest` runs this array to keep the promise honest.
 */
internal val PLAN_EXAMPLE_STEPS: JsonArray = buildJsonArray {
    add(
        buildJsonObject {
            put("id", "focus")
            put("action", "tap")
            put("target", buildJsonObject { put("class", "EditText") })
        },
    )
    add(
        buildJsonObject {
            put("id", "write")
            put("action", "type_text")
            put("target", buildJsonObject { put("class", "EditText") })
            put("text", "on my way")
        },
    )
    add(
        buildJsonObject {
            put("id", "send")
            put("action", "tap")
            put("target", buildJsonObject { put("contentDescription", "Send") })
        },
    )
}

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
        if (mode == "save") return saveDefinition(library, arguments, requested, packageName)
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
            is WorkflowLibrary.Lookup.NotFound -> {
                val legacy = store.all().firstOrNull {
                    it.name.equals(requested, ignoreCase = true) &&
                        (packageName.isNullOrBlank() || it.packageName.equals(packageName, ignoreCase = true))
                }
                if (legacy != null) return refusal(
                    "workflow_legacy_format",
                    "\"${legacy.name}\" is an older saved step list for ${legacy.packageName}, not a " +
                        "verified workflow definition. Read it with list_workflows and run its steps with " +
                        "run_workflow, or save a declarative definition with workflow_runner(mode=\"save\").",
                )
                return refusal(
                    "workflow_not_found",
                    "There is no workflow called \"$requested\". " + known(library, packageName),
                )
            }
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

    /** Store a validated declarative workflow without touching the phone. */
    private fun saveDefinition(
        library: WorkflowLibrary,
        arguments: JsonObject,
        requested: String?,
        packageName: String?,
    ): ToolResult {
        val raw = arguments["definition"] as? JsonObject
            ?: return refusal("workflow_definition_required", "mode=\"save\" needs a definition object.")
        val definition = try {
            WorkflowDefinition.parse(raw)
        } catch (invalid: WorkflowFormatException) {
            return refusal(invalid.errorType, invalid.message ?: "The workflow definition is invalid.")
        }
        if (requested != null && !requested.equals(definition.id, ignoreCase = true)) {
            return refusal("workflow_id_mismatch", "The requested workflow name differs from definition.id.")
        }
        if (packageName != null && !packageName.equals(definition.packageName, ignoreCase = true)) {
            return refusal("workflow_package_mismatch", "The requested package differs from definition.package.")
        }
        if (library.all().any { it.id == definition.id && it.packageName != definition.packageName }) {
            return refusal("workflow_id_taken", "That workflow id belongs to another package. Choose a new id.")
        }
        return try {
            library.save(definition)
            ToolResult(
                buildJsonObject {
                    put("ok", true)
                    put("saved", true)
                    put("format", "declarative")
                    put("workflow", definition.id)
                    put("package", definition.packageName)
                    put("version", definition.version)
                    put("steps", definition.steps.size)
                    put("nothingRan", true)
                    put("note", "Saved. Use workflow_runner(mode=\"describe\") to review it before running.")
                }.toString(),
            )
        } catch (error: Exception) {
            refusal("workflow_save_failed", error.message ?: "The definition could not be saved.")
        }
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
                "\"steps\" is required: the actions to run, in order, as objects - " +
                    "steps=$PLAN_EXAMPLE_STEPS",
            )
            // A step the model quoted as JSON text is still a step: the schema
            // used to advertise a bare array, so this arrived, and refusing it
            // costs a whole round trip to learn one thing about quoting.
            is JsonArray -> JsonArray(raw.map(::unquoted))
            else -> return refusal("plan_invalid", "\"steps\" must be an array of step objects.")
        }
        if (steps.isEmpty()) return refusal("plan_invalid", "\"steps\" is empty, so there is nothing to run.")
        steps.indexOfFirst { it !is JsonObject }.takeIf { it >= 0 }?.let { index ->
            return refusal(
                "plan_invalid",
                "Step $index is not a step object. Each step is " +
                    "{\"action\":\"tap\",\"target\":{\"text\":\"Send\"}} - an object with an " +
                    "\"action\", not a string, a number or a list.",
            )
        }
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

    /**
     * One step, with JSON text read as the object it spells.
     *
     * Anything else is handed back untouched, so it meets the refusal that
     * names its position instead of being coerced into something else.
     */
    private fun unquoted(element: JsonElement): JsonElement {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return element
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: element
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
        val legacy = if (packageName.isNullOrBlank()) store.all() else store.forPackage(packageName)
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
                put("legacyCount", legacy.size)
                put(
                    "legacyWorkflows",
                    JsonArray(
                        legacy.take(MAX_LEGACY_LIST).map { workflow ->
                            buildJsonObject {
                                put("name", workflow.name)
                                put("package", workflow.packageName)
                                put("steps", workflow.steps.size)
                                put("format", "legacy_steps")
                            }
                        },
                    ),
                )
                if (legacy.size > MAX_LEGACY_LIST) put("legacyTruncated", true)
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
                if (definitions.isEmpty() && legacy.isNotEmpty()) {
                    put(
                        "hint",
                        "Only older saved step lists match this request. Read them with list_workflows " +
                            "and replay with run_workflow, or save a verified definition with " +
                            "workflow_runner(mode=\"save\"). The older format is not loaded as a definition.",
                    )
                } else if (definitions.isEmpty() && !packageName.isNullOrBlank() && library.all().isNotEmpty()) {
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
                            "tools, then save a declarative definition with workflow_runner(mode=\"save\"). " +
                            "save_workflow stores an older format that workflow_runner does not load.",
                    )
                }
            }.toString(),
        )
    }

    private fun known(library: WorkflowLibrary, packageName: String?): String {
        val ids = (if (packageName.isNullOrBlank()) library.all() else library.forPackage(packageName)).map { it.id }
        return if (ids.isEmpty()) {
            val legacyCount = if (packageName.isNullOrBlank()) store.all().size else store.forPackage(packageName).size
            if (!packageName.isNullOrBlank() && library.all().isNotEmpty()) {
                "No declarative definition matches package $packageName. " +
                    "Call workflow_runner(mode=\"list\") without a package to see other definitions."
            } else if (legacyCount == 0) "No workflow definitions are installed." else
                "No declarative definition matches; $legacyCount older saved step list(s) are available through list_workflows."
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
        val MODES = listOf("run", "resume", "describe", "list", "save")
        const val MAX_LEGACY_LIST = 40

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
         * How a step names the element it acts on.
         *
         * Open on purpose: the parser takes `class` and `className`, `id` and
         * `resourceId`, and a strict schema would refuse a call the runner
         * understands. What matters here is that these are the fields that
         * exist at all — a plan that names a `nodeId` is refused, and this is
         * where a model learns what to send instead.
         */
        private val TARGET_SCHEMA: JsonObject = openObject(
            "Which element, by what the last read_ui showed. NOT a nodeId or coordinates.",
            mapOf(
                "text" to field("string", "Visible text or contentDescription, as a substring."),
                "contentDescription" to field("string", "Accessibility label, as a substring."),
                "resourceId" to field("string", "View id; the local half (\"search_src_text\") is enough."),
                "class" to field("string", "Class name substring, e.g. EditText, Switch, RecyclerView."),
                "exact" to field("boolean", "Require the whole field to equal the value."),
                "index" to field("integer", "Which match to take when several score the same. Default 0."),
                "clickable" to field("boolean", "Only a node that can be clicked, or has a clickable ancestor."),
                "scrollable" to field("boolean", "Only a scrollable node."),
                "scrollIntoView" to field("boolean", "Scroll looking for it before giving up."),
            ),
        )

        /** What must be true for a step to count as done. */
        private val VERIFY_SCHEMA: JsonObject = openObject(
            "What must be true after the step. Polled until it holds, so timeoutMs is your estimate of the work.",
            mapOf(
                "present" to openObject("A selector that must be on screen."),
                "absent" to openObject("A selector that must be gone."),
                "package" to field("string", "The app that must be in front, as a substring."),
                "checked" to field("boolean", "The target switch's expected state."),
                "timeoutMs" to field("integer", "How long this may take. Default 5000, up to 60000."),
            ),
        )

        /** One `act_plan` step, spelled out so a step arrives as an object. */
        private val PLAN_STEP_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("description", "One action, with the element it acts on named by label.")
            put("properties", buildJsonObject {
                put("id", field("string", "Your name for this step. It comes back in the ledger and in startAt."))
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "What this step does.")
                    put("enum", JsonArray(WorkflowAction.WIRE_NAMES.map { JsonPrimitive(it) }))
                })
                put("target", TARGET_SCHEMA)
                put("text", field("string", "For type_text: the whole final text; it replaces the field."))
                put("submit", field("boolean", "Press Enter after typing. In a chat this is Send, and it asks the user."))
                put("arguments", openObject("Action arguments: keycode, direction, package, or an intent's action/uri/extras."))
                put("verify", VERIFY_SCHEMA)
                put("optional", field("boolean", "A step that may have nothing to do, like a dialog that does not always appear."))
                put("skipIfVerified", field("boolean", "Check verify first and skip the step when it already holds."))
                put("requiresConfirmation", field("boolean", "Ask the user before this step runs."))
                put("timeoutMs", field("integer", "This step's own wait, 500-30000."))
                put("waitForChange", field("boolean", "Wait for the screen to settle afterwards. Default true for an action."))
                put("tool", field("string", "For action \"call\": the registered device tool to invoke."))
                put("output", field("string", "For action \"call\": capture the result under this name."))
            })
            put("required", JsonArray(listOf(JsonPrimitive("action"))))
            // Open, because an extra key the parser ignores is not worth
            // failing a whole plan over.
            put("additionalProperties", true)
        }

        private val DEFINITION_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("description", "A reusable declarative workflow; no coordinates or observation ids.")
            put("properties", buildJsonObject {
                put("id", field("string", "Stable lowercase id, using letters, digits, - or _."))
                put("package", field("string", "Android package this workflow operates."))
                put("version", field("integer", "Definition format version; use 1."))
                put("description", field("string", "What this workflow does."))
                put("parameters", openObject("Named values supplied when the workflow runs."))
                put("steps", array(PLAN_STEP_SCHEMA, "Actions resolved against the current screen."))
            })
            put("required", JsonArray(listOf("id", "package", "steps").map { JsonPrimitive(it) }))
            put("additionalProperties", true)
        }

        /** One literal tool call, for the older `run_workflow` and `save_workflow`. */
        private val LITERAL_STEP_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("description", "One tool call, replayed as written.")
            put("properties", buildJsonObject {
                put("tool", field("string", "The device tool to call, e.g. tap_node, set_text, key."))
                put("arguments", openObject("That tool's own arguments."))
                put("timeoutMs", field("integer", "This step's budget in milliseconds."))
            })
            put("required", JsonArray(listOf(JsonPrimitive("tool"))))
            put("additionalProperties", true)
        }
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
                "{action, target, text?} - for example steps=$PLAN_EXAMPLE_STEPS. action is " +
                "tap, type_text, scroll, key, wait, observe, open_app, " +
                "open_intent or call; target names the element by \"text\", \"contentDescription\", " +
                "\"resourceId\" or \"class\" - NOT by nodeId, which belongs to one observation. The " +
                "steps are resolved against the screen in front of each one, not replayed, so the plan " +
                "still lands after the keyboard opens or a row moves. Add \"verify\" to a step to say " +
                "what must be true afterwards; without it that step is reported as " +
                "verification=not_requested even when the action ran. Use \"optional\":true for a " +
                "dialog that may not appear. " +
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
            structured = mapOf(
                "steps" to array(PLAN_STEP_SCHEMA, "The actions to run, in order. Objects, not JSON text."),
            ),
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
            "Save, list, describe, or run a declarative workflow. mode=\"save\" takes a " +
                "definition object with id, package, and steps; it validates and stores it without " +
                "running it. mode=\"list\" shows definitions and older saved step lists separately. " +
                "Run a saved definition end to end in ONE call, with no model turn per step. Name the " +
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
                "definition" to "object",
                "params" to "object",
                "outputs" to "object",
                "startAt" to "string",
                "totalBudgetMs" to "integer",
                "screenshotOnFailure" to "boolean",
            ),
            emptyList(),
            structured = mapOf("definition" to DEFINITION_SCHEMA),
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
                structured = mapOf(
                    "steps" to array(LITERAL_STEP_SCHEMA, "The tool calls to replay, in order."),
                ),
            ),
            tool(
                "save_workflow",
                "Legacy format: store literal tool calls for run_workflow. These calls are not " +
                    "loaded by workflow_runner. For a new reusable sequence, prefer " +
                    "workflow_runner(mode=\"save\") with a declarative definition.",
                mapOf(
                    "name" to "string",
                    "package" to "string",
                    "description" to "string",
                    "steps" to "array",
                ),
                listOf("name", "package", "steps"),
                structured = mapOf(
                    "steps" to array(LITERAL_STEP_SCHEMA, "The tool calls that worked, in order."),
                ),
            ),
            tool(
                "list_workflows",
                "List older saved literal step sequences, optionally for one package. " +
                    "workflow_runner(mode=\"list\") shows these separately from declarative definitions. Read-only.",
                mapOf("package" to "string"),
                emptyList(),
            ),
        )

        /**
         * A tool whose arguments are plain scalars, plus any spelled-out
         * schemas in [structured].
         *
         * A bare `{"type":"array"}` is not a description of anything: a client
         * with no `items` renders it as an array of strings, and a model then
         * sends each element as quoted JSON and is refused for it. So every
         * array here carries its `items`.
         */
        fun tool(
            name: String,
            description: String,
            properties: Map<String, String>,
            required: List<String>,
            structured: Map<String, JsonObject> = emptyMap(),
        ): ToolDefinition {
            val props = buildJsonObject {
                for ((key, type) in properties) {
                    val spelled = structured[key]
                    if (spelled != null) {
                        put(key, spelled)
                    } else {
                        put(key, buildJsonObject {
                            put("type", type)
                            // A free-form map, such as workflow_runner's params.
                            if (type == "object") put("additionalProperties", true)
                        })
                    }
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

        private fun array(items: JsonObject, description: String): JsonObject = buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", items)
        }

        private fun field(type: String, description: String): JsonObject = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        /** An object whose own keys are open: a selector's aliases, an intent's extras. */
        private fun openObject(description: String, properties: Map<String, JsonObject> = emptyMap()): JsonObject =
            buildJsonObject {
                put("type", "object")
                put("description", description)
                if (properties.isNotEmpty()) {
                    put("properties", buildJsonObject { properties.forEach { (key, value) -> put(key, value) } })
                }
                put("additionalProperties", true)
            }

    }
}
