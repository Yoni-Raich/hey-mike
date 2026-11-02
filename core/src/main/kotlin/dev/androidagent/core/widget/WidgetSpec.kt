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

@file:OptIn(ExperimentalSerializationApi::class)

package dev.androidagent.core.widget

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Text with `{expression}` holes: `"Total {round(perDay * 7)} calls"`. A hole
 * that is not a valid expression stays as typed, so ordinary braces survive.
 */
class Tpl private constructor(private val parts: List<Any>) {
    val isStatic: Boolean get() = parts.all { it is String }

    fun render(env: WidgetEnv): String {
        if (parts.size == 1) (parts[0] as? String)?.let { return it }
        val out = StringBuilder()
        for (part in parts) out.append(if (part is WidgetExpr) part.text(env) else part as String)
        return out.toString()
    }

    fun uses(name: String): Boolean = parts.any { it is WidgetExpr && it.uses(name) }

    companion object {
        val EMPTY = Tpl(listOf(""))

        fun of(raw: String): Tpl {
            val parts = ArrayList<Any>()
            val literal = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val open = raw.indexOf('{', i)
                val close = if (open >= 0) raw.indexOf('}', open + 1) else -1
                if (open < 0 || close < 0) {
                    literal.append(raw, i, raw.length)
                    break
                }
                literal.append(raw, i, open)
                val inner = raw.substring(open + 1, close)
                val expr = inner.takeIf { it.isNotBlank() }?.let(WidgetExpr::parseOrNull)
                if (expr == null) {
                    literal.append(raw, open, close + 1)
                } else {
                    if (literal.isNotEmpty()) parts += literal.toString().also { literal.clear() }
                    parts += expr
                }
                i = close + 1
            }
            if (literal.isNotEmpty() || parts.isEmpty()) parts += literal.toString()
            return Tpl(parts)
        }
    }
}

/** A number that is fixed, or worked out from the widget's state: `120` or `"=perDay * rate / 100"`. */
class Dyn private constructor(private val constant: Double, private val expr: WidgetExpr?) {
    val isConstant: Boolean get() = expr == null

    fun eval(env: WidgetEnv): Double = expr?.number(env) ?: constant

    fun truthy(env: WidgetEnv): Boolean = expr?.truthy(env) ?: (constant != 0.0)

    fun uses(name: String): Boolean = expr?.uses(name) == true

    companion object {
        fun of(value: Double): Dyn = Dyn(value, null)

        /** Reads a JSON number, boolean or expression string; [warn] hears about a string that is neither. */
        fun parse(element: JsonElement?, fallback: Double, warn: (String) -> Unit): Dyn {
            val primitive = element as? JsonPrimitive ?: return Dyn(fallback, null)
            if (primitive is JsonNull) return Dyn(fallback, null)
            if (!primitive.isString) {
                return when (primitive.content) {
                    "true" -> Dyn(1.0, null)
                    "false" -> Dyn(0.0, null)
                    else -> Dyn(primitive.doubleOrNull ?: fallback, null)
                }
            }
            val text = primitive.content.trim()
            val source = if (text.startsWith("=")) text.drop(1) else text
            text.toDoubleOrNull()?.let { return Dyn(it, null) }
            if (source.isBlank()) return Dyn(fallback, null)
            return try {
                Dyn(fallback, WidgetExpr.parse(source))
            } catch (e: ExprException) {
                warn("'$text': ${e.message}")
                Dyn(fallback, null)
            }
        }
    }
}

enum class Tone { NORMAL, MUTED, GOOD, WARN, BAD }

enum class ChartKind { LINE, BAR, AREA, PIE, DONUT, SPARK }

enum class ButtonAction { ASK, COPY }

class Option(val value: Any, val label: Tpl)

class Stat(
    val label: Tpl,
    val value: Dyn,
    val unit: String,
    val format: String?,
    val icon: String?,
    val delta: Dyn?,
    val caption: Tpl?,
    val color: Int?,
)

class Input(val id: String, val initial: Any)

class ChartSeries(
    val name: Tpl,
    val color: Int?,
    val fixed: List<Dyn>?,
    val generator: WidgetExpr?,
    val visible: Dyn?,
    val pointColors: List<Int?>?,
)

class SeriesData(val name: String, val color: Int, val values: List<Double>, val pointColors: List<Int>?)

class ChartData(val labels: List<String>, val series: List<SeriesData>)

