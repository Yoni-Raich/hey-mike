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
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * App-owned pages, one per chat, each a WebView in a window of its own.
 *
 * Every change to [pages] runs on the main thread, whatever caused it: an
 * agent call, the window's own Close button, or the renderer dying. [lock]
 * additionally keeps two calls from making or reconnecting pages at once.
 */
class AndroidBrowserHost(
    private val context: Context,
    private val takeScreen: suspend (String) -> Boolean = { true },
    private val confirm: suspend (String, String) -> Boolean = { _, _ -> false },
) : BrowserHost {
    private class Page(
        val key: String,
        val marker: String,
        val view: WebView,
        val window: LinearLayout,
        val address: TextView,
    ) {
        /** The DevTools target of this WebView; it lives as long as the WebView does. */
        var targetId: String? = null
        var session: BrowserSession? = null
        var lastUsed: Long = SystemClock.elapsedRealtime()
    }

    private val pages = linkedMapOf<String, Page>()
    private val lock = Mutex()
    private val policy = WebUrlPolicy()
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** What a chat should hear about its page the next time it gets one: closed to free memory, renderer gone. */
    private val pendingNotes = ConcurrentHashMap<String, MutableList<String>>()
    @Volatile private var count = 0
    @Volatile private var visibleKey: String? = null
    private var workersGuarded = false

    private val dialer = DevToolsDialer {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress("webview_devtools_remote_${Process.myPid()}", LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 15_000
            DevToolsStream(socket.inputStream, socket.outputStream, { socket.close() }, { socket.soTimeout = 0 })
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }

    override fun unavailable(): WebFailure? = if (Settings.canDrawOverlays(context)) null else
        WebFailure("browser_overlay_permission", "Allow Hey Mike to display over other apps first.",
            "Open Hey Mike settings and allow its floating window. Wireless ADB is not needed.")

    override fun statusLine() = "Internal browser: ${if (unavailable() == null) "ready ($count pages)" else "floating window permission needed"}"

    override fun conditions(): String? {
        val parts = buildList {
            if (runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() == false) add("the screen is off")
            if (runCatching { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }.getOrNull() == true) add("the phone is locked")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" and ", postfix = ". The page's window is not drawn then, which can stall or drop the browser.")
    }

    override suspend fun session(key: String): BrowserSession = lock.withLock {
        val existing = onMain { pages[key]?.also { it.lastUsed = SystemClock.elapsedRealtime() } }
        existing?.session?.takeIf { it.isOpen }?.let { return@withLock it }
        unavailable()?.let { throw it }
        if (existing != null) {
            // The WebView is still alive and only its connection dropped:
            // attach again and keep the page, its login and its form as they are.
            val again = try {
                connect(existing, key, fresh = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (again != null) {
                again.note("The browser connection dropped and was re-established on the same page. Nothing was repeated; take a snapshot before acting, because earlier refs no longer work.")
                return@withLock again
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                discard(existing, "The page's connection could not be restored, so it was closed and a new blank page opened. Anything typed there is gone.")
            }
        }
        val page = onMain {
            makeRoom()
            create(key).also { pages[key] = it; count = pages.size }
        }
        try {
            connect(page, key, fresh = true).also { session -> pendingNotes.remove(key)?.forEach(session::note) }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.Main.immediate) { discard(page, null) }
            if (failure is IOException) throw WebFailure("browser_cdp_unavailable", "WebView CDP connection failed: ${failure.message}")
            throw failure
        }
    }

    /** Attach a DevTools session to [page]. A fresh page also gets its first, blank address. */
    private suspend fun connect(page: Page, key: String, fresh: Boolean): BrowserSession {
        val id = page.targetId ?: if (fresh) discover(page.marker) else throw IOException("This page has no DevTools target")
        page.targetId = id
        val client = withContext(Dispatchers.IO) { CdpClient.attach(dialer, id) }
        try {
            val session = BrowserSession(client, policy, nativeTap = { x, y -> WebViewInput.tap(page.view, x, y) },
                confirm = { summary -> confirm(key, summary) })
            session.start()
            if (fresh) session.open("about:blank")
            onMain {
                page.session = session
                session.userControlled = visibleKey == key
            }
            return session
        } catch (failure: Throwable) {
            client.close()
            throw failure
        }
    }

    private suspend fun discover(marker: String): String {
        repeat(30) {
            val id = withContext(Dispatchers.IO) { runCatching { CdpClient.pages(dialer).firstOrNull { it.url == marker }?.id }.getOrNull() }
            if (id != null) return id
            delay(100)
        }
        throw WebFailure("browser_cdp_unavailable",
            "This WebView did not expose its app-owned DevTools socket.",
            "Same-UID CDP must be checked on this phone; Wireless ADB is not used by this browser.")
    }

    /**
     * Before a new page: close pages of chats that were deleted, then, above
     * [MAX_PAGES], the least recently used page nobody is using. A WebView
     * costs tens of megabytes; without this every chat that ever browsed kept
     * one until the service stopped. A page in use, or shown to the user, is
     * never taken; the cap gives way instead.
     */
    private fun makeRoom() {
        pages.values.filter { page ->
            File(page.key).let { it.isAbsolute && !it.exists() } && page.session?.busy != true
        }.forEach { discard(it, null) }
        while (pages.size >= MAX_PAGES) {
            val victim = pages.values
                .filter { it.key != visibleKey && it.session?.busy != true && it.session?.userControlled != true }
                .minByOrNull { it.lastUsed } ?: break
            discard(victim, "This chat's page was closed to free memory while other chats browsed. Open the address again; anything typed there is gone.")
        }
    }

    /**
     * Take [page] away for good, on the main thread. Every path that ends a
     * page comes here, so the count, the shown page and WebView debugging
     * always agree with what exists.
     */
    private fun discard(page: Page, note: String?) {
        if (pages[page.key] === page) pages.remove(page.key)
        note?.let { pendingNotes.getOrPut(page.key) { mutableListOf() }.add(it) }
        runCatching { page.session?.close() }
        page.session = null
        runCatching { windows.removeViewImmediate(page.window) }
        runCatching { page.view.stopLoading(); page.view.destroy() }
        count = pages.size
        if (visibleKey == page.key && pages[page.key] == null) visibleKey = null
        // CDP is this browser's control channel, also in release builds. It is
        // open only while an internal page exists.
        if (pages.isEmpty()) WebView.setWebContentsDebuggingEnabled(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(key: String): Page {
        guardServiceWorkers()
        val marker = "https://hey-mike.invalid/page/${UUID.randomUUID()}"
        // Intended use: CDP is the browser's control channel, also in release.
        // It is enabled only while an internal page exists, and disabled on close.
        WebView.setWebContentsDebuggingEnabled(true)
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val padding = dp(8)
            setPadding(padding, 0, padding, 0)
        }
        // The user types passwords here, so the bar says which site this is.
        val address = TextView(context).apply {
            text = TITLE
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
        }
        val page = Page(key, marker, view, root, address)
        bar.addView(address, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(context).apply { setText(R.string.browser_hide); setOnClickListener { park(page.key, null) } })
        bar.addView(Button(context).apply { setText(R.string.browser_close); setOnClickListener { discard(page, "The user closed this page.") } })
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Main thread: no name lookup here. Literal addresses and schemes
                // are judged now; shouldInterceptRequest and the read guard in
                // BrowserSession judge names.
                val blocked = policy.check(request.url.toString(), lookUp = false) as? WebUrlPolicy.Decision.Blocked
                blocked?.let { page.session?.note(it.message) }
                return blocked != null
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString() == marker) return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(MARKER_PAGE))
                return refuse(request.url.toString()) { page.session?.note(it) }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                showAddress(page, url)
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                discard(page, "The page's renderer stopped (" + (if (detail.didCrash()) "it crashed" else "the phone reclaimed its memory") +
                    "), so the page was closed and a new blank page opened. Anything typed there is gone.")
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null); page.session?.note("File upload requires the user's browser."); return true
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, resultMsg: android.os.Message): Boolean {
                page.session?.note("A popup was refused. Open its URL in this page instead."); return false
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> page.session?.note("Download refused. This browser does not save files automatically.") }
        windows.addView(root, layout(shown = false))
        view.loadDataWithBaseURL(marker, String(MARKER_PAGE), "text/html", "UTF-8", marker)
        return page
    }

    /** A 403 for an address the policy refuses, or null to let the request through. */
    private fun refuse(url: String, report: (String) -> Unit): WebResourceResponse? {
        val blocked = policy.check(url) as? WebUrlPolicy.Decision.Blocked ?: return null
        report(blocked.message)
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * A service worker's own fetches never reach a page's WebViewClient. This
     * app has no WebView but the browser's, so the app-wide worker client
     * applies the same address policy to them.
     */
    private fun guardServiceWorkers() {
        if (workersGuarded) return
        workersGuarded = true
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    refuse(request.url.toString()) {}
            })
        }
    }

    private fun showAddress(page: Page, url: String?) {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val host = uri?.host?.takeIf { it.isNotBlank() && it != "hey-mike.invalid" }
        page.address.text = when {
            host == null -> TITLE
            uri.scheme.equals("https", ignoreCase = true) -> "$TITLE\n$host"
            else -> "$TITLE\nNot secure: $host"
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun layout(shown: Boolean): WindowManager.LayoutParams {
        val bounds = windows.currentWindowMetrics.bounds
        return WindowManager.LayoutParams(bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (shown) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = if (shown) 0 else bounds.width() - 1
                alpha = if (shown) 1f else 0.01f
                title = "Hey Mike browser"
                // The keyboard must not cover the field the user is typing a password into.
                if (shown) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
    }

    /** Put [key]'s page out of sight and back under agent control. Main thread. */
    private fun park(key: String, note: String?) {
        val page = pages[key] ?: return
        windows.updateViewLayout(page.window, layout(false))
        page.session?.userControlled = false
        note?.let { page.session?.note(it) }
        if (visibleKey == key) visibleKey = null
    }

    override suspend fun show(key: String) {
        if (!takeScreen(key)) throw WebFailure("stopped", "The run stopped before showing its browser.")
        onMain {
            val page = pages[key] ?: throw WebFailure("no_page", "This chat has no page open.", "Open an address first.")
            visibleKey?.takeIf { it != key }?.let { park(it, PUT_AWAY) }
            page.lastUsed = SystemClock.elapsedRealtime()
            windows.updateViewLayout(page.window, layout(true))
            page.session?.userControlled = true
            page.view.requestFocus()
            visibleKey = key
        }
    }

    /**
     * Screen work is about to start. A shown page would sit on top of the
     * app it is meant for, so it is put away first, whichever chat it is for.
     * Await the main thread before showing a new page or sending screen input.
     */
    override suspend fun screenInUse(key: String) {
        onMain { visibleKey?.let { shown -> park(shown, PUT_AWAY) } }
    }

    override suspend fun hide(key: String) = onMain { park(key, null) }

    override suspend fun close(key: String): Boolean = lock.withLock {
        onMain {
            val page = pages[key] ?: return@onMain false
            discard(page, null)
            pendingNotes.remove(key)
            true
        }
    }

    suspend fun closeAll() = lock.withLock {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            pages.values.toList().forEach { discard(it, null) }
            pendingNotes.clear()
            visibleKey = null
            WebView.setWebContentsDebuggingEnabled(false)
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private companion object {
        const val TITLE = "Mike browser"
        const val MAX_PAGES = 4
        const val PUT_AWAY = "The page was put away because a task needed the phone's screen. Call show again if the user still needs it."
        val MARKER_PAGE = "<title>Mike browser</title>".toByteArray()
    }
}
