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

package dev.androidagent.engineclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeUserMemoryTest {

    @Test fun systemPromptAppendsUserMemoryAfterAgentsMdAndOmitsBlank() {
        val without = ClaudeInstructions.systemPrompt("Manual body", null)
        assertTrue(without.contains("user_memory"))
        assertTrue(without.contains("Manual body"))
        assertFalse(without.contains("[User memory"))

        val withMemory = ClaudeInstructions.systemPrompt("Manual body", "[User memory]\n- Keeps kosher")
        assertTrue(withMemory.indexOf("Manual body") < withMemory.indexOf("[User memory]"))
        assertTrue(withMemory.endsWith("[User memory]\n- Keeps kosher"))

        assertEquals(without, ClaudeInstructions.systemPrompt("Manual body", "   "))
    }

    @Test fun oneShotArgsDisableToolsAndTranscriptsWithoutBareMode() {
        val args = ClaudeProtocol.oneShotArgs("/tmp/system.txt", "haiku")
        fun after(flag: String) = args[args.indexOf(flag) + 1]
        assertEquals("stream-json", after("--input-format"))
        assertEquals("stream-json", after("--output-format"))
        assertEquals("", after("--tools"))
        assertEquals("", after("--setting-sources"))
        assertEquals("/tmp/system.txt", after("--system-prompt-file"))
        assertEquals("haiku", after("--model"))
        assertTrue(args.containsAll(listOf("--strict-mcp-config", "--no-session-persistence")))
        assertFalse(args.any { it == "--bare" || it == "--resume" || it == "--session-id" })
    }
}
