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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.LimitPhase
import dev.androidagent.core.LimitPlan
import dev.androidagent.core.LimitStop
import dev.androidagent.core.LimitTarget
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// A chat that ran out of usage used to end on a red line and wait for the user
// to notice, come back and type "continue". This card says what happened and
// when it frees up, and puts the three ways on from there one tap away: wait
// and go on by itself, move to an account or provider that has usage, or try
// again now. The sheet sets the same thing in advance, per chat.

private val LimitAmber = Color(0xFFF2B155)
private val LimitSheetFill = Color(0xFF1B1B1B)
private val LimitOptionFill = Color(0xFF252525)
private val LimitMuted = Color(0xFF8F8F8F)
private val LimitFree = Color(0xFF22C55E)

/** `14:30` today, `Tue 09:00` within the week, else the date. */
internal fun resetClock(atMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val pattern = when {
        at.toLocalDate() == now.toLocalDate() -> "HH:mm"
        atMillis - nowMillis < 6 * 24 * 3_600_000L -> "EEE HH:mm"
        else -> "d MMM, HH:mm"
    }
    return at.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}

/** `2h 14m`, `12m`, `under a minute`. */
internal fun limitTimeLeft(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0) + 59_999) / 60_000
    return when {
        millis <= 60_000 -> "under a minute"
        minutes < 60 -> "${minutes}m"
        minutes < 48 * 60 -> "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
        else -> "${minutes / (24 * 60)}d ${(minutes % (24 * 60)) / 60}h"
    }
}

/** Who a target is in one line: `Codex · dana@mail.com`. */
internal fun LimitOption.title(): String = listOfNotNull(provider, account).joinToString(" · ")

private fun ChatLimits.titleOf(target: LimitTarget): String =
    option(target)?.title() ?: if (target.engine == dev.androidagent.core.EngineKind.CLAUDE) "Claude" else "Codex"

/** Ticks once a second while shown, so countdowns move. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Under the last line of a chat that stopped at a usage limit. It stays until
 * the chat goes on, by itself or by a message.
 */
@Composable
internal fun LimitCard(stop: LimitStop, limits: ChatLimits, actions: AgentUiActions, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val waiting = stop.phase == LimitPhase.WAITING
    val switching = stop.phase == LimitPhase.SWITCHING
    val accent = if (waiting || switching) MaterialTheme.colorScheme.secondary else LimitAmber
    val resetsAt = stop.resetsAt?.takeIf { it > now }
    val alternatives = limits.options.filter { it.target.key != stop.target.key && !it.current }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            when {
                switching -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                waiting && stop.continueAt != null -> {
                    val span = (stop.continueAt!! - stop.stoppedAt).coerceAtLeast(1L)
                    LinearProgressIndicator(
                        progress = { ((now - stop.stoppedAt).toFloat() / span).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.16f),
                        drawStopIndicator = {},
                    )
                }
            }
            Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(34.dp).background(accent.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when {
                                switching -> Icons.Outlined.SwapHoriz
                                waiting -> Icons.Outlined.Timer
                                else -> Icons.Outlined.HourglassTop
                            },
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when {
                                switching -> "Moving to continue…"
                                waiting -> stop.continueAt?.let { "Continues at ${resetClock(it, now)}" } ?: "Waiting for the reset"
                                else -> "Usage limit reached"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                switching -> "The chat picks up where it stopped."
                                waiting -> stop.continueAt?.let { "In ${limitTimeLeft(it - now)} · picks up where it stopped" } ?: "Picks up where it stopped"
                                resetsAt != null -> "${limits.titleOf(stop.target)} · resets at ${resetClock(resetsAt, now)}, in ${limitTimeLeft(resetsAt - now)}"
                                else -> "${limits.titleOf(stop.target)} · reset time not reported"
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!switching) {
                        IconButton(onClick = actions.onDismissLimit, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = LimitMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                stop.reason?.takeIf { !waiting && !switching }?.let {
                    Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = LimitAmber, modifier = Modifier.padding(end = 8.dp))
                }
                if (!switching) Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (waiting) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = actions.onContinueNow, modifier = Modifier.weight(1f)) { Text("Continue now") }
                            TextButton(onClick = actions.onCancelLimitWait) { Text("Cancel") }
                        }
                    } else {
                        FilledTonalButton(onClick = actions.onContinueAtReset, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(resetsAt?.let { "Continue at ${resetClock(it + 60_000, now)}" } ?: "Continue when it resets")
                        }
                        if (alternatives.isNotEmpty()) {
                            Text("Or continue now with", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                alternatives.forEach { option ->
                                    TargetChip(option, now) { actions.onSwitchAfterLimit(option.target) }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!waiting) TextButton(onClick = actions.onContinueNow) { Text("Try again now") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = actions.onOpenLimitPlan) {
                            Icon(Icons.Outlined.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (limits.plan.active) "Edit plan" else "Set up for next time")
                        }
                    }
                }
            }
        }
    }
}

