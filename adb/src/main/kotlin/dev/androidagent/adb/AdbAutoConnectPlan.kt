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

package dev.androidagent.adb

import dev.androidagent.core.AdbEndpoint

/**
 * Pure decisions for the connect attempt that follows a successful pairing.
 *
 * Wireless Debugging advertises the pairing port and the connect port as two
 * separate mDNS services, and the connect one only appears once pairing is
 * accepted. So the app can find that port itself instead of asking the user to
 * read it off the system dialog, as long as it is willing to look more than
 * once.
 */
object AdbAutoConnectPlan {

    /** Discovery passes before giving up and leaving it to the background loop. */
    const val MAX_ATTEMPTS = 6

    /**
     * Ceiling for the whole sequence. Six passes cost six 3s discovery windows
     * plus 0.5+1+2+4+8+10s of backoff, so 45s covers the worst case while the
     * usual case finishes on the first pass in a few seconds.
     */
    const val TOTAL_BUDGET_MS = 45_000L

    /**
     * The next port worth trying, or null when nothing new is advertised.
     * Ports already tried are excluded so a refused endpoint is not retried in
     * a tight loop, including a stale saved port.
     */
    fun target(savedPort: Int?, endpoints: List<AdbEndpoint>, tried: Set<Int>): Int? {
        val remaining = endpoints.filter { it.port !in tried }
        return AdbReconnectPolicy.preferredConnectPort(savedPort?.takeIf { it !in tried }, remaining)
    }

    fun attemptMessage(attempt: Int, port: Int?): String = when {
        port != null && attempt == 0 -> "Paired. Connecting on port $port…"
        port != null -> "Paired. Trying port $port…"
        attempt == 0 -> "Paired. Looking for the connect port…"
        else -> "Paired. Still looking for the connect port…"
    }

    /**
     * What to say when the sequence runs out. Pairing itself succeeded in every
     * one of these cases, so the identity is stored and the service reconnect
     * loop keeps trying — the copy has to say that, or the user will pair again
     * for no reason.
     */
    fun giveUpMessage(wirelessDebuggingEnabled: Boolean?): String = when (wirelessDebuggingEnabled) {
        false -> "Paired, but Wireless Debugging was switched off before connecting. Turn it back on and try again."
        else -> "Paired, but this phone has not advertised a connect port yet. The app keeps trying in the background, " +
            "or you can enter the port manually."
    }
}
