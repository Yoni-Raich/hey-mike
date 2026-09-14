package dev.androidagent.app.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Present only because `<voice-interaction-service>` does not parse without a
 * `recognitionService` in the same package, and Android then rejects the whole
 * assistant registration.
 *
 * Mike does no local speech-to-text: voice is a realtime audio session in
 * `:voice`. So a request fails at once instead of holding the microphone open
 * for a result that never comes.
 */
class AgentRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
