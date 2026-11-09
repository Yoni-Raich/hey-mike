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

package dev.androidagent.core.widget

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where an expression reads names from: a slider's value, a chart's point
 * index, a cell's position. A name nobody defines reads as 0. A value is a
 * [Double] or a [String]; a switch is 1.0 or 0.0.
 */
fun interface WidgetEnv {
    fun lookup(name: String): Any?
}

/** A [WidgetEnv] that adds names on top of another, for the one point or cell being drawn. */
class ScopedEnv(private val parent: WidgetEnv) : WidgetEnv {
    private val local = HashMap<String, Any>()

    operator fun set(name: String, value: Any) {
        local[name] = value
    }

    override fun lookup(name: String): Any? = local[name] ?: parent.lookup(name)
}

class ExprException(message: String) : IllegalArgumentException(message)

/**
 * A small arithmetic language the agent writes inside a widget, so a slider
 * can drive a number, a chart or a pattern without any script running.
 *
 * Numbers, `'text'`, names, `+ - * / % ^`, comparisons, `&& || !`, `a ? b : c`
 * and a fixed set of functions. There are no loops, no assignment, no calls out
 * of the language and no way to reach the phone, so the worst a wrong or
 * hostile expression can do is show a wrong number. Length and nesting are
 * capped, and [number] never returns NaN or infinity.
 */
class WidgetExpr private constructor(val source: String, private val root: Node, val names: Set<String>) {
    fun value(env: WidgetEnv): Any = root.any(env)

    fun number(env: WidgetEnv): Double = root.num(env).let { if (it.isFinite()) it else 0.0 }

    fun text(env: WidgetEnv): String = WidgetFormat.plain(root.any(env))

    fun truthy(env: WidgetEnv): Boolean = when (val v = root.any(env)) {
        is Double -> v != 0.0 && !v.isNaN()
        is String -> v.isNotEmpty()
        else -> false
    }

    /** True when the expression reads [name], for example `t` to know a pattern animates. */
    fun uses(name: String): Boolean = name in names

    companion object {
        const val MAX_LENGTH = 1_000
        private const val MAX_DEPTH = 48
        private const val CACHE_LIMIT = 1_024
        private val cache = ConcurrentHashMap<String, WidgetExpr>()

        /** Parses [source]; throws [ExprException] with a short reason. Parsed expressions are cached. */
        fun parse(source: String): WidgetExpr {
            cache[source]?.let { return it }
            if (source.length > MAX_LENGTH) throw ExprException("expression longer than $MAX_LENGTH characters")
            val names = LinkedHashSet<String>()
            val root = Parser(source, names).parseAll()
            val parsed = WidgetExpr(source, root, names)
            if (cache.size >= CACHE_LIMIT) cache.clear()
            cache[source] = parsed
            return parsed
        }

        fun parseOrNull(source: String): WidgetExpr? = try {
            parse(source)
        } catch (_: ExprException) {
            null
        }
    }

    // --- tree ---------------------------------------------------------------------------------

    private sealed class Node {
        abstract fun any(env: WidgetEnv): Any
        open fun num(env: WidgetEnv): Double = toNumber(any(env))
    }

    private class Num(val v: Double) : Node() {
        override fun any(env: WidgetEnv): Any = v
        override fun num(env: WidgetEnv): Double = v
    }

    private class Text(val s: String) : Node() {
        override fun any(env: WidgetEnv): Any = s
    }

    private class Name(val name: String) : Node() {
        override fun any(env: WidgetEnv): Any = when (val v = env.lookup(name)) {
            is Double, is String -> v
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            else -> 0.0
        }
    }

    private class Neg(val a: Node) : Node() {
        override fun any(env: WidgetEnv): Any = num(env)
        override fun num(env: WidgetEnv): Double = -a.num(env)
    }

    private class Not(val a: Node) : Node() {
        override fun any(env: WidgetEnv): Any = num(env)
        override fun num(env: WidgetEnv): Double = if (truth(a.any(env))) 0.0 else 1.0
    }

