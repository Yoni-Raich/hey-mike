package dev.androidagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
) : DeviceToolGateway {

    @Volatile private var revoked = true

    private val engine = WorkflowEngine(
        invokeTool = { name, args -> router().invoke(name, args) },
        store = store,
        isRevoked = { revoked },
    )

    override val definitions: List<ToolDefinition> = TOOL_DEFINITIONS

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
    override fun needsControl(name: String): Boolean = name == "run_workflow"

    /** Steps dispatch through the device backends; this gateway alone operates nothing. */
    override fun deviceBackendLive(): Boolean = false

    override fun statusLine(): String {
        val count = store.all().size
        return "Workflows: " + if (count == 0) "none saved" else "$count saved"
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (revoked) throw IllegalStateException("Run stopped. No device action was performed.")
        return when (name) {
            "run_workflow" -> engine.run(arguments)
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

    private companion object {
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
                for ((key, type) in properties) put(key, buildJsonObject { put("type", type) })
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
