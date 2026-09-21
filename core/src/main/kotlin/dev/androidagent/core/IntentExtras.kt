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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One intent extra, typed the way the receiving app reads it.
 *
 * The type matters to Android, not only to us: `getIntExtra` on a value put as
 * a long returns the default, so a timer's `LENGTH` has to arrive as an int
 * and a calendar event's begin time as a long.
 *
 * Only plain values exist here. There is no Uri, Parcelable or Bundle, and so
 * no way for a model-built intent to hand another app a grant on our data.
 */
sealed interface IntentExtra {
    data class Text(val value: String) : IntentExtra
    data class Int32(val value: Int) : IntentExtra
    data class Int64(val value: Long) : IntentExtra
    data class Real(val value: Double) : IntentExtra
    data class Flag(val value: Boolean) : IntentExtra
    data class TextList(val values: List<String>) : IntentExtra
}

/**
 * Reads `open_intent`'s `extras` argument.
 *
 * Generic on purpose. Nothing here knows what `SET_TIMER` or
 * `EXTRA_EVENT_BEGIN_TIME` are: a recipe for a feature is knowledge that lives
 * in a skill or a workflow definition, and this only decides whether a value is
 * well formed and bounded.
 *
 * A bare JSON value picks the obvious type — a string, a boolean, an integer
 * that fits an int, a larger integer as a long, a fraction as a double, an
 * array of strings. `{"type":"long","value":5}` states it when the obvious
 * type is wrong for the receiver.
 */
object IntentExtras {
    const val MAX_EXTRAS = 16
    const val MAX_KEY_CHARS = 128
    const val MAX_VALUE_CHARS = 1_000
    const val MAX_LIST_ITEMS = 20

    private val KEY_RE = Regex("[A-Za-z_][A-Za-z0-9_.]{0,${MAX_KEY_CHARS - 1}}")

    sealed interface Parsed {
        data class Ok(val extras: Map<String, IntentExtra>) : Parsed
        data class Invalid(val reason: String, val message: String) : Parsed
    }

    fun parse(element: JsonElement?): Parsed {
        if (element == null || element is JsonNull) return Parsed.Ok(emptyMap())
        val json = element as? JsonObject
            ?: return Parsed.Invalid("extras_malformed", "extras must be an object of name to value.")
        if (json.size > MAX_EXTRAS) {
            return Parsed.Invalid("extras_too_many", "extras has ${json.size} entries; the limit is $MAX_EXTRAS.")
        }
        val extras = LinkedHashMap<String, IntentExtra>()
        for ((key, raw) in json) {
            if (!KEY_RE.matches(key)) {
                return Parsed.Invalid("extra_key_malformed", "\"${key.take(40)}\" is not a usable extra name.")
            }
            val value = when (raw) {
                is JsonObject -> typed(key, raw)
                else -> inferred(key, raw)
            }
            when (value) {
                is IntentExtra -> extras[key] = value
                is Parsed.Invalid -> return value
                else -> return invalid(key, "has a value that is not a string, number, boolean or list of strings")
            }
        }
        return Parsed.Ok(extras)
    }

    /** A value with its type spelled out: `{"type":"long","value":1757000000000}`. */
    private fun typed(key: String, json: JsonObject): Any? {
        val type = (json["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            ?: return invalid(key, "is an object without a \"type\"")
        val raw = json["value"] ?: return invalid(key, "has no \"value\"")
        val primitive = raw as? JsonPrimitive
        return when (type) {
            "string" -> primitive?.takeIf { it.isString }?.content?.let { text(key, it) }
            "int" -> primitive?.takeIf { !it.isString }?.longOrNull
                ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { IntentExtra.Int32(it.toInt()) }
            "long" -> primitive?.takeIf { !it.isString }?.longOrNull?.let { IntentExtra.Int64(it) }
            "double", "float" -> primitive?.takeIf { !it.isString }?.doubleOrNull?.let { IntentExtra.Real(it) }
            "boolean" -> primitive?.takeIf { !it.isString }?.booleanOrNull?.let { IntentExtra.Flag(it) }
            "string_array", "string_list" -> (raw as? JsonArray)?.let { list(key, it) }
            else -> return invalid(key, "has type \"$type\"; use string, int, long, double, boolean or string_array")
        } ?: invalid(key, "does not hold a $type")
    }

    private fun inferred(key: String, raw: JsonElement): Any? = when (raw) {
        is JsonArray -> list(key, raw)
        is JsonPrimitive -> when {
            raw.isString -> text(key, raw.content)
            raw.booleanOrNull != null -> IntentExtra.Flag(raw.booleanOrNull!!)
            raw.longOrNull != null -> raw.longOrNull!!.let { number ->
                if (number in Int.MIN_VALUE..Int.MAX_VALUE) IntentExtra.Int32(number.toInt()) else IntentExtra.Int64(number)
            }
            raw.doubleOrNull != null -> IntentExtra.Real(raw.doubleOrNull!!)
            else -> null
        }
        else -> null
    }

    private fun list(key: String, array: JsonArray): Any {
        if (array.size > MAX_LIST_ITEMS) return invalid(key, "has ${array.size} items; the limit is $MAX_LIST_ITEMS")
        val values = array.map { item ->
            val primitive = item as? JsonPrimitive
            if (primitive == null || !primitive.isString) return invalid(key, "is a list with an item that is not a string")
            when (val checked = text(key, primitive.content)) {
                is IntentExtra.Text -> checked.value
                else -> return checked
            }
        }
        return IntentExtra.TextList(values)
    }

    private fun text(key: String, value: String): Any {
        if (value.length > MAX_VALUE_CHARS) return invalid(key, "is longer than $MAX_VALUE_CHARS characters")
        if (value.any { it.code < 0x20 && it != '\n' && it != '\t' }) return invalid(key, "contains control characters")
        // An extra is not a uri, but plenty of apps parse a string extra as one.
        // A blocked scheme here would reach local data by the side door.
        val colon = value.indexOf(':')
        if (colon > 0) {
            val scheme = value.substring(0, colon).trim().lowercase()
            if (scheme in IntentPolicy.blockedSchemes) {
                return Parsed.Invalid(
                    "extra_scheme_blocked",
                    "extra \"$key\" names a \"$scheme:\" location, which this agent may not hand to another app.",
                )
            }
        }
        return IntentExtra.Text(value)
    }

    private fun invalid(key: String, what: String) =
        Parsed.Invalid("extra_invalid", "extra \"$key\" $what.")

    /** One line naming the extras, for an approval card. Values are shortened, never dropped. */
    fun describe(extras: Map<String, IntentExtra>): String = extras.entries.joinToString(", ") { (key, value) ->
        val shown = when (value) {
            is IntentExtra.Text -> "\"${value.value.take(60)}\""
            is IntentExtra.Int32 -> value.value.toString()
            is IntentExtra.Int64 -> value.value.toString()
            is IntentExtra.Real -> value.value.toString()
            is IntentExtra.Flag -> value.value.toString()
            is IntentExtra.TextList -> value.values.joinToString(prefix = "[", postfix = "]") { it.take(30) }
        }
        "${key.substringAfterLast('.')}=$shown"
    }
}
