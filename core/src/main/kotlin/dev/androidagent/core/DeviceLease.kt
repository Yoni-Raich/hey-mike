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

import kotlinx.coroutines.sync.Mutex

/**
 * The phone's screen and tools, held by one run at a time.
 *
 * Several chats can think at once; only one can drive the phone. A run takes
 * the lease at its first tool call and keeps it until the run ends, so another
 * chat's taps never land between its own. Waiters are served in order.
 */
class DeviceLease {
    private val mutex = Mutex()

    @Volatile
    var holder: Any? = null
        private set

    fun isHeldBy(owner: Any): Boolean = holder === owner

    /** Wait for the phone. Holding it already is not an error. */
    suspend fun acquire(owner: Any) {
        if (holder === owner) return
        mutex.lock(owner)
        holder = owner
    }

    fun tryAcquire(owner: Any): Boolean {
        if (holder === owner) return true
        if (!mutex.tryLock(owner)) return false
        holder = owner
        return true
    }

    /** Hand the phone back. Only its holder can. */
    fun release(owner: Any) {
        if (holder !== owner) return
        holder = null
        mutex.unlock(owner)
    }
}

/**
 * How one coordinator shares the phone with the others in [AgentRuns].
 * The default is a coordinator on its own, which behaves as it always did.
 */
class PhoneShare(
    val lease: DeviceLease = DeviceLease(),
    /** [AgentRuns] delivers engine events, rather than the coordinator collecting them itself. */
    val routed: Boolean = false,
    /** Another chat is running, so a stop must not close the engine they share. */
    val othersActive: () -> Boolean = { false },
)