/** One piece of a widget. [visible] is the optional `"when"`: the block is skipped while it is false. */
sealed class Block {
    var visible: Dyn? = null
        internal set

    class Heading(val text: Tpl, val level: Int) : Block()

    class TextLine(val text: Tpl, val tone: Tone) : Block()

    class Stats(val items: List<Stat>) : Block()

    class Slider(
        val id: String,
        val label: Tpl,
        val min: Dyn,
        val max: Dyn,
        val step: Double,
        val unit: String,
        val format: String?,
    ) : Block()

    class Choice(val id: String, val label: Tpl?, val options: List<Option>, val tabs: Boolean) : Block()

    class Toggle(val id: String, val label: Tpl) : Block()

    class TextInput(val id: String, val label: Tpl, val hint: String) : Block()

    class Chart(
        val id: String?,
        val title: Tpl?,
        val kinds: List<ChartKind>,
        val labels: List<Tpl>?,
        val points: Dyn,
        val labelTemplate: Tpl?,
        val series: List<ChartSeries>,
        val stacked: Boolean,
        val height: Int,
        val format: String?,
        val unit: String,
        val yMin: Dyn?,
        val yMax: Dyn?,
    ) : Block() {
        fun evaluate(env: WidgetEnv): ChartData {
            val count = labels?.size ?: points.eval(env).toInt().coerceIn(1, MAX_POINTS)
            val scope = ScopedEnv(env)
            scope["n"] = count.toDouble()
            val names = labels?.map { it.render(env) } ?: List(count) { i ->
                scope["i"] = i.toDouble()
                labelTemplate?.render(scope) ?: (i + 1).toString()
            }
            val data = series.mapIndexedNotNull { index, s ->
                if (s.visible?.truthy(env) == false) return@mapIndexedNotNull null
                val values = when {
                    s.fixed != null -> List(count) { i -> s.fixed.getOrNull(i)?.eval(env) ?: 0.0 }
                    s.generator != null -> List(count) { i ->
                        scope["i"] = i.toDouble()
                        s.generator.number(scope)
                    }
                    else -> List(count) { 0.0 }
                }
                SeriesData(
                    name = s.name.render(env),
                    color = s.color ?: WidgetFormat.PALETTE[index % WidgetFormat.PALETTE.size],
                    values = values,
                    pointColors = s.pointColors?.mapIndexed { i, c -> c ?: WidgetFormat.PALETTE[i % WidgetFormat.PALETTE.size] },
                )
            }
            return ChartData(names, data)
        }
    }

    class Gauge(
        val label: Tpl,
        val value: Dyn,
        val min: Dyn,
        val max: Dyn,
        val unit: String,
        val format: String?,
        val caption: Tpl?,
        val color: Int?,
    ) : Block()

    class Progress(
        val label: Tpl,
        val value: Dyn,
        val max: Dyn,
        val unit: String,
        val format: String?,
        val color: Int?,
    ) : Block()

    class Table(val columns: List<Tpl>, val rows: List<List<Tpl>>, val caption: Tpl?) : Block()

    /** A grid of dots whose size and brightness come from [intensity], evaluated per cell and, when [animated], per frame. */
    class Dots(
        val cols: Int,
        val rows: Int,
        val intensity: WidgetExpr,
        val color: Int?,
        val square: Boolean,
        val height: Int,
        val title: Tpl?,
    ) : Block() {
        val animated: Boolean get() = intensity.uses("t")
    }

    class Card(val title: Tpl?, val subtitle: Tpl?, val children: List<Block>) : Block()

    class Row(val children: List<Block>) : Block()

    class Button(val label: Tpl, val action: ButtonAction, val prompt: Tpl?) : Block()

    class Divider : Block()

    class Unsupported(val type: String) : Block()

    fun children(): List<Block> = when (this) {
        is Card -> children
        is Row -> children
        else -> emptyList()
    }
}

const val MAX_POINTS = 400

class WidgetParseException(message: String) : IllegalArgumentException(message)

