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
)

/**
 * FIFO sessions share one device owner. Restored work requires explicit Resume.
 *
 * Pausing (Stop, voice) holds the work that was already waiting. A message the
 * user submits afterwards is a request to run now, so it runs as soon as the
 * device is free even while the queue is paused; the held work still waits
 * for Resume.
 */
class SessionRunQueue(
    private val scope: CoroutineScope,
    private val coordinator: AgentCoordinator,
    private val store: SessionStore,
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
            coordinator.available.collect { if (it) dispatch() }
        }
    }

    suspend fun submit(request: QueuedTurn) {
        loaded.join()
        lock.withLock {
            check(store.getSession(request.sessionId) != null) { "Chat no longer exists." }
            val active = coordinator.state.value
            if (active.active && active.sessionId == request.sessionId) {
                check(active.phase != RunPhase.STARTING && active.phase != RunPhase.STOPPING) { "Wait for the current turn to start or stop." }
                check(request.imagePaths.isEmpty()) { "Send attachments after this run finishes." }
                coordinator.steer(request.prompt)
                return
            }
            check(pending.value.size < 32) { "The queue is full. Cancel a queued task first." }
            submittedNow += request.id
            save(pending.value + request)
        }
        dispatch()
    }

    /** Hold everything waiting now; only turns submitted after this may start until [resume]. */
    fun pause() {
        submittedNow.clear()
        pausedState.value = true
    }
    suspend fun resume() { loaded.join(); pausedState.value = false; dispatch() }
    suspend fun cancel(id: String) { loaded.join(); lock.withLock { save(pending.value.filterNot { it.id == id }) } }
    suspend fun cancelSession(id: String) { loaded.join(); lock.withLock { save(pending.value.filterNot { it.sessionId == id }) } }

    private suspend fun save(value: List<QueuedTurn>) { store.saveQueuedTurns(value); pending.value = value }

    private suspend fun dispatch() = lock.withLock {
        if (!coordinator.available.value || coordinator.state.value.active) return@withLock
        // While paused, only turns submitted since the pause may start.
        val next = if (pausedState.value) {
            pending.value.firstOrNull { it.id in submittedNow }
        } else {
            pending.value.firstOrNull()
        } ?: return@withLock
        // Dequeue durably before starting, so a process crash cannot replay side effects.
        save(pending.value - next)
        submittedNow -= next.id
        coordinator.send(next.sessionId, next.prompt, next.imagePaths.map(::File), next.model, next.effort, next.skill, next.planMode)
    }
}
