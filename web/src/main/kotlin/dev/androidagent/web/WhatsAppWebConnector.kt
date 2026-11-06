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


package dev.androidagent.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.androidagent.core.proactive.ConnectorIds
import dev.androidagent.core.proactive.ConnectorState
import dev.androidagent.core.proactive.ConnectorStatus
import dev.androidagent.core.proactive.ConnectorUnavailable
import dev.androidagent.core.proactive.ContextConnector
import dev.androidagent.core.proactive.ContextMessage
import dev.androidagent.core.proactive.ConversationContext
import dev.androidagent.core.proactive.ConversationQuery
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The user's own WhatsApp Web session, linked inside Hey Mike, read in the
 * background. One page for both jobs: the linking screen shows it, and the
 * connector reads from it. Read-only by construction: the only script that
 * runs in it is the bundled [SCRIPT], which reads loaded chats.
 *
 * The page shares the WebView profile of Mike's browser, so a session the user
 * already linked there is used as is. The agent's browser itself refuses the
 * site ([WebUrlPolicy.RESERVED_HOSTS]): the model never drives the page.
 * Reading is local only: the script reads what the page already holds.
 *
 * Unofficial: WhatsApp may log the session out or change the page. Every
 * change is caught and reported as [ConnectorState.NEEDS_ATTENTION], never
 * worked around.
 */
