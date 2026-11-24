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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class QueuedTurn(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val prompt: String,
    val imagePaths: List<String> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val skill: AgentSkill? = null,
    /** Run in plan mode: agree a plan before acting. */
    val planMode: Boolean = false,
    /**
     * The engine [model] belongs to. The chat moves to it before the turn
     * runs, so a model picked for one engine is never sent to the other.
     * Null keeps the chat's own engine.
     */
    val engine: EngineKind? = null,
    /** The user's original text when [prompt] also carries app-supplied context. */
    val displayPrompt: String? = null,
    /**
     * Epoch millis after which this turn is stale and must not start.
     *
     * Null for anything a person just sent: they are waiting for it, however
     * long the queue takes. A turn a rule queued is different — "post this at
     * 19:00" that finally reaches the device at 23:40 is not a late success,
     * it is the wrong action — so an automation sets a deadline and the queue
     * drops the turn rather than running it long after its moment.
     */
    val validUntil: Long? = null,
    /** App-supplied child context must never masquerade as the user's words or answer a gate. */
    val agentContext: Boolean = false,
    /** Recovery work loses permission after Stop or a restart; it must not be replayed. */
    val requiresLiveGuard: Boolean = false,
    @kotlinx.serialization.Transient val dispatchGuard: (() -> Boolean)? = null,
)

/**
 * Turns wait here only while every run is busy, or while their own chat is
 * still running; a chat that is free starts at once, beside the others.
 * FIFO otherwise. Restored work requires explicit Resume.
 *
 * Pausing (Stop, voice) holds the work that was already waiting. A message the
 * user submits afterwards is a request to run now, so it runs as soon as the
 * device is free even while the queue is paused; the held work still waits
 * for Resume.
 */
