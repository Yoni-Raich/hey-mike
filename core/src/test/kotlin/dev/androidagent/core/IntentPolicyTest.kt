package dev.androidagent.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IntentPolicyTest {

    private val view = "android.intent.action.VIEW"

    @Test fun everyBlockedSchemeIsRefused() {
        for (scheme in IntentPolicy.blockedSchemes) {
            val decision = IntentPolicy.evaluate(view, "$scheme://anything/at/all")
            assertTrue("$scheme should be denied", decision is IntentPolicy.Decision.Deny)
            assertEquals("scheme_blocked", (decision as IntentPolicy.Decision.Deny).reason)
        }
    }

    @Test fun aBlockedSchemeIsStillBlockedInAnyCase() {
        // Scheme comparison is case-insensitive by RFC, so an uppercase
        // spelling must not be a way around the list.
        val decision = IntentPolicy.evaluate(view, "CONTENT://media/external/images/1")
        assertEquals("scheme_blocked", (decision as IntentPolicy.Decision.Deny).reason)
    }

    @Test fun aPrivateAppDeepLinkIsAllowed() {
        // This is the case the layer exists for. A positive scheme allowlist
        // would break it, which is why the rule is structural instead.
        for (uri in listOf("waze://?ll=32.1,34.8&navigate=yes", "spotify:track:xyz", "tg://resolve?domain=x")) {
            val decision = IntentPolicy.evaluate(view, uri)
            assertTrue("$uri should be allowed", decision is IntentPolicy.Decision.Allow)
        }
    }

    @Test fun anyActivityActionIsReachableWithoutACodeChangePerFeature() {
        // A timer, a settings screen, a share: none of them is named anywhere
        // in the policy, and none of them has to be.
        for ((action, uri) in listOf(
            "android.intent.action.SET_TIMER" to null,
            "android.settings.WIRELESS_SETTINGS" to null,
            "android.intent.action.SEND" to null,
            "android.intent.action.SET_ALARM" to null,
        )) {
            val decision = IntentPolicy.evaluate(action, uri)
            assertTrue("$action should be allowed: $decision", decision is IntentPolicy.Decision.Allow)
        }
    }

    @Test fun somethingThatIsNotAnActionNameIsRefused() {
        for (action in listOf("SET_TIMER", "android.intent.action.SET TIMER", "a.b;rm -rf", "x".repeat(300) + ".y")) {
            val decision = IntentPolicy.evaluate(action, null)
            assertEquals(action, "action_malformed", (decision as IntentPolicy.Decision.Deny).reason)
        }
    }

    @Test fun placingACallDirectlyIsNotAnAvailableAction() {
        // ACTION_CALL dials with no confirmation screen. DIAL reaches the same
        // place and leaves the irreversible press to the user.
        val decision = IntentPolicy.evaluate("android.intent.action.CALL", "tel:+972500000000")
        assertEquals("action_not_allowed", (decision as IntentPolicy.Decision.Deny).reason)
        assertTrue(IntentPolicy.evaluate("android.intent.action.DIAL", "tel:+972500000000")
            is IntentPolicy.Decision.Allow)
    }

    @Test fun aMissingActionDefaultsToView() {
        val decision = IntentPolicy.evaluate(null, "https://example.com")
        assertEquals(view, (decision as IntentPolicy.Decision.Allow).action)
        assertEquals(view, (IntentPolicy.evaluate("  ", "https://example.com")
            as IntentPolicy.Decision.Allow).action)
    }

    @Test fun onlyViewNeedsAUri() {
        // Every other action may carry its whole request in extras.
        assertTrue(IntentPolicy.evaluate("android.intent.action.MAIN", null)
            is IntentPolicy.Decision.Allow)
        assertTrue(IntentPolicy.evaluate("android.intent.action.SET_TIMER", null)
            is IntentPolicy.Decision.Allow)
        assertEquals(
            "uri_required",
            (IntentPolicy.evaluate(view, null) as IntentPolicy.Decision.Deny).reason,
        )
        assertEquals(
            "uri_required",
            (IntentPolicy.evaluate(view, "   ") as IntentPolicy.Decision.Deny).reason,
        )
    }

    @Test fun aUriWithNoSchemeCannotBeResolved() {
        assertEquals(
            "uri_relative",
            (IntentPolicy.evaluate(view, "/settings/wifi") as IntentPolicy.Decision.Deny).reason,
        )
    }

    @Test fun anOverlongUriIsRefusedBeforeItIsParsed() {
        val long = "https://example.com/" + "a".repeat(3_000)
        assertEquals(
            "uri_too_long",
            (IntentPolicy.evaluate(view, long) as IntentPolicy.Decision.Deny).reason,
        )
    }

    @Test fun controlCharactersAndUnparseableTextAreRefused() {
        assertEquals(
            "uri_malformed",
            (IntentPolicy.evaluate(view, "https://example.com/\npath") as IntentPolicy.Decision.Deny).reason,
        )
        assertEquals(
            "uri_malformed",
            (IntentPolicy.evaluate(view, "http://exa mple.com") as IntentPolicy.Decision.Deny).reason,
        )
    }

    @Test fun openingAMessageDraftDoesNotAsk() {
        // A draft sends nothing. The approval belongs to the Send tap, which
        // the device backend gates; asking here approved a screen, not a send.
        for (uri in listOf(
            "mailto:a@b.com?subject=hi", "sms:+972500000000?body=hi", "smsto:+972500000000",
            "https://wa.me/972500000000?text=hi", "whatsapp://send?text=hello", "myapp://search?text=hello",
        )) {
            assertTrue("$uri should open without asking", IntentPolicy.evaluate(view, uri) is IntentPolicy.Decision.Allow)
        }
        assertTrue(
            IntentPolicy.evaluate("android.intent.action.SENDTO", "smsto:+972500000000")
                is IntentPolicy.Decision.Allow,
        )
    }

    @Test fun plainNavigationDoesNotAskFirst() {
        for (uri in listOf("tel:+972500000000", "geo:32.08,34.78?q=cafe", "https://maps.google.com/?q=cafe")) {
            assertTrue("$uri should be allowed", IntentPolicy.evaluate(view, uri) is IntentPolicy.Decision.Allow)
        }
    }

    @Test fun aPaymentAmountAsksFirstEvenOnHttps() {
        assertTrue(
            IntentPolicy.evaluate(view, "https://pay.example/checkout?amount=10")
                is IntentPolicy.Decision.NeedsConfirmation,
        )
        assertTrue(IntentPolicy.evaluate(view, "myapp://pay?amount=10") is IntentPolicy.Decision.NeedsConfirmation)
    }

    @Test fun forbiddenInputsRemainDenied() {
        assertTrue(
            IntentPolicy.evaluate(view, "content://media/external/images/1")
                is IntentPolicy.Decision.Deny,
        )
        assertTrue(
            IntentPolicy.evaluate("android.intent.action.CALL", "tel:+1")
                is IntentPolicy.Decision.Deny,
        )
        assertTrue(
            IntentPolicy.evaluate(view, "intent://scan/#Intent;scheme=zxing;end")
                is IntentPolicy.Decision.Deny,
        )
    }

    @Test fun sendToAlwaysAsksFirst() {
        val decision = IntentPolicy.evaluate("android.intent.action.SENDTO", "https://example.com/x")
        assertTrue(decision is IntentPolicy.Decision.NeedsConfirmation)
    }

    // ---- extras ----

    private fun extras(json: String): IntentPolicy.ExtrasParse =
        IntentPolicy.parseExtras(Json.parseToJsonElement(json))

    private fun parsed(json: String): Map<String, IntentExtra> =
        (extras(json) as IntentPolicy.ExtrasParse.Parsed).extras

    private fun invalid(json: String): String =
        (extras(json) as IntentPolicy.ExtrasParse.Invalid).deny.reason

    @Test fun aTimerCarriesItsLengthAsAnIntAndItsFlagAsABoolean() {
        val timer = parsed(
            """{"android.intent.extra.alarm.LENGTH":300,"android.intent.extra.alarm.SKIP_UI":true,
               "android.intent.extra.alarm.MESSAGE":"פסטה"}""",
        )
        assertEquals(IntentExtra.IntValue(300), timer["android.intent.extra.alarm.LENGTH"])
        assertEquals(IntentExtra.Flag(true), timer["android.intent.extra.alarm.SKIP_UI"])
        assertEquals(IntentExtra.Text("פסטה"), timer["android.intent.extra.alarm.MESSAGE"])
        val decision = IntentPolicy.evaluate("android.intent.action.SET_TIMER", null, timer)
        assertEquals(timer, (decision as IntentPolicy.Decision.Allow).extras)
    }

    @Test fun aReceiverThatReadsALongGetsOneWhenAsked() {
        // Android does not convert int to long: getLongExtra on an int returns
        // the default, so a calendar start time would silently be 0.
        val event = parsed("""{"beginTime":{"type":"long","value":1700000000000},"allDay":{"type":"boolean","value":false}}""")
        assertEquals(IntentExtra.LongValue(1_700_000_000_000L), event["beginTime"])
        assertEquals(IntentExtra.Flag(false), event["allDay"])
        assertEquals(IntentExtra.LongValue(5_000_000_000L), parsed("""{"big":5000000000}""")["big"])
        assertEquals(IntentExtra.DoubleValue(1.5), parsed("""{"ratio":1.5}""")["ratio"])
        assertEquals(IntentExtra.TextList(listOf("a@b.com")), parsed("""{"android.intent.extra.EMAIL":["a@b.com"]}""")["android.intent.extra.EMAIL"])
    }

    @Test fun nothingButAPlainValueIsAnExtra() {
        // No nesting, no null, no mixed arrays: nothing that could be read as
        // a Bundle or a Parcelable on the other side.
        for (json in listOf(
            """[1,2]""",
            """{"k":null}""",
            """{"k":{"nested":{"deeper":1}}}""",
            """{"k":[1,"a"]}""",
            """{"k":{"type":"uri","value":"https://x"}}""",
            """{"k":{"type":"int","value":"300"}}""",
            """{"bad key":1}""",
        )) {
            assertEquals(json, "extras_malformed", invalid(json))
        }
        val tooMany = (0..IntentPolicy.MAX_EXTRAS).joinToString(",", "{", "}") { "\"k$it\":1" }
        assertEquals("extras_malformed", invalid(tooMany))
    }

    @Test fun anExtraCannotSmuggleABlockedSchemeToTheReceivingApp() {
        for (json in listOf(
            """{"android.intent.extra.TEXT":"content://com.android.contacts/contacts"}""",
            """{"url":"  FILE:///data/data/dev.androidagent.app/files/x"}""",
            """{"links":["https://ok.example","intent://x#Intent;end"]}""",
        )) {
            assertEquals(json, "extra_blocked", invalid(json))
        }
        assertTrue(extras("""{"android.intent.extra.TEXT":"see https://example.com"}""") is IntentPolicy.ExtrasParse.Parsed)
    }

    @Test fun aSharedMessageFillsADraftAndDoesNotAsk() {
        val text = parsed("""{"android.intent.extra.TEXT":"on my way"}""")
        assertTrue(IntentPolicy.evaluate("android.intent.action.SEND", null, text) is IntentPolicy.Decision.Allow)
    }

    @Test fun anAmountInAnExtraAsksFirstLikeOneInTheUri() {
        val pay = parsed("""{"com.example.pay.AMOUNT":10}""")
        val decision = IntentPolicy.evaluate("com.example.pay.CHECKOUT", null, pay)
        assertTrue(decision is IntentPolicy.Decision.NeedsConfirmation)
        assertEquals(pay, (decision as IntentPolicy.Decision.NeedsConfirmation).extras)
        assertTrue(IntentPolicy.evaluate(view, "myapp://pay", pay) is IntentPolicy.Decision.NeedsConfirmation)
    }

    @Test fun extrasDoNotLiftTheBlockOnPlacingACall() {
        val decision = IntentPolicy.evaluate("android.intent.action.CALL", "tel:+1", parsed("""{"x":1}"""))
        assertEquals("action_not_allowed", (decision as IntentPolicy.Decision.Deny).reason)
    }

    // ---- prefilled message bodies ----

    @Test fun textIsPercentEncodedIntoTheUriSoASpaceDoesNotTruncateTheMessage() {
        // A hand-built "?text=on my way" either fails URI parsing or loses
        // everything after the first separator, which is why the tool composes
        // it instead of trusting the model to encode.
        val composed = IntentPolicy.withText("https://wa.me/972500000000", "on my way & almost there")
        val uri = (composed as IntentPolicy.Decision.Allow).uri
        assertEquals(
            "https://wa.me/972500000000?text=on%20my%20way%20%26%20almost%20there",
            uri,
        )
        // And it survives the parse that a raw one would have failed.
        assertTrue(IntentPolicy.evaluate("android.intent.action.VIEW", uri) is IntentPolicy.Decision.Allow)
        assertTrue(
            IntentPolicy.evaluate("android.intent.action.VIEW", "https://wa.me/972500000000?text=on my way")
                is IntentPolicy.Decision.Deny,
        )
    }

    @Test fun composedTextCannotHideAPayment() {
        // A body fills a draft; it must never make a payment link look like one.
        val uri = (IntentPolicy.withText("https://pay.example/checkout?amount=10", null) as IntentPolicy.Decision.Allow).uri
        assertTrue(IntentPolicy.evaluate("android.intent.action.VIEW", uri) is IntentPolicy.Decision.NeedsConfirmation)
    }

    @Test fun textJoinsAnExistingQueryWithAnAmpersandNotASecondQuestionMark() {
        val uri = (IntentPolicy.withText("whatsapp://send?phone=972500000000", "hi") as IntentPolicy.Decision.Allow).uri
        assertEquals("whatsapp://send?phone=972500000000&text=hi", uri)
    }

    @Test fun noTextLeavesTheUriExactlyAsItWas() {
        assertEquals(
            "https://wa.me/972500000000",
            (IntentPolicy.withText("https://wa.me/972500000000", null) as IntentPolicy.Decision.Allow).uri,
        )
        assertEquals(
            "https://wa.me/972500000000",
            (IntentPolicy.withText("https://wa.me/972500000000", "   ") as IntentPolicy.Decision.Allow).uri,
        )
    }

    @Test fun textIsRefusedWhenItWouldBeAmbiguousOrUnbounded() {
        // Two payloads: overwriting one would send something unintended.
        assertEquals(
            "text_conflict",
            (IntentPolicy.withText("https://wa.me/1?text=already", "other") as IntentPolicy.Decision.Deny).reason,
        )
        assertEquals(
            "uri_required",
            (IntentPolicy.withText(null, "hello") as IntentPolicy.Decision.Deny).reason,
        )
        assertEquals(
            "text_too_long",
            (IntentPolicy.withText("https://wa.me/1", "x".repeat(IntentPolicy.MAX_TEXT_CHARS + 1))
                as IntentPolicy.Decision.Deny).reason,
        )
        assertEquals(
            "uri_has_fragment",
            (IntentPolicy.withText("https://example.com/page#part", "hello") as IntentPolicy.Decision.Deny).reason,
        )
    }

}
