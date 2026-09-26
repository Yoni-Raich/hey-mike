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

package dev.androidagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Decides which rules an event fires, and says why the others did not.
 *
 * Pure and side-effect free on purpose. It records nothing, runs nothing and
 * touches no file, so the same call serves the live path and `mode:"test"`: a
 * dry run cannot consume a rule's daily quota, and a live run cannot get a
 * different answer from the one the model was shown.
 *
 * Every rule is reported, fired or not. A rule that silently does nothing is
 * the characteristic failure of this whole feature — the person wrote it, it
 * looks right, and nothing happens at 19:00 — so "did not fire because the
 * cooldown has 14 minutes left" is a first-class output rather than a debug log.
 *
 * Firing here means *decided*, not *done*. The host takes [Outcome.Fired] and
 * executes it, then calls [AutomationHistory.record]. Keeping those apart is
 * what lets the host refuse an action it cannot serve right now without the
 * journal already believing it ran.
 */
class AutomationEvaluator(private val history: AutomationHistory) {

    /** Why a rule did not fire. [retryable] separates "not now" from "not this". */
    enum class Skip(val wire: String, val retryable: Boolean) {
        DISABLED("disabled", retryable = false),
        TRIGGER("trigger_did_not_match", retryable = false),
        CONDITION("condition_failed", retryable = false),
        COOLDOWN("cooling_down", retryable = true),
        DAILY_LIMIT("daily_limit_reached", retryable = true),
        NEEDS_USER("needs_you", retryable = true),
        NEEDS_AGENT("needs_the_agent", retryable = true),
    }

    sealed interface Outcome {
        val rule: AutomationRule

        /**
         * The rule fired. [actions] already have their placeholders filled from
         * [exported], and [exported] is every field of the event that will
         * leave this phone — nothing else from it is reachable downstream.
         */
        data class Fired(
            override val rule: AutomationRule,
            val actions: List<AutomationAction>,
            val exported: Map<String, String>,
        ) : Outcome

        data class Skipped(
            override val rule: AutomationRule,
            val reason: Skip,
            val detail: String,
        ) : Outcome
    }

    fun evaluate(
        rules: List<AutomationRule>,
        event: AutomationEvent,
        context: AutomationContext,
    ): List<Outcome> = rules.map { rule -> evaluateOne(rule, event, context) }

    /** The rules this event actually fires, in declaration order. */
    fun fired(
        rules: List<AutomationRule>,
        event: AutomationEvent,
        context: AutomationContext,
    ): List<Outcome.Fired> = evaluate(rules, event, context).filterIsInstance<Outcome.Fired>()

