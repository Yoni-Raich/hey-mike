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
import java.time.DayOfWeek

/**
 * One value a person can change on a rule's edit screen: a time, a set of
 * days, a number, a line of text, or a switch.
 *
 * [key] is the value's place in the rule, dotted, with array positions as
 * numbers: `when.at`, `if.0.after`, `then.1.prompt`, `guard.maxPerDay`.
 * [value] is always a string so a text field can hold it while it is being
 * typed: a time is `HH:mm`, days are wire names joined by commas
 * (`sun,mon`), a switch is `true` or `false`.
 */
data class AutomationEditField(
    val key: String,
    val section: AutomationEditor.Section,
    /** The condition or action this belongs to, as the person reads it. Null for the rule's own fields. */
    val group: String?,
    val label: String,
    val type: AutomationEditor.FieldType,
    val value: String,
    /** Empty is allowed, and removes the value. */
    val optional: Boolean = false,
    val hint: String? = null,
    val min: Long? = null,
    val max: Long? = null,
)

/**
 * The rule edit screen, as data.
 *
 * A standing rule is JSON underneath, and nobody should have to read JSON to
 * move 19:00 to 20:00. This turns a rule into the handful of plain values a
 * person would actually change — the time, the days, the text of a message,
 * a limit — and turns the edited values back into the `changes` that
 * [AutomationLibrary.update] takes, so the screen saves through exactly the
 * validation the agent's `mode:"update"` does.
 *
 * What is deliberately not here: changing a rule's *kind* of trigger or
 * action, adding or removing a condition, or the technical fields (which
 * workflow runs, an intent's action string, which event field a text test
 * reads). Those change
 * what the rule is rather than a value in it, and are what asking Mike in
 * words is for.
 *
 * Lives in `:core`, not beside the Composable, for the same reason
 * [AutomationOverview] does: this is where a test can reach it.
 */
object AutomationEditor {

    enum class Section(val title: String) {
        ABOUT("Name"),
        WHEN("When"),
        IF("Only if"),
        THEN("Then"),
        LIMITS("Limits"),
    }

    enum class FieldType { TEXT, LONG_TEXT, TIME, DAYS, NUMBER, SWITCH }

    /** Every editable value of [rule], in the order the screen shows them. */
    fun fields(rule: AutomationRule): List<AutomationEditField> = buildList {
        add(
            AutomationEditField(
                key = "description",
                section = Section.ABOUT,
                group = null,
                label = "What it does, in a line",
                type = FieldType.TEXT,
                value = rule.description,
                optional = true,
            ),
        )
        addAll(triggerFields(rule.trigger))
        rule.conditions.forEachIndexed { index, condition -> addAll(conditionFields(index, condition)) }
        rule.actions.forEachIndexed { index, action -> addAll(actionFields(index, action)) }
        addAll(guardFields(rule.guard))
    }