class SessionRunQueue(
    private val scope: CoroutineScope,
    private val coordinator: TurnRunner,
    private val store: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told about a turn dropped for being stale, so it is reported rather than vanishing. */
    private val onExpired: (QueuedTurn) -> Unit = {},
) {
    private val lock = Mutex()
    private val pending = MutableStateFlow<List<QueuedTurn>>(emptyList())
    val turns = pending.asStateFlow()
    private val pausedState = MutableStateFlow(false)
    val paused = pausedState.asStateFlow()

    // Turns submitted since the last pause, which that pause does not hold
    // back. Not persisted: after a restart everything waits for Resume again.
    private val submittedNow: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val loaded = scope.launch {
        lock.withLock {
            pending.value = store.loadQueuedTurns()
            if (pending.value.isNotEmpty()) pausedState.value = true
        }
    }

    init {
        scope.launch {
            loaded.join()
            coordinator.freed.collect { dispatch() }
        }
    }

    suspend fun submit(request: QueuedTurn) {
        loaded.join()
        lock.withLock {
            if (!request.dispatchAllowed()) return
            check(store.getSession(request.sessionId) != null) { "Chat no longer exists." }
            val phase = coordinator.phaseOf(request.sessionId)
            if (phase != null && !request.requiresLiveGuard) {
                check(phase != RunPhase.STARTING && phase != RunPhase.STOPPING) { "Wait for the current turn to start or stop." }
                check(request.imagePaths.isEmpty()) { "Send attachments after this run finishes." }
                coordinator.steer(request.sessionId, request.prompt)
                return
            }
            check(pending.value.size < MAX_QUEUED) { "The queue is full. Cancel a queued task first." }
            submittedNow += request.id
            save(pending.value + request)
        }
        dispatch()
    }

    /**
     * Queue [request] as a turn of its own, even while its chat is running: it
     * starts when that run ends, never as steering input for it. Steering is
     * [submit]'s job. The user asked for this turn after any earlier pause, so
     * that pause does not hold it; Stop on the run it waits for does.
     *
     * [seenPauses] is [pauses] when the user tapped Queue. Preparing the turn
     * can take a while (a file copied to a computer), and a Stop pressed
     * meanwhile must hold it too, not let it start the moment the run ends.
     */
    suspend fun enqueue(request: QueuedTurn, seenPauses: Long = pauses) {
        loaded.join()
        lock.withLock {
            check(request.dispatchAllowed()) { "This task can no longer start." }
            check(request.prompt.isNotBlank() || request.imagePaths.isNotEmpty()) { "Type a message to queue." }
            check(store.getSession(request.sessionId) != null) { "Chat no longer exists." }
            check(pending.value.size < MAX_QUEUED) { "The queue is full. Cancel a queued task first." }
            if (seenPauses == pauses) submittedNow += request.id
            save(pending.value + request)
        }
        dispatch()
    }

    /**
     * Run [block] only while [sessionId] is not running and has nothing queued,
     * and start no queued turn until it returns. A model picked for the next
     * message, on the other engine, moves the chat with this: never under a
     * running turn, never ahead of a turn that was queued with its own model.
     * False when the chat was busy and [block] did not run.
     */
    suspend fun ifIdle(sessionId: String, block: suspend () -> Unit): Boolean {
        loaded.join()
        return lock.withLock {
            if (coordinator.phaseOf(sessionId) != null || pending.value.any { it.sessionId == sessionId }) return@withLock false
            block()
            true
        }
    }

    private val pauseCount = java.util.concurrent.atomic.AtomicLong()
    /** How many times the queue was paused; see [enqueue]. */
    val pauses: Long get() = pauseCount.get()

    /** Hold everything waiting now; only turns submitted after this may start until [resume]. */
    fun pause() {
        pauseCount.incrementAndGet()
        submittedNow.clear()
        pausedState.value = true
    }
    suspend fun resume() { loaded.join(); pausedState.value = false; dispatch() }
    suspend fun cancel(id: String) { loaded.join(); lock.withLock { save(pending.value.filterNot { it.id == id }) } }
    suspend fun cancelSession(id: String) { loaded.join(); lock.withLock { save(pending.value.filterNot { it.sessionId == id }) } }

    private suspend fun save(value: List<QueuedTurn>) { store.saveQueuedTurns(value); pending.value = value }

    private suspend fun dispatch() = lock.withLock {
        // One turn per free run, until the runs or the startable turns run out.
        val refused = mutableSetOf<String>()
        while (coordinator.canStart()) {
            // Stale turns are dropped before anything is chosen, so an expired one
            // at the head cannot hold up the turn behind it.
            val now = clock()
            val permitted = pending.value.filter { it.dispatchAllowed() }
            if (permitted.size != pending.value.size) save(permitted)
            val fresh = permitted.filter { it.validUntil == null || it.validUntil >= now }
            if (fresh.size != pending.value.size) {
                val dropped = pending.value - fresh.toSet()
                save(fresh)
                dropped.forEach { onExpired(it) }
            }
            // While paused, only turns submitted since the pause may start. A
            // chat still running keeps its next turn until that run ends.
            val next = fresh.firstOrNull { turn ->
                turn.sessionId !in refused && (!pausedState.value || turn.id in submittedNow) &&
                    coordinator.phaseOf(turn.sessionId) == null
            } ?: return@withLock
            // Dequeue durably before starting, so a process crash cannot replay side effects.
            val at = pending.value.indexOf(next)
            save(pending.value - next)
            if (!next.dispatchAllowed()) { submittedNow -= next.id; continue }
            if (coordinator.start(next)) { submittedNow -= next.id; continue }
            // Its chat started some other way since it was chosen (a child's
            // update, a recovery). The turn goes back where it was and waits
            // for that run, instead of joining it as an instruction.
            save(pending.value.toMutableList().apply { add(at.coerceIn(0, size), next) })
            refused += next.sessionId
        }
    }

    companion object {
        const val MAX_QUEUED = 32
    }
}

internal fun QueuedTurn.dispatchAllowed(): Boolean = !requiresLiveGuard || dispatchGuard?.invoke() == true
