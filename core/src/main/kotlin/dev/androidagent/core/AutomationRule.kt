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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A standing rule: when something happens, and the conditions hold, do this.
 *
 * A workflow answers "how do I do this on this phone". A rule answers "when
 * should it happen, and who has to be awake for it" — the two are deliberately
 * separate files. A rule that inlined its steps would be a workflow with a
 * clock bolted on, and every improvement to the runner would stop at the
 * automation boundary.
 *
 * Three properties matter more than expressiveness here, because a rule runs
 * when nobody is watching:
 *
 * **What it may do is a closed set.** [AutomationActionKind] is the whole
 * vocabulary. A rule cannot run shell, cannot call an arbitrary tool, and
 * cannot name a step sequence of its own; it names a workflow, an intent, a
 * prompt, or a person. Anything wider would be a second agent loop with none
 * of the coordinator's approval routing or revoke semantics — the same reason
 * [WorkflowEngine]'s step set is closed.
 *
 * **What it may read is what it says it reads.** A rule that matches on a
 * notification is matched here, on the phone, against the whole event. What
 * leaves for the model is only the fields its actions actually interpolate
 * (see [exportedFields]). A rule that triggers on a WhatsApp message but whose
 * prompt never says `{{notification.text}}` never sends that text anywhere.
 * This is the standing decision in `AgentAccessibilityService`, which reads
 * none of the events it receives, carried into a feature that has to read some
 * of them.
 *
 * **It says who has to be awake.** [attention] is derived from the actions, not
 * declared: a rule that only runs a workflow needs nothing, one that needs a
 * thinking turn says `model`, and one that wants to talk to you says `user`.
 * The host reads that before it fires anything, so "post at 19:00" never wakes
 * a voice call and "ask me about this" is never answered by a machine at 3am.
 */
