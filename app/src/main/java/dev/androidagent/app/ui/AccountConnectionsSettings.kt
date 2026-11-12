/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.androidagent.app.AgentApplication
import dev.androidagent.core.EngineKind
import dev.androidagent.core.connections.AccountConnectionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun accountConnectionsHubSummary(): String {
    val graph = (LocalContext.current.applicationContext as? AgentApplication)?.graph
        ?: return "Gmail and other apps from your AI accounts"
    val state by graph.accountConnections.state.collectAsState()
    val count = state.active.size
    return if (count == 0) "Gmail and other apps from your AI accounts" else "$count connections on · read only"
}

@Composable internal fun AccountConnectionsSettings(onOpenAccounts: () -> Unit) {
    val controller = (LocalContext.current.applicationContext as AgentApplication).graph.accountConnections
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { withContext(Dispatchers.IO) { controller.refresh() } }
    AccountConnectionsPage(
        state = state,
        onRefresh = { scope.launch(Dispatchers.IO) { controller.refresh() } },
        onToggle = { id, on -> controller.setEnabled(id, on) },
        onOpenAccounts = onOpenAccounts,
    )
}

/** A data-only page also used by UI fixtures; rendering it never starts an engine or reads mail. */
@Composable internal fun AccountConnectionsPage(
    state: AccountConnectionsState,
    onRefresh: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Give Mike access to Gmail and other apps already connected to your ChatGPT or Claude account.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("You choose each connection. Only reading is allowed; sending messages or changing data stays blocked.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = state.loading.isEmpty()) { Text("Refresh connections") }
            TextButton(onClick = onOpenAccounts) { Text("Accounts") }
        }
        for (provider in listOf(EngineKind.CODEX, EngineKind.CLAUDE)) {
            val accounts = state.accounts.filter { it.engine == provider }
            val title = if (provider == EngineKind.CODEX) "ChatGPT · Codex" else "Claude Code"
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (provider in state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (provider == EngineKind.CLAUDE) Text("For Claude chats on this phone.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        provider in state.errors -> Text("Could not load connections. Check your sign-in in Accounts, then refresh.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        accounts.isEmpty() && provider in state.loading -> Text("Checking your account connections…", style = MaterialTheme.typography.bodySmall)
                        accounts.isEmpty() && provider !in state.checked -> Text("Not checked yet.", style = MaterialTheme.typography.bodySmall)
                        accounts.isEmpty() -> Text("No connections found. Sign in under Accounts and connect apps in ${if (provider == EngineKind.CODEX) "ChatGPT" else "Claude"}, then refresh.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (account in accounts) {
                        val checked = account.id in state.enabled
                        val available = account.connected && account.readTools.isNotEmpty()
                        Row(Modifier.fillMaxWidth().testTag("account-connection-${account.id}")
                            .toggleable(checked, enabled = available || checked, role = Role.Switch, onValueChange = { onToggle(account.id, it) })
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(account.name, style = MaterialTheme.typography.titleSmall)
                                Text(when {
                                    !account.connected -> "Unavailable · refresh or check your account"
                                    account.readTools.isEmpty() -> "No read-only abilities available"
                                    checked -> "On · ${account.readTools.size} read-only abilities"
                                    else -> "Off · ${account.readTools.size} read-only abilities"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = checked, onCheckedChange = null, enabled = available || checked)
                        }
                    }
                }
            }
        }
        Text("Turning a connection off also stops phone Claude chats that still have its account tools open.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
