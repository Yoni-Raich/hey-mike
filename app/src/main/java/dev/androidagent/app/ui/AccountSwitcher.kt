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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Saved Codex sign-ins, one tap to make any of them live.
 *
 * Switching never touches a chat: only the quota changes, so this sits
 * with the usage bars as well as in account settings.
 */
@Composable
fun AccountSwitcher(
    state: AgentUiState,
    actions: AgentUiActions,
    modifier: Modifier = Modifier,
    allowRemove: Boolean = false,
) {
    val saved = state.savedAccounts
    val busy = state.isSwitchingAccount || state.runState.active || state.voiceState.active
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        saved.accounts.forEach { account ->
            val live = account.id == saved.activeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !live && !busy, role = Role.RadioButton) { actions.onSwitchAccount(account.id) }
                    .semantics {
                        selected = live
                        contentDescription = if (live) "${account.label}, in use" else "Switch to ${account.label}"
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (live) Icons.Outlined.CheckCircle else Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        account.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (live) "In use" else "Tap to switch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (live && state.isSwitchingAccount) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (allowRemove && !live) {
                    IconButton(onClick = { actions.onRemoveAccount(account.id) }, enabled = !busy) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove ${account.label} from this phone")
                    }
                }
            }
        }
        OutlinedButton(onClick = actions.onAddAccount, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            LoadingButtonContent(
                loading = state.isSwitchingAccount,
                icon = Icons.Outlined.PersonAdd,
                label = "Add another account",
                loadingLabel = "Switching…",
            )
        }
    }
}