/** An account or provider to move to, with whether it has usage now. */
@Composable
private fun TargetChip(option: LimitOption, now: Long, onClick: () -> Unit) {
    val freeAt = option.freeAt?.takeIf { it > now }
    val ready = freeAt == null
    Surface(
        onClick = onClick,
        enabled = ready,
        shape = RoundedCornerShape(16.dp),
        color = if (ready) MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f) else Color.Transparent,
        border = BorderStroke(1.dp, if (ready) MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f) else LimitMuted.copy(alpha = 0.4f)),
        modifier = Modifier.semantics {
            contentDescription = if (ready) "Continue with ${option.title()}" else "${option.title()}, out of usage until ${resetClock(freeAt!!, now)}"
        },
    ) {
        Row(
            Modifier.heightIn(min = 34.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProviderDot(option.provider, ready)
            Text(
                option.title(),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
                color = if (ready) MaterialTheme.colorScheme.onSurface else LimitMuted,
            )
            if (!ready) Text("until ${resetClock(freeAt!!, now)}", fontSize = 11.sp, color = LimitMuted, maxLines = 1)
        }
    }
}

/** The provider's initial in a small round mark, green when it has usage now. */
@Composable
private fun ProviderDot(provider: String, ready: Boolean) {
    val tint = if (ready) LimitFree else LimitMuted
    Box(Modifier.size(18.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Text(provider.take(1), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

/** Beside the model chip while the chat has a plan, or is waiting for a reset. */
@Composable
internal fun LimitChip(limits: ChatLimits, onClick: () -> Unit) {
    val waitingUntil = limits.stop?.takeIf { it.phase == LimitPhase.WAITING }?.continueAt
    val teal = MaterialTheme.colorScheme.secondary
    val now = if (waitingUntil != null) rememberNow() else System.currentTimeMillis()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = teal.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, teal.copy(alpha = 0.45f)),
        modifier = Modifier.semantics {
            contentDescription = waitingUntil?.let { "Continues at ${resetClock(it, now)}. Edit the usage limit plan" }
                ?: "Usage limit plan is on. Edit it"
        },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (waitingUntil != null) Icons.Outlined.Timer else Icons.Outlined.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp), tint = teal)
            Text(waitingUntil?.let { resetClock(it, now) } ?: "Auto", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = teal)
        }
    }
}

