package dev.androidagent.core

import dev.heymike.connect.Access
import dev.heymike.connect.Capability
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedAppStatusTest {
    private val schema = buildJsonObject { put("type", "object") }
    private val app = ConnectProvider("clock.app", "ClockService", "Clock", "Clock settings", "signer-a", 1)
    private val read = Capability("get_theme", "Read theme", "Read the clock theme", schema, access = Access.READ)
    private val write = Capability("set_theme", "Change theme", "Set a clock color", schema, access = Access.WRITE)

    private fun grant(vararg caps: Capability, enabled: Boolean = true, signer: String = app.signer, off: Set<String> = emptySet()) =
        ConnectionGrant(app.packageName, signer, enabled, caps.map { CapabilityGrant(it.name, it.access, it.policyHash(), enabled = it.name !in off) })

    @Test fun anAppWithNoGrantIsOff() {
        val status = connectedAppStatus(app, emptyList(), null)
        assertEquals(ConnectedAppState.OFF, status.state)
        assertFalse(status.isOn)
        assertEquals(0, status.allowed)
    }

    @Test fun aReviewedAndEnabledAppIsOnWithItsAllowedCount() {
        val status = connectedAppStatus(app, listOf(grant(read, write)), listOf(read, write))
        assertTrue(status.isOn)
        assertEquals(2, status.allowed)
        assertEquals(0, status.waiting)
    }

    @Test fun anAbilityTheUserTurnedOffIsNotCountedAndNotWaiting() {
        val status = connectedAppStatus(app, listOf(grant(read, write, off = setOf("set_theme"))), listOf(read, write))
        assertEquals(1, status.allowed)
        assertEquals(0, status.waiting)
    }

    @Test fun anAbilityTheUserNeverSawIsWaiting() {
        val status = connectedAppStatus(app, listOf(grant(read)), listOf(read, write))
        assertEquals(1, status.allowed)
        assertEquals(1, status.waiting)
    }

    @Test fun anAbilityWhoseAccessChangedIsWaitingAgain() {
        val widened = write.copy(access = Access.SENSITIVE)
        val status = connectedAppStatus(app, listOf(grant(read, write)), listOf(read, widened))
        assertEquals(1, status.allowed)
        assertEquals(1, status.waiting)
    }

    @Test fun turningTheConnectionOffAllowsNothingButKeepsTheChoices() {
        val status = connectedAppStatus(app, listOf(grant(read, write, enabled = false)), listOf(read, write))
        assertEquals(ConnectedAppState.OFF, status.state)
        assertEquals(0, status.allowed)
        assertEquals(0, status.waiting)
    }

    @Test fun anUnreadAppFallsBackToTheSavedCountAndDoesNotGuessWaiting() {
        val status = connectedAppStatus(app, listOf(grant(read, write, off = setOf("get_theme"))), null)
        assertTrue(status.isOn)
        assertEquals(1, status.allowed)
        assertNull(status.waiting)
    }

    @Test fun aGrantFromAnotherPublisherIsNotTrustedForThisOne() {
        val status = connectedAppStatus(app, listOf(grant(read, signer = "signer-b")), listOf(read))
        assertEquals(ConnectedAppState.PUBLISHER_CHANGED, status.state)
        assertFalse(status.isOn)
        assertEquals(0, status.allowed)
    }

    @Test fun theHubRowSaysWhetherAnyAppIsOn() {
        assertEquals("Off · choose which apps Mike can use", connectedAppsSummary(emptyList()))
        assertEquals("Off · choose which apps Mike can use", connectedAppsSummary(listOf(grant(read, enabled = false))))
        assertEquals("1 app on", connectedAppsSummary(listOf(grant(read))))
        val second = ConnectionGrant("other.app", "s", true)
        assertEquals("2 apps on", connectedAppsSummary(listOf(grant(read), second)))
    }
}