data class AutomationRule(
    val id: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val description: String,
    val trigger: AutomationTrigger,
    /** ANDed. An empty list means the trigger alone is the whole rule. */
    val conditions: List<AutomationCondition> = emptyList(),
    val actions: List<AutomationAction>,
    val guard: AutomationGuard = AutomationGuard(),
    /** Set when the rule was read from a file, for the failure report. */
    val source: String? = null,
    /**
     * When the file was last written, epoch millis, or null for a rule that
     * never touched disk.
     *
     * Not part of the format. A schedule reads it so a rule saved at 19:05
     * does not catch up on the 19:00 it was never around for, and so an
     * interval counts from when it was written rather than from nothing.
     */
    val savedAt: Long? = null,
) {

    /**
     * The highest attention any of its actions needs.
     *
     * Derived rather than declared on purpose: a rule whose author wrote
     * `"attention":"none"` over a voice call would be a rule that lies, and the
     * host trusts this value to decide whether to fire while the phone is in a
     * pocket.
     */
    val attention: AutomationAttention =
        actions.maxOfOrNull { it.kind.attention } ?: AutomationAttention.NONE

    /**
     * Placeholders the actions use, and therefore the only event fields that
     * may leave the phone when this rule fires.
     *
     * Conditions are excluded deliberately. They are evaluated here and their
     * inputs go nowhere, so matching on the body of a message does not export
     * the body of that message.
     */
    val exportedFields: Set<String> =
        actions.flatMap { action -> PLACEHOLDER_RE.findAll(action.raw.toString()).map { it.groupValues[1] } }.toSet()

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
        if (!enabled) put("enabled", false)
        if (description.isNotEmpty()) put("description", description)
        put("when", trigger.toJson())
        if (conditions.isNotEmpty()) put("if", JsonArray(conditions.map { it.toJson() }))
        put("then", JsonArray(actions.map { it.toJson() }))
        guard.toJson()?.let { put("guard", it) }
    }

    /** The rule as a person reads it, for `mode:"list"` and `mode:"describe"`. */
    fun outline(): JsonObject = buildJsonObject {
        put("id", id)
        put("enabled", enabled)
        put("description", description)
        put("when", trigger.describe())
        if (conditions.isNotEmpty()) put("if", JsonArray(conditions.map { JsonPrimitive(it.describe()) }))
        put("then", JsonArray(actions.map { JsonPrimitive(it.describe()) }))
        put("attention", attention.wire)
        put("attentionMeans", attention.explanation)
        if (exportedFields.isNotEmpty()) put("sendsToTheModel", JsonArray(exportedFields.sorted().map { JsonPrimitive(it) }))
    }

    companion object {
        const val MAX_ID_CHARS = 64
        const val MAX_DESCRIPTION_CHARS = 400
        const val MAX_CONDITIONS = 8
        const val MAX_ACTIONS = 4

        /** Ids name files and are echoed in failures, so they stay boring on purpose. */
        val ID_RE = Regex("[a-z0-9][a-z0-9_-]{0,${MAX_ID_CHARS - 1}}")

        /**
         * `{{notification.text}}` and friends. Dotted, unlike a workflow
         * parameter, because what a rule substitutes is a field of the event
         * that fired it rather than a value a caller passed in.
         */
        val PLACEHOLDER_RE = Regex("\\{\\{\\s*([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)*)\\s*\\}\\}")

        /**
         * Parse one rule, or throw [AutomationFormatException] naming what is
         * wrong with it.
         *
         * A rule that half-parses is worse than one that does not load: it
         * would fire on a trigger nobody agreed to, at a time nobody chose.
         */
        fun parse(json: JsonObject, source: String? = null, savedAt: Long? = null): AutomationRule {
            val id = json.str("id")?.lowercase()
                ?: throw AutomationFormatException("automation_invalid", "\"id\" is required.")
            if (!ID_RE.matches(id)) {
                throw AutomationFormatException(
                    "automation_invalid",
                    "\"$id\" is not a usable rule id: use lowercase letters, digits, \"-\" and \"_\".",
                )
            }
            val triggerJson = json["when"] as? JsonObject
                ?: throw AutomationFormatException("automation_invalid", "Rule \"$id\" has no \"when\" object.")
            val trigger = AutomationTrigger.parse(triggerJson, id)

            val rawConditions = when (val raw = json["if"]) {
                null, is JsonNull -> JsonArray(emptyList())
                is JsonArray -> raw
                is JsonObject -> JsonArray(listOf(raw))
                else -> throw AutomationFormatException(
                    "automation_invalid",
                    "Rule \"$id\": \"if\" must be a condition or an array of them.",
                )
            }
            if (rawConditions.size > MAX_CONDITIONS) {
                throw AutomationFormatException(
                    "automation_invalid",
                    "Rule \"$id\" has ${rawConditions.size} conditions; the limit is $MAX_CONDITIONS.",
                )
            }
            val conditions = rawConditions.mapIndexed { index, element ->
                val obj = element as? JsonObject ?: throw AutomationFormatException(
                    "automation_invalid",
                    "Condition $index of \"$id\" is not an object.",
                )
                AutomationCondition.parse(obj, index, id)
            }

            val rawActions = when (val raw = json["then"]) {
                is JsonArray -> raw
                is JsonObject -> JsonArray(listOf(raw))
                else -> throw AutomationFormatException(
                    "automation_invalid",
                    "Rule \"$id\" has no \"then\" array: a rule that does nothing is not a rule.",
                )
            }
            if (rawActions.isEmpty()) {
                throw AutomationFormatException("automation_invalid", "Rule \"$id\" has no actions.")
            }
            if (rawActions.size > MAX_ACTIONS) {
                throw AutomationFormatException(
                    "automation_invalid",
                    "Rule \"$id\" has ${rawActions.size} actions; the limit is $MAX_ACTIONS. " +
                        "A longer sequence belongs in a workflow the rule names.",
                )
            }
            val actions = rawActions.mapIndexed { index, element ->
                val obj = element as? JsonObject ?: throw AutomationFormatException(
                    "automation_invalid",
                    "Action $index of \"$id\" is not an object.",
                )
                AutomationAction.parse(obj, index, id)
            }

            val rule = AutomationRule(
                id = id,
                version = json["version"]?.jsonPrimitive?.intOrNull ?: 1,
                enabled = json.bool("enabled") ?: true,
                description = json.str("description")?.take(MAX_DESCRIPTION_CHARS).orEmpty(),
                trigger = trigger,
                conditions = conditions,
                actions = actions,
                guard = AutomationGuard.parse(json["guard"], id),
                source = source,
                savedAt = savedAt,
            )

            // A placeholder the trigger cannot produce would reach the model as
            // the literal text "{{notification.text}}", so it is refused where
            // it is written rather than at 3am on the phone.
            val available = trigger.kind.fields + AutomationContext.AMBIENT_FIELDS
            rule.exportedFields.firstOrNull { it !in available }?.let { unknown ->
                throw AutomationFormatException(
                    "automation_invalid",
                    "Rule \"$id\" uses {{$unknown}}, which a \"${trigger.kind.wire}\" trigger never provides. " +
                        "It provides " + available.sorted().joinToString(", ") + ".",
                )
            }
            for (condition in conditions) {
                condition.field?.takeIf { it !in available }?.let { unknown ->
                    throw AutomationFormatException(
                        "automation_invalid",
                        "Rule \"$id\" tests \"$unknown\", which a \"${trigger.kind.wire}\" trigger never provides. " +
                            "It provides " + available.sorted().joinToString(", ") + ".",
                    )
                }
            }
            return rule
        }
    }
}

