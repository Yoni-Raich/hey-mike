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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surfaces, lifetimes, the allowlist and live values: the parse side of panels outside the chat. */
class PanelPlacementTest {
    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is String -> put(k, v); is Number -> put(k, v); else -> error("type") } }
    }

    private fun block(type: String, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("type", type); body() }

    private fun panel(vararg blocks: JsonObject, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("title", "Music")
        put("blocks", buildJsonArray { blocks.forEach { add(it) } })
        extra()
    }

    private fun system(op: String, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("operation", op); body() }

    private fun mediaKey(id: String, command: String) = block("action") {
        put("id", id); put("label", id); put("tool", "system_controls")
        put("arguments", system("media_control") { put("command", command) })
    }

    @Test fun noSurfaceIsTheChatForTheTurn() {
        assertEquals(PanelPlacement.CHAT, PanelPlacement.parse(JsonObject(emptyMap())))
        assertFalse(PanelPlacement.CHAT.detached)
        assertFalse(PanelPlacement.CHAT.needsApproval)
    }

    @Test fun widgetAndNotificationArePinnedByDefaultAndTtlSecondsAloneMeansTtl() {
        assertEquals(PanelLifetime.Pinned, PanelPlacement.parse(args("surface" to "widget")).lifetime)
        assertEquals(PanelLifetime.Pinned, PanelPlacement.parse(args("surface" to "notification")).lifetime)
        assertEquals(PanelLifetime.Ttl(30), PanelPlacement.parse(args("surface" to "floating", "ttl_seconds" to 30)).lifetime)
        assertEquals(PanelLifetime.Ttl(PanelPlacement.SHORT_TTL_SECONDS), PanelPlacement.parse(args("surface" to "floating")).lifetime)
    }

    @Test fun badPlacementsAreRefusedWithAReason() {
        listOf(
            args("surface" to "wall"),
            args("surface" to "chat", "lifetime" to "pinned"),
            args("surface" to "widget", "lifetime" to "turn"),
            args("surface" to "floating", "lifetime" to "turn"),
            args("surface" to "floating", "lifetime" to "ttl"),
            args("surface" to "floating", "lifetime" to "ttl", "ttl_seconds" to 2),
            args("surface" to "floating", "lifetime" to "ttl", "ttl_seconds" to 100_000),
            args("surface" to "floating", "lifetime" to "pinned", "ttl_seconds" to 60),
            args("surface" to "floating", "lifetime" to "forever"),
        ).forEach { bad ->
            val error = runCatching { PanelPlacement.parse(bad) }.exceptionOrNull()
            assertTrue("$bad should be refused", error is IllegalArgumentException && !error.message.isNullOrBlank())
        }
    }

    @Test fun onlyAShortFloatingCardSkipsApproval() {
        fun needs(vararg pairs: Pair<String, Any>) = PanelPlacement.parse(args(*pairs)).needsApproval
        assertFalse(needs("surface" to "floating"))
        assertFalse(needs("surface" to "floating", "ttl_seconds" to 60))
        assertTrue(needs("surface" to "floating", "ttl_seconds" to 61))
        assertTrue(needs("surface" to "floating", "lifetime" to "pinned"))
        assertTrue(needs("surface" to "widget"))
        assertTrue(needs("surface" to "notification", "ttl_seconds" to 10))
    }

    @Test fun allowlistTakesOnlySystemSettingsAndMediaKeys() {
        fun action(tool: String, op: String?) = PanelAllowlist.checkAction(PanelCall(tool, buildJsonObject { op?.let { put("operation", it) } }))
        PanelAllowlist.ACTIONS.forEach { assertNull(it, action("system_controls", it)) }
        listOf("clipboard_get", "clipboard_set", "location", "usage", "put_setting", "get_setting", "screen_timeout", "status", "media_status")
            .forEach { assertNotNull(it, action("system_controls", it)) }
        listOf("adb_shell", "tap", "type_text", "screenshot", "send_message", "apps", "notifications").forEach {
            assertNotNull(it, action(it, "torch"))
        }
        assertNotNull(action("system_controls", null))
        assertNull(PanelAllowlist.checkRead(PanelCall("system_controls", system("media_status"))))
        assertNotNull(PanelAllowlist.checkRead(PanelCall("system_controls", system("torch"))))
    }

    @Test fun theSliderMarkCannotChooseTheOperationOrCommand() {
        assertNotNull(PanelAllowlist.checkAction(PanelCall("system_controls", buildJsonObject { put("operation", AgentPanels.VALUE) })))
        assertNotNull(PanelAllowlist.checkAction(PanelCall("system_controls", system("media_control") { put("command", "{value}x") })))
        assertNull(PanelAllowlist.checkAction(PanelCall("system_controls", system("volume") { put("percent", AgentPanels.VALUE) })))
    }

    @Test fun oldChatPanelsParseUnchanged() {
        val parsed = AgentPanels.parse("p", panel(
            block("field") { put("id", "mail"); put("label", "Email") },
            block("action") { put("id", "shot"); put("label", "Shot"); put("tool", "camera"); put("arguments", buildJsonObject { }) },
        )).getOrThrow()
        assertEquals(PanelPlacement.CHAT, parsed.placement)
        assertEquals(listOf(PanelButton("done", "Done")), parsed.buttons)
    }

    @Test fun aDetachedPanelRefusesCallsOffTheAllowlist() {
        val error = AgentPanels.parse("p", panel(
            block("action") { put("id", "shot"); put("label", "Shot"); put("tool", "camera"); put("arguments", buildJsonObject { }) },
        ) { put("surface", "widget") }).exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("cannot run without Mike"))
        val clip = AgentPanels.parse("p", panel(
            block("action") { put("id", "c"); put("label", "C"); put("tool", "system_controls"); put("arguments", system("clipboard_get")) },
        ) { put("surface", "notification") }).exceptionOrNull()
        assertNotNull(clip)
    }

    @Test fun aDetachedPanelRefusesInputsButtonsAndSlidersWhereTheyCannotWork() {
        val inputs = listOf("field", "toggle", "choice", "control").map { type ->
            AgentPanels.parse("p", panel(block(type) { put("id", "x"); put("label", "X"); put("control", "model") }) { put("surface", "floating") })
        }
        inputs.forEach { assertTrue(it.isFailure) }
        val buttons = AgentPanels.parse("p", panel(mediaKey("play", "toggle")) {
            put("surface", "floating"); put("lifetime", "pinned")
            put("buttons", buildJsonArray { add(buildJsonObject { put("id", "ok"); put("label", "OK") }) })
        })
        assertTrue(buttons.exceptionOrNull()?.message.orEmpty().contains("no buttons"))
        val slider = block("slider") {
            put("id", "vol"); put("label", "Volume"); put("min", 0); put("max", 100); put("tool", "system_controls")
            put("arguments", system("volume") { put("percent", AgentPanels.VALUE) })
        }
        assertTrue(AgentPanels.parse("p", panel(slider) { put("surface", "widget") }).isFailure)
        assertTrue(AgentPanels.parse("p", panel(slider) { put("surface", "floating"); put("ttl_seconds", 30) }).isSuccess)
        val plainSlider = block("slider") { put("id", "n"); put("label", "N"); put("min", 0); put("max", 10) }
        assertTrue(AgentPanels.parse("p", panel(plainSlider) { put("surface", "floating") }).isFailure)
    }

    @Test fun aNotificationShowsAtMostThreeActions() {
        val four = panel(mediaKey("a", "previous"), mediaKey("b", "toggle"), mediaKey("c", "next"), mediaKey("d", "stop")) {
            put("surface", "notification")
        }
        assertTrue(AgentPanels.parse("p", four).exceptionOrNull()?.message.orEmpty().contains("at most 3"))
        val three = AgentPanels.parse("p", panel(mediaKey("a", "previous"), mediaKey("b", "toggle"), mediaKey("c", "next")) {
            put("surface", "notification")
        }).getOrThrow()
        assertTrue(three.buttons.isEmpty())
        assertEquals(PanelSurface.NOTIFICATION, three.placement.surface)
    }

    @Test fun bindReadsAValueByPathAndOnlyOnADetachedPanel() {
        val status = block("status") {
            put("label", "Now playing")
            put("bind", buildJsonObject {
                put("tool", "system_controls"); put("arguments", system("media_status")); put("path", "current.title")
            })
        }
        assertTrue(AgentPanels.parse("p", panel(status)).isFailure)
        val parsed = AgentPanels.parse("p", panel(status) { put("surface", "widget") }).getOrThrow()
        val bind = parsed.binds.getValue(0)
        assertEquals("Song", bind.read("""{"ok":true,"current":{"title":"Song"}}"""))
        assertNull(bind.read("""{"ok":true}"""))
        assertEquals(listOf(bind.call), parsed.allCalls)
        val writes = block("status") {
            put("label", "x"); put("bind", buildJsonObject { put("tool", "system_controls"); put("arguments", system("torch")); put("path", "on") })
        }
        assertTrue(AgentPanels.parse("p", panel(writes) { put("surface", "widget") }).isFailure)
    }

    @Test fun bindFormatsTimeAndDecidesASwitch() {
        val time = PanelBind.parse(buildJsonObject {
            put("tool", "system_controls"); put("path", "sessions.0.positionMs"); put("format", "time")
        }).getOrThrow()
        assertEquals("1:05", time.read("""{"sessions":[{"positionMs":65000}]}"""))
        assertEquals("1:01:01", PanelBind.clock(3_661_000))
        val playing = PanelBind.parse(buildJsonObject { put("tool", "system_controls"); put("path", "current.state"); put("equals", "playing") }).getOrThrow()
        assertTrue(playing.isOn("PLAYING"))
        assertFalse(playing.isOn("paused"))
        assertTrue(PanelBind.parse(buildJsonObject { put("tool", "t"); put("path", "a..b") }).isFailure)
        assertTrue(PanelBind.parse(buildJsonObject { put("tool", "t"); put("path", "a"); put("every", 1) }).isFailure)
    }

    @Test fun approvalWordsDescribeEveryCall() {
        assertEquals("Turn the flashlight on", PanelCallWords.describe(PanelCall("system_controls", system("torch") { put("on", true) })))
        assertEquals("Skip back 10 s in the playing app",
            PanelCallWords.describe(PanelCall("system_controls", system("media_control") { put("command", "seek_by"); put("offset_ms", -10_000) })))
        assertEquals("Set the media volume to the slider's percent",
            PanelCallWords.describe(PanelCall("system_controls", system("volume") { put("percent", AgentPanels.VALUE) })))
    }
}
