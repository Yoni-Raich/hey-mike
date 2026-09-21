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
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A test a rule applies before it acts, over the event and the moment together.
 *
 * This is the half that makes the mechanism compose: "Dad messaged" is a
 * trigger, "it is after 19:00" is a condition, and the interesting rules are
 * the ones that need both. Keeping them separate also keeps the wake-ups cheap
 * — the host only has to register for the trigger, and everything else is
 * decided in memory once it fires.
 *
 * Closed, like everything else a rule may say, and pure: a condition reads the
 * event and the context and returns a boolean. It cannot read the screen, ask
 * the model, or touch the network, so evaluating a hundred rules on a
 * notification costs nothing and can never block the listener.
 */
data class AutomationCondition(
    val kind: Kind,
    val negate: Boolean = false,
    /** TEXT: which event field is tested. Validated against the trigger's fields. */
    val field: String? = null,
    val contains: String? = null,
    val equals: String? = null,
    /** TIME_BETWEEN: inclusive start, exclusive end. Wraps past midnight. */
    val after: LocalTime? = null,
    val before: LocalTime? = null,
    /** DAY_OF_WEEK. */
    val days: Set<DayOfWeek> = emptySet(),
    /** AT_PLACE. */
    val place: String? = null,
    /** DEVICE_STATE. */
    val stateName: String? = null,
) {

    enum class Kind(val wire: String) {
        TIME_BETWEEN("time_between"),
        DAY_OF_WEEK("day_of_week"),
        AT_PLACE("at_place"),
        TEXT("text"),
        DEVICE_STATE("device_state");

        companion object {
            fun from(wire: String): Kind? = entries.firstOrNull { it.wire == wire }
            val WIRE_NAMES: List<String> = entries.map { it.wire }
        }
    }

    fun holds(event: AutomationEvent, context: AutomationContext): Boolean {
        val raw = when (kind) {
            Kind.TIME_BETWEEN -> inWindow(context.now.toLocalTime())
            Kind.DAY_OF_WEEK -> context.now.dayOfWeek in days
            Kind.AT_PLACE -> context.places.any { it.equals(place, ignoreCase = true) }
            Kind.TEXT -> {
                val value = event.fields()[field] ?: context.fields()[field]
                when {
                    value == null -> false
                    equals != null -> value.equals(equals, ignoreCase = true)
                    contains != null -> value.contains(contains, ignoreCase = true)
                    else -> value.isNotBlank()
                }
            }
            Kind.DEVICE_STATE -> {
                val value = context.deviceState[stateName]
                when {
                    value == null -> false
                    equals != null -> value.equals(equals, ignoreCase = true)
                    contains != null -> value.contains(contains, ignoreCase = true)
                    else -> value.isNotBlank()
                }
            }
        }
        return raw != negate
    }

    /**
     * True when [time] is inside the window, which may run past midnight.
     *
     * "after 19:00 and before 07:00" is the shape half these rules take, and
     * reading it as `19:00 <= t < 07:00` makes it empty — a rule that silently
     * never fires is the worst possible failure for this feature.
     */
    private fun inWindow(time: LocalTime): Boolean {
        val from = after ?: LocalTime.MIN
        val until = before ?: LocalTime.MAX
        return if (from <= until) {
            time >= from && time < until
        } else {
            time >= from || time < until
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("type", kind.wire)
        if (negate) put("not", true)
        field?.let { put("field", it) }
        contains?.let { put("contains", it) }
        equals?.let { put("equals", it) }
        after?.let { put("after", "%02d:%02d".format(it.hour, it.minute)) }
        before?.let { put("before", "%02d:%02d".format(it.hour, it.minute)) }
        if (days.isNotEmpty()) {
            put("days", JsonArray(DayOfWeek.entries.filter { it in days }.map { JsonPrimitive(it.wire()) }))
        }
        place?.let { put("place", it) }
        stateName?.let { put("state", it) }
    }

    fun describe(): String {
        val body = when (kind) {
            Kind.TIME_BETWEEN -> "the time is between ${after ?: "00:00"} and ${before ?: "24:00"}"
            Kind.DAY_OF_WEEK -> "the day is " + DayOfWeek.entries.filter { it in days }.joinToString("/") { it.wire() }
            Kind.AT_PLACE -> "the phone is at \"$place\""
            Kind.TEXT -> "$field " + (equals?.let { "is \"$it\"" } ?: contains?.let { "contains \"$it\"" } ?: "is set")
            Kind.DEVICE_STATE -> "$stateName " + (equals?.let { "is \"$it\"" } ?: contains?.let { "contains \"$it\"" } ?: "is set")
        }
        return if (negate) "not ($body)" else body
    }

    companion object {
        fun parse(json: JsonObject, index: Int, ruleId: String): AutomationCondition {
            fun bad(reason: String): Nothing =
                throw AutomationFormatException("automation_invalid", "Condition $index of \"$ruleId\": $reason")

            val wire = json.str("type") ?: bad("\"type\" is required.")
            val kind = Kind.from(wire)
                ?: bad("\"$wire\" is not a condition. Use one of " + Kind.WIRE_NAMES.joinToString(", ") + ".")
            val negate = json.bool("not") ?: false
            return when (kind) {
                Kind.TIME_BETWEEN -> {
                    val after = json.str("after")?.let { parseClock(it, ruleId, "after") }
                    val before = json.str("before")?.let { parseClock(it, ruleId, "before") }
                    if (after == null && before == null) bad("a \"time_between\" condition needs \"after\", \"before\" or both.")
                    AutomationCondition(kind, negate, after = after, before = before)
                }

                Kind.DAY_OF_WEEK -> {
                    val days = parseDays(json["days"], ruleId)
                    if (days.isEmpty()) bad("a \"day_of_week\" condition needs a non-empty \"days\" array.")
                    AutomationCondition(kind, negate, days = days)
                }

                Kind.AT_PLACE ->
                    AutomationCondition(kind, negate, place = json.str("place") ?: bad("an \"at_place\" condition needs \"place\"."))

                Kind.TEXT -> AutomationCondition(
                    kind,
                    negate,
                    field = json.str("field") ?: bad("a \"text\" condition needs \"field\", such as \"notification.text\"."),
                    contains = json.str("contains"),
                    equals = json.str("equals"),
                )

                Kind.DEVICE_STATE -> AutomationCondition(
                    kind,
                    negate,
                    stateName = json.str("state") ?: bad("a \"device_state\" condition needs \"state\"."),
                    contains = json.str("contains"),
                    equals = json.str("equals"),
                )
            }
        }
    }
}
