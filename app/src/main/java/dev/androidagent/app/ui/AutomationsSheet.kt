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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androidagent.core.AutomationAttention
import dev.androidagent.core.AutomationSummary

/**
 * Every standing rule, and one rule's own screen.
 *
 * Two levels in one sheet, the way Settings already does it, rather than a new
 * navigation idea: back walks the rule then the sheet. This is the screen the
 * strip cannot be — the strip is a glance with room for three chips and one
 * sentence, and everything that does not fit is here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomationsSheet(state: AgentUiState, actions: AgentUiActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val overview = state.automations.overview
    val selected = selectedId?.let { id -> overview.summaries.firstOrNull { it.id == id } }
    val animate = animationsEnabled()

    ModalBottomSheet(
        onDismissRequest = actions.onCloseAutomations,
        sheetState = sheetState,
        // Same reason as Settings: the sheet's own back dispatcher would
        // otherwise close the whole thing from a rule's screen.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.fillMaxHeight(0.94f),
    ) {
        BackHandler { if (selected != null) selectedId = null else actions.onCloseAutomations() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (selected == null) actions.onCloseAutomations() else selectedId = null }) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = if (selected == null) "Close rules" else "Back to rules",
                    )
                }
                Text(
                    selected?.name ?: "Standing rules",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            AnimatedContent(
                targetState = selected?.id,
                transitionSpec = {
                    if (!animate) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else if (targetState == null) {
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                    } else {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                    }
                },
                label = "automation-route",
            ) { target ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val rule = target?.let { id -> overview.summaries.firstOrNull { it.id == id } }
                    if (rule == null) RuleList(state, actions) { selectedId = it } else RuleDetail(rule, state, actions)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The rules, grouped by what they need from you.
 *
 * Grouped rather than listed because the question a person opens this with is
 * never "what rules do I have" — it is "is anything wrong". A rule that cannot
 * run sits at the top under its own heading, with the reason spelled out.
 */
@Composable
private fun ColumnScope.RuleList(state: AgentUiState, actions: AgentUiActions, onOpen: (String) -> Unit) {
    val overview = state.automations.overview
    if (overview.isEmpty) {
        Explanation(
            "No rules yet. Ask Mike for one — \"every day at seven, post the evening update\", or " +
                "\"when Dad messages me after hours, tell him I can't talk\".",
        )
        return
    }

    val blocked = overview.summaries.filter { it.status == AutomationSummary.Status.BLOCKED }
    val running = overview.summaries.filter { it.status == AutomationSummary.Status.ON }
    val off = overview.summaries.filter { it.status == AutomationSummary.Status.OFF }

    RuleGroup("Needs you", RuleBlocked, blocked, onOpen)
    RuleGroup("Running", RuleReady, running, onOpen)
    RuleGroup("Turned off", RuleOff, off, onOpen)

    // The permissions live in Settings, but the reason a rule is amber is here,
    // so the way out is here too.
    if (blocked.isNotEmpty() && !state.automations.notificationAccess) {
        Button(onClick = actions.onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
            Text("Allow notification access")
        }
    }
}

@Composable
private fun ColumnScope.RuleGroup(
    title: String,
    accent: Color,
    rules: List<AutomationSummary>,
    onOpen: (String) -> Unit,
) {
    if (rules.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
    rules.forEach { rule -> RuleRow(rule) { onOpen(rule.id) } }
}

@Composable
private fun RuleRow(rule: AutomationSummary, onClick: () -> Unit) {
    val blocked = rule.status == AutomationSummary.Status.BLOCKED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (blocked) Color(0xFF241E14) else MaterialTheme.colorScheme.surface,
        border = if (blocked) BorderStroke(1.dp, Color(0xFF4A3A24)) else null,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.padding(top = 5.dp)) { StatusDot(rule.status, 8.dp) }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        rule.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AttentionBadge(rule.attention)
                }
                Text(
                    rule.trigger,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    rule.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blocked) RuleBlocked else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Who has to be awake, shown only when the answer is not "nobody". */
@Composable
private fun AttentionBadge(attention: AutomationAttention) {
    val label = when (attention) {
        AutomationAttention.NONE -> return
        AutomationAttention.MODEL -> "THINKS"
        AutomationAttention.USER -> "ASKS YOU"
    }
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (attention == AutomationAttention.USER) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (attention == AutomationAttention.USER) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

/**
 * One rule: what it does, what it costs, what it sends, and the switch.
 *
 * The chain is the order the rule fires in, which is the only ordering that
 * explains itself. What leaves the phone is stated rather than implied — the
 * format already knows it exactly, so there is no reason to make anyone guess.
 */
@Composable
private fun ColumnScope.RuleDetail(rule: AutomationSummary, state: AgentUiState, actions: AgentUiActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(rule.status)
                Text(
                    rule.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rule.status == AutomationSummary.Status.BLOCKED) {
                        RuleBlocked
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Switch(
            checked = rule.status != AutomationSummary.Status.OFF,
            onCheckedChange = { on -> actions.onToggleRule(rule.id, on) },
        )
    }

    ChainStep("WHEN", MaterialTheme.colorScheme.onSecondaryContainer, rule.whenLine, last = rule.conditions.isEmpty() && rule.actions.isEmpty())
    rule.conditions.forEachIndexed { index, line ->
        ChainStep("AND", MaterialTheme.colorScheme.onSurfaceVariant, line, last = false)
    }
    rule.actions.forEachIndexed { index, line ->
        ChainStep(
            if (index == 0) "THEN" else "AND THEN",
            MaterialTheme.colorScheme.onTertiaryContainer,
            line,
            last = index == rule.actions.lastIndex,
            cost = if (index == rule.actions.lastIndex) rule.cost else null,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    Text(
        "WHAT LEAVES YOUR PHONE",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(17.dp), tint = RuleReady)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(rule.sends, style = MaterialTheme.typography.bodyMedium)
                Text(
                    rule.sendsNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (rule.status == AutomationSummary.Status.BLOCKED) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Why it isn't running", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        rule.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Button(
        onClick = { actions.onRunRule(rule.id) },
        enabled = rule.status != AutomationSummary.Status.OFF,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Run it now")
    }
    Explanation(
        "Running it now stands in for its trigger. Everything else still applies — the hours it is " +
            "allowed, how often it may run — so it may decide not to.",
    )
}

/** One link in the when/and/then chain, with the rail that joins it to the next. */
@Composable
private fun ColumnScope.ChainStep(
    label: String,
    accent: Color,
    body: String,
    last: Boolean,
    cost: String? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.size(10.dp))
            }
            if (!last) {
                Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.width(2.dp)) {
                    Box(Modifier.height(30.dp))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = if (last) 0.dp else 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (cost != null) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        cost.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
