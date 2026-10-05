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

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.EngineKind
import dev.androidagent.runtime.ClaudeInstallPhase
import dev.androidagent.runtime.ClaudeInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSetupTest {
    private val size = 232_077_120L

    @Test fun theDownloadSizeIsShownInWholeMegabytes() {
        assertEquals("232 MB", megabytes(size))
        assertEquals("0 MB", megabytes(0))
    }

    @Test fun progressSaysHowMuchOfTheDownloadArrived() {
        val state = ClaudeInstallState(ClaudeInstallPhase.DOWNLOADING, 116_000_000, size)
        assertEquals("116 of 232 MB", claudeProgressText(state))
        assertEquals(0.5f, claudeProgress(state)!!, 0.01f)
        assertNull(claudeProgress(ClaudeInstallState(ClaudeInstallPhase.VERIFYING, 0, 0)))
    }

    @Test fun eachInstallStepHasOneStage() {
        assertEquals(ClaudeStage.UNSUPPORTED, claudeStage(ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.UNSUPPORTED))))
        assertEquals(ClaudeStage.DOWNLOAD, claudeStage(ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.NOT_INSTALLED))))
        assertEquals(ClaudeStage.DOWNLOAD, claudeStage(ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.CANCELLED))))
        assertEquals(ClaudeStage.DOWNLOADING, claudeStage(ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.VERIFYING))))
        assertEquals(ClaudeStage.FAILED, claudeStage(ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.FAILED))))
        val installed = ClaudeInstallState(ClaudeInstallPhase.INSTALLED)
        assertEquals(ClaudeStage.SIGN_IN, claudeStage(ClaudeUiState(installed)))
        assertEquals(ClaudeStage.PASTE_CODE, claudeStage(ClaudeUiState(installed, AccountStatus(false, "Sign in", loginUrl = "https://claude.ai/x"))))
        assertEquals(ClaudeStage.SIGNED_IN, claudeStage(ClaudeUiState(installed, AccountStatus(true, "me@example.com"))))
    }

    @Test fun eitherEngineSignedInCountsAsSignedIn() {
        val installed = ClaudeInstallState(ClaudeInstallPhase.INSTALLED)
        val claudeOnly = AgentUiState(claude = ClaudeUiState(installed, AccountStatus(true, "me")))
        assertEquals(true, claudeOnly.setupSignals().signedIn)
        val codexOnly = AgentUiState(accountStatus = AccountStatus(true, "me"))
        assertEquals(true, codexOnly.setupSignals().signedIn)
        val neither = AgentUiState(accountStatus = AccountStatus(false, "Sign in"))
        assertEquals(false, neither.setupSignals().signedIn)
    }

    @Test fun aClaudeChatHasNoVoiceButton() {
        assertEquals(ComposerAction.VOICE, composerAction(active = false, hasDraft = false, runActive = false, voiceActive = false, voiceAllowed = true))
        assertEquals(ComposerAction.SEND, composerAction(active = false, hasDraft = false, runActive = false, voiceActive = false, voiceAllowed = false))
        assertEquals(ComposerAction.STOP, composerAction(active = true, hasDraft = false, runActive = true, voiceActive = false, voiceAllowed = false))
        assertEquals(ComposerAction.STEER, composerAction(active = true, hasDraft = true, runActive = true, voiceActive = false, voiceAllowed = false))
    }

    @Test fun theEngineNameReadsAsTheProvider() {
        assertEquals("ChatGPT (Codex)", providerName(EngineKind.CODEX))
        assertEquals("Claude subscription", providerName(EngineKind.CLAUDE))
    }

    @Test fun theNoticeKeepsTheRequiredWords() {
        assertTrue(CLAUDE_NOTICE.contains("Use your own Claude subscription (runs Anthropic's Claude Code)."))
        assertTrue(CLAUDE_NOTICE.contains("Not affiliated with Anthropic."))
        assertFalse(ANTHROPIC_PRIVACY_URL.isBlank())
    }
}
