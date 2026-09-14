package dev.androidagent.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.androidagent.core.RunState
import dev.androidagent.core.VoiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as AgentApplication).graph
    private lateinit var keepAwake: ConversationKeepAwakeController
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                graph.chatMode.stop(dev.androidagent.core.ChatModeStopReason.SCREEN_LOCKED, announce = false)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        keepAwake = ConversationKeepAwakeController(AndroidScreenWakeLock(this))
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_LOW))
        startForeground(
            101,
            notification(RunState(), VoiceState()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        graph.adb.startAutoReconnect(scope)
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, screenFilter)
        }
        screenReceiverRegistered = true
        scope.launch {
            combine(graph.coordinator.state, graph.voice.state) { run, voice -> run to voice }
                .collect { (run, voice) ->
                    keepAwake.update(run, voice)
                    updateForeground(run, voice)
                }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopAll()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { stopAll(); stopSelf() }
    override fun onDestroy() {
        stopAll()
        if (screenReceiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
        graph.adb.stopAutoReconnect()
        graph.scope.launch {
            runCatching { graph.voice.stop() }
            runCatching { graph.engine.close() }
            runCatching { graph.adb.disconnect() }
        }
        graph.overlay.hide()
        scope.cancel()
        keepAwake.release()
        super.onDestroy()
    }
    private fun stopAll() {
        graph.chatMode.stop(dev.androidagent.core.ChatModeStopReason.STOPPED, announce = false)
        graph.queue.pause()
        graph.coordinator.endVoice()
        graph.coordinator.stop()
        scope.launch { runCatching { graph.voice.stop() } }
    }
    private fun updateForeground(run: RunState, voice: VoiceState) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            if (voice.active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (Build.VERSION.SDK_INT >= 34) startForeground(101, notification(run, voice), types)
        else getSystemService(NotificationManager::class.java).notify(101, notification(run, voice))
    }
    private fun notification(state: RunState, voice: VoiceState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val active = state.active || voice.active
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(if (voice.active) "Talking with Mike" else if (state.active) "Mike is working" else "Hey Mike")
            .setContentText(if (voice.active) voice.message else if (state.active) state.status else "Local runtime ready")
            .setContentIntent(open).setOngoing(active).setSilent(true)
            .addAction(0, "Stop", stop).build()
    }
    companion object { const val CHANNEL = "agent_activity"; const val ACTION_STOP = "dev.androidagent.STOP" }
}
