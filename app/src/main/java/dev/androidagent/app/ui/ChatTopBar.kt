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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.a11y.A11yStatus
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.RunPhase
import dev.androidagent.core.UsageSummary

// The chat's top bar. The title opens the chats; the agent's sphere is the
// status, with the quota as a ring around it: teal when it can reach the
// phone, blue and busy while it works, amber when nothing lets it control the
// phone. The sphere opens one sheet with each backend and its fix, the quota
// windows, the chat's files and settings.

internal enum class ControlState { READY, WORKING, BLOCKED }

internal data class PhoneControl(val state: ControlState, val via: String?)

/** Which backend can act on the phone right now, from both, not from ADB alone. */
internal fun phoneControl(a11yConnected: Boolean, adbPhase: ConnectionPhase, running: Boolean): PhoneControl {
    val adb = adbPhase == ConnectionPhase.CONNECTED
    val via = when {
        a11yConnected && adb -> "Accessibility and ADB"
        a11yConnected -> "Accessibility"
        adb -> "ADB"
        else -> null
    }
    val state = when {
        running -> ControlState.WORKING
        via != null -> ControlState.READY
        else -> ControlState.BLOCKED
    }
    return PhoneControl(state, via)
}

internal fun PhoneControl.sentence(): String = when (state) {
    ControlState.WORKING -> via?.let { "Working on your phone through $it" } ?: "Working on your request"
    ControlState.READY -> "Ready to control your phone through $via"
    ControlState.BLOCKED -> "The agent cannot control your phone yet"
}

internal fun a11yNote(status: A11yStatus): String = when {
    status.connected -> "On · reads and taps apps directly"
    status.blockedByRestrictedSetting -> "Allow restricted settings to finish turning it on"
    else -> "Off"
}

internal fun adbNote(status: AdbStatus): String = when (status.phase) {
    ConnectionPhase.CONNECTED -> status.port?.let { "Connected · port $it" } ?: "Connected"
    ConnectionPhase.DISCOVERING -> "Searching"
    ConnectionPhase.PAIRING -> "Pairing"
    ConnectionPhase.CONNECTING -> "Reconnecting"
    ConnectionPhase.ERROR -> "Could not connect"
    ConnectionPhase.DISCONNECTED -> "Not connected"
}

