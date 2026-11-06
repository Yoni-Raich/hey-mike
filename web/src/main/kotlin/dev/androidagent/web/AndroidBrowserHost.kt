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
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the app shows about one chat's page. */
data class BrowserPageState(
    /** The chat's key: its workspace path. */
    val key: String,
    /** The site, or null while the page is blank. */
    val host: String? = null,
    val secure: Boolean = true,
    val title: String? = null,
    /** The user has the page; the agent's actions wait until they give it back. */
    val userControlled: Boolean = false,
    /** Changes when phone screen work asks the UI to leave this page's panel. */
    val panelDismissals: Long = 0,
    /** The page is asked for as a desktop site. */
    val desktop: Boolean = false,
)

/**
 * Where the app puts a chat's page on screen. While the agent has the page,
 * the user's touches are held back here so they cannot land in the middle of
 * an action; the agent's own input goes to the WebView directly.
 */
class BrowserFrame(context: Context) : FrameLayout(context) {
    var locked = true
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = locked || super.onInterceptTouchEvent(event)
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = locked || super.onTouchEvent(event)
}

/**
 * App-owned pages, one per chat, each a WebView.
 *
 * A page lives in a hidden window of its own, so it keeps rendering while the
 * user is in another app. When the user looks at it, the same WebView moves
 * into a [BrowserFrame] on Hey Mike's own screen, and back when they leave.
 *
 * Every change to [pages] runs on the main thread, whatever caused it: an
 * agent call, the user's Close, or the renderer dying. [lock] additionally
 * keeps two calls from making or reconnecting pages at once.
 */
