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


package dev.androidagent.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dev.androidagent.core.proactive.ConnectorIds
import dev.androidagent.core.proactive.ConnectorState
import dev.androidagent.core.proactive.ConnectorStatus
import dev.androidagent.core.proactive.ConnectorUnavailable
import dev.androidagent.core.proactive.ContextConnector
import dev.androidagent.core.proactive.ContextMessage
import dev.androidagent.core.proactive.ConversationContext
import dev.androidagent.core.proactive.ConversationQuery
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gmail, read-only, through Google's own sign-in: the `gmail.readonly` scope
 * and the Gmail REST API. Nothing here sends, labels, archives or marks read.
 *
 * Needs an Android OAuth client in a Google Cloud project for this package
 * and signing key. Without one, Google answers the sign-in with an error,
 * which is shown on the card as is.
 */
class GmailConnector(private val context: Context) : ContextConnector {
    override val id: String = ConnectorIds.GMAIL
    override val packages: Set<String> = setOf("com.google.android.gm")
    private val prefs = context.getSharedPreferences("connector-gmail", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val linked: Boolean get() = prefs.getBoolean(KEY_LINKED, false)

    override suspend fun status(): ConnectorStatus {
        if (!linked) return ConnectorStatus(ConnectorState.NOT_LINKED, prefs.getString(KEY_ERROR, null))
        return try {
            token()
            ConnectorStatus(ConnectorState.READY, account = prefs.getString(KEY_ACCOUNT, null))
        } catch (e: ConnectorUnavailable) {
            ConnectorStatus(ConnectorState.NEEDS_ATTENTION, e.message, prefs.getString(KEY_ACCOUNT, null))
        }
    }

    override suspend fun conversation(query: ConversationQuery): ConversationContext = withContext(Dispatchers.IO) {
        val token = token()
        val (threadId, by) = findThread(token, query) ?: throw ConnectorUnavailable("no_match", "No recent Gmail thread matched this notification.")
        val thread = get(token, "threads/$threadId?format=full")
        val all = thread["messages"]?.jsonArray.orEmpty().map { it.jsonObject }
        val older = all.filter { m -> query.before == null || (m.internalDate() ?: 0) < query.before!! }
        val limit = query.limit.coerceIn(1, 50)
        val messages = older.takeLast(limit).map { m ->
            val headers = m.headers()
            val sent = m["labelIds"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "SENT" } == true
            ContextMessage(
                direction = if (sent) "out" else "in",
                sender = if (sent) null else headers["from"]?.let(::displayName),
                text = listOfNotNull(headers["subject"]?.takeIf { m === older.first() }?.let { "[$it] " }, body(m)).joinToString(""),
                at = m.internalDate(),
                kind = "email",
            )
        }
        ConversationContext(id, all.firstOrNull()?.headers()?.get("subject"), messages, by, hasEarlier = older.size > limit)
    }

    /** The thread behind a notification: its sender's recent mail, picked by subject when there is one. */
    private fun findThread(token: String, query: ConversationQuery): Pair<String, String>? {
        val sender = query.sender?.takeIf { it.isNotBlank() && !it.contains("new messages", true) }
        val hint = query.hintText?.trim()?.takeIf { it.isNotEmpty() }
        val search = when {
            sender != null -> "from:(\"${sender.replace("\"", "")}\") newer_than:14d"
            hint != null -> "\"${hint.take(60).replace("\"", "")}\" newer_than:7d"
            else -> return null
        }
        val list = get(token, "messages?maxResults=8&q=" + URLEncoder.encode(search, "UTF-8"))
        val ids = list["messages"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["threadId"]?.jsonPrimitive?.contentOrNull to it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        if (ids.isEmpty()) return null
        if (hint != null) {
            val wanted = norm(hint)
            for ((thread, message) in ids.take(5)) {
                if (thread == null || message == null) continue
                val subject = get(token, "messages/$message?format=metadata&metadataHeaders=Subject").headers()["subject"]?.let(::norm) ?: continue
                if (subject.isNotEmpty() && (wanted.contains(subject) || subject.contains(wanted.take(40)))) return thread to "subject"
            }
        }
        return ids.first().first?.let { it to if (sender != null) "sender" else "text" }
    }

    // ----- Sign-in ------------------------------------------------------------------

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Start the sign-in from a screen: null when access is already granted, else the consent screen to launch. */
    suspend fun begin(activity: android.app.Activity): PendingIntent? {
        prefs.edit().remove(KEY_ERROR).apply()
        val result = try { Identity.getAuthorizationClient(activity).authorize(request()).await() }
            catch (e: Exception) { fail(e); throw e }
        if (result.hasResolution()) return result.pendingIntent
        finish(result)
        return null
    }

    /** The consent screen returned. */
    suspend fun complete(data: Intent?) {
        val result = try { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }
            catch (e: Exception) { fail(e); throw e }
        finish(result)
    }

    private suspend fun finish(result: AuthorizationResult) {
        val token = result.accessToken ?: throw IllegalStateException("Google returned no access")
        prefs.edit().putBoolean(KEY_LINKED, true).remove(KEY_ERROR).apply()
        runCatching {
            withContext(Dispatchers.IO) { get(token, "profile")["emailAddress"]?.jsonPrimitive?.contentOrNull }
        }.getOrNull()?.let { prefs.edit().putString(KEY_ACCOUNT, it).apply() }
    }

    private fun fail(e: Exception) { prefs.edit().putString(KEY_ERROR, e.message?.take(160) ?: e.javaClass.simpleName).apply() }

    /** Forget the grant on this phone. Google's account page still lists the app until removed there. */
    fun disconnect() {
        val token = prefs.getString(KEY_TOKEN, null)
        prefs.edit().clear().apply()
        token?.let { t -> Thread { runCatching { GoogleAuthUtil.clearToken(context, t) } }.start() }
    }

    /** A fresh token. Play services caches it; a grant that needs the user again is reported, never prompted from here. */
    private suspend fun token(): String {
        val result = try { Identity.getAuthorizationClient(context).authorize(request()).await() }
            catch (e: Exception) { throw ConnectorUnavailable("auth", e.message?.take(160) ?: "Google sign-in failed.") }
        if (result.hasResolution()) throw ConnectorUnavailable("auth", "Sign in to Gmail again.")
        val token = result.accessToken ?: throw ConnectorUnavailable("auth", "Google returned no access.")
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    // ----- REST ---------------------------------------------------------------------

    private fun get(token: String, path: String): JsonObject {
        val connection = URL("$API/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code == 401) {
                runCatching { GoogleAuthUtil.clearToken(context, token) }
                throw ConnectorUnavailable("auth", "Gmail access expired; it renews on the next try.")
            }
            if (code == 403) throw ConnectorUnavailable("forbidden", "Gmail refused access (403). Check the Google Cloud setup.")
            if (code !in 200..299) throw ConnectorUnavailable("http_$code", "Gmail answered $code.")
            return json.parseToJsonElement(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        } finally { connection.disconnect() }
    }

    private fun JsonObject.internalDate(): Long? = this["internalDate"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.headers(): Map<String, String> = (this["payload"]?.jsonObject?.get("headers")?.jsonArray.orEmpty())
        .mapNotNull { h -> val o = h.jsonObject; (o["name"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null) to (o["value"]?.jsonPrimitive?.contentOrNull ?: "") }
        .toMap()

    /** The plain text of one message, without the quoted history below it. */
    private fun body(message: JsonObject): String {
        val payload = message["payload"]?.jsonObject ?: return message["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val plain = part(payload, "text/plain")
        val text = plain ?: part(payload, "text/html")?.let(::stripHtml) ?: message["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return withoutQuote(text)
    }

    private fun part(node: JsonObject, mime: String): String? {
        if (node["mimeType"]?.jsonPrimitive?.contentOrNull == mime) {
            node["body"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull?.let { data ->
                return runCatching { String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8) }.getOrNull()
            }
        }
        node["parts"]?.jsonArray?.forEach { child -> part(child.jsonObject, mime)?.let { return it } }
        return null
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
        private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val KEY_LINKED = "linked"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_ERROR = "error"
        private const val KEY_TOKEN = "token"

        private val QUOTE_START = Regex("^(On .+ wrote:|ב[-־ ]?.+ כתב(/ה)?:|-----Original Message-----|From: .+)$", RegexOption.IGNORE_CASE)

        internal fun withoutQuote(text: String): String {
            val out = StringBuilder()
            for (line in text.lineSequence()) {
                val t = line.trim()
                if (t.startsWith(">") || QUOTE_START.matches(t)) break
                out.append(line).append('\n')
            }
            return out.toString().trim()
        }

        internal fun stripHtml(html: String): String = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")

        internal fun displayName(from: String): String = from.substringBefore('<').trim().trim('"').ifEmpty { from.trim() }.take(80)

        private fun norm(text: String) = text.lowercase().replace(Regex("^(re|fwd?|fw):\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