private val ReadyTeal = Color(0xFF83D9CA)
private val WorkingBlue = Color(0xFF69A7FF)
private val BlockedAmber = Color(0xFFF6B86A)
private val RingTrack = Color(0xFF262626)
private val StatusSheetFill = Color(0xFF1B1B1B)
private val StatusMuted = Color(0xFF8F8F8F)
private val BackendTile = Color(0xFF262626)
private val BackendTileInk = Color(0xFFE6E6E6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(state: AgentUiState, actions: AgentUiActions, onOpenDrawer: () -> Unit) {
    var confirmNew by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Start a new chat?") },
            text = { Text("The current task will keep running. New tasks will wait in the queue.") },
            confirmButton = { TextButton(onClick = { confirmNew = false; actions.onNewChat() }) { Text("New chat") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
        )
    }
    val title = state.activeSessionTitle?.takeIf { it.isNotBlank() } ?: "Hey Mike"
    TopAppBar(
        navigationIcon = { PanelButton(state, onOpenDrawer) },
        title = {
            Column {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                )
                // Which machine this chat acts on, so a command is never sent
                // to the computer by someone who thought it was the phone.
                // It also says the phone is still in reach from there.
                state.activeSessionId?.let(state.remoteChats::get)?.let { where ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Computer, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp),
                        )
                        Text(
                            " $where",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            "  +  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Outlined.PhoneAndroid, contentDescription = "and this phone",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        },
        actions = {
            AgentStatusButton(state) { showStatus = true }
            IconButton(onClick = { if (state.runState.active) confirmNew = true else actions.onNewChat() }) {
                Icon(Icons.Outlined.EditNote, contentDescription = "New chat")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
    if (showStatus) StatusSheet(state, actions) { showStatus = false }
}

// The way into the panel is a button of its own, so the chat name is only a
// name. It also carries the panel's one urgent fact: a rule that is switched
// on and cannot run is otherwise invisible until the day someone notices it
// never did anything. No dot when nothing is wrong — a badge that is always
// lit stops being read.
@Composable
private fun PanelButton(state: AgentUiState, onOpenDrawer: () -> Unit) {
    val ruleAlert = state.automations.overview.needsAttention
    val spoken = if (ruleAlert) {
        "Open chats and rules. A standing rule cannot run."
    } else {
        "Open chats and rules"
    }
    IconButton(onClick = onOpenDrawer, modifier = Modifier.semantics { contentDescription = spoken }) {
        Box {
            Icon(Icons.Outlined.Menu, contentDescription = null, modifier = Modifier.size(22.dp))
            if (ruleAlert) {
                // A ring in the bar's own background, so the dot never merges
                // into the glyph at this size.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-3).dp)
                        .size(9.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(6.dp).background(BlockedAmber, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun AgentStatusButton(state: AgentUiState, onClick: () -> Unit) {
    val control = phoneControl(state.a11yStatus.connected, state.adbStatus.phase, state.runState.active)
    val windows = remember(state.usageLimits) { UsageSummary.windows(state.usageLimits, System.currentTimeMillis() / 1000L) }
    val primary = remember(windows) { UsageSummary.primary(windows) }
    val spoken = "${control.sentence()}. ${UsageSummary.spoken(primary)}. Open status and usage"
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        val fraction = primary?.fraction
        Canvas(Modifier.size(40.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(RingTrack, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (fraction != null) {
                // From twelve o'clock, so a full ring reads as a full window.
                drawArc(quotaColor(fraction), -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        AgentOrb(
            Modifier.size(32.dp),
            phase = if (state.runState.active) state.runState.phase else RunPhase.IDLE,
            controlling = state.runState.active && state.runState.controlling,
            idleColor = if (control.state == ControlState.BLOCKED) BlockedAmber else ReadyTeal,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSheet(state: AgentUiState, actions: AgentUiActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val control = phoneControl(state.a11yStatus.connected, state.adbStatus.phase, state.runState.active)
    val windows = remember(state.usageLimits) { UsageSummary.windows(state.usageLimits, System.currentTimeMillis() / 1000L) }
    val dot = when (control.state) {
        ControlState.WORKING -> WorkingBlue
        ControlState.READY -> ReadyTeal
        ControlState.BLOCKED -> BlockedAmber
    }
    val adbBusy = state.adbStatus.phase in setOf(ConnectionPhase.DISCOVERING, ConnectionPhase.PAIRING, ConnectionPhase.CONNECTING)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = StatusSheetFill) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(color = dot, size = 10.dp, pulsing = control.state == ControlState.WORKING)
                Text(control.sentence(), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
            StatusLabel("PHONE CONTROL")
            Column {
                BackendRow(
                    icon = Icons.Outlined.AccessibilityNew,
                    title = "Accessibility",
                    note = a11yNote(state.a11yStatus),
                    on = state.a11yStatus.connected,
                    busy = false,
                    fixLabel = if (state.a11yStatus.blockedByRestrictedSetting) "Allow" else "Turn on",
                    onFix = if (state.a11yStatus.blockedByRestrictedSetting) actions.onOpenAppInfo else actions.onOpenAccessibilitySettings,
                )
                BackendRow(
                    icon = Icons.Outlined.Terminal,
                    title = "Wireless ADB",
                    note = adbNote(state.adbStatus),
                    on = state.adbStatus.phase == ConnectionPhase.CONNECTED,
                    busy = adbBusy,
                    fixLabel = "Set up",
                    // Pairing lives in the app's settings; Wireless Debugging alone is not enough.
                    onFix = { onDismiss(); actions.onOpenSettings() },
                )
            }
            StatusLabel("USAGE")
            if (windows.isEmpty()) {
                Text("Account quota is not available for this account yet.", fontSize = 13.sp, lineHeight = 18.sp, color = StatusMuted)
            }
            windows.forEach { window -> UsageWindowRow(window) }
            TextButton(onClick = actions.onRefreshAccount, enabled = !state.isRefreshingAccount) {
                LoadingButtonContent(loading = state.isRefreshingAccount, icon = Icons.Outlined.Refresh, label = "Refresh usage", loadingLabel = "Refreshing…")
            }
            if (state.savedAccounts.accounts.isNotEmpty()) {
                StatusLabel("ACCOUNT")
                AccountSwitcher(state, actions)
            }
            HorizontalDivider(color = RingTrack)
            Column {
                LinkRow(Icons.Outlined.Folder, "Workspace files") { onDismiss(); actions.onOpenWorkspaceFiles() }
                LinkRow(Icons.Outlined.Settings, "Settings") { onDismiss(); actions.onOpenSettings() }
            }
        }
    }
}

@Composable
private fun StatusLabel(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = StatusMuted)
}

@Composable
private fun BackendRow(
    icon: ImageVector,
    title: String,
    note: String,
    on: Boolean,
    busy: Boolean,
    fixLabel: String,
    onFix: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(BackendTile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = BackendTileInk)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(note, fontSize = 12.5.sp, lineHeight = 17.sp, color = StatusMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            on -> Icon(Icons.Outlined.Check, contentDescription = "$title is on", tint = ReadyTeal)
            busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Surface(onClick = onFix, shape = RoundedCornerShape(17.dp), color = Color(0xFFF2F2F2)) {
                Text(
                    fixLabel,
                    color = Color(0xFF111111),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color(0xFFAAAAAA))
        Text(text, Modifier.weight(1f), fontSize = 15.sp, color = BackendTileInk)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF6B6B6B))
    }
}
