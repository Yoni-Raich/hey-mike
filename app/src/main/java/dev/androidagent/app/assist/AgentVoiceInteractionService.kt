package dev.androidagent.app.assist

import android.service.voice.VoiceInteractionService

/**
 * Registers Hey Mike as a digital assistant, so the user can pick it under
 * Default apps > Digital assistant app and reach Mike by holding the power
 * button instead of Gemini.
 *
 * The service holds no logic. Android keeps it bound while the app is the
 * assistant and starts [AgentVoiceInteractionSessionService] on each press.
 *
 * It opens no always-on hotword detector: replacing "Hey Google" needs a
 * preinstalled or privileged app, and a detector that can never trigger would
 * only hold the microphone.
 */
class AgentVoiceInteractionService : VoiceInteractionService()
