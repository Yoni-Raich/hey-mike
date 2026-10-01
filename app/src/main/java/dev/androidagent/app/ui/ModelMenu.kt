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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.AgentModel

// The model choice as a small menu over the chip, not a sheet over half the
// screen: the intelligence levels first, because that is what changes from
// task to task, and the model behind one more tap, because it rarely does.

/** "xhigh" as it reads in a menu: "Extra High". */
internal fun effortLabel(value: String): String = when (value.trim().lowercase()) {
    "xhigh", "x-high", "extra_high", "extra-high", "extra high" -> "Extra High"
    else -> words(value)
}

/** "gpt-6-luna" as the chip shows it: "6 Luna". */
internal fun shortModelName(id: String): String = words(id.removePrefix("gpt-"))

/**
 * "gpt-6-luna" as a menu row: "GPT-6 Luna". The engine's own display name wins
 * when it says something other than the id.
 */
internal fun modelTitle(id: String, displayName: String? = null): String {
    displayName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(id, ignoreCase = true) }?.let { return it }
    return if (id.startsWith("gpt-")) "GPT-" + shortModelName(id) else shortModelName(id)
}

private fun words(text: String): String =
    text.trim().split('-', '_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/** The level a turn runs at: the one chosen, else the one the model advertises as its own. */
internal fun effectiveEffort(model: AgentModel?, selected: String?): String? {
    model ?: return selected
    val offered = model.reasoningEfforts.map { it.value }
    return selected?.takeIf { it in offered } ?: model.defaultReasoningEffort?.takeIf { it in offered }
}

private val MenuFill = Color(0xFF2E2E2E)
private val MenuMuted = Color(0xFF9A9A9A)

private enum class ModelPage { MAIN, MODELS }

@Composable
internal fun ModelMenu(state: AgentUiState, actions: AgentUiActions, expanded: Boolean, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(ModelPage.MAIN) }
    // Reopen on the intelligence levels, not wherever it was last left.
    LaunchedEffect(expanded) { if (!expanded) page = ModelPage.MAIN }
    val model = state.modelCatalog.firstOrNull { it.id == state.selectedModel }
    val efforts = model?.reasoningEfforts.orEmpty()
    val current = effectiveEffort(model, state.selectedReasoningEffort)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(264.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        containerColor = MenuFill,
    ) {
        Column {
            if (page == ModelPage.MAIN) {
                if (efforts.isNotEmpty()) {
                    MenuHeader("Intelligence")
                    efforts.forEach { option ->
                        ChoiceRow(effortLabel(option.value), selected = option.value == current) {
                            // The model's own level is Auto again, so a server-side default change still applies.
                            actions.onReasoningEffortSelected(option.value.takeIf { it != model?.defaultReasoningEffort })
                            onDismiss()
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Color(0xFF444444))
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Model", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                state.selectedModel?.let { modelTitle(it, model?.displayName) } ?: "Choose a model",
                                fontSize = 13.sp,
                                color = MenuMuted,
                            )
                        }
                    },
                    trailingIcon = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                    onClick = { page = ModelPage.MODELS },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Model", fontSize = 14.sp, color = MenuMuted) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = MenuMuted) },
                    onClick = { page = ModelPage.MAIN },
                )
                state.availableModels.forEach { id ->
                    val title = modelTitle(id, state.modelCatalog.firstOrNull { it.id == id }?.displayName)
                    ChoiceRow(title, selected = id == state.selectedModel) {
                        actions.onModelSelected(id)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(text, fontSize = 14.sp, color = MenuMuted, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface) },
        trailingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
        } else {
            null
        },
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected },
    )
}
