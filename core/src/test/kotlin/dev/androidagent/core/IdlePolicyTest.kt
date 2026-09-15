package dev.androidagent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdlePolicyTest {
    private val thresholds = IdleThresholds(parkAfterMillis = 45_000L, dozeAfterMillis = 180_000L)

    private fun listening(
        quietMillis: Long = 0L,
        run: RunState = RunState(),
        microphoneMuted: Boolean = false,
        expectingReply: Boolean = false,
        hasAudioFocus: Boolean = true,
    ) = IdleInputs(
        voice = VoiceState(phase = VoicePhase.LISTENING, threadId = "thread-1"),
        run = run,
        quietMillis = quietMillis,
        microphoneMuted = microphoneMuted,
        expectingReply = expectingReply,
        hasAudioFocus = hasAudioFocus,
    )

    private fun alertness(inputs: IdleInputs) = IdlePolicy.alertness(inputs, thresholds)

    private fun pendingApproval() = EngineEvent.Approval(
        requestId = "request-1",
        method = "open_intent",
        details = buildJsonObject { put("reason", "Send a WhatsApp message?") },
    )

    @Test fun aConversationInUseStaysActive() {
        assertEquals(VoiceAlertness.ACTIVE, alertness(listening(quietMillis = 5_000L)))
    }

    @Test fun quietPassesThroughParkedOnItsWayToDozing() {
        assertEquals(VoiceAlertness.ACTIVE, alertness(listening(quietMillis = 44_999L)))
        assertEquals(VoiceAlertness.PARKED, alertness(listening(quietMillis = 45_000L)))
        assertEquals(VoiceAlertness.PARKED, alertness(listening(quietMillis = 179_999L)))
        assertEquals(VoiceAlertness.DOZING, alertness(listening(quietMillis = 180_000L)))
        assertEquals(VoiceAlertness.DOZING, alertness(listening(quietMillis = 3_600_000L)))
    }

    @Test fun mutingParksTheMicrophoneWithoutWaitingForQuiet() {
        assertEquals(VoiceAlertness.PARKED, alertness(listening(quietMillis = 0L, microphoneMuted = true)))
    }

    @Test fun aMutedConversationStillDozesOnceItIsQuietEnough() {
        assertEquals(
            VoiceAlertness.DOZING,
            alertness(listening(quietMillis = 180_000L, microphoneMuted = true)),
        )
    }

    @Test fun aPendingApprovalHoldsTheConversationOpen() {
        val waiting = listening(
            quietMillis = 3_600_000L,
            run = RunState(
                phase = RunPhase.TOOL,
                approval = pendingApproval(),
            ),
        )
        assertEquals(VoiceAlertness.ACTIVE, alertness(waiting))
    }

    @Test fun aQuestionFromMikeHoldsTheConversationOpen() {
        assertEquals(
            VoiceAlertness.ACTIVE,
            alertness(listening(quietMillis = 3_600_000L, expectingReply = true)),
        )
    }

    @Test fun aMutedMicrophoneOutranksAHold() {
        // The user cannot answer an approval through a microphone they silenced,
        // so the session is kept but the outgoing half is not.
        assertEquals(
            VoiceAlertness.PARKED,
            alertness(
                listening(
                    quietMillis = 0L,
                    microphoneMuted = true,
                    run = RunState(
                        phase = RunPhase.TOOL,
                        approval = pendingApproval(),
                    ),
                ),
            ),
        )
        assertEquals(
            VoiceAlertness.PARKED,
            alertness(listening(quietMillis = 0L, microphoneMuted = true, expectingReply = true)),
        )
    }

    @Test fun aLongRunIsNotAReasonToStayAwake() {
        // The timer and workflow case: nothing to hear until it finishes, and
        // finishing is what wakes the conversation.
        for (phase in listOf(RunPhase.STARTING, RunPhase.THINKING, RunPhase.TOOL, RunPhase.CONTROLLING)) {
            assertEquals(
                "run phase $phase should not hold the microphone open",
                VoiceAlertness.DOZING,
                alertness(listening(quietMillis = 180_000L, run = RunState(phase = phase))),
            )
        }
    }

    @Test fun aRunThatIsBeingSteeredIsStillActive() {
        // Quiet is what decides, so a run the user is talking through keeps its
        // microphone; only a run nobody has spoken to in minutes loses it.
        assertEquals(
            VoiceAlertness.ACTIVE,
            alertness(listening(quietMillis = 2_000L, run = RunState(phase = RunPhase.CONTROLLING))),
        )
    }

    @Test fun losingAudioFocusDozesImmediately() {
        assertEquals(
            VoiceAlertness.DOZING,
            alertness(listening(quietMillis = 0L, hasAudioFocus = false)),
        )
    }

    @Test fun losingAudioFocusOutranksEveryHold() {
        val inCall = listening(
            quietMillis = 0L,
            hasAudioFocus = false,
            expectingReply = true,
            run = RunState(
                phase = RunPhase.TOOL,
                approval = pendingApproval(),
            ),
        )
        assertEquals(VoiceAlertness.DOZING, alertness(inCall))
    }

    @Test fun mikeIsNeverCutOffMidSentence() {
        val speaking = IdleInputs(
            voice = VoiceState(phase = VoicePhase.SPEAKING, threadId = "thread-1"),
            quietMillis = 3_600_000L,
        )
        assertEquals(VoiceAlertness.PARKED, alertness(speaking))
    }

    @Test fun aSessionStillNegotiatingIsNeverParked() {
        val starting = IdleInputs(
            voice = VoiceState(phase = VoicePhase.STARTING, threadId = "thread-1"),
            quietMillis = 3_600_000L,
            microphoneMuted = true,
        )
        assertEquals(VoiceAlertness.ACTIVE, alertness(starting))
    }

    @Test fun thereIsNothingToHoldOpenWithoutASession() {
        for (phase in listOf(VoicePhase.IDLE, VoicePhase.ERROR, VoicePhase.STOPPING)) {
            assertEquals(
                "voice $phase has no session to hold open",
                VoiceAlertness.DOZING,
                alertness(IdleInputs(voice = VoiceState(phase = phase), quietMillis = 0L)),
            )
        }
    }

    @Test fun defaultThresholdsParkBeforeTheyDoze() {
        val defaults = IdleThresholds()
        assertEquals(
            VoiceAlertness.PARKED,
            IdlePolicy.alertness(listening(quietMillis = defaults.parkAfterMillis), defaults),
        )
        assertEquals(
            VoiceAlertness.DOZING,
            IdlePolicy.alertness(listening(quietMillis = defaults.dozeAfterMillis), defaults),
        )
    }

    @Test fun thresholdsRejectAnImpossibleLadder() {
        assertThrows(IllegalArgumentException::class.java) { IdleThresholds(parkAfterMillis = 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            IdleThresholds(parkAfterMillis = 60_000L, dozeAfterMillis = 30_000L)
        }
    }
}
