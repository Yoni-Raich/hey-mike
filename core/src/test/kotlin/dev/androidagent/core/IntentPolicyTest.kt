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

package dev.androidagent.core

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

    @Test fun anActionOutsideTheAllowlistIsRefused() {
        val decision = IntentPolicy.evaluate("android.intent.action.DELETE", "https://example.com")
        assertEquals("action_not_allowed", (decision as IntentPolicy.Decision.Deny).reason)
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

    @Test fun viewNeedsAUriAndAnActionThatIsCompleteOnItsOwnDoesNot() {
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

    // ---- any action, structurally bounded ----

    @Test fun anAndroidFeatureReachedByIntentNeedsNoCodeChange() {
        // The reason there is no action allowlist: a timer, a settings screen
        // and a calendar insert are all just actions the phone already handles.
        for (action in listOf(
            "android.intent.action.SET_TIMER",
            "android.intent.action.SET_ALARM",
            "android.settings.WIRELESS_SETTINGS",
            "android.intent.action.INSERT",
            "android.intent.action.SEND",
        )) {
            assertTrue("$action should be allowed", IntentPolicy.evaluate(action, null) !is IntentPolicy.Decision.Deny)
        }
    }

    @Test fun everyBlockedActionIsRefused() {
        for (action in IntentPolicy.blockedActions) {
            val decision = IntentPolicy.evaluate(action, "tel:+972500000000")
            assertEquals(action, "action_not_allowed", (decision as IntentPolicy.Decision.Deny).reason)
            assertTrue(IntentPolicy.evaluate(action, null) is IntentPolicy.Decision.Deny)
        }
    }

    @Test fun anActionThatIsNotAnActionNameIsRefused() {
        for (action in listOf("SET TIMER", "intent:#Intent;end", "android.intent.action.VIEW;component=x")) {
            assertEquals(action, "action_malformed", (IntentPolicy.evaluate(action, null) as IntentPolicy.Decision.Deny).reason)
        }
    }

    @Test fun anAmountExtraAsksFirstLikeAnAmountInTheUri() {
        val extras = mapOf("com.example.extra.AMOUNT" to IntentExtra.Int32(10))
        assertTrue(IntentPolicy.evaluate("com.example.PAY", null, extras) is IntentPolicy.Decision.NeedsConfirmation)
        assertTrue(IntentPolicy.evaluate(view, "myapp://checkout", extras) is IntentPolicy.Decision.NeedsConfirmation)
    }

    @Test fun aShareSheetWithTextIsADraftAndDoesNotAsk() {
        // It opens a chooser or a composer; nothing leaves the phone until the
        // user sends it, which the device backend gates.
        val extras = mapOf("android.intent.extra.TEXT" to IntentExtra.Text("on my way"))
        assertTrue(IntentPolicy.evaluate("android.intent.action.SEND", null, extras) is IntentPolicy.Decision.Allow)
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
