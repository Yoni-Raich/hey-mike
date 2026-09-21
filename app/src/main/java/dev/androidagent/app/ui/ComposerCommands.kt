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

import dev.androidagent.core.AgentSkill
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.UsageSummary
import kotlin.math.roundToInt

// What `/` offers besides skills. Codex has no call that lists its own slash
// commands (its terminal keeps them in its own code), so this is the app's
// set, each one backed by something the app or app-server can really do.
internal enum class ComposerCommand(val id: String, val label: String, val description: String) {
    NEW("new", "New chat", "Start fresh in a new chat"),
    COMPACT("compact", "Compact", "Summarize the chat to free up context"),
    PLAN("plan", "Plan mode", "Agree on a plan before it touches your phone"),
    MODEL("model", "Model", "Choose model and reasoning"),
    RENAME("rename", "Rename", "Give this chat a new name"),
    STATUS("status", "Status", "Model, limits and phone connection"),
}

/** What a leading `/` (skills and commands) or `$` (skills only) is searching for. */
internal data class MenuQuery(val text: String, val skillsOnly: Boolean)

/** The open menu's query, or null when the draft is not a single `/word` or `$word`. */
internal fun menuQuery(draft: String): MenuQuery? {
    val lead = draft.firstOrNull() ?: return null
    if (lead != '/' && lead != '$') return null
    val rest = draft.substring(1)
    if (rest.any { it.isWhitespace() }) return null
    return MenuQuery(rest, skillsOnly = lead == '$')
}

internal fun AgentSkill.matches(query: String): Boolean =
    query.isEmpty() || label.contains(query, ignoreCase = true) || name.contains(query, ignoreCase = true)

internal fun ComposerCommand.matches(query: String): Boolean =
    query.isEmpty() || label.contains(query, ignoreCase = true) || id.contains(query, ignoreCase = true)

/** The command a whole draft names, such as "/compact", or null. */
internal fun exactCommand(draft: String): ComposerCommand? =
    ComposerCommand.values().firstOrNull { "/${it.id}".equals(draft.trim(), ignoreCase = true) }

/** A picked skill's default prompt replaces an empty field or the query that found it; typed words stay. */
internal fun draftAfterPicking(skill: AgentSkill, draft: String): String =
    if (draft.isBlank() || menuQuery(draft) != null) skill.defaultPrompt.orEmpty() else draft

/** Codex wants `$name` in the text beside the skill item, as its terminal sends it. */
internal fun withSkill(skill: AgentSkill?, text: String): String =
    if (skill == null) text else "\$${skill.name} $text".trimEnd()

/** Where [query] sits in [text], ignoring case, so the menu can light it. */
internal fun matchRange(text: String, query: String): IntRange? {
    if (query.isEmpty()) return null
    val at = text.indexOf(query, ignoreCase = true)
    return if (at < 0) null else at until at + query.length
}

/** A skill's `#RRGGBB` brand colour as opaque ARGB, or null when it has none. */
internal fun brandArgb(hex: String?): Long? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { 0xFF000000L or it }
}

/**
 * One line for /status. Context use is left out on purpose: the engine reports
 * the thread's running token total, not what is in the window now.
 */
internal fun statusSummary(state: AgentUiState, nowSeconds: Long = System.currentTimeMillis() / 1000L): String {
    val parts = mutableListOf<String>()
    val model = state.selectedModel?.removePrefix("gpt-") ?: "Default model"
    parts += model + state.selectedReasoningEffort?.let { " · $it" }.orEmpty()
    if (state.planMode) parts += "Plan mode"
    UsageSummary.primary(UsageSummary.windows(state.usageLimits, nowSeconds))?.let { window ->
        window.fraction?.let { used -> parts += "${window.label} ${100 - (used * 100).roundToInt()}% left" }
    }
    val a11y = state.a11yStatus.connected
    val adb = state.adbStatus.phase == ConnectionPhase.CONNECTED
    parts += when {
        a11y && adb -> "Accessibility and ADB on"
        a11y -> "Accessibility on"
        adb -> "ADB connected"
        else -> "Phone control off"
    }
    return parts.joinToString(" · ")
}
