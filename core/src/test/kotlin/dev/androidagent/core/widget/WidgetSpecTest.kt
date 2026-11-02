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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The control-center dashboard from the reference video, written in the widget format. */
internal val CONTROL_CENTER = """
{
  "title": "Agent Control Center",
  "subtitle": "דשבורד לדוגמה — הנתונים מדומים",
  "dir": "rtl",
  "body": [
    {"type": "stats", "items": [
      {"label": "פעולות", "value": "=perDay * 7", "format": "int", "icon": "check", "color": "green"},
      {"label": "שיעור הצלחה", "value": "=rate", "unit": "%", "icon": "target"},
      {"label": "זמן תגובה", "value": "=latency", "unit": " ms", "icon": "bolt"}
    ]},
    {"type": "card", "title": "פרמטרים חיים", "children": [
      {"type": "slider", "id": "perDay", "label": "פעולות ביום", "min": 20, "max": 400, "step": 10, "value": 120},
      {"type": "slider", "id": "rate", "label": "שיעור הצלחה", "min": 50, "max": 100, "value": 92, "unit": "%"},
      {"type": "slider", "id": "latency", "label": "זמן תגובה", "min": 100, "max": 3000, "step": 50, "value": 850, "unit": " ms"}
    ]},
    {"type": "card", "title": "פעילות הסוכן", "children": [
      {"type": "select", "id": "days", "options": [
        {"value": 7, "label": "7 ימים"}, {"value": 14, "label": "14 ימים"}, {"value": 30, "label": "30 ימים"}], "value": 14},
      {"type": "chart", "id": "activity", "kinds": ["bar", "line"], "points": "=days", "xLabel": "{days - i}", "series": [
        {"name": "הצלחות", "color": "blue", "values": "=perDay * rate / 100 * (0.8 + 0.4 * noise(i * 0.7))"},
        {"name": "כשלונות", "color": "purple", "values": "=perDay * (100 - rate) / 100 * (0.8 + 0.4 * noise(i * 0.9 + 9))", "when": "=showFailed"}
      ]},
      {"type": "toggle", "id": "showFailed", "label": "הצג גם פעולות שנכשלו", "value": true}
    ]},
    {"type": "row", "children": [
      {"type": "gauge", "label": "מד עגול", "value": "=rate", "unit": "%", "caption": "מתעדכן מהסליידר"},
      {"type": "chart", "kind": "pie", "title": "גרף עוגה", "slices": [
        {"label": "הצלחה", "value": "=rate", "color": "blue"}, {"label": "כישלון", "value": "=100 - rate", "color": "purple"}]}
    ]},
    {"type": "card", "title": "בונוס: תצוגה אינטראקטיבית", "children": [
      {"type": "tabs", "id": "mode", "options": [
        {"value": "wave", "label": "גל"}, {"value": "radial", "label": "רדיאלי"}, {"value": "noise", "label": "רעש"}]},
      {"type": "dots", "cols": 14, "rows": 10,
       "value": "=power / 100 * (mode == 'wave' ? 0.5 + 0.5 * sin(t * 2 + x * 8) : mode == 'radial' ? 0.5 + 0.5 * sin(t * 2 - dist(x, y, 0.5, 0.5) * 12) : noise(x * 5, y * 5, t))"},
      {"type": "slider", "id": "power", "label": "עוצמת האפקט", "min": 0, "max": 100, "value": 65, "unit": "%"}
    ]},
    {"type": "table", "columns": ["יום", "פעולות"], "rows": [["ראשון", 120], ["שני", "{perDay}"]]},
    {"type": "button", "label": "העתק נתונים כ-JSON", "action": "copy"},
    {"type": "button", "label": "שלח את ההגדרות למייק", "action": "ask"}
  ]
}
"""

class WidgetSpecTest {
    private fun envOf(state: Map<String, Any>) = StateEnv(state)

    @Test fun theControlCenterParsesWithoutWarnings() {
        val spec = WidgetSpec.parse(CONTROL_CENTER)
        assertEquals(emptyList<String>(), spec.warnings)
        assertEquals(true, spec.rtl)
        assertEquals(8, spec.body.size)
        val state = spec.initialState()
        assertEquals(120.0, state["perDay"])
        assertEquals(92.0, state["rate"])
        assertEquals(850.0, state["latency"])
        assertEquals(14.0, state["days"])
        assertEquals(1.0, state["showFailed"])
        assertEquals("wave", state["mode"])
        assertEquals(65.0, state["power"])
    }

    @Test fun nestedBlocksAreFoundDepthFirst() {
        val kinds = WidgetSpec.parse(CONTROL_CENTER).allBlocks().map { it::class.simpleName }
        assertTrue(kinds.count { it == "Slider" } == 4)
        assertTrue(kinds.count { it == "Chart" } == 2)
        assertTrue("Dots" in kinds && "Gauge" in kinds && "Table" in kinds)
    }

