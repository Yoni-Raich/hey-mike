package dev.androidagent.app.ui

import dev.androidagent.core.EngineEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder

/**
 * What an approval card says, in words a person can check at a glance.
 *
 * The card used to print the raw request — `"uri":"https://wa.me/97258…?text=%D7%94…"` —
 * so the one thing the user had to verify, who gets which message, was
 * percent-encoded JSON.
 */
internal data class ApprovalSummary(
    val headline: String,
    /** Label and value pairs, in display order. */
    val lines: List<Pair<String, String>>,
)

internal fun EngineEvent.Approval.summary(): ApprovalSummary {
    fun detail(key: String) = (details[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val uri = detail("uri")
    val parsed = uri?.let { runCatching { URI(it) }.getOrNull() }
    // An opaque uri such as smsto:+972…?body=… has no rawQuery of its own.
    val rawQuery = parsed?.rawQuery ?: parsed?.rawSchemeSpecificPart?.substringAfter('?', "")
    val query = rawQuery.orEmpty().split('&').mapNotNull { part ->
        val key = part.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        key to decode(part.substringAfter('=', ""))
    }.toMap()
    val message = query["text"] ?: query["body"] ?: query["message"]
    val scheme = parsed?.scheme?.lowercase()
    val host = parsed?.host?.lowercase()

    val app = when {
        host == "wa.me" || host == "api.whatsapp.com" || detail("package") == "com.whatsapp" -> "WhatsApp"
        scheme in setOf("sms", "smsto", "mms", "mmsto") -> "SMS"
        scheme == "mailto" -> "Email"
        host == "t.me" || host == "telegram.me" -> "Telegram"
        else -> null
    }
    val recipient = when {
        host == "wa.me" -> parsed?.path?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "+$it" }
        scheme in setOf("sms", "smsto", "mms", "mmsto", "mailto", "tel") ->
            parsed?.rawSchemeSpecificPart?.substringBefore('?')?.let(::decode)?.takeIf { it.isNotBlank() }
        else -> null
    }

    val headline = when {
        message != null && app == "Email" -> "Send an email?"
        message != null && app == "SMS" -> "Send an SMS?"
        message != null && app != null -> "Send a $app message?"
        message != null -> "Send a message?"
        method == "open_intent" -> "Open this?"
        else -> "Allow $method?"
    }
    val lines = buildList {
        recipient?.let { add("To" to it) }
        message?.let { add("Message" to it) }
        if (recipient == null && message == null) {
            detail("reason")?.let { add("What" to it) }
            uri?.let { add("Link" to decode(it)) }
        }
        if (app == null) detail("package")?.let { add("App" to it) }
    }
    return ApprovalSummary(headline, lines)
}

private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
