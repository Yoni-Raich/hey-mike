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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WidgetExprTest {
    private val none = WidgetEnv { null }

    private fun num(source: String, vararg vars: Pair<String, Any>): Double {
        val env = vars.toMap()
        return WidgetExpr.parse(source).number { env[it] }
    }

    private fun text(source: String, vararg vars: Pair<String, Any>): String {
        val env = vars.toMap()
        return WidgetExpr.parse(source).text { env[it] }
    }

    @Test fun arithmeticFollowsPrecedence() {
        assertEquals(7.0, num("1 + 2 * 3"), 1e-9)
        assertEquals(9.0, num("(1 + 2) * 3"), 1e-9)
        assertEquals(1.0, num("7 % 3"), 1e-9)
        assertEquals(2.5, num("10 / 4"), 1e-9)
        assertEquals(-3.0, num("2 - 5"), 1e-9)
    }

    @Test fun powerIsRightAssociativeAndTighterThanMinus() {
        assertEquals(512.0, num("2 ^ 3 ^ 2"), 1e-9)
        assertEquals(-4.0, num("-2 ^ 2"), 1e-9)
        assertEquals(0.5, num("2 ^ -1"), 1e-9)
    }

    @Test fun namesComeFromTheEnvironmentAndMissingOnesAreZero() {
        assertEquals(240.0, num("perDay * 2", "perDay" to 120.0), 1e-9)
        assertEquals(5.0, num("missing + 5"), 1e-9)
    }

    @Test fun comparisonsAndLogicGiveOneOrZero() {
        assertEquals(1.0, num("3 > 2 && 2 >= 2"), 1e-9)
        assertEquals(0.0, num("3 < 2 || 1 == 2"), 1e-9)
        assertEquals(1.0, num("!(1 == 2)"), 1e-9)
        assertEquals(1.0, num("1 != 2"), 1e-9)
    }

    @Test fun ternaryAndIfPickABranch() {
        assertEquals(10.0, num("x > 5 ? 10 : 20", "x" to 6.0), 1e-9)
        assertEquals(20.0, num("x > 5 ? 10 : 20", "x" to 4.0), 1e-9)
        assertEquals(7.0, num("if(x, 7, 8)", "x" to 1.0), 1e-9)
        assertEquals(8.0, num("if(x, 7, 8)", "x" to 0.0), 1e-9)
    }

    @Test fun textComparesAndJoins() {
        assertEquals(1.0, num("mode == 'wave'", "mode" to "wave"), 1e-9)
        assertEquals(0.0, num("mode == 'wave'", "mode" to "noise"), 1e-9)
        assertEquals("day 3", text("'day ' + n", "n" to 3.0))
        assertEquals("a-b", text("\"a-\" + 'b'"))
    }

    @Test fun functionsWork() {
        assertEquals(3.0, num("max(1, 3, 2)"), 1e-9)
        assertEquals(1.0, num("min(4, 1, 2)"), 1e-9)
        assertEquals(4.0, num("abs(-4)"), 1e-9)
        assertEquals(3.0, num("round(2.6)"), 1e-9)
        assertEquals(2.57, num("round(2.567, 2)"), 1e-9)
        assertEquals(5.0, num("clamp(9, 0, 5)"), 1e-9)
        assertEquals(15.0, num("mix(10, 20, 0.5)"), 1e-9)
        assertEquals(5.0, num("hypot(3, 4)"), 1e-9)
        assertEquals(2.0, num("sqrt(4)"), 1e-9)
        assertEquals(0.0, num("sqrt(-4)"), 1e-9)
        assertEquals(1.0, num("sin(pi / 2)"), 1e-9)
        assertEquals(2.0, num("mod(-1, 3)"), 1e-9)
        assertEquals(2.0, num("avg(1, 2, 3)"), 1e-9)
    }

    @Test fun formatFunctionWritesNumbers() {
        assertEquals("1,387", text("fmt(1387, 'int')"))
        assertEquals("1.2K", text("fmt(1234, 'compact')"))
        assertEquals("88.5", text("fmt(88.46, '1')"))
    }

    @Test fun noiseIsDeterministicAndInRange() {
        val a = num("noise(1.3, 2.7, 0.5)")
        assertEquals(a, num("noise(1.3, 2.7, 0.5)"), 0.0)
        for (i in 0 until 200) {
            val v = num("noise(i * 0.37, i * 0.11)", "i" to i.toDouble())
            assertTrue("noise out of range: $v", v in 0.0..1.0)
            val r = num("rand(i)", "i" to i.toDouble())
            assertTrue("rand out of range: $r", r in 0.0..1.0)
        }
        assertEquals(num("rand(4)"), num("rand(4)"), 0.0)
    }

    @Test fun numberNeverReturnsNaNOrInfinity() {
        assertEquals(0.0, num("1 / 0"), 0.0)
        assertEquals(0.0, num("0 / 0"), 0.0)
        assertEquals(0.0, num("log(-1)"), 0.0)
        assertEquals(0.0, num("5 % 0"), 0.0)
    }

    @Test fun usesReportsTheNamesAnExpressionReads() {
        val expr = WidgetExpr.parse("sin(t * 2) + x + pi")
        assertTrue(expr.uses("t"))
        assertTrue(expr.uses("x"))
        assertFalse(expr.uses("pi"))
        assertFalse(expr.uses("sin"))
    }

    @Test fun badExpressionsFailWithAReason() {
        for (bad in listOf("1 +", "foo(1)", "max()", "abs(1, 2)", "(1", "1 2", "'open", "a ? b", "1 = 1", "")) {
            try {
                WidgetExpr.parse(bad)
                fail("expected '$bad' to fail")
            } catch (e: ExprException) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        }
        assertNull(WidgetExpr.parseOrNull("1 +"))
        assertNotNull(WidgetExpr.parseOrNull("1 + 1"))
    }

    @Test fun tooLongOrTooDeepIsRefusedNotCrashed() {
        assertNull(WidgetExpr.parseOrNull("1" + " + 1".repeat(WidgetExpr.MAX_LENGTH)))
        assertNull(WidgetExpr.parseOrNull("(".repeat(500) + "1" + ")".repeat(500)))
        assertNull(WidgetExpr.parseOrNull("-".repeat(500) + "1"))
    }

    @Test fun scopedEnvLayersOverItsParent() {
        val scope = ScopedEnv { if (it == "a") 1.0 else null }
        scope["i"] = 4.0
        assertEquals(5.0, WidgetExpr.parse("a + i").number(scope), 0.0)
        scope["a"] = 10.0
        assertEquals(14.0, WidgetExpr.parse("a + i").number(scope), 0.0)
    }

    @Test fun templatesFillTheirHoles() {
        val tpl = Tpl.of("Total {round(perDay * 7)} calls, {note}")
        assertFalse(tpl.isStatic)
        assertEquals("Total 840 calls, ok", tpl.render { if (it == "perDay") 120.0 else if (it == "note") "ok" else null })
        // A hole that is not an expression stays as typed.
        assertEquals("keep {this one} ", Tpl.of("keep {this one} ").render(none))
        assertTrue(Tpl.of("plain").isStatic)
        assertEquals("", Tpl.EMPTY.render(none))
    }

    @Test fun formatsNumbers() {
        assertEquals("1387", WidgetFormat.number(1387.0))
        assertEquals("2.57", WidgetFormat.number(2.567))
        assertEquals("1,387", WidgetFormat.format(1387.0, "int"))
        assertEquals("88%", WidgetFormat.format(88.0, "percent"))
        assertEquals("+3", WidgetFormat.format(3.0, "signed"))
        assertEquals("-3", WidgetFormat.format(-3.0, "signed"))
        assertEquals("3.4M", WidgetFormat.format(3_400_000.0, "compact"))
        assertEquals("850 ms", WidgetFormat.withUnit(850.0, null, " ms"))
    }

    @Test fun parsesColors() {
        assertEquals(0xFF3B82F6.toInt(), WidgetFormat.parseColor("blue"))
        assertEquals(0xFFFF0000.toInt(), WidgetFormat.parseColor("#f00"))
        assertEquals(0xFF123456.toInt(), WidgetFormat.parseColor("#123456"))
        assertEquals(0x80123456.toInt(), WidgetFormat.parseColor("#80123456"))
        assertNull(WidgetFormat.parseColor("#12"))
        assertNull(WidgetFormat.parseColor("javascript:alert(1)"))
        assertNull(WidgetFormat.parseColor(null))
    }

    @Test fun niceScaleCoversTheDataWithRoundSteps() {
        val scale = WidgetFormat.niceScale(0.0, 143.0)
        assertEquals(0.0, scale.min, 0.0)
        assertTrue(scale.max >= 143.0)
        assertEquals(50.0, scale.step, 0.0)
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0), scale.ticks)
        val flat = WidgetFormat.niceScale(5.0, 5.0)
        assertTrue(flat.min < 5.0 && flat.max > 5.0)
    }
}
