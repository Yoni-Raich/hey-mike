package dev.androidagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale

/**
 * One extra on an intent, as a type Android's `Intent.putExtra` takes.
 *
 * Closed on purpose: there is no Uri, Parcelable or Bundle here, so an extra
 * can pass a value to another app but never a grant on this app's data.
 */
sealed interface IntentExtra {
    data class Text(val value: String) : IntentExtra
    data class Flag(val value: Boolean) : IntentExtra
    data class IntValue(val value: Int) : IntentExtra
    data class LongValue(val value: Long) : IntentExtra
    data class DoubleValue(val value: Double) : IntentExtra
    data class TextList(val values: List<String>) : IntentExtra

    /** How the value reads on an approval card. */
    fun display(): String = when (this) {
        is Text -> value
        is Flag -> value.toString()
        is IntValue -> value.toString()
        is LongValue -> value.toString()
        is DoubleValue -> value.toString()
        is TextList -> values.joinToString(", ")
    }
}

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
     * Actions the agent may not name.
     *
     * The rule is structural, the same way [blockedSchemes] is: an allowlist
     * of actions meant a code change for every Android feature — a timer, a
     * calendar event, a share — and every one the list did not name was simply
     * unreachable. What an activity intent can do is bounded without it: this
     * layer launches activities only, never names a component, never grants a
     * uri permission, and carries only plain [IntentExtra] values, so the
     * target app still enforces its own permissions and shows its own screens.
     *
     * What remains are the actions that act the moment they arrive, with no
     * screen for the user to back out of. `ACTION_CALL` places a call with no
     * dialer confirmation; `ACTION_DIAL` reaches the same screen and leaves the
     * last press to the user.
     */
    val blockedActions: Set<String> = setOf(
        "android.intent.action.CALL",
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.CALL_EMERGENCY",
    )

    private const val MAX_ACTION_CHARS = 200
    private val ACTION_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** At most this many extras on one intent. */
    const val MAX_EXTRAS = 16
    private const val MAX_EXTRA_KEY_CHARS = 128
    private const val MAX_EXTRA_TEXT_CHARS = 2_000
    private const val MAX_EXTRA_LIST_ITEMS = 16
    private val EXTRA_KEY_RE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")

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
        data class Allow(
            val uri: String?,
            val action: String,
            val extras: Map<String, IntentExtra> = emptyMap(),
        ) : Decision

        /**
         * Launchable, but it would send, pay, or otherwise act on someone
         * else's behalf. [what] is the sentence to put to the user.
         */
        data class NeedsConfirmation(
            val uri: String?,
            val action: String,
            val what: String,
            val extras: Map<String, IntentExtra> = emptyMap(),
        ) : Decision

        /** Refused. [reason] is a typed error code, [message] is for the model. */
        data class Deny(val reason: String, val message: String) : Decision
    }

    /** The outcome of reading model-supplied extras. */
    sealed interface ExtrasParse {
        data class Parsed(val extras: Map<String, IntentExtra>) : ExtrasParse
        data class Invalid(val deny: Decision.Deny) : ExtrasParse
    }

    /**
     * Read the `extras` argument of an intent tool.
     *
     * A value is a plain JSON string, boolean, number or array of strings. A
     * whole number becomes an int when it fits and a long otherwise; a receiver
     * that reads `getLongExtra` for a small value (a calendar's start time is
     * one) needs `{"type":"long","value":...}` to say so, because Android does
     * not convert between the two. Nothing here can carry a Uri, a Parcelable
     * or a Bundle, which is what keeps an extra from handing another app access
     * to this app's data.
     */
    fun parseExtras(raw: JsonElement?): ExtrasParse {
        if (raw == null || raw is JsonNull) return ExtrasParse.Parsed(emptyMap())
        val obj = raw as? JsonObject ?: return invalidExtras("extras must be an object of name to value.")
        if (obj.size > MAX_EXTRAS) return invalidExtras("${obj.size} extras is above the $MAX_EXTRAS limit.")
        val parsed = LinkedHashMap<String, IntentExtra>()
        for ((key, value) in obj) {
            if (key.length > MAX_EXTRA_KEY_CHARS || !EXTRA_KEY_RE.matches(key)) {
                return invalidExtras("\"$key\" is not an extra name. Use a name like android.intent.extra.TEXT.")
            }
            val extra = when (value) {
                is JsonObject -> typedExtra(key, value)
                else -> inferredExtra(key, value)
            } ?: return invalidExtras(
                "extra \"$key\" must be a string, boolean, number, array of strings, or " +
                    "{\"type\":\"int|long|double|boolean|string|string_array\",\"value\":...}.",
            )
            unsafeText(extra)?.let { return ExtrasParse.Invalid(Decision.Deny("extra_blocked", "extra \"$key\": $it")) }
            parsed[key] = extra
        }
        return ExtrasParse.Parsed(parsed)
    }

    private fun invalidExtras(message: String) = ExtrasParse.Invalid(Decision.Deny("extras_malformed", message))

    private fun inferredExtra(key: String, value: JsonElement): IntentExtra? = when (value) {
        is JsonArray -> stringList(value)
        is JsonPrimitive -> when {
            value is JsonNull -> null
            value.isString -> IntentExtra.Text(value.content)
            value.booleanOrNull != null -> IntentExtra.Flag(value.boolean)
            value.content.toLongOrNull() != null -> value.content.toLong().let { number ->
                if (number in Int.MIN_VALUE..Int.MAX_VALUE) IntentExtra.IntValue(number.toInt()) else IntentExtra.LongValue(number)
            }
            value.doubleOrNull != null -> IntentExtra.DoubleValue(value.double)
            else -> null
        }
        else -> null
    }

    private fun typedExtra(key: String, value: JsonObject): IntentExtra? {
        val type = (value["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase(Locale.ROOT) ?: return null
        val inner = value["value"] ?: return null
        val primitive = inner as? JsonPrimitive
        return when (type) {
            "string" -> primitive?.takeIf { it.isString }?.content?.let(IntentExtra::Text)
            "boolean" -> primitive?.takeIf { !it.isString }?.booleanOrNull?.let(IntentExtra::Flag)
            "int" -> primitive?.takeIf { !it.isString }?.content?.toIntOrNull()?.let(IntentExtra::IntValue)
            "long" -> primitive?.takeIf { !it.isString }?.content?.toLongOrNull()?.let(IntentExtra::LongValue)
            "double" -> primitive?.takeIf { !it.isString }?.doubleOrNull?.let(IntentExtra::DoubleValue)
            "string_array" -> (inner as? JsonArray)?.let(::stringList)
            else -> null
        }
    }

    private fun stringList(array: JsonArray): IntentExtra? {
        if (array.size > MAX_EXTRA_LIST_ITEMS) return null
        val items = array.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
        return IntentExtra.TextList(items)
    }

    /**
     * Why a text extra is refused, or null.
     *
     * Many receivers read a string extra as a link and open it. A string that
     * names a blocked scheme would reach the same local data [blockedSchemes]
     * keeps a uri away from, so it is refused the same way.
     */
    private fun unsafeText(extra: IntentExtra): String? {
        val texts = when (extra) {
            is IntentExtra.Text -> listOf(extra.value)
            is IntentExtra.TextList -> extra.values
            else -> return null
        }
        for (text in texts) {
            if (text.length > MAX_EXTRA_TEXT_CHARS) return "text is above the $MAX_EXTRA_TEXT_CHARS character limit."
            if (text.any { it.code == 0 }) return "text contains a NUL character."
            declaredSchemeOf(text.trim())?.takeIf { it in blockedSchemes }?.let {
                return "\"$it:\" is not a scheme this agent may pass to another app."
            }
        }
        return null
    }

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

    /**
     * Decide what to do with a proposed intent.
     *
     * @param action fully-qualified action, or null to default to VIEW.
     * @param uri the data URI, or null for an action that needs none.
     * @param extras already read by [parseExtras].
     */
    fun evaluate(action: String?, uri: String?, extras: Map<String, IntentExtra> = emptyMap()): Decision {
        val resolvedAction = action?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "android.intent.action.VIEW"
        if (resolvedAction.length > MAX_ACTION_CHARS || !ACTION_RE.matches(resolvedAction)) {
            return Decision.Deny(
                "action_malformed",
                "\"$resolvedAction\" is not an action name. Use a fully-qualified one like " +
                    "android.intent.action.VIEW.",
            )
        }
        if (resolvedAction in blockedActions) {
            return Decision.Deny(
                "action_not_allowed",
                "\"$resolvedAction\" acts with no screen for the user to confirm on. " +
                    "Use android.intent.action.DIAL to open the dialer instead.",
            )
        }
        if (uri == null || uri.isBlank()) {
            // VIEW with nothing to view resolves to nothing. Every other action
            // may carry its whole request in extras: a timer has no uri.
            if (resolvedAction == "android.intent.action.VIEW") {
                return Decision.Deny("uri_required", "\"$resolvedAction\" needs a uri.")
            }
            val sideEffect = describeExtrasSideEffect(extras)
            return if (sideEffect == null) {
                Decision.Allow(null, resolvedAction, extras)
            } else {
                Decision.NeedsConfirmation(null, resolvedAction, sideEffect, extras)
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
        val sideEffect = describeSideEffect(parsed, scheme, resolvedAction) ?: describeExtrasSideEffect(extras)
        return if (sideEffect == null) {
            Decision.Allow(trimmed, resolvedAction, extras)
        } else {
            Decision.NeedsConfirmation(trimmed, resolvedAction, sideEffect, extras)
        }
    }

    /**
     * The side effect extras carry, read the way [describeSideEffect] reads a
     * query: by what the value is, not by which app receives it. A message in
     * `android.intent.extra.TEXT` fills a draft like `?text=` does and asks
     * nothing; an amount starts a payment wherever it appears.
     */
    private fun describeExtrasSideEffect(extras: Map<String, IntentExtra>): String? =
        if (extras.keys.any { it.substringAfterLast('.').lowercase(Locale.ROOT).contains("amount") }) {
            "Start a payment."
        } else {
            null
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
