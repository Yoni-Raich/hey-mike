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
import android.graphics.Bitmap
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
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.androidagent.core.AssistantScreenText
import dev.androidagent.core.EngineKind
import dev.androidagent.core.QueuedTurn
import dev.androidagent.core.RunPhase
import dev.androidagent.app.ChatEngines
import java.io.File
import java.util.UUID

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
    private var screenshot = CompletableDeferred<Bitmap?>()
    private var fallback: AssistantScreenText? = null
    private var captured: Deferred<ScreenCapture?>? = null
    private var chat: Deferred<String>? = null
    private var chatId: String? = null
    private var messagesJob: Job? = null
    private var voiceStopFailure: String? = null
    private var startVoice: Job? = null
    private var stopVoice: Job? = null
    private var closingVoice: Job? = null
    private var inputMode = AssistantInputMode()
    private val typedLine = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private var image: File? = null
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
        if (usePanel) {
            scope.cancel()
            scope = MainScope()
            inputMode = AssistantInputMode()
            screen = CompletableDeferred()
            screenshot = CompletableDeferred()
            fallback = null
            image = null
            chatId = null
            typedLine.value = ""
            sending.value = false
            voiceStopFailure = null
            // Respect Android's per-user context switches. Do not recapture the
            // composited display after Mike or the keyboard has covered it.
            val disabled = userDisabledShowContext or disabledShowContext
            if (showFlags and SHOW_WITH_ASSIST == 0 || disabled and SHOW_WITH_ASSIST != 0) screen.complete(null)
            if (showFlags and SHOW_WITH_SCREENSHOT == 0 || disabled and SHOW_WITH_SCREENSHOT != 0) screenshot.complete(null)
            if (!screen.isCompleted && !screenshot.isCompleted) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    fallback = runCatching { graph.tools.assistantScreenText() }.getOrNull()
                }
            }
        }
    }

    override fun onCreateContentView(): View = AssistantPanelView(
        context,
        level = { graph.voice.level.value },
        onMute = { graph.voice.setMuted(!graph.voice.muted.value) },
        onOpen = ::openApp,
        onEnd = { chatId?.let { if (inputMode.typing) graph.coordinator.stop(it) }; hide() },
        onTyping = ::startTyping,
        onSend = ::sendText,
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
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setDecorFitsSystemWindows(false)
        }
        failure.value = null
        waking.value = "Waking Mike"
        ownsVoice = false
        handedOff = false
        panel?.resetDraft()
        captured = scope.async {
            withTimeoutOrNull(SCREEN_WAIT_MS) { screen.await(); screenshot.await() }
            ScreenText.merge(if (screen.isCompleted) screen.await() else null, fallback,
                screenshot.isCompleted && screenshot.await() != null).also {
                Log.i(TAG, "screen ready: textChars=${it?.lines?.let(ScreenText::joined)?.length ?: 0} image=${screenshot.isCompleted && screenshot.await() != null}")
            }
        }
        chat = scope.async { closingVoice?.join(); chooseSession().also { chatId = it; Log.i(TAG, "assistant chat: $it") } }
        // The panel shows what the floating card would, so the card waits.
        graph.overlay.setAppForeground(true)
        panel?.enter()
        observe()
        startVoice = scope.launch { start() }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        // The app the user is looking at; other windows on screen report too.
        if (state.isFocused) screen.complete(state.assistStructure?.let(ScreenText::from))
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        this.screenshot.complete(screenshot)
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
        if (inputMode.typing && !handedOff) chatId?.let { id ->
            graph.coordinator.stop(id)
            graph.scope.launch { graph.queue.cancelSession(id) }
        }
        // Reset here, not on show: the screen can report before the panel does.
        if (ownsVoice && !handedOff) {
            closingVoice = graph.scope.launch { runCatching { graph.voiceConversation.stop() } }
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
                val sessionId = checkNotNull(chat).await()
                val capture = checkNotNull(captured).await()
                if (inputMode.typing) return
                waking.value = "Connecting"
                ownsVoice = true
                val model = context.getSharedPreferences(AssistLaunch.UI_PREFERENCES, 0).getString(AssistLaunch.KEY_MODEL, null)
                graph.voiceConversation.begin(sessionId, model, initialContext = ScreenText.context(capture))
            } else {
                // A running conversation also needs the new invocation's screen.
                val wasMuted = graph.voice.muted.value
                graph.voice.setMuted(true)
                try {
                    val context = ScreenText.context(checkNotNull(captured).await())
                    graph.voiceConversation.addContext(context.guidance, context.quoted)
                } finally { if (!inputMode.typing) graph.voice.setMuted(wasMuted) }
            }
            waking.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "panel voice did not start: ${error.javaClass.simpleName}: ${error.message}")
            if (!inputMode.typing) failure.value = error.message ?: "Mike could not start."
        }
    }

    /**
     * The chat the app has open when it is still empty, or a new one: a press
     * starts over, and never talks into a conversation the user already had.
     * The choice is saved so the app opens on this chat.
     */
    private suspend fun chooseSession(): String {
        val preferences = context.getSharedPreferences(AssistLaunch.UI_PREFERENCES, 0)
        val saved = preferences.getString(AssistLaunch.KEY_SESSION, null)
            ?.takeIf { graph.sessions.getSession(it) != null }
        // Voice is Codex's, so a chat made for it starts there.
        val id = graph.voiceConversation.sessionId.value ?: if (saved != null &&
            graph.sessions.messages(saved).first().isEmpty() && graph.sessions.composerDraft(saved).isNullOrEmpty() &&
            graph.computers.binding(saved) == null) saved
        else graph.sessions.createSession(ChatEngines.parse(preferences.getString("defaultEngine", null))).id
        preferences.edit().putString(AssistLaunch.KEY_SESSION, id).apply()
        return id
    }

    private fun startTyping() {
        if (!inputMode.startTyping()) return
        Log.i(TAG, "switching to text")
        failure.value = null
        waking.value = "Ending voice"
        graph.voice.setMuted(true)
        startVoice?.cancel()
        // Local capture and playback stop before any remote acknowledgement.
        // The startup job must finish cleaning up before a typed turn can run.
        stopVoice = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                graph.voiceConversation.stop()
                startVoice?.join()
                ownsVoice = false
                Log.i(TAG, "text ready: voice=${graph.voice.state.value.phase}")
                waking.value = "Voice off"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                voiceStopFailure = "Could not end voice: ${error.message}"
                failure.value = voiceStopFailure
            }
        }
    }

    private fun sendText(question: String) {
        if (question.isBlank() || sending.value) return
        startTyping()
        sending.value = true
        scope.launch {
            try {
                stopVoice?.join()
                check(voiceStopFailure == null && !graph.voice.state.value.active) { voiceStopFailure ?: "Voice is still ending." }
                failure.value = null
                val id = checkNotNull(chat).await()
                val capture = checkNotNull(captured).await()
                val bitmap = if (screenshot.isCompleted) screenshot.await() else null
                if (image == null && bitmap != null) image = withContext(Dispatchers.IO) {
                    File(graph.sessions.workspace(id), "screenshots/assistant-${UUID.randomUUID()}.png").also { file ->
                        file.parentFile?.mkdirs()
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    }
                }
                val kind = checkNotNull(graph.sessions.getSession(id)).engine
                val preferences = context.getSharedPreferences(AssistLaunch.UI_PREFERENCES, 0)
                val modelKey = if (kind == EngineKind.CLAUDE) "claudeModel" else AssistLaunch.KEY_MODEL
                val effortKey = if (kind == EngineKind.CLAUDE) "claudeReasoningEffort" else "reasoningEffort"
                graph.queue.submit(QueuedTurn(sessionId = id,
                    prompt = ScreenText.typedPrompt(question, capture, image != null),
                    displayPrompt = question,
                    imagePaths = listOfNotNull(image?.absolutePath), engine = kind,
                    model = preferences.getString(modelKey, null), effort = preferences.getString(effortKey, null)))
                panel?.resetDraft()
                typedLine.value = ""
                messagesJob?.cancel()
                messagesJob = scope.launch {
                    graph.sessions.messages(id).collect { messages ->
                        typedLine.value = messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure.value = error.message ?: "Could not send. Your question is still here." }
            finally { sending.value = false }
        }
    }

    private fun observe() {
        var wasLive = false
        scope.launch {
            combine(
                graph.voice.state,
                graph.voice.muted,
                graph.voiceConversation.transcript,
                graph.coordinator.sessionStates,
                combine(waking, failure, typedLine, sending) { phase, error, text, busy -> PanelState(phase, error, text, busy) },
            ) { voice, muted, line, runs, state ->
                val run = runs[chatId] ?: RunState()
                if (inputMode.typing) PanelContent(state.error ?: if (run.active || run.phase == RunPhase.ERROR) run.status else state.phase ?: "Ask about this screen",
                    state.text, when {
                        state.error != null || run.phase == RunPhase.ERROR -> PanelTone.ERROR
                        run.controlling -> PanelTone.CONTROLLING
                        run.active -> PanelTone.WORKING
                        else -> PanelTone.LISTENING
                    },
                    sending = state.busy || run.active)
                else content(voice, muted, line, run, state.phase, state.error)
            }
                .collect { content ->
                    panel?.render(content)
                    val live = graph.voice.state.value.active
                    // Ended by voice, the notification or the app: nothing is
                    // left to show. A failure stays up so it can be read.
                    if (wasLive && !live && failure.value == null && !inputMode.typing) hide()
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
            .apply { chatId?.let { putExtra("dev.androidagent.app.extra.OPEN_CHAT", it) } }
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

    private data class PanelState(val phase: String?, val error: String?, val text: String, val busy: Boolean)
}
