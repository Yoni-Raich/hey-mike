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

package dev.androidagent.devicetools

import dev.androidagent.core.ToolSchemaAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ADB backend and the native capability tools describe what they take. */
class DeviceToolSchemaTest {

    @Test fun everyAdbToolSaysWhatItTakes() {
        val complaints = ToolSchemaAudit.complaints(AndroidDeviceTools.TOOL_DEFINITIONS)
        assertEquals(complaints.joinToString("\n"), emptyList<String>(), complaints)
    }

    @Test fun everyCapabilityToolSaysWhatItTakes() {
        val complaints = ToolSchemaAudit.complaints(AndroidCapabilityTools.TOOL_DEFINITIONS)
        assertEquals(complaints.joinToString("\n"), emptyList<String>(), complaints)
    }

    @Test fun bothListsAreTheRealOnes() {
        // A check over an empty list passes by testing nothing.
        assertTrue(AndroidDeviceTools.TOOL_DEFINITIONS.map { it.name }.contains("read_ui"))
        assertTrue(AndroidCapabilityTools.TOOL_DEFINITIONS.map { it.name }.contains("contacts"))
    }
}
