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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.androidagent.core.EngineKind
import dev.androidagent.runtime.ClaudeInstallPhase
import dev.androidagent.runtime.ClaudeInstallState

// The Claude subscription card, shared by the first-launch sign-in screen and
// Settings. Claude Code is Anthropic's program and is never bundled: the user
// sees its size and starts the download, then signs in inside `claude` itself.
// The app relays the sign-in page and the pasted code and keeps neither.

/** Required wording. The feature is never called "Claude Code" in the app's own name. */
internal const val CLAUDE_NOTICE = "Use your own Claude subscription (runs Anthropic's Claude Code). Not affiliated with Anthropic."
internal const val ANTHROPIC_PRIVACY_URL = "https://www.anthropic.com/legal/privacy"

/** What a user picks between: the account behind the engine, in their words. */
internal fun providerName(kind: EngineKind): String = when (kind) {
    EngineKind.CODEX -> "ChatGPT (Codex)"
    EngineKind.CLAUDE -> "Claude subscription"
}

/** Decimal megabytes, as download sizes are usually given. */
internal fun megabytes(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"

internal fun claudeProgressText(state: ClaudeInstallState): String =
    "${state.bytesDownloaded / 1_000_000} of ${megabytes(state.totalBytes)}"

/** 0..1 while bytes arrive; null while the size is unknown or the file is being checked. */
internal fun claudeProgress(state: ClaudeInstallState): Float? =
    if (state.phase == ClaudeInstallPhase.DOWNLOADING && state.totalBytes > 0) {
        (state.bytesDownloaded.toFloat() / state.totalBytes).coerceIn(0f, 1f)
    } else {
        null
    }

internal enum class ClaudeStage { UNSUPPORTED, DOWNLOAD, DOWNLOADING, FAILED, SIGN_IN, PASTE_CODE, SIGNED_IN }

internal fun claudeStage(claude: ClaudeUiState): ClaudeStage = when (claude.install.phase) {
    ClaudeInstallPhase.UNSUPPORTED -> ClaudeStage.UNSUPPORTED
    ClaudeInstallPhase.NOT_INSTALLED, ClaudeInstallPhase.CANCELLED -> ClaudeStage.DOWNLOAD
    ClaudeInstallPhase.DOWNLOADING, ClaudeInstallPhase.VERIFYING -> ClaudeStage.DOWNLOADING
    ClaudeInstallPhase.FAILED -> ClaudeStage.FAILED
    ClaudeInstallPhase.INSTALLED -> when {
        claude.account?.signedIn == true -> ClaudeStage.SIGNED_IN
        claude.account?.loginUrl != null -> ClaudeStage.PASTE_CODE
        else -> ClaudeStage.SIGN_IN
    }
}

/** A one-line summary for the settings hub. */
internal fun claudeSummary(claude: ClaudeUiState): String = when (claudeStage(claude)) {
    ClaudeStage.UNSUPPORTED -> "Not available on this device"
    ClaudeStage.DOWNLOAD -> "Not set up"
    ClaudeStage.DOWNLOADING -> "Downloading"
    ClaudeStage.FAILED -> "Download failed"
    ClaudeStage.SIGN_IN, ClaudeStage.PASTE_CODE -> "Not signed in"
    ClaudeStage.SIGNED_IN -> "Signed in"
}

/** Which account new chats use. A chat that has begun keeps its own until another model is picked in it. */
@Composable
internal fun ProviderChoice(selected: EngineKind, onSelect: (EngineKind) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        EngineKind.values().forEach { kind ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = kind == selected, role = Role.RadioButton, onClick = { onSelect(kind) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = kind == selected, onClick = null)
                Text(providerName(kind), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** The Claude card: notice, download, sign-in, and where the data goes. */
@Composable
internal fun ColumnScope.ClaudeCard(claude: ClaudeUiState, actions: AgentUiActions) {
    val uriHandler = LocalUriHandler.current
    var confirmDownload by remember { mutableStateOf(false) }
    // Never saved: the code only passes through to `claude auth login`.
    var code by remember { mutableStateOf("") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val size = megabytes(claude.install.totalBytes)

    Text(CLAUDE_NOTICE, style = MaterialTheme.typography.bodySmall, color = muted)
    when (claudeStage(claude)) {
        ClaudeStage.UNSUPPORTED -> Text(
            "Not available on this device. Claude needs a 64-bit ARM phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        ClaudeStage.DOWNLOAD -> {
            Text(
                "Mike needs Anthropic's Claude Code on this phone. It is a one-time download of $size from Anthropic.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { confirmDownload = true }, modifier = Modifier.fillMaxWidth()) { Text("Download ($size)") }
        }
        ClaudeStage.DOWNLOADING -> {
            val progress = claudeProgress(claude.install)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (claude.install.phase == ClaudeInstallPhase.VERIFYING) "Checking the download" else claudeProgressText(claude.install),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            OutlinedButton(onClick = actions.onCancelClaudeDownload, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
        ClaudeStage.FAILED -> {
            Text(
                claude.install.message.ifBlank { "The download failed." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { confirmDownload = true }, modifier = Modifier.fillMaxWidth()) { Text("Try again ($size)") }
        }
        ClaudeStage.SIGN_IN -> {
            Text("Claude Code is ready. Sign in with your Claude account.", style = MaterialTheme.typography.bodyMedium)
            BusyButton(label = "Sign in to Claude", busy = claude.busy, onClick = actions.onClaudeLogin)
        }
        ClaudeStage.PASTE_CODE -> {
            Text(
                "Sign in on the page that opened. It then shows a code: copy it and paste it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val submit = {
                if (code.isNotBlank()) {
                    actions.onClaudeCode(code)
                    code = ""
                }
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code from the sign-in page") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                enabled = !claude.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            BusyButton(label = "Finish sign-in", busy = claude.busy, enabled = code.isNotBlank(), onClick = submit)
            claude.account?.loginUrl?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) { Text("Open the sign-in page again") }
            }
        }
        ClaudeStage.SIGNED_IN -> {
            Text("Signed in · ${claude.account?.label.orEmpty()}", fontWeight = FontWeight.Medium)
            OutlinedButton(onClick = actions.onClaudeLogout, enabled = !claude.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out of Claude")
            }
        }
    }
    Text(
        "In Claude chats, what Mike sees and what you type goes to Anthropic to answer you, and is covered by " +
            "Anthropic's policies. Your sign-in stays on this phone. Voice runs on ChatGPT (Codex), also in a Claude chat.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    TextButton(onClick = { uriHandler.openUri(ANTHROPIC_PRIVACY_URL) }) { Text("Read Anthropic's privacy policy") }

    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download Claude Code?") },
            text = {
                Text(
                    "$size from downloads.claude.ai. Use Wi-Fi if your data is limited. " +
                        "Mike checks the file before it runs, and you can cancel at any time.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDownload = false; actions.onDownloadClaude() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun BusyButton(label: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Text("Working…", modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(label)
        }
    }
}
