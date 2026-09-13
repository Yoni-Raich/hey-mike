package dev.androidagent.core

import org.junit.Assert.*
import org.junit.Test

class SetupChecklistTest {

    private val ready = SetupSignals(
        runtimePhase = RuntimePhase.READY,
        signedIn = true,
        a11yConnected = true,
        a11yDeclared = true,
        overlayGranted = true,
        notificationsGranted = true,
        installUpdatesGranted = true,
        microphoneGranted = true,
        adbPhase = ConnectionPhase.CONNECTED,
        adbPort = 41231,
    )

    private fun row(signals: SetupSignals, item: SetupItem) =
        SetupChecklist.rows(signals).first { it.item == item }

    @Test fun aFullySetUpPhoneIsReady() {
        val rows = SetupChecklist.rows(ready)
        assertTrue(rows.all { it.state == SetupState.DONE })
        assertTrue(SetupChecklist.readyForRuns(rows))
        assertEquals(0, SetupChecklist.outstanding(rows))
        assertEquals("Ready", SetupChecklist.headline(rows))
    }

    @Test fun anEmptyPhoneListsEveryRequiredStep() {
        val rows = SetupChecklist.rows(SetupSignals())
        assertEquals(4, SetupChecklist.outstanding(rows))
        assertFalse(SetupChecklist.readyForRuns(rows))
        assertEquals("4 things to finish", SetupChecklist.headline(rows))
    }

    @Test fun anUnpairedWirelessAdbNeverBlocksARun() {
        // Screen control runs on the accessibility service; ADB only adds
        // shell, files and installs, so an unpaired phone is ready.
        val signals = ready.copy(adbPhase = ConnectionPhase.DISCONNECTED, adbPort = null)
        val rows = SetupChecklist.rows(signals)
        assertTrue(SetupChecklist.readyForRuns(rows))
        assertEquals("Ready", SetupChecklist.headline(rows))
        val adb = row(signals, SetupItem.WIRELESS_ADB)
        assertEquals(SetupImportance.OPTIONAL, adb.importance)
        assertEquals(SetupState.PENDING, adb.state)
    }

    @Test fun optionalPermissionsNeverBlockARun() {
        // Voice and update installs are conveniences. Counting them would tell
        // a working phone it is not ready.
        val signals = ready.copy(microphoneGranted = false, installUpdatesGranted = false, notificationsGranted = false)
        assertTrue(SetupChecklist.readyForRuns(SetupChecklist.rows(signals)))
        assertEquals(SetupState.PENDING, row(signals, SetupItem.MICROPHONE).state)
        assertEquals(SetupState.PENDING, row(signals, SetupItem.NOTIFICATIONS).state)
    }

    @Test fun oneOutstandingStepIsSingular() {
        val rows = SetupChecklist.rows(ready.copy(overlayGranted = false))
        assertEquals(1, SetupChecklist.outstanding(rows))
        assertEquals("1 thing to finish", SetupChecklist.headline(rows))
    }

    @Test fun accessibilitySwitchedOnButNeverStartedIsBlockedNotPending() {
        // The restricted-settings case: telling the user to turn on a switch
        // they already turned on is the wrong instruction.
        val blocked = row(ready.copy(a11yConnected = false, a11yDeclared = true), SetupItem.SCREEN_CONTROL)
        assertEquals(SetupState.BLOCKED, blocked.state)
        assertTrue(blocked.summary.contains("restricted", ignoreCase = true))

        val off = row(ready.copy(a11yConnected = false, a11yDeclared = false), SetupItem.SCREEN_CONTROL)
        assertEquals(SetupState.PENDING, off.state)
    }

    @Test fun runtimeAndAdbReportProgressWhileTheyWork() {
        assertEquals(SetupState.WORKING, row(ready.copy(runtimePhase = RuntimePhase.PREPARING), SetupItem.RUNTIME).state)
        assertEquals(SetupState.BLOCKED, row(ready.copy(runtimePhase = RuntimePhase.ERROR), SetupItem.RUNTIME).state)
        for (phase in listOf(ConnectionPhase.DISCOVERING, ConnectionPhase.PAIRING, ConnectionPhase.CONNECTING)) {
            assertEquals(SetupState.WORKING, row(ready.copy(adbPhase = phase), SetupItem.WIRELESS_ADB).state)
        }
        assertEquals(SetupState.BLOCKED, row(ready.copy(adbPhase = ConnectionPhase.ERROR), SetupItem.WIRELESS_ADB).state)
    }

    @Test fun aConnectedAdbRowNamesItsPort() {
        assertEquals("Connected · port 41231", row(ready, SetupItem.WIRELESS_ADB).summary)
        assertEquals("Connected", row(ready.copy(adbPort = null), SetupItem.WIRELESS_ADB).summary)
    }

    @Test fun anUnreportedAccountIsPendingRatherThanSignedOut() {
        // Before the engine connects there is no account either way, and
        // "Signed out" would be a claim the app cannot make yet.
        val unknown = row(ready.copy(signedIn = null), SetupItem.ACCOUNT)
        assertEquals(SetupState.PENDING, unknown.state)
        assertEquals("Waiting for the engine", unknown.summary)

        val pending = row(ready.copy(signedIn = false, loginPending = true), SetupItem.ACCOUNT)
        assertEquals(SetupState.WORKING, pending.state)
    }
}