/**
 * Who has to be awake for a rule to finish.
 *
 * This is the question a rule has to answer before it fires, and the reason the
 * kinds are ranked rather than merely listed: a host decides what it may do
 * unattended by comparing, not by matching.
 */
enum class AutomationAttention(val wire: String, val rank: Int, val explanation: String) {
    /** Runs to completion on its own. A workflow, an intent, a notification. */
    NONE("none", 0, "Runs on its own. Nothing waits for you and no thinking turn is paid for."),

    /** Needs a turn: the agent reads something, decides, writes. Still no person. */
    MODEL(
        "model",
        1,
        "Runs a turn: Mike reads what happened and decides what to do. It costs a turn and " +
            "needs the runtime, but it does not need you.",
    ),

    /** Needs the person: a voice conversation, or an answer to a question. */
    USER(
        "user",
        2,
        "Needs you. It opens a conversation or asks a question, so it is held rather than " +
            "fired when you are unreachable.",
    );

    companion object {
        fun from(wire: String): AutomationAttention? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a rule may do. Closed, for the same reason [WorkflowAction] is closed. */
enum class AutomationActionKind(
    val wire: String,
    val attention: AutomationAttention,
    val requiredFields: List<String>,
) {
    /**
     * Run a saved [WorkflowDefinition] by name. The whole point of the split:
     * the rule decides when, the definition decides how, and a workflow that is
     * repaired for one caller is repaired for both.
     */
    RUN_WORKFLOW("run_workflow", AutomationAttention.NONE, listOf("workflow")),

    /** Launch an intent, through the same policy and approval gate as the tool. */
    OPEN_INTENT("open_intent", AutomationAttention.NONE, listOf("action")),

    /** Tell the user something happened. The honest ending for a rule that did its work. */
    NOTIFY("notify", AutomationAttention.NONE, listOf("text")),

    /**
     * Hand the agent a prompt and let it work. This is the escape hatch for
     * everything a fixed sequence cannot express — "reply to him that I can't
     * talk" is a different sentence every time.
     */
    AGENT_TURN("agent_turn", AutomationAttention.MODEL, listOf("prompt")),

    /**
     * Start a voice conversation. Never fires unattended: the host holds it
     * until the person is reachable, because a phone that starts talking in a
     * meeting is a bug no matter how correct the rule was.
     */
    VOICE_CALL("voice_call", AutomationAttention.USER, listOf("opening")),

    /**
     * Ask a yes/no question and run the rest only on yes. The gate a rule uses
     * when it is right about the trigger and unsure about the consequence.
     */
    ASK("ask", AutomationAttention.USER, listOf("question"));

    companion object {
        fun from(wire: String): AutomationActionKind? = entries.firstOrNull { it.wire == wire }
        val WIRE_NAMES: List<String> = entries.map { it.wire }
    }
}

/**
 * One thing a rule does.
 *
 * The arguments stay as the raw object the author wrote, validated for the
 * fields the kind requires and otherwise passed through: an `open_intent`
 * action carries whatever `open_intent` takes, and this file does not want a
 * second copy of that schema to keep in step.
 */
data class AutomationAction(
    val kind: AutomationActionKind,
    val raw: JsonObject,
    /**
     * Ask before this action runs, over and above what the action itself
     * already gates. A standing rule is exactly where an unattended send wants
     * a person in the loop.
     */
    val requiresApproval: Boolean = false,
) {
    val attention: AutomationAttention = kind.attention

    fun toJson(): JsonObject = buildJsonObject {
        raw.forEach { (key, value) -> put(key, value) }
        put("type", kind.wire)
        if (requiresApproval) put("requiresApproval", true)
    }

    fun describe(): String = buildString {
        append(kind.wire)
        kind.requiredFields.firstOrNull()?.let { field ->
            raw.str(field)?.let { append(": ").append(it.take(80)) }
        }
        if (requiresApproval) append(" (asks first)")
    }

    /** This action with every `{{field}}` replaced from [values]. */
    fun bind(values: Map<String, String>): AutomationAction =
        copy(raw = substitute(raw, values) as JsonObject)

    companion object {
        const val MAX_TEXT_CHARS = 2_000

        fun parse(json: JsonObject, index: Int, ruleId: String): AutomationAction {
            fun bad(reason: String): Nothing =
                throw AutomationFormatException("automation_invalid", "Action $index of \"$ruleId\": $reason")

            val wire = json.str("type") ?: bad("\"type\" is required.")
            val kind = AutomationActionKind.from(wire)
                ?: bad(
                    "\"$wire\" is not an automation action. Use one of " +
                        AutomationActionKind.WIRE_NAMES.joinToString(", ") + ".",
                )
            for (field in kind.requiredFields) {
                if (json.str(field) == null) bad("a \"$wire\" action needs \"$field\".")
            }
            for ((key, value) in json) {
                if (value is JsonPrimitive && value.isString && value.content.length > MAX_TEXT_CHARS) {
                    bad("\"$key\" is longer than $MAX_TEXT_CHARS characters.")
                }
            }
            val raw = JsonObject(json.filterKeys { it != "type" && it != "requiresApproval" })
            return AutomationAction(
                kind = kind,
                raw = raw,
                requiresApproval = json.bool("requiresApproval") ?: false,
            )
        }

        private fun substitute(element: JsonElement, values: Map<String, String>): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> substitute(value, values) })
            is JsonArray -> JsonArray(element.map { substitute(it, values) })
            is JsonPrimitive -> if (!element.isString) element else {
                JsonPrimitive(
                    AutomationRule.PLACEHOLDER_RE.replace(element.content) { match ->
                        // A field the event did not carry becomes empty rather
                        // than the literal braces: the prompt reads as a gap,
                        // which is what it is, instead of as a template bug.
                        values[match.groupValues[1]].orEmpty()
                    },
                )
            }
            else -> element
        }
    }
}

