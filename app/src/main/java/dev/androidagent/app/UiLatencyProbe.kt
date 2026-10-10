package dev.androidagent.app

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import org.json.JSONObject

/** Opt-in debug evidence. Measures touch-up dispatch to next draw, not GPU presentation or task completion. */
internal class UiLatencyProbe(private val root: View) : ViewTreeObserver.OnDrawListener {
    var enabled = false
    private var pending: Pair<Long, Long>? = null
    private var sequence = 0L
    private val expire = Runnable {
        pending?.let { (id, _) -> emit(id, "no_draw", null) }
        pending = null
    }

    init { root.viewTreeObserver.addOnDrawListener(this) }

    fun input() {
        if (!enabled) return
        pending?.let { (id, _) -> emit(id, "superseded", null) }
        root.removeCallbacks(expire)
        pending = ++sequence to SystemClock.elapsedRealtimeNanos()
        root.postDelayed(expire, 1_000)
    }

    override fun onDraw() {
        val (id, start) = pending ?: return
        pending = null
        root.removeCallbacks(expire)
        emit(id, "drawn", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
    }

    private fun emit(id: Long, status: String, elapsed: Double?) {
        Log.i(TAG, JSONObject().put("sample", id).put("status", status)
            .put("uptimeMs", SystemClock.uptimeMillis()).apply {
                elapsed?.let { put("inputToDrawMs", it) }
            }.toString())
    }

    fun close() {
        pending = null
        root.removeCallbacks(expire)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(this)
    }

    companion object {
        const val EXTRA = "dev.androidagent.app.QA_UI_LATENCY"
        const val TAG = "MikeUiLatency"
    }
}
