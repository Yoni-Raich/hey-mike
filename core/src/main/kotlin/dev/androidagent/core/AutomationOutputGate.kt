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

import java.time.ZonedDateTime

/** A lost connection ends permission for this call. Reconnecting cannot replay private speech. */
class AutomationOutputGate {
    private var required: List<AutomationCondition> = emptyList()
    private var lost = false
    @Synchronized fun reset(conditions: List<AutomationCondition> = emptyList()) { required = conditions; lost = false }
    @Synchronized fun require(conditions: List<AutomationCondition>) { required = (required + conditions).distinct() }
    @Synchronized fun conditions(): List<AutomationCondition> = required
    @Synchronized fun allows(snapshot: AutomationDeviceSnapshot): Boolean {
        if (lost) return false
        if (required.isEmpty()) return true
        val now = ZonedDateTime.now()
        val context = AutomationContext(now, deviceState = snapshot.states, connections = snapshot.connections)
        if (!required.all { it.holds(AutomationEvent.Clock(now), context) }) lost = true
        return !lost
    }
}