/** A parsed widget: what to draw, and what the user can change. */
class WidgetSpec internal constructor(
    val title: Tpl?,
    val subtitle: Tpl?,
    /** `true` or `false` when the widget says so; null lets the text decide. */
    val rtl: Boolean?,
    val body: List<Block>,
    val inputs: List<Input>,
    val warnings: List<String>,
) {
    /** What every input starts as. */
    fun initialState(): Map<String, Any> = inputs.associate { it.id to it.initial }

    /** Every block, depth first, hidden or not. */
    fun allBlocks(): List<Block> {
        val out = ArrayList<Block>()
        fun walk(blocks: List<Block>) {
            for (block in blocks) {
                out += block
                walk(block.children())
            }
        }
        walk(body)
        return out
    }

    /**
     * The current values and every visible chart's numbers as JSON, for the
     * "copy data" button and for sending the state back to the agent.
     */
    fun snapshot(env: WidgetEnv, state: Map<String, Any>): JsonObject = buildJsonObject {
        putJsonObjectOf("values", state)
        val charts = allBlocks().filterIsInstance<Block.Chart>().filter { it.visible?.truthy(env) != false }
        if (charts.isNotEmpty()) {
            put("charts", buildJsonObject {
                charts.forEachIndexed { index, chart ->
                    val data = chart.evaluate(env)
                    val key = chart.id ?: chart.title?.render(env)?.takeIf { it.isNotBlank() } ?: "chart${index + 1}"
                    put(key, buildJsonObject {
                        putJsonArray("labels") { data.labels.forEach { add(JsonPrimitive(it)) } }
                        put("series", buildJsonObject {
                            data.series.forEach { s ->
                                putJsonArray(s.name.ifBlank { "values" }) { s.values.forEach { add(JsonPrimitive(it)) } }
                            }
                        })
                    })
                }
            })
        }
    }

    fun snapshotText(env: WidgetEnv, state: Map<String, Any>): String =
        PRETTY.encodeToString(JsonObject.serializer(), snapshot(env, state))

    companion object {
        const val MAX_SOURCE_CHARS = 64_000
        private const val MAX_BLOCKS = 150
        private const val MAX_DEPTH = 5
        private val PRETTY = Json { prettyPrint = true }
        private val LENIENT = Json { isLenient = true; allowTrailingComma = true; ignoreUnknownKeys = true }

        /** Parses the JSON inside a `mike-widget` block. Throws [WidgetParseException] when nothing can be drawn. */
        fun parse(source: String): WidgetSpec {
            if (source.length > MAX_SOURCE_CHARS) throw WidgetParseException("widget is larger than $MAX_SOURCE_CHARS characters")
            val root = try {
                LENIENT.parseToJsonElement(source)
            } catch (e: Exception) {
                throw WidgetParseException("not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            val ctx = Ctx()
            val obj = when (root) {
                is JsonObject -> root
                is JsonArray -> buildJsonObject { put("body", root) }
                else -> throw WidgetParseException("expected a JSON object")
            }
            val bodyElement = obj["body"] ?: obj["blocks"] ?: obj["children"]
            val body = (bodyElement as? JsonArray)?.let { ctx.blocks(it, 0) }
                ?: throw WidgetParseException("missing \"body\" array")
            if (body.isEmpty()) throw WidgetParseException("\"body\" has no blocks")
            val dir = obj.str("dir")?.lowercase()
            return WidgetSpec(
                title = obj.str("title")?.let(Tpl::of),
                subtitle = obj.str("subtitle")?.let(Tpl::of),
                rtl = when (dir) {
                    "rtl" -> true
                    "ltr" -> false
                    else -> null
                },
                body = body,
                inputs = ctx.inputs.values.toList(),
                warnings = ctx.warnings.toList(),
            )
        }
    }

    private class Ctx {
        val warnings = ArrayList<String>()
        val inputs = LinkedHashMap<String, Input>()
        var count = 0
        private var auto = 0

        fun warn(message: String) {
            if (warnings.size < 20) warnings += message
        }

        fun blocks(array: JsonArray, depth: Int): List<Block> {
            val out = ArrayList<Block>()
            for (element in array) {
                val obj = element as? JsonObject
                if (obj == null) {
                    warn("a block is not an object")
                    continue
                }
                if (++count > MAX_BLOCKS) {
                    warn("more than $MAX_BLOCKS blocks; the rest were dropped")
                    break
                }
                out += block(obj, depth)
            }
            return out
        }

        fun dyn(element: JsonElement?, fallback: Double): Dyn = Dyn.parse(element, fallback, ::warn)

        fun tpl(obj: JsonObject, key: String): Tpl? = obj.str(key)?.let(Tpl::of)

        private fun inputId(obj: JsonObject, kind: String): String {
            val given = obj.str("id")?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
            if (given != null) return given
            warn("a $kind has no usable \"id\"")
            return "${kind}_${++auto}"
        }

        private fun register(id: String, initial: Any) {
            if (id in inputs) warn("two inputs share the id '$id'") else inputs[id] = Input(id, initial)
        }

        private fun block(obj: JsonObject, depth: Int): Block {
            val type = obj.str("type")?.trim()?.lowercase().orEmpty()
            val block: Block = when (type) {
                "heading", "title", "h1", "h2", "h3" -> Block.Heading(
                    tpl(obj, "text") ?: Tpl.EMPTY,
                    obj.int("level") ?: if (type == "h1") 1 else if (type == "h3") 3 else 2,
                )
                "text", "paragraph", "note", "caption" -> Block.TextLine(
                    tpl(obj, "text") ?: Tpl.EMPTY,
                    when (obj.str("tone")?.lowercase()) {
                        "muted" -> Tone.MUTED
                        "good", "success" -> Tone.GOOD
                        "warn", "warning" -> Tone.WARN
                        "bad", "error" -> Tone.BAD
                        else -> if (type == "caption") Tone.MUTED else Tone.NORMAL
                    },
                )
                "stats", "stat", "kpi", "metrics" -> stats(obj)
                "slider", "range" -> {
                    val id = inputId(obj, "slider")
                    val min = dyn(obj["min"], 0.0)
                    val max = dyn(obj["max"], 100.0)
                    val low = min.eval { null }
                    val high = max.eval { null }
                    val start = (obj.double("value") ?: obj.double("default") ?: low).coerceIn(minOf(low, high), maxOf(low, high))
                    register(id, start)
                    Block.Slider(id, tpl(obj, "label") ?: Tpl.EMPTY, min, max, obj.double("step") ?: 0.0, obj.str("unit").orEmpty(), obj.str("format"))
                }
                "select", "dropdown", "tabs", "segmented", "pills" -> {
                    val tabs = type != "select" && type != "dropdown"
                    val id = inputId(obj, if (tabs) "tabs" else "select")
                    val options = options(obj["options"])
                    if (options.isEmpty()) warn("'$id' has no options")
                    val wanted = (obj["value"] ?: obj["default"])
                    val wantedText = (wanted as? JsonPrimitive)?.contentOrNull
                    val initial = options.firstOrNull { WidgetFormat.plain(it.value) == wantedText }?.value
                        ?: options.firstOrNull()?.value ?: ""
                    register(id, initial)
                    Block.Choice(id, tpl(obj, "label"), options, tabs)
                }
                "toggle", "switch", "checkbox" -> {
                    val id = inputId(obj, "toggle")
                    val on = (obj["value"] ?: obj["default"])?.let { Dyn.parse(it, 0.0, ::warn).truthy { null } } ?: false
                    register(id, if (on) 1.0 else 0.0)
                    Block.Toggle(id, tpl(obj, "label") ?: Tpl.EMPTY)
                }
                "input", "text_input", "textfield" -> {
                    val id = inputId(obj, "input")
                    register(id, obj.str("value") ?: obj.str("default") ?: "")
                    Block.TextInput(id, tpl(obj, "label") ?: Tpl.EMPTY, obj.str("hint").orEmpty())
                }
                "chart", "line", "bar", "area", "pie", "donut", "spark" -> chart(obj, type)
                "gauge", "meter", "radial" -> Block.Gauge(
                    label = tpl(obj, "label") ?: Tpl.EMPTY,
                    value = dyn(obj["value"], 0.0),
                    min = dyn(obj["min"], 0.0),
                    max = dyn(obj["max"], 100.0),
                    unit = obj.str("unit").orEmpty(),
                    format = obj.str("format"),
                    caption = tpl(obj, "caption"),
                    color = WidgetFormat.parseColor(obj.str("color")),
                )
                "progress" -> Block.Progress(
                    label = tpl(obj, "label") ?: Tpl.EMPTY,
                    value = dyn(obj["value"], 0.0),
                    max = dyn(obj["max"], 100.0),
                    unit = obj.str("unit").orEmpty(),
                    format = obj.str("format"),
                    color = WidgetFormat.parseColor(obj.str("color")),
                )
                "table" -> table(obj)
                "dots", "matrix", "particles" -> dots(obj)
                "card", "section", "group", "panel" -> {
                    if (depth >= MAX_DEPTH) {
                        warn("blocks nested deeper than $MAX_DEPTH levels were dropped")
                        Block.Unsupported(type)
                    } else {
                        Block.Card(tpl(obj, "title"), tpl(obj, "subtitle"), children(obj, depth))
                    }
                }
                "row", "columns" -> {
                    if (depth >= MAX_DEPTH) {
                        warn("blocks nested deeper than $MAX_DEPTH levels were dropped")
                        Block.Unsupported(type)
                    } else {
                        Block.Row(children(obj, depth))
                    }
                }
                "button", "action" -> Block.Button(
                    tpl(obj, "label") ?: Tpl.EMPTY,
                    if (obj.str("action")?.lowercase() == "copy") ButtonAction.COPY else ButtonAction.ASK,
                    tpl(obj, "prompt"),
                )
                "divider", "hr", "spacer" -> Block.Divider()
                else -> {
                    warn("unknown block type '$type'")
                    Block.Unsupported(type)
                }
            }
            (obj["when"] ?: obj["visible"] ?: obj["show"])?.let { block.visible = dyn(it, 1.0) }
            return block
        }

        private fun children(obj: JsonObject, depth: Int): List<Block> =
            ((obj["children"] ?: obj["body"] ?: obj["items"]) as? JsonArray)?.let { blocks(it, depth + 1) }.orEmpty()

        private fun options(element: JsonElement?): List<Option> = (element as? JsonArray).orEmpty().take(24).mapNotNull { item ->
            when (item) {
                is JsonObject -> {
                    val value = item["value"]?.let(::scalar) ?: item.str("label") ?: return@mapNotNull null
                    Option(value, Tpl.of(item.str("label") ?: WidgetFormat.plain(value)))
                }
                is JsonPrimitive -> scalar(item)?.let { Option(it, Tpl.of(WidgetFormat.plain(it))) }
                else -> null
            }
        }

        private fun scalar(element: JsonElement): Any? {
            val p = element as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            if (p.isString) return p.content
            return when (p.content) {
                "true" -> 1.0
                "false" -> 0.0
                else -> p.doubleOrNull
            }
        }

        private fun stats(obj: JsonObject): Block {
            val items = (obj["items"] as? JsonArray) ?: buildJsonArray { add(obj) }
            return Block.Stats(
                items.take(6).mapNotNull { it as? JsonObject }.map { item ->
                    Stat(
                        label = tpl(item, "label") ?: Tpl.EMPTY,
                        value = dyn(item["value"], 0.0),
                        unit = item.str("unit").orEmpty(),
                        format = item.str("format"),
                        icon = item.str("icon")?.lowercase(),
                        delta = item["delta"]?.let { dyn(it, 0.0) },
                        caption = tpl(item, "caption"),
                        color = WidgetFormat.parseColor(item.str("color")),
                    )
                },
            )
        }

        private fun table(obj: JsonObject): Block {
            val columns = (obj["columns"] as? JsonArray).orEmpty().take(8).map { Tpl.of((it as? JsonPrimitive)?.content.orEmpty()) }
            val rows = (obj["rows"] as? JsonArray).orEmpty().take(200).mapNotNull { row ->
                (row as? JsonArray)?.take(8)?.map { cell -> Tpl.of((cell as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { cellText(it) }.orEmpty()) }
            }
            return Block.Table(columns, rows, tpl(obj, "caption"))
        }

        private fun cellText(p: JsonPrimitive): String =
            if (p.isString) p.content else p.doubleOrNull?.let(WidgetFormat::number) ?: p.content

        private fun dots(obj: JsonObject): Block {
            val source = obj.str("value") ?: obj.str("expr") ?: obj.str("intensity") ?: DEFAULT_PATTERN
            val expr = try {
                WidgetExpr.parse(source.trim().removePrefix("="))
            } catch (e: ExprException) {
                warn("dots '$source': ${e.message}")
                WidgetExpr.parse(DEFAULT_PATTERN)
            }
            return Block.Dots(
                cols = (obj.int("cols") ?: 12).coerceIn(1, 48),
                rows = (obj.int("rows") ?: 10).coerceIn(1, 48),
                intensity = expr,
                color = WidgetFormat.parseColor(obj.str("color")),
                square = obj.str("shape")?.lowercase() == "square",
                height = (obj.int("height") ?: 200).coerceIn(80, 420),
                title = tpl(obj, "title"),
            )
        }

        private fun chart(obj: JsonObject, type: String): Block {
            val kindNames = ((obj["kinds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                ?: listOfNotNull(obj.str("kind"))
            var kinds = kindNames.mapNotNull(::kind).distinct()
            if (kinds.isEmpty()) kinds = listOf(kind(type) ?: ChartKind.LINE)
            obj.str("kind")?.let(::kind)?.takeIf { it in kinds }?.let { first -> kinds = listOf(first) + kinds.filter { it != first } }

            val series = ArrayList<ChartSeries>()
            var labels = (obj["x"] as? JsonArray)?.take(MAX_POINTS)?.map { Tpl.of((it as? JsonPrimitive)?.content.orEmpty()) }?.takeIf { it.isNotEmpty() }
            val slices = obj["slices"] as? JsonArray
            if (slices != null) {
                val items = slices.take(24).mapNotNull { it as? JsonObject }
                labels = items.map { tpl(it, "label") ?: Tpl.EMPTY }
                series += ChartSeries(
                    name = Tpl.EMPTY,
                    color = null,
                    fixed = items.map { dyn(it["value"], 0.0) },
                    generator = null,
                    visible = null,
                    pointColors = items.map { WidgetFormat.parseColor(it.str("color")) },
                )
            } else {
                val declared = (obj["series"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?: listOf(obj).takeIf { obj["values"] != null || obj["expr"] != null }.orEmpty()
                declared.take(12).forEach { s ->
                    val values = s["values"] ?: s["data"]
                    val array = values as? JsonArray
                    val generator = (s.str("expr") ?: (values as? JsonPrimitive)?.takeIf { it.isString }?.content)?.let { text ->
                        try {
                            WidgetExpr.parse(text.trim().removePrefix("="))
                        } catch (e: ExprException) {
                            warn("series '$text': ${e.message}")
                            null
                        }
                    }
                    series += ChartSeries(
                        name = tpl(s, "name") ?: tpl(s, "label") ?: Tpl.EMPTY,
                        color = WidgetFormat.parseColor(s.str("color")),
                        fixed = array?.take(MAX_POINTS)?.map { dyn(it, 0.0) },
                        generator = generator,
                        visible = (s["when"] ?: s["visible"])?.let { dyn(it, 1.0) },
                        pointColors = null,
                    )
                }
            }
            if (series.isEmpty()) warn("a chart has no series")
            val sizeOfFixed = series.mapNotNull { it.fixed?.size }.maxOrNull() ?: 12
            return Block.Chart(
                id = obj.str("id"),
                title = tpl(obj, "title"),
                kinds = kinds,
                labels = labels,
                points = dyn(obj["points"], sizeOfFixed.toDouble()),
                labelTemplate = tpl(obj, "xLabel"),
                series = series,
                stacked = (obj["stacked"] as? JsonPrimitive)?.contentOrNull == "true",
                height = (obj.int("height") ?: 180).coerceIn(100, 420),
                format = obj.str("format"),
                unit = obj.str("unit").orEmpty(),
                yMin = obj["yMin"]?.let { dyn(it, 0.0) },
                yMax = obj["yMax"]?.let { dyn(it, 0.0) },
            )
        }

        private fun kind(name: String): ChartKind? = when (name.trim().lowercase()) {
            "line", "lines" -> ChartKind.LINE
            "bar", "bars", "column", "columns" -> ChartKind.BAR
            "area" -> ChartKind.AREA
            "pie" -> ChartKind.PIE
            "donut", "doughnut" -> ChartKind.DONUT
            "spark", "sparkline" -> ChartKind.SPARK
            else -> null
        }
    }
}

private const val DEFAULT_PATTERN = "0.5 + 0.5 * sin(t * 2 + x * 6 + y * 4)"

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

private fun JsonObject.int(key: String): Int? = double(key)?.toInt()

/** The state map as an environment. A switch is stored as 1.0 or 0.0. */
class StateEnv(private val state: Map<String, Any>) : WidgetEnv {
    override fun lookup(name: String): Any? = state[name]
}

private fun JsonObjectBuilder.putJsonObjectOf(key: String, values: Map<String, Any>) {
    put(key, buildJsonObject {
        values.toSortedMap().forEach { (name, value) ->
            when (value) {
                is Double -> put(name, value)
                is Number -> put(name, value.toDouble())
                is Boolean -> put(name, value)
                else -> put(name, value.toString())
            }
        }
    })
}
