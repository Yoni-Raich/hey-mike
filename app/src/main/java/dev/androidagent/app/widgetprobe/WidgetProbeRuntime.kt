package dev.androidagent.app.widgetprobe

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.androidagent.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Lazy, opt-in owner. Nothing calls this from application startup or the agent/tool graph. */
internal class WidgetProbeRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("wa_widget_probe_v1", Context.MODE_PRIVATE)
    val manager: AppWidgetManager = AppWidgetManager.getInstance(app)
    private val handler = Handler(Looper.getMainLooper())
    private val power = app.getSystemService(PowerManager::class.java)
    private val events = runCatching { JSONArray(prefs.getString("events", "[]")) }.getOrDefault(JSONArray())
    var revision by mutableStateOf(0); private set
    var status by mutableStateOf(prefs.getString("status", "Off. Setup is optional.")!!); private set
    var previewVisible by mutableStateOf(false); private set
    var running by mutableStateOf(false); private set
    var lastSample by mutableStateOf("No collection rows observed."); private set
    val stage get() = prefs.getString("setup", "NONE")!!
    val widgetId get() = prefs.getInt("widgetId", AppWidgetManager.INVALID_APPWIDGET_ID)
    val ready get() = stage == "READY" && providerMatches()
    private var view: AppWidgetHostView? = null
    private var listening = false
    private var trial: ProbeTrial? = null
    private var marker = ""
    private var goal = ""
    private var lastHeartbeat = 0L
    private var lastPhase: ProbePhase? = null
    private var lastTick = 0L
    private var frames = 0
    private var collectionEvents = 0
    private var stopService: (() -> Unit)? = null
    private var sampling = false
    private val rows = ProbeRows {
        if (allowed()) { collectionEvents++; event("collection_changed") }
    }
    val host: AppWidgetHost = object : AppWidgetHost(app, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView =
            object : AppWidgetHostView(context) {
                // No WhatsApp PendingIntent, chat open or click can be sent by this preview.
                override fun dispatchTouchEvent(event: MotionEvent?) = true
                override fun performClick() = false
                override fun updateAppWidget(remoteViews: RemoteViews?) {
                    if (!allowed()) return
                    super.updateAppWidget(remoteViews)
                    frames++
                    event(if (remoteViews == null) "empty_frame" else "frame_update")
                }
            }
        fun release() = clearViews()
        override fun onAppWidgetRemoved(appWidgetId: Int) {
            if (appWidgetId == widgetId) { stop("Widget removed by system"); prefs.edit().putString("setup", "NONE").apply() }
            super.onAppWidgetRemoved(appWidgetId)
        }
    }

    init {
        if (prefs.getBoolean("running", false)) {
            prefs.edit().putBoolean("running", false).apply()
            event("interrupted_process_or_update")
            setStatus("Interrupted: process ended or app updated. No automatic restart.")
        }
    }

    fun provider(): AppWidgetProviderInfo? = manager.getInstalledProvidersForProfile(android.os.Process.myUserHandle())
        .firstOrNull { it.provider == PROVIDER && it.profile == android.os.Process.myUserHandle() }

    fun providerMatches() = runCatching {
        manager.getAppWidgetInfo(widgetId)?.let { it.provider == PROVIDER && it.profile == android.os.Process.myUserHandle() } == true
    }.getOrDefault(false)

    fun beginSetup(): Int {
        check(!running)
        reset()
        check(provider() != null) { "Personal-profile WhatsApp widget not available" }
        val id = host.allocateAppWidgetId()
        prefs.edit().putInt("widgetId", id).putString("setup", "BINDING").commit()
        setStatus("Waiting for system widget consent.")
        return id
    }

    fun configuring() {
        check(stage == "BINDING" && providerMatches())
        prefs.edit().putString("setup", "CONFIGURING").commit()
        setStatus("Complete WhatsApp widget configuration/authentication.")
    }

    fun configured(ok: Boolean, returnedId: Int?) {
        if (!ProbeTrial.configurationAccepted(stage, ok, providerMatches(), returnedId == null || returnedId == widgetId)) {
            setupFailed("Configuration canceled or rejected. Nothing enabled."); return
        }
        prefs.edit().putString("setup", "READY").commit()
        event("setup_ready")
        setStatus("Configured. Show the foreground preview before a trial.")
    }

    fun setupFailed(message: String) { reset(); setStatus(message) }

    fun reset() {
        stop("Stopped")
        val id = widgetId
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) runCatching { host.deleteAppWidgetId(id) }
        prefs.edit().remove("widgetId").putString("setup", "NONE").commit()
        setStatus("Off. The experiment widget was removed.")
    }

    fun visible(value: Boolean) {
        previewVisible = value
        if (!value) {
            detach()
            if (!running) releaseHost()
        }
    }

    fun showPreview() {
        if (!ready || !previewVisible) return
        try {
            ensureHost()
            setStatus("Foreground baseline. Widget taps are disabled.")
        } catch (e: RuntimeException) { stop("Host failed: ${e.javaClass.simpleName}") }
    }

    fun attach(container: FrameLayout) {
        if (!previewVisible) return
        val current = view ?: return
        if (current.parent !== container) {
            detach()
            container.addView(current, FrameLayout.LayoutParams(-1, -1))
        }
        val widthDp = (container.width / app.resources.displayMetrics.density).toInt()
        if (widthDp > 100) updateSize(widthDp, 300)
    }

    fun detach() { (view?.parent as? ViewGroup)?.removeView(view) }

    fun startTrial(goal: String, marker: String, onStop: () -> Unit) {
        check(ready && !running && goal in listOf("BACKGROUND", "SCREEN_OFF"))
        check(ProbeTrial.validMarker(marker))
        this.goal = goal
        this.marker = marker
        trial = ProbeTrial(SystemClock.elapsedRealtime())
        frames = 0; collectionEvents = 0; lastHeartbeat = 0; lastTick = 0; lastPhase = null
        stopService = onStop
        running = true
        prefs.edit().putBoolean("running", true).commit()
        event("trial_start", JSONObject().put("goal", goal).put("marker", marker).put("durationMs", ProbeTrial.TEN_MINUTES))
        try { ensureHost() } catch (e: RuntimeException) { stop("Host failed: ${e.javaClass.simpleName}"); return }
        setStatus("Running 10 minutes: $goal. Use Home or turn the screen off now.")
    }

    fun stop(reason: String = "Stopped by user") {
        val wasRunning = running
        trial?.stop(); trial = null; running = false
        prefs.edit().putBoolean("running", false).apply()
        releaseHost()
        if (wasRunning) { event("trial_end", JSONObject().put("reason", reason)); setStatus(reason) }
        stopService?.also { stopService = null; it() }
        revision++
    }

    private fun allowed(): Boolean {
        val current = trial
        if (current != null && !current.accepting(SystemClock.elapsedRealtime())) return false
        return ready && (previewVisible || running)
    }

    private fun ensureHost() {
        if (view == null) {
            val info = checkNotNull(manager.getAppWidgetInfo(widgetId))
            view = host.createView(app, widgetId, info)
            // Real foreground allocation; the detached host retains these same dimensions.
            updateSize(300, 300)
        }
        if (!listening) { host.startListening(); listening = true }
        handler.removeCallbacks(tick)
        handler.post(tick)
        revision++
    }

    private fun updateSize(width: Int, height: Int) {
        val options = manager.getAppWidgetOptions(widgetId)
        if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) == width &&
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) == height) return
        manager.updateAppWidgetOptions(widgetId, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        })
        event("allocation", JSONObject().put("widthDp", width).put("heightDp", height))
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (running && trial?.accepting(now) != true) { stop("Finished 10-minute window (not a pass result)"); return }
            if (!allowed()) return
            try { sample(now) } catch (e: RuntimeException) {
                event("public_api_error", JSONObject().put("class", e.javaClass.simpleName))
                stop("Public widget API failed: ${e.javaClass.simpleName}"); return
            }
            handler.postDelayed(this, 2_000)
        }
    }

    private fun sample(now: Long) {
        if (sampling) return
        sampling = true
        try {
            val current = view ?: return
            val phase = phase()
            if (phase != lastPhase) { event("phase"); lastPhase = phase }
            if (lastTick > 0 && now - lastTick > 5_000) event("sampling_gap", JSONObject().put("gapMs", now - lastTick))
            lastTick = now
            if (current.parent == null) {
                val options = manager.getAppWidgetOptions(widgetId)
                val density = app.resources.displayMetrics.density
                val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300) * density).toInt()
                val height = (300 * density).toInt()
                current.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                current.layout(0, 0, width, height)
            }
            val sample = rows.sample(current)
            lastSample = "Frames: $frames; collection events: $collectionEvents\n" +
                "Collections: ${sample.collections}; adapter count: ${sample.adapterCount}; actual rows: ${sample.rows.size}" +
                if (sample.capped) " (first 100 only)" else ""
            val trial = trial
            if (trial != null) sample.rows.forEach { row ->
                val id = trial.rowId(listOf(row.heading, row.preview, row.date))
                if (trial.firstSeen(id)) event("row_first_observed", JSONObject()
                    .put("row", id).put("heading", row.heading.isNotBlank()).put("preview", true)
                    .put("date", row.date.isNotBlank()).put("markerMatched", row.preview.contains(marker)))
            }
            if (now - lastHeartbeat >= 10_000 || lastHeartbeat == 0L) {
                lastHeartbeat = now
                event("sample", JSONObject().put("collections", sample.collections).put("adapterCount", sample.adapterCount)
                    .put("actualRows", sample.rows.size).put("requested", sample.requested).put("capped", sample.capped)
                    .put("frames", frames).put("collectionEvents", collectionEvents))
            }
        } finally { sampling = false }
    }

    private fun phase() = ProbeTrial.phase(power.isInteractive,
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

    private fun event(kind: String, details: JSONObject = JSONObject()) {
        details.put("event", kind).put("timeMs", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime())
            .put("phase", phase().name).put("previewAttached", view?.isAttachedToWindow == true)
        events.put(details)
        while (events.length() > 2000) events.remove(0)
        prefs.edit().putString("events", events.toString()).apply()
        revision++
    }

    fun export(): String = JSONObject().put("schema", 1).put("build", BuildConfig.VERSION_NAME)
        .put("androidApi", android.os.Build.VERSION.SDK_INT).put("provider", PROVIDER.flattenToString())
        .put("setup", stage).put("status", status).put("running", running)
        .put("limitations", "First 100 preview rows; no message IDs/history. New to observer is not proof of a new message. " +
            "No wake lock; gaps are recorded. Background widget rows are unproven. No message bodies are exported.")
        .put("events", events).toString(2)

    fun setStatus(value: String) { status = value; prefs.edit().putString("status", value).apply() }

    private fun releaseHost() {
        handler.removeCallbacks(tick)
        rows.close()
        detach()
        if (listening) runCatching { host.stopListening() }
        listening = false
        // clearViews is a protected, documented host API, not reflection or a hidden call.
        (host as? ReleasableHost)?.releaseViews()
        view = null
    }

    companion object {
        private const val HOST_ID = 0x574150
        val PROVIDER = ComponentName("com.whatsapp", "com.whatsapp.appwidget.WidgetProvider")
        @Volatile private var instance: WidgetProbeRuntime? = null
        fun get(context: Context): WidgetProbeRuntime = instance ?: synchronized(this) {
            instance ?: WidgetProbeRuntime(context).also { instance = it }
        }
    }
    private interface ReleasableHost { fun releaseViews() }
}
