package dev.androidagent.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

/** App-owned pages. All Android view operations run on the main thread. */
class AndroidBrowserHost(
    private val context: Context,
    private val takeScreen: suspend (String) -> Boolean = { true },
    private val confirm: suspend (String, String) -> Boolean = { _, _ -> false },
) : BrowserHost {
    private data class Page(val view: WebView, val window: LinearLayout, var session: BrowserSession? = null)
    private val pages = linkedMapOf<String, Page>()
    private val lock = Mutex()
    private val policy = WebUrlPolicy()
    private val windows = context.getSystemService(WindowManager::class.java)
    @Volatile private var count = 0
    @Volatile private var visibleKey: String? = null

    override fun unavailable(): WebFailure? = if (Settings.canDrawOverlays(context)) null else
        WebFailure("browser_overlay_permission", "Allow Hey Mike to display over other apps first.",
            "Open Hey Mike settings and allow its floating window. Wireless ADB is not needed.")

    override fun statusLine() = "Internal browser: ${if (unavailable() == null) "ready ($count pages)" else "floating window permission needed"}"

    override suspend fun session(key: String): BrowserSession = lock.withLock {
        withContext(Dispatchers.Main.immediate) { pages[key]?.session }?.takeIf { it.isOpen }?.let { return@withLock it }
        unavailable()?.let { throw it }
        withContext(Dispatchers.Main.immediate) { pages.remove(key)?.let(::destroy) }
        val marker = "https://hey-mike.invalid/page/${UUID.randomUUID()}"
        val page = withContext(Dispatchers.Main.immediate) { create(key, marker).also { pages[key] = it; count = pages.size } }
        try {
            val dialer = DevToolsDialer {
                val socket = LocalSocket()
                try {
                    socket.connect(LocalSocketAddress("webview_devtools_remote_${Process.myPid()}", LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 15_000
                    DevToolsStream(socket.inputStream, socket.outputStream, { socket.close() }, { socket.soTimeout = 0 })
                } catch (failure: Exception) { socket.close(); throw failure }
            }
            var pageId: String? = null
            repeat(30) {
                if (pageId == null) {
                    pageId = withContext(Dispatchers.IO) { runCatching { CdpClient.pages(dialer).firstOrNull { it.url == marker }?.id }.getOrNull() }
                    if (pageId == null) delay(100)
                }
            }
            val id = pageId ?: throw WebFailure("browser_cdp_unavailable",
                "This WebView did not expose its app-owned DevTools socket.",
                "Same-UID CDP must be checked on this phone; Wireless ADB is not used by this browser.")
            val client = withContext(Dispatchers.IO) { CdpClient.attach(dialer, id) }
            try {
                BrowserSession(client, policy, nativeTap = { x, y -> WebViewInput.tap(page.view, x, y) },
                    confirm = { summary -> confirm(key, summary) }).also {
                    it.start(); it.open("about:blank"); page.session = it
                }
            } catch (failure: Throwable) { client.close(); throw failure }
        } catch (failure: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                pages.remove(key)?.let(::destroy); count = pages.size
                if (pages.isEmpty()) WebView.setWebContentsDebuggingEnabled(false)
            }
            if (failure is IOException) throw WebFailure("browser_cdp_unavailable", "WebView CDP connection failed: ${failure.message}")
            throw failure
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(key: String, marker: String): Page {
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
        val page = Page(view, root)
        val bar = LinearLayout(context)
        bar.addView(TextView(context).apply { text = "Mike browser"; setTextColor(Color.BLACK) }, LinearLayout.LayoutParams(0, 56, 1f))
        bar.addView(Button(context).apply { text = "Hide"; setOnClickListener { park(key) } })
        bar.addView(Button(context).apply {
            text = "Close"; setOnClickListener {
                pages.remove(key)?.let(::destroy); count = pages.size; visibleKey = null
                if (pages.isEmpty()) WebView.setWebContentsDebuggingEnabled(false)
            }
        })
        root.addView(bar)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val blocked = policy.check(request.url.toString(), lookUp = false) as? WebUrlPolicy.Decision.Blocked
                blocked?.let { page.session?.note(it.message) }
                return blocked != null
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString() == marker) return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<title>Mike browser</title>".toByteArray()))
                val blocked = policy.check(request.url.toString()) as? WebUrlPolicy.Decision.Blocked ?: return null
                page.session?.note(blocked.message)
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                pages.remove(key)?.let(::destroy); count = pages.size
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null); page.session?.note("File upload requires the user's browser."); return true
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, resultMsg: android.os.Message): Boolean {
                page.session?.note("A popup was refused. Open its URL in this page instead."); return false
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> page.session?.note("Download refused. This browser does not save files automatically.") }
        windows.addView(root, layout(shown = false))
        view.loadDataWithBaseURL(marker, "<title>Mike browser</title>", "text/html", "UTF-8", marker)
        return page
    }

    private fun layout(shown: Boolean): WindowManager.LayoutParams {
        val bounds = windows.currentWindowMetrics.bounds
        return WindowManager.LayoutParams(bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (shown) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = if (shown) 0 else bounds.width() - 1
                alpha = if (shown) 1f else 0.01f
                title = "Hey Mike browser"
            }
    }

    private fun park(key: String) {
        pages[key]?.session?.userControlled = false
        pages[key]?.let { windows.updateViewLayout(it.window, layout(false)) }
        if (visibleKey == key) visibleKey = null
    }

    override suspend fun show(key: String) {
        if (!takeScreen(key)) throw WebFailure("stopped", "The run stopped before showing its browser.")
        withContext(Dispatchers.Main.immediate) {
            visibleKey?.let(::park)
            pages[key]?.let {
                it.session?.userControlled = true
                windows.updateViewLayout(it.window, layout(true)); it.view.requestFocus(); visibleKey = key
            }
        }
    }
    override suspend fun hide(key: String) = withContext(Dispatchers.Main.immediate) { park(key) }
    override suspend fun close(key: String): Boolean = lock.withLock {
        withContext(Dispatchers.Main.immediate) {
            val page = pages.remove(key) ?: return@withContext false
            destroy(page); count = pages.size
            if (visibleKey == key) visibleKey = null
            if (pages.isEmpty()) WebView.setWebContentsDebuggingEnabled(false)
            true
        }
    }
    suspend fun closeAll() = lock.withLock {
        withContext(Dispatchers.Main.immediate) {
            pages.values.toList().forEach(::destroy); pages.clear(); count = 0; visibleKey = null
            WebView.setWebContentsDebuggingEnabled(false)
        }
    }
    private fun destroy(page: Page) {
        page.session?.close()
        runCatching { windows.removeViewImmediate(page.window) }
        page.view.stopLoading(); page.view.destroy()
    }
}
