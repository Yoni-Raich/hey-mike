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

import dev.androidagent.core.AdbEndpoint
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.AdbTransport
import dev.androidagent.core.CommandResult
import dev.androidagent.core.ConnectionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Base64

class AndroidDeviceToolsTest {
    @Test fun combinedActionReportsCompletedSideEffectWhenObservationFails() = runBlocking {
        val adb = FakeAdb()
        val visibility = mutableListOf<Boolean>()
        val tools = AndroidDeviceTools(adb, observationVisibility = { visibility += it })
        val workspace = Files.createTempDirectory("combined-action").toFile()
        try {
            tools.beginRun("test", workspace)
            val result = tools.invoke("act_and_observe", buildJsonObject {
                put("action", "tap"); put("arguments", buildJsonObject { put("x", 5); put("y", 6) })
            })
            assertFalse(result.success)
            assertTrue(Json.parseToJsonElement(result.text).jsonObject["actionCompleted"]!!.jsonPrimitive.content == "true")
            assertEquals(listOf(true, false), visibility)
            assertEquals(2, adb.calls)
        } finally { workspace.deleteRecursively() }
    }

    @Test fun screenshotIsStoredInTheSessionForInlinePreview() = runBlocking {
        val workspace = Files.createTempDirectory("screenshot-preview").toFile()
        try {
            val tools = AndroidDeviceTools(FakeAdb())
            tools.beginRun("test", workspace)
            val result = tools.invoke("screenshot", buildJsonObject {})
            assertTrue(result.success)
            val image = java.io.File(result.attachmentPaths.single())
            assertTrue(image.canonicalPath.startsWith(workspace.canonicalPath))
            assertArrayEquals(Base64.getDecoder().decode(result.imageBase64), image.readBytes())
        } finally { workspace.deleteRecursively() }
    }


    @Test fun quotingEscapesSingleQuotesAndSpaces() {
        assertEquals("'hello'", AndroidDeviceTools.shellQuote("hello"))
        assertEquals("''", AndroidDeviceTools.shellQuote(""))
        assertEquals("'a'\\''b'", AndroidDeviceTools.shellQuote("a'b"))
        assertEquals("'a b; rm -rf /'", AndroidDeviceTools.shellQuote("a b; rm -rf /"))
        // Quoted payload stays a single shell token: no raw injection.
        assertTrue(AndroidDeviceTools.shellQuote("x\$(whoami)").startsWith("'"))
        assertTrue(AndroidDeviceTools.shellQuote("x\$(whoami)").endsWith("'"))
    }

    @Test fun invokeBeforeBeginRunIsDenied() {
        val adb = FakeAdb()
        val tools = AndroidDeviceTools(adb)
        assertTrue(tools.definitions.any { it.name == "act_and_observe" })
        try {
            runBlocking { tools.invoke("device_status", buildJsonObject {}) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("stopped", ignoreCase = true))
        }
        assertEquals(0, adb.calls)
    }

    @Test fun revokePreventsSubsequentDispatch() {
        val adb = FakeAdb()
        val tools = AndroidDeviceTools(adb)
        val ws = Files.createTempDirectory("ws").toFile()
        tools.beginRun("r1", ws)
        runBlocking { tools.invoke("device_status", buildJsonObject {}) }
        assertEquals(0, adb.calls) // device_status reads status flow, no shell call
        tools.revoke()
        try {
            runBlocking { tools.invoke("tap", buildJsonObject { put("x", 10); put("y", 20) }) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("stopped", ignoreCase = true))
        }
        assertEquals(0, adb.calls)
        // A new run re-arms dispatch.
        tools.beginRun("r2", ws)
        runBlocking { tools.invoke("tap", buildJsonObject { put("x", 10); put("y", 20) }) }
        assertEquals(1, adb.calls)
        assertTrue(adb.lastCommand!!.startsWith("input tap 10 20"))
    }

