package dev.androidagent.app.panels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Add to home screen" never makes a second widget for a panel that has one. */
class SavedPanelWidgetPinTest {
    private val map = mapOf("11" to "saved-shop", "12" to "saved-water", "13" to "saved-gone")

    @Test fun aPanelWithAWidgetOnTheHomeScreenIsAlreadyPlaced() {
        assertTrue(SavedPanelWidget.alreadyPlaced("saved-shop", setOf(11, 12), map))
    }

    @Test fun aTieLeftByAWidgetTheUserTookOffDoesNotCount() {
        // 13 is still in the map, but the launcher no longer has it.
        assertFalse(SavedPanelWidget.alreadyPlaced("saved-gone", setOf(11, 12), map))
    }

    @Test fun aPanelWithNoWidgetCanBePinned() {
        assertFalse(SavedPanelWidget.alreadyPlaced("saved-new", setOf(11, 12, 13), map))
        assertFalse(SavedPanelWidget.alreadyPlaced("saved-shop", emptySet(), map))
        // A placed widget nobody tied to a panel yet belongs to no panel.
        assertFalse(SavedPanelWidget.alreadyPlaced("saved-shop", setOf(14), map + ("x" to "saved-shop")))
    }
}
