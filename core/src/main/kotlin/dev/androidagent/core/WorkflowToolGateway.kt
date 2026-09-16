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
        if (library == null) TOOL_DEFINITIONS else TOOL_DEFINITIONS + RUNNER_DEFINITION

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
    override fun needsControl(name: String): Boolean = name == "run_workflow" || name == "workflow_runner"

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
