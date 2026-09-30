/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.app.assist

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.AgentService
import dev.androidagent.app.MainActivity
import dev.androidagent.app.VoiceTranscript
import dev.androidagent.core.RunState
import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Starts one [AgentVoiceInteractionSession] per assistant press. */
class AgentVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentVoiceInteractionSession(this)
}

/**
 * One press of the power button (or the assist gesture).
 *
 * Mike opens over the app the user is in: the edge glow sweeps in, a card
 * rises from the bottom, and a live voice conversation starts with the text
 * of that screen already handed to the model, so "what does this mean?" works
 * from the first word. The conversation belongs to the app (see
 * `VoiceConversation`), so "Open Mike" moves it to the chat screen unbroken.
 *
 * Before the microphone is allowed or the consent is given there is nowhere
 * to ask for them, so the press opens the app's voice mode instead, as it did
 * before the panel existed.
 */
class AgentVoiceInteractionSession(private val context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val finish = Runnable { runCatching { hide() } }
    private val graph get() = (context.applicationContext as AgentApplication).graph
    private var scope = MainScope()
    private var panel: AssistantPanelView? = null
    private var usePanel = false
    private var screen = CompletableDeferred<ScreenCapture?>()
    private val waking = MutableStateFlow<String?>(null)
    private val failure = MutableStateFlow<String?>(null)
    /** This press started the conversation, so closing the panel ends it. */
    private var ownsVoice = false
    /** "Open Mike" moved the conversation to the app, which now owns it. */
    private var handedOff = false

    // Before every show, not once: a session can outlive the press that made
    // it, and the microphone or the consent may have changed in between.
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        usePanel = AssistLaunch.panelReady(context)
        setUiEnabled(usePanel)
    }

    override fun onCreateContentView(): View = AssistantPanelView(
        context,
        level = { graph.voice.level.value },
        onMute = { graph.voice.setMuted(!graph.voice.muted.value) },
        onOpen = ::openApp,
        onEnd = { hide() },
    ).also { panel = it }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (!usePanel) {
            openAppVoice()
            return
        }
        window?.window?.apply {
            // A glow and a card over the app, not a sheet that hides it.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDecorFitsSystemWindows(false)
        }
        scope.cancel()
        scope = MainScope()
        failure.value = null
        waking.value = "Waking Mike"
        ownsVoice = false
        handedOff = false
        // The panel shows what the floating card would, so the card waits.
        graph.overlay.setAppForeground(true)
        panel?.enter()
        observe()
        scope.launch { start() }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        // The app the user is looking at; other windows on screen report too.
        if (state.isFocused) screen.complete(state.assistStructure?.let(ScreenText::from))
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = panel ?: return
        // Nothing is covered for layout purposes, and only the card takes touches.
        outInsets.contentInsets.top = view.height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        view.touchableRegion(outInsets.touchableRegion)
    }

    override fun onHide() {
        super.onHide()
        if (!usePanel) return
        scope.cancel()
        // Reset here, not on show: the screen can report before the panel does.
        screen = CompletableDeferred()
        if (ownsVoice && !handedOff) {
            graph.scope.launch { runCatching { graph.voiceConversation.stop() } }
        }
        ownsVoice = false
        if (!handedOff) graph.overlay.setAppForeground(false)
    }

    override fun onDestroy() {
        handler.removeCallbacks(finish)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun start() {
        try {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java)) }
                .onFailure { Log.w(TAG, "agent service did not start: ${it.javaClass.simpleName}: ${it.message}") }
            if (!graph.voice.state.value.active) {
                graph.queue.pause()
                val sessionId = chooseSession()
                waking.value = "Connecting"
                ownsVoice = true
                val model = context.getSharedPreferences(AssistLaunch.UI_PREFERENCES, 0).getString(AssistLaunch.KEY_MODEL, null)
                graph.voiceConversation.begin(sessionId, model)
            }
            waking.value = null
            // Usually here long before voice is up; a screen that never reports
            // (a secure window, the setting off) must not hold the call.
            val capture = withTimeoutOrNull(SCREEN_WAIT_MS) { screen.await() }
            val context = ScreenText.context(capture)
            graph.voiceConversation.addContext(context.guidance, context.quoted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "panel voice did not start: ${error.javaClass.simpleName}: ${error.message}")
            failure.value = error.message ?: "Mike could not start."
        }
    }

    /**
     * The chat the app has open when it is still empty, or a new one: a press
     * starts over, and never talks into a conversation the user already had.
     * The choice is saved so the app opens on this chat.
     */
    private suspend fun chooseSession(): String {
        val preferences = context.getSharedPreferences(AssistLaunch.UI_PREFERENCES, 0)
        // Voice is Codex's, so a saved Claude chat is never reused here.
        val saved = preferences.getString(AssistLaunch.KEY_SESSION, null)
            ?.takeIf { graph.sessions.getSession(it)?.engine == dev.androidagent.core.EngineKind.CODEX }
        val id = if (saved != null && graph.sessions.messages(saved).first().isEmpty()) saved
        else graph.sessions.createSession(dev.androidagent.core.EngineKind.CODEX).id
        preferences.edit().putString(AssistLaunch.KEY_SESSION, id).apply()
        return id
    }

    private fun observe() {
        var wasLive = false
        scope.launch {
            combine(
                graph.voice.state,
                graph.voice.muted,
                graph.voiceConversation.transcript,
                graph.coordinator.state,
                combine(waking, failure) { phase, error -> phase to error },
            ) { voice, muted, line, run, (phase, error) -> content(voice, muted, line, run, phase, error) }
                .collect { content ->
                    panel?.render(content)
                    val live = graph.voice.state.value.active
                    // Ended by voice, the notification or the app: nothing is
                    // left to show. A failure stays up so it can be read.
                    if (wasLive && !live && failure.value == null) hide()
                    wasLive = live
                }
        }
    }

    private fun content(voice: VoiceState, muted: Boolean, line: VoiceTranscript, run: RunState, phase: String?, error: String?): PanelContent = when {
        error != null -> PanelContent(error, tone = PanelTone.ERROR)
        !voice.active -> PanelContent(phase ?: voice.message, tone = PanelTone.WAKING)
        run.approval != null -> PanelContent("Mike needs your OK: say yes or no", line.text, PanelTone.WORKING, muted, live = true)
        run.controlling -> PanelContent(run.status, line.text, PanelTone.CONTROLLING, muted, live = true)
        run.tool != null || run.status.startsWith("Working") -> PanelContent(run.status, line.text, PanelTone.WORKING, muted, live = true)
        muted -> PanelContent("Muted", line.text, PanelTone.LISTENING, muted = true, live = true)
        voice.phase == VoicePhase.SPEAKING -> PanelContent("Mike", line.text, PanelTone.SPEAKING, live = true)
        else -> PanelContent("Listening", line.text, PanelTone.LISTENING, live = true)
    }

    /** Move the live conversation to the chat screen without ending it. */
    private fun openApp() {
        handedOff = true
        graph.overlay.setAppForeground(false)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.applicationContext.startActivity(intent) }
            .onFailure { runCatching { startAssistantActivity(intent) } }
        hide()
    }

    /** The press before the panel is usable: open the app straight into voice mode. */
    private fun openAppVoice() {
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

    private companion object {
        const val TAG = "MikeAssist"
        const val HIDE_DELAY_MILLIS = 1_000L
        const val SCREEN_WAIT_MS = 2_000L
    }
}
