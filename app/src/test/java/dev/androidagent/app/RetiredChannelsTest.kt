package dev.androidagent.app

import dev.androidagent.app.panels.SavedPanelNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredChannelsTest {
    private val current = SavedPanelNotifications.CHANNELS.map { it.id } + QuestionNotifier.CHANNEL

    @Test fun everyOldChannelWithASoundIsOnTheList() {
        for (id in listOf("questions", "saved_panels", "saved_panel_approvals", "saved_panel_alerts")) {
            assertTrue(id, id in RetiredChannels.IDS)
        }
    }

    @Test fun noChannelInUseIsOnTheList() {
        assertFalse(current.any { it in RetiredChannels.IDS })
    }

    @Test fun anUpgradedPhoneLosesEveryOldChannelAndKeepsTheNewOnes() {
        val phone = (RetiredChannels.IDS + current + "agent_run").toMutableSet()
        val deleted = RetiredChannels.retire(exists = { it in phone }, delete = { phone.remove(it) })
        assertEquals(RetiredChannels.IDS.toSet(), deleted.toSet())
        assertTrue(phone.none { it in RetiredChannels.IDS })
        assertTrue(phone.containsAll(current + "agent_run"))
    }

    @Test fun aSecondStartDeletesNothing() {
        val phone = current.toMutableSet()
        val deleted = RetiredChannels.retire(exists = { it in phone }, delete = { phone.remove(it) })
        assertTrue(deleted.isEmpty())
    }

    @Test fun oneFailedDeleteDoesNotStopTheRest() {
        val phone = RetiredChannels.IDS.toMutableSet()
        val first = RetiredChannels.IDS.first()
        RetiredChannels.retire(exists = { it in phone }, delete = { id ->
            if (id == first) error("refused")
            phone.remove(id)
        })
        assertEquals(setOf(first), phone)
    }
}
