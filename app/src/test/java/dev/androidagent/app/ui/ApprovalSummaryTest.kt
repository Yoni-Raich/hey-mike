package dev.androidagent.app.ui

import dev.androidagent.core.EngineEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalSummaryTest {

    private fun approval(uri: String, pkg: String? = null, reason: String = "Open a prefilled message.") =
        EngineEvent.Approval(
            requestId = "r",
            method = "open_intent",
            details = buildJsonObject {
                put("reason", reason)
                put("action", "android.intent.action.VIEW")
                put("uri", uri)
                pkg?.let { put("package", it) }
            },
        )

    @Test fun aWhatsAppMessageNamesTheRecipientAndTheDecodedText() {
        // The card that failed on the phone showed this exact request as raw JSON.
        val summary = approval("https://wa.me/972587160002?text=%D7%94%D7%99%D7%99", "com.whatsapp").summary()
        assertEquals("Send a WhatsApp message?", summary.headline)
        assertEquals(listOf("To" to "+972587160002", "Message" to "היי"), summary.lines)
    }

    @Test fun anIntentWithNoUriNamesItsAction() {
        val summary = EngineEvent.Approval(
            requestId = "p",
            method = "open_intent",
            details = buildJsonObject {
                put("reason", "Start a payment. (AMOUNT=10)")
                put("action", "com.example.pay.CHECKOUT")
                put("package", "com.example.pay")
            },
        ).summary()
        assertEquals("Open this?", summary.headline)
        assertEquals(
            listOf("What" to "Start a payment. (AMOUNT=10)", "Action" to "CHECKOUT", "App" to "com.example.pay"),
            summary.lines,
        )
    }

    @Test fun anSmsShowsTheNumberAndBody() {
        val summary = approval("smsto:+972500000000?body=on%20my%20way%20%26%20close").summary()
        assertEquals("Send an SMS?", summary.headline)
        assertEquals(listOf("To" to "+972500000000", "Message" to "on my way & close"), summary.lines)
    }

    @Test fun aSendNamesTheChatAndOffersBothAlwaysChoices() {
        val summary = EngineEvent.Approval(
            requestId = "s",
            method = "send_message",
            details = buildJsonObject {
                put("kind", "send")
                put("app", "WhatsApp")
                put("package", "com.whatsapp")
                put("recipient", "My Wife")
                put("message", "hi")
            },
        ).summary()
        assertEquals("Send this WhatsApp message?", summary.headline)
        assertEquals(listOf("To" to "My Wife", "Message" to "hi"), summary.lines)
        assertEquals("WhatsApp", summary.sendApp)
        assertEquals("My Wife", summary.sendRecipient)
    }

    @Test fun anythingElseFallsBackToTheReasonAndLink() {
        val summary = approval("https://pay.example/?amount=10", reason = "Start a payment.").summary()
        assertEquals("Open this?", summary.headline)
        assertTrue(summary.lines.contains("What" to "Start a payment."))
        assertTrue(summary.lines.contains("Link" to "https://pay.example/?amount=10"))
    }
}
