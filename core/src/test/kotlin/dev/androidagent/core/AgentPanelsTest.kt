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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPanelsTest {
    private fun panel(vararg blocks: JsonObject, buttons: List<JsonObject> = emptyList()) = buildJsonObject {
        put("title", "Settings")
        put("blocks", buildJsonArray { blocks.forEach { add(it) } })
        if (buttons.isNotEmpty()) put("buttons", buildJsonArray { buttons.forEach { add(it) } })
    }

    private fun block(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject { put("type", type); body() }

    @Test fun parsesEveryBlockTypeAndAddsDoneWithoutButtons() {
        val parsed = AgentPanels.parse("p", panel(
            block("text") { put("text", "Hi") },
            block("status") { put("label", "Brightness"); put("value", "40%"); put("tone", "warn") },
            block("field") { put("id", "mail"); put("label", "Email"); put("kind", "email") },
            block("toggle") { put("id", "dnd"); put("label", "Do not disturb"); put("value", true) },
            block("choice") { put("id", "mode"); put("label", "Ringer"); put("options", buildJsonArray { add(JsonPrimitive("normal")); add(JsonPrimitive("silent")) }) },
            block("control") { put("control", "model") },
        )).getOrThrow()
        assertEquals(6, parsed.blocks.size)
        assertEquals(Tone.WARN, (parsed.blocks[1] as PanelBlock.Status).tone)
        assertEquals("true", (parsed.blocks[3] as PanelBlock.Toggle).initial)
        assertEquals(listOf(PanelButton("done", "Done")), parsed.buttons)
    }

    @Test fun anActionWithOffArgumentsIsASwitch() {
        val parsed = AgentPanels.parse("p", panel(
            block("action") {
                put("id", "torch"); put("label", "Torch"); put("tool", "system_controls"); put("icon", "nope"); put("accent", "amber")
                put("arguments", buildJsonObject { put("operation", "torch"); put("on", true) })
                put("off_arguments", buildJsonObject { put("operation", "torch"); put("on", false) })
                put("value", "true")
            },
            block("action") { put("id", "next"); put("label", "Next"); put("tool", "system_controls"); put("arguments", buildJsonObject { put("operation", "media") }) },
        )).getOrThrow()
        val torch = parsed.actions[0]
        assertTrue(torch.switch)
        assertTrue(torch.on)
        assertEquals("bolt", torch.icon)
        assertEquals(PanelAccent.AMBER, torch.accent)
        // The operation and its arguments, not the tool name every tile shares.
        assertEquals("torch on=true", torch.caption)
        assertEquals("torch on=false", torch.callFor(false)!!.caption)
        assertEquals("system_controls", PanelCall("system_controls", buildJsonObject { }).caption)
        assertEquals("open_app package=com.x", PanelCall("open_app", buildJsonObject { put("package", "com.x") }).caption)
        assertEquals("false", torch.callFor(false)!!.arguments["on"].toString())
        assertTrue(!parsed.actions[1].switch)
        // A refusal from the coordinator's check reaches the model with the action's id.
        val refused = AgentPanels.parse("p", panel(
            block("action") { put("id", "t"); put("label", "T"); put("tool", "tap"); put("arguments", buildJsonObject { }) },
        )) { tool, _ -> if (tool == "tap") "uses the screen" else null }
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("\"t\": uses the screen"))
        assertTrue(AgentPanels.parse("p", panel(block("action") { put("id", "t"); put("label", "T"); put("tool", "x") })).isFailure)
    }

    @Test fun refusesWhatTheAppCannotDraw() {
        listOf(
            panel(block("slider")),
            panel(block("field") { put("label", "No id") }),
            panel(block("field") { put("id", "a"); put("label", "A") }, block("toggle") { put("id", "a"); put("label", "B") }),
            panel(block("choice") { put("id", "c"); put("label", "C"); put("options", buildJsonArray { add(JsonPrimitive("one")) }) }),
            panel(block("control") { put("control", "model") }, block("control") { put("control", "model") }),
            panel(block("field") { put("id", "a"); put("label", "A"); put("kind", "html") }),
            buildJsonObject { put("title", "Empty") },
        ).forEach { assertTrue(it.toString(), AgentPanels.parse("p", it).isFailure) }
    }

    @Test fun acceptChecksOptionsAndLetsCancelSkipRequiredFields() {
        val parsed = AgentPanels.parse("p", panel(
            block("field") { put("id", "name"); put("label", "Name"); put("required", true) },
            block("choice") { put("id", "c"); put("label", "C"); put("options", buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) }) },
            buttons = listOf(buildJsonObject { put("id", "ok"); put("label", "OK") }, buildJsonObject { put("id", "skip"); put("label", "Skip"); put("style", "cancel") }),
        )).getOrThrow()
        assertNull(parsed.accept("ok", mapOf("name" to "Dana", "c" to "z")))
        assertNull(parsed.accept("ok", mapOf("c" to "x")))
        assertNotNull(parsed.accept("skip", emptyMap()))
        assertEquals(mapOf("name" to "Dana", "c" to "y"), parsed.accept("ok", mapOf("name" to "Dana", "c" to "y"))!!.values)
    }

    private fun volume(body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = block("slider") {
        put("id", "vol"); put("label", "Volume"); put("min", 0); put("max", 100); put("step", 5); put("value", 40)
        put("tool", "system_controls")
        put("arguments", buildJsonObject { put("operation", "volume"); put("stream", "media"); put("percent", AgentPanels.VALUE) })
        body()
    }

    @Test fun aPlainSliderIsAFormInputWithDefaults() {
        val parsed = AgentPanels.parse("p", panel(block("slider") { put("id", "s"); put("label", "S"); put("min", 1); put("max", 10) })).getOrThrow()
        val slider = parsed.blocks.single() as PanelBlock.Slider
        assertEquals(1.0, slider.step, 0.0)
        assertEquals("1", slider.initial)
        assertTrue(!slider.live)
        assertTrue(parsed.liveSliders.isEmpty())
        assertEquals(mapOf("s" to "1"), parsed.accept("done", emptyMap())!!.values)
        assertEquals(mapOf("s" to "7"), parsed.accept("done", mapOf("s" to "7"))!!.values)
    }

    @Test fun aWholeLiveSliderSendsAJsonIntegerWhereTheMarkIs() {
        val parsed = AgentPanels.parse("p", panel(volume())).getOrThrow()
        val slider = parsed.liveSliders.single()
        assertTrue(slider.whole)
        assertEquals(20, slider.intervals)
        assertEquals("40", slider.initial)
        val call = slider.callAt(63.0)!!
        assertEquals("system_controls", call.tool)
        // 63 snaps to 65; a JSON integer, not "65" and not 65.0.
        assertEquals(JsonPrimitive(65L), call.arguments["percent"])
        assertEquals("{\"operation\":\"volume\",\"stream\":\"media\",\"percent\":65}", call.arguments.toString())
        assertEquals("volume stream=media percent=65", call.caption)
    }

    @Test fun aDecimalSliderKeepsTheStepsDecimals() {
        val parsed = AgentPanels.parse("p", panel(block("slider") {
            put("id", "z"); put("label", "Zoom"); put("min", 0.5); put("max", 2); put("step", 0.25); put("value", "1.1")
        })).getOrThrow()
        val slider = parsed.blocks.single() as PanelBlock.Slider
        assertTrue(!slider.whole)
        assertEquals("1.00", slider.initial)
        assertEquals("1.75", slider.format(slider.snap(1.8)))
        assertEquals("0.50", slider.format(slider.snap(-4.0)))
        assertTrue(slider.holds("1.25"))
        assertTrue(!slider.holds("1.3"))
        assertTrue(!slider.holds("3"))
        assertTrue(!slider.holds("loud"))
    }

    @Test fun aNewSliderShowsDecimalsOnlyWhenItAsksForThem() {
        fun fine(body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = panel(block("slider") {
            put("id", "v"); put("label", "Volume"); put("min", 0); put("max", 100); put("step", 0.1); body()
        })
        // The Xiaomi case: a fine step for a smooth track read 75.4. A new panel must ask for decimals.
        val refused = AgentPanels.parse("p", fine(), created = true).exceptionOrNull()?.message.orEmpty()
        assertTrue(refused, "leave step out" in refused && "set decimals" in refused)
        val asked = AgentPanels.parse("p", fine { put("decimals", 1) }, created = true).getOrThrow().blocks.single() as PanelBlock.Slider
        assertEquals("75.4", asked.format(asked.snap(75.38)))
        assertEquals("0.3", asked.format(asked.snap(0.30000000000000004)))
        assertEquals(0.3, asked.snap(0.1 + 0.2), 0.0)
        assertEquals("75.0", asked.format(75.0))
        // The Xiaomi case, round 6: decimals 2 on a 0.5 step read "0.5" while Mike said "0.50".
        val mix = AgentPanels.parse("p", panel(block("slider") {
            put("id", "m"); put("label", "Mix"); put("min", 0); put("max", 1); put("step", 0.5); put("decimals", 2)
        }), created = true).getOrThrow().blocks.single() as PanelBlock.Slider
        assertEquals("0.50", mix.format(mix.snap(0.5)))
        assertEquals("1.00", mix.format(1.0))
        // Leaving step out gives whole numbers on the same smooth track.
        val whole = AgentPanels.parse("p", panel(block("slider") {
            put("id", "v"); put("label", "Volume"); put("min", 0); put("max", 100)
        }), created = true).getOrThrow().blocks.single() as PanelBlock.Slider
        assertEquals(0, whole.decimals)
        assertEquals("75", whole.format(whole.snap(75.4)))
        // A panel saved before still loads with its step's decimals.
        val saved = AgentPanels.parse("p", fine()).getOrThrow().blocks.single() as PanelBlock.Slider
        assertEquals(1, saved.decimals)
        assertEquals("75.4", saved.format(saved.snap(75.4)))
        // Fewer places than the step needs, or more than 4, is refused.
        assertTrue(AgentPanels.parse("p", fine { put("decimals", 0) }, created = true).isFailure)
        assertTrue(AgentPanels.parse("p", fine { put("decimals", 5) }).isFailure)
        assertTrue(AgentPanels.parse("p", fine { put("decimals", "x") }).isFailure)
    }

    @Test fun aDecimalLiveSliderSendsItsRoundedNumber() {
        val parsed = AgentPanels.parse("p", panel(block("slider") {
            put("id", "b"); put("label", "Level"); put("min", 0); put("max", 1); put("step", 0.1); put("decimals", 1)
            put("tool", "system_controls"); put("arguments", buildJsonObject { put("operation", "brightness"); put("level", "{value}") })
        }), created = true).getOrThrow()
        val call = parsed.liveSliders.single().callAt(0.1 + 0.2)!!
        assertEquals(JsonPrimitive(0.3), call.arguments["level"])
    }

    @Test fun theFloatingTrackShowsAndSendsTheSameNumberAsTheChat() {
        val track = PanelFace.Row.Track("v", "Volume", 0.0, 1.0, 0.1, 0.0, "0", decimals = 1)
        assertEquals(0.3, track.valueAt(3), 0.0)
        assertEquals("0.3", track.textAt(3))
        assertEquals("0.0", track.textAt(0))
        assertEquals("0.50", PanelFace.Row.Track("m", "Mix", 0.0, 1.0, 0.5, 0.0, "0.00", decimals = 2).textAt(1))
        val whole = PanelFace.Row.Track("v", "Volume", 0.0, 100.0, 1.0, 0.0, "0")
        assertEquals("75", whole.textAt(75))
    }

    @Test fun acceptRefusesASliderValueOffTheTrack() {
        val parsed = AgentPanels.parse("p", panel(block("slider") { put("id", "s"); put("label", "S"); put("min", 0); put("max", 10); put("step", 2) },
            buttons = listOf(buildJsonObject { put("id", "ok"); put("label", "OK") }, buildJsonObject { put("id", "no"); put("label", "No"); put("style", "cancel") }),
        )).getOrThrow()
        assertNull(parsed.accept("ok", mapOf("s" to "3")))
        assertNull(parsed.accept("ok", mapOf("s" to "12")))
        assertNull(parsed.accept("ok", mapOf("s" to "NaN")))
        assertNotNull(parsed.accept("no", mapOf("s" to "3")))
        assertEquals(mapOf("s" to "4"), parsed.accept("ok", mapOf("s" to "4"))!!.values)
    }

    @Test fun refusesSlidersThatCannotWork() {
        fun s(body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = panel(block("slider") { put("id", "s"); put("label", "S"); body() })
        listOf(
            s { put("max", 10) },
            s { put("min", 0) },
            s { put("min", 5); put("max", 5) },
            s { put("min", 0); put("max", 10); put("step", 0) },
            s { put("min", 0); put("max", 10); put("step", 3) },
            s { put("min", 0); put("max", 10_000) },
            s { put("min", 0); put("max", 10); put("value", 11) },
            s { put("min", "low"); put("max", 10) },
            s { put("min", 0); put("max", 2e9) },
            s { put("min", 0); put("max", 10); put("tool", "system_controls") },
            s { put("min", 0); put("max", 10); put("arguments", buildJsonObject { put("level", AgentPanels.VALUE) }) },
            s { put("min", 0); put("max", 10); put("tool", "system_controls"); put("arguments", buildJsonObject { put("level", 4) }) },
        ).forEach { assertTrue(it.toString(), AgentPanels.parse("p", it).isFailure) }
    }

    @Test fun theCallCheckSeesTheRealNumberAndLiveSlidersCountAsActions() {
        var seen: JsonObject? = null
        val refused = AgentPanels.parse("p", panel(volume())) { _, args -> seen = args; "no volume here" }
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("Slider \"vol\": no volume here"))
        assertEquals(JsonPrimitive(40L), seen!!["percent"])
        val tiles = (1..AgentPanels.MAX_ACTIONS).map { i ->
            block("action") { put("id", "a$i"); put("label", "A"); put("tool", "system_controls"); put("arguments", buildJsonObject { put("operation", "status") }) }
        }
        assertTrue(AgentPanels.parse("p", panel(*tiles.toTypedArray())).isSuccess)
        assertTrue(AgentPanels.parse("p", panel(*(tiles + volume()).toTypedArray())).isFailure)
    }

    @Test fun aSlidersRunIsReportedWithItsValue() {
        val parsed = AgentPanels.parse("p", panel(volume())).getOrThrow()
        val reply = parsed.accept(null, emptyMap())!!
        val result = AgentPanels.result(parsed, reply, mask = true, log = listOf(PanelActionLog("vol", "Volume", "system_controls", null, true, "ok", "65")))
        assertTrue(result, result.contains("\"id\":\"vol\",\"label\":\"Volume\",\"tool\":\"system_controls\",\"value\":\"65\",\"ok\":true"))
        assertTrue(result, !result.contains("\"on\""))
    }

    @Test fun closingIsNotASubmit() {
        val parsed = AgentPanels.parse("p", panel(block("field") { put("id", "name"); put("label", "Name"); put("required", true) })).getOrThrow()
        val closed = parsed.accept(null, emptyMap())!!
        val result = AgentPanels.result(parsed, closed, mask = true)
        assertTrue(result, result.contains("\"errorType\":\"closed\""))
        assertEquals("Closed \"Settings\"", AgentPanels.summary(parsed, closed))
    }
}