    private class Cond(val test: Node, val yes: Node, val no: Node) : Node() {
        override fun any(env: WidgetEnv): Any = if (truth(test.any(env))) yes.any(env) else no.any(env)
    }

    private enum class Op { ADD, SUB, MUL, DIV, MOD, POW, LT, LE, GT, GE, EQ, NE, AND, OR }

    private class Bin(val op: Op, val l: Node, val r: Node) : Node() {
        override fun any(env: WidgetEnv): Any {
            if (op == Op.ADD) {
                val a = l.any(env)
                val b = r.any(env)
                return if (a is String || b is String) WidgetFormat.plain(a) + WidgetFormat.plain(b) else toNumber(a) + toNumber(b)
            }
            return num(env)
        }

        override fun num(env: WidgetEnv): Double = when (op) {
            Op.ADD -> toNumber(any(env))
            Op.SUB -> l.num(env) - r.num(env)
            Op.MUL -> l.num(env) * r.num(env)
            Op.DIV -> l.num(env) / r.num(env)
            Op.MOD -> l.num(env).let { a -> r.num(env).let { b -> if (b == 0.0) 0.0 else a - b * floor(a / b) } }
            Op.POW -> l.num(env).pow(r.num(env))
            Op.LT -> bool(l.num(env) < r.num(env))
            Op.LE -> bool(l.num(env) <= r.num(env))
            Op.GT -> bool(l.num(env) > r.num(env))
            Op.GE -> bool(l.num(env) >= r.num(env))
            Op.EQ -> bool(equal(l.any(env), r.any(env)))
            Op.NE -> bool(!equal(l.any(env), r.any(env)))
            Op.AND -> bool(truth(l.any(env)) && truth(r.any(env)))
            Op.OR -> bool(truth(l.any(env)) || truth(r.any(env)))
        }

        private fun equal(a: Any, b: Any): Boolean =
            if (a is Double && b is Double) abs(a - b) < 1e-9 else WidgetFormat.plain(a) == WidgetFormat.plain(b)
    }

    private class Fmt(val value: Node, val style: Node) : Node() {
        override fun any(env: WidgetEnv): Any = WidgetFormat.format(value.num(env), style.any(env).let { WidgetFormat.plain(it) })
    }

    private class Call(val fn: Fn, val args: List<Node>) : Node() {
        override fun any(env: WidgetEnv): Any = num(env)
        override fun num(env: WidgetEnv): Double {
            val values = DoubleArray(args.size) { args[it].num(env) }
            return fn.apply(values)
        }
    }

    // --- parser -------------------------------------------------------------------------------

    private class Parser(val src: String, val names: MutableSet<String>) {
        var pos = 0
        var depth = 0

        fun parseAll(): Node {
            val node = ternary()
            skip()
            if (pos < src.length) fail("unexpected '${src[pos]}'")
            return node
        }

        private fun fail(reason: String): Nothing = throw ExprException("$reason at ${pos + 1}")

        private fun skip() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun eat(token: String): Boolean {
            skip()
            if (!src.startsWith(token, pos)) return false
            pos += token.length
            return true
        }

        private fun nested(block: () -> Node): Node {
            if (++depth > MAX_DEPTH) fail("expression nested too deeply")
            try {
                return block()
            } finally {
                depth--
            }
        }

        private fun ternary(): Node = nested {
            val test = or()
            if (eat("?")) {
                val yes = ternary()
                if (!eat(":")) fail("expected ':'")
                Cond(test, yes, ternary())
            } else test
        }

        private fun or(): Node {
            var left = and()
            while (eat("||")) left = Bin(Op.OR, left, and())
            return left
        }

        private fun and(): Node {
            var left = equality()
            while (eat("&&")) left = Bin(Op.AND, left, equality())
            return left
        }

