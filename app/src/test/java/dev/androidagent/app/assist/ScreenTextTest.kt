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

package dev.androidagent.app.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    @Test
    fun aPasswordFieldIsNeverRead() {
        assertNull(ScreenText.line("hunter2", null, "Password", editable = true, password = true))
    }

    @Test
    fun aFieldSaysWhatItIsFor() {
        assertEquals("[field: Search] pizza", ScreenText.line("pizza", null, "Search", editable = true, password = false))
        assertEquals("[empty field: Message]", ScreenText.line("", null, "Message", editable = true, password = false))
    }

    @Test
    fun aViewWithoutTextFallsBackToItsDescription() {
        assertEquals("Send", ScreenText.line(null, "Send", null, editable = false, password = false))
        assertNull(ScreenText.line("  ", null, null, editable = false, password = false))
    }

    @Test
    fun repeatedLinesAreReadOnce() {
        assertEquals("שלום\nOK", ScreenText.joined(listOf("שלום", "שלום", "  OK  ")))
    }

    @Test
    fun aLongScreenIsCut() {
        val text = ScreenText.joined(List(1_000) { "line $it" })
        assertTrue(text.length <= ScreenText.MAX_CHARS + 1)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun thePromptCarriesTheScreenAndAsksForSilence() {
        val prompt = ScreenText.prompt(ScreenCapture("com.example.chat", listOf("Dana", "See you at 8?")))
        assertTrue(prompt.contains("Do not reply to this message"))
        assertTrue(prompt.contains("App: com.example.chat"))
        assertTrue(prompt.contains("See you at 8?"))
    }

    @Test
    fun withoutScreenTextThePromptSaysSo() {
        assertTrue(ScreenText.prompt(null).contains("not available"))
        assertTrue(ScreenText.prompt(ScreenCapture("com.example", emptyList())).contains("not available"))
    }
}
