package dev.androidagent.core

import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale

/**
 * What the agent is allowed to launch, and what it has to ask about first.
 *
 * Intent text is model-supplied and frequently derived from screen content,
 * which the harness already treats as untrusted — a phone number read off a
 * page is not a phone number the user asked to dial. So every intent this
 * project launches is checked here first.
 *
 * Pure JVM on purpose: this is the security boundary for the intent layer,
 * and it is worth more as something covered by fast unit tests than as
 * something wired into `android.net.Uri`.
 */
object IntentPolicy {

    /**
     * Schemes that are refused outright.
     *
     * A positive allowlist was the first design and it does not survive
     * contact with the feature: app deep links use private schemes — `waze:`,
     * `spotify:`, `tg:` — and enumerating them would block exactly the case
     * this layer exists to serve. So the rule is structural instead: anything
     * that reads local data, injects a component, or executes is refused, and
     * ordinary opaque app schemes are allowed.
     *
     * - `file`, `content`, `android_resource`: read the device's own storage
     *   and providers. A model-supplied one is a data-exfiltration primitive.
     * - `intent`: Android's own serialisation format. It can name an arbitrary
     *   component, action, category and extras inside a single string, which
     *   would route straight around every check below.
     * - `javascript`, `data`: execute in whatever browser resolves them.
     * - `jar`: fetches and loads code.
     */
    val blockedSchemes: Set<String> = setOf(
        "file",
        "content",
        "android_resource",
        "intent",
        "android-app",
        "javascript",
        "data",
        "jar",
    )

    /**
     * Actions the agent may name.
     *
     * `ACTION_CALL` is deliberately absent: it places a call with no dialer
     * confirmation. `ACTION_DIAL` reaches the same screen and leaves the last
     * press to the user, so the capability is kept and the irreversible half
     * is not.
     */
    val allowedActions: Set<String> = setOf(
        "android.intent.action.VIEW",
        "android.intent.action.DIAL",
        "android.intent.action.SENDTO",
        "android.intent.action.SEARCH",
        "android.intent.action.WEB_SEARCH",
        "android.intent.action.MAIN",
    )

    /** Schemes that open a message draft to someone. Opening one sends nothing. */
    private val messagingSchemes: Set<String> = setOf("sms", "smsto", "mms", "mmsto", "mailto")

    /**
     * Query keys that carry a payload rather than a destination. Two payloads
     * in one uri are ambiguous, so [withText] refuses to add a second.
     */
    private val payloadKeys: Set<String> = setOf(
        "body", "text", "subject", "message", "amount", "cc", "bcc",
    )

    private const val MAX_URI_CHARS = 2_000

    /**
     * Longest prefilled message body accepted.
     *
     * Percent-encoding can triple a string, so this is well under
     * [MAX_URI_CHARS] to leave room for the destination it is attached to.
     */
    const val MAX_TEXT_CHARS = 400

    sealed interface Decision {
        /** Safe to launch without asking. */
        data class Allow(val uri: String?, val action: String) : Decision

        /**
         * Launchable, but it would send, pay, or otherwise act on someone
         * else's behalf. [what] is the sentence to put to the user.
         */
        data class NeedsConfirmation(val uri: String?, val action: String, val what: String) : Decision

        /** Refused. [reason] is a typed error code, [message] is for the model. */
        data class Deny(val reason: String, val message: String) : Decision
    }

    /**
     * Decide what to do with a proposed intent.
     *
     * @param action fully-qualified action, or null to default to VIEW.
     * @param uri the data URI, or null for an action that needs none.
     */
    /**
     * Attach a prefilled message body to a deep link.
     *
     * A model that hand-builds `?text=` gets the encoding wrong: an unencoded
     * space or `&` either truncates the message at the first separator or
     * fails [URI] parsing outright, which is why the caller hands over plain
     * text and this composes it. The composed uri still goes through
     * [evaluate]; the text only fills a draft, and the send is gated where the
     * send button is pressed.
     */
    fun withText(uri: String?, text: String?): Decision {
        val body = text?.takeIf { it.isNotBlank() } ?: return Decision.Allow(uri, "")
        val destination = uri?.trim()?.takeUnless { it.isEmpty() }
            ?: return Decision.Deny(
                "uri_required",
                "text needs a uri to attach to. Pass the deep link that opens the conversation.",
            )
        if (body.length > MAX_TEXT_CHARS) {
            return Decision.Deny(
                "text_too_long",
                "text is ${body.length} characters, above the $MAX_TEXT_CHARS limit.",
            )
        }
        if (destination.contains('#')) {
            return Decision.Deny(
                "uri_has_fragment",
                "A uri with a fragment cannot carry a query payload. Drop the \"#\" part.",
            )
        }
        val existing = runCatching { queryOf(URI(destination)) }.getOrDefault(emptyMap())
        if (payloadKeys.any { it in existing }) {
            // Two payloads is ambiguous: silently overwriting one would send
            // something the caller did not mean to send.
            return Decision.Deny(
                "text_conflict",
                "That uri already carries a payload. Pass text, or put it in the uri, not both.",
            )
        }
        val encoded = try {
            URLEncoder.encode(body, "UTF-8").replace("+", "%20")
        } catch (error: UnsupportedEncodingException) {
            return Decision.Deny("text_malformed", "text could not be encoded.")
        }
        val separator = if (destination.contains('?')) "&" else "?"
        return Decision.Allow("$destination${separator}text=$encoded", "")
    }