    private fun evaluateOne(
        rule: AutomationRule,
        event: AutomationEvent,
        context: AutomationContext,
    ): Outcome {
        fun skip(reason: Skip, detail: String) = Outcome.Skipped(rule, reason, detail)

        if (!rule.enabled) return skip(Skip.DISABLED, "The rule is turned off.")

        // A manual run names its rule, and naming it *is* the trigger: the
        // person or the agent supplied what the clock or the listener would
        // have. Without this a scheduled rule could only ever be proved by
        // waiting until 19:00, which is not a feedback loop anyone checks a
        // standing rule with. Another rule's manual run still fires nothing,
        // and everything after this — the conditions, the cooldown, the daily
        // limit, the attention gate — applies exactly as it would have.
        val manualForThisRule = event is AutomationEvent.Manual &&
            event.ruleId.equals(rule.id, ignoreCase = true)
        if (event is AutomationEvent.Manual && !manualForThisRule) {
            return skip(Skip.TRIGGER, "This run was for \"${event.ruleId}\".")
        }
        if (!manualForThisRule && event is AutomationEvent.Clock && rule.trigger.kind == AutomationTriggerKind.SCHEDULE) {
            // Due-ness needs the history: a late alarm still owes its slot, and
            // a slot already served is not owed twice. See [AutomationSchedule.dueSlot].
            val schedule = rule.trigger.schedule
            val slot = schedule?.dueSlot(
                now = event.at,
                lastFiredAt = history.lastFiredAt(rule.id),
                savedAt = rule.savedAt,
                toleranceMs = rule.guard.validForMs,
            )
            if (slot == null) return skip(Skip.TRIGGER, notDue(rule, event.at))
        } else if (!manualForThisRule && !rule.trigger.matches(event)) {
            return skip(
                Skip.TRIGGER,
                "A \"${event.kind.wire}\" event does not match ${rule.trigger.describe().content}.",
            )
        }

        rule.conditions.firstOrNull { !it.holds(event, context) }?.let { failed ->
            return skip(Skip.CONDITION, "The rule needs ${failed.describe()}, and that is not true now.")
        }

        // The guard runs after the conditions so the explanation names the real
        // reason. A rule blocked by its cooldown *and* out of hours should say
        // out of hours: the cooldown will pass on its own, the hour will not.
        val nowMs = context.now.toInstant().toEpochMilli()
        history.lastFiredAt(rule.id)?.let { last ->
            val elapsed = nowMs - last
            if (elapsed in 0 until rule.guard.cooldownMs) {
                val remaining = (rule.guard.cooldownMs - elapsed + 59_999L) / 60_000L
                return skip(
                    Skip.COOLDOWN,
                    "It already ran less than ${rule.guard.cooldownMs / 60_000L} minute(s) ago; " +
                        "$remaining more to go.",
                )
            }
        }
        val today = history.firedOn(rule.id, context.now.toLocalDate())
        if (today >= rule.guard.maxPerDay) {
            return skip(
                Skip.DAILY_LIMIT,
                "It has already run ${rule.guard.maxPerDay} time(s) today, which is its limit.",
            )
        }

        // The attention gate. A rule that wants to talk to you is held until you
        // can answer rather than fired at a locked phone, and a rule that needs
        // a thinking turn is held when no turn can run — in both cases held,
        // not dropped, because the trigger really did happen.
        when (rule.attention) {
            AutomationAttention.USER -> if (!context.userReachable) {
                return skip(
                    Skip.NEEDS_USER,
                    "It ends in something that needs you (${rule.actions.first { it.attention == AutomationAttention.USER }.kind.wire}), " +
                        "and you are not reachable right now.",
                )
            }
            AutomationAttention.MODEL -> if (!context.agentAvailable) {
                return skip(Skip.NEEDS_AGENT, "It needs a turn, and the agent cannot run one right now.")
            }
            AutomationAttention.NONE -> Unit
        }

        // Only the fields the actions actually name are read out of the event.
        // This is the whole privacy contract in three lines: everything else
        // the event carried stops here.
        val available = event.fields() + context.fields()
        val exported = rule.exportedFields.mapNotNull { field -> available[field]?.let { field to it } }.toMap()
        return Outcome.Fired(
            rule = rule,
            actions = rule.actions.map { it.bind(exported) },
            exported = exported,
        )
    }

    /** Why a schedule owes nothing right now, in the terms [AutomationSchedule.dueSlot] decided it. */
    private fun notDue(rule: AutomationRule, now: java.time.ZonedDateTime): String {
        val schedule = rule.trigger.schedule ?: return "It has no schedule."
        val lastFired = history.lastFiredAt(rule.id)
        if (schedule.everyMinutes != null) {
            return "It runs ${schedule.describe()}, and that long has not passed since it last ran or was saved."
        }
        val slot = schedule.lastSlotAtOrBefore(now) ?: return "It is not one of its days."
        val slotMs = slot.toInstant().toEpochMilli()
        val time = "%02d:%02d".format(slot.hour, slot.minute)
        return when {
            lastFired != null && lastFired >= slotMs -> "It already ran for its $time slot."
            rule.savedAt != null && rule.savedAt > slotMs -> "It was saved after its $time slot; it waits for the next one."
            else -> "Its $time slot passed more than ${rule.guard.validForMs / 60_000L} minutes ago, so it waits for the next one."
        }
    }

    companion object {
        /** One outcome as the tool reply renders it. */
        fun toJson(outcome: Outcome): JsonObject = buildJsonObject {
            put("rule", outcome.rule.id)
            when (outcome) {
                is Outcome.Fired -> {
                    put("fires", true)
                    put("attention", outcome.rule.attention.wire)
                    put("then", JsonArray(outcome.actions.map { it.toJson() }))
                    if (outcome.exported.isNotEmpty()) {
                        put(
                            "usedFromTheEvent",
                            JsonObject(outcome.exported.mapValues { (_, value) -> JsonPrimitive(value) }),
                        )
                    }
                }
                is Outcome.Skipped -> {
                    put("fires", false)
                    put("why", outcome.reason.wire)
                    put("detail", outcome.detail)
                    put("tryAgainLater", outcome.reason.retryable)
                }
            }
        }
    }
}
