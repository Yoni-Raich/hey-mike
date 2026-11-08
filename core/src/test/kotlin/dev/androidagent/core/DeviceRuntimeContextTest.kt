package dev.androidagent.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapshot both engines send; the golden cases pin its exact text. */
class DeviceRuntimeContextTest {
    private val now = ZonedDateTime.of(2026, 9, 14, 2, 30, 0, 0, ZoneId.of("Asia/Jerusalem"))
    private val head = "[Trusted Android Agent runtime context]\n" +
        "This snapshot replaces every older snapshot, and any earlier statement in this chat that device tools were unavailable.\n" +
        "Phone local time: Monday, 14 September 2026, 02:30 (Asia/Jerusalem). A task that names a date means that date, not today.\n"

    private val golden: List<Pair<DeviceCapabilities, String>> = listOf(
        DeviceCapabilities(
            adbStatus = AdbStatus(ConnectionPhase.DISCONNECTED, "Not connected"),
            ready = setOf("tap", "read_ui"),
            blocked = setOf("shell"),
            backendStatus = "Accessibility: connected",
        ) to head + "Backends: Accessibility: connected\n" +
            "Device tools you can call now: read_ui, tap\n" +
            "Tools that need a backend that is off: shell\n" +
            "Call anything in the first list normally. Wireless ADB is an optional advanced extra and being off is normal. " +
            "Only if the task truly needs a tool from the second list, name that exact tool and what it needs. " +
            "Never tell the user a task needs ADB when the first list covers it.",
        DeviceCapabilities(adbStatus = AdbStatus(ConnectionPhase.CONNECTING, "Connecting")) to head +
            "Wireless ADB (optional): connecting\n" +
            "Device tools you can call now: none\n" +
            "No device backend is live yet. Screen control needs only the Hey Mike accessibility service: " +
            "ask the user to enable it in Settings > Accessibility. Wireless ADB is still connecting, but it is optional.",
        DeviceCapabilities(
            adbStatus = AdbStatus(ConnectionPhase.DISCONNECTED, "Not connected"),
            ready = setOf("device_status"),
            blocked = setOf("read_ui"),
            deviceBackendLive = false,
        ) to head + "Wireless ADB (optional): disconnected\n" +
            "Device tools you can call now: device_status\n" +
            "Tools that need a backend that is off: read_ui\n" +
            "No device backend is live, so you cannot read or operate the screen right now. " +
            "Anything in the first list still works — opening an app or a deep link needs no backend. " +
            "For screen control, ask the user to enable the Hey Mike accessibility service in Settings > Accessibility. " +
            "Wireless ADB is an optional advanced extra.",
        DeviceCapabilities(adbStatus = AdbStatus(ConnectionPhase.CONNECTED, "Connected", 1), ready = setOf("tap")) to head +
            "Wireless ADB (optional): connected\n" +
            "Device tools you can call now: tap\n" +
            "Use the supplied device tools when the task needs device access.",
        DeviceCapabilities(
            adbStatus = AdbStatus(ConnectionPhase.CONNECTED, "Connected", 1),
            ready = setOf("shell"),
            blocked = setOf("tap_node"),
        ) to head + "Wireless ADB (optional): connected\n" +
            "Device tools you can call now: shell\n" +
            "Tools that need a backend that is off: tap_node\n" +
            "Call anything in the first list normally. The tools in the second list need the Hey Mike accessibility " +
            "service; if the task needs one, ask the user to enable it in Settings > Accessibility. " +
            "Do not treat the whole device as unavailable.",
    )

    @Test fun everyBranchKeepsItsExactText() {
        for ((caps, expected) in golden) assertEquals(expected, DeviceRuntimeContext.render(caps, now))
    }

    @Test fun aDisconnectedAdbNeverBlocksTheToolsAccessibilityCanStillServe() {
        // Issue #44: the old snapshot derived one availability flag from the
        // ADB phase and said "Do not call device tools", which stopped
        // open_intent on a deep link that never needed ADB.
        val context = DeviceRuntimeContext.render(
            DeviceCapabilities(
                adbStatus = AdbStatus(ConnectionPhase.DISCONNECTED, "Not connected"),
                ready = setOf("read_ui", "open_intent", "tap", "type_text"),
                blocked = setOf("shell", "install_apk", "push_file", "pull_file"),
                backendStatus = "Accessibility: connected | Wireless ADB (optional): disconnected - Wireless Debugging is off",
            )
        )

        assertTrue(context.contains("Device tools you can call now: open_intent, read_ui, tap, type_text"))
        assertTrue(context.contains("Tools that need a backend that is off: install_apk, pull_file, push_file, shell"))
        assertTrue(context.contains("Call anything in the first list normally"))
        assertTrue(context.contains("being off is normal"))
        // The blanket block is exactly what must not come back.
        assertFalse(context.contains("Do not call device tools"))
        assertFalse(context.contains("Device tools available: no"))
        // Nor may the snapshot lead with the ADB phase.
        assertFalse(context.contains("Wireless ADB phase"))
        assertFalse(context.contains("Local ADB port"))
    }

    @Test fun noLiveDeviceBackendAsksForAccessibilityAndCallsAdbOptional() {
        // Knowledge and workflow tools are always ready, so a non-empty list
        // must not hide that nothing can operate the screen.
        val context = DeviceRuntimeContext.render(
            DeviceCapabilities(
                adbStatus = AdbStatus(ConnectionPhase.DISCONNECTED, "Not connected"),
                ready = setOf("recall_capability", "device_status"),
                blocked = setOf("read_ui", "shell"),
                deviceBackendLive = false,
            )
        )

        assertTrue(context.contains("No device backend is live"))
        assertTrue(context.contains("Settings > Accessibility"))
        assertTrue(context.contains("opening an app or a deep link needs no backend"))
        assertFalse(context.contains("Call anything in the first list normally"))
    }

    @Test fun accessibilityOffWithAdbConnectedAsksForAccessibilityNotAdb() {
        val context = DeviceRuntimeContext.render(
            DeviceCapabilities(
                adbStatus = AdbStatus(ConnectionPhase.CONNECTED, "Connected", 37123),
                ready = setOf("read_ui", "shell", "tap"),
                blocked = setOf("tap_node", "open_intent"),
            )
        )

        assertTrue(context.contains("need the Hey Mike accessibility service"))
        assertFalse(context.contains("being off is normal"))
    }
}