    fun evaluate(action: String?, uri: String?): Decision {
        val resolvedAction = action?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "android.intent.action.VIEW"
        if (resolvedAction !in allowedActions) {
            return Decision.Deny(
                "action_not_allowed",
                "\"$resolvedAction\" is not an action this agent may send. Allowed: " +
                    allowedActions.sorted().joinToString(", ") + ".",
            )
        }
        if (uri == null || uri.isBlank()) {
            return if (resolvedAction == "android.intent.action.MAIN") {
                Decision.Allow(null, resolvedAction)
            } else {
                Decision.Deny(
                    "uri_required",
                    "\"$resolvedAction\" needs a uri.",
                )
            }
        }
        val trimmed = uri.trim()
        if (trimmed.length > MAX_URI_CHARS) {
            return Decision.Deny(
                "uri_too_long",
                "The uri is ${trimmed.length} characters, above the $MAX_URI_CHARS limit.",
            )
        }
        if (trimmed.any { it == '\n' || it == '\r' || it.code < 0x20 }) {
            return Decision.Deny(
                "uri_malformed",
                "The uri contains control characters.",
            )
        }
        // The scheme is read off the raw text before parsing, because some
        // blocked schemes are not valid URIs at all — `android_resource` has an
        // underscore, which RFC 3986 forbids and java.net.URI rejects. Parsing
        // first would report those as merely malformed, which reads as "try a
        // different spelling" rather than "this is not allowed".
        declaredSchemeOf(trimmed)?.let { declared ->
            if (declared in blockedSchemes) {
                return Decision.Deny(
                    "scheme_blocked",
                    "\"$declared:\" is not a scheme this agent may open. It can read local data or " +
                        "name an arbitrary component, which would bypass the checks on this tool.",
                )
            }
        }
        val parsed = try {
            URI(trimmed)
        } catch (error: URISyntaxException) {
            return Decision.Deny("uri_malformed", "The uri could not be parsed: ${error.reason}")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            ?: return Decision.Deny(
                "uri_relative",
                "The uri has no scheme. A relative uri cannot be resolved to an app.",
            )
        val sideEffect = describeSideEffect(parsed, scheme, resolvedAction)
        return if (sideEffect == null) {
            Decision.Allow(trimmed, resolvedAction)
        } else {
            Decision.NeedsConfirmation(trimmed, resolvedAction, sideEffect)
        }
    }

    /**
     * The scheme as written, lowercased, or null when the text declares none.
     *
     * Deliberately laxer than RFC 3986 about what characters a scheme may
     * contain: the job here is to recognise a scheme someone is trying to
     * sneak past the list, not to validate it.
     */
    private fun declaredSchemeOf(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val candidate = uri.substring(0, colon)
        if (candidate.any { it == '/' || it == '?' || it == '#' }) return null
        return candidate.lowercase(Locale.ROOT)
    }

    /**
     * One sentence naming the side effect, or null when the intent only
     * navigates.
     *
     * Opening a message draft is not a side effect. `wa.me/…?text=`, `smsto:`
     * and `mailto:` land on a composer with the text typed in, and nothing
     * leaves the phone until Send is pressed. Asking here made the user approve
     * a screen that sends nothing, while the tap that does send went unasked;
     * the send itself is gated where it happens, by the device backend.
     */
    private fun describeSideEffect(uri: URI, scheme: String, action: String): String? {
        val query = queryOf(uri)
        if (query.containsKey("amount")) {
            return "Start a payment."
        }
        if (scheme in messagingSchemes) {
            return null
        }
        if (action == "android.intent.action.SENDTO") {
            // SENDTO to anything but a known draft scheme may hand the data
            // straight to an app that acts on it.
            return "Send to a recipient through another app."
        }
        return null
    }

    /** Query keys, lowercased. Values are never inspected or logged. */
    private fun queryOf(uri: URI): Map<String, String> {
        val raw = uri.query ?: uri.schemeSpecificPart?.substringAfter('?', "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore('=').lowercase(Locale.ROOT)
                key to pair.substringAfter('=', "")
            }
    }
}
