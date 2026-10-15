package dev.androidagent.browserprobe

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Process
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.toString() == "https://example.com/hey-mike-probe")
                    WebResourceResponse("text/html", "UTF-8", FIXTURE.byteInputStream()) else null
            override fun onPageFinished(view: WebView, url: String) {
                if (!started) { started = true; scope.launch { probe() } }
            }
        }
        // Current WebView exposes a data: history entry for loadDataWithBaseURL.
        view.loadUrl("https://example.com/hey-mike-probe")
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
                nativeTap = { x, y -> WebViewInput.tap(view, x, y) }) { summary ->
                    android.util.Log.i("MikeBrowserProbe", "approval refused: $summary")
                    false
                }
            session = page; page.start()
            val gateway = WebToolGateway(object : BrowserHost {
                override fun unavailable(): WebFailure? = null
                override suspend fun session(key: String) = page
                override suspend fun show(key: String) = false
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
            // Keep navigation measurements independent from screenshot support.
            if (intent.getBooleanExtra("benchmark", false)) benchmark(gateway, client)
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
    private suspend fun benchmark(gateway: WebToolGateway, client: CdpClient) {
        val output = File(getExternalFilesDir(null), "benchmark.jsonl")
        output.writeText("")
        suspend fun call(label: String, args: String): dev.androidagent.core.ToolResult {
            val start = android.os.SystemClock.elapsedRealtime()
            val result = gateway.invoke("browser", Json.parseToJsonElement(args).jsonObject)
            val row = buildJsonObject {
                put("label", label); put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                put("ok", result.success); put("result", result.text)
            }
            output.appendText("$row\n")
            if (result.text.contains("No node found at given location")) {
                val geometry = client.call("Runtime.evaluate", buildJsonObject {
                    put("expression", "JSON.stringify({inner:[innerWidth,innerHeight],visual:[visualViewport.width,visualViewport.height,visualViewport.offsetTop,visualViewport.scale],scroll:[scrollX,scrollY],radio:document.querySelector('input[value=medium]')?.getBoundingClientRect().toJSON()})")
                    put("returnByValue", true)
                })
                output.appendText(buildJsonObject { put("label", "$label-geometry"); put("geometry", geometry) }.toString() + "\n")
                output.appendText(buildJsonObject { put("label", "$label-layout"); put("geometry", client.call("Page.getLayoutMetrics")) }.toString() + "\n")
            }
            android.util.Log.i("MikeBrowserProbe", "benchmark $label ${android.os.SystemClock.elapsedRealtime() - start}ms ok=${result.success}")
            return result
        }
        repeat(3) { index ->
            val search = call("google-open-$index", """{"operation":"open","url":"https://www.google.com"}""")
            if (search.success) {
                val query = call("google-search-$index", """{"operation":"plan","steps":[
                    {"operation":"fill","selector":"[name=q]","value":"httpbin forms post"},
                    {"operation":"press","key":"Enter","verify":{"url":"/search","timeoutMs":15000}}
                ]}""")
                if (query.success) {
                    // Public test-page structure only; never an approval bypass.
                    val links = client.call("Runtime.evaluate", buildJsonObject {
                        put("expression", "JSON.stringify(Array.from(document.querySelectorAll('a,[role=link]')).filter(e=>e.textContent.includes('forms/post')).map(e=>({tag:e.localName,role:e.getAttribute('role'),href:e.getAttribute('href'),html:e.outerHTML.slice(0,2400)})))")
                        put("returnByValue", true)
                    })
                    output.appendText(buildJsonObject { put("label", "google-links-$index"); put("links", links) }.toString() + "\n")
                    call("google-click-$index", """{"operation":"click","text":"forms/post","role":"link"}""")
                }
            }
            val direct = call("direct-$index", """{"operation":"open","url":"https://httpbin.org/forms/post"}""")
            if (direct.success) call("form-plan-$index", """{"operation":"plan","steps":[
                {"operation":"fill","text":"Customer name:","value":"בדיקת דפדפן"},
                {"operation":"fill","text":"E-mail address:","value":"test@example.com"},
                {"operation":"fill","text":"Delivery instructions:","value":"בדיקת מילוי שדות בדפדפן הפנימי."},
                {"operation":"click","text":"Medium","role":"radio"},
                {"operation":"click","text":"Mushroom","role":"checkbox"}
            ]}""")
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
