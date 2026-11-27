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

package dev.androidagent.app.ui

import dev.androidagent.core.UserMemoryConfig
import dev.androidagent.core.UserMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class MemorySettingsTest {

    @Test fun hubSummaryDescribesAllStatesClearly() {
        assertEquals(
            "Updating from recent chats…",
            memoryHubSummaryText(UserMemorySnapshot(), busy = true),
        )
        assertEquals(
            "Off",
            memoryHubSummaryText(UserMemorySnapshot(content = "abc", config = UserMemoryConfig(enabled = false)), busy = false),
        )
        assertEquals(
            "Empty · learns nightly",
            memoryHubSummaryText(UserMemorySnapshot(content = "", config = UserMemoryConfig(enabled = true, autoLearn = true)), busy = false),
        )
        assertEquals(
            "Empty",
            memoryHubSummaryText(UserMemorySnapshot(content = "", config = UserMemoryConfig(enabled = true, autoLearn = false)), busy = false),
        )
        assertEquals(
            "12 chars · learns nightly",
            memoryHubSummaryText(UserMemorySnapshot(content = "Hello world!", config = UserMemoryConfig(enabled = true, autoLearn = true)), busy = false),
        )
        assertEquals(
            "12 chars",
            memoryHubSummaryText(UserMemorySnapshot(content = "Hello world!", config = UserMemoryConfig(enabled = true, autoLearn = false)), busy = false),
        )
    }
}
