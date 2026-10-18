package dev.androidagent.a11y

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test

class ScreenChangePolicyTest {
    @Test fun hiddenChatContentDoesNotPreventSettlingButRealAppChangesStillDo() {
        assertFalse(ScreenChangePolicy.counts("mike", "mike", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(ScreenChangePolicy.counts("mike", "mike", AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
        assertTrue(ScreenChangePolicy.counts("capcut", "mike", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(ScreenChangePolicy.counts("mike", "mike", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(ScreenChangePolicy.counts(null, "mike", AccessibilityEvent.TYPE_WINDOWS_CHANGED))
    }
}
