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

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What [SessionRunQueue] needs from whatever runs turns: one coordinator, or [AgentRuns]. */
interface TurnRunner {
    /** Emits whenever a run may have ended, so a waiting turn may now start. */
    val freed: Flow<*>
    /** A turn for a chat that is not running could start now. */
    fun canStart(): Boolean
    /** The phase of [sessionId]'s run, or null when that chat is not running. */
    fun phaseOf(sessionId: String): RunPhase?
    fun steer(sessionId: String, prompt: String)
    fun send(turn: QueuedTurn) = send(turn.sessionId, turn.prompt, turn.imagePaths.map(::File), turn.model,
        turn.effort, turn.skill, turn.planMode, turn.engine)
    fun send(
        sessionId: String,
        prompt: String,
        images: List<File> = emptyList(),
        model: String? = null,
        reasoningEffort: String? = null,
        skill: AgentSkill? = null,
        planMode: Boolean = false,
        /** The engine this turn runs on; the chat moves to it first. Null keeps the chat's own. */
        engineKind: EngineKind? = null,
    )
}

/**
 * Chats running at once, as many as are started.
 *
 * Each running chat has a coordinator of its own, made when no idle one is
 * left, so a new chat started while others work starts straight away instead
 * of waiting in the queue. Codex runs any number of threads; the only thing
 * the chats share is the phone's one screen. [DeviceLease] gives it to one run
 * at a time: for the length of one call for a tool that leaves the screen
 * alone, and from the first read or tap to the run's end for one that does.
 * A chat that only thinks, or works on a computer, never waits for it.
 *
 * Engine events are routed here, by thread, to the one coordinator that owns
 * it. A coordinator collecting them itself would refuse every other chat's
 * tool calls and approvals as not its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuns(
    scope: CoroutineScope,
    engine: AgentEngine,
    private val onStopSession: (String?) -> Unit = {},
    private val create: (PhoneShare) -> AgentCoordinator,
) : TurnRunner {
    val lease = DeviceLease()
    private val slotList = MutableStateFlow<List<AgentCoordinator>>(emptyList())
    /** Every coordinator made so far; an idle one is reused before another is made. */
    val slots: List<AgentCoordinator> get() = slotList.value
    private var voiceSlot: AgentCoordinator? = null
    /** Link order is also the order in which the source shows its children's gates. */
    private val childParents = linkedMapOf<String, String>()
    private val dispatchedChildren = mutableSetOf<String>()

    /** An app-owned direct task can expose its gates and Stop in its source chat. */
    fun linkChild(child: String, parent: String) = synchronized(this) {
        require(child.isNotBlank() && parent.isNotBlank()) { "Child and source session IDs are required." }
        require(child != parent && child !in ancestorsOf(parent)) { "A child link cannot form a cycle." }
        require(childParents[child] == null || childParents[child] == parent) {
            "A child chat already belongs to another source chat."
        }
        childParents[child] = parent
    }

    /** A deleted parent detaches its surviving chats in the session store. */
    fun unlinkChild(child: String) = synchronized(this) { childParents.remove(child); Unit }

    fun childrenOf(sessionId: String): List<String> = synchronized(this) {
        childParents.filterValues { it == sessionId }.keys.toList()
    }

    fun ancestorsOf(sessionId: String): List<String> = synchronized(this) {
        buildList { var next = childParents[sessionId]; while (next != null && next !in this) { add(next); next = childParents[next] } }
    }

    fun family(sessionId: String): List<String> = synchronized(this) {
        buildList { add(sessionId); var index = 0; while (index < size) { addAll(childrenOf(get(index++))) } }
    }

    /** Reads the coordinators directly; the combined flow can lag a synchronous dispatch. */
    fun outcomeOf(sessionId: String): RunState? = slots.firstNotNullOfOrNull { it.outcomes.value[sessionId] }

    fun sourceState(sessionId: String): RunState {
        val own = stateOf(sessionId) ?: RunState(sessionId = sessionId)
        val children = family(sessionId).drop(1).mapNotNull(::stateOf)
        val waiting = children.firstOrNull { it.approval != null || it.question != null }
        if (waiting != null && own.approval == null && own.question == null) return own.copy(
            phase = if (own.active) own.phase else RunPhase.THINKING, status = waiting.status,
            approval = waiting.approval, question = waiting.question, delegated = !own.active)
        return if (!own.active && children.isNotEmpty()) own.copy(phase = RunPhase.THINKING, status = children.first().status, delegated = true) else own
    }

    /** The gate shown in the source chat: its own first, then its oldest waiting child. */
    private fun waitingSlot(sessionId: String): AgentCoordinator? = family(sessionId).firstNotNullOfOrNull { id ->
        slotFor(id)?.takeIf { it.state.value.approval != null || it.state.value.question != null }
    }

    init { slotList.value = listOf(newSlot()) }

    private fun newSlot(): AgentCoordinator {
        lateinit var made: AgentCoordinator
        made = create(PhoneShare(lease, routed = true, othersActive = { slots.any { it !== made && it.state.value.active } }))
        return made
    }

    /** An idle coordinator, or a new one. Chats are not capped. */
    private fun freeSlot(): AgentCoordinator = synchronized(this) {
        slots.firstOrNull { it.canStart() } ?: newSlot().also { slotList.value = slots + it }
    }

    private inline fun <reified T, R> eachSlot(crossinline pick: (AgentCoordinator) -> Flow<T>, crossinline fold: (List<T>) -> R): Flow<R> =
        slotList.flatMapLatest { list -> combine(list.map { pick(it) }) { fold(it.toList()) } }

    /** Every chat's latest run state, by chat. */
    val sessionStates: StateFlow<Map<String, RunState>> = eachSlot({ it.state }) { states ->
        // An active run wins over the finished one a slot last held for the same chat.
        states.filter { it.sessionId != null }.sortedBy { it.active }.associateBy { it.sessionId!! }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val outcomes: StateFlow<Map<String, RunState>> = eachSlot({ it.outcomes }) { maps ->
        maps.fold(emptyMap<String, RunState>()) { all, one -> all + one }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * One state for what only asks whether Mike is busy: the notification, the
     * assistant panel, voice. The run on the phone first, then one waiting on
     * the user, then any other.
     */
    val state: StateFlow<RunState> = eachSlot({ it.state }) { states ->
        val active = states.filter { it.active }
        active.firstOrNull { it.controlling } ?: active.firstOrNull { it.approval != null }
            ?: active.firstOrNull { it.question != null } ?: active.firstOrNull() ?: RunState()
    }.stateIn(scope, SharingStarted.Eagerly, RunState())

    /** No chat is running. */
    val available: StateFlow<Boolean> = state.map { !it.active }.stateIn(scope, SharingStarted.Eagerly, true)

    override val freed: Flow<*> = slotList.flatMapLatest { list -> list.map { it.available }.merge() }

    val metrics: StateFlow<Map<String, RunMetrics>> = eachSlot({ it.metrics }) { maps ->
        maps.fold(emptyMap<String, RunMetrics>()) { all, one -> all + one }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch { engine.events.collect { route(it) } }
    }

    /** Some chat is running, read now rather than through [state]. */
    val anyActive: Boolean get() = slots.any { it.state.value.active }

    private fun slotFor(sessionId: String): AgentCoordinator? =
        slots.firstOrNull { it.state.value.active && it.state.value.sessionId == sessionId }

    /** This chat's run, or null when it is not running. */
    fun stateOf(sessionId: String): RunState? = slotFor(sessionId)?.state?.value

    /** Always: a chat that is not running can start beside any number of others. */
    override fun canStart(): Boolean = true
    override fun phaseOf(sessionId: String): RunPhase? = slotFor(sessionId)?.state?.value?.phase

    override fun send(turn: QueuedTurn) {
        if (turn.prompt.isBlank() && turn.imagePaths.isEmpty()) return
        synchronized(this) {
            val active = slotFor(turn.sessionId)
            val slot = active ?: freeSlot()
            if (active == null) slots.forEach { it.clearOutcome(turn.sessionId) }
            slot.send(turn)
        }
    }

    override fun send(
        sessionId: String,
        prompt: String,
        images: List<File>,
        model: String?,
        reasoningEffort: String?,
        skill: AgentSkill?,
        planMode: Boolean,
        engineKind: EngineKind?,
    ) {
        if (prompt.isBlank() && images.isEmpty()) return
        synchronized(this) {
            val active = slotFor(sessionId)
            val slot = active ?: freeSlot()
            if (active == null) slots.forEach { it.clearOutcome(sessionId) }
            // Reserve the slot before releasing the same lock another sender uses.
            // A chat already running is steered by its own coordinator.
            slot.send(sessionId, prompt, images, model, reasoningEffort, skill, planMode, engineKind)
        }
    }

    /**
     * Link and dispatch together with Stop. The app-owned receipt supplies
     * [allowed], so a Stop during computer setup can refuse dispatch even
     * before the child has a running coordinator.
     */
    fun sendChild(
        sessionId: String,
        originSessionId: String,
        prompt: String,
        model: String? = null,
        allowed: () -> Boolean = { true },
        engineKind: EngineKind = EngineKind.CODEX,
        reasoningEffort: String? = null,
    ): Boolean = synchronized(this) {
        if (!allowed()) return@synchronized false
        require(prompt.isNotBlank()) { "A computer task needs a message." }
        linkChild(sessionId, originSessionId)
        // The receipt owns the exact-once boundary; a repeated dispatch must
        // not become steering input on an already running child.
        if (sessionId in dispatchedChildren || slotFor(sessionId) != null) return@synchronized false
        dispatchedChildren += sessionId
        send(sessionId, prompt, model = model, reasoningEffort = reasoningEffort, engineKind = engineKind)
        true
    }

    override fun steer(sessionId: String, prompt: String) { slotFor(sessionId)?.steer(prompt) }

    fun stop(sessionId: String) = synchronized(this) {
        try { onStopSession(sessionId) }
        finally { family(sessionId).forEach { slotFor(it)?.stop() } }
    }

    /**
     * Stop every chat, as the notification's and the floating card's Stop do.
     * Stopping only the chat on the phone would hand it straight to the next
     * chat waiting for it, which is not what pressing Stop on it asks for.
     */
    fun stop() = synchronized(this) {
        try { onStopSession(null) }
        finally { slots.forEach { it.stop() } }
    }

    /** Words typed on the floating card, for the chat it shows: the one on the phone. */
    fun steerPhone(prompt: String) {
        (slots.firstOrNull { lease.isHeldBy(it) } ?: slots.singleOrNull { it.state.value.active })?.steer(prompt)
    }

    fun approve(requestId: String, allow: Boolean, scope: ApprovalScope = ApprovalScope.ONCE) {
        slots.firstOrNull { it.state.value.approval?.requestId == requestId }?.approve(requestId, allow, scope)
    }

    /** A spoken or typed yes or no, for the approval waiting in [sessionId], or in any chat. */
    fun answerApprovalByReply(reply: String, record: Boolean = true, sessionId: String? = null): Boolean {
        val slot = if (sessionId == null) slots.firstOrNull { it.state.value.approval != null }
            else waitingSlot(sessionId)?.takeIf { it.state.value.approval != null }
        if (slot == null) return false
        return slot.answerApprovalByReply(reply, record)
    }

    /** The user's answer to an `ask_user` question, or null for "not answering". */
    fun answerQuestion(questionId: String, reply: String?): Boolean =
        slots.firstOrNull { it.state.value.question?.id == questionId }?.answerQuestion(questionId, reply) ?: false

    /** What the user typed in [sessionId] while a question waits there is its answer. */
    fun answerQuestionByReply(reply: String, sessionId: String): Boolean =
        waitingSlot(sessionId)?.takeIf { it.state.value.question != null }
            ?.answerQuestionByReply(reply) ?: false

    fun beginVoice(sessionId: String, threadId: String, workspace: File) {
        val slot = freeSlot()
        slot.beginVoice(sessionId, threadId, workspace)
        voiceSlot = slot
    }

    fun endVoice() { voiceSlot?.endVoice() }

    suspend fun <T> runAutomation(
        label: String,
        workspace: File,
        waitMs: Long = AgentCoordinator.DEFAULT_AUTOMATION_WAIT_MS,
        block: suspend () -> T,
    ): T? {
        // A coordinator of its own, so it waits only for the phone, never for a chat that is thinking.
        return freeSlot().runAutomation(label, workspace, waitMs, block)
    }

    // Asked from inside a tool call, so by the run holding the phone.
    private fun deviceOwner(): AgentCoordinator = slots.firstOrNull { lease.isHeldBy(it) } ?: slots.first()

    suspend fun authorizeLocalIntent(request: LocalIntentRequest, dispatch: () -> ToolResult): ToolResult =
        deviceOwner().authorizeLocalIntent(request, dispatch)

    suspend fun authorizeSend(request: SendRequest, dispatch: suspend () -> ToolResult): ToolResult =
        deviceOwner().authorizeSend(request, dispatch)

    suspend fun authorizeWorkflowStep(request: WorkflowConfirmation): WorkflowConfirmationOutcome =
        deviceOwner().authorizeWorkflowStep(request)

    private suspend fun route(event: EngineEvent) {
        val thread = event.threadIdOrNull()
        if (thread.isNullOrBlank()) {
            slots.forEach { it.deliver(event) }
            return
        }
        // A thread no run owns goes to one coordinator, which refuses its tool
        // calls and approvals: nothing is waiting for them.
        (slots.firstOrNull { it.ownsThread(thread) } ?: slots.first()).deliver(event)
    }
}

internal fun EngineEvent.threadIdOrNull(): String? = when (this) {
    is EngineEvent.TurnStarted -> threadId
    is EngineEvent.TextDelta -> threadId
    is EngineEvent.ToolCall -> threadId
    is EngineEvent.TurnFinished -> threadId
    is EngineEvent.Approval -> threadId
    is EngineEvent.Failure -> threadId
    is EngineEvent.MessageCompleted -> threadId
    is EngineEvent.GeneratedImage -> threadId
    is EngineEvent.Activity -> threadId
    is EngineEvent.ItemActivity -> threadId
    is EngineEvent.UsageChanged, is EngineEvent.AccountChanged, EngineEvent.SkillsChanged -> null
}