        private fun equality(): Node {
            var left = relational()
            while (true) {
                left = when {
                    eat("==") -> Bin(Op.EQ, left, relational())
                    eat("!=") -> Bin(Op.NE, left, relational())
                    else -> return left
                }
            }
        }

        private fun relational(): Node {
            var left = additive()
            while (true) {
                left = when {
                    eat("<=") -> Bin(Op.LE, left, additive())
                    eat(">=") -> Bin(Op.GE, left, additive())
                    eat("<") -> Bin(Op.LT, left, additive())
                    eat(">") -> Bin(Op.GT, left, additive())
                    else -> return left
                }
            }
        }

        private fun additive(): Node {
            var left = multiplicative()
            while (true) {
                left = when {
                    eat("+") -> Bin(Op.ADD, left, multiplicative())
                    eat("-") -> Bin(Op.SUB, left, multiplicative())
                    else -> return left
                }
            }
        }

        private fun multiplicative(): Node {
            var left = unary()
            while (true) {
                left = when {
                    eat("*") -> Bin(Op.MUL, left, unary())
                    eat("/") -> Bin(Op.DIV, left, unary())
                    eat("%") -> Bin(Op.MOD, left, unary())
                    else -> return left
                }
            }
        }

        private fun unary(): Node = nested {
            when {
                eat("-") -> Neg(unary())
                eat("!") -> Not(unary())
                eat("+") -> unary()
                else -> power()
            }
        }

        // Right-associative, and binds tighter than a minus on its left: -2^2 is -4.
        private fun power(): Node {
            val base = primary()
            return if (eat("^")) Bin(Op.POW, base, unary()) else base
        }

        private fun primary(): Node {
            skip()
            if (pos >= src.length) fail("unexpected end")
            val c = src[pos]
            return when {
                c == '(' -> {
                    pos++
                    val inner = ternary()
                    if (!eat(")")) fail("expected ')'")
                    inner
                }
                c.isDigit() || c == '.' -> number()
                c == '\'' || c == '"' -> string(c)
                c.isLetter() || c == '_' -> identifier()
                else -> fail("unexpected '$c'")
            }
        }

        private fun number(): Node {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                val save = pos
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos < src.length && src[pos].isDigit()) while (pos < src.length && src[pos].isDigit()) pos++ else pos = save
            }
            return Num(src.substring(start, pos).toDoubleOrNull() ?: fail("bad number"))
        }

        private fun string(quote: Char): Node {
            pos++
            val out = StringBuilder()
            while (pos < src.length && src[pos] != quote) {
                if (src[pos] == '\\' && pos + 1 < src.length) pos++
                out.append(src[pos++])
            }
            if (pos >= src.length) fail("unterminated text")
            pos++
            return Text(out.toString())
        }

        private fun identifier(): Node {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos)
            skip()
            if (pos < src.length && src[pos] == '(') return call(name)
            CONSTANTS[name]?.let { return Num(it) }
            names += name
            return Name(name)
        }

        private fun call(name: String): Node {
            pos++
            val args = ArrayList<Node>()
            if (!eat(")")) {
                do {
                    args += ternary()
                    if (args.size > MAX_ARGS) fail("too many arguments")
                } while (eat(","))
                if (!eat(")")) fail("expected ')'")
            }
            if (name == "if") {
                if (args.size != 3) fail("if needs 3 arguments")
                return Cond(args[0], args[1], args[2])
            }
            if (name == "fmt") {
                if (args.size != 2) fail("fmt needs a value and a style")
                return Fmt(args[0], args[1])
            }
            val fn = FUNCTIONS[name] ?: fail("unknown function '$name'")
            if (args.size < fn.min || args.size > fn.max) fail("$name takes ${fn.min}..${fn.max} arguments")
            return Call(fn, args)
        }
    }
}

private const val MAX_ARGS = 8

internal class Fn(val min: Int, val max: Int, val apply: (DoubleArray) -> Double)

private val CONSTANTS = mapOf(
    "pi" to Math.PI,
    "tau" to Math.PI * 2,
    "e" to Math.E,
    "true" to 1.0,
    "false" to 0.0,
)

