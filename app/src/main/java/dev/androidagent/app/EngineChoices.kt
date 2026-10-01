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

package dev.androidagent.app

import dev.androidagent.core.AgentModel
import dev.androidagent.core.ChatSession
import dev.androidagent.core.EngineKind
import dev.androidagent.core.UsageLimit

/**
 * One engine's model list and the user's pick from it.
 *
 * Codex and Claude offer different models, so each engine keeps its own
 * pick. The model menu lists both engines' models; the chip shows the pick
 * of the engine the open chat runs on.
 */
data class ModelChoice(
    val catalog: List<AgentModel> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
) {
    /**
     * A fresh list from the engine. A pick the list no longer has is
     * dropped, and so is an effort the model does not offer. An empty list
     * keeps the pick: the engine is simply not ready yet.
     */
    fun withCatalog(next: List<AgentModel>): ModelChoice {
        if (next.isEmpty()) return copy(catalog = next)
        val kept = model?.let { id -> next.firstOrNull { it.id == id } }
        return ModelChoice(next, kept?.id, kept?.let { normalize(it, effort) })
    }

    /** Pick a model. The effort stays only if the new model offers it. */
    fun select(id: String): ModelChoice =
        copy(model = id, effort = catalog.firstOrNull { it.id == id }?.let { normalize(it, effort) })

    /** Pick an effort the chosen model offers; null is Auto. */
    fun selectEffort(value: String?): ModelChoice {
        val entry = entry() ?: return this
        return copy(effort = value?.takeIf { wanted -> entry.reasoningEfforts.any { it.value == wanted } })
    }

    /** The effort a turn sends: the pick, else the model's own default, else none. */
    val turnEffort: String?
        get() {
            val entry = entry() ?: return null
            fun offered(value: String?) = value?.takeIf { wanted -> entry.reasoningEfforts.any { it.value == wanted } }
            return offered(effort) ?: offered(entry.defaultReasoningEffort)
        }

    /** Plan mode rides on a model name, so it falls back to the first one offered. */
    fun modelFor(planMode: Boolean): String? = model ?: if (planMode) catalog.firstOrNull()?.id else null

    private fun entry(): AgentModel? = model?.let { id -> catalog.firstOrNull { it.id == id } }

    // Auto stays the first choice, so the engine applies its own default. A
    // stale explicit level is cleared rather than guessed.
    private fun normalize(model: AgentModel, requested: String?): String? =
        requested?.takeIf { value -> model.reasoningEfforts.any { it.value == value } }
}

/** Every engine's model pick and last quota. */
data class EngineChoices(
    private val models: Map<EngineKind, ModelChoice> = emptyMap(),
    private val quotas: Map<EngineKind, List<UsageLimit>> = emptyMap(),
) {
    fun of(kind: EngineKind): ModelChoice = models[kind] ?: ModelChoice()
    fun update(kind: EngineKind, change: (ModelChoice) -> ModelChoice) = copy(models = models + (kind to change(of(kind))))
    fun limits(kind: EngineKind): List<UsageLimit> = quotas[kind].orEmpty()
    fun withLimits(kind: EngineKind, limits: List<UsageLimit>) = copy(quotas = quotas + (kind to limits))

    /** One list for the model menu: the models of each engine in [kinds], Codex first. */
    fun offered(kinds: Collection<EngineKind>): List<AgentModel> =
        EngineKind.entries.filter { it in kinds }.flatMap { kind -> of(kind).catalog.map { if (it.engine == kind) it else it.copy(engine = kind) } }
}

/** What a chat's engine decides in the app. */
object ChatEngines {
    fun of(sessions: List<ChatSession>, id: String?): EngineKind =
        sessions.firstOrNull { it.id == id }?.engine ?: EngineKind.CODEX

    /**
     * The engine that runs the model [id] picked from [offered]. The chat's
     * own engine wins when both list the id; an id nobody lists stays there too.
     */
    fun engineOfModel(offered: List<AgentModel>, id: String, active: EngineKind): EngineKind =
        offered.firstOrNull { it.id == id && it.engine == active }?.engine
            ?: offered.firstOrNull { it.id == id }?.engine
            ?: active

    /**
     * Realtime voice is Codex's. A Codex chat always shows the voice button,
     * which then asks for the sign-in; a chat on another engine can talk too,
     * on Codex, once Codex is signed in (see [VoiceConversation.begin]).
     */
    fun hasVoice(kind: EngineKind, codexSignedIn: Boolean): Boolean = kind == EngineKind.CODEX || codexSignedIn

    /** A stored engine name, or Codex for anything unknown. */
    fun parse(value: String?): EngineKind = EngineKind.values().firstOrNull { it.name == value } ?: EngineKind.CODEX
}