    @Test fun workspaceTraversalDenied() {
        val adb = FakeAdb()
        val tools = AndroidDeviceTools(adb)
        val ws = Files.createTempDirectory("ws").toFile()
        tools.beginRun("r1", ws)
        for (evil in listOf("../evil.txt", "/abs.txt", "a/../../evil.txt", "C:\\evil.txt", "sub\\file.txt")) {
            try {
                runBlocking {
                    tools.invoke(
                        "pull_file",
                        buildJsonObject { put("remotePath", "/sdcard/f.txt"); put("localName", evil) },
                    )
                }
                fail("expected rejection for $evil")
            } catch (e: IllegalArgumentException) {
                // absolute/traversal/backslash rejections surface as require() failures
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("traversal", ignoreCase = true))
            }
        }
    }

    @Test fun unicodeTextFailsHonestly() {
        try {
            AndroidDeviceTools.requireAdbInputText("hello שלום")
            fail("expected Unicode rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("IME", ignoreCase = true))
        }
        // ASCII passes.
        AndroidDeviceTools.requireAdbInputText("Meet at 6:00 PM!")
    }

    @Test fun imePayloadPreservesHebrewAndBroadcastDoesNotExposeText() {
        val text = "שלום עולם 😀 O'Reilly"
        val payload = AndroidDeviceTools.encodeImePayload(text)
        assertEquals(text, String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8))

        val command = AndroidDeviceTools.buildImeBroadcastCommand(
            "dev.androidagent.app/dev.androidagent.app.ime.AgentInputMethodService",
            payload,
        )
        assertTrue(command.contains("dev.androidagent.app.INPUT_TEXT"))
        assertTrue(command.contains("payload_base64"))
        assertTrue(command.contains(AndroidDeviceTools.shellQuote(payload)))
        assertFalse(command.contains("--receiver-permission"))
        assertFalse(command.contains(text))
        assertFalse(command.contains("O'Reilly"))
    }

    @Test fun imeSelectionNormalizesAndroidComponentNames() {
        val component = "dev.androidagent.app/dev.androidagent.app.ime.AgentInputMethodService"
        assertTrue(AndroidDeviceTools.imeSelectionMatches(component, component))
        assertTrue(AndroidDeviceTools.imeSelectionMatches(
            "dev.androidagent.app/.ime.AgentInputMethodService",
            component,
        ))
        assertFalse(AndroidDeviceTools.imeSelectionMatches("com.android.inputmethod/.LatinIME", component))
    }

    @Test fun imeBroadcastOnlyConfirmsARealCommitResult() {
        assertTrue(AndroidDeviceTools.imeBroadcastCommitted("Broadcast completed: result=1 data=ok"))
        assertFalse(AndroidDeviceTools.imeBroadcastCommitted("Broadcast completed: result=4 data=error"))
        assertFalse(AndroidDeviceTools.imeBroadcastCommitted("Broadcast completed"))
    }

    @Test fun imeWaitsForTheEditorConnectionBeforeSendingUnicode() = runBlocking {
        val component = "dev.androidagent.app/dev.androidagent.app.ime.AgentInputMethodService"
        val adb = ImeFakeAdb(component, readyAfterDump = 2, commitResult = 1)
        val tools = AndroidDeviceTools(adb, component)
        tools.beginRun("ime", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("type_text", buildJsonObject { put("text", "שלום") })

        assertTrue(result.success)
        assertTrue(adb.commands.any { it.contains("INPUT_PROBE") })
        assertTrue(adb.commands.any { it.startsWith("am broadcast") })
        assertEquals(1, adb.commands.count { it.contains("INPUT_TEXT") })
        assertTrue(adb.commands.indexOfFirst { it.contains("INPUT_TEXT") } > adb.commands.indexOfFirst { it.contains("INPUT_PROBE") })
    }

    @Test fun imeCommitFailureReturnsGuidanceAndNeverClaimsSuccess() = runBlocking {
        val component = "dev.androidagent.app/dev.androidagent.app.ime.AgentInputMethodService"
        val adb = ImeFakeAdb(component, readyAfterDump = 1, commitResult = 4)
        val tools = AndroidDeviceTools(adb, component)
        tools.beginRun("ime", Files.createTempDirectory("ws").toFile())

        try {
            tools.invoke("type_text", buildJsonObject { put("text", "שלום") })
            fail("expected the IME commit to be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("No Enter key was sent", ignoreCase = true))
            assertTrue(error.message!!.contains("editor", ignoreCase = true))
        }
        assertTrue(adb.commands.count { it.startsWith("am broadcast") } >= 2)
    }

    @Test fun ambiguousCommitIsNeverRetriedOrSubmitted() = runBlocking {
        val component = "dev.androidagent.app/.ime.AgentInputMethodService"
        val adb = ImeFakeAdb(component, 1, 5)
        val tools = AndroidDeviceTools(adb, component)
        tools.beginRun("ime", Files.createTempDirectory("ws").toFile())
        try {
            tools.invoke("type_text", buildJsonObject { put("text", "שלום"); put("submit", true) })
            fail("expected ambiguous commit failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, adb.commands.count { it.contains("INPUT_TEXT") })
        assertFalse(adb.commands.any { it == "input keyevent 66" })
        assertTrue(adb.commands.last().startsWith("ime set"))
    }

    @Test fun coordinatesAndKeysValidated() {
        val tools = AndroidDeviceTools(FakeAdb())
        val ws = Files.createTempDirectory("ws").toFile()
        tools.beginRun("r1", ws)
        try {
            runBlocking { tools.invoke("tap", buildJsonObject { put("x", -5); put("y", 10) }) }
            fail("expected coordinate rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("x"))
        }
        try {
            runBlocking { tools.invoke("key", buildJsonObject { put("keycode", "NOT_A_KEY") }) }
            fail("expected key rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unknown key"))
        }
        assertEquals(66, AndroidDeviceTools.resolveKeycode("ENTER"))
        assertEquals(4, AndroidDeviceTools.resolveKeycode("keycode_back"))
        assertEquals(3, AndroidDeviceTools.resolveKeycode("3"))
    }

    @Test fun controlMappingKeepsReadsQuietAndShellVisible() {
        val tools = AndroidDeviceTools(FakeAdb())
        assertFalse(tools.needsControl("device_status"))
        assertFalse(tools.needsControl("read_ui"))
        assertFalse(tools.needsControl("screenshot"))
        assertFalse(tools.needsControl("pull_file"))
        for (name in listOf("tap", "swipe", "type_text", "key", "open_app", "shell", "push_file", "install_apk")) {
            assertTrue("$name must need control", tools.needsControl(name))
        }
        assertTrue(tools.needsControl("unknown_future_tool"))
    }

    @Test fun noToolIsReadyWhileTheAdbTransportIsDown() {
        // Everything this backend does needs the transport, so a disconnected
        // ADB serves nothing. The advertised surface is untouched.
        val tools = AndroidDeviceTools(FakeAdb(ConnectionPhase.DISCONNECTED))
        assertTrue(tools.readyTools().isEmpty())
        assertTrue(tools.definitions.isNotEmpty())
    }

    @Test fun aDisconnectedTransportRefusesTypedBeforeTouchingTheDevice() {
        // A bare "ADB is not connected" IOException escaped the composite and
        // told the model the task needed ADB. The typed refusal lets the
        // accessibility backend's own reason lead instead.
        val adb = FakeAdb(ConnectionPhase.DISCONNECTED)
        val tools = AndroidDeviceTools(adb)
        val ws = Files.createTempDirectory("ws").toFile()
        try {
            tools.beginRun("r1", ws)
            val refusal = runCatching {
                runBlocking { tools.invoke("type_text", buildJsonObject { put("text", "hi") }) }
            }.exceptionOrNull()
            assertTrue(refusal is dev.androidagent.core.ToolNotServiceable)
            assertEquals("adb_not_connected", (refusal as dev.androidagent.core.ToolNotServiceable).errorType)
            assertEquals(0, adb.calls)
            // device_status still answers, since it only reads the status flow.
            assertTrue(runBlocking { tools.invoke("device_status", buildJsonObject {}) }.success)
        } finally { ws.deleteRecursively() }
    }

    @Test fun theStatusLineCallsAdbOptional() {
        val line = AndroidDeviceTools(FakeAdb(ConnectionPhase.DISCONNECTED)).statusLine()!!
        assertTrue(line.startsWith("Wireless ADB (optional): disconnected"))
    }

    @Test fun everyAdvertisedToolIsReadyOnceAdbIsConnected() {
        val tools = AndroidDeviceTools(FakeAdb())
        assertEquals(tools.definitions.map { it.name }.toSet(), tools.readyTools())
    }

    @Test fun shellInjectionSafelyQuotedInOpenApp() {
        val adb = FakeAdb()
        val tools = AndroidDeviceTools(adb)
        val ws = Files.createTempDirectory("ws").toFile()
        tools.beginRun("r1", ws)
        try {
            runBlocking {
                tools.invoke("open_app", buildJsonObject { put("package", "com.evil; rm -rf /") })
            }
            fail("expected package rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("package", ignoreCase = true))
        }
        assertEquals(0, adb.calls)
    }

    @Test fun readUiReturnsCompactSemanticNodesAndClickableAncestor() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(SAMPLE_UI_XML, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject {})

        assertTrue(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("com.whatsapp", json["activePackage"]!!.jsonPrimitive.content)
        assertEquals("file", json["source"]!!.jsonPrimitive.content)
        assertEquals("true", json["stable"]!!.jsonPrimitive.content)
        val label = json["nodes"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["text"]?.jsonPrimitive?.content == "Send" }
        assertEquals("n0", label["clickableAncestor"]!!.jsonObject["nodeId"]!!.jsonPrimitive.content)
        assertEquals(listOf("900", "2100", "1080", "2300"),
            label["clickableAncestor"]!!.jsonObject["bounds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, adb.commands.size)
        assertTrue(adb.timeouts.single() in 1..AndroidDeviceTools.READ_UI_DEFAULT_TIMEOUT_MS)
    }

    @Test fun readUiFiltersTheParsedScreenWhenTheModelAsksAFocusedQuestion() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(SAMPLE_UI_XML, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject { put("text", "send") })

        assertTrue(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(2, json["totalNodes"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["matchedNodes"]!!.jsonPrimitive.content.toInt())
        assertEquals("send", json["query"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("Send"),
            json["nodes"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content },
        )
        // Still one dump: a query narrows the reply, never the read.
        assertEquals(1, adb.commands.size)
    }

    @Test fun readUiReturnsASubtreeWhenGivenARootNodeIdFromTheXmlParser() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(SAMPLE_UI_XML, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject { put("rootNodeId", "n0") })

        assertTrue(result.success)
        val ids = Json.parseToJsonElement(result.text).jsonObject["nodes"]!!.jsonArray
            .map { it.jsonObject["nodeId"]!!.jsonPrimitive.content }
        // The container plus the label under it; the unlabelled sibling was
        // never emitted, so it is not part of the flat subtree either.
        assertEquals(listOf("n0", "n1"), ids)
    }

    @Test fun readUiRejectsARootNodeIdThatIsNotOnScreen() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(SAMPLE_UI_XML, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject { put("rootNodeId", "n99") })

        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("ui_unknown_node", json["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun readUiAdvertisesTheFocusedQueryArguments() {
        val schema = AndroidDeviceTools(FakeAdb()).definitions.first { it.name == "read_ui" }
        val properties = schema.inputSchema["properties"]!!.jsonObject
        for (key in listOf(
            "text", "resourceId", "class", "package", "rootNodeId",
            "clickableOnly", "scrollableOnly", "offset", "maxNodes", "maxChars",
        )) {
            assertTrue("read_ui must advertise $key", properties.containsKey(key))
        }
        assertTrue(schema.description.contains("nextOffset"))
    }

    @Test fun uiDumpCommandRestoresRotationLockThawedByUiautomator() {
        val command = AndroidDeviceTools.uiDumpCommand("/sdcard/window_dump.xml")

        // The dump itself is unchanged and still the source of the exit code.
        assertTrue(command.contains("uiautomator dump --compressed '/sdcard/window_dump.xml'"))
        assertTrue(command.contains("cat '/sdcard/window_dump.xml'"))
        assertTrue(command.contains("__rc=\$?"))
        assertTrue(command.endsWith("exit \$__rc"))
        // Both rotation settings are snapshotted before uiautomator can thaw them.
        val dumpAt = command.indexOf("uiautomator dump")
        assertTrue(command.indexOf("__ar=\$(settings get system accelerometer_rotation)") in 0 until dumpAt)
        assertTrue(command.indexOf("__ur=\$(settings get system user_rotation)") in 0 until dumpAt)
        // The lock is only put back when it was actually held and then lost.
        assertTrue(command.contains("if [ \"\$__ar\" = 0 ] && "))
        assertTrue(command.contains("[ \"\$(settings get system accelerometer_rotation)\" != 0 ]"))
        assertTrue(command.contains("settings put system accelerometer_rotation 0"))
        // A missing or invalid user_rotation is never written back verbatim.
        assertTrue(command.contains("case \"\$__ur\" in 0|1|2|3) settings put system user_rotation \"\$__ur\";; esac"))
    }

    @Test fun readUiStillParsesHierarchyThroughRotationGuard() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(SAMPLE_UI_XML, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject {})

        assertTrue(result.success)
        // Still a single round trip: the guard rides along in the same shell command.
        assertEquals(1, adb.commands.size)
        assertEquals(AndroidDeviceTools.uiDumpCommand(AndroidDeviceTools.UI_DUMP_PATH), adb.commands.single())
    }

    @Test fun readUiIdleFailureIsTypedAndNeverFallsBack() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult("ERROR: could not get idle state.", 1)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject {})

        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("ui_idle_failure", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("false", json["stable"]!!.jsonPrimitive.content)
        assertNotNull(json["elapsedMs"])
        assertEquals(1, adb.commands.size)
        assertTrue(adb.commands.single().contains(AndroidDeviceTools.UI_DUMP_PATH))
    }

    /**
     * Regression guard for v0.3.4: the `/dev/tty` shortcut reported exit 0 with
     * no hierarchy on the supported device, and the guard that skipped the file
     * dump after a "slow" shortcut left read_ui with no working path at all.
     * One staged dump per observation, and never /dev/tty.
     */
    @Test fun readUiIssuesOneStagedDumpAndNeverUsesDevTty() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(SAMPLE_UI_XML, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject {})

        assertTrue(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("file", json["source"]!!.jsonPrimitive.content)
        assertEquals(1, adb.commands.size)
        assertTrue(adb.commands.single().contains(AndroidDeviceTools.UI_DUMP_PATH))
        assertFalse(adb.commands.any { it.contains("/dev/tty") })
        assertTrue(adb.timeouts.single() <= AndroidDeviceTools.READ_UI_DEFAULT_TIMEOUT_MS)
    }

    @Test fun readUiReportsDumpFailureWhenNoHierarchyIsReturned() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(
            CommandResult("UI hierchary dumped to: /sdcard/window_dump.xml", 0),
        ))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject {})

        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("ui_dump_failure", json["errorType"]!!.jsonPrimitive.content)
        assertEquals(1, adb.commands.size)
    }

    @Test fun readUiSuppressesAnIdenticalConsecutiveObservation() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(
            CommandResult(SAMPLE_UI_XML, 0),
            CommandResult(SAMPLE_UI_XML, 0),
        ))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val first = Json.parseToJsonElement(tools.invoke("read_ui", buildJsonObject {}).text).jsonObject
        val second = tools.invoke("read_ui", buildJsonObject {})

        assertTrue(second.success)
        val json = Json.parseToJsonElement(second.text).jsonObject
        assertEquals("true", json["unchanged"]!!.jsonPrimitive.content)
        assertEquals(first["revision"]!!.jsonPrimitive.content,
            json["unchangedSinceRevision"]!!.jsonPrimitive.content)
        // The whole point: the node list is not resent.
        assertNull(json["nodes"])
        assertTrue(second.text.length < 400)
        // The dump still runs, so a changed screen is never missed.
        assertEquals(2, adb.commands.size)
    }

    @Test fun readUiResendsFullNodesWhenScreenChanges() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(
            CommandResult(SAMPLE_UI_XML, 0),
            CommandResult(SAMPLE_UI_XML.replace("Send", "Resend"), 0),
        ))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        tools.invoke("read_ui", buildJsonObject {})
        val json = Json.parseToJsonElement(tools.invoke("read_ui", buildJsonObject {}).text).jsonObject

        assertNull(json["unchanged"])
        assertTrue(json["nodes"]!!.jsonArray.isNotEmpty())
    }

    @Test fun readUiForceResendsNodesForAnIdenticalScreen() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(
            CommandResult(SAMPLE_UI_XML, 0),
            CommandResult(SAMPLE_UI_XML, 0),
        ))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        tools.invoke("read_ui", buildJsonObject {})
        val forced = tools.invoke("read_ui", buildJsonObject { put("force", true) })

        val json = Json.parseToJsonElement(forced.text).jsonObject
        assertNull(json["unchanged"])
        assertTrue(json["nodes"]!!.jsonArray.isNotEmpty())
    }

    @Test fun readUiResendsFullNodesAfterAFailedObservation() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(
            CommandResult(SAMPLE_UI_XML, 0),
            CommandResult("ERROR: could not get idle state.", 1),
            CommandResult(SAMPLE_UI_XML, 0),
        ))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        tools.invoke("read_ui", buildJsonObject {})
        assertFalse(tools.invoke("read_ui", buildJsonObject {}).success)
        val json = Json.parseToJsonElement(tools.invoke("read_ui", buildJsonObject {}).text).jsonObject

        // The screen was unknown while the dump failed, so no diff is claimed.
        assertNull(json["unchanged"])
        assertTrue(json["nodes"]!!.jsonArray.isNotEmpty())
    }

    @Test fun readUiRawModeIsNeverSuppressed() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(
            CommandResult(SAMPLE_UI_XML, 0),
            CommandResult(SAMPLE_UI_XML, 0),
        ))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        tools.invoke("read_ui", buildJsonObject {})
        val raw = tools.invoke("read_ui", buildJsonObject { put("raw", true) })

        assertTrue(raw.text.startsWith("<hierarchy"))
    }

    @Test fun aSwitchInTheXmlDumpCarriesItsOnOffState() = runBlocking {
        // Both backends have to answer "is the toggle on" the same way, or a
        // workflow's verification means something different on each of them.
        val xml = """<hierarchy rotation="0"><node index="0" text="Wireless debugging" resource-id="com.android.settings:id/switch_widget" class="android.widget.Switch" package="com.android.settings" content-desc="" checkable="true" checked="true" clickable="true" enabled="true" scrollable="false" focused="false" bounds="[0,100][1080,200]"/></hierarchy>"""
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(xml, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject {})

        assertTrue(result.success)
        assertTrue(result.text, result.text.contains("\"checkable\":true"))
        assertTrue(result.text, result.text.contains("\"checked\":true"))
    }

    @Test fun readUiRawModePreservesXmlCompatibility() = runBlocking {
        val adb = ScriptedUiAdb(mutableListOf(CommandResult(SAMPLE_UI_XML, 0)))
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject { put("raw", true) })

        assertTrue(result.success)
        assertTrue(result.text.startsWith("<hierarchy"))
        assertTrue(result.text.endsWith("</hierarchy>"))
    }

    @Test fun readUiTimeoutIsTypedAndNeverFallsBack() = runBlocking {
        val adb = HangingUiAdb()
        val tools = AndroidDeviceTools(adb)
        tools.beginRun("ui", Files.createTempDirectory("ws").toFile())

        val result = tools.invoke("read_ui", buildJsonObject { put("timeoutMs", 10) })

        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("ui_timeout", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("false", json["stable"]!!.jsonPrimitive.content)
        assertEquals(1, adb.calls)
    }

    private class FakeAdb(phase: ConnectionPhase = ConnectionPhase.CONNECTED) : AdbTransport {
        private val state = MutableStateFlow(AdbStatus(phase, "ok", 1))
        override val status: StateFlow<AdbStatus> = state.asStateFlow()
        var calls = 0
        var lastCommand: String? = null
        override suspend fun discover(): List<AdbEndpoint> = emptyList()
        override suspend fun pair(port: Int, code: String) = Unit
        override suspend fun connect(port: Int) = Unit
        override suspend fun execute(command: String, timeoutMs: Long): CommandResult {
            calls++
            lastCommand = command
            return CommandResult("ok", 0)
        }
        override suspend fun executeBytes(command: String, timeoutMs: Long): ByteArray {
            calls++
            lastCommand = command
            // Minimal valid PNG header so screenshot validation passes.
            return byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01,
            )
        }
        override suspend fun cancelActive() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun forgetPairing() = Unit
    }

    private class ImeFakeAdb(
        private val component: String,
        private val readyAfterDump: Int,
        private val commitResult: Int,
    ) : AdbTransport {
        private val state = MutableStateFlow(AdbStatus(ConnectionPhase.CONNECTED, "ok", 1))
        override val status: StateFlow<AdbStatus> = state.asStateFlow()
        val commands = mutableListOf<String>()
        private var settingsReads = 0
        private var dumpReads = 0

        override suspend fun discover(): List<AdbEndpoint> = emptyList()
        override suspend fun pair(port: Int, code: String) = Unit
        override suspend fun connect(port: Int) = Unit
        override suspend fun execute(command: String, timeoutMs: Long): CommandResult {
            commands += command
            return when {
                command == "settings get secure default_input_method" -> {
                    settingsReads++
                    CommandResult(if (settingsReads == 1) "com.android.inputmethod/.LatinIME" else "$component\n", 0)
                }
                command.contains("INPUT_PROBE") -> {
                    dumpReads++
                    CommandResult("Broadcast completed: result=${if (dumpReads >= readyAfterDump) 1 else 4}", 0)
                }
                command.startsWith("am broadcast") -> CommandResult("Broadcast completed: result=$commitResult data=ok", 0)
                else -> CommandResult("ok", 0)
            }
        }
        override suspend fun executeBytes(command: String, timeoutMs: Long): ByteArray = ByteArray(0)
        override suspend fun cancelActive() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun forgetPairing() = Unit
    }

    private class ScriptedUiAdb(
        private val results: MutableList<CommandResult>,
    ) : AdbTransport {
        private val state = MutableStateFlow(AdbStatus(ConnectionPhase.CONNECTED, "ok", 1))
        override val status: StateFlow<AdbStatus> = state.asStateFlow()
        val commands = mutableListOf<String>()
        val timeouts = mutableListOf<Long>()

        override suspend fun discover(): List<AdbEndpoint> = emptyList()
        override suspend fun pair(port: Int, code: String) = Unit
        override suspend fun connect(port: Int) = Unit
        override suspend fun execute(command: String, timeoutMs: Long): CommandResult {
            commands += command
            timeouts += timeoutMs
            return results.removeAt(0)
        }
        override suspend fun executeBytes(command: String, timeoutMs: Long): ByteArray = ByteArray(0)
        override suspend fun cancelActive() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun forgetPairing() = Unit
    }

    private class HangingUiAdb : AdbTransport {
        private val state = MutableStateFlow(AdbStatus(ConnectionPhase.CONNECTED, "ok", 1))
        override val status: StateFlow<AdbStatus> = state.asStateFlow()
        var calls = 0

        override suspend fun discover(): List<AdbEndpoint> = emptyList()
        override suspend fun pair(port: Int, code: String) = Unit
        override suspend fun connect(port: Int) = Unit
        override suspend fun execute(command: String, timeoutMs: Long): CommandResult {
            calls++
            return suspendCancellableCoroutine { }
        }
        override suspend fun executeBytes(command: String, timeoutMs: Long): ByteArray = ByteArray(0)
        override suspend fun cancelActive() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun forgetPairing() = Unit
    }

    companion object {
        private const val SAMPLE_UI_XML = """<hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.LinearLayout" package="com.whatsapp" content-desc="" clickable="true" enabled="true" scrollable="false" focused="false" bounds="[900,2100][1080,2300]"><node index="0" text="Send" resource-id="com.whatsapp:id/send" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" scrollable="false" focused="false" bounds="[920,2120][1060,2280]"/><node index="1" text="" resource-id="" class="android.view.View" package="com.whatsapp" content-desc="" clickable="false" enabled="true" scrollable="false" focused="false" bounds="[0,0][1,1]"/></node></hierarchy>"""
    }
}