    /**
     * Why [value] cannot go into [field], in words, or null when it can.
     *
     * Checked per field so the screen can put the reason under the field that
     * has it. Whatever passes here is still validated whole by
     * [AutomationRule.parse] when it is saved.
     */
    fun problem(field: AutomationEditField, value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) {
            return if (field.optional || field.type == FieldType.SWITCH) null else "This cannot be empty."
        }
        return when (field.type) {
            FieldType.TIME -> if (normalizeTime(text) == null) "Write a 24-hour time, such as 19:00." else null
            FieldType.NUMBER -> {
                val number = text.toLongOrNull() ?: return "Write a whole number."
                when {
                    field.min != null && number < field.min -> "At least ${field.min}."
                    field.max != null && number > field.max -> "At most ${field.max}."
                    else -> null
                }
            }
            FieldType.DAYS -> if (parseDays(text) == null) "Pick at least one day." else null
            FieldType.TEXT, FieldType.LONG_TEXT ->
                if (text.length > AutomationAction.MAX_TEXT_CHARS) "Keep it under ${AutomationAction.MAX_TEXT_CHARS} characters." else null
            FieldType.SWITCH -> null
        }
    }

    /**
     * The `changes` for [AutomationLibrary.update] that turn [rule] into what
     * the screen holds.
     *
     * Only a value that differs from what the field started with is written,
     * so opening the editor and saving changes nothing — not even the spelling
     * of a guard the rule never set. Top-level keys that end up identical are
     * left out, and an emptied optional value is removed rather than saved as
     * an empty string.
     */
    fun changes(rule: AutomationRule, values: Map<String, String>): JsonObject {
        val original = rule.toJson()
        var edited: JsonElement = original
        val guard = mutableMapOf<String, JsonElement>()
        for (field in fields(rule)) {
            val value = values[field.key] ?: continue
            if (value.trim() == field.value.trim()) continue
            val path = field.key.split('.')
            if (path.first() == "guard") guard[path.last()] = encode(field, value)
            else edited = setPath(edited, path, encode(field, value))
        }
        // A guard is written whole: the parser reads it as one object, and a
        // rule on the defaults has no guard key to set a path inside.
        if (guard.isNotEmpty()) edited = setPath(edited, listOf("guard"), guardWith(rule.guard, guard))
        val result = edited as JsonObject
        val changes = mutableMapOf<String, JsonElement>()
        for ((key, value) in result) if (original[key] != value) changes[key] = value
        for (key in original.keys) if (key !in result) changes[key] = JsonNull
        return JsonObject(changes)
    }

    // ---- building the fields ----

    private fun triggerFields(trigger: AutomationTrigger): List<AutomationEditField> = buildList {
        fun field(key: String, label: String, type: FieldType, value: String, optional: Boolean = false, hint: String? = null, min: Long? = null, max: Long? = null) =
            add(AutomationEditField("when.$key", Section.WHEN, null, label, type, value, optional, hint, min, max))

        when (trigger.kind) {
            AutomationTriggerKind.SCHEDULE -> {
                val schedule = trigger.schedule ?: return@buildList
                if (schedule.everyMinutes != null) {
                    field(
                        "everyMinutes", "Every how many minutes", FieldType.NUMBER, schedule.everyMinutes.toString(),
                        min = AutomationSchedule.MIN_INTERVAL_MINUTES.toLong(),
                        max = AutomationSchedule.MAX_INTERVAL_MINUTES.toLong(),
                    )
                } else if (schedule.at != null) {
                    field("at", "At", FieldType.TIME, clock(schedule.at.hour, schedule.at.minute))
                }
                field("days", "On these days", FieldType.DAYS, days(schedule.days), optional = true, hint = "None picked means every day.")
            }
            AutomationTriggerKind.NOTIFICATION -> {
                field("package", "App (package name)", FieldType.TEXT, trigger.packageName.orEmpty(), hint = "For example com.whatsapp")
                field("from", "From (sender name contains)", FieldType.TEXT, trigger.from.orEmpty(), optional = true, hint = "Empty means anyone.")
            }
            AutomationTriggerKind.PLACE -> field("place", "Place", FieldType.TEXT, trigger.place.orEmpty())
            AutomationTriggerKind.DEVICE_STATE -> field("is", "Becomes", FieldType.TEXT, trigger.stateValue.orEmpty(), optional = true, hint = "Empty means any change.")
            AutomationTriggerKind.MANUAL -> Unit
        }
    }

    private fun conditionFields(index: Int, condition: AutomationCondition): List<AutomationEditField> = buildList {
        val group = when (condition.kind) {
            AutomationCondition.Kind.TIME_BETWEEN -> "Between these times"
            AutomationCondition.Kind.DAY_OF_WEEK -> "On these days"
            AutomationCondition.Kind.AT_PLACE -> "At a place"
            AutomationCondition.Kind.TEXT -> "When ${condition.field}"
            AutomationCondition.Kind.DEVICE_STATE -> "When ${condition.stateName}"
        }.let { if (condition.negate) "Not: $it" else it }
        fun field(key: String, label: String, type: FieldType, value: String, optional: Boolean = false, hint: String? = null) =
            add(AutomationEditField("if.$index.$key", Section.IF, group, label, type, value, optional, hint))

        when (condition.kind) {
            AutomationCondition.Kind.TIME_BETWEEN -> {
                // Either end may be missing, but not both — the parser says so if both are cleared.
                field("after", "From", FieldType.TIME, condition.after?.let { clock(it.hour, it.minute) }.orEmpty(), optional = true)
                field("before", "Until", FieldType.TIME, condition.before?.let { clock(it.hour, it.minute) }.orEmpty(), optional = true)
            }
            AutomationCondition.Kind.DAY_OF_WEEK -> field("days", "Days", FieldType.DAYS, days(condition.days))
            AutomationCondition.Kind.AT_PLACE -> field("place", "Place", FieldType.TEXT, condition.place.orEmpty())
            AutomationCondition.Kind.TEXT, AutomationCondition.Kind.DEVICE_STATE -> when {
                condition.equals != null -> field("equals", "Is exactly", FieldType.TEXT, condition.equals)
                condition.contains != null -> field("contains", "Contains", FieldType.TEXT, condition.contains)
                else -> Unit
            }
        }
    }

    private fun actionFields(index: Int, action: AutomationAction): List<AutomationEditField> = buildList {
        val group = when (action.kind) {
            AutomationActionKind.RUN_WORKFLOW -> "Run the \"${action.raw.str("workflow").orEmpty()}\" workflow"
            AutomationActionKind.OPEN_INTENT -> "Open something"
            AutomationActionKind.NOTIFY -> "Send me a notification"
            AutomationActionKind.AGENT_TURN -> "Let Mike handle it"
            AutomationActionKind.VOICE_CALL -> "Start a voice conversation"
            AutomationActionKind.ASK -> "Ask me"
        }
        fun field(key: String, label: String, type: FieldType, value: String, optional: Boolean = false, hint: String? = null) =
            add(AutomationEditField("then.$index.$key", Section.THEN, group, label, type, value, optional, hint))

        when (action.kind) {
            AutomationActionKind.RUN_WORKFLOW -> {
                // The workflow's name is an id, not a value: renaming it here
                // would point the rule at a workflow that may not exist. Which
                // workflow runs is a bigger change, and goes through Mike. The
                // values it is handed are editable, one field each — only plain
                // values, since a nested object is not something a text field
                // can hold.
                (action.raw["parameters"] as? JsonObject)?.forEach { (name, value) ->
                    val primitive = value as? JsonPrimitive ?: return@forEach
                    if ('.' in name || !primitive.isString) return@forEach
                    field("parameters.$name", humanize(name), FieldType.TEXT, primitive.content, optional = true)
                }
            }
            AutomationActionKind.OPEN_INTENT -> Unit
            AutomationActionKind.NOTIFY -> {
                field("title", "Title", FieldType.TEXT, action.raw.str("title").orEmpty(), optional = true)
                field("text", "Message", FieldType.LONG_TEXT, action.raw.str("text").orEmpty())
            }
            AutomationActionKind.AGENT_TURN ->
                field("prompt", "What Mike should do", FieldType.LONG_TEXT, action.raw.str("prompt").orEmpty(), hint = PLACEHOLDER_HINT)
            AutomationActionKind.VOICE_CALL ->
                field("opening", "What Mike opens with", FieldType.LONG_TEXT, action.raw.str("opening").orEmpty())
            AutomationActionKind.ASK -> field("question", "Question", FieldType.LONG_TEXT, action.raw.str("question").orEmpty())
        }
        if (action.kind != AutomationActionKind.ASK) {
            field("requiresApproval", "Ask me before this runs", FieldType.SWITCH, action.requiresApproval.toString())
        }
    }

    private fun guardFields(guard: AutomationGuard): List<AutomationEditField> = listOf(
        AutomationEditField(
            "guard.cooldownMinutes", Section.LIMITS, null, "Wait between runs (minutes)", FieldType.NUMBER,
            (guard.cooldownMs / 60_000L).toString(), min = 0, max = AutomationGuard.MAX_COOLDOWN_MS / 60_000L,
        ),
        AutomationEditField(
            "guard.maxPerDay", Section.LIMITS, null, "Most runs a day", FieldType.NUMBER,
            guard.maxPerDay.toString(), min = 1, max = AutomationGuard.MAX_PER_DAY_CEILING.toLong(),
        ),
        AutomationEditField(
            "guard.validForMinutes", Section.LIMITS, null, "Still run if late by up to (minutes)", FieldType.NUMBER,
            (guard.validForMs / 60_000L).toString(), min = 1, max = AutomationGuard.MAX_VALID_FOR_MS / 60_000L,
        ),
    )

    // ---- writing values back ----

    private fun encode(field: AutomationEditField, value: String): JsonElement {
        val text = value.trim()
        if (text.isEmpty() && field.type != FieldType.SWITCH) return JsonNull
        return when (field.type) {
            FieldType.TIME -> JsonPrimitive(normalizeTime(text) ?: text)
            FieldType.NUMBER -> text.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
            FieldType.DAYS -> parseDays(text)?.let { set ->
                JsonArray(DayOfWeek.entries.filter { it in set }.map { JsonPrimitive(it.wire()) })
            } ?: JsonNull
            FieldType.SWITCH -> if (text == "true") JsonPrimitive(true) else JsonNull
            FieldType.TEXT, FieldType.LONG_TEXT -> JsonPrimitive(text)
        }
    }

    /** The guard as the parser reads it, with [changed] applied over the current values. */
    private fun guardWith(guard: AutomationGuard, changed: Map<String, JsonElement>): JsonObject {
        val base = mutableMapOf<String, JsonElement>(
            "maxPerDay" to JsonPrimitive(guard.maxPerDay),
            "validForMinutes" to JsonPrimitive(guard.validForMs / 60_000L),
        )
        // A sub-minute cooldown nobody touched keeps its exact value.
        if (guard.cooldownMs % 60_000L == 0L || "cooldownMinutes" in changed) {
            base["cooldownMinutes"] = JsonPrimitive(guard.cooldownMs / 60_000L)
        } else {
            base["cooldownMs"] = JsonPrimitive(guard.cooldownMs)
        }
        for ((key, value) in changed) if (value is JsonNull) base.remove(key) else base[key] = value
        return JsonObject(base)
    }

    /** [root] with the value at [path] replaced, or removed when [value] is [JsonNull]. */
    private fun setPath(root: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        val head = path.first()
        val rest = path.drop(1)
        return when (root) {
            is JsonObject -> {
                val map = root.toMutableMap()
                if (rest.isEmpty()) {
                    if (value is JsonNull) map.remove(head) else map[head] = value
                } else {
                    map[head] = setPath(map[head] ?: JsonObject(emptyMap()), rest, value)
                }
                JsonObject(map)
            }
            is JsonArray -> {
                val index = head.toIntOrNull() ?: return root
                if (index !in root.indices) return root
                val list = root.toMutableList()
                list[index] = if (rest.isEmpty()) value else setPath(list[index], rest, value)
                JsonArray(list)
            }
            else -> root
        }
    }

    // ---- small formats ----

    private fun clock(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    private fun days(set: Set<DayOfWeek>): String =
        DayOfWeek.entries.filter { it in set }.joinToString(",") { it.wire() }

    /** "7:5" and "07:05" both mean 07:05; anything else is not a time. */
    fun normalizeTime(text: String): String? {
        val match = Regex("([01]?\\d|2[0-3]):([0-5]?\\d)").matchEntire(text.trim()) ?: return null
        return clock(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    /** `sun,mon` into days; null when nothing in it is a day. */
    fun parseDays(text: String): Set<DayOfWeek>? {
        val set = text.split(',').mapNotNull { DAY_NAMES[it.trim().lowercase().take(3)] }.toSet()
        return set.ifEmpty { null }
    }

    private fun humanize(name: String): String =
        name.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

    private const val PLACEHOLDER_HINT =
        "Words in {{double braces}} are filled in from what happened, such as {{notification.title}}."
}
