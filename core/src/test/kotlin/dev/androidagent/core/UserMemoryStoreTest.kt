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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserMemoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private var clock = 1_700_000_000_000L
    private fun store() = UserMemoryStore(temp.newFolder("memory")) { clock += 1_000L; clock }

    @Test fun emptyOrDisabledStoreInjectsNothing() {
        val store = store()
        assertNull(store.promptBlock())
        assertNull(store.voiceMemoryText())

        store.write("## About\n- Speaks Hebrew and English")
        val block = store.promptBlock()
        assertNotNull(block)
        assertTrue(block!!.startsWith("[User memory"))
        assertTrue(block.contains("quoted user background data"))
        assertTrue(block.contains("Speaks Hebrew and English"))
        assertEquals("## About\n- Speaks Hebrew and English", store.voiceMemoryText())

        store.setEnabled(false)
        assertNull(store.promptBlock())
        assertNull(store.voiceMemoryText())

        store.setEnabled(true)
        assertNotNull(store.promptBlock())
    }

    @Test fun appendFactInsertsUnderSectionAndDeduplicatesCaseInsensitively() {
        val store = store()
        val first = store.appendFact("Prefers concise Hebrew answers?", section = "Preferences")
        assertTrue(first.added)
        assertFalse(first.duplicate)
        assertTrue(store.read().contains("## Preferences\n- Prefers concise Hebrew answers?"))

        val dup = store.appendFact("- prefers concise hebrew answers?", section = "Preferences")
        assertFalse(dup.added)
        assertTrue(dup.duplicate)

        val second = store.appendFact("Uses Android phone daily", section = "Preferences")
        assertTrue(second.added)
        val lines = store.read().lines()
        assertEquals("## Preferences", lines[0])
        assertEquals("- Prefers concise Hebrew answers?", lines[1])
        assertEquals("- Uses Android phone daily", lines[2])
    }

    @Test fun removeMatchingDeletesFactsWithoutDroppingHeadings() {
        val store = store()
        store.write(
            """
            ## Preferences
            - Prefers dark coffee
            - Likes concise replies

            ## People
            - Sister lives in Haifa
            """.trimIndent(),
        )
        val result = store.removeMatching("coffee")
        assertEquals(1, result.removedLines)
        val after = store.read()
        assertTrue(after.contains("## Preferences"))
        assertFalse(after.contains("coffee"))
        assertTrue(after.contains("Likes concise replies"))
        assertTrue(after.contains("Sister lives in Haifa"))
        assertEquals(1, store.listBackups().size)
    }

    @Test fun sanitizeRedactsSecretsWhileKeepingQuestionMarks() {
        val raw = """
            - Ask before spending money? Always yes!
            - password: SuperSecretPassword123
            - pin is 492817
            - token sk-proj-abcdefghijklmnopqrstuvwxyz123456
            - -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val cleaned = UserMemoryStore.sanitizeContent(raw)
        assertTrue(cleaned, cleaned.contains("Ask before spending money? Always yes!"))
        assertFalse(cleaned, cleaned.contains("SuperSecretPassword123"))
        assertFalse(cleaned, cleaned.contains("492817"))
        assertFalse(cleaned, cleaned.contains("sk-proj-abcdefghijklmnopqrstuvwxyz123456"))
        assertFalse(cleaned, cleaned.contains("MIIEowIBAAKCAQEA"))
        assertTrue(cleaned, cleaned.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test fun rollingBackupsCapAtMaxAndRestoreIsReversible() {
        val store = store()
        store.write("version 1")
        for (i in 2..8) {
            store.write("version $i")
        }
        val backups = store.listBackups()
        assertEquals(UserMemoryStore.MAX_BACKUPS, backups.size)
        assertEquals("version 8", store.read())

        val oldestKept = backups.last()
        store.restoreBackup(oldestKept.id)
        assertEquals("version 3", store.read())
        assertTrue(store.listBackups().any { it.preview.contains("version 8") })

        val bad = runCatching { store.restoreBackup("../profile.md") }.exceptionOrNull()
        assertTrue(bad is IllegalArgumentException)
    }

    @Test fun writeAndClearEnforceLimitsAndKeepBackup() {
        val store = store()
        val tooBig = "a".repeat(UserMemoryStore.MAX_PROFILE_CHARS + 1)
        assertTrue(runCatching { store.write(tooBig) }.exceptionOrNull() is IllegalArgumentException)

        store.write("## About\n- Architect")
        store.clear()
        assertEquals("", store.read())
        assertEquals(1, store.listBackups().size)
        assertTrue(store.listBackups().single().preview.contains("## About"))
    }

    @Test fun recordConsolidationUpdatesProfileAndMonotonicWatermark() {
        val store = store()
        store.recordConsolidation(
            ok = true,
            summary = "Updated from 3 chats",
            processedChats = 3,
            newWatermark = 5_000L,
            updatedProfile = "## About\n- Lives in Israel",
        )
        assertEquals("## About\n- Lives in Israel", store.read())
        assertEquals(5_000L, store.config().lastConsolidatedAt)
        assertEquals(true, store.config().lastRunOk)
        assertEquals(3, store.config().lastProcessedChats)

        // Failed run does not move watermark backward or lose profile.
        store.recordConsolidation(ok = false, summary = "Offline", processedChats = 0, newWatermark = 1_000L)
        assertEquals("## About\n- Lives in Israel", store.read())
        assertEquals(5_000L, store.config().lastConsolidatedAt)
        assertEquals(false, store.config().lastRunOk)
    }
}