    @Test fun aSliderDrivesTheStatAndTheChart() {
        val spec = WidgetSpec.parse(CONTROL_CENTER)
        val stats = spec.body[0] as Block.Stats
        val actions = stats.items[0]
        assertEquals(840.0, actions.value.eval(envOf(spec.initialState())), 1e-9)
        val busier = spec.initialState() + ("perDay" to 200.0)
        assertEquals(1400.0, actions.value.eval(envOf(busier)), 1e-9)
        assertEquals("1,400", WidgetFormat.format(1400.0, actions.format))

        val chart = spec.allBlocks().filterIsInstance<Block.Chart>().first { it.id == "activity" }
        val before = chart.evaluate(envOf(spec.initialState()))
        val after = chart.evaluate(envOf(busier))
        assertEquals(14, before.labels.size)
        assertEquals(listOf("14", "13", "12"), before.labels.take(3))
        assertEquals(14, before.series[0].values.size)
        assertTrue(after.series[0].values.sum() > before.series[0].values.sum())
    }

    @Test fun theDropdownChangesTheNumberOfPoints() {
        val spec = WidgetSpec.parse(CONTROL_CENTER)
        val chart = spec.allBlocks().filterIsInstance<Block.Chart>().first { it.id == "activity" }
        assertEquals(30, chart.evaluate(envOf(spec.initialState() + ("days" to 30.0))).labels.size)
        assertEquals(7, chart.evaluate(envOf(spec.initialState() + ("days" to 7.0))).labels.size)
    }

    @Test fun aSeriesCanBeHiddenWithWhen() {
        val spec = WidgetSpec.parse(CONTROL_CENTER)
        val chart = spec.allBlocks().filterIsInstance<Block.Chart>().first { it.id == "activity" }
        assertEquals(2, chart.evaluate(envOf(spec.initialState())).series.size)
        val hidden = chart.evaluate(envOf(spec.initialState() + ("showFailed" to 0.0)))
        assertEquals(listOf("הצלחות"), hidden.series.map { it.name })
    }

    @Test fun theChartCanSwitchKindAndKeepsTheFirstAsDefault() {
        val chart = WidgetSpec.parse(CONTROL_CENTER).allBlocks().filterIsInstance<Block.Chart>().first()
        assertEquals(listOf(ChartKind.BAR, ChartKind.LINE), chart.kinds)
    }

    @Test fun slicesBecomeOneSeriesWithPointColors() {
        val spec = WidgetSpec.parse(CONTROL_CENTER)
        val pie = spec.allBlocks().filterIsInstance<Block.Chart>().last()
        assertEquals(listOf(ChartKind.PIE), pie.kinds)
        val data = pie.evaluate(envOf(spec.initialState()))
        assertEquals(listOf("הצלחה", "כישלון"), data.labels)
        assertEquals(listOf(92.0, 8.0), data.series.single().values)
        assertEquals(0xFF3B82F6.toInt(), data.series.single().pointColors!![0])
    }

    @Test fun dotsKnowWhenTheyAnimate() {
        val dots = WidgetSpec.parse(CONTROL_CENTER).allBlocks().filterIsInstance<Block.Dots>().single()
        assertTrue(dots.animated)
        val still = WidgetSpec.parse("""{"body":[{"type":"dots","value":"=x*y"}]}""").allBlocks().single() as Block.Dots
        assertTrue(!still.animated)
    }

    @Test fun dotsStayInRangeForEveryModeAndCell() {
        val spec = WidgetSpec.parse(CONTROL_CENTER)
        val dots = spec.allBlocks().filterIsInstance<Block.Dots>().single()
        for (mode in listOf("wave", "radial", "noise")) {
            val base = ScopedEnv(envOf(spec.initialState() + ("mode" to mode)))
            for (j in 0 until dots.rows) for (i in 0 until dots.cols) {
                base["x"] = i / (dots.cols - 1.0)
                base["y"] = j / (dots.rows - 1.0)
                base["t"] = 1.7
                val v = dots.intensity.number(base)
                assertTrue("$mode cell $i,$j = $v", v in 0.0..1.0)
            }
        }
    }

