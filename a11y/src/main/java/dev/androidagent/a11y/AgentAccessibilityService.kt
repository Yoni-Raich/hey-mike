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

package dev.androidagent.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide handle on the live accessibility service.
 *
 * The system owns the service instance, so the gateway cannot hold one. It
 * resolves through here on every call instead, which is also what makes
 * "the user just switched it off" an ordinary, recoverable state.
 *
 * A [StateFlow] rather than a plain field because the UI has to react to
 * connect and disconnect, and because a run in flight must re-arm a service
 * instance that reconnected under it.
 */
object A11yServiceHandle {
    private val state = MutableStateFlow<AgentAccessibilityService?>(null)

    val service: StateFlow<AgentAccessibilityService?> = state.asStateFlow()

    val connected: Boolean get() = state.value != null

    internal fun attach(instance: AgentAccessibilityService) {
        state.value = instance
    }

    /**
     * Identity-compared: a late `onDestroy` from an instance the system has
     * already replaced must not clear the live one.
     */
    internal fun detach(instance: AgentAccessibilityService) {
        state.compareAndSet(instance, null)
    }

    /** The service, or null if it does not connect in time. Never throws. */
    suspend fun await(timeoutMs: Long): AgentAccessibilityService? =
        withTimeoutOrNull(timeoutMs) { service.filterNotNull().first() }
}

/**
 * Reads and drives the screen without ADB.
 *
 * Enabled by the user in Settings, this runs for as long as they leave it on —
 * which is most of the phone's uptime. So outside an agent run it does as
 * close to nothing as the platform allows: it never reads event text, never
 * retains a node or a tree, never logs anything derived from an event, and
 * never writes to disk. [runActive] is pushed by the gateway and is the only
 * thing that opens it up.
 */
class AgentAccessibilityService : AccessibilityService() {

    /**
     * True only while an agent run holds the gateway. The gateway sets it in
     * `beginRun` and clears it in `revoke`, and re-pushes it if this instance
     * reconnected mid-run.
     */
    @Volatile
    var runActive: Boolean = false

    @Volatile
    private var lastEventNanos: Long = System.nanoTime()

    private val changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Ticks on every window or content change. Carries no screen content. */
    val screenChanges: SharedFlow<Unit> = changes.asSharedFlow()

    /** Milliseconds since the last window or content change. */
    val idleMs: Long get() = (System.nanoTime() - lastEventNanos) / 1_000_000L

    /** A dispatched action can return before Android sends its first UI event. */
    internal fun expectUiChange() {
        lastEventNanos = System.nanoTime()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yServiceHandle.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Toggling the service off delivers onUnbind promptly; onDestroy can lag.
        runActive = false
        A11yServiceHandle.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runActive = false
        A11yServiceHandle.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately does not read the event. AccessibilityEvent carries the
        // text that changed, and none of it is ours to look at or to log.
        lastEventNanos = System.nanoTime()
        changes.tryEmit(Unit)
    }

    override fun onInterrupt() = Unit
}
