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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFilesTest {
    private val items = listOf(
        WorkspaceFileItem("AGENTS.md", 900, modifiedAt = 500),
        WorkspaceFileItem("attachments/3f2b1c4d-1234-4abc-8def-0123456789ab-receipt.jpg", 204_800, modifiedAt = 200),
        WorkspaceFileItem(".agents/skills/app-cards/SKILL.md", 400, modifiedAt = 500),
        WorkspaceFileItem("notes/summary.md", 1_200, modifiedAt = 300),
        WorkspaceFileItem("preferences.json", 300, modifiedAt = 500),
        WorkspaceFileItem("attachments", isDirectory = true),
    )

    @Test fun theUsersFilesComeFirstNewestOnTop() {
        val listing = workspaceListing(items)
        assertEquals(listOf("summary.md", "receipt.jpg"), listing.yours.map { it.name })
        assertEquals(listOf("notes", "attachments"), listing.yours.map { it.folder })
    }

    @Test fun whatTheAppPlantsIsKeptApartInPathOrder() {
        val listing = workspaceListing(items)
        assertEquals(listOf(".agents/skills/app-cards/SKILL.md", "AGENTS.md", "preferences.json"), listing.agent.map { it.item.path })
    }

    @Test fun hiddenFoldersAndSeededRootsAreAgentFiles() {
        assertTrue(isAgentFile(".agents/skills/x/SKILL.md"))
        assertTrue(isAgentFile("AGENTS.md"))
        assertTrue(isAgentFile("preferences.json"))
        // No longer seeded, so a folder by that name is the user's own.
        assertFalse(isAgentFile("cards/maps.md"))
        assertFalse(isAgentFile("attachments/photo.png"))
        assertFalse(isAgentFile("report.md"))
    }

    @Test fun anAttachmentShowsTheNameItWasGiven() {
        assertEquals("receipt.jpg", displayName("3f2b1c4d-1234-4abc-8def-0123456789ab-receipt.jpg"))
        assertEquals("plain.txt", displayName("plain.txt"))
        assertEquals("2024-report.pdf", displayName("2024-report.pdf"))
    }

    @Test fun kindsFollowTheExtension() {
        assertEquals(FileKind.IMAGE, kindOf("photo.JPG"))
        assertEquals(FileKind.PDF, kindOf("bill.pdf"))
        assertEquals(FileKind.TEXT, kindOf("notes.md"))
        assertEquals(FileKind.OTHER, kindOf("blob"))
    }

    @Test fun sessionTraceIsShownAsPlainText() {
        assertEquals(FileKind.TEXT, kindOf("session-trace.jsonl"))
        assertEquals("text/plain", mimeTypeFor("session-trace.jsonl") { null })
    }

    @Test fun notesGoOutAsPlainTextAndUnknownFilesToAnyApp() {
        assertEquals("text/plain", mimeTypeFor("notes.md") { null })
        assertEquals("application/pdf", mimeTypeFor("bill.pdf") { if (it == "pdf") "application/pdf" else null })
        assertEquals("*/*", mimeTypeFor("blob") { null })
        assertEquals("*/*", mimeTypeFor("data.bin") { null })
    }
}
