package dev.androidagent.app

import dev.androidagent.core.*
import dev.androidagent.remote.*
import kotlinx.serialization.json.*

/** App-owned destination and model checks, shared by typed and realtime child creation. */
class SessionAgentSetup(
    private val sessions: SessionStore,
    private val hub: RemoteHub,
    private val phoneCodex: AgentEngine,
    private val phoneClaude: AgentEngine,
    private val checkFolder: suspend (String, String) -> Unit = { id, cwd -> hub.listFolders(id, cwd) },
) {
    private val targets = ChildSessionTargets(hub.store) { hub.threads.value.mapValues { (_, chats) -> chats.map { it.cwd } } }

    suspend fun prepare(task: SessionAgentTask) {
        val target = targets.resolve(task.parentSessionId, task.computer, task.project)
        // Keep the requested destination even when its connection or model check fails.
        // Opening this ordinary child must never silently send a retry to the phone.
        target?.let { hub.store.bind(task.sessionId!!, it) }
        if (target != null && (task.computer != null || task.project != null)) {
            checkFolder(target.computerId, target.cwd) // Read only; never create a missing folder or install a runtime.
        }
        if (task.model != null) {
            val catalog = catalog(target, task.engine)
            val model = catalog.firstOrNull { it.id == task.model }
                ?: error("This target does not offer model '${task.model}'. Use session_agents options for its exact model IDs.")
            require(task.reasoningEffort == null || model.reasoningEfforts.any { it.value == task.reasoningEffort }) {
                "Model '${task.model}' does not offer '${task.reasoningEffort}'. Use session_agents options for supported levels."
            }
        }
        target?.let { hub.store.addProject(it.computerId, it.cwd) }
    }

    suspend fun options(origin: String, args: JsonObject): JsonObject {
        fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val target = targets.resolve(origin, text("computer"), text("project"))
        val engine = text("engine")?.let { name -> EngineKind.entries.firstOrNull { it.name.equals(name, true) }
            ?: error("Use codex or claude.") } ?: sessions.getSession(origin)?.engine ?: EngineKind.CODEX
        val offered = catalog(target, engine)
        return buildJsonObject {
            put("engine", engine.name.lowercase())
            put("computer", target?.computerId ?: "phone")
            target?.let { put("project", it.cwd) }
            put("models", buildJsonArray { offered.forEach { model -> add(buildJsonObject {
                put("id", model.id); put("name", model.displayName)
                put("reasoningEfforts", buildJsonArray { model.reasoningEfforts.forEach { add(it.value) } })
                model.defaultReasoningEffort?.let { put("defaultReasoningEffort", it) }
            }) } })
        }
    }

    private suspend fun catalog(target: RemoteBinding?, kind: EngineKind): List<AgentModel> {
        val engine: AgentEngine = if (target == null) {
            if (kind == EngineKind.CLAUDE) phoneClaude else phoneCodex
        } else if (kind == EngineKind.CLAUDE) hub.claude(target.computerId) else hub.engine(target.computerId)
        return engine.modelCatalog()
    }
}