/**
 * How often a rule is allowed to fire.
 *
 * Not a nicety. The triggers that matter most are the noisy ones — a message
 * arrives, a place is entered, a screen turns on — and a rule with no ceiling
 * turns one busy group chat into a hundred unattended turns.
 */
data class AutomationGuard(
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val maxPerDay: Int = DEFAULT_MAX_PER_DAY,
    /**
     * How long after firing the action is still the right action.
     *
     * A rule's moment can pass while it waits for the device: the phone was in
     * another run, the queue was paused by a Stop, the user was on a call.
     * "Post this at 19:00" that finally reaches the front at 23:40 is not a
     * late success, it is the wrong action — so a queued turn carries a
     * deadline and is dropped rather than run long after its moment.
     */
    val validForMs: Long = DEFAULT_VALID_FOR_MS,
) {
    fun toJson(): JsonObject? {
        if (cooldownMs == DEFAULT_COOLDOWN_MS && maxPerDay == DEFAULT_MAX_PER_DAY && validForMs == DEFAULT_VALID_FOR_MS) {
            return null
        }
        return buildJsonObject {
            // Minutes when they are exact, so a sub-minute cooldown written as
            // cooldownMs survives an edit instead of rounding down to zero.
            if (cooldownMs % 60_000L == 0L) put("cooldownMinutes", cooldownMs / 60_000L) else put("cooldownMs", cooldownMs)
            put("maxPerDay", maxPerDay)
            put("validForMinutes", validForMs / 60_000L)
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 24L * 60 * 60 * 1_000
        const val DEFAULT_MAX_PER_DAY = 20
        const val MAX_PER_DAY_CEILING = 200
        const val DEFAULT_VALID_FOR_MS = 30L * 60 * 1_000
        const val MAX_VALID_FOR_MS = 24L * 60 * 60 * 1_000

        fun parse(element: JsonElement?, ruleId: String): AutomationGuard {
            if (element == null || element is JsonNull) return AutomationGuard()
            val json = element as? JsonObject ?: throw AutomationFormatException(
                "automation_invalid",
                "Rule \"$ruleId\": \"guard\" must be an object.",
            )
            val minutes = (json["cooldownMinutes"] as? JsonPrimitive)?.longOrNull
            val millis = (json["cooldownMs"] as? JsonPrimitive)?.longOrNull
            val validFor = (json["validForMinutes"] as? JsonPrimitive)?.longOrNull
            return AutomationGuard(
                cooldownMs = (minutes?.times(60_000L) ?: millis ?: DEFAULT_COOLDOWN_MS)
                    .coerceIn(0L, MAX_COOLDOWN_MS),
                maxPerDay = ((json["maxPerDay"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_MAX_PER_DAY)
                    .coerceIn(1, MAX_PER_DAY_CEILING),
                validForMs = (validFor?.times(60_000L) ?: DEFAULT_VALID_FOR_MS)
                    .coerceIn(60_000L, MAX_VALID_FOR_MS),
            )
        }
    }
}

/** A rule file that cannot be trusted. Typed so the tool reply says which file and why. */
class AutomationFormatException(val errorType: String, override val message: String) : Exception(message)

/** Shared by the trigger and condition parsers: "19:00" and "7:05" both mean what they look like. */
internal fun parseClock(value: String, ruleId: String, field: String): LocalTime {
    val match = Regex("([01]?\\d|2[0-3]):([0-5]\\d)").matchEntire(value.trim())
        ?: throw AutomationFormatException(
            "automation_invalid",
            "Rule \"$ruleId\": \"$field\" is \"$value\"; write a 24-hour time such as \"19:00\".",
        )
    return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

internal fun parseDays(element: JsonElement?, ruleId: String): Set<DayOfWeek> {
    if (element == null || element is JsonNull) return emptySet()
    val array = element as? JsonArray ?: throw AutomationFormatException(
        "automation_invalid",
        "Rule \"$ruleId\": \"days\" must be an array such as [\"mon\",\"tue\"].",
    )
    return array.map { entry ->
        val name = (entry as? JsonPrimitive)?.contentOrNullSafe()?.lowercase()?.take(3)
        DAY_NAMES[name] ?: throw AutomationFormatException(
            "automation_invalid",
            "Rule \"$ruleId\": \"$name\" is not a day. Use " + DAY_NAMES.keys.joinToString(", ") + ".",
        )
    }.toSet()
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

internal val DAY_NAMES: Map<String, DayOfWeek> = mapOf(
    "mon" to DayOfWeek.MONDAY,
    "tue" to DayOfWeek.TUESDAY,
    "wed" to DayOfWeek.WEDNESDAY,
    "thu" to DayOfWeek.THURSDAY,
    "fri" to DayOfWeek.FRIDAY,
    "sat" to DayOfWeek.SATURDAY,
    "sun" to DayOfWeek.SUNDAY,
)

internal fun DayOfWeek.wire(): String = DAY_NAMES.entries.first { it.value == this }.key