/**
 * What this chat does when it hits a usage limit: the accounts and providers
 * to move to, in order, and whether to wait for the reset. Saved per chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LimitPlanSheet(limits: ChatLimits, onSave: (LimitPlan) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val now = rememberNow()
    val choices = limits.options.filter { !it.current }
    // Picked targets in the plan's order, then the rest in the order offered.
    val picked = remember(limits.plan, limits.options) {
        mutableStateListOf<String>().apply { addAll(limits.plan.switchTo.map { it.key }.filter { key -> choices.any { it.target.key == key } }) }
    }
    var waitForReset by remember(limits.plan) { mutableStateOf(limits.plan.continueAtReset) }
    val ordered = picked.mapNotNull { key -> choices.firstOrNull { it.target.key == key } } + choices.filter { it.target.key !in picked }
    val current = limits.options.firstOrNull { it.current }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = LimitSheetFill) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("When this chat hits a usage limit", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Mike picks the task up where it stopped. Nothing that already ran is run again.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = LimitMuted,
                )
            }
            current?.let {
                Text("Now on ${it.title()}", fontSize = 12.sp, color = LimitMuted)
            }
            SectionLabel("Move to, in this order")
            if (choices.isEmpty()) {
                Text(
                    "Add another Codex account or set up Claude in Settings to have somewhere to move to.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = LimitMuted,
                )
            } else Surface(shape = RoundedCornerShape(16.dp), color = LimitOptionFill) {
                Column {
                    ordered.forEachIndexed { index, option ->
                        val key = option.target.key
                        val position = picked.indexOf(key)
                        PlanRow(
                            option = option,
                            now = now,
                            order = position.takeIf { it >= 0 }?.plus(1),
                            canRaise = position > 0,
                            canLower = position >= 0 && position < picked.size - 1,
                            onToggle = { if (position >= 0) picked.remove(key) else picked.add(key) },
                            onRaise = { picked.removeAt(position); picked.add(position - 1, key) },
                            onLower = { picked.removeAt(position); picked.add(position + 1, key) },
                        )
                        if (index < ordered.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp).background(Color.White.copy(alpha = 0.06f)))
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = LimitOptionFill) {
                Row(
                    Modifier.fillMaxWidth().clickable { waitForReset = !waitForReset }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Continue when usage resets", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (picked.isEmpty()) "Waits for the reset, then goes on by itself, even with the app closed."
                            else "If none of the above has usage, waits for the first reset and goes on by itself.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = LimitMuted,
                        )
                    }
                    Switch(checked = waitForReset, onCheckedChange = { waitForReset = it })
                }
            }
            if (picked.any { key -> choices.firstOrNull { it.target.key == key }?.target?.engine != current?.target?.engine }) {
                Text(
                    "Moving to another provider tells it what was said in this chat first.",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = LimitMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (limits.plan.active) {
                    TextButton(onClick = { onSave(LimitPlan()); onDismiss() }) { Text("Turn off") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val targets = picked.mapNotNull { key -> choices.firstOrNull { it.target.key == key }?.target }
                        onSave(LimitPlan(continueAtReset = waitForReset, switchTo = targets))
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(Locale.ROOT), fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = LimitMuted)
}

@Composable
private fun PlanRow(
    option: LimitOption,
    now: Long,
    order: Int?,
    canRaise: Boolean,
    canLower: Boolean,
    onToggle: () -> Unit,
    onRaise: () -> Unit,
    onLower: () -> Unit,
) {
    val freeAt = option.freeAt?.takeIf { it > now }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = order != null,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary),
        )
        Column(Modifier.weight(1f).padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(option.provider, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                order?.let { OrderBadge(it) }
            }
            Text(
                listOfNotNull(option.account, if (freeAt == null) "has usage" else "out until ${resetClock(freeAt, now)}").joinToString(" · "),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (freeAt == null) LimitMuted else LimitAmber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (order != null) {
            ReorderButton(Icons.Outlined.KeyboardArrowUp, "Move ${option.title()} up", canRaise, onRaise)
            ReorderButton(Icons.Outlined.KeyboardArrowDown, "Move ${option.title()} down", canLower, onLower)
        }
    }
}

@Composable
private fun OrderBadge(order: Int) {
    val teal = MaterialTheme.colorScheme.secondary
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(teal.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(if (order == 1) "1st" else if (order == 2) "2nd" else if (order == 3) "3rd" else "${order}th", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = teal)
    }
}

@Composable
private fun ReorderButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = if (enabled) MaterialTheme.colorScheme.onSurface else LimitMuted.copy(alpha = 0.35f))
    }
}
