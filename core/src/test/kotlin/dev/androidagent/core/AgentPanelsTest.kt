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

    @Test fun closingIsNotASubmit() {
        val parsed = AgentPanels.parse("p", panel(block("field") { put("id", "name"); put("label", "Name"); put("required", true) })).getOrThrow()
        val closed = parsed.accept(null, emptyMap())!!
        val result = AgentPanels.result(parsed, closed, mask = true)
        assertTrue(result, result.contains("\"errorType\":\"closed\""))
        assertEquals("Closed \"Settings\"", AgentPanels.summary(parsed, closed))
    }
}
