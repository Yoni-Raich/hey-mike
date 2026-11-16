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

package dev.androidagent.devicetools

import dev.androidagent.core.ActiveNotification
import dev.androidagent.core.LocalIntentRequest
import dev.androidagent.core.MediaSessionAccess
import dev.androidagent.core.MediaSessionInfo
import dev.androidagent.core.NotificationAccess
import dev.androidagent.core.NotificationButton
import dev.androidagent.core.NotificationOutcome
import dev.androidagent.core.SendRequest
import dev.androidagent.core.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DeepControlTest {

    private class FakeNotifications : NotificationAccess, MediaSessionAccess {
        var granted = true
        var connected = true
        val calls = mutableListOf<String>()
        val rows = listOf(
            ActiveNotification(
                key = "0|com.chat|1|null|10", packageName = "com.chat", title = "Dana", text = "Lunch?",
                subText = null, postedAtMs = 5L, category = "msg", ongoing = false, clearable = true,
                buttons = listOf(NotificationButton(0, "Reply", true), NotificationButton(1, "Mark read", false)),
            ),
        )
        override fun granted() = granted
        override fun connected() = connected
        override fun active(packageName: String?, limit: Int) = rows.filter { packageName == null || it.packageName == packageName }.take(limit)
        override fun find(key: String) = rows.find { it.key == key }
        override fun reply(key: String, buttonIndex: Int?, text: String): NotificationOutcome { calls += "reply:$key:$text"; return NotificationOutcome.Done }
        override fun press(key: String, buttonIndex: Int?): NotificationOutcome { calls += "press:$key:$buttonIndex"; return NotificationOutcome.Done }
        override fun dismiss(key: String): NotificationOutcome { calls += "dismiss:$key"; return NotificationOutcome.Done }
        var media = listOf(MediaSessionInfo("com.music", "playing", "Song", "Artist", null, 1000L, 10L, listOf("pause")))
        override fun sessions() = media
        override fun control(packageName: String?, command: String, positionMs: Long?): NotificationOutcome {
            calls += "media:$packageName:$command:$positionMs"; return NotificationOutcome.Done
        }
    }

    private class FakeSystem : SystemPlatform {
        val calls = mutableListOf<String>()
        var canWrite = true
        override fun status(): Map<String, Any?> = mapOf("torch" to false, "ringer" to "normal")
        override fun setTorch(on: Boolean): SystemWrite { calls += "torch:$on"; return SystemWrite.Done }
        override fun setVolume(stream: String, level: Int?, percent: Int?, showUi: Boolean): Pair<SystemWrite, Map<String, Int>> {
            calls += "volume:$stream:$level:$percent"; return SystemWrite.Done to mapOf("level" to 5, "max" to 15)
        }
        override fun setRinger(mode: String): SystemWrite { calls += "ringer:$mode"; return SystemWrite.Done }
        override fun setDnd(mode: String): SystemWrite { calls += "dnd:$mode"; return SystemWrite.Missing("notification_policy", "No policy access.") }
        override fun setBrightness(level: Int?, auto: Boolean?): SystemWrite =
            if (canWrite) { calls += "brightness:$level:$auto"; SystemWrite.Done } else SystemWrite.Missing("write_settings", "No write.")
        override fun setScreenTimeout(seconds: Int): SystemWrite { calls += "timeout:$seconds"; return SystemWrite.Done }
        override fun setAutoRotate(on: Boolean): SystemWrite { calls += "rotate:$on"; return SystemWrite.Done }
        override fun clipboardGet(): String? = "copied"
        override fun clipboardSet(text: String): SystemWrite { calls += "clip:$text"; return SystemWrite.Done }
        override suspend fun location(precise: Boolean, timeoutMs: Long): LocationFix? = null
        override fun hasUsageAccess(): Boolean = false
        override fun usage(sinceMs: Long, untilMs: Long, limit: Int): List<UsageRow> = emptyList()
        override fun getSetting(namespace: String, key: String): String? = "1"
        override fun putSetting(namespace: String, key: String, value: String?): SystemWrite { calls += "put:$namespace:$key:$value"; return SystemWrite.Done }
    }

    private val approve: suspend (SendRequest, suspend () -> ToolResult) -> ToolResult = { _, dispatch -> dispatch() }
    private val approveChange: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = { _, dispatch -> dispatch() }
    private val deny: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = { _, _ -> ToolResult("{\"ok\":false,\"errorType\":\"declined\"}", success = false) }

    private fun run(
        tool: String,
        args: JsonObject,
        platform: CapabilityToolsTest.FakePlatform = CapabilityToolsTest.FakePlatform(),
        notifications: FakeNotifications = FakeNotifications(),
        system: FakeSystem = FakeSystem(),
        send: suspend (SendRequest, suspend () -> ToolResult) -> ToolResult = approve,
        change: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = approveChange,
    ): ToolResult {
        val tools = AndroidCapabilityTools(platform, notifications = notifications, media = notifications, system = system, authorizeSend = send, authorizeChange = change)
        tools.beginRun("r", Files.createTempDirectory("ws").toFile())
        return runBlocking { tools.invoke(tool, args) }
    }

    private fun json(result: ToolResult) = Json.parseToJsonElement(result.text).jsonObject
    private fun errorType(result: ToolResult) = json(result)["errorType"]?.jsonPrimitive?.content

    @Test fun listsLiveNotificationsWithButtons() {
        val result = run("communications", buildJsonObject { put("operation", "list_notifications") })
        assertTrue(result.text, result.success)
        val item = json(result)["items"]!!.jsonArray[0].jsonObject
        assertEquals("Dana", item["title"]!!.jsonPrimitive.content)
        assertEquals(2, item["buttons"]!!.jsonArray.size)
    }

    @Test fun notificationOpsNeedAccess() {
        val n = FakeNotifications().apply { granted = false }
        val result = run("communications", buildJsonObject { put("operation", "list_notifications") }, notifications = n)
        assertEquals("notification_access_missing", errorType(result))
    }

    @Test fun replyGoesThroughSendApproval() {
        val n = FakeNotifications()
        var asked: SendRequest? = null
        val result = run(
            "communications",
            buildJsonObject { put("operation", "notification_reply"); put("key", "0|com.chat|1|null|10"); put("text", "Yes") },
            notifications = n,
            send = { request, dispatch -> asked = request; dispatch() },
        )
        assertTrue(result.text, result.success)
        assertEquals("com.chat", asked!!.packageName)
        assertEquals("Yes", asked!!.message)
        assertEquals(listOf("reply:0|com.chat|1|null|10:Yes"), n.calls)
    }

    @Test fun deniedSendDoesNothing() {
        val n = FakeNotifications()
        val result = run(
            "communications",
            buildJsonObject { put("operation", "notification_reply"); put("key", "0|com.chat|1|null|10"); put("text", "Yes") },
            notifications = n,
            send = AndroidCapabilityTools.DENY_SEND,
        )
        assertFalse(result.success)
        assertTrue(n.calls.isEmpty())
    }

    @Test fun actionButtonPressNeedsChangeApproval() {
        val n = FakeNotifications()
        val result = run(
            "communications",
            buildJsonObject { put("operation", "notification_press"); put("key", "0|com.chat|1|null|10"); put("button", 1) },
            notifications = n,
            change = deny,
        )
        assertFalse(result.success)
        assertTrue(n.calls.isEmpty())
    }

    @Test fun sendSmsNeedsPermissionThenApproval() {
        val p = CapabilityToolsTest.FakePlatform()
        val args = buildJsonObject { put("operation", "send_sms"); put("to", "+15550001"); put("body", "Hi") }
        assertEquals("permission_denied", errorType(run("communications", args, platform = p)))
        p.granted += CapabilityPolicy.PERM_SEND_SMS
        val result = run("communications", args, platform = p)
        assertTrue(result.text, result.success)
        assertTrue(p.calls.contains("SEND_SMS:+15550001:Hi"))
    }

    @Test fun readSmsAndCallLog() {
        val p = CapabilityToolsTest.FakePlatform()
        p.granted += CapabilityPolicy.PERM_READ_SMS
        p.granted += CapabilityPolicy.PERM_READ_CALL_LOG
        val sms = run("communications", buildJsonObject { put("operation", "read_sms"); put("box", "inbox") }, platform = p)
        assertEquals("Hi there", json(sms)["items"]!!.jsonArray[0].jsonObject["body"]!!.jsonPrimitive.content)
        val calls = run("communications", buildJsonObject { put("operation", "read_call_log") }, platform = p)
        assertEquals("missed", json(calls)["items"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun calendarCreateDefaultsToOneHourAndDeleteAsks() {
        val p = CapabilityToolsTest.FakePlatform()
        p.granted += CapabilityPolicy.PERM_CALENDAR
        p.granted += CapabilityPolicy.PERM_WRITE_CALENDAR
        val created = run("calendar", buildJsonObject { put("operation", "create"); put("title", "Dentist"); put("beginMs", 1000) }, platform = p)
        assertTrue(created.text, created.success)
        assertTrue(p.calls.contains("EVENT:Dentist:1000:3601000:null"))
        val refused = run("calendar", buildJsonObject { put("operation", "delete"); put("id", "7") }, platform = p, change = deny)
        assertFalse(refused.success)
        assertFalse(p.calls.any { it.startsWith("DELETE_EVENT") })
    }

    @Test fun contactCreateNeedsWriteContacts() {
        val p = CapabilityToolsTest.FakePlatform()
        val args = buildJsonObject { put("operation", "create"); put("name", "Noa"); put("phone", "+15550009") }
        assertEquals("permission_denied", errorType(run("contacts", args, platform = p)))
        p.granted += CapabilityPolicy.PERM_WRITE_CONTACTS
        assertTrue(run("contacts", args, platform = p).success)
    }

    @Test fun systemControlsDispatchAndReportMissingGrant() {
        val s = FakeSystem()
        assertTrue(run(SystemPolicy.TOOL, buildJsonObject { put("operation", "torch"); put("on", true) }, system = s).success)
        assertEquals(listOf("torch:true"), s.calls)
        val dnd = run(SystemPolicy.TOOL, buildJsonObject { put("operation", "dnd"); put("mode", "priority") }, system = s)
        assertEquals("grant_missing", errorType(dnd))
        val bad = run(SystemPolicy.TOOL, buildJsonObject { put("operation", "volume"); put("stream", "bogus") }, system = s)
        assertEquals("invalid_argument", errorType(bad))
        val extra = run(SystemPolicy.TOOL, buildJsonObject { put("operation", "torch"); put("on", true); put("x", 1) }, system = s)
        assertEquals("unknown_argument", errorType(extra))
    }

    @Test fun putSettingNeedsApproval() {
        val s = FakeSystem()
        val args = buildJsonObject { put("operation", "put_setting"); put("namespace", "global"); put("key", "airplane_mode_on"); put("value", "1") }
        assertFalse(run(SystemPolicy.TOOL, args, system = s, change = deny).success)
        assertTrue(s.calls.isEmpty())
        assertTrue(run(SystemPolicy.TOOL, args, system = s).success)
        assertEquals(listOf("put:global:airplane_mode_on:1"), s.calls)
    }

    @Test fun seekBySkipsFromTheLivePositionWithinTheTrack() {
        val n = FakeNotifications()
        n.media = listOf(
            MediaSessionInfo("com.radio", "paused", "News", null, null, null, 5_000L, emptyList()),
            MediaSessionInfo("com.music", "playing", "Song", "Artist", null, 200_000L, 95_000L, listOf("pause")),
        )
        fun seek(offset: Int, pkg: String? = null) = run(SystemPolicy.TOOL, buildJsonObject {
            put("operation", "media_control"); put("command", "seek_by"); put("offset_ms", offset); pkg?.let { put("package", it) }
        }, notifications = n)
        val back = seek(-10_000)
        assertTrue(back.text, back.success)
        assertEquals("85000", json(back)["positionMs"]!!.jsonPrimitive.content)
        assertTrue(seek(600_000).success)
        assertTrue(seek(-60_000, "com.radio").success)
        assertEquals(listOf("media:com.music:seek:85000", "media:com.music:seek:200000", "media:com.radio:seek:0"), n.calls)
        assertEquals("invalid_argument", errorType(seek(600_001)))
        assertEquals("invalid_argument", errorType(run(SystemPolicy.TOOL, buildJsonObject {
            put("operation", "media_control"); put("command", "seek_by")
        }, notifications = n)))
        n.media = listOf(MediaSessionInfo("com.cast", "playing", "Live", null, null, null, null, emptyList()))
        assertEquals("no_position", errorType(seek(10_000)))
        n.media = emptyList()
        assertEquals("no_session", errorType(seek(10_000)))
        assertEquals(3, n.calls.size)
    }

    @Test fun mediaStatusNamesTheSessionCommandsActOn() {
        val n = FakeNotifications()
        n.media = listOf(
            MediaSessionInfo("com.radio", "paused", "News", null, null, null, 5_000L, emptyList()),
            MediaSessionInfo("com.music", "playing", "Song", "Artist", null, 200_000L, 95_000L, listOf("pause")),
        )
        val status = json(run(SystemPolicy.TOOL, buildJsonObject { put("operation", "media_status") }, notifications = n))
        val current = status["current"]!!.jsonObject
        assertEquals("com.music", current["package"]!!.jsonPrimitive.content)
        assertEquals("Song", current["title"]!!.jsonPrimitive.content)
        n.media = emptyList()
        assertNull(json(run(SystemPolicy.TOOL, buildJsonObject { put("operation", "media_status") }, notifications = n))["current"])
    }

    @Test fun systemControlsWithoutPlatformIsTyped() {
        val tools = AndroidCapabilityTools(CapabilityToolsTest.FakePlatform())
        tools.beginRun("r", Files.createTempDirectory("ws").toFile())
        val result = runBlocking { tools.invoke(SystemPolicy.TOOL, buildJsonObject { put("operation", "status") }) }
        assertEquals("capability_unsupported", errorType(result))
    }
}
