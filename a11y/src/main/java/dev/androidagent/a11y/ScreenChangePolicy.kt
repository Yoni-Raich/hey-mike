package dev.androidagent.a11y

import android.view.accessibility.AccessibilityEvent

/** Only package/type metadata; never reads event text or source nodes. */
internal object ScreenChangePolicy {
    fun counts(eventPackage: String?, ownPackage: String, type: Int?): Boolean {
        if (eventPackage != ownPackage) return true
        // Our streamed chat/overlay updates are excluded from observations. They
        // must not keep another app's observation waiting for screen stability.
        return type !in setOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )
    }
}