private fun bool(v: Boolean): Double = if (v) 1.0 else 0.0

private fun truth(v: Any): Boolean = when (v) {
    is Double -> v != 0.0 && !v.isNaN()
    is String -> v.isNotEmpty()
    else -> false
}

private fun toNumber(v: Any): Double = when (v) {
    is Double -> v
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: 0.0
    is Boolean -> bool(v)
    else -> 0.0
}

private fun smooth(t: Double): Double = t * t * (3 - 2 * t)

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

private fun hash01(x: Int, y: Int, z: Int): Double {
    var h = x * 374_761_393 + y * 668_265_263 + z * 1_274_126_177
    h = (h xor (h ushr 13)) * 1_103_515_245
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2_147_483_647.0
}

/** Smooth value noise in 0..1, the same for the same inputs. Pass 1 to 3 coordinates. */
private fun noise(x: Double, y: Double, z: Double): Double {
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val z0 = floor(z).toInt()
    val fx = smooth(x - x0)
    val fy = smooth(y - y0)
    val fz = smooth(z - z0)
    fun layer(zi: Int): Double {
        val a = lerp(hash01(x0, y0, zi), hash01(x0 + 1, y0, zi), fx)
        val b = lerp(hash01(x0, y0 + 1, zi), hash01(x0 + 1, y0 + 1, zi), fx)
        return lerp(a, b, fy)
    }
    return lerp(layer(z0), layer(z0 + 1), fz)
}

private val FUNCTIONS: Map<String, Fn> = buildMap {
    fun fn(name: String, min: Int, max: Int = min, apply: (DoubleArray) -> Double) {
        put(name, Fn(min, max, apply))
    }
    fn("abs", 1) { abs(it[0]) }
    fn("sign", 1) { sign(it[0]) }
    fn("floor", 1) { floor(it[0]) }
    fn("ceil", 1) { ceil(it[0]) }
    fn("fract", 1) { it[0] - floor(it[0]) }
    fn("round", 1, 2) { v ->
        if (v.size == 1) round(v[0]) else 10.0.pow(v[1].coerceIn(0.0, 9.0)).let { round(v[0] * it) / it }
    }
    fn("sqrt", 1) { sqrt(max(0.0, it[0])) }
    fn("pow", 2) { it[0].pow(it[1]) }
    fn("exp", 1) { exp(it[0]) }
    fn("log", 1) { if (it[0] > 0) ln(it[0]) else 0.0 }
    fn("sin", 1) { sin(it[0]) }
    fn("cos", 1) { cos(it[0]) }
    fn("tan", 1) { tan(it[0]) }
    fn("atan2", 2) { atan2(it[0], it[1]) }
    fn("hypot", 2) { hypot(it[0], it[1]) }
    fn("dist", 4) { hypot(it[0] - it[2], it[1] - it[3]) }
    fn("min", 1, MAX_ARGS) { v -> v.min() }
    fn("max", 1, MAX_ARGS) { v -> v.max() }
    fn("sum", 1, MAX_ARGS) { v -> v.sum() }
    fn("avg", 1, MAX_ARGS) { v -> v.average() }
    fn("clamp", 3) { min(max(it[0], it[1]), it[2]) }
    fn("mix", 3) { lerp(it[0], it[1], it[2]) }
    fn("step", 2) { bool(it[1] >= it[0]) }
    fn("smooth", 3) { v -> smooth(((v[2] - v[0]) / (v[1] - v[0])).coerceIn(0.0, 1.0)) }
    fn("mod", 2) { v -> if (v[1] == 0.0) 0.0 else v[0] - v[1] * floor(v[0] / v[1]) }
    fn("noise", 1, 3) { v -> noise(v[0], v.getOrElse(1) { 0.0 }, v.getOrElse(2) { 0.0 }) }
    fn("rand", 1) { hash01(floor(it[0]).toInt(), 17, 91) }
}