    @Test fun snapshotHasTheValuesAndTheVisibleChartData() {
        val spec = WidgetSpec.parse(CONTROL_CENTER)
        val state = spec.initialState()
        val json = Json.parseToJsonElement(spec.snapshotText(envOf(state), state)).jsonObject
        assertEquals("120", json["values"]!!.jsonObject["perDay"]!!.jsonPrimitive.content.removeSuffix(".0"))
        assertEquals("wave", json["values"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        val activity = json["charts"]!!.jsonObject["activity"]!!.jsonObject
        assertEquals(14, activity["labels"]!!.jsonArray.size)
        assertEquals(setOf("הצלחות", "כשלונות"), activity["series"]!!.jsonObject.keys)
        assertNotNull(json["charts"]!!.jsonObject["גרף עוגה"])
    }

    @Test fun aSliderStartsInsideItsRange() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"slider","id":"a","min":0,"max":10,"value":99}]}""")
        assertEquals(10.0, spec.initialState()["a"])
    }

    @Test fun anUnknownBlockIsKeptAsUnsupportedWithAWarning() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"hologram"},{"type":"text","text":"hi"}]}""")
        assertTrue(spec.body[0] is Block.Unsupported)
        assertEquals(2, spec.body.size)
        assertTrue(spec.warnings.single().contains("hologram"))
    }

    @Test fun aBadExpressionDoesNotSinkTheWidget() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"gauge","label":"x","value":"=1 +"},{"type":"text","text":"still here"}]}""")
        assertEquals(2, spec.body.size)
        assertTrue(spec.warnings.single().contains("1 +"))
        assertEquals(0.0, (spec.body[0] as Block.Gauge).value.eval(envOf(emptyMap())), 0.0)
    }

    @Test fun duplicateAndMissingIdsAreRepaired() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"slider","id":"a"},{"type":"slider","id":"a"},{"type":"toggle","label":"x"}]}""")
        assertEquals(2, spec.inputs.size)
        assertTrue(spec.warnings.any { it.contains("share the id") })
        assertTrue(spec.warnings.any { it.contains("no usable") })
    }

    @Test fun jsonThatIsNotAWidgetIsRefused() {
        for (bad in listOf("", "not json", "42", """{"title":"x"}""", """{"body":[]}""", """{"body":"x"}""")) {
            try {
                WidgetSpec.parse(bad)
                fail("expected '$bad' to be refused")
            } catch (e: WidgetParseException) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test fun lenientJsonIsAcceptedBecauseModelsWriteIt() {
        val spec = WidgetSpec.parse("""{ body: [ {type: "text", text: "hi",}, ], }""")
        assertEquals(1, spec.body.size)
        val bare = WidgetSpec.parse("""[{"type":"text","text":"hi"}]""")
        assertEquals(1, bare.body.size)
    }

    @Test fun sizeAndDepthAreCapped() {
        try {
            WidgetSpec.parse("""{"body":[{"type":"text","text":"${"a".repeat(WidgetSpec.MAX_SOURCE_CHARS)}"}]}""")
            fail("expected an oversized widget to be refused")
        } catch (_: WidgetParseException) {
        }
        val many = (1..400).joinToString(",") { """{"type":"text","text":"$it"}""" }
        assertEquals(150, WidgetSpec.parse("""{"body":[$many]}""").body.size)
        var nested = """{"type":"text","text":"deep"}"""
        repeat(10) { nested = """{"type":"card","children":[$nested]}""" }
        val deep = WidgetSpec.parse("""{"body":[$nested]}""")
        assertTrue(deep.warnings.any { it.contains("nested deeper") })
    }

    @Test fun chartPointsAreCapped() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"line","points":1000000,"values":"=i"}]}""")
        val chart = spec.body.single() as Block.Chart
        assertEquals(MAX_POINTS, chart.evaluate(envOf(emptyMap())).labels.size)
    }

    @Test fun aFixedSeriesAndLabelsAreEnough() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"bar","x":["a","b","c"],"series":[{"name":"s","values":[1,2,"=3*2"]}]}]}""")
        val data = (spec.body.single() as Block.Chart).evaluate(envOf(emptyMap()))
        assertEquals(listOf("a", "b", "c"), data.labels)
        assertEquals(listOf(1.0, 2.0, 6.0), data.series.single().values)
        assertEquals(WidgetFormat.PALETTE[0], data.series.single().color)
    }

    @Test fun whenHidesABlock() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"text","text":"x","when":"=on"}]}""")
        val block = spec.body.single()
        assertNotNull(block.visible)
        assertTrue(block.visible!!.truthy(envOf(mapOf("on" to 1.0))))
        assertTrue(!block.visible!!.truthy(envOf(mapOf("on" to 0.0))))
        assertNull(WidgetSpec.parse("""{"body":[{"type":"text","text":"x"}]}""").body.single().visible)
    }

    @Test fun selectKeepsNumbersAsNumbersAndTextAsText() {
        val spec = WidgetSpec.parse(
            """{"body":[{"type":"select","id":"n","options":[{"value":7,"label":"7"},{"value":14,"label":"14"}],"value":14},
                {"type":"tabs","id":"m","options":["wave","noise"]}]}""",
        )
        assertEquals(14.0, spec.initialState()["n"])
        assertEquals("wave", spec.initialState()["m"])
        assertTrue((spec.body[1] as Block.Choice).tabs)
        assertTrue(!(spec.body[0] as Block.Choice).tabs)
    }

    @Test fun snapshotOfAnEmptyChartListHasOnlyValues() {
        val spec = WidgetSpec.parse("""{"body":[{"type":"slider","id":"a","value":3}]}""")
        val json: JsonObject = spec.snapshot(envOf(spec.initialState()), spec.initialState())
        assertTrue("charts" !in json)
    }
}
