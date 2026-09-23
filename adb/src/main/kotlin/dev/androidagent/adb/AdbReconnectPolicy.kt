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

/** Pure decisions used by the lifecycle reconnect loop. */
object AdbReconnectPolicy {
    const val MAX_BACKOFF_MS = 10_000L

    /** Prefer the last known connect port, then an advertised connect service. */
    fun preferredConnectPort(savedPort: Int?, endpoints: List<AdbEndpoint>): Int? {
        if (savedPort != null && AdbServiceDiscovery.isValidAdbPort(savedPort)) return savedPort
        return endpoints.firstOrNull { !it.pairing && AdbServiceDiscovery.isValidAdbPort(it.port) }?.port
    }

    /** Pick a newly advertised connect endpoint after a stored port failed. */
    fun fallbackConnectPort(failedPort: Int?, endpoints: List<AdbEndpoint>): Int? =
        endpoints.firstOrNull {
            !it.pairing && it.port != failedPort && AdbServiceDiscovery.isValidAdbPort(it.port)
        }?.port

    fun retryDelayMs(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 5)
        return (500L shl exponent).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun noServiceMessage(wirelessDebuggingEnabled: Boolean?): String = when (wirelessDebuggingEnabled) {
        false -> "Wireless Debugging is off"
        true -> "Wireless Debugging is on; waiting for the ADB service"
        null -> "Wireless Debugging status unavailable; waiting for the ADB service"
    }
}
