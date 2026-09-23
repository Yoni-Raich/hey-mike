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

package dev.androidagent.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRuntimeHostConfigTest {
    @Test
    fun `fresh config enables realtime feature`() {
        val directory = Files.createTempDirectory("android-agent-config").toFile()
        try {
            val config = directory.resolve("config.toml")

            assertTrue(AndroidRuntimeHost.ensureRealtimeFeatureConfig(config))
            val text = config.readText()
            assertTrue(text.contains("[features]"))
            assertTrue(text.contains("realtime_conversation = true"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `existing config preserves content and migration is idempotent`() {
        val directory = Files.createTempDirectory("android-agent-config").toFile()
        try {
            val config = directory.resolve("config.toml")
            config.writeText(
                """
                # Keep this setting and its comment.
                [auth]
                credential_reference = "keep-me"
                [features]
                realtime_conversation = false # keep this inline comment
                """.trimIndent() + "\n"
            )

            assertTrue(AndroidRuntimeHost.ensureRealtimeFeatureConfig(config))
            val migrated = config.readText()
            assertTrue(migrated.contains("credential_reference = \"keep-me\""))
            assertTrue(migrated.contains("realtime_conversation = true # keep this inline comment"))

            assertFalse(AndroidRuntimeHost.ensureRealtimeFeatureConfig(config))
            assertEquals(migrated, config.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `partial config without feature keeps existing sections`() {
        val directory = Files.createTempDirectory("android-agent-config").toFile()
        try {
            val config = directory.resolve("config.toml")
            config.writeText(
                """
                # User supplied config.
                [model]
                name = "keep-model"
                """.trimIndent() + "\n"
            )

            assertTrue(AndroidRuntimeHost.ensureRealtimeFeatureConfig(config))
            val text = config.readText()
            assertTrue(text.contains("name = \"keep-model\""))
            assertTrue(text.contains("[features]"))
            assertTrue(text.contains("realtime_conversation = true"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
