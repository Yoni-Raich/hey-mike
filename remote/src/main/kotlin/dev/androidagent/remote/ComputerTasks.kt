package dev.androidagent.remote

import dev.androidagent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Durable receipt. A dispatch with an uncertain outcome is never replayed. */
data class ComputerTask(
    val id: String,
    val requestId: String,
    val originSessionId: String,
    val computerId: String,
    val project: String,
    val message: String,
    val model: String? = null,
    val sessionId: String? = null,
    val threadId: String? = null,
    /** [AgentTaskStatus.wire]. Read it through [state]. */
    val status: String = AgentTaskStatus.QUEUED.wire,
    val progress: String = "Preparing the computer task",
    val result: String? = null,
    /** Last child activity is sealed too, so source progress survives restart. */
    val activity: String? = null,
) {
    val state: AgentTaskStatus get() = AgentTaskStatus.of(status)
    val terminal: Boolean get() = state.terminal
    // Keep the computer receipt's existing wire name; shared code reads it as STOPPING.
    fun at(state: AgentTaskStatus, progress: String = this.progress): ComputerTask =
        copy(status = if (state == AgentTaskStatus.STOPPING) "cancelling" else state.wire, progress = progress)
    fun toJson(): JsonObject = buildJsonObject {
        put("taskId", id); put("requestId", requestId); put("originSessionId", originSessionId)
        put("kind", "computer_subagent"); put("parentSessionId", originSessionId)
        put("computerId", computerId); put("project", project); put("message", message)
        model?.let { put("model", it) }; sessionId?.let { put("sessionId", it) }
        threadId?.let { put("threadId", it) }; put("status", status); put("progress", progress)
        result?.let { put("result", it) }
        activity?.let { put("activity", it) }
    }
    companion object {
        fun fromJson(j: JsonObject): ComputerTask {
            fun text(k: String) = (j[k] as? JsonPrimitive)?.contentOrNull
            return ComputerTask(text("taskId")!!, text("requestId")!!, text("originSessionId")!!,
                text("computerId")!!, text("project")!!, text("message")!!, text("model"),
                text("sessionId"), text("threadId"), text("status")!!, text("progress")!!, text("result"), text("activity"))
        }
    }
}

/** Uses the ordinary coordinators, including their tool and approval gates. */
interface ComputerTaskRunner {
    val states: StateFlow<Map<String, RunState>>
    val outcomes: StateFlow<Map<String, RunState>>
    fun send(sessionId: String, originSessionId: String, message: String, model: String?, allowed: () -> Boolean): Boolean
    fun stop(sessionId: String)
    fun childrenOf(originSessionId: String): List<String> = emptyList()
}

class RunsComputerTaskRunner(private val runs: AgentRuns) : ComputerTaskRunner {
    override val states get() = runs.sessionStates
    override val outcomes get() = runs.outcomes
    override fun send(sessionId: String, originSessionId: String, message: String, model: String?, allowed: () -> Boolean) =
        runs.sendChild(sessionId, originSessionId, message, model, allowed)
    override fun stop(sessionId: String) = runs.stop(sessionId)
    override fun childrenOf(originSessionId: String) = runs.childrenOf(originSessionId)
}

