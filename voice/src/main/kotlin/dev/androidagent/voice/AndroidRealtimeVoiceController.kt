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

package dev.androidagent.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import dev.androidagent.core.RealtimeAudioChunk
import dev.androidagent.core.RealtimeTransport
import dev.androidagent.core.RealtimeVoiceEngine
import dev.androidagent.core.VoiceEvent
import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.math.max

/**
 * Android microphone and speaker lifecycle for the experimental Codex realtime
 * protocol. Raw microphone audio is streamed only; it is never written to disk.
 */
class AndroidRealtimeVoiceController(
    context: Context,
    private val engine: RealtimeVoiceEngine,
    private val scope: CoroutineScope,
    private val webRtcSessionFactory: (Context) -> RealtimeMediaSession = { WebRtcRealtimeAudioSession(it) },
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val audioRoute = CommunicationAudioRoute(audioManager)
    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()
    private val mutableMuted = MutableStateFlow(false)

    /** True while the user has silenced the microphone. The conversation stays open. */
    val muted: StateFlow<Boolean> = mutableMuted.asStateFlow()
    private val mutableLevel = MutableStateFlow(0f)

    /**
     * Loudness of whoever is talking, 0 (silence) to 1: the microphone while
     * listening, Codex's audio while it speaks. Updated every 10-20 ms.
     */
    val level: StateFlow<Float> = mutableLevel.asStateFlow()

    private var activeThreadId: String? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var playerFormat: Pair<Int, Int>? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var captureJob: Job? = null
    private var senderJob: Job? = null
    private var playbackJob: Job? = null
    private var speakingResetJob: Job? = null
    private var inputFrames: Channel<RealtimeAudioChunk>? = null
    private var outputFrames: Channel<RealtimeAudioChunk>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var startedSignal: CompletableDeferred<Unit>? = null
    private var sdpSignal: CompletableDeferred<String>? = null
    // Also read from the UI thread by setMuted.
    @Volatile private var webRtcSession: RealtimeMediaSession? = null
    @Volatile private var lastCodexAudioAt = 0L
    private var activeTransport: RealtimeTransport = RealtimeTransport.WEBRTC
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneState: Boolean? = null

    init {
        scope.launch {
            engine.voiceEvents.collect { event -> handleEvent(event) }
        }
    }

    suspend fun start(
        threadId: String,
        model: String? = null,
        transport: RealtimeTransport = RealtimeTransport.WEBRTC,
    ) {
        val started = CompletableDeferred<Unit>()
        val answer = if (transport == RealtimeTransport.WEBRTC) CompletableDeferred<String>() else null
        var mediaSession: RealtimeMediaSession? = null
        var remoteStarted = false

        try {
            lifecycle.withLock {
                check(!state.value.active) { "A voice conversation is already active." }
                check(
                    ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                ) { "Microphone permission is required for voice." }

                mutableState.value = VoiceState(VoicePhase.STARTING, "Connecting voice", threadId)
                mutableMuted.value = false
                activeThreadId = threadId
                activeTransport = transport
                requestAudioFocus()
                startedSignal = started
                sdpSignal = answer

                if (transport == RealtimeTransport.WEBSOCKET) {
                    val audioRecord = createRecorder()
                    recorder = audioRecord
                    enableInputEffects(audioRecord.audioSessionId)

                    inputFrames = Channel(
                        capacity = FRAME_QUEUE_CAPACITY,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST,
                    )
                    outputFrames = Channel(
                        capacity = FRAME_QUEUE_CAPACITY * 2,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST,
                    )
                } else {
                    mediaSession = webRtcSessionFactory(app).also { session ->
                        session.setLevelListener(::onAudioLevel)
                        session.setMicrophoneMuted(mutableMuted.value)
                    }
                    webRtcSession = mediaSession
                }
            }

            val offerSdp = if (transport == RealtimeTransport.WEBRTC) {
                checkNotNull(mediaSession).createOffer()
            } else {
                null
            }
            engine.startVoice(threadId, model, transport, offerSdp)
            remoteStarted = true

            // The start RPC only means "accepted". Audio waits for the
            // separate started notification and, for WebRTC, the SDP answer.
            withTimeout(VOICE_START_TIMEOUT_MS) { started.await() }
            if (transport == RealtimeTransport.WEBRTC) {
                val remoteSdp = withTimeout(VOICE_START_TIMEOUT_MS) { checkNotNull(answer).await() }
                checkNotNull(mediaSession).setRemoteAnswer(remoteSdp)
                checkNotNull(mediaSession).awaitConnected()
                lifecycle.withLock {
                    check(activeThreadId == threadId && state.value.phase != VoicePhase.STOPPING) {
                        "Voice start was cancelled."
                    }
                    checkNotNull(mediaSession).startAudio()
                    mutableState.value = VoiceState(VoicePhase.LISTENING, "Listening", threadId)
                    startedSignal = null
                    sdpSignal = null
                }
            } else {
                lifecycle.withLock {
                    check(activeThreadId == threadId && state.value.phase != VoicePhase.STOPPING) {
                        "Voice start was cancelled."
                    }
                    val audioRecord = checkNotNull(recorder)
                    val outgoing = checkNotNull(inputFrames)
                    val incoming = checkNotNull(outputFrames)
                    audioRecord.startRecording()
                    check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "Android could not start the microphone."
                    }

                    senderJob = scope.launch(Dispatchers.IO) {
                        try {
                            for (chunk in outgoing) engine.appendAudio(chunk)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            fail(threadId, error.message ?: "Could not send microphone audio.")
                        }
                    }
                    captureJob = scope.launch(Dispatchers.IO) { capture(audioRecord, outgoing) }
                    playbackJob = scope.launch(Dispatchers.IO) {
                        try {
                            for (chunk in incoming) play(chunk)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            fail(threadId, error.message ?: "Could not play voice audio.")
                        }
                    }
                    mutableState.value = VoiceState(VoicePhase.LISTENING, "Listening", threadId)
                    startedSignal = null
                    sdpSignal = null
                }
            }
        } catch (error: Exception) {
            if (remoteStarted) runCatching { engine.stopVoice() }
            lifecycle.withLock {
                if (activeThreadId == threadId) {
                    val wasStopping = state.value.phase == VoicePhase.STOPPING
                    releaseLocalAudio()
                    activeThreadId = null
                    mutableState.value = if (wasStopping) VoiceState(VoicePhase.IDLE, "Voice ended") else VoiceState(
                        VoicePhase.ERROR,
                        error.message ?: "Voice could not start.",
                        threadId,
                    )
                }
            }
            throw error
        }
    }

    suspend fun stop() {
        val threadId = lifecycle.withLock {
            if (state.value.phase == VoicePhase.STOPPING) return
            val current = activeThreadId ?: return
            mutableState.value = VoiceState(VoicePhase.STOPPING, "Ending voice", current)
            // Silence capture and playback before waiting for any network acknowledgement.
            releaseLocalAudio()
            current
        }
        try {
            engine.stopVoice()
        } finally {
            lifecycle.withLock {
                if (activeThreadId == threadId) {
                    activeThreadId = null
                    releaseLocalAudio()
                    mutableState.value = VoiceState(VoicePhase.IDLE, "Voice ended")
                }
            }
        }
    }

    suspend fun appendText(text: String) {
        val threadId = activeThreadId ?: error("Voice is not active.")
        engine.appendText(text, "user")
    }

    /** Silence or restore the microphone without ending the conversation. */
    fun setMuted(muted: Boolean) {
        if (!state.value.active) return
        mutableMuted.value = muted
        webRtcSession?.setMicrophoneMuted(muted)
        if (muted && state.value.phase != VoicePhase.SPEAKING) mutableLevel.value = 0f
    }

    // Audio-thread callback from capture and playback.
    private fun onAudioLevel(source: VoiceLevelSource, value: Float) {
        val current = state.value
        if (!current.active || current.phase == VoicePhase.STOPPING) return
        when (source) {
            VoiceLevelSource.INPUT -> if (current.phase != VoicePhase.SPEAKING) {
                mutableLevel.value = if (mutableMuted.value) 0f else value
            }
            VoiceLevelSource.OUTPUT -> {
                // Over WebRTC Codex's audio arrives on the media track, so its
                // playback level also marks Codex as speaking.
                val threadId = current.threadId ?: return
                if (value >= OUTPUT_SPEECH_LEVEL) heardCodex(threadId)
                if (state.value.phase == VoicePhase.SPEAKING) mutableLevel.value = value
            }
        }
    }

    /** Codex audio just arrived: show Codex as speaking until it has been quiet for [SPEAKING_IDLE_MS]. */
    private fun heardCodex(threadId: String) {
        lastCodexAudioAt = System.nanoTime()
        mutableState.update { current ->
            if (current.threadId == threadId && current.active && current.phase != VoicePhase.STOPPING) {
                VoiceState(VoicePhase.SPEAKING, "Mike is speaking", threadId)
            } else {
                current
            }
        }
        if (speakingResetJob?.isActive == true) return
        speakingResetJob = scope.launch {
            while (true) {
                val quietMs = (System.nanoTime() - lastCodexAudioAt) / 1_000_000L
                if (quietMs >= SPEAKING_IDLE_MS) break
                delay(SPEAKING_IDLE_MS - quietMs)
            }
            mutableState.update { current ->
                if (current.threadId == threadId && current.phase == VoicePhase.SPEAKING) {
                    VoiceState(VoicePhase.LISTENING, "Listening", threadId)
                } else {
                    current
                }
            }
        }
    }

    private suspend fun handleEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Started -> if (event.threadId == activeThreadId) {
                startedSignal?.complete(Unit)
                if (activeTransport == RealtimeTransport.WEBSOCKET) {
                    mutableState.value = VoiceState(VoicePhase.LISTENING, "Listening", event.threadId)
                } else {
                    mutableState.value = VoiceState(VoicePhase.STARTING, "Negotiating voice", event.threadId)
                }
            }
            is VoiceEvent.SdpAnswer -> if (
                event.threadId == activeThreadId && activeTransport == RealtimeTransport.WEBRTC
            ) {
                sdpSignal?.complete(event.sdp)
            }
            is VoiceEvent.OutputAudio -> if (event.threadId == activeThreadId) {
                heardCodex(event.threadId)
                if (activeTransport == RealtimeTransport.WEBSOCKET) outputFrames?.trySend(event.audio)
            }
            is VoiceEvent.Failure -> activeThreadId?.takeIf { event.threadId == null || it == event.threadId }?.let { threadId ->
                startedSignal?.completeExceptionally(IllegalStateException(event.message))
                fail(threadId, event.message)
            }
            is VoiceEvent.Closed -> if (event.threadId == activeThreadId) {
                startedSignal?.completeExceptionally(
                    IllegalStateException(event.reason?.takeIf { it.isNotBlank() } ?: "Voice closed before it started.")
                )
                lifecycle.withLock {
                    if (activeThreadId == event.threadId) {
                        activeThreadId = null
                        releaseLocalAudio()
                        mutableState.value = VoiceState(
                            VoicePhase.IDLE,
                            event.reason?.takeIf { it.isNotBlank() } ?: "Voice ended",
                        )
                    }
                }
            }
            is VoiceEvent.TranscriptDelta, is VoiceEvent.TranscriptDone -> Unit
        }
    }

    private suspend fun fail(threadId: String, message: String) {
        val shouldStop = lifecycle.withLock {
            if (activeThreadId != threadId) return
            activeThreadId = null
            releaseLocalAudio()
            mutableState.value = VoiceState(VoicePhase.ERROR, message, threadId)
            true
        }
        if (shouldStop) runCatching { engine.stopVoice() }
    }

    private suspend fun capture(record: AudioRecord, outgoing: Channel<RealtimeAudioChunk>) {
        val buffer = ByteArray(FRAME_BYTES)
        try {
            while (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    // Muted: send silence rather than nothing, so the stream keeps its timing.
                    if (mutableMuted.value) buffer.fill(0, 0, count)
                    onAudioLevel(VoiceLevelSource.INPUT, VoiceLevelMeter.level(buffer, count))
                    outgoing.trySend(
                        RealtimeAudioChunk(
                            data = buffer.copyOf(count),
                            sampleRate = SAMPLE_RATE,
                            numChannels = CHANNELS,
                            samplesPerChannel = samplesPerChannel(count, CHANNELS),
                        )
                    )
                } else if (count < 0) {
                    error("Microphone read failed ($count).")
                }
            }
        } finally {
            outgoing.close()
        }
    }

    private fun play(chunk: RealtimeAudioChunk) {
        if (chunk.data.isEmpty()) return
        onAudioLevel(VoiceLevelSource.OUTPUT, VoiceLevelMeter.level(chunk.data))
        val channels = chunk.numChannels.coerceIn(1, 2)
        val format = chunk.sampleRate to channels
        if (player == null || playerFormat != format) {
            player?.runCatching { stop() }
            player?.release()
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(
                chunk.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "Unsupported voice output format." }
            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(chunk.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer, chunk.sampleRate * channels * 2 / 5))
                .build()
                .also { track ->
                    check(track.state == AudioTrack.STATE_INITIALIZED) { "Android could not start voice playback." }
                    track.play()
                }
            playerFormat = format
        }
        val track = checkNotNull(player)
        var offset = 0
        while (offset < chunk.data.size) {
            val written = track.write(chunk.data, offset, chunk.data.size - offset, AudioTrack.WRITE_BLOCKING)
            check(written > 0) { "Voice playback failed ($written)." }
            offset += written
        }
    }

    // start() checks the runtime permission in the same lifecycle lock before
    // this helper is called. Keep the check at the public boundary.
    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "24 kHz microphone input is not supported on this device." }
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer, FRAME_BYTES * 8))
            .build()
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "Android could not open the microphone." } }
    }

    private fun enableInputEffects(sessionId: Int) {
        echoCanceler = if (AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        else null
        noiseSuppressor = if (NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        else null
        gainControl = if (AutomaticGainControl.isAvailable())
            runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull()
        else null
    }

    private fun requestAudioFocus() {
        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        run { previousSpeakerphoneState = audioManager.isSpeakerphoneOn }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setOnAudioFocusChangeListener { focus ->
                if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch { stop() }
                }
            }
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = request
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Another app is using communication audio. Try voice again when it finishes."
        }
        audioRoute.start()
    }

    private fun stopCapture() {
        runCatching { recorder?.stop() }
        if (activeTransport == RealtimeTransport.WEBRTC) runCatching { webRtcSession?.stopAudio() }
        captureJob?.cancel()
        senderJob?.cancel()
        inputFrames?.close()
        startedSignal?.cancel()
        startedSignal = null
    }

    private fun releaseLocalAudio() {
        stopCapture()
        senderJob?.cancel()
        playbackJob?.cancel()
        speakingResetJob?.cancel()
        outputFrames?.close()
        captureJob = null
        senderJob = null
        playbackJob = null
        speakingResetJob = null
        inputFrames = null
        outputFrames = null
        echoCanceler?.release()
        noiseSuppressor?.release()
        gainControl?.release()
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        recorder?.release()
        recorder = null
        webRtcSession?.setLevelListener(null)
        webRtcSession?.close()
        webRtcSession = null
        mutableLevel.value = 0f
        mutableMuted.value = false
        sdpSignal?.cancel()
        sdpSignal = null
        player?.runCatching { stop() }
        player?.release()
        player = null
        playerFormat = null
        audioRoute.close()
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 31) previousSpeakerphoneState?.let { speakerphone -> audioManager.isSpeakerphoneOn = speakerphone }
        previousSpeakerphoneState = null
        previousAudioMode?.let { audioManager.mode = it }
        previousAudioMode = null
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val CHANNELS = 1
        const val SAMPLES_PER_FRAME = 480
        const val BYTES_PER_SAMPLE = 2
        const val FRAME_BYTES = SAMPLES_PER_FRAME * CHANNELS * BYTES_PER_SAMPLE
        private const val FRAME_QUEUE_CAPACITY = 12
        private const val SPEAKING_IDLE_MS = 280L

        // Playback louder than this is Codex talking rather than line noise.
        private const val OUTPUT_SPEECH_LEVEL = 0.12f
        private const val VOICE_START_TIMEOUT_MS = 30_000L

        internal fun samplesPerChannel(byteCount: Int, channels: Int): Int =
            byteCount / (BYTES_PER_SAMPLE * channels.coerceAtLeast(1))
    }
}