class WhatsAppWebConnector(private val context: Context) : ContextConnector {
    override val id: String = ConnectorIds.WHATSAPP_WEB
    override val packages: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("connector-whatsapp-web", Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var view: WebView? = null
    @Volatile private var base: MutableContextWrapper? = null
    @Volatile private var shownOnScreen = false
    @Volatile private var lastUse = 0L
    private val release = Runnable { if (!shownOnScreen && System.currentTimeMillis() - lastUse >= IDLE_MS - 1_000) destroy() }

    /** The user started linking at least once and has not disconnected. */
    val started: Boolean get() = prefs.getBoolean(KEY_STARTED, false)
    private var linked: Boolean
        get() = prefs.getBoolean(KEY_LINKED, false)
        set(value) { prefs.edit().putBoolean(KEY_LINKED, value).apply() }

    override suspend fun status(): ConnectorStatus {
        if (!started && !hasStoredSession()) return ConnectorStatus(ConnectorState.NOT_LINKED)
        if (!started) prefs.edit().putBoolean(KEY_STARTED, true).apply()
        return lock.withLock { pageStatus(wait = !shownOnScreen) }
    }

    override suspend fun conversation(query: ConversationQuery): ConversationContext = lock.withLock {
        val status = pageStatus(wait = true)
        if (status.state != ConnectorState.READY) throw ConnectorUnavailable(status.state.wire, status.detail ?: "WhatsApp Web is not ready.")
        val call = UUID.randomUUID().toString()
        val q = buildJsonObject {
            query.conversation?.let { put("conversation", it) }
            query.sender?.let { put("sender", it) }
            query.hintText?.let { put("hint", it.take(200)) }
            put("group", query.group)
            query.before?.let { put("before", it) }
            put("limit", query.limit)
        }
        eval("window.__heyMikeWA.start(${JsonPrimitive(call)}, $q)")
        var raw: String? = null
        for (attempt in 0 until 60) {
            delay(250)
            raw = evalString("window.__heyMikeWA && window.__heyMikeWA.take(${JsonPrimitive(call)})")
            if (raw != null) break
        }
        touch()
        val result = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: throw ConnectorUnavailable("timeout", "WhatsApp Web did not answer.")
        parse(result)
    }

    private fun parse(result: JsonObject): ConversationContext {
        result["error"]?.jsonPrimitive?.contentOrNull?.let { error ->
            if (error == "unsupported") throw ConnectorUnavailable("unsupported", "WhatsApp Web changed and Mike cannot read it yet.")
            throw ConnectorUnavailable("failed", error.take(160))
        }
        if (result["found"]?.jsonPrimitive?.booleanOrNull != true) {
            throw ConnectorUnavailable("no_match", "No single WhatsApp chat matched this notification.")
        }
        val messages = result["messages"]?.jsonArray.orEmpty().mapNotNull { item ->
            val m = item.jsonObject
            val text = m["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ContextMessage(
                direction = if (m["dir"]?.jsonPrimitive?.contentOrNull == "out") "out" else "in",
                sender = m["from"]?.jsonPrimitive?.contentOrNull,
                text = text,
                at = m["at"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
                kind = m["kind"]?.jsonPrimitive?.contentOrNull ?: "text",
            )
        }
        return ConversationContext(
            connector = id,
            title = result["title"]?.jsonPrimitive?.contentOrNull,
            messages = messages,
            matchedBy = result["by"]?.jsonPrimitive?.contentOrNull ?: "title",
            hasEarlier = result["hasEarlier"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /** Load the page if needed and read its state. [wait] gives a cold page time to open its chats. */
    private suspend fun pageStatus(wait: Boolean): ConnectorStatus {
        onMain { ensureView() }
        touch()
        val deadline = System.currentTimeMillis() + if (wait) LOAD_WAIT_MS else 1_500
        var state = "loading"
        while (true) {
            state = evalString(STATUS_CALL)?.let { raw ->
                runCatching { Json.parseToJsonElement(raw).jsonObject["state"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            } ?: "loading"
            if (state != "loading" || System.currentTimeMillis() > deadline) break
            delay(700)
        }
        return when (state) {
            "ready" -> { linked = true; ConnectorStatus(ConnectorState.READY) }
            "unsupported" -> ConnectorStatus(ConnectorState.NEEDS_ATTENTION, "WhatsApp Web changed; reading is paused")
            "link" -> if (linked) ConnectorStatus(ConnectorState.NEEDS_ATTENTION, "logged out; link again")
                else ConnectorStatus(ConnectorState.LINKING)
            else -> ConnectorStatus(if (linked) ConnectorState.NEEDS_ATTENTION else ConnectorState.LINKING, "still loading")
        }
    }

    // ----- The linking screen ---------------------------------------------------------

    /** Show the page inside [container] so the user can link. Main thread. */
    fun attach(container: ViewGroup, activity: Context) {
        prefs.edit().putBoolean(KEY_STARTED, true).apply()
        val page = ensureView()
        base?.baseContext = activity
        (page.parent as? ViewGroup)?.removeView(page)
        container.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shownOnScreen = true
        page.onResume()
    }

    /** The linking screen closed. The page stays for a while for the next read. Main thread. */
    fun detach() {
        view?.let { page -> (page.parent as? ViewGroup)?.removeView(page) }
        base?.baseContext = context.applicationContext
        shownOnScreen = false
        touch()
    }

    /** Forget the session on this phone. The phone's Linked devices list still shows it until removed there. */
    fun disconnect() {
        main.post {
            destroy()
            clearDefaultProfile()
        }
        prefs.edit().clear().apply()
    }

    /** WhatsApp Web already keeps data in this app's browser, as after linking in Mike's browser. */
    private suspend fun hasStoredSession(): Boolean = onMainSuspend { done ->
        runCatching {
            WebStorage.getInstance().getOrigins { origins ->
                done(origins.orEmpty().values.any { (it as? WebStorage.Origin)?.origin?.trimEnd('/') == ORIGIN })
            }
        }.onFailure { done(false) }
    }

    private fun clearDefaultProfile() {
        runCatching { WebStorage.getInstance().deleteOrigin(ORIGIN) }
        val cookies = CookieManager.getInstance()
        cookies.getCookie(ORIGIN)?.split(';')?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }?.forEach { name ->
            cookies.setCookie(ORIGIN, "$name=; Max-Age=0; Path=/")
        }
        cookies.flush()
    }

    // ----- The page -------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView(): WebView {
        view?.let { return it }
        val wrapper = MutableContextWrapper(context.applicationContext)
        val page = WebView(wrapper)
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            // WhatsApp Web serves its desktop app only to a desktop browser.
            userAgentString = DesktopView.userAgent(WebSettings.getDefaultUserAgent(context))
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The page stays on WhatsApp Web; any other link is not followed here.
                val host = request.url.host?.lowercase()
                return host != HOST
            }
            override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(SCRIPT, null) }
        }
        page.setDownloadListener { _, _, _, _, _ -> }
        page.loadUrl(ORIGIN)
        base = wrapper
        view = page
        return page
    }

    private fun destroy() {
        val page = view ?: return
        view = null
        base = null
        (page.parent as? ViewGroup)?.removeView(page)
        page.stopLoading()
        page.destroy()
    }

    private fun touch() {
        lastUse = System.currentTimeMillis()
        main.removeCallbacks(release)
        main.postDelayed(release, IDLE_MS)
    }

    private suspend fun eval(script: String): String? = onMainSuspend { done ->
        val page = view
        if (page == null) done(null)
        else page.evaluateJavascript("$SCRIPT;\n$script") { done(it) }
    }

    /** A JavaScript string result, unquoted; null for null or undefined. */
    private suspend fun evalString(script: String): String? {
        val raw = eval(script) ?: return null
        if (raw == "null" || raw == "undefined") return null
        return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
    }

    private suspend fun <T> onMain(block: () -> T): T = onMainSuspend { done -> done(block()) }

    private suspend fun <T> onMainSuspend(block: (done: (T) -> Unit) -> Unit): T = suspendCancellableCoroutine { cont ->
        main.post {
            try { block { value -> if (cont.isActive) cont.resume(value) } }
            catch (e: Exception) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
        }
    }

    companion object {
        const val HOST = "web.whatsapp.com"
        const val ORIGIN = "https://web.whatsapp.com"
        private const val KEY_STARTED = "started"
        private const val KEY_LINKED = "linked"
        private const val STATUS_CALL = "window.__heyMikeWA && window.__heyMikeWA.status()"
        /** How long a cold page may take to open its chats. */
        private const val LOAD_WAIT_MS = 20_000L
        /** The page is closed after this long without a read, to free memory. */
        private const val IDLE_MS = 10 * 60_000L
        private val SCRIPT: String by lazy {
            checkNotNull(WhatsAppWebConnector::class.java.getResourceAsStream("whatsapp_reader.js")) {
                "whatsapp_reader.js is missing from the build"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
