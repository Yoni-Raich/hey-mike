package dev.androidagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModeControllerTest {
    private var now = 1_000L
    private fun message(text: String, direction: ChatMessageDirection) = VisibleChatMessage(text, direction)
    private fun snapshot(
        messages: List<VisibleChatMessage>,
        title: String = "Family",
        pkg: String? = "com.whatsapp",
        windowId: Int? = 7,
        textAvailable: Boolean = true,
    ) = ForegroundChatSnapshot(pkg, windowId, title, messages, textAvailable)

    private fun start(subject: ChatModeController, baseline: ForegroundChatSnapshot) {
        assertTrue(subject.offer("voice-1", baseline.packageName!!, baseline.chatTitle!!))
        assertTrue(subject.startWatching(baseline))
    }

    @Test fun explicitOfferAndApprovalStartTheWatch() {
        val subject = ChatModeController(nowMs = { now })
        val baseline = snapshot(listOf(message("sent", ChatMessageDirection.OUTGOING)))
        assertTrue(subject.offer("voice-1", baseline.packageName!!, baseline.chatTitle!!))
        assertEquals(ChatModePhase.OFFERED, subject.state.value.phase)
        assertTrue(subject.startWatching(baseline))
        assertEquals(ChatModePhase.WATCHING, subject.state.value.phase)
    }

    @Test fun repeatedEqualMessagesRemainDistinct() {
        val sent = message("same", ChatMessageDirection.OUTGOING)
        val reply = message("same", ChatMessageDirection.INCOMING)
        val subject = ChatModeController(nowMs = { now })
        start(subject, snapshot(listOf(sent)))

        val update = subject.observe(snapshot(listOf(sent, reply))) as ChatModeObservation.Incoming
        assertEquals(listOf(reply), update.messages)
        assertEquals(1, update.contextRevision)
        assertEquals(listOf(reply), subject.consumePending(update.contextRevision))
        assertEquals(ChatModePhase.WATCHING, subject.state.value.phase)
    }

    @Test fun outgoingMessagesAdvanceBaselineWithoutInterrupting() {
        val first = message("one", ChatMessageDirection.OUTGOING)
        val second = message("two", ChatMessageDirection.OUTGOING)
        val subject = ChatModeController(nowMs = { now })
        start(subject, snapshot(listOf(first)))
        assertEquals(ChatModeObservation.NoChange, subject.observe(snapshot(listOf(first, second))))
        assertEquals(ChatModePhase.WATCHING, subject.state.value.phase)
    }

    @Test fun changingChatOrLeavingWhatsAppStopsTheWatch() {
        val first = message("one", ChatMessageDirection.OUTGOING)
        val subject = ChatModeController(nowMs = { now })
        start(subject, snapshot(listOf(first)))
        assertEquals(
            ChatModeStopReason.CHAT_CHANGED,
            (subject.observe(snapshot(listOf(first), title = "Other")) as ChatModeObservation.Stopped).reason,
        )
        assertFalse(subject.state.value.active)
    }

    @Test fun unknownDirectionOrLostHistoryFailsClosed() {
        val first = message("one", ChatMessageDirection.OUTGOING)
        val subject = ChatModeController(nowMs = { now })
        start(subject, snapshot(listOf(first)))
        val stopped = subject.observe(snapshot(listOf(message("unknown", ChatMessageDirection.UNKNOWN))))
        assertEquals(ChatModeStopReason.HISTORY_UNSTABLE, (stopped as ChatModeObservation.Stopped).reason)
        assertEquals(ChatModePhase.UNSUPPORTED, subject.state.value.phase)
    }

    @Test fun fiveMinutesWithoutUserOrMessageActivityExpires() {
        val subject = ChatModeController(nowMs = { now })
        start(subject, snapshot(listOf(message("one", ChatMessageDirection.OUTGOING))))
        now += ChatModeController.DEFAULT_IDLE_TIMEOUT_MS - 1
        assertFalse(subject.expireIfIdle())
        now++
        assertTrue(subject.expireIfIdle())
        assertEquals(ChatModeStopReason.TIMEOUT, subject.state.value.stopReason)
    }

    @Test fun anUnansweredOfferAlsoExpires() {
        val subject = ChatModeController(nowMs = { now })
        assertTrue(subject.offer("voice-1", "com.whatsapp", "Family"))

        now += ChatModeController.DEFAULT_IDLE_TIMEOUT_MS

        assertTrue(subject.expireIfIdle())
        assertEquals(ChatModePhase.OFF, subject.state.value.phase)
    }

    @Test fun losingAccessibilityStopsWithTheSpecificReason() {
        val subject = ChatModeController(nowMs = { now })
        start(subject, snapshot(listOf(message("one", ChatMessageDirection.OUTGOING))))

        val result = subject.observe(ForegroundChatSnapshot(null, null, null, emptyList(), textAvailable = false))

        assertEquals(ChatModeStopReason.ACCESSIBILITY_LOST, (result as ChatModeObservation.Stopped).reason)
    }

    @Test fun directAdbWritesAreBlockedOnlyWhileWatching() {
        val subject = ChatModeController(nowMs = { now })
        assertTrue(subject.canDispatchTool("shell"))
        start(subject, snapshot(listOf(message("one", ChatMessageDirection.OUTGOING))))

        assertFalse(subject.canDispatchTool("shell"))
        assertFalse(subject.canDispatchTool("install_apk"))
        assertTrue(subject.canDispatchTool("read_ui"))
        assertTrue(subject.canDispatchTool("tap"))
    }

    @Test fun staleContextCannotAuthorizeAnAction() {
        val first = message("one", ChatMessageDirection.OUTGOING)
        val reply = message("reply", ChatMessageDirection.INCOMING)
        val subject = ChatModeController(nowMs = { now })
        start(subject, snapshot(listOf(first)))
        assertTrue(subject.isCurrent("voice-1", 0))
        subject.observe(snapshot(listOf(first, reply)))
        assertFalse(subject.isCurrent("voice-1", 0))
        assertTrue(subject.isCurrent("voice-1", 1))
    }

    @Test fun consentDoesNotTreatSendCommandsAsChatModeApproval() {
        assertEquals(true, ChatModeConsentReply.parse("כן"))
        assertEquals(false, ChatModeConsentReply.parse("no"))
        assertEquals(null, ChatModeConsentReply.parse("send it"))
        assertEquals(null, ChatModeConsentReply.parse("yes, but wait"))
    }

    @Test fun exitCommandMustBeExplicit() {
        assertTrue(ChatModeExitReply.matches("Exit Chat Mode"))
        assertTrue(ChatModeExitReply.matches("כבה מצב צ׳אט"))
        assertFalse(ChatModeExitReply.matches("What does chat mode do?"))
        assertFalse(ChatModeExitReply.matches("stop"))
    }
}
