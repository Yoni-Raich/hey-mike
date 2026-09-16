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

import dev.androidagent.core.ToolSchemaAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility backend's tools describe what they take.
 *
 * `act_plan` shipped with a parameter advertised as a bare array, which a
 * client renders as an array of strings; the agent sent strings and was refused
 * for the schema's mistake. The audit is the shared definition of that defect,
 * and this is the backend that serves most calls, so it runs here too.
 */
class A11yToolSchemaTest {

    @Test fun everyToolThisBackendAdvertisesSaysWhatItTakes() {
        val complaints = ToolSchemaAudit.complaints(A11yDeviceTools.TOOL_DEFINITIONS)
        assertEquals(complaints.joinToString("\n"), emptyList<String>(), complaints)
    }

    @Test fun theIntentToolsAreInThatList() {
        // open_intent carries the one free-form object here (extras), so an
        // empty list would make the check above pass by testing nothing.
        val names = A11yDeviceTools.TOOL_DEFINITIONS.map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("read_ui", "tap_node", "open_intent")))
    }
}