class AndroidBrowserHost(
    private val context: Context,
    private val takeScreen: suspend (String) -> Boolean = { true },
    private val confirm: suspend (String, String) -> Boolean = { _, _ -> false },
    /** Bring Hey Mike forward with [key]'s page open. */
    private val present: (String) -> Unit = {},
) : BrowserHost {
    private class Page(
        val key: String,
        val marker: String,
        val view: WebView,
        /** The WebView's context: the app while hidden, the activity while on screen. */
        val base: MutableContextWrapper,
        val window: FrameLayout,
    ) {
        /** The DevTools target of this WebView; it lives as long as the WebView does. */
        var targetId: String? = null
        var session: BrowserSession? = null
        var lastUsed: Long = SystemClock.elapsedRealtime()
        var userControlled = false
        /** Where the page is on screen, or null while it is hidden. */
        var frame: BrowserFrame? = null
        var state = BrowserPageState(key)
    }

    private val pages = linkedMapOf<String, Page>()
    private val lock = Mutex()
    private val policy = WebUrlPolicy()
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** What a chat should hear about its page the next time it gets one: closed to free memory, renderer gone. */
    private val pendingNotes = ConcurrentHashMap<String, MutableList<String>>()
    @Volatile private var count = 0
    private var workersGuarded = false
    /** Chats whose pages are desktop sites, kept for their next page too. Main thread. */
    private val desktopChats = mutableSetOf<String>()
    /** Where the user's own layout switch runs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val shown = MutableStateFlow<Map<String, BrowserPageState>>(emptyMap())
    /** Every open page, by chat key. */
    val pageStates: StateFlow<Map<String, BrowserPageState>> = shown.asStateFlow()

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
            "Open Hey Mike settings and allow display over other apps: the page keeps running in a hidden window while the user is elsewhere. Wireless ADB is not needed.")

    override fun statusLine() = "Internal browser: ${if (unavailable() == null) "ready ($count pages)" else "display over other apps permission needed"}"

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
        val page = onMain { checkNotNull(pages[open(key)]) }
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
            if (onMain { page.state.desktop }) session.desktopLayout(true, reload = false)
            if (fresh) session.open("about:blank")
            onMain {
                page.session = session
                session.userControlled = page.userControlled
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

    /** A new blank page for [key], not yet connected. Main thread. Returns [key]. */
    internal fun open(key: String): String {
        makeRoom()
        val page = create(key)
        pages[key] = page
        count = pages.size
        publish(page, page.state)
        return key
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
                .filter { it.frame == null && it.session?.busy != true && !it.userControlled }
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
        (page.view.parent as? ViewGroup)?.removeView(page.view)
        page.frame = null
        runCatching { windows.removeViewImmediate(page.window) }
        runCatching { page.view.stopLoading(); page.view.destroy() }
        count = pages.size
        if (pages[page.key] == null) shown.update { it - page.key }
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
        // A dropdown or a date picker opened by the user needs an activity, so
        // the context follows the WebView onto the screen and back.
        val base = MutableContextWrapper(context.applicationContext)
        val view = WebView(base)
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
        applyLayout(view, key in desktopChats)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        view.setBackgroundColor(Color.WHITE)
        val window = FrameLayout(context.applicationContext)
        val page = Page(key, marker, view, base, window)
        page.state = page.state.copy(desktop = key in desktopChats)
        window.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
            override fun onReceivedTitle(view: WebView, title: String?) {
                publish(page, page.state.copy(title = title?.takeIf { it.isNotBlank() && it != TITLE }))
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null); page.session?.note("File upload requires the user's browser."); return true
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!userGesture || dialog || pages[page.key] !== page) {
                    page.session?.note("A popup was refused. Open its URL in this page instead."); return false
                }
                return followInPage(page, resultMsg)
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> page.session?.note("Download refused. This browser does not save files automatically.") }
        windows.addView(window, hidden())
        view.loadDataWithBaseURL(marker, String(MARKER_PAGE), "text/html", "UTF-8", marker)
        return page
    }

    /**
     * The WebView's half of [DesktopView]: the user agent, and a layout wider
     * than the phone, shrunk to fit and pinch-zoomable. Main thread.
     */
    private fun applyLayout(view: WebView, desktop: Boolean) = view.settings.run {
        // Null puts back the WebView's own user agent.
        userAgentString = if (desktop) DesktopView.userAgent(WebSettings.getDefaultUserAgent(context)) else null
        useWideViewPort = desktop
        loadWithOverviewMode = desktop
        builtInZoomControls = desktop
        displayZoomControls = false
    }

    override suspend fun layout(key: String, desktop: Boolean): WebAction {
        val session = onMain {
            val page = pages[key] ?: throw WebFailure("no_page", "This chat has no page open.", "Open an address first.")
            if (desktop) desktopChats += key else desktopChats -= key
            applyLayout(page.view, desktop)
            publish(page, page.state.copy(desktop = desktop))
            page.session
        }
        // A page not connected yet gets the viewport rewrite when it connects.
        return session?.desktopLayout(desktop) ?: onMain {
            pages[key]?.view?.reload()
            WebAction(if (desktop) "desktop site; the page was reloaded" else "mobile site; the page was reloaded")
        }
    }

    /** The user switched the page between the desktop and the mobile site. Main thread. */
    fun toggleLayout(key: String) {
        val page = pages[key] ?: return
        val desktop = !page.state.desktop
        scope.launch {
            runCatching { layout(key, desktop) }
            page.session?.note("The user switched this page to the ${if (desktop) "desktop" else "mobile"} site and it reloaded. Take a snapshot before acting.")
        }
    }

    /**
     * A tapped link that asks for a new window (target=_blank) opens in this
     * page instead, as one tab would. Main thread. The new window is a WebView
     * that never fetches anything: its first address is read off and the
     * window destroyed, and the page then loads that address through its own
     * client, so the URL policy applies as for any link. A window that names
     * no web address soon enough (a script writing into about:blank) stays
     * refused.
     */
    private fun followInPage(page: Page, resultMsg: android.os.Message): Boolean {
        val catcher = WebView(context.applicationContext)
        var done = false
        page.session?.popupOpening(true)
        fun finish(url: String?) = main.post {
            if (done) return@post
            done = true
            runCatching { catcher.stopLoading(); catcher.destroy() }
            val session = page.session
            val blocked = url?.let { policy.check(it, lookUp = false) as? WebUrlPolicy.Decision.Blocked }
            when {
                pages[page.key] !== page -> Unit
                url == null -> session?.note("A popup was refused. Open its URL in this page instead.")
                blocked != null -> session?.note(blocked.message)
                else -> {
                    session?.note("The link asked for a new window; it was opened in this page instead.")
                    page.view.loadUrl(url)
                    return@post
                }
            }
            session?.popupOpening(false)
        }
        fun web(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
        catcher.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                request.url.toString().takeIf(::web)?.let(::finish)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.isForMainFrame) request.url.toString().takeIf(::web)?.let(::finish)
                return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }
        (resultMsg.obj as WebView.WebViewTransport).webView = catcher
        resultMsg.sendToTarget()
        main.postDelayed({ finish(null) }, POPUP_WAIT_MS)
        return true
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
        // The user types passwords here, so the app says which site this is.
        publish(page, page.state.copy(host = host, secure = host == null || uri.scheme.equals("https", ignoreCase = true)))
    }

    /** Main thread. */
    private fun publish(page: Page, state: BrowserPageState) {
        page.state = state.copy(userControlled = page.userControlled)
        if (pages[page.key] === page) shown.update { it + (page.key to page.state) }
    }

    /** Main thread. Who has the page: the user, or the agent. */
    private fun control(page: Page, user: Boolean) {
        page.userControlled = user
        page.session?.userControlled = user
        page.frame?.locked = !user
        publish(page, page.state)
    }

    /**
     * Phone-sized and out of sight: the page lays out as it would on screen
     * and keeps drawing, so the agent can work while the user is elsewhere.
     */
    private fun hidden(): WindowManager.LayoutParams {
        val bounds = windows.currentWindowMetrics.bounds
        return WindowManager.LayoutParams(bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = bounds.width() - 1
                alpha = 0.01f
                title = "Hey Mike browser"
            }
    }

    /**
     * Put [key]'s page on Hey Mike's screen, in [frame]. Main thread. False
     * when the chat has no page. The page leaves whatever frame it was in.
     */
    fun attach(key: String, frame: BrowserFrame): Boolean {
        val page = pages[key] ?: return false
        if (page.frame === frame && page.view.parent === frame) return true
        (page.view.parent as? ViewGroup)?.removeView(page.view)
        page.frame = frame
        page.base.baseContext = frame.context
        frame.locked = !page.userControlled
        frame.addView(page.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        page.lastUsed = SystemClock.elapsedRealtime()
        return true
    }

    /** Take [key]'s page off the screen and back to its hidden window, if it is in [frame]. Main thread. */
    fun detach(key: String, frame: BrowserFrame) {
        val page = pages[key] ?: return
        if (page.frame !== frame) return
        frame.removeView(page.view)
        page.frame = null
        page.base.baseContext = context.applicationContext
        page.window.addView(page.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** The user takes the page from the agent. Main thread. */
    fun takeControl(key: String) {
        pages[key]?.let { control(it, user = true) }
    }

    /** The user hands the page back. Main thread. */
    fun giveBack(key: String) {
        val page = pages[key]?.takeIf { it.userControlled } ?: return
        control(page, user = false)
        page.session?.note("The user gave the page back. Take a snapshot before acting: they may have changed it.")
    }

    /** The user closed the page from the app. Main thread. */
    fun closeByUser(key: String) {
        pages[key]?.let { discard(it, "The user closed this page.") }
    }

    /**
     * The user gets the page, on Hey Mike's screen. True when the page is
     * there within a few seconds; false when the app could not come forward
     * (the phone may be locked), and the page then waits in the chat.
     */
    override suspend fun show(key: String): Boolean {
        if (!takeScreen(key)) throw WebFailure("stopped", "The run stopped before showing its browser.")
        onMain {
            val page = pages[key] ?: throw WebFailure("no_page", "This chat has no page open.", "Open an address first.")
            page.lastUsed = SystemClock.elapsedRealtime()
            control(page, user = true)
        }
        present(key)
        repeat((SHOW_WAIT_MS / POLL_MS).toInt()) {
            if (onMain { pages[key]?.frame != null }) return true
            delay(POLL_MS)
        }
        return false
    }

    override suspend fun hide(key: String) = onMain { pages[key]?.let { control(it, user = false) }; Unit }

    override suspend fun screenInUse(key: String) {
        val waiting = onMain {
            val visible = pages.values.filter { it.frame != null }
            if (visible.any { it.userControlled }) throw WebFailure(
                "user_control", "The user is controlling a visible browser page. No phone action was performed.",
                "Wait for the user to give the page back or return to the chat before using the phone's screen.",
            )
            visible.forEach { page -> publish(page, page.state.copy(panelDismissals = page.state.panelDismissals + 1)) }
            visible.isNotEmpty()
        }
        if (!waiting) return
        // Only the panel's UI disposal can acknowledge that its bar, dialogs
        // and WebView have all left the screen. Moving just the WebView is not enough.
        repeat((SHOW_WAIT_MS / POLL_MS).toInt()) {
            if (onMain { pages.values.none { it.frame != null } }) return
            delay(POLL_MS)
        }
        throw WebFailure(
            "browser_panel_busy", "The browser panel is still on screen. No phone action was performed.",
            "Return to the chat before trying the phone action again.",
        )
    }

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
            WebView.setWebContentsDebuggingEnabled(false)
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private companion object {
        const val TITLE = "Mike browser"
        const val MAX_PAGES = 4
        const val SHOW_WAIT_MS = 3_000L
        /** How long a new window may take to name its address before it stays refused. */
        const val POPUP_WAIT_MS = 3_000L
        const val POLL_MS = 100L
        val MARKER_PAGE = "<title>Mike browser</title>".toByteArray()
    }
}
