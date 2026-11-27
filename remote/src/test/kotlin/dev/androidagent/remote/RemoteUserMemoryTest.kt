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

package dev.androidagent.remote

import dev.androidagent.core.EngineKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUserMemoryTest {
    private val pc = RemoteComputer(id = "pc-1", label = "Pc", host = "10.0.0.2", user = "yoni", os = HostOs.WINDOWS)

    @Test fun remoteInstructionsIncludeUserMemoryRuleAndAppendMemoryBlockAcrossEngines() {
        val memory = "[User memory]\n- Speaks Hebrew"
        for (engine in EngineKind.entries) {
            val without = RemoteInstructions.forComputer(pc, engine, userMemory = null)
            assertTrue("engine=$engine mentions user_memory", without.contains("user_memory"))
            assertFalse("engine=$engine omits empty memory", without.contains("[User memory]"))

            val withMem = RemoteInstructions.forComputer(pc, engine, userMemory = memory)
            assertTrue("engine=$engine ends with user memory", withMem.endsWith(memory))
        }
    }

    @Test fun profileForReadsDynamicUserMemoryFreshOnEachSessionStart() {
        var memory: String? = null
        val profile = RemoteHub.profileFor(pc) { memory }
        assertFalse(profile.effectiveInstructions().contains("[User memory]"))

        memory = "[User memory]\n- Prefers concise answers"
        assertTrue(profile.effectiveInstructions().contains("Prefers concise answers"))

        memory = null
        assertFalse(profile.effectiveInstructions().contains("Prefers concise answers"))
    }
}
