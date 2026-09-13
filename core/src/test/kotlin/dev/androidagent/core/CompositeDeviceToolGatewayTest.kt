package dev.androidagent.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CompositeDeviceToolGatewayTest {

    @Test fun aToolRoutesToTheFirstBackendThatDeclaresIt() = runBlocking {
        val first = FakeGateway("a11y", tools = listOf("read_ui", "tap"))
        val second = FakeGateway("adb", tools = listOf("tap", "shell"))
        val composite = CompositeDeviceToolGateway(listOf(first, second))

        assertEquals("a11y:tap", composite.invoke("tap", empty()).text)
        assertEquals(listOf("tap"), first.invoked)
        assertTrue(second.invoked.isEmpty())

        assertEquals("adb:shell", composite.invoke("shell", empty()).text)
        assertEquals(listOf("shell"), second.invoked)
    }

    @Test fun aSharedToolNameIsAdvertisedOnceAndKeepsTheFirstBackendsDescription() {
        val first = FakeGateway("a11y", tools = listOf("read_ui", "tap"))
        val second = FakeGateway("adb", tools = listOf("tap", "shell"))
        val composite = CompositeDeviceToolGateway(listOf(first, second))

        assertEquals(listOf("read_ui", "tap", "shell"), composite.definitions.map { it.name })
        assertEquals("a11y tap", composite.definitions.first { it.name == "tap" }.description)
    }

    @Test fun theAdvertisedSurfaceDoesNotShrinkWhenABackendStopsAnswering() = runBlocking {
        // Codex binds the tool list at thread/start and never re-sends it on
        // resume, so a surface that shrank would strand every open thread.
        val a11y = FakeGateway("a11y", tools = listOf("read_ui"))
        val adb = FakeGateway("adb", tools = listOf("shell"))
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))
        val before = composite.definitions.map { it.name }

        a11y.absent += "read_ui"
        assertFalse(composite.invoke("read_ui", empty()).success)
        assertEquals(before, composite.definitions.map { it.name })
    }

    @Test fun anAbsentCapabilityFallsThroughToTheNextBackend() = runBlocking {
        val a11y = FakeGateway("a11y", tools = listOf("key"), absent = mutableSetOf("key"))
        val adb = FakeGateway("adb", tools = listOf("key"))
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        assertEquals("adb:key", composite.invoke("key", empty()).text)
        assertEquals(listOf("key"), adb.invoked)
    }

    @Test fun aRealFailureNeverRetriesOnAnotherBackend() = runBlocking {
        // The action may already have been dispatched, so repeating it
        // elsewhere could commit the same side effect twice.
        val a11y = FakeGateway("a11y", tools = listOf("tap"), broken = mutableSetOf("tap"))
        val adb = FakeGateway("adb", tools = listOf("tap"))
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        val error = runCatching { composite.invoke("tap", empty()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue("second backend must not be invoked", adb.invoked.isEmpty())
    }

    @Test fun anExhaustedChainExplainsEachBackendsReason() = runBlocking {
        val a11y = FakeGateway("a11y", tools = listOf("read_ui"), absent = mutableSetOf("read_ui"), absentAs = "a11y_unavailable")
        val adb = FakeGateway("adb", tools = listOf("read_ui"), absent = mutableSetOf("read_ui"), absentAs = "adb_not_connected")
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        val result = composite.invoke("read_ui", empty())
        assertFalse(result.success)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("backend_unavailable", json["errorType"]!!.jsonPrimitive.content)
        val remedy = json["remedy"]!!.jsonPrimitive.content
        assertTrue(remedy.contains("accessibility service"))
        assertTrue(remedy.contains("Wireless ADB is optional"))
        val reasons = json["reasons"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, reasons.size)
        assertTrue(reasons.any { it.contains("a11y_unavailable") })
        assertTrue(reasons.any { it.contains("adb_not_connected") })
    }

    @Test fun aLiveBackendsOwnRefusalLeadsInsteadOfAMissingAdb() = runBlocking {
        // type_text with no focused field used to fall through to a
        // disconnected ADB and come back as "ADB is not connected".
        val a11y = FakeGateway("a11y", tools = listOf("type_text"), absent = mutableSetOf("type_text"), absentAs = "no_text_focus")
        val adb = FakeGateway("adb", tools = listOf("type_text"), absent = mutableSetOf("type_text"), absentAs = "adb_not_connected")
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        val json = Json.parseToJsonElement(composite.invoke("type_text", empty()).text).jsonObject
        assertEquals("no_text_focus", json["errorType"]!!.jsonPrimitive.content)
        assertEquals("a11y cannot serve type_text", json["message"]!!.jsonPrimitive.content)
        assertTrue(json["remedy"]!!.jsonPrimitive.content.contains("Wireless ADB being off is normal"))
    }

    @Test fun anAdbOnlyToolSaysThatOneToolNeedsAdb() = runBlocking {
        val a11y = FakeGateway("a11y", tools = listOf("tap"))
        val adb = FakeGateway("adb", tools = listOf("shell"), absent = mutableSetOf("shell"), absentAs = "adb_not_connected")
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        val json = Json.parseToJsonElement(composite.invoke("shell", empty()).text).jsonObject
        assertEquals("backend_unavailable", json["errorType"]!!.jsonPrimitive.content)
        val remedy = json["remedy"]!!.jsonPrimitive.content
        assertTrue(remedy.contains("\"shell\" is one of the few tools that need Wireless ADB"))
        assertTrue(remedy.contains("screen control works without it"))
    }

    @Test fun deviceStatusIsAlwaysReadyAndDescribesEveryBackend() {
        val a11y = FakeGateway("a11y", tools = listOf("tap"), ready = emptySet())
        val adb = FakeGateway("adb", tools = listOf("device_status", "shell"), ready = emptySet())
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        assertEquals(setOf("device_status"), composite.readyTools())
        val description = composite.definitions.first { it.name == "device_status" }.description
        assertTrue(description.contains("accessibility service"))
        assertTrue(description.contains("optional Wireless ADB"))
    }

    @Test fun alwaysReadyLocalToolsDoNotCountAsALiveDeviceBackend() {
        val local = object : DeviceToolGateway by FakeGateway("knowledge", tools = listOf("recall_capability")) {
            override fun deviceBackendLive() = false
        }
        val a11y = FakeGateway("a11y", tools = listOf("read_ui"), ready = emptySet())
        val adb = FakeGateway("adb", tools = listOf("shell"), ready = emptySet())

        val capabilities = DeviceCapabilities.of(CompositeDeviceToolGateway(listOf(local, a11y, adb)), AdbStatus())

        assertTrue(capabilities.anyReady)
        assertFalse(capabilities.deviceBackendLive)
    }

    @Test fun anUnknownToolIsReportedRatherThanRouted() = runBlocking {
        val composite = CompositeDeviceToolGateway(listOf(FakeGateway("adb", tools = listOf("tap"))))
        val result = composite.invoke("nope", empty())
        assertFalse(result.success)
        assertEquals("Unknown tool: nope", result.text)
    }

    @Test fun deviceStatusIsAnsweredByTheCompositeAndNamesEveryBackend() = runBlocking {
        val a11y = FakeGateway("a11y", tools = listOf("device_status"))
        val adb = FakeGateway("adb", tools = listOf("device_status"))
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        val result = composite.invoke("device_status", empty())
        assertTrue(result.success)
        assertTrue(result.text.contains("a11y status"))
        assertTrue(result.text.contains("adb status"))
        // Only the composite sees every backend, so it must not delegate.
        assertTrue(a11y.invoked.isEmpty())
        assertTrue(adb.invoked.isEmpty())
    }

    @Test fun aPartiallyArmedRunIsRevokedRatherThanLeftRunning() {
        val ok = FakeGateway("a11y", tools = listOf("tap"))
        val failing = FakeGateway("adb", tools = listOf("shell"), failBeginRun = true)
        val composite = CompositeDeviceToolGateway(listOf(ok, failing))

        val error = runCatching { composite.beginRun("run-1", File("workspace")) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(1, ok.revokes)
        assertEquals(1, failing.revokes)
    }

    @Test fun revokeReachesEveryBackendEvenWhenOneThrows() {
        val throwing = FakeGateway("a11y", tools = listOf("tap"), failRevoke = true)
        val other = FakeGateway("adb", tools = listOf("shell"))
        val composite = CompositeDeviceToolGateway(listOf(throwing, other))

        val error = runCatching { composite.revoke() }.exceptionOrNull()
        assertNotNull(error)
        // Skipping the second revoke would leave a live backend after Stop.
        assertEquals(1, other.revokes)
    }

    @Test fun cancelReachesEveryBackend() = runBlocking {
        val a11y = FakeGateway("a11y", tools = listOf("tap"))
        val adb = FakeGateway("adb", tools = listOf("shell"))
        CompositeDeviceToolGateway(listOf(a11y, adb)).cancel()
        assertEquals(1, a11y.cancels)
        assertEquals(1, adb.cancels)
    }

    @Test fun controlAndCaptureQuestionsGoToTheBackendThatWouldAnswer() {
        val a11y = FakeGateway("a11y", tools = listOf("read_ui"), control = setOf(), capture = setOf())
        val adb = FakeGateway("adb", tools = listOf("shell"), control = setOf("shell"), capture = setOf("shell"))
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        assertFalse(composite.needsControl("read_ui"))
        assertFalse(composite.hidesOverlayDuringCapture("read_ui"))
        assertTrue(composite.needsControl("shell"))
        assertTrue(composite.hidesOverlayDuringCapture("shell"))

        // An unrouted name fails safe as visible control, and never hides the
        // overlay for a capture that is not going to happen.
        assertTrue(composite.needsControl("nope"))
        assertFalse(composite.hidesOverlayDuringCapture("nope"))
    }

    @Test fun aCompositeWithNoBackendsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompositeDeviceToolGateway(emptyList())
        }
    }

    private fun empty(): JsonObject = buildJsonObject { }

    // ---- per-operation availability (issue #44) ----

    @Test fun aToolStaysReadyWhenAnyBackendInItsChainIsLive() {
        // The whole point of the fallback chain: a dead first choice does not
        // make a name unavailable when a later backend can still serve it.
        val a11y = FakeGateway("a11y", tools = listOf("read_ui", "tap", "open_intent"))
        val adb = FakeGateway("adb", tools = listOf("read_ui", "tap", "shell"), ready = emptySet())
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        assertEquals(setOf("read_ui", "tap", "open_intent"), composite.readyTools())
    }

    @Test fun capabilitiesSplitTheAdvertisedSurfaceIntoLiveAndBlocked() {
        val a11y = FakeGateway("a11y", tools = listOf("read_ui", "tap", "open_intent"))
        val adb = FakeGateway("adb", tools = listOf("read_ui", "shell", "install_apk"), ready = emptySet())
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        val capabilities = DeviceCapabilities.of(
            composite,
            AdbStatus(ConnectionPhase.DISCONNECTED, "Not connected"),
        )

        assertTrue(capabilities.anyReady)
        assertTrue(capabilities.deviceBackendLive)
        assertEquals(setOf("read_ui", "tap", "open_intent"), capabilities.ready)
        // ADB-only names, and only those.
        assertEquals(setOf("shell", "install_apk"), capabilities.blocked)
        assertEquals(ConnectionPhase.DISCONNECTED, capabilities.adbStatus.phase)
    }

    @Test fun everyBackendDownLeavesNothingReadyButStillAdvertisesTheSurface() {
        val a11y = FakeGateway("a11y", tools = listOf("read_ui", "tap"), ready = emptySet())
        val adb = FakeGateway("adb", tools = listOf("shell"), ready = emptySet())
        val composite = CompositeDeviceToolGateway(listOf(a11y, adb))

        val capabilities = DeviceCapabilities.of(composite, AdbStatus())

        assertFalse(capabilities.anyReady)
        assertFalse(capabilities.deviceBackendLive)
        assertEquals(setOf("read_ui", "tap", "shell"), capabilities.blocked)
        // The advertised list never shrinks; only the snapshot changes.
        assertEquals(listOf("read_ui", "tap", "shell"), composite.definitions.map { it.name })
    }

    @Test fun aBackendThatThrowsWhileReportingReadinessDoesNotBreakTheSnapshot() {
        val throwing = object : DeviceToolGateway {
            override val definitions = listOf(ToolDefinition("shell", "shell", buildJsonObject { }))
            override fun beginRun(runId: String, workspace: File) = Unit
            override fun revoke() = Unit
            override fun needsControl(name: String) = true
            override suspend fun invoke(name: String, arguments: JsonObject) = ToolResult("x")
            override suspend fun cancel() = Unit
            override fun readyTools(): Set<String> = error("backend is confused")
        }
        val composite = CompositeDeviceToolGateway(
            listOf(FakeGateway("a11y", tools = listOf("read_ui")), throwing),
        )

        // A snapshot is never worth failing a turn over.
        assertEquals(setOf("read_ui"), composite.readyTools())
        assertEquals(setOf("shell"), DeviceCapabilities.of(composite, AdbStatus()).blocked)
    }

    private class FakeGateway(
        private val id: String,
        tools: List<String>,
        /** Null means "ready for everything it declares", the interface default. */
        private val ready: Set<String>? = null,
        val absent: MutableSet<String> = mutableSetOf(),
        /** errorType an absent call refuses with; null means `<id>_absent`. */
        private val absentAs: String? = null,
        private val broken: MutableSet<String> = mutableSetOf(),
        private val control: Set<String> = emptySet(),
        private val capture: Set<String> = emptySet(),
        private val failBeginRun: Boolean = false,
        private val failRevoke: Boolean = false,
    ) : DeviceToolGateway {
        val invoked = mutableListOf<String>()
        var runs = 0
        var revokes = 0
        var cancels = 0

        override val definitions: List<ToolDefinition> =
            tools.map { ToolDefinition(it, "$id $it", buildJsonObject { }) }

        override fun beginRun(runId: String, workspace: File) {
            runs++
            if (failBeginRun) error("$id cannot start")
        }

        override fun revoke() {
            revokes++
            if (failRevoke) error("$id cannot revoke")
        }

        override fun readyTools(): Set<String> = ready ?: super.readyTools()

        override fun needsControl(name: String) = name in control

        override fun hidesOverlayDuringCapture(name: String) = name in capture

        override fun statusLine() = "$id status"

        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
            if (name in absent) throw ToolNotServiceable(absentAs ?: "${id}_absent", "$id cannot serve $name")
            if (name in broken) error("$id failed while running $name")
            invoked += name
            return ToolResult("$id:$name")
        }

        override suspend fun cancel() {
            cancels++
        }
    }
}
