package dev.androidagent.app.panels

import android.app.NotificationManager
import dev.androidagent.app.QuestionNotifier
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

    @Test fun aPanelToApproveKeepsItsImportanceWithNoSoundAndNoVibration() {
        val approvals = channels.getValue(SavedPanelNotifications.APPROVALS)
        assertEquals("Panels to approve", approvals.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, approvals.importance)
        assertFalse(approvals.sound)
        assertFalse(approvals.vibration)
    }

    @Test fun theOldApprovalChannelWithASoundIsReplacedAndDeleted() {
        assertEquals("saved_panel_approvals", SavedPanelNotifications.OLD_APPROVALS)
        assertNotEquals(SavedPanelNotifications.OLD_APPROVALS, SavedPanelNotifications.APPROVALS)
        assertTrue(SavedPanelNotifications.OLD_APPROVALS in SavedPanelNotifications.RETIRED_CHANNELS)
    }

    @Test fun aQuestionPopsUpWithNoSoundAndNoVibration() {
        val questions = QuestionNotifier.CHANNEL_SPEC
        assertEquals(QuestionNotifier.CHANNEL, questions.id)
        assertEquals("Questions from Mike", questions.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, questions.importance)
        assertFalse(questions.sound)
        assertFalse(questions.vibration)
    }

    @Test fun theOldQuestionChannelWithASoundIsReplacedAndDeleted() {
        assertEquals("questions", QuestionNotifier.OLD_CHANNEL)
        assertNotEquals(QuestionNotifier.OLD_CHANNEL, QuestionNotifier.CHANNEL)
        assertTrue(QuestionNotifier.OLD_CHANNEL in QuestionNotifier.RETIRED_CHANNELS)
        assertFalse(QuestionNotifier.CHANNEL in QuestionNotifier.RETIRED_CHANNELS)
    }
}