class ComputerTasks(
    private val store: RemoteStore,
    private val sessions: SessionStore,
    private val scope: CoroutineScope,
    private val runner: () -> ComputerTaskRunner,
    /** Existing SSH, pinned host key, phone account and no automatic install. */
    private val prepare: suspend (String, String) -> Unit,
) {
    private val lock = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    // Stop's callback cannot suspend or take this manager's mutex: AgentRuns
    // calls it inside its dispatch fence. A synchronous mark closes setup races.
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()
    private val originStops = ConcurrentHashMap<String, AtomicLong>()
    private val globalStops = AtomicLong()
    val state: StateFlow<RemoteState> get() = store.state

    init {
        // A process loss may occur after turn/start reached the PC. Keep the
        // retry receipt, but never claim it finished or start another turn.
        store.state.value.tasks.values.filter { !it.terminal }.forEach {
            val recovered = it.at(AgentTaskStatus.UNKNOWN, "App restarted. Remote outcome is unknown; do not retry with a new key.").copy(
                threadId = it.threadId ?: it.sessionId?.let(store::binding)?.threadId)
            store.saveTask(recovered)
        }
        val restored = store.state.value.tasks.values.toList()
        scope.launch { lock.withLock { restored.forEach { task ->
            if (sessions.getSession(task.originSessionId) != null && task.sessionId?.let { sessions.getSession(it) } != null) {
                sessions.setParent(task.sessionId!!, task.originSessionId)
            }
            publish(task)
        } } }
        // Removing a saved computer must not leave its old live coordinator
        // dispatching. Source removal likewise stops setup and its child.
        scope.launch {
            combine(store.state, sessions.sessions) { state, chats ->
                val sources = chats.map { it.id }.toSet()
                state.tasks.values.filter { it.computerId !in state.computers.map { c -> c.id } || it.originSessionId !in sources }
            }.collect { unavailable ->
                lock.withLock {
                    unavailable.forEach { task ->
                        val job = jobs[task.id] ?: return@forEach
                        stopRequested.add(task.id)
                        task.sessionId?.let { runner().stop(it) }
                        job.cancel()
                        val current = store.state.value.tasks[task.id] ?: return@forEach
                        if (!current.terminal) update(current.at(if (current.sessionId == null) AgentTaskStatus.CANCELLED else AgentTaskStatus.UNKNOWN,
                            "Source chat or computer removed; no further local dispatch. Remote outcome is unknown."))
                        else publish(current)
                    }
                }
            }
        }
    }

    suspend fun start(origin: String, requestId: String, computer: RemoteComputer, project: String,
                      message: String, model: String?, allowed: () -> Boolean): ComputerTask = lock.withLock {
        require(requestId.isNotBlank() && requestId.length <= 200) { "Give a stable requestId (up to 200 characters). Reuse it on retries." }
        store.state.value.tasks.values.firstOrNull { it.originSessionId == origin && it.requestId == requestId }?.let {
            require(it.computerId == computer.id && ComputerToolGateway.pathKey(it.project) == ComputerToolGateway.pathKey(project) &&
                it.message == message && it.model == model) { "This requestId already belongs to a different task." }
            return@withLock it
        }
        val stopEpoch = originStops.getOrPut(origin) { AtomicLong() }.get()
        val globalStopEpoch = globalStops.get()
        check(allowed()) { "Run stopped. Nothing was started." }
        check(sessions.getSession(origin) != null) { "The source chat no longer exists." }
        val task = ComputerTask(UUID.randomUUID().toString(), requestId, origin, computer.id, project, message, model)
        store.saveTask(task) // Before creating a session or sending anything.
        try {
            sessions.append(ChatMessage(task.id, origin, "note", display(task), System.currentTimeMillis(), "streaming"))
        } catch (error: Exception) {
            store.saveTask(task.at(if (error is CancellationException) AgentTaskStatus.CANCELLED else AgentTaskStatus.FAILED,
                "Could not prepare the source chat. Nothing was dispatched."))
            throw error
        }
        if (!allowed() || originStops[origin]?.get() != stopEpoch || globalStops.get() != globalStopEpoch) {
            stopRequested.add(task.id)
            val cancelled = task.at(AgentTaskStatus.CANCELLED, "Stopped before dispatch")
            update(cancelled)
            return@withLock cancelled
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(task.id, stopEpoch, globalStopEpoch) }
        jobs[task.id] = job
        job.start()
        task
    }

    fun find(origin: String, taskId: String?, requestId: String?): ComputerTask? =
        store.state.value.tasks.values.firstOrNull {
            it.originSessionId == origin && (if (taskId != null) it.id == taskId else requestId != null && it.requestId == requestId)
        }

    suspend fun cancel(origin: String, taskId: String?, requestId: String?): ComputerTask = lock.withLock {
        val task = find(origin, taskId, requestId) ?: error("No task with that id in this source chat.")
        if (task.state == AgentTaskStatus.UNKNOWN) {
            return@withLock task.copy(progress = "Remote outcome is unknown. No live run can be interrupted here; inspect the computer session. No replacement was started.").also { update(it) }
        }
        if (task.terminal) return@withLock task
        stopRequested.add(task.id)
        // Persist the intent before cancelling setup or invoking interruption.
        val next = task.at(AgentTaskStatus.STOPPING, "Stopping; completed actions are not undone.")
        store.saveTask(next)
        task.sessionId?.let { runner().stop(it) }
        if (task.sessionId == null || task.state == AgentTaskStatus.QUEUED) {
            jobs[task.id]?.cancel()
            update(next.at(AgentTaskStatus.CANCELLED))
        } else update(next)
        store.state.value.tasks.getValue(task.id)
    }

    /** Stop also covers setup jobs that do not own a coordinator yet. */
    fun stopOrigin(origin: String) {
        originStops.getOrPut(origin) { AtomicLong() }.incrementAndGet()
        store.state.value.tasks.values.filter { it.originSessionId == origin && !it.terminal }.forEach { task ->
            stopRequested.add(task.id)
            scope.launch { cancel(origin, task.id, null) }
        }
    }

    /** Covers tasks still looking up their source, before a receipt exists. */
    fun stopAll() {
        globalStops.incrementAndGet()
        store.state.value.tasks.values.map { it.originSessionId }.distinct().forEach(::stopOrigin)
    }

    private suspend fun execute(id: String, stopEpoch: Long, globalStopEpoch: Long) {
        try {
            var task = store.state.value.tasks.getValue(id)
            if (!canDispatch(task) || originStops[task.originSessionId]?.get() != stopEpoch || globalStops.get() != globalStopEpoch) {
                lock.withLock { if (!task.terminal) update(task.at(AgentTaskStatus.CANCELLED, "Stopped before dispatch")) }
                return
            }
            prepare(task.computerId, task.project)
            lock.withLock {
                task = store.state.value.tasks.getValue(id)
                if (!canDispatch(task) || originStops[task.originSessionId]?.get() != stopEpoch ||
                    globalStops.get() != globalStopEpoch || sessions.getSession(task.originSessionId) == null) {
                    if (!task.terminal) update(task.at(AgentTaskStatus.CANCELLED, "Stopped before dispatch"))
                    return
                }
                val session = sessions.createSession()
                sessions.setParent(session.id, task.originSessionId)
                store.bind(session.id, RemoteBinding(task.computerId, task.project))
                store.addProject(task.computerId, task.project)
                sessions.rename(session.id, ComputerToolGateway.folderName(task.project))
                task = task.copy(sessionId = session.id).at(AgentTaskStatus.STARTING, "Starting Codex")
                update(task) // A crash after this point must not dispatch again.
                val dispatched = runner().send(session.id, task.originSessionId, task.message, task.model) {
                    originStops[task.originSessionId]?.get() == stopEpoch && globalStops.get() == globalStopEpoch &&
                        store.state.value.tasks[id]?.let(::canDispatch) == true
                }
                if (!dispatched) {
                    update(task.at(AgentTaskStatus.CANCELLED, "Stopped before dispatch"))
                    return
                }
            }
            val sessionId = task.sessionId!!
            // Observe normal coordinator history and states. No second event
            // reader, no separate approval handler, and no long-held phone lease.
            combine(runner().states, runner().outcomes, sessions.messages(sessionId), store.state) { states, outcomes, messages, _ ->
                Triple(outcomes[sessionId] ?: states[sessionId], messages, outcomes.containsKey(sessionId))
            }.takeWhile { (run, messages, ended) ->
                lock.withLock {
                    val current = store.state.value.tasks.getValue(id)
                    if (current.terminal) return@withLock false
                    // Outcome publication follows history flush, but flow
                    // delivery can race. Read that flushed snapshot at finish.
                    val history = if (ended) sessions.messages(sessionId).first() else messages
                    val latest = history.lastOrNull { it.role in setOf("assistant", "remote_activity", "system") }
                    val status = when {
                        ended -> AgentTaskStatus.ended(run?.outcome)
                        current.state == AgentTaskStatus.STOPPING || id in stopRequested -> AgentTaskStatus.STOPPING
                        run?.waitingForUser == true -> AgentTaskStatus.WAITING_FOR_USER
                        else -> AgentTaskStatus.RUNNING
                    }
                    val next = current.at(status).copy(threadId = store.binding(sessionId)?.threadId,
                        progress = when {
                            ended && run?.stopConfirmed == false -> "Stopped locally; computer interruption could not be confirmed."
                            ended && run?.outcomeUnknown == true -> "Computer connection lost; remote outcome is unknown. No replacement was started."
                            else -> run?.status ?: current.progress
                        },
                        result = if (ended) history.lastOrNull { it.role == "assistant" }?.text
                            ?: history.lastOrNull { it.role == "system" }?.text ?: run?.status else current.result)
                    update(next.copy(activity = latest?.text?.take(2000) ?: current.activity))
                    !ended
                }
            }.collect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lock.withLock {
                val task = store.state.value.tasks[id] ?: return
                if (task.terminal) return
                // A dispatch exception may be a lost reply, never replay it.
                update(task.at(if (task.sessionId == null) AgentTaskStatus.FAILED else AgentTaskStatus.UNKNOWN,
                    error.message ?: "Computer task failed"))
            }
        } finally {
            withContext(NonCancellable) { lock.withLock { jobs.remove(id) } }
        }
    }

    private fun canDispatch(task: ComputerTask): Boolean =
        !task.terminal && task.state != AgentTaskStatus.STOPPING && task.id !in stopRequested && store.computer(task.computerId) != null

    private suspend fun update(task: ComputerTask) {
        val saved = if (store.state.value.tasks[task.id] != task) store.saveTask(task) else task
        publish(saved)
    }

    private suspend fun publish(task: ComputerTask) {
        if (sessions.getSession(task.originSessionId) == null) return
        val text = display(task)
        val state = if (task.terminal) "complete" else "streaming"
        val existing = sessions.messages(task.originSessionId).first().firstOrNull { it.id == task.id }
        if (existing == null) sessions.append(ChatMessage(task.id, task.originSessionId, "note", text, System.currentTimeMillis(), state))
        else if (existing.text != text || existing.state != state) sessions.updateMessage(task.id, text, state)
    }

    private fun display(task: ComputerTask): String =
        "Computer subagent · ${store.computer(task.computerId)?.label ?: task.computerId}\n" +
            "${task.project}\n${task.status}: ${task.progress}" +
            (task.result ?: task.activity)?.let { "\n$it" }.orEmpty()

    /** Surface the child's existing approval/question on the source screen. */
    fun sourceState(origin: String, states: Map<String, RunState>): RunState {
        val raw = states[origin] ?: RunState(sessionId = origin)
        val own = raw.copy(approval = raw.pendingApproval, question = raw.pendingQuestion)
        val children = store.state.value.tasks.values.filter { it.originSessionId == origin && !it.terminal }
        val linked = runner().childrenOf(origin)
        val runs = children.sortedBy { task -> linked.indexOf(task.sessionId).takeIf { it >= 0 } ?: Int.MAX_VALUE }
            .mapNotNull { it.sessionId?.let(states::get) }.filter { it.active }
        val waiting = runs.firstOrNull { it.waitingForUser }
        if (waiting != null && !own.waitingForUser && own.phase != RunPhase.STOPPING) return own.copy(
            phase = if (own.active) own.phase else RunPhase.THINKING,
            status = waiting.status, approval = waiting.pendingApproval, question = waiting.pendingQuestion, delegated = !own.active)
        if (own.active || children.isEmpty()) return own
        return own.copy(phase = RunPhase.THINKING, status = children.first().progress, delegated = true)
    }
}
