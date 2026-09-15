package dev.androidagent.core

/**
 * How much of a realtime voice conversation is held open right now.
 *
 * Voice is not on or off. A conversation the user keeps open while driving is
 * mostly waiting, and waiting has a price: the WebRTC peer connection streams
 * whether or not anyone is talking, the screen is held awake by
 * [KeepAwakePolicy], and the foreground service keeps the microphone type.
 * This ladder is what lets the same conversation cost less between sentences.
 *
 * Muting does not save anything on its own. The WebSocket path deliberately
 * sends silence to preserve stream timing, and the WebRTC path keeps the
 * connection up, so only detaching the microphone and, below that, closing the
 * session changes what is spent.
 */
enum class VoiceAlertness {
    /** Microphone live, session live. What a conversation costs while it is a conversation. */
    ACTIVE,

    /**
     * Session live, microphone detached from the outgoing track.
     *
     * The bridge between sentences: waking is immediate because nothing was
     * torn down, and Mike can still speak, because only the outgoing half is
     * parked. It is cheaper than [ACTIVE] and is *not* free, so it is meant to
     * be a short window rather than a resting place.
     */
    PARKED,

    /**
     * Realtime session closed. The thread, and so the conversation, survives.
     *
     * The only rung that stops the spend. Waking means negotiating a new
     * session against the same thread, which costs both latency and the
     * thread history as input tokens — so dozing and waking repeatedly can
     * cost more than never dozing at all. Which way that lands is a
     * measurement on a real phone, not something this policy can assume.
     */
    DOZING,
}

/**
 * How long a conversation stays at each rung once it goes quiet.
 *
 * The defaults are provisional. Nothing on a real phone has been measured yet:
 * the price of a quiet minute of realtime, and the price of re-attaching to an
 * existing thread, are what should set these, and until both are known
 * [parkAfterMillis] being worth having at all is an assumption.
 */
data class IdleThresholds(
    val parkAfterMillis: Long = 45_000L,
    val dozeAfterMillis: Long = 180_000L,
) {
    init {
        require(parkAfterMillis > 0) { "parkAfterMillis must be positive" }
        require(dozeAfterMillis >= parkAfterMillis) {
            "dozeAfterMillis must not come before parkAfterMillis"
        }
    }
}

/**
 * Everything the ladder is decided from.
 *
 * @param quietMillis time since the last speech in either direction. The
 *   caller owns this clock; keeping it an input is what makes the decision a
 *   pure function rather than something only an instrumented test can reach.
 * @param microphoneMuted the user silenced the microphone. An explicit "do not
 *   listen", so there is nothing to keep the outgoing half open for.
 * @param expectingReply Mike asked a question and the answer has not arrived.
 *   Dozing here would drop the microphone in the one moment the user is about
 *   to use it.
 * @param hasAudioFocus false during a phone call or while another app owns
 *   communication audio.
 */
data class IdleInputs(
    val voice: VoiceState = VoiceState(),
    val run: RunState = RunState(),
    val quietMillis: Long = 0L,
    val microphoneMuted: Boolean = false,
    val expectingReply: Boolean = false,
    val hasAudioFocus: Boolean = true,
)

/**
 * Decides which rung of [VoiceAlertness] an open voice conversation belongs on.
 *
 * Pure JVM, like [KeepAwakePolicy] and [IntentPolicy]: the rules that decide
 * when the agent stops listening are worth more covered by fast unit tests
 * than buried in the audio lifecycle.
 *
 * A long run is not a reason to stay awake. A five minute timer, or a workflow
 * the agent kicked off, is exactly the case this exists for — the agent has
 * nothing to hear until it finishes, and it is woken by that finishing rather
 * than by listening for it. The cost of that choice is real and is stated here
 * rather than hidden: steering a run by voice ("not that one, the second
 * contact") only reaches a conversation that is still [VoiceAlertness.ACTIVE],
 * so a run that has been quiet long enough to doze cannot be steered until the
 * user wakes Mike first.
 *
 * What does hold the conversation open is someone waiting on the user: a
 * pending approval, which the user answers by saying yes, and a question Mike
 * has just asked.
 */
object IdlePolicy {

    fun alertness(inputs: IdleInputs, thresholds: IdleThresholds = IdleThresholds()): VoiceAlertness {
        val phase = inputs.voice.phase

        // No session, or one on its way out: there is nothing to hold open.
        if (!inputs.voice.active || phase == VoicePhase.STOPPING) return VoiceAlertness.DOZING

        // Mid-handshake. Parking a session that has not finished negotiating
        // would race the start path rather than save anything.
        if (phase == VoicePhase.STARTING) return VoiceAlertness.ACTIVE

        // A phone call owns the audio. Holding a session open through it spends
        // on a microphone the app does not have.
        if (!inputs.hasAudioFocus) return VoiceAlertness.DOZING

        // Someone is waiting on the user to speak, so the session stays open.
        // A muted microphone still wins: the user cannot answer through it,
        // so holding the outgoing half open would only spend on silence.
        if (holdsConversationOpen(inputs)) {
            return if (inputs.microphoneMuted) VoiceAlertness.PARKED else VoiceAlertness.ACTIVE
        }

        val rung = when {
            inputs.quietMillis >= thresholds.dozeAfterMillis -> VoiceAlertness.DOZING
            inputs.quietMillis >= thresholds.parkAfterMillis -> VoiceAlertness.PARKED
            inputs.microphoneMuted -> VoiceAlertness.PARKED
            else -> VoiceAlertness.ACTIVE
        }

        // Closing the session while Mike is talking would cut him off
        // mid-sentence. Parking only detaches the microphone, which a muted
        // user has already done, so it stays available.
        return if (phase == VoicePhase.SPEAKING && rung == VoiceAlertness.DOZING) VoiceAlertness.PARKED else rung
    }

    /** True while someone is waiting on the user to say something. */
    private fun holdsConversationOpen(inputs: IdleInputs): Boolean =
        inputs.run.approval != null || inputs.expectingReply
}
