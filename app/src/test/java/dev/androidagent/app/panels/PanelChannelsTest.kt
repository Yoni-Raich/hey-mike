package dev.androidagent.app.panels

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelChannelsTest {
    private val channels = SavedPanelNotifications.CHANNELS.associateBy { it.id }

    @Test fun aPanelTimerPopsUpWithNoSoundAndNoVibration() {
        val timers = channels.getValue(SavedPanelNotifications.ALERTS)
        assertEquals("Panel timers", timers.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, timers.importance)
        assertFalse(timers.sound)
        assertFalse(timers.vibration)
    }

    @Test fun theOldTimerChannelWithASoundIsReplacedAndDeleted() {
        // Android keeps a channel's first sound, so silence needs a new id.
        assertNotEquals(SavedPanelNotifications.OLD_ALERTS, SavedPanelNotifications.ALERTS)
        assertTrue(SavedPanelNotifications.OLD_ALERTS in SavedPanelNotifications.RETIRED_CHANNELS)
        assertFalse(SavedPanelNotifications.CHANNELS.any { it.id in SavedPanelNotifications.RETIRED_CHANNELS })
    }

    @Test fun aPinnedPanelIsSilent() {
        val panels = channels.getValue(SavedPanelNotifications.PANELS)
        assertFalse(panels.sound)
        assertFalse(panels.vibration)
    }
}
