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

package dev.androidagent.app.panels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.ui.AndroidAgentTheme
import dev.androidagent.core.AgentPanel
import dev.androidagent.core.PanelApprovalLines
import dev.androidagent.core.PanelCall
import dev.androidagent.core.PanelCallWords
import dev.androidagent.core.PanelHost
import dev.androidagent.core.PanelPlacement
import dev.androidagent.core.PanelRevision
import dev.androidagent.core.PanelSurface
import dev.androidagent.core.SavedPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one place that lists every saved panel, approves the ones that wait
 * and removes any of them. The app draws all of it; nothing here comes from
 * the agent but the panel's own title and calls, shown as data.
 */
class SavedPanelsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as? AgentApplication)?.graph
        if (graph == null) {
            finish()
            return
        }
        // Approving here lets taps run without Mike; a window drawn over this
        // screen must not be able to press the button for the user.
        window.decorView.filterTouchesWhenObscured = true
        val focus = intent.getStringExtra(EXTRA_PANEL)
        setContent {
            AndroidAgentTheme {
                SavedPanelsScreen(
                    host = graph.panels,
                    surfaces = graph.panelSurfaces,
                    focus = focus,
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as? AgentApplication)?.graph?.panelSurfaces?.setListOpen(true)
    }

    override fun onStop() {
        (application as? AgentApplication)?.graph?.panelSurfaces?.setListOpen(false)
        super.onStop()
    }

    companion object {
        private const val EXTRA_PANEL = "panel"

        fun intent(context: Context, panelId: String?): Intent =
            Intent(context, SavedPanelsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (panelId != null) putExtra(EXTRA_PANEL, panelId) }
    }
}

private const val ALREADY_ON_HOME = "This panel is already on your home screen."

