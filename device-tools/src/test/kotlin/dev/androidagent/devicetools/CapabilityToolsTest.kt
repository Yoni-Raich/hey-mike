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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CapabilityToolsTest {

    // ---- surface ----

    @Test fun exactlyFiveOperationBasedTools() {
        val tools = AndroidCapabilityTools(FakePlatform())
        assertEquals(
            listOf("contacts", "calendar", "files_media", "communications", "apps_settings"),
            tools.definitions.map { it.name },
        )
        assertEquals(tools.definitions.map { it.name }.toSet(), tools.readyTools())
        // Local APIs stay ready, but they cannot operate the screen, so they
        // must not mask "no screen-control backend is live".
        assertFalse(tools.deviceBackendLive())
    }

    @Test fun unknownToolThrowsNotServiceable() {
        val tools = AndroidCapabilityTools(FakePlatform())
        tools.beginRun("r1", Files.createTempDirectory("ws").toFile())
        try {
            runBlocking { tools.invoke("shell", buildJsonObject { put("operation", "x") }) }
            fail("expected ToolNotServiceable")
        } catch (e: dev.androidagent.core.ToolNotServiceable) {
            assertEquals("capability_unsupported", e.errorType)
        }
    }

    @Test fun missingOperationFailsTyped() {
        val result = invoke("contacts", buildJsonObject { put("query", "ada") })
        assertFailure(result, "contacts", "missing_operation")
    }

    @Test fun nonStringOperationFailsTypedWithoutDispatch() {
        val fake = FakePlatform()
        val result = invoke(
            "contacts",
            buildJsonObject { put("operation", buildJsonObject { put("nested", true) }) },
            fake = fake,
        )
        assertFailure(result, "contacts", "missing_operation")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun unknownOperationFailsWithHintAndNoSideEffect() {
        val fake = FakePlatform()
        val result = invoke("contacts", buildJsonObject { put("operation", "delete") }, fake = fake)
        assertFailure(result, "contacts", "unknown_operation")
        assertTrue(result.text.contains("search"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun unknownArgumentRejectedBeforeSideEffect() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        val result = invoke(
            "contacts",
            buildJsonObject { put("operation", "search"); put("query", "ada"); put("shell", true) },
            fake = fake,
        )
        assertFailure(result, "contacts", "unknown_argument")
        assertTrue(result.text.contains("shell"))
        assertTrue(fake.calls.isEmpty())
    }

    // ---- limits ----

    @Test fun contactSearchLimitIsCoercedToMax() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        val result = invoke(
            "contacts",
            buildJsonObject { put("operation", "search"); put("query", "a"); put("limit", 5000) },
            fake = fake,
        )
        assertTrue(result.success)
        assertEquals(50, fake.lastLimit)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertTrue(json["count"]!!.jsonPrimitive.content.toInt() <= 50)
    }

    @Test fun zeroLimitIsRejected() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        val result = invoke(
            "contacts",
            buildJsonObject { put("operation", "search"); put("query", "a"); put("limit", 0) },
            fake = fake,
        )
        assertFailure(result, "contacts", "invalid_argument")
    }

    @Test fun contactSearchRequiresQuery() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        val result = invoke("contacts", buildJsonObject { put("operation", "search") }, fake = fake)
        assertFailure(result, "contacts", "invalid_argument")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun contactGetNotFoundIsTyped() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        val result = invoke("contacts", buildJsonObject { put("operation", "get"); put("id", "999") }, fake = fake)
        assertFailure(result, "contacts", "not_found")
    }

    // ---- permissions ----

    @Test fun permissionFailureCarriesPermissionAndCallableRetry() {
        val result = invoke("calendar", buildJsonObject { put("operation", "list"); put("limit", 7) })
        assertFailure(result, "calendar", "permission_denied")
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(CapabilityPolicy.PERM_CALENDAR, json["permission"]!!.jsonPrimitive.content)
        val retry = json["retry"]!!.jsonObject
        assertEquals("calendar", retry["tool"]!!.jsonPrimitive.content)
        // Retry is directly callable: arguments keep types and the operation.
        val replay = retry["arguments"]!!.jsonObject
        assertEquals("list", replay["operation"]!!.jsonPrimitive.content)
        assertEquals(7, replay["limit"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun retryReplaysAfterGrant() {
        val fake = FakePlatform()
        val denied = invoke("calendar", buildJsonObject { put("operation", "list"); put("limit", 3) }, fake = fake)
        assertFailure(denied, "calendar", "permission_denied")
        val retry = Json.parseToJsonElement(denied.text).jsonObject["retry"]!!.jsonObject
        fake.granted += CapabilityPolicy.PERM_CALENDAR
        val tools = AndroidCapabilityTools(fake)
        tools.beginRun("replay", Files.createTempDirectory("ws").toFile())
        val replayed = runBlocking {
            tools.invoke(retry["tool"]!!.jsonPrimitive.content, retry["arguments"]!!.jsonObject)
        }
        assertTrue(replayed.success)
        assertTrue(fake.calls.single().startsWith("queryEvents:"))
    }

    @Test fun retryArgumentsPreserveJsonTypes() {
        val src = buildJsonObject {
            put("operation", "request_permissions")
            put("count", 7)
            put("flag", true)
            put("name", "z".repeat(1000))
            put("list", buildJsonArrayOf("a", "b"))
            put("nested", buildJsonObject { put("k", 1) })
        }
        val bounded = CapabilityPolicy.retryArguments(src)
        assertEquals("request_permissions", bounded["operation"]!!.jsonPrimitive.content)
        assertEquals(7, bounded["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", bounded["flag"]!!.jsonPrimitive.content)
        assertEquals(CapabilityPolicy.MAX_STRING_CHARS, bounded["name"]!!.jsonPrimitive.content.length)
        assertEquals(listOf("a", "b"), bounded["list"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, bounded["nested"]!!.jsonObject["k"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun mediaInfoNeedsPermissionToo() {
        val result = invoke(
            "files_media",
            buildJsonObject { put("operation", "info"); put("uri", "content://media/1") },
        )
        assertFailure(result, "files_media", "permission_denied")
    }

    @Test fun requestPermissionsRejectsOutsideAllowlistWithoutCallback() {
        val fake = FakePlatform()
        var callbackCalls = 0
        val tools = AndroidCapabilityTools(fake) { callbackCalls++; it }
        tools.beginRun("r1", Files.createTempDirectory("ws").toFile())
        val result = runBlocking {
            tools.invoke(
                "apps_settings",
                buildJsonObject {
                    put("operation", "request_permissions")
                    put("permissions", buildJsonArrayOf("android.permission.SEND_SMS"))
                },
            )
        }
        assertFailure(result, "apps_settings", "permission_not_requestable")
        assertEquals(0, callbackCalls)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun requestPermissionsDedupsAndCallsBackOnce() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        var callbackCalls = 0
        var seen: Set<String> = emptySet()
        val tools = AndroidCapabilityTools(fake) { perms ->
            callbackCalls++
            seen = perms
            // Grant only contacts: calendar must come back denied.
            setOf(CapabilityPolicy.PERM_CONTACTS)
        }
        tools.beginRun("r1", Files.createTempDirectory("ws").toFile())
        val result = runBlocking {
            tools.invoke(
                "apps_settings",
                buildJsonObject {
                    put("operation", "request_permissions")
                    put(
                        "permissions",
                        buildJsonArrayOf(
                            CapabilityPolicy.PERM_CONTACTS,
                            CapabilityPolicy.PERM_CONTACTS,
                            CapabilityPolicy.PERM_CALENDAR,
                        ),
                    )
                },
            )
        }
        assertTrue(result.success)
        assertEquals(1, callbackCalls)
        // Only the missing permission reaches the callback.
        assertEquals(setOf(CapabilityPolicy.PERM_CALENDAR), seen)
        val json = Json.parseToJsonElement(result.text).jsonObject
        val granted = json["granted"]!!.jsonArray.map { it.jsonPrimitive.content }
        val denied = json["denied"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(granted.contains(CapabilityPolicy.PERM_CONTACTS))
        assertTrue(denied.contains(CapabilityPolicy.PERM_CALENDAR))
    }

    @Test fun requestPermissionsEmptyIsRejected() {
        val fake = FakePlatform()
        var callbackCalls = 0
        val tools = AndroidCapabilityTools(fake) { callbackCalls++; it }
        tools.beginRun("r1", Files.createTempDirectory("ws").toFile())
        val result = runBlocking {
            tools.invoke(
                "apps_settings",
                buildJsonObject {
                    put("operation", "request_permissions")
                    put("permissions", buildJsonArrayOf())
                },
            )
        }
        assertFailure(result, "apps_settings", "invalid_argument")
        assertEquals(0, callbackCalls)
    }

    // ---- URIs, kinds, settings ----

    @Test fun filesMediaRejectsFileScheme() {
        val result = invoke("files_media", buildJsonObject { put("operation", "open"); put("uri", "file:///sdcard/x.png") })
        assertFailure(result, "files_media", "invalid_uri")
    }

    @Test fun filesMediaRejectsHttpScheme() {
        val result = invoke("files_media", buildJsonObject { put("operation", "share"); put("uri", "https://example.com/x.png") })
        assertFailure(result, "files_media", "invalid_uri")
    }

    @Test fun filesMediaRejectsUnknownKind() {
        val fake = FakePlatform()
        val result = invoke(
            "files_media",
            buildJsonObject { put("operation", "list"); put("kind", "document") },
            fake = fake,
        )
        assertFailure(result, "files_media", "invalid_argument")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun openSystemSettingRejectsArbitraryAction() {
        val fake = FakePlatform()
        val result = invoke(
            "apps_settings",
            buildJsonObject { put("operation", "open_system_setting"); put("setting", "android.intent.action.DELETE") },
            fake = fake,
        )
        assertFailure(result, "apps_settings", "invalid_setting")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun openSystemSettingAllowsListedKey() {
        val fake = FakePlatform()
        val result = invoke(
            "apps_settings",
            buildJsonObject { put("operation", "open_system_setting"); put("setting", "wifi") },
            fake = fake,
        )
        assertTrue(result.success)
        assertEquals(listOf("SYSTEM:android.settings.WIFI_SETTINGS"), fake.calls)
    }

    @Test fun openSpecialAccessRejectsUnknownKind() {
        val fake = FakePlatform()
        val result = invoke(
            "apps_settings",
            buildJsonObject { put("operation", "open_special_access"); put("kind", "root") },
            fake = fake,
        )
        assertFailure(result, "apps_settings", "invalid_argument")
        assertTrue(fake.calls.isEmpty())
    }

    // ---- draft actions use non-sending intents ----

    @Test fun draftSmsUsesSendto() {
        val fake = FakePlatform()
        val result = invoke(
            "communications",
            buildJsonObject { put("operation", "draft_sms"); put("to", "+15551234"); put("body", "hi") },
            fake = fake,
        )
        assertTrue(result.success)
        assertEquals(listOf("SENDTO:smsto:+15551234"), fake.calls)
    }

    @Test fun draftEmailRejectsBadAddressWithoutSideEffect() {
        val fake = FakePlatform()
        val result = invoke(
            "communications",
            buildJsonObject { put("operation", "draft_email"); put("to", "not-an-address") },
            fake = fake,
        )
        assertFailure(result, "communications", "invalid_argument")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun dialUsesDialNeverCall() {
        val fake = FakePlatform()
        val result = invoke(
            "communications",
            buildJsonObject { put("operation", "dial"); put("number", "+15551234") },
            fake = fake,
        )
        assertTrue(result.success)
        assertEquals(listOf("DIAL:tel:+15551234"), fake.calls)
        assertTrue(fake.calls.none { it.contains("CALL") && !it.contains("DIAL") })
    }

    @Test fun dialWithoutNumberOpensDialer() {
        val fake = FakePlatform()
        val result = invoke("communications", buildJsonObject { put("operation", "dial") }, fake = fake)
        assertTrue(result.success)
        assertEquals(listOf("DIAL:"), fake.calls)
    }

    @Test fun createDraftNeedsContent() {
        val fake = FakePlatform()
        val result = invoke("contacts", buildJsonObject { put("operation", "create_draft") }, fake = fake)
        assertFailure(result, "contacts", "invalid_argument")
        assertTrue(fake.calls.isEmpty())
    }

    // ---- workspace ----

    @Test fun workspaceEscapeIsRejected() {
        val ws = Files.createTempDirectory("ws").toFile()
        val tools = AndroidCapabilityTools(FakePlatform())
        tools.beginRun("r1", ws)
        for (evil in listOf("../evil.txt", "/abs.txt", "a/../../evil.txt", "C:\\evil.txt", "sub\\file.txt", "file:///x")) {
            val result = runBlocking {
                tools.invoke("files_media", buildJsonObject { put("operation", "ws_read_text"); put("path", evil) })
            }
            assertTrue("must reject $evil", !result.success)
            assertFailure(result, "files_media", "invalid_argument")
        }
    }

    @Test fun workspaceSymlinkEscapeIsRejectedWhereSupported() {
        val ws = Files.createTempDirectory("ws").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            val target = File(outside, "secret.txt").apply { writeText("top-secret") }
            // A file symlink is the portable escape unit: a dir link is not
            // traversable on filesystems that type their links (Windows), and
            // then the read below just misses instead of leaking.
            try {
                Files.createSymbolicLink(File(ws, "link.txt").toPath(), target.toPath())
            } catch (_: Exception) {
                assumeTrue("symlinks need privilege on this machine", false)
                return
            }
            try {
                CapabilityPolicy.resolveWorkspaceFile(ws, "link.txt")
                fail("expected symlink escape rejection")
            } catch (e: PolicyException) {
                assertEquals("invalid_argument", e.errorType)
            }
            val result = invoke(
                "files_media",
                buildJsonObject { put("operation", "ws_read_text"); put("path", "link.txt") },
                workspace = ws,
            )
            assertFalse(result.success)
            assertFalse(result.text.contains("top-secret"))
        } finally {
            ws.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun workspaceWriteReadRoundtripIsAtomic() {
        val ws = Files.createTempDirectory("ws").toFile()
        val fake = FakePlatform()
        val tools = AndroidCapabilityTools(fake)
        tools.beginRun("r1", ws)
        val written = runBlocking {
            tools.invoke(
                "files_media",
                buildJsonObject { put("operation", "ws_write_text"); put("path", "notes/todo.txt"); put("text", "buy milk") },
            )
        }
        assertTrue(written.success)
        assertTrue(File(ws, "notes/todo.txt").readText() == "buy milk")
        assertTrue(ws.walkTopDown().none { it.name.endsWith(".tmp") })
        val read = runBlocking {
            tools.invoke("files_media", buildJsonObject { put("operation", "ws_read_text"); put("path", "notes/todo.txt") })
        }
        assertTrue(read.success)
        assertTrue(read.text.contains("buy milk"))
    }

    @Test fun workspaceWriteTooLargeIsRejected() {
        val ws = Files.createTempDirectory("ws").toFile()
        val tools = AndroidCapabilityTools(FakePlatform())
        tools.beginRun("r1", ws)
        val result = runBlocking {
            tools.invoke(
                "files_media",
                buildJsonObject {
                    put("operation", "ws_write_text")
                    put("path", "big.txt")
                    put("text", "x".repeat(CapabilityPolicy.MAX_TEXT_CHARS + 1))
                },
            )
        }
        assertFailure(result, "files_media", "file_too_large")
        assertFalse(File(ws, "big.txt").exists())
    }

    @Test fun workspaceReadMissingIsNotFound() {
        val result = invoke(
            "files_media",
            buildJsonObject { put("operation", "ws_read_text"); put("path", "nope.txt") },
            workspace = Files.createTempDirectory("ws").toFile(),
        )
        assertFailure(result, "files_media", "not_found")
    }

    // ---- revoke, caps, envelopes ----

    @Test fun revokeStopsNewOperations() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        val tools = AndroidCapabilityTools(fake)
        val ws = Files.createTempDirectory("ws").toFile()
        tools.beginRun("r1", ws)
        runBlocking {
            tools.invoke("contacts", buildJsonObject { put("operation", "permission_status") })
        }
        val callsBefore = fake.calls.size
        tools.revoke()
        try {
            runBlocking {
                tools.invoke("contacts", buildJsonObject { put("operation", "permission_status") })
            }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("stopped", ignoreCase = true))
        }
        assertEquals(callsBefore, fake.calls.size)
        tools.beginRun("r2", ws)
        runBlocking {
            tools.invoke("contacts", buildJsonObject { put("operation", "permission_status") })
        }
    }

    @Test fun invokeBeforeBeginRunIsDenied() {
        val tools = AndroidCapabilityTools(FakePlatform())
        try {
            runBlocking { tools.invoke("contacts", buildJsonObject { put("operation", "permission_status") }) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("stopped", ignoreCase = true))
        }
    }

    @Test fun outputCapKeepsValidJson() {
        val fake = FakePlatform()
        fake.mediaGranted = true
        fake.media = (1..50).map {
            MediaRow("content://media/$it", "n".repeat(512), "image/png", 10L, 1L)
        }
        val result = invoke("files_media", buildJsonObject { put("operation", "list"); put("limit", 50) }, fake = fake)
        assertTrue(result.success)
        assertTrue(result.text.length <= CapabilityPolicy.MAX_OUTPUT_CHARS)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("true", json["truncated"]!!.jsonPrimitive.content)
        assertTrue(json["items"]!!.jsonArray.isNotEmpty())
    }

    @Test fun everyFailureHasTypedEnvelope() {
        val failures = listOf(
            invoke("contacts", buildJsonObject { put("query", "x") }),
            invoke("calendar", buildJsonObject { put("operation", "nope") }),
            invoke("files_media", buildJsonObject { put("operation", "open"); put("uri", "file:///x") }),
            invoke(
                "apps_settings",
                buildJsonObject { put("operation", "open_system_setting"); put("setting", "nope") },
            ),
            invoke("calendar", buildJsonObject { put("operation", "list"); put("startMs", 5); put("endMs", 1) }),
        )
        for (result in failures) {
            assertFalse(result.success)
            val json = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("false", json["ok"]!!.jsonPrimitive.content)
            for (key in listOf("tool", "operation", "errorType", "message")) {
                assertTrue("missing $key in ${result.text}", json.containsKey(key))
            }
        }
    }

    @Test fun calendarListRejectsInvertedRange() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CALENDAR
        val result = invoke(
            "calendar",
            buildJsonObject { put("operation", "list"); put("startMs", 200); put("endMs", 100) },
            fake = fake,
        )
        assertFailure(result, "calendar", "invalid_argument")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun openAppUnknownPackageIsNotFound() {
        val fake = FakePlatform()
        val result = invoke(
            "apps_settings",
            buildJsonObject { put("operation", "open_app"); put("package", "com.example.nothing") },
            fake = fake,
        )
        assertFailure(result, "apps_settings", "not_found")
    }

    @Test fun oversizedSuccessPayloadStaysValidJson() {
        val text = CapabilityPolicy.ok("contacts", "search") { put("blob", "y".repeat(100_000)) }
        assertTrue(text.length <= CapabilityPolicy.MAX_OUTPUT_CHARS)
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("output_too_large", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("contacts", json["tool"]!!.jsonPrimitive.content)
        assertEquals("search", json["operation"]!!.jsonPrimitive.content)
    }

    @Test fun oversizedFailurePayloadStaysValidJson() {
        val text = CapabilityPolicy.fail("calendar", "list", "custom", "m".repeat(100)) {
            put("blob", "y".repeat(100_000))
        }
        assertTrue(text.length <= CapabilityPolicy.MAX_OUTPUT_CHARS)
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("custom", json["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun appendWritesAtomically() {
        val ws = Files.createTempDirectory("ws").toFile()
        val tools = AndroidCapabilityTools(FakePlatform())
        tools.beginRun("r1", ws)
        runBlocking {
            assertTrue(
                tools.invoke(
                    "files_media",
                    buildJsonObject { put("operation", "ws_write_text"); put("path", "log.txt"); put("text", "hello") },
                ).success,
            )
            val appended = tools.invoke(
                "files_media",
                buildJsonObject {
                    put("operation", "ws_write_text"); put("path", "log.txt"); put("text", " world"); put("append", true)
                },
            )
            assertTrue(appended.success)
            val read = tools.invoke(
                "files_media",
                buildJsonObject { put("operation", "ws_read_text"); put("path", "log.txt") },
            )
            assertTrue(read.text.contains("hello world"))
        }
        assertTrue(ws.walkTopDown().none { it.name.startsWith(".cap") || it.name.endsWith(".tmp") })
    }

    @Test fun appendEnforcesFinalCapAndWritesNothing() {
        val ws = Files.createTempDirectory("ws").toFile()
        val tools = AndroidCapabilityTools(FakePlatform())
        tools.beginRun("r1", ws)
        val before = "x".repeat(65_000)
        runBlocking {
            assertTrue(
                tools.invoke(
                    "files_media",
                    buildJsonObject { put("operation", "ws_write_text"); put("path", "big.txt"); put("text", before) },
                ).success,
            )
            val result = tools.invoke(
                "files_media",
                buildJsonObject {
                    put("operation", "ws_write_text"); put("path", "big.txt"); put("text", "y".repeat(2_000)); put("append", true)
                },
            )
            assertFailure(result, "files_media", "file_too_large")
        }
        assertEquals(before, File(ws, "big.txt").readText())
    }

    @Test fun replaceExistingOverwrites() {
        val ws = Files.createTempDirectory("ws").toFile()
        val tools = AndroidCapabilityTools(FakePlatform())
        tools.beginRun("r1", ws)
        runBlocking {
            tools.invoke(
                "files_media",
                buildJsonObject { put("operation", "ws_write_text"); put("path", "n.txt"); put("text", "one") },
            )
            assertTrue(
                tools.invoke(
                    "files_media",
                    buildJsonObject { put("operation", "ws_write_text"); put("path", "n.txt"); put("text", "two") },
                ).success,
            )
        }
        assertEquals("two", File(ws, "n.txt").readText())
    }

    @Test fun symlinkParentEscapeIsRejectedWhereSupported() {
        val ws = Files.createTempDirectory("ws").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            val marker = File(outside, "marker.txt").apply { writeText("outside") }
            try {
                Files.createSymbolicLink(File(ws, "evil").toPath(), outside.toPath())
            } catch (_: Exception) {
                assumeTrue("symlinks need privilege on this machine", false)
                return
            }
            try {
                CapabilityPolicy.resolveWorkspaceFile(ws, "evil/marker.txt")
                fail("expected symlink parent rejection")
            } catch (e: PolicyException) {
                assertEquals("invalid_argument", e.errorType)
            }
            val result = invoke(
                "files_media",
                buildJsonObject { put("operation", "ws_write_text"); put("path", "evil/marker.txt"); put("text", "pwned") },
                workspace = ws,
            )
            assertFalse(result.success)
            assertEquals("outside", marker.readText())
        } finally {
            ws.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun mediaPermissionMappingIsPure() {
        // Selected photos count for images and videos, never for audio.
        assertEquals(
            emptyList<String>(),
            CapabilityPolicy.mediaMissingFor("image") { it == CapabilityPolicy.PERM_MEDIA_SELECTED },
        )
        assertEquals(
            emptyList<String>(),
            CapabilityPolicy.mediaMissingFor("video") { it == CapabilityPolicy.PERM_MEDIA_SELECTED },
        )
        assertEquals(
            listOf(CapabilityPolicy.PERM_MEDIA_AUDIO),
            CapabilityPolicy.mediaMissingFor("audio") { it == CapabilityPolicy.PERM_MEDIA_SELECTED },
        )
        assertEquals(
            emptyList<String>(),
            CapabilityPolicy.mediaMissingFor("any") { it == CapabilityPolicy.PERM_MEDIA_AUDIO },
        )
        assertEquals(
            listOf(CapabilityPolicy.PERM_MEDIA_VIDEO),
            CapabilityPolicy.mediaMissingFor("video") { it == CapabilityPolicy.PERM_MEDIA_IMAGES },
        )
        assertEquals(
            3,
            CapabilityPolicy.mediaMissingFor("any") { false }.size,
        )
        assertEquals(
            setOf("image", "video"),
            CapabilityPolicy.readableKinds { it == CapabilityPolicy.PERM_MEDIA_SELECTED },
        )
        assertEquals(
            listOf("image"),
            CapabilityPolicy.anyQueryKinds(setOf("image")),
        )
        assertEquals(
            emptyList<String>(),
            CapabilityPolicy.anyQueryKinds(emptySet()),
        )
    }

    @Test fun selectedGrantReadsImagesButNotAudio() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_MEDIA_SELECTED
        assertTrue(invoke("files_media", buildJsonObject { put("operation", "list"); put("kind", "image") }, fake = fake).success)
        assertTrue(invoke("files_media", buildJsonObject { put("operation", "list"); put("kind", "video") }, fake = fake).success)
        assertTrue(invoke("files_media", buildJsonObject { put("operation", "list"); put("kind", "any") }, fake = fake).success)
        val audio = invoke("files_media", buildJsonObject { put("operation", "list"); put("kind", "audio") }, fake = fake)
        assertFailure(audio, "files_media", "permission_denied")
        assertTrue(audio.text.contains(CapabilityPolicy.PERM_MEDIA_AUDIO))
    }

    @Test fun unresolvablePathsFailClosed() {
        val ws = Files.createTempDirectory("ws").toFile()
        File(ws, "real.txt").writeText("x")
        val base = ws.toPath().toAbsolutePath().normalize()
        val broken: (java.nio.file.Path) -> java.nio.file.Path = { throw java.io.IOException("disk gone") }
        // Existing prefix that cannot be resolved is a typed failure, not a fallback.
        try {
            CapabilityPolicy.resolveIn(base, broken, "real.txt")
            fail("expected path_unresolvable")
        } catch (e: PolicyException) {
            assertEquals("path_unresolvable", e.errorType)
        }
        // Unresolvable workspace root is the same failure.
        try {
            CapabilityPolicy.resolveIn(base, broken, "new.txt")
            fail("expected path_unresolvable")
        } catch (e: PolicyException) {
            assertEquals("path_unresolvable", e.errorType)
        }
        // The seam still resolves a missing tail with a working resolver.
        val created = CapabilityPolicy.resolveIn(base, { it.toRealPath() }, "sub/new.txt")
        assertTrue(created.path.endsWith("new.txt"))
    }

    @Test fun hugeOperationStaysValidJson() {
        val result = invoke("contacts", buildJsonObject { put("operation", "x".repeat(100_000)) })
        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("unknown_operation", json["errorType"]!!.jsonPrimitive.content)
        assertTrue(result.text.length <= CapabilityPolicy.MAX_OUTPUT_CHARS)
    }

    @Test fun platformSecurityExceptionIsTyped() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        fake.throwSecurity = true
        val result = invoke(
            "contacts",
            buildJsonObject { put("operation", "search"); put("query", "ada") },
            fake = fake,
        )
        assertFailure(result, "contacts", "security_denied")
    }

    @Test fun cancellationStillCancels() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        fake.throwCancellation = true
        val tools = AndroidCapabilityTools(fake)
        tools.beginRun("r1", Files.createTempDirectory("ws").toFile())
        try {
            runBlocking {
                tools.invoke("contacts", buildJsonObject { put("operation", "search"); put("query", "ada") })
            }
            fail("expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Propagates instead of becoming a result.
        }
    }

    @Test fun successFlagMatchesEnvelope() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.REQUESTABLE_PERMISSIONS
        fake.mediaGranted = true
        fake.notifEnabled = true
        val ws = Files.createTempDirectory("ws").toFile()
        val tools = AndroidCapabilityTools(fake)
        tools.beginRun("happy", ws)
        val calls = listOf(
            "contacts" to buildJsonObject { put("operation", "permission_status") },
            "contacts" to buildJsonObject { put("operation", "search"); put("query", "a") },
            "contacts" to buildJsonObject { put("operation", "list") },
            "contacts" to buildJsonObject { put("operation", "get"); put("id", "1") },
            "contacts" to buildJsonObject { put("operation", "create_draft"); put("name", "N") },
            "calendar" to buildJsonObject { put("operation", "permission_status") },
            "calendar" to buildJsonObject { put("operation", "list") },
            "calendar" to buildJsonObject { put("operation", "get"); put("id", "7") },
            "calendar" to buildJsonObject { put("operation", "create_draft"); put("title", "T") },
            "files_media" to buildJsonObject { put("operation", "permission_status") },
            "files_media" to buildJsonObject { put("operation", "list") },
            "files_media" to buildJsonObject { put("operation", "info"); put("uri", "content://media/1") },
            "files_media" to buildJsonObject { put("operation", "open"); put("uri", "content://media/1") },
            "files_media" to buildJsonObject { put("operation", "share"); put("uri", "content://media/1") },
            "files_media" to buildJsonObject { put("operation", "ws_list") },
            "files_media" to buildJsonObject { put("operation", "ws_write_text"); put("path", "h.txt"); put("text", "hi") },
            "files_media" to buildJsonObject { put("operation", "ws_read_text"); put("path", "h.txt") },
            "communications" to buildJsonObject { put("operation", "draft_sms"); put("body", "hi") },
            "communications" to buildJsonObject { put("operation", "draft_email"); put("subject", "s") },
            "communications" to buildJsonObject { put("operation", "dial") },
            "communications" to buildJsonObject { put("operation", "notification_access_status") },
            "communications" to buildJsonObject { put("operation", "open_notification_access_settings") },
            "apps_settings" to buildJsonObject { put("operation", "list_apps") },
            "apps_settings" to buildJsonObject { put("operation", "app_info"); put("package", "com.example.app") },
            "apps_settings" to buildJsonObject { put("operation", "open_app"); put("package", "com.example.app") },
            "apps_settings" to buildJsonObject { put("operation", "open_app_settings"); put("package", "com.example.app") },
            "apps_settings" to buildJsonObject { put("operation", "permission_status") },
            "apps_settings" to buildJsonObject {
                put("operation", "request_permissions")
                put("permissions", buildJsonArrayOf(CapabilityPolicy.PERM_CONTACTS))
            },
            "apps_settings" to buildJsonObject { put("operation", "open_special_access"); put("kind", "overlay") },
            "apps_settings" to buildJsonObject { put("operation", "open_system_setting"); put("setting", "wifi") },
        )
        runBlocking {
            for ((tool, args) in calls) {
                val result = tools.invoke(tool, args)
                val envelope = Json.parseToJsonElement(result.text).jsonObject
                assertEquals(
                    "$tool ${args["operation"]}: flag contradicts envelope",
                    envelope["ok"]!!.jsonPrimitive.content.toBooleanStrict(),
                    result.success,
                )
            }
        }
    }

    @Test fun storageApiReportsStoragePermission() {
        val fake = FakePlatform()
        fake.storageApi = true
        val status = invoke("files_media", buildJsonObject { put("operation", "permission_status") }, fake = fake)
        assertTrue(status.success)
        val keys = Json.parseToJsonElement(status.text).jsonObject["permissions"]!!.jsonObject.keys
        assertEquals(setOf(CapabilityPolicy.PERM_STORAGE), keys)
        val denied = invoke("files_media", buildJsonObject { put("operation", "list") }, fake = fake)
        assertFailure(denied, "files_media", "permission_denied")
        assertTrue(denied.text.contains(CapabilityPolicy.PERM_STORAGE))
        fake.granted += CapabilityPolicy.PERM_STORAGE
        val allowed = invoke("files_media", buildJsonObject { put("operation", "list") }, fake = fake)
        assertTrue(allowed.success)
    }

    @Test fun requestPermissionsSkipsCallbackWhenAllGranted() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.PERM_CONTACTS
        fake.granted += CapabilityPolicy.PERM_CALENDAR
        var callbackCalls = 0
        val tools = AndroidCapabilityTools(fake) { callbackCalls++; it }
        tools.beginRun("r1", Files.createTempDirectory("ws").toFile())
        val result = runBlocking {
            tools.invoke(
                "apps_settings",
                buildJsonObject {
                    put("operation", "request_permissions")
                    put(
                        "permissions",
                        buildJsonArrayOf(CapabilityPolicy.PERM_CONTACTS, CapabilityPolicy.PERM_CALENDAR),
                    )
                },
            )
        }
        assertTrue(result.success)
        assertEquals(0, callbackCalls)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(2, json["granted"]!!.jsonArray.size)
        assertEquals(0, json["denied"]!!.jsonArray.size)
    }

    @Test fun schemasAreClosedAndEnumerated() {
        val expected = mapOf(
            "contacts" to CapabilityPolicy.CONTACT_OPS,
            "calendar" to CapabilityPolicy.CALENDAR_OPS,
            "files_media" to CapabilityPolicy.FILES_OPS,
            "communications" to CapabilityPolicy.COMM_OPS,
            "apps_settings" to CapabilityPolicy.APPS_OPS,
        )
        val tools = AndroidCapabilityTools(FakePlatform())
        assertEquals(expected.keys, tools.definitions.map { it.name }.toSet())
        for (def in tools.definitions) {
            val schema = def.inputSchema
            assertEquals(listOf("operation"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
            val ops = schema["properties"]!!.jsonObject["operation"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content }.toSet()
            assertEquals(expected[def.name], ops)
        }
        val apps = tools.definitions.first { it.name == "apps_settings" }
        val items = apps.inputSchema["properties"]!!.jsonObject["permissions"]!!.jsonObject["items"]!!.jsonObject
        assertEquals("string", items["type"]!!.jsonPrimitive.content)
    }

    @Test fun accessResultsAreHonest() {
        val fake = FakePlatform()
        val special = invoke(
            "apps_settings",
            buildJsonObject { put("operation", "open_special_access"); put("kind", "overlay") },
            fake = fake,
        )
        assertTrue(special.success)
        assertTrue(special.text.contains("setup", ignoreCase = true))
        val status = invoke("communications", buildJsonObject { put("operation", "notification_access_status") })
        assertTrue(status.success)
        assertTrue(status.text.contains("cannot read", ignoreCase = true))
    }

    @Test fun largeReadFitsEnvelope() {
        val ws = Files.createTempDirectory("ws").toFile()
        val tools = AndroidCapabilityTools(FakePlatform())
        tools.beginRun("r1", ws)
        val result = runBlocking {
            assertTrue(
                tools.invoke(
                    "files_media",
                    buildJsonObject {
                        put("operation", "ws_write_text"); put("path", "large.txt"); put("text", "L".repeat(60_000))
                    },
                ).success,
            )
            tools.invoke(
                "files_media",
                buildJsonObject { put("operation", "ws_read_text"); put("path", "large.txt") },
            )
        }
        assertTrue(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("true", json["truncated"]!!.jsonPrimitive.content)
        assertTrue(json["text"]!!.jsonPrimitive.content.length <= CapabilityPolicy.MAX_READ_CHARS)
        assertTrue(result.text.length <= CapabilityPolicy.MAX_OUTPUT_CHARS)
    }

    @Test fun mediaStoreAuthorityIsEnforced() {
        CapabilityPolicy.requireMediaStoreUri("content://media/external/images/media/123")
        CapabilityPolicy.requireMediaStoreUri("CONTENT://MEDIA/1")
        for (bad in listOf(
            "content://com.android.contacts/contacts/1",
            "content://com.example.provider/x",
            "content://media",
            "content://media/",
            "file:///sdcard/x.png",
            "https://example.com/x.png",
        )) {
            try {
                CapabilityPolicy.requireMediaStoreUri(bad)
                fail("expected rejection for $bad")
            } catch (e: PolicyException) {
                assertEquals("invalid_uri", e.errorType)
            }
        }
    }

    @Test fun infoRejectsNonMediaAuthorityBeforeDispatch() {
        val fake = FakePlatform()
        fake.mediaGranted = true
        val result = invoke(
            "files_media",
            buildJsonObject { put("operation", "info"); put("uri", "content://com.android.contacts/contacts/1") },
            fake = fake,
        )
        assertFailure(result, "files_media", "invalid_uri")
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun likePatternEscapesWildcards() {
        assertEquals("%a\\%b\\_c\\\\d%", CapabilityPolicy.likePattern("a%b_c\\d"))
        assertEquals("%plain%", CapabilityPolicy.likePattern("plain"))
        assertTrue(CapabilityPolicy.LIKE_ESCAPE.contains("ESCAPE"))
    }

    @Test fun nonStringFieldsAreRejected() {
        val fake = FakePlatform()
        fake.granted += CapabilityPolicy.REQUESTABLE_PERMISSIONS
        val cases = listOf(
            "contacts" to buildJsonObject { put("operation", "search"); put("query", 7) },
            "contacts" to buildJsonObject { put("operation", "search"); put("query", true) },
            "calendar" to buildJsonObject { put("operation", "create_draft"); put("title", "T"); put("allDay", "true") },
            "calendar" to buildJsonObject { put("operation", "create_draft"); put("title", "T"); put("allDay", 1) },
            "files_media" to buildJsonObject { put("operation", "ws_write_text"); put("path", "x.txt"); put("text", "ok"); put("append", "yes") },
            "files_media" to buildJsonObject { put("operation", "ws_write_text"); put("path", "x.txt"); put("text", 123) },
            "apps_settings" to buildJsonObject { put("operation", "list_apps"); put("include_system", 1) },
            "communications" to buildJsonObject { put("operation", "draft_email"); put("to", 5) },
            "apps_settings" to buildJsonObject {
                put("operation", "request_permissions")
                put("permissions", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(5))))
            },
            "apps_settings" to buildJsonObject { put("operation", "request_permissions"); put("permission", 5) },
        )
        for ((tool, args) in cases) {
            val result = invoke(tool, args, fake = fake)
            assertFailure(result, tool, "invalid_argument")
        }
    }

    @Test fun requestPermissionsAcceptsSingularKey() {
        val fake = FakePlatform()
        var seen: Set<String> = emptySet()
        val tools = AndroidCapabilityTools(fake) { perms -> seen = perms; perms }
        tools.beginRun("r1", Files.createTempDirectory("ws").toFile())
        val result = runBlocking {
            tools.invoke(
                "apps_settings",
                buildJsonObject {
                    put("operation", "request_permissions")
                    put("permission", CapabilityPolicy.PERM_CONTACTS)
                },
            )
        }
        assertTrue(result.success)
        assertEquals(setOf(CapabilityPolicy.PERM_CONTACTS), seen)
    }

    // ---- helpers ----

    private fun invoke(
        tool: String,
        args: JsonObject,
        fake: FakePlatform = FakePlatform(),
        workspace: File = Files.createTempDirectory("ws").toFile(),
        callback: suspend (Set<String>) -> Set<String> = { emptySet() },
    ): dev.androidagent.core.ToolResult {
        val tools = AndroidCapabilityTools(fake, callback)
        tools.beginRun("test", workspace)
        return runBlocking { tools.invoke(tool, args) }
    }

    private fun assertFailure(result: dev.androidagent.core.ToolResult, tool: String, errorType: String) {
        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("false", json["ok"]!!.jsonPrimitive.content)
        assertEquals(tool, json["tool"]!!.jsonPrimitive.content)
        assertEquals(errorType, json["errorType"]!!.jsonPrimitive.content)
        assertTrue(json["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    private fun buildJsonArrayOf(vararg values: String): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })

    private class FakePlatform : CapabilityPlatform {
        val granted = mutableSetOf<String>()
        var mediaGranted = false
        var mediaMissing: List<String> = emptyList()
        /** When true, behave like API 30-32: storage grant gates media. */
        var storageApi = false
        val calls = mutableListOf<String>()
        var handlerOk = true
        var notifEnabled = false
        var throwSecurity = false
        var throwCancellation = false
        var lastLimit = 0
        var contacts: List<ContactRow> = listOf(
            ContactRow("1", "Ada Lovelace", "+15550001", "ada@example.com"),
            ContactRow("2", "Alan Turing", "+15550002", null),
        )
        var events: List<EventRow> = listOf(EventRow("7", "Standup", 1000L, 2000L, "Office"))
        var media: List<MediaRow> = listOf(
            MediaRow("content://media/1", "photo.png", "image/png", 12L, 3L),
        )
        var apps: List<AppRow> = listOf(
            AppRow("com.example.app", "Example", "1.0", 1L, false),
            AppRow("android", "Android System", null, null, true),
        )
        val launchable = mutableSetOf("com.example.app")

        override fun hasPermission(permission: String): Boolean = permission in granted

        override fun mediaStatusPermissions(): Map<String, Boolean> =
            if (storageApi) {
                mapOf(CapabilityPolicy.PERM_STORAGE to (CapabilityPolicy.PERM_STORAGE in granted))
            } else {
                listOf(
                    CapabilityPolicy.PERM_MEDIA_IMAGES,
                    CapabilityPolicy.PERM_MEDIA_VIDEO,
                    CapabilityPolicy.PERM_MEDIA_AUDIO,
                    CapabilityPolicy.PERM_MEDIA_SELECTED,
                ).associateWith { it in granted }
            }

        override fun mediaReadState(kind: String): MediaReadState {
            if (storageApi) {
                return if (CapabilityPolicy.PERM_STORAGE in granted) MediaReadState(true)
                else MediaReadState(false, listOf(CapabilityPolicy.PERM_STORAGE))
            }
            if (mediaGranted) return MediaReadState(true)
            if (mediaMissing.isNotEmpty()) return MediaReadState(false, mediaMissing)
            val missing = CapabilityPolicy.mediaMissingFor(kind) { it in granted }
            return MediaReadState(missing.isEmpty(), missing)
        }

        override suspend fun queryContacts(query: String?, limit: Int): List<ContactRow> {
            if (throwCancellation) throw kotlinx.coroutines.CancellationException("stopped")
            if (throwSecurity) throw SecurityException("permission revoked mid-call")
            lastLimit = limit
            calls += "queryContacts:$query:$limit"
            return contacts.filter { query == null || it.displayName.contains(query, ignoreCase = true) }.take(limit)
        }

        override suspend fun getContact(id: String): ContactRow? {
            calls += "getContact:$id"
            return contacts.find { it.id == id }
        }

        override suspend fun queryEvents(startMs: Long, endMs: Long, limit: Int): List<EventRow> {
            calls += "queryEvents:$startMs:$endMs:$limit"
            return events.take(limit)
        }

        override suspend fun getEvent(id: String): EventRow? {
            calls += "getEvent:$id"
            return events.find { it.id == id }
        }

        override suspend fun queryMedia(kind: String, nameQuery: String?, limit: Int): List<MediaRow> {
            lastLimit = limit
            calls += "queryMedia:$kind:$nameQuery:$limit"
            return media.filter { nameQuery == null || it.displayName.contains(nameQuery, ignoreCase = true) }.take(limit)
        }

        override suspend fun mediaInfo(uri: String): MediaRow? {
            calls += "mediaInfo:$uri"
            return media.find { it.uri == uri }
        }

        override suspend fun openContentUri(uri: String): Boolean {
            calls += "OPEN:$uri"
            return handlerOk
        }

        override suspend fun shareContentUri(uri: String): Boolean {
            calls += "SHARE:$uri"
            return handlerOk
        }

        override suspend fun insertContactDraft(name: String?, phone: String?, email: String?): Boolean {
            calls += "INSERT_CONTACT:$name:$phone:$email"
            return handlerOk
        }

        override suspend fun insertEventDraft(
            title: String,
            description: String?,
            location: String?,
            beginMs: Long?,
            endMs: Long?,
            allDay: Boolean,
        ): Boolean {
            calls += "INSERT_EVENT:$title"
            return handlerOk
        }

        override suspend fun draftSms(to: String?, body: String?): Boolean {
            calls += "SENDTO:smsto:${to ?: ""}"
            return handlerOk
        }

        override suspend fun draftEmail(to: List<String>, subject: String?, body: String?): Boolean {
            calls += "SENDTO:mailto:${to.joinToString(",")}"
            return handlerOk
        }

        override suspend fun dial(number: String?): Boolean {
            calls += if (number != null) "DIAL:tel:$number" else "DIAL:"
            return handlerOk
        }

        override fun isNotificationListenerEnabled(): Boolean = notifEnabled

        override suspend fun openNotificationAccessSettings(): Boolean {
            calls += "OPEN:notification_access"
            return handlerOk
        }

        override suspend fun queryApps(query: String?, limit: Int, includeSystem: Boolean): List<AppRow> {
            calls += "queryApps:$query:$limit:$includeSystem"
            return apps
                .filter { includeSystem || !it.system }
                .filter { query == null || it.label.contains(query, ignoreCase = true) }
                .take(limit)
        }

        override suspend fun appInfo(packageName: String): AppRow? {
            calls += "appInfo:$packageName"
            return apps.find { it.packageName == packageName }
        }

        override suspend fun launchApp(packageName: String): Boolean {
            calls += "LAUNCH:$packageName"
            return handlerOk && packageName in launchable
        }

        override suspend fun openAppSettings(packageName: String): Boolean {
            calls += "APP_SETTINGS:$packageName"
            return handlerOk
        }

        override suspend fun openSpecialAccess(kind: String, packageName: String?): Boolean {
            calls += "SPECIAL:$kind:$packageName"
            return handlerOk
        }

        override suspend fun openSystemSetting(action: String): Boolean {
            calls += "SYSTEM:$action"
            return handlerOk
        }
    }
}
