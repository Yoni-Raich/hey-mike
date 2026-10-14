package dev.androidagent.web

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Input goes only to this app-owned WebView, never to the phone's global screen. */
object WebViewInput {
    @Suppress("DEPRECATION")
    suspend fun tap(view: WebView, cssX: Double, cssY: Double) = withContext(Dispatchers.Main.immediate) {
        val scale = view.scale.takeIf { it > 0f } ?: view.resources.displayMetrics.density
        val x = (cssX * scale).toFloat()
        val y = (cssY * scale).toFloat()
        if (!view.isAttachedToWindow || x < 0 || y < 0 || x >= view.width || y >= view.height)
            throw WebFailure("not_visible", "The target is outside the internal WebView viewport. Nothing was pressed.")
        val downAt = SystemClock.uptimeMillis()
        fun dispatch(action: Int): Boolean {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
            return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        val accepted = dispatch(MotionEvent.ACTION_DOWN)
        try {
            if (!accepted) throw WebFailure("input_refused", "The WebView refused touch input. Inspect the page before retrying.", mayHaveActed = true)
            delay(50)
            dispatch(MotionEvent.ACTION_UP)
        } catch (failure: Throwable) {
            // Stop between down/up cancels the gesture instead of completing a click.
            dispatch(MotionEvent.ACTION_CANCEL)
            throw failure
        }
    }
}
