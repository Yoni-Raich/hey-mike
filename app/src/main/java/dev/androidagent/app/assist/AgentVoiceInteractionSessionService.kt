package dev.androidagent.app.assist

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/** Starts one [AgentVoiceInteractionSession] per assistant press. */
class AgentVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentVoiceInteractionSession(this)
}

/**
 * One press of the power button (or the assist gesture).
 *
 * The session draws nothing. It opens the app's voice mode and steps aside:
 * the conversation lives in `MainActivity`, which owns the microphone, the
 * approvals and the stop button, so a second assistant surface would only
 * split them.
 */
class AgentVoiceInteractionSession(private val context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val finish = Runnable { runCatching { hide() } }

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val app = context.applicationContext
        val intent = AssistLaunch.voiceIntent(app)
        // A plain launch keeps Mike in its own task, so the press lands on the
        // same screen the launcher icon opens. startAssistantActivity puts a
        // second copy in an assistant task, with its own chat state.
        runCatching { app.startActivity(intent) }.onFailure { plain ->
            Log.w(TAG, "plain launch failed: ${plain.javaClass.simpleName}: ${plain.message}")
            runCatching { startAssistantActivity(intent) }.onFailure {
                Log.e(TAG, "assistant launch failed too, the press opened nothing: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        // Hidden a moment later, not at once: ending the session before the
        // activity start is processed can cancel the launch on some builds.
        handler.postDelayed(finish, HIDE_DELAY_MILLIS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(finish)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MikeAssist"
        const val HIDE_DELAY_MILLIS = 1_000L
    }
}
