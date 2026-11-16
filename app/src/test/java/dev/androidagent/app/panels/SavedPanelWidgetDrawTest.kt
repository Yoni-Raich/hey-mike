package dev.androidagent.app.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A widget is sent to the launcher only when what it shows changed. */
class SavedPanelWidgetDrawTest {
    @Test fun theSameFaceIsNotSentTwice() {
        val drawn = mutableMapOf<Int, String>()
        assertTrue(SavedPanelWidget.changed(drawn, 7, "panel:a", force = false))
        assertFalse(SavedPanelWidget.changed(drawn, 7, "panel:a", force = false))
        // Another widget of the same panel is its own.
        assertTrue(SavedPanelWidget.changed(drawn, 8, "panel:a", force = false))
    }

    @Test fun aChangedFaceIsSentAndRemembered() {
        val drawn = mutableMapOf(7 to "panel:a")
        assertTrue(SavedPanelWidget.changed(drawn, 7, "removed", force = false))
        assertEquals("removed", drawn[7])
    }

    @Test fun theLauncherAskingAlwaysGetsADraw() {
        val drawn = mutableMapOf(7 to "panel:a")
        assertTrue(SavedPanelWidget.changed(drawn, 7, "panel:a", force = true))
    }
}
