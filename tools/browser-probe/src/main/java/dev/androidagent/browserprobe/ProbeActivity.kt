package dev.androidagent.browserprobe

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Process
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.web.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

/** A separate, non-debuggable, test-signed app: never replaces Hey Mike. */
class ProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var view: WebView
    private lateinit var status: TextView
    private var session: BrowserSession? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WebView.setWebContentsDebuggingEnabled(true)
        view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
        }
        status = TextView(this).apply { text = "Checking release WebView CDP..."; textSize = 18f }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status); root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        var started = false
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!started) { started = true; scope.launch { probe() } }
            }
        }
        view.loadDataWithBaseURL("https://example.com/hey-mike-probe", FIXTURE, "text/html", "UTF-8", null)
    }
    private suspend fun probe() {
        val evidence = mutableListOf<String>()
        try {
            val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            check(!debuggable) { "Probe must be non-debuggable" }
            evidence += "debuggable=false"
            evidence += "webview=${WebView.getCurrentWebViewPackage()?.versionName}"
            val dialer = DevToolsDialer {
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress("webview_devtools_remote_${Process.myPid()}", LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 10_000
                DevToolsStream(socket.inputStream, socket.outputStream, { socket.close() }, { socket.soTimeout = 0 })
            }
            val client = withContext(Dispatchers.IO) {
                CdpClient.attach(dialer, CdpClient.pages(dialer).single().id)
            }
            evidence += "same_uid_cdp=connected"
            val page = BrowserSession(client, WebUrlPolicy(), BrowserSession.Pointer.TOUCH,
                nativeTap = { x, y -> WebViewInput.tap(view, x, y) }) { false }
            session = page; page.start()
            val gateway = WebToolGateway(object : BrowserHost {
                override fun unavailable(): WebFailure? = null
                override suspend fun session(key: String) = page
                override suspend fun show(key: String) = Unit
                override suspend fun hide(key: String) = Unit
                override suspend fun close(key: String): Boolean { page.close(); return true }
                override fun statusLine() = "Probe"
            })
            gateway.beginRun("probe", filesDir)
            val result = gateway.invoke("browser", Json.parseToJsonElement("""{"operation":"plan","steps":[
                {"operation":"fill","text":"Query","value":"hello & שלום"},
                {"operation":"fill","text":"Address","value":"fixture"},
                {"operation":"click","text":"Apply","verify":{"text":"Done hello & שלום true","timeoutMs":5000}}
            ]}""").jsonObject)
            evidence += result.text
            check(result.success) { "Plan failed" }
            evidence += "plan_fill_fill_touch=PASS"
            val screenshot = page.screenshot()
            check(screenshot?.startsWith("iVBOR") == true) { "Screenshot unavailable" }
            File(getExternalFilesDir(null), "probe.png").writeBytes(android.util.Base64.decode(screenshot, android.util.Base64.DEFAULT))
            evidence += "screenshot=PASS"
            page.open("https://example.com")
            check(page.snapshot().contains("Example Domain")) { "Public navigation failed" }
            evidence += "public_https_navigation=PASS"
            save("PASS", evidence)
        } catch (failure: Throwable) {
            evidence += "${failure.javaClass.simpleName}: ${failure.message}"
            save("FAIL", evidence)
        }
    }
    private fun save(outcome: String, evidence: List<String>) {
        val output = "$outcome\n" + evidence.joinToString("\n")
        File(getExternalFilesDir(null), "probe.txt").writeText(output)
        status.text = output
        android.util.Log.i("MikeBrowserProbe", output)
    }
    override fun onDestroy() {
        scope.cancel(); session?.close(); view.destroy(); WebView.setWebContentsDebuggingEnabled(false)
        super.onDestroy()
    }
    companion object {
        private const val FIXTURE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Mike Browser Probe</title>
          <h1>Browser test</h1><label>Query <input type="search" id="query"></label><p><label>Address <input id="address"></label></p>
          <button type="button" onclick="document.querySelector('#result').textContent='Done '+document.querySelector('#query').value+' '+event.isTrusted">Apply</button><p id="result">Ready</p>"""
    }
}
