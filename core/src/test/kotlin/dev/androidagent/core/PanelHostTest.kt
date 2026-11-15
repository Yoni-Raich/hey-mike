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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Approval, the signed store, ttl, re-checks and logging of panels that outlive their turn. */
@OptIn(ExperimentalCoroutinesApi::class)
class PanelHostTest {
    private class Store : SessionStore {
        override val sessions = MutableStateFlow(listOf(ChatSession("one", "One", 0, 0)))
        val messages = mutableListOf<ChatMessage>()
        val traces = mutableListOf<JsonObject>()
        override suspend fun createSession(engine: EngineKind) = sessions.value.first()
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String): Flow<List<ChatMessage>> = flowOf(messages.filter { it.sessionId == sessionId })
        override suspend fun append(message: ChatMessage) { messages.add(message) }
        override suspend fun appendTrace(sessionId: String, entry: JsonObject) { traces.add(entry) }
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File("session-$sessionId")
        fun traced(type: String, source: String? = null) = traces.filter {
            it["type"]?.jsonPrimitive?.content == type && (source == null || it["source"]?.jsonPrimitive?.content == source)
        }
    }

    private inner class Rig(val test: TestScope, val vault: InMemorySavedPanelVault = InMemorySavedPanelVault()) {
        val store = Store()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var now = 1_000_000L
        var refuse: String? = null
        var controlling = false
        var reply: (JsonObject) -> String = { """{"ok":true,"done":true}""" }
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        fun host() = PanelHost(
            test.backgroundScope, vault, store,
            invoke = { tool, args -> calls += tool to args; ToolResult(reply(args)) },
            checkCall = { _, _ -> refuse },
            clock = { now },
            controlling = { controlling },
            io = dispatcher,
        )
    }

    private fun system(op: String, body: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("operation", op); body() }

    private fun torch(extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("title", "Torch")
        put("blocks", buildJsonArray {
            add(buildJsonObject {
                put("type", "action"); put("id", "torch"); put("label", "Torch"); put("tool", "system_controls")
                put("arguments", system("torch") { put("on", true) }); put("off_arguments", system("torch") { put("on", false) })
            })
        })
        extra()
    }

    private fun id(result: ToolResult) = Json.parseToJsonElement(result.text).jsonObject["panelId"]!!.jsonPrimitive.content
    private fun status(result: ToolResult) = Json.parseToJsonElement(result.text).jsonObject["status"]!!.jsonPrimitive.content

    @Test fun aWidgetWaitsForApprovalAndRunsNothingUntilThen() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val result = host.propose("one", torch { put("surface", "widget") })
        assertTrue(result.success)
        assertEquals("awaiting_approval", status(result))
        val panelId = id(result)
        assertEquals(SavedPanel.Status.PENDING, host.get(panelId)!!.status)
        assertFalse(host.runAction(panelId, "torch"))
        runCurrent()
        assertTrue(rig.calls.isEmpty())
        assertEquals(SavedPanelVault.Read.Empty, rig.vault.read())

        assertNull(host.approve(panelId))
        runCurrent()
        assertEquals(SavedPanel.Status.ACTIVE, host.get(panelId)!!.status)
        assertTrue(host.runAction(panelId, "torch"))
        runCurrent()
        assertEquals(listOf("system_controls" to system("torch") { put("on", true) }), rig.calls)
        assertEquals(true, host.state(panelId, "torch").on)
        assertEquals(1, rig.store.traced("tool_call", "saved_panel").size)
        assertEquals(1, rig.store.traced("tool_result", "saved_panel").size)
        assertTrue(rig.store.messages.single().text.startsWith("system_controls:"))
    }

    @Test fun approvalWaitsWhileMikeControlsTheScreen() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val panelId = id(host.propose("one", torch { put("surface", "notification") }))
        rig.controlling = true
        assertNotNull(host.approve(panelId))
        assertEquals(SavedPanel.Status.PENDING, host.get(panelId)!!.status)
        rig.controlling = false
        assertNull(host.approve(panelId))
        assertNotNull("approving twice is refused", host.approve(panelId))
    }

    @Test fun aShortFloatingCardShowsAtOnceKeepsNothingOnDiskAndExpires() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val result = host.propose("one", torch { put("surface", "floating"); put("ttl_seconds", 60) })
        assertEquals("shown", status(result))
        val panelId = id(result)
        assertEquals(SavedPanel.Status.ACTIVE, host.get(panelId)!!.status)
        assertEquals(SavedPanelVault.Read.Empty, rig.vault.read())
        rig.now += 59_000
        host.tick()
        assertNotNull(host.active(panelId))
        rig.now += 1_000
        assertNull("expired at once, before the tick removes it", host.active(panelId))
        host.tick()
        assertNull(host.get(panelId))
        assertEquals(1, rig.store.traced("saved_panel_expired").size)
    }

    @Test fun aTtlCountsFromApproval() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val panelId = id(host.propose("one", torch { put("surface", "floating"); put("ttl_seconds", 120) }))
        rig.now += 500_000
        assertNull(host.approve(panelId))
        assertEquals(rig.now + 120_000, host.get(panelId)!!.expiresAtMs)
    }

    @Test fun aPersistedTtlPanelThatExpiredIsDroppedOnLoad() = runTest {
        val rig = Rig(this)
        val first = rig.host()
        val panelId = id(first.propose("one", torch { put("surface", "widget"); put("ttl_seconds", 600) }))
        assertNull(first.approve(panelId))
        assertNotNull(rig.host().get(panelId))
        rig.now += 601_000
        val later = rig.host()
        assertNull(later.get(panelId))
        val body = (rig.vault.read() as SavedPanelVault.Read.Valid).body
        assertFalse(body.contains(panelId))
    }

    @Test fun everyLoadChecksThePanelAgainAndBlocksOneThatNoLongerPasses() = runTest {
        val rig = Rig(this)
        val first = rig.host()
        val panelId = id(first.propose("one", torch { put("surface", "widget") }))
        assertNull(first.approve(panelId))
        rig.refuse = "system_controls is not available."
        val reloaded = rig.host()
        val saved = reloaded.get(panelId)!!
        assertEquals(SavedPanel.Status.BLOCKED, saved.status)
        assertTrue(saved.blocked!!.contains("not available"))
        assertFalse(reloaded.runAction(panelId, "torch"))
        runCurrent()
        assertTrue(rig.calls.isEmpty())
    }

    @Test fun everyTapChecksTheCallAgain() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val panelId = id(host.propose("one", torch { put("surface", "widget") }))
        assertNull(host.approve(panelId))
        rig.refuse = "torch is turned off by policy."
        assertFalse(host.runAction(panelId, "torch"))
        runCurrent()
        assertTrue(rig.calls.isEmpty())
        assertEquals(false, host.state(panelId, "torch").ok)
        assertEquals(1, rig.store.traced("tool_refused").size)
    }

    @Test fun aTamperedStoreLoadsEmptyAndSaysSo() = runTest {
        val rig = Rig(this)
        val first = rig.host()
        val panelId = id(first.propose("one", torch { put("surface", "widget") }))
        assertNull(first.approve(panelId))
        val (body, mac) = rig.vault.stored!!
        rig.vault.stored = body.replace("\"on\":true", "\"on\":false") to mac
        val reloaded = rig.host()
        assertTrue(reloaded.panels.value.isEmpty())
        assertNotNull(reloaded.notice.value)
    }

    @Test fun anOlderCopyOfTheStoreCannotBringBackARemovedPanel() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val keep = id(host.propose("one", torch { put("surface", "widget") }))
        val drop = id(host.propose("one", torch { put("surface", "notification") }))
        assertNull(host.approve(keep))
        assertNull(host.approve(drop))
        val before = rig.vault.stored
        host.remove(drop)
        assertNull(host.get(drop))
        assertFalse(host.runAction(drop, "torch"))
        rig.vault.stored = before
        val replayed = rig.host()
        assertTrue(replayed.panels.value.none { it.id == drop })
        assertNotNull(replayed.notice.value)
    }

    @Test fun removeAllEmptiesTheStore() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val panelId = id(host.propose("one", torch { put("surface", "widget") }))
        assertNull(host.approve(panelId))
        host.removeAll()
        assertTrue(rig.host().panels.value.isEmpty())
    }

    @Test fun pendingPanelsAreCapped() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        repeat(PanelHost.MAX_PENDING) { assertTrue(host.propose("one", torch { put("surface", "widget") }).success) }
        val over = host.propose("one", torch { put("surface", "widget") })
        assertFalse(over.success)
    }

    @Test fun aBadPanelIsRefusedWithTheReason() = runTest {
        val host = Rig(this).host()
        val result = host.propose("one", buildJsonObject {
            put("title", "Shell"); put("surface", "widget")
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("type", "action"); put("id", "a"); put("label", "A"); put("tool", "adb_shell")
                    put("arguments", buildJsonObject { put("command", "reboot") })
                })
            })
        })
        assertFalse(result.success)
        assertTrue(result.text, result.text.contains("cannot run without Mike"))
        assertTrue(host.panels.value.isEmpty())
    }

    @Test fun liveValuesAreReadWhileShownAndTracedOnlyWhenTheyChange() = runTest {
        val rig = Rig(this)
        var title = "One"
        rig.reply = { args ->
            if (args["operation"]?.jsonPrimitive?.content == "media_status") """{"ok":true,"current":{"title":"$title","state":"playing"}}"""
            else """{"ok":true,"done":true}"""
        }
        val host = rig.host()
        val panelId = id(host.propose("one", buildJsonObject {
            put("title", "Music"); put("surface", "notification")
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("type", "status"); put("label", "Now")
                    put("bind", buildJsonObject { put("tool", "system_controls"); put("arguments", system("media_status")); put("path", "current.title") })
                })
                add(buildJsonObject {
                    put("type", "action"); put("id", "play"); put("label", "Play"); put("tool", "system_controls")
                    put("arguments", system("media_control") { put("command", "toggle") })
                    put("bind", buildJsonObject {
                        put("tool", "system_controls"); put("arguments", system("media_status")); put("path", "current.state"); put("equals", "playing")
                    })
                })
            })
        }))
        assertNull(host.approve(panelId))
        host.tick()
        assertTrue("not read while not shown", rig.calls.isEmpty())
        host.setShown(panelId, true)
        host.tick()
        assertEquals("one read for both bound blocks", 1, rig.calls.size)
        assertEquals("One", host.live.value[panelId]!!.values[0])
        assertEquals(true, host.state(panelId, "play").on)
        rig.now += 5_000
        host.tick()
        assertEquals(2, rig.calls.size)
        assertEquals(1, rig.store.traced("tool_result", "saved_panel_bind").size)
        title = "Two"
        rig.now += 5_000
        host.tick()
        assertEquals("Two", host.live.value[panelId]!!.values[0])
        assertEquals(2, rig.store.traced("tool_result", "saved_panel_bind").size)
    }

    @Test fun nothingIsLoggedWhenTheChatIsGone() = runTest {
        val rig = Rig(this)
        val host = rig.host()
        val panelId = id(host.propose("one", torch { put("surface", "widget") }))
        assertNull(host.approve(panelId))
        rig.store.sessions.value = emptyList()
        rig.store.traces.clear()
        assertTrue(host.runAction(panelId, "torch"))
        runCurrent()
        assertEquals(1, rig.calls.size)
        assertTrue(rig.store.traces.isEmpty())
        assertTrue(rig.store.messages.isEmpty())
    }
}
