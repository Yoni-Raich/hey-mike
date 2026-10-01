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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What [SessionRunQueue] needs from whatever runs turns: one coordinator, or [AgentRuns]. */
interface TurnRunner {
    /** Turns true whenever a turn may have become able to start. */
    val available: StateFlow<Boolean>
    /** A turn for a chat that is not running could start now. */
    fun canStart(): Boolean
    /** The phase of [sessionId]'s run, or null when that chat is not running. */
    fun phaseOf(sessionId: String): RunPhase?
    fun steer(sessionId: String, prompt: String)
    fun send(
        sessionId: String,
        prompt: String,
        images: List<File> = emptyList(),
        model: String? = null,
        reasoningEffort: String? = null,
        skill: AgentSkill? = null,
        planMode: Boolean = false,
    )
}

/**
 * Several chats running at once.
 *
 * Each running chat has a coordinator of its own, so a new chat started while
 * another works starts straight away instead of waiting in the queue. The
 * phone itself is still one screen: [DeviceLease] gives it to one run at a
 * time, from that run's first tool call to its end. A chat that only thinks,
 * or one whose agent works on a computer, never waits for it.
 *
 * Engine events are routed here, by thread, to the one coordinator that owns
 * it. A coordinator collecting them itself would refuse every other chat's
 * tool calls and approvals as not its own.
 */
class AgentRuns(
    scope: CoroutineScope,
    engine: AgentEngine,
    size: Int = DEFAULT_PARALLEL_RUNS,
    create: (PhoneShare) -> AgentCoordinator,
) : TurnRunner {
    val lease = DeviceLease()
    val slots: List<AgentCoordinator> = List(size.coerceAtLeast(1)) { index ->
        create(PhoneShare(lease, routed = true, othersActive = { othersActive(index) }))
    }
    private var voiceSlot: AgentCoordinator? = null

    /** Every running chat's state, by chat. */
    val sessionStates: StateFlow<Map<String, RunState>> = combine(slots.map { it.state }) { states ->
        // An active run wins over the finished one a slot last held for the same chat.
        states.filter { it.sessionId != null }.sortedBy { it.active }.associateBy { it.sessionId!! }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * One state for what only asks whether Mike is busy: the notification, the
     * assistant panel, voice. The run on the phone first, then one waiting on
     * the user, then any other.
     */
    val state: StateFlow<RunState> = combine(slots.map { it.state }) { states ->
        val active = states.filter { it.active }
        active.firstOrNull { it.controlling } ?: active.firstOrNull { it.approval != null } ?: active.firstOrNull() ?: RunState()
    }.stateIn(scope, SharingStarted.Eagerly, RunState())

    override val available: StateFlow<Boolean> = combine(slots.map { it.available }) { free -> free.any { it } }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val metrics: StateFlow<Map<String, RunMetrics>> = combine(slots.map { it.metrics }) { maps ->
        maps.fold(emptyMap<String, RunMetrics>()) { all, one -> all + one }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch { engine.events.collect { route(it) } }
    }

    /** Some chat is running, read now rather than through [state]. */
    val anyActive: Boolean get() = slots.any { it.state.value.active }

    private fun othersActive(index: Int): Boolean = slots.withIndex().any { (i, slot) -> i != index && slot.state.value.active }

    private fun slotFor(sessionId: String): AgentCoordinator? =
        slots.firstOrNull { it.state.value.active && it.state.value.sessionId == sessionId }

    /** This chat's run, or null when it is not running. */
    fun stateOf(sessionId: String): RunState? = slotFor(sessionId)?.state?.value

    override fun canStart(): Boolean = slots.any { it.canStart() }
    override fun phaseOf(sessionId: String): RunPhase? = slotFor(sessionId)?.state?.value?.phase

    override fun send(
        sessionId: String,
        prompt: String,
        images: List<File>,
        model: String?,
        reasoningEffort: String?,
        skill: AgentSkill?,
        planMode: Boolean,
    ) {
        val slot = synchronized(this) { slotFor(sessionId) ?: slots.firstOrNull { it.canStart() } } ?: return
        // A chat already running is steered by its own coordinator.
        slot.send(sessionId, prompt, images, model, reasoningEffort, skill, planMode)
    }

    override fun steer(sessionId: String, prompt: String) { slotFor(sessionId)?.steer(prompt) }

    fun stop(sessionId: String) { slotFor(sessionId)?.stop() }

    /**
     * Stop every chat, as the notification's and the floating card's Stop do.
     * Stopping only the chat on the phone would hand it straight to the next
     * chat waiting for it, which is not what pressing Stop on it asks for.
     */
    fun stop() { slots.forEach { it.stop() } }

    /** Words typed on the floating card, for the chat it shows: the one on the phone. */
    fun steerPhone(prompt: String) {
        (slots.firstOrNull { lease.isHeldBy(it) } ?: slots.singleOrNull { it.state.value.active })?.steer(prompt)
    }

    fun approve(requestId: String, allow: Boolean, scope: ApprovalScope = ApprovalScope.ONCE) {
        slots.firstOrNull { it.state.value.approval?.requestId == requestId }?.approve(requestId, allow, scope)
    }

    /** A spoken or typed yes or no, for the approval waiting in [sessionId], or in any chat. */
    fun answerApprovalByReply(reply: String, record: Boolean = true, sessionId: String? = null): Boolean {
        val slot = slots.firstOrNull { slot ->
            slot.state.value.approval != null && (sessionId == null || slot.state.value.sessionId == sessionId)
        } ?: return false
        return slot.answerApprovalByReply(reply, record)
    }

    fun beginVoice(sessionId: String, threadId: String, workspace: File) {
        val slot = synchronized(this) {
            slots.firstOrNull { it.canStart() } ?: error("Another agent run is already active.")
        }
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
        val slot = synchronized(this) { slots.firstOrNull { it.canStart() } }
            ?: run {
                withTimeoutOrNull(waitMs) { available.first { it } }
                synchronized(this) { slots.firstOrNull { it.canStart() } }
            }
            ?: return null
        return slot.runAutomation(label, workspace, waitMs, block)
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

    companion object {
        /** Chats that may run at once. Each holds a Codex turn open. */
        const val DEFAULT_PARALLEL_RUNS = 3
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