@Composable
private fun SavedPanelsScreen(host: PanelHost, surfaces: SavedPanelSurfaces, focus: String?, onBack: () -> Unit) {
    val panels by host.panels.collectAsState()
    val revisions by host.revisions.collectAsState()
    val notice by host.notice.collectAsState()
    val scope = rememberCoroutineScope()
    val problems = remember { mutableStateMapOf<String, String>() }
    var confirmAll by remember { mutableStateOf(false) }
    // The clock for "ends in", and a fresh look at what can float.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val ordered = panels.sortedWith(compareBy<SavedPanel>({ it.id != focus }, { it.status != SavedPanel.Status.PENDING }, { -it.createdAtMs }))

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Saved panels", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (panels.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Remove all") }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                notice?.let { text ->
                    item(key = "notice") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = host::dismissNotice) { Text("OK") }
                            }
                        }
                    }
                }
                item(key = "about") {
                    Text(
                        "Panels Mike made to stay after his turn: floating cards, notifications and home-screen widgets. " +
                            "Their buttons run without Mike: phone settings, media keys, the panel's own list, counter, timer " +
                            "or note, and opening what you approved. A button that asks Mike starts a chat with him. " +
                            "Removing one here takes it off every surface.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (panels.isEmpty()) {
                    item(key = "empty") {
                        Text("No saved panels. Ask Mike for a floating card, a notification or a widget.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(ordered, key = { it.id }) { saved ->
                    PanelCard(
                        saved = saved,
                        now = now,
                        canFloat = surfaces.canFloat(),
                        problem = problems[saved.id],
                        revision = revisions[saved.id],
                        onApproveRevision = {
                            scope.launch {
                                val why = host.approveRevision(saved.id)
                                if (why == null) problems.remove(saved.id) else problems[saved.id] = why
                            }
                        },
                        onRejectRevision = { scope.launch { host.rejectRevision(saved.id) } },
                        canPin = saved.placement?.surface == PanelSurface.WIDGET && saved.status == SavedPanel.Status.ACTIVE &&
                            SavedPanelWidget.canPin(surfaces.context),
                        onApprove = {
                            scope.launch {
                                val why = host.approve(saved.id)
                                if (why == null) problems.remove(saved.id) else problems[saved.id] = why
                                if (why == null && saved.placement?.surface == PanelSurface.WIDGET) {
                                    when (SavedPanelWidget.requestPin(surfaces.context, host, saved.id)) {
                                        SavedPanelWidget.Pin.ASKED -> Unit
                                        SavedPanelWidget.Pin.ALREADY_PLACED -> problems[saved.id] = ALREADY_ON_HOME
                                        SavedPanelWidget.Pin.CANNOT -> problems[saved.id] =
                                            "Approved. Your launcher cannot place widgets for apps; add \"Mike · Panel\" from its widget list."
                                    }
                                }
                            }
                        },
                        onRemove = { scope.launch { host.remove(saved.id); problems.remove(saved.id) } },
                        onShow = { scope.launch { host.setHidden(saved.id, false) } },
                        onPin = {
                            when (SavedPanelWidget.requestPin(surfaces.context, host, saved.id)) {
                                SavedPanelWidget.Pin.ASKED -> problems.remove(saved.id)
                                SavedPanelWidget.Pin.ALREADY_PLACED -> problems[saved.id] = ALREADY_ON_HOME
                                SavedPanelWidget.Pin.CANNOT -> problems[saved.id] = "Your launcher cannot place widgets for apps."
                            }
                        },
                    )
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Remove every saved panel?") },
            text = { Text("Floating cards and notifications go at once. A widget stays on the home screen, empty, until you remove it there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    scope.launch { host.removeAll(); problems.clear() }
                }) { Text("Remove all") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelCard(
    saved: SavedPanel,
    now: Long,
    canFloat: Boolean,
    problem: String?,
    revision: PanelRevision?,
    onApproveRevision: () -> Unit,
    onRejectRevision: () -> Unit,
    canPin: Boolean,
    onApprove: () -> Unit,
    onRemove: () -> Unit,
    onShow: () -> Unit,
    onPin: () -> Unit,
) {
    val placement = saved.placement
    val pending = saved.status == SavedPanel.Status.PENDING
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(saved.title.ifBlank { "Untitled panel" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(placement?.words, statusWords(saved, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (saved.status == SavedPanel.Status.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            placement?.lockScreen?.let { LockScreenLine(it) }
            saved.blocked?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (placement?.surface == PanelSurface.FLOATING && !canFloat) {
                Text(
                    "A floating card needs Screen control or Display over other apps. Turn one on in Settings to see it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val panel = saved.panel
            if (panel != null && (pending || saved.status == SavedPanel.Status.ACTIVE)) {
                PanelDoes(panel, if (pending) "If you approve, its buttons can:" else "Its buttons:")
            }
            if (revision != null && saved.status == SavedPanel.Status.ACTIVE) {
                Text(
                    "Mike wants to change this panel. Until you approve, it keeps working as it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                PanelDoes(revision.panel, "If you approve, its buttons can:")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApproveRevision) { Text("Approve change") }
                    OutlinedButton(onClick = onRejectRevision) { Text("Keep as it is") }
                }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending) {
                    Button(onClick = onApprove) { Text("Approve") }
                    OutlinedButton(onClick = onRemove) { Text("Decline") }
                } else {
                    if (saved.hidden && saved.status == SavedPanel.Status.ACTIVE) Button(onClick = onShow) { Text("Show again") }
                    if (canPin) OutlinedButton(onClick = onPin) { Text("Add to home screen") }
                    OutlinedButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}

/** A notification's lock-screen view in one short line; the whole rule waits under Details. */
@Composable
private fun LockScreenLine(short: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(short, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
        if (open) {
            Text(PanelPlacement.LOCK_SCREEN_RULE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Each button by its label, what it does in plain words, and what it reads while shown. */
@Composable
private fun PanelDoes(panel: AgentPanel, heading: String) {
    val lines = PanelApprovalLines.of(panel)
    val reads = panel.binds.values.distinctBy { it.call }
    if (lines.isNotEmpty()) {
        Text(heading, style = MaterialTheme.typography.labelLarge)
        lines.forEach { line -> CallLine(line.label, line.words, line.calls) }
    }
    if (reads.isNotEmpty()) {
        Text("It reads, while it is on screen:", style = MaterialTheme.typography.labelLarge)
        reads.forEach { bind -> CallLine(null, PanelCallWords.describe(bind.call) + ", every ${bind.everySeconds} s", listOf(bind.call)) }
    }
}

/**
 * One button: its label, what it does in plain words, and under Details the
 * exact calls, whole and unwrapped (scroll sideways), so vague words cannot
 * hide what a tap does.
 */
@Composable
private fun CallLine(label: String?, words: String, calls: List<PanelCall>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        label?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        Text(words, style = MaterialTheme.typography.bodyMedium)
        if (calls.isNotEmpty()) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
            if (open) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    calls.forEach { call ->
                        Text(
                            "${call.tool} ${call.arguments}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun statusWords(saved: SavedPanel, now: Long): String = when (saved.status) {
    SavedPanel.Status.PENDING -> "Waits for your approval"
    SavedPanel.Status.BLOCKED -> "Blocked"
    SavedPanel.Status.ACTIVE -> {
        val left = saved.expiresAtMs?.let { ((it - now) / 1_000).coerceAtLeast(0) }
        listOfNotNull(
            if (saved.hidden) "Hidden" else "On",
            left?.let { "ends in " + clock(it) },
        ).joinToString(", ")
    }
}

private fun clock(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
    seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
    else -> "$seconds s"
}
