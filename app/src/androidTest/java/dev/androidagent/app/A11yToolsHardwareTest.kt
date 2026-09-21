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

package dev.androidagent.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.a11y.A11yDeviceTools
import dev.androidagent.a11y.A11yServiceHandle
import dev.androidagent.core.KnowledgeStore
import dev.androidagent.core.ObservationState
import dev.androidagent.core.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the accessibility tools against the real device.
 *
 * The JVM suite covers the serialiser, the routing and the policies; none of
 * it can tell whether `takeScreenshot` returns a decodable PNG, whether the
 * `HardwareBuffer` handling survives a real capture, or whether our own
 * process sees the same intent handlers the shell does. Those are the claims
 * that were recorded as unverified, and they are only answerable here.
 *
 * Requires the accessibility service to be enabled by hand. Each test skips
 * rather than fails when it is not, because a red suite on a device where the
 * user simply has not granted it says nothing useful.
 */
@RunWith(AndroidJUnit4::class)
class A11yToolsHardwareTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var tools: A11yDeviceTools

    @Before fun setUp() {
        workspace = File(context.cacheDir, "a11y-hw-test").apply {
            deleteRecursively()
            mkdirs()
        }
        tools = A11yDeviceTools(context, ObservationState())
        tools.beginRun("hardware-test", workspace)
    }

    private fun requireService() {
        // Generous on purpose. `am instrument` force-stops the package to make
        // its own process, which tears down an already-bound accessibility
        // service; the system then rebinds it into the new process. On a device
        // with aggressive power management that round trip takes tens of
        // seconds, and a short wait reports "not enabled" for what is really
        // "not rebound yet".
        val connected = runBlocking { A11yServiceHandle.await(45_000) } != null
        assumeTrue(
            "Accessibility service is not connected. Enable Hey Mike in " +
                "Settings > Accessibility and re-run.",
            connected,
        )
    }

    private fun invoke(name: String, arguments: JsonObject = buildJsonObject { }): ToolResult =
        runBlocking { tools.invoke(name, arguments) }

    @Test fun theScreenshotToolReturnsADecodablePng() {
        requireService()
        val result = invoke("screenshot")
        assertTrue("screenshot failed: ${result.text}", result.success)

        val path = result.attachmentPaths.single()
        val bytes = File(path).readBytes()
        assertTrue("file is empty", bytes.isNotEmpty())
        // Magic bytes rather than a decode, so a truncated write is caught as
        // a truncated write instead of as a decoder error.
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])

        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("PNG did not decode", decoded)
        assertTrue("implausible width ${decoded.width}", decoded.width > 100)
        assertTrue("implausible height ${decoded.height}", decoded.height > 100)
        assertNotNull("imageBase64 missing", result.imageBase64)
    }

    @Test fun repeatedCapturesDoNotLeakOrCrash() {
        requireService()
        // The HardwareBuffer is the thing at risk here. A leak would not throw,
        // so this asserts the weaker but checkable claim: several captures in a
        // row keep succeeding or degrade to a typed refusal, never a crash.
        var succeeded = 0
        var refused = 0
        repeat(6) {
            try {
                if (invoke("screenshot").success) succeeded++
            } catch (absent: dev.androidagent.core.ToolNotServiceable) {
                // Rate limiting is expected and is exactly the typed fallthrough.
                assertTrue(absent.errorType.startsWith("screenshot_"))
                refused++
            }
        }
        assertEquals("every attempt should either succeed or be typed", 6, succeeded + refused)
        assertTrue("no capture succeeded at all", succeeded > 0)
    }

    @Test fun readUiReportsTheAccessibilitySourceAndExcludesOurOwnUi() {
        requireService()
        val result = invoke("read_ui", buildJsonObject { put("force", true) })
        assertTrue("read_ui failed: ${result.text}", result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("accessibility", json["source"]!!.jsonPrimitive.content)

        val nodes = json["nodes"]?.jsonArray.orEmpty()
        val ours = nodes.map { it.jsonObject }
            .filter { it["packageName"]?.jsonPrimitive?.content == context.packageName }
        assertTrue("our own UI leaked into the observation: $ours", ours.isEmpty())
    }

    @Test fun ourProcessSeesTheSameIntentHandlersTheShellDoes() {
        requireService()
        // The gap flagged in PROGRESS.md: `cmd package resolve-activity` runs as
        // shell, which has package visibility this app only has through
        // QUERY_ALL_PACKAGES. This is the check that the permission works from
        // inside our own process.
        val result = invoke(
            "resolve_intent",
            buildJsonObject { put("uri", "waze://?ll=32.08,34.78&navigate=yes") },
        )
        assertTrue("resolve_intent failed: ${result.text}", result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assumeTrue("Waze is not installed on this device", json["resolves"]!!.jsonPrimitive.booleanOrNull == true)
        val packages = json["handlers"]!!.jsonArray.map { it.jsonObject["package"]!!.jsonPrimitive.content }
        assertTrue("expected com.waze in $packages", packages.contains("com.waze"))
    }

    @Test fun resolveIntentRefusesABlockedSchemeOnDevice() {
        requireService()
        val result = invoke(
            "resolve_intent",
            buildJsonObject { put("uri", "content://media/external/images/media/1") },
        )
        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("scheme_blocked", json["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun sendingAMessageAsksBeforeItLaunches() {
        requireService()
        // Nothing is launched by this test: the point is that the gate fires
        // before startActivity rather than after it.
        val result = invoke(
            "open_intent",
            buildJsonObject { put("uri", "smsto:+972500000000?body=test") },
        )
        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("approval_unavailable", json["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun theKnowledgeStoreRoundTripsOnDeviceStorage() {
        val root = File(context.filesDir, "hw-test-knowledge")
        root.deleteRecursively()
        val store = KnowledgeStore(root)
        store.upsert(
            KnowledgeStore.Record(
                packageName = "com.whatsapp",
                screen = "chat list",
                selector = "com.whatsapp:id/search",
                does = "opens search",
            ),
        )
        // A separate instance over the same directory is what a later chat is.
        val recalled = KnowledgeStore(root).read("com.whatsapp")
        assertEquals(1, recalled.size)
        assertEquals("com.whatsapp:id/search", recalled.single().selector)
        assertFalse(store.isStale(recalled.single()))
        root.deleteRecursively()
    }

    @Test fun aStoppedRunRefusesEveryTool() {
        requireService()
        tools.revoke()
        val error = runCatching { invoke("read_ui") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("Run stopped"))
    }
}
