package dev.androidagent.browserprobe

import android.app.Activity
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.web.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

/** Physical-device tests of the production host. No fake host or auto-approved actions. */
class DeepProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var host: AndroidBrowserHost
    private lateinit var gateway: WebToolGateway
    private lateinit var frame: BrowserFrame
    private lateinit var composer: EditText
    private lateinit var status: TextView
    private lateinit var output: File
    private lateinit var key: String
    private var client: CdpClient? = null
    private var started = false
    private var failures = 0
    private var lastReply = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Deep production browser tests" }
        frame = BrowserFrame(this)
        composer = EditText(this).apply { hint = "Test chat composer" }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status); root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(composer)
        setContentView(root)
        output = File(getExternalFilesDir(null), "deep.jsonl")
        output.writeText("")
        key = File(filesDir, "deep-test-chat").absolutePath
        check(File(key).isDirectory || File(key).mkdirs())
        host = AndroidBrowserHost(this, confirm = { _, summary ->
            row("approval-refused", buildJsonObject { put("summary", summary) }); false
        }, present = { runOnUiThread { host.attach(it, frame) } })
        gateway = WebToolGateway(host) { android.util.Log.i("MikeBrowserTiming", it) }
        gateway.beginRun("deep-test", File(key))
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!started && Settings.canDrawOverlays(this)) {
            started = true
            scope.launch {
                try {
                    row("environment", buildJsonObject {
                        put("webview", WebView.getCurrentWebViewPackage()?.versionName)
                        put("productionHost", true); put("scenario", intent.getStringExtra("scenario") ?: "forms")
                    })
                    when (intent.getStringExtra("scenario")) {
                        "sites" -> sites()
                        "github" -> github()
                        "controls" -> controls()
                        "keyboard" -> keyboard()
                        else -> forms()
                    }
                    row("FINISHED", buildJsonObject { put("failures", failures) })
                    status.text = "Finished: $failures failures"
                } catch (error: Throwable) {
                    row("FATAL", buildJsonObject { put("error", error.toString()); put("stack", error.stackTraceToString()) })
                    status.text = error.toString()
                }
            }
        }
    }

    private fun row(label: String, data: JsonElement) {
        output.appendText(buildJsonObject { put("atMs", SystemClock.elapsedRealtime()); put("label", label); put("data", data) }.toString() + "\n")
        android.util.Log.i("MikeDeepProbe", label)
    }

    private suspend fun call(label: String, args: JsonObject, required: Boolean = true): Boolean {
        status.text = label
        val start = SystemClock.elapsedRealtime()
        // verify is a plan-step contract, not a standalone click option.
        val request = if (args["verify"] != null && args["operation"] != JsonPrimitive("plan")) buildJsonObject {
            put("operation", "plan"); put("steps", buildJsonArray { add(args) })
        } else args
        val result = gateway.invoke("browser", request)
        lastReply = result.text
        row(label, buildJsonObject { put("elapsedMs", SystemClock.elapsedRealtime() - start); put("ok", result.success); put("result", result.text) })
        if (!result.success && required) failures++
        return result.success
    }

    private suspend fun call(label: String, args: String, required: Boolean = true) =
        call(label, Json.parseToJsonElement(args).jsonObject, required)

    private suspend fun connect() {
        if (client != null) return
        val dialer = DevToolsDialer {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress("webview_devtools_remote_${Process.myPid()}", LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 10_000
            DevToolsStream(socket.inputStream, socket.outputStream, { socket.close() }, { socket.soTimeout = 0 })
        }
        client = withContext(Dispatchers.IO) { CdpClient.attach(dialer, CdpClient.pages(dialer).single().id) }
    }

    private suspend fun eval(expression: String): JsonElement? {
        connect()
        return client!!.call("Runtime.evaluate", buildJsonObject { put("expression", expression); put("returnByValue", true) })["result"]?.jsonObject?.get("value")
    }

    private suspend fun geometry(label: String) {
        val document = client?.call("DOM.getDocument")?.get("root")?.jsonObject?.get("nodeId")
        val node = document?.let { client!!.call("DOM.querySelector", buildJsonObject { put("nodeId", it); put("selector", "input[value=medium]") })["nodeId"] }
        val quads = node?.let { client!!.call("DOM.getContentQuads", buildJsonObject { put("nodeId", it) }) }
        val points = quads?.get("quads")?.jsonArray?.firstOrNull()?.jsonArray?.map { it.jsonPrimitive.double }
        val viewport = eval("JSON.stringify([visualViewport.offsetLeft,visualViewport.offsetTop])")?.jsonPrimitive?.content?.let { Json.parseToJsonElement(it).jsonArray.map { v -> v.jsonPrimitive.double } }
        val hits = buildJsonArray {
            if (points != null && viewport != null) for (offset in listOf(false, true)) {
                val x = listOf(points[0], points[2], points[4], points[6]).average() + if (offset) viewport[0] else 0.0
                val y = listOf(points[1], points[3], points[5], points[7]).average() + if (offset) viewport[1] else 0.0
                val hit = runCatching { client!!.call("DOM.getNodeForLocation", buildJsonObject { put("x", kotlin.math.round(x).toInt()); put("y", kotlin.math.round(y).toInt()); put("includeUserAgentShadowDOM", true) }) }.getOrElse { buildJsonObject { put("error", it.toString()) } }
                val backend = hit["backendNodeId"]
                val html = backend?.let { client!!.call("DOM.getOuterHTML", buildJsonObject { put("backendNodeId", it) }) }
                add(buildJsonObject { put("offsetAdded", offset); put("hit", hit); put("html", html ?: JsonNull) })
            }
        }
        row(label, buildJsonObject {
            put("quads", quads ?: JsonNull); put("hits", hits)
            put("dom", eval("JSON.stringify({inner:[innerWidth,innerHeight],visual:[visualViewport.width,visualViewport.height,visualViewport.offsetLeft,visualViewport.offsetTop,visualViewport.scale],scroll:[scrollX,scrollY],focus:document.activeElement?.outerHTML,radio:document.querySelector('input[value=medium]')?.getBoundingClientRect().toJSON(),hit:(()=>{const r=document.querySelector('input[value=medium]')?.getBoundingClientRect();return r?document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.outerHTML:null})()})") ?: JsonNull)
            put("layout", client!!.call("Page.getLayoutMetrics"))
            put("attached", frame.childCount > 0)
            put("frameWidth", frame.width); put("frameHeight", frame.height)
            put("imeVisible", frame.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
        })
    }

    private suspend fun forms() {
        // Same real public form, production parked/attached WebView, three repeats each.
        for (attached in listOf(false, true)) repeat(3) { index ->
            val prefix = "form-${if (attached) "attached" else "parked"}-$index"
            call("$prefix-open", """{"operation":"open","url":"https://httpbin.org/forms/post"}""")
            if (attached) host.attach(key, frame) else host.detach(key, frame)
            if (attached && index == 1) {
                composer.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
                delay(500)
            }
            val filled = call("$prefix-fill", """{"operation":"plan","steps":[
                {"operation":"fill","selector":"[name=custname]","value":"בדיקת דפדפן"},
                {"operation":"fill","selector":"[name=custemail]","value":"test@example.com"},
                {"operation":"fill","selector":"[name=comments]","value":"בדיקת מילוי שדות בדפדפן הפנימי."}
            ]}""")
            geometry("$prefix-before-medium")
            val clicked = filled && call("$prefix-medium", """{"operation":"click","selector":"input[value=medium]","verify":{"selector":"input[value=medium]:checked"}}""")
            geometry("$prefix-after-medium")
            if (clicked) {
                call("$prefix-update-check", """{"operation":"plan","steps":[
                    {"operation":"click","selector":"input[value=bacon]"},
                    {"operation":"click","selector":"input[value=mushroom]"},
                    {"operation":"scroll","direction":"down"},
                    {"operation":"scroll","direction":"up"},
                    {"operation":"fill","selector":"[name=custname]","value":"הבדיקה הצליחה"},
                    {"operation":"click","selector":"input[value=large]"},
                    {"operation":"click","selector":"input[value=bacon]"},
                    {"operation":"fill","selector":"[name=comments]","value":"הערכים עודכנו ונבדקו"},
                    {"operation":"check","checks":[
                        {"selector":"[name=custname]","value":"הבדיקה הצליחה"},
                        {"selector":"[name=custemail]","value":"test@example.com"},
                        {"selector":"[name=comments]","value":"הערכים עודכנו ונבדקו"},
                        {"selector":"input[value=small]","checked":false},
                        {"selector":"input[value=medium]","checked":false},
                        {"selector":"input[value=large]","checked":true},
                        {"selector":"input[value=bacon]","checked":false},
                        {"selector":"input[value=cheese]","checked":false},
                        {"selector":"input[value=onion]","checked":false},
                        {"selector":"input[value=mushroom]","checked":true}
                    ]}
                ]}""")
            }
        }
        call("final-show", """{"operation":"show"}""")
    }

    private suspend fun sites() {
        // Select links from each site's live DOM, then use the production click path.
        val routes = listOf(
            Triple("wikipedia", "https://en.wikipedia.org/wiki/Android_(operating_system)", "Linux"),
            Triple("mdn", "https://developer.mozilla.org/en-US/docs/Web/JavaScript", "JavaScript reference"),
            Triple("github", "https://github.com/whatwg/html", "Issues")
        )
        repeat(2) { index ->
            for ((name, url, label) in routes) {
                if (!call("$name-$index-open", buildJsonObject { put("operation", "open"); put("url", url) })) continue
                call("$name-$index-snapshot", """{"operation":"snapshot","maxChars":18000,"force":true}""")
                val candidate = eval("""JSON.stringify(Array.from(document.querySelectorAll('a[href]')).filter(a=>(a.textContent.trim()===${JsonPrimitive(label)} || (${JsonPrimitive(label)}==='Issues' && a.textContent.trim().startsWith('Issues') && a.pathname.startsWith('/whatwg/html/issues'))) && /^https?:/.test(a.href) && a.getClientRects().length).slice(0,1).map(a=>({href:a.href,text:a.textContent.trim()})))""")
                row("$name-$index-link", candidate ?: JsonNull)
                if (name == "mdn") row("mdn-visibility-$index", eval("""JSON.stringify(Array.from(document.querySelectorAll('a')).filter(a=>a.textContent.trim()==='JavaScript reference').slice(0,1).map(a=>{let e=a,r=[];while(e){const s=getComputedStyle(e);r.push({tag:e.localName,display:s.display,visibility:s.visibility,contentVisibility:s.contentVisibility,check:e.checkVisibility(),rect:e.getBoundingClientRect().toJSON(),aria:e.getAttribute('aria-hidden')});e=e.parentElement}return r}))""") ?: JsonNull)
                val links = candidate?.jsonPrimitive?.content?.let { Json.parseToJsonElement(it).jsonArray }
                if (links.isNullOrEmpty()) { failures++; continue }
                val href = links.first().jsonObject["href"]!!.jsonPrimitive.content
                // Snapshot refs remain tied to their nodes if a client-rendered
                // site inserts siblings after this read; positional CSS does not.
                val ref = Regex("""(?m)^link "([^"]*)" \[([^]]+)]""").findAll(lastReply).firstOrNull {
                    it.groupValues[1] == label || label == "Issues" && it.groupValues[1].startsWith("Issues")
                }?.groupValues?.get(2)
                val clicked = call("$name-$index-click-$label", buildJsonObject {
                    put("operation", "click")
                    if (ref != null) put("ref", ref) else { put("text", label); put("role", "link") }
                    put("verify", buildJsonObject { put("url", href); put("timeoutMs", 15000) })
                })
                call("$name-$index-scroll-and-read", """{"operation":"plan","steps":[{"operation":"scroll","direction":"down"},{"operation":"scroll","direction":"down"},{"operation":"scroll","direction":"up"}]}""")
                if (clicked) call("$name-$index-back", buildJsonObject {
                    put("operation", "back"); put("verify", buildJsonObject { put("url", url); put("timeoutMs", 15000) })
                })
                if (name == "github") {
                    // History updates the URL before GitHub's client router
                    // restores the repository body. Verify that body as well.
                    val backReply = lastReply
                    if (!call("$name-$index-repository-ready", """{"operation":"wait","text":"html.spec.whatwg.org/multipage/","timeoutMs":5000}""", required = false)) {
                        // The router kept the Issues view: apply the reply's remedy.
                        if (!backReply.contains("same document")) failures++
                        call("$name-$index-reload", """{"operation":"reload","verify":{"text":"html.spec.whatwg.org/multipage/","timeoutMs":15000}}""")
                    }
                    val external = eval("JSON.stringify(Array.from(document.querySelectorAll('a[href]')).filter(a=>a.href==='https://html.spec.whatwg.org/multipage/' && a.getClientRects().length).map(a=>({text:a.textContent.trim(),href:a.href})))")
                    row("$name-$index-cross-site-link", external ?: JsonNull)
                    // GitHub opens this external link in a new window. The
                    // browser opens it in this page instead; check the destination.
                    val beforeClick = host.session(key).inputs
                    var popupClicked = call("$name-$index-popup-link", """{"operation":"click","text":"html.spec.whatwg.org/multipage/","role":"link"}""", required = false)
                    if (!popupClicked && lastReply.contains("error: stale_ref") && host.session(key).inputs == beforeClick) {
                        // GitHub replaced the resolved anchor before any input.
                        // Recover only this known-unperformed step, never a
                        // click with a lost/uncertain result or a denied approval.
                        row("$name-$index-known-stale-recovery", buildJsonObject { put("inputsUnchanged", true) })
                        call("$name-$index-refresh", """{"operation":"snapshot","force":true}""")
                        popupClicked = call("$name-$index-popup-link-resumed", """{"operation":"click","text":"html.spec.whatwg.org/multipage/","role":"link"}""", required = false)
                    }
                    if (!popupClicked) failures++
                    val destination = host.session(key).location().first
                    val followed = lastReply.contains("opened in this page instead") && destination.startsWith("https://html.spec.whatwg.org/multipage/")
                    row("$name-$index-cross-site-verdict", buildJsonObject { put("url", destination); put("pass", followed) })
                    if (!followed) failures++
                    call("whatwg-$index-read", """{"operation":"snapshot","maxChars":16000,"force":true}""")
                }
            }
        }
        call("sites-show", """{"operation":"show"}""")
    }

    /**
     * The hosting phone's reported GitHub route, as a model drives it: click
     * Issues by snapshot ref, go Back, then record what the body shows, not
     * only the address. Also clicks the external target=_blank link.
     */
    private suspend fun github() {
        val repo = "https://github.com/whatwg/html"
        repeat(5) { index ->
            val p = "gh-$index"
            call("$p-open", buildJsonObject { put("operation", "open"); put("url", repo) })
            val ref = Regex("""(?m)^link "(Issues[^"]*)" \[([^]]+)]""").find(lastReply)?.groupValues?.get(2)
            row("$p-issues-ref", JsonPrimitive(ref ?: "none"))
            val clicked = call("$p-click-issues", buildJsonObject {
                put("operation", "click")
                if (ref != null) put("ref", ref) else { put("text", "Issues"); put("role", "link") }
                put("verify", buildJsonObject { put("url", "/whatwg/html/issues"); put("timeoutMs", 15000) })
            })
            if (!clicked) return@repeat
            call("$p-back", buildJsonObject {
                put("operation", "back"); put("verify", buildJsonObject { put("url", repo); put("timeoutMs", 15000) })
            })
            for (sample in listOf(0L, 3000L, 8000L)) {
                if (sample > 0) delay(sample - if (sample == 8000L) 3000L else 0L)
                row("$p-body-${sample}ms", eval("""JSON.stringify({url:location.href,title:document.title,
                    readme:!!document.querySelector('article.markdown-body, #readme'),
                    issuesList:!!document.querySelector('[data-testid="list-view-items"], [aria-label="Issues"] li'),
                    error:/error while loading|Please reload this page/i.test(document.body.innerText),
                    head:document.body.innerText.replace(/\s+/g,' ').slice(0,300)})""") ?: JsonNull)
            }
            val restored = eval("!!document.querySelector('article.markdown-body, #readme')")?.jsonPrimitive?.booleanOrNull == true
            if (!restored && lastReply.contains("same document")) {
                // The product's remedy for a client router that did not restore its view.
                call("$p-reload", """{"operation":"reload","verify":{"text":"html.spec.whatwg.org/multipage/","timeoutMs":15000}}""")
            }
            val external = call("$p-external", """{"operation":"click","text":"html.spec.whatwg.org/multipage/","role":"link","verify":{"url":"html.spec.whatwg.org","timeoutMs":15000}}""", required = false)
            row("$p-external-result", buildJsonObject { put("ok", external); put("url", host.session(key).location().first) })
        }
        call("gh-show", """{"operation":"show"}""")
    }

    private suspend fun keyboard() {
        for (height in listOf(2100, 1800, 1450, 1100)) repeat(2) { index ->
            val label = "keyboard-$height-$index"
            call("$label-open", """{"operation":"open","url":"https://httpbin.org/forms/post"}""")
            host.attach(key, frame)
            frame.layoutParams = LinearLayout.LayoutParams(-1, height)
            composer.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
            delay(400)
            call("$label-fill", """{"operation":"plan","steps":[{"operation":"fill","selector":"[name=custname]","value":"בדיקה"},{"operation":"fill","selector":"[name=custemail]","value":"test@example.com"},{"operation":"fill","selector":"[name=comments]","value":"מקלדת פתוחה"}]}""")
            geometry("$label-before-medium")
            call("$label-medium", """{"operation":"click","selector":"input[value=medium]","verify":{"selector":"input[value=medium]:checked"}}""")
            geometry("$label-after-medium")
        }
    }

    private suspend fun controls() {
        call("controls-open", """{"operation":"open","url":"https://example.com"}""")
        host.attach(key, frame)
        // Controlled test document only. Real cover must still block; failed steps must not replay.
        eval("document.body.innerHTML='<label>Query<input id=q></label><label>Medium<input id=m type=radio></label><button id=b onclick=\"this.textContent=String(++window.count)\">Count</button><div id=cover style=\"position:fixed;inset:0;background:#ddd;z-index:999\">Test cover</div>';window.count=0")
        val blocked = call("real-cover-refused", """{"operation":"click","selector":"#m"}""", required = false)
        val unchanged = eval("document.querySelector('#m').checked") == JsonPrimitive(false)
        row("cover-verdict", buildJsonObject { put("pass", !blocked && unchanged) })
        if (blocked || !unchanged) failures++
        eval("document.querySelector('#cover').remove()")
        call("uncovered-plan", """{"operation":"plan","steps":[{"operation":"fill","selector":"#q","value":"שלום"},{"operation":"click","selector":"#m"},{"operation":"check","checks":[{"selector":"#m","checked":true},{"selector":"#q","value":"שלום"}]}]}""")
        call("handoff", """{"operation":"show"}""")
        val userBlocked = call("user-control-refused", """{"operation":"click","selector":"#b"}""", required = false)
        if (userBlocked || eval("window.count") != JsonPrimitive(0)) failures++
        host.giveBack(key)
        call("returned-control", """{"operation":"click","selector":"#b"}""")
        gateway.revoke()
        val stopBlocked = try {
            call("stop-refused", """{"operation":"click","selector":"#b"}""", required = false)
        } catch (stopped: IllegalStateException) {
            check(stopped.message == "Run stopped. No browser action was performed.")
            row("stop-refused", buildJsonObject { put("rejectedBeforeDispatch", true) })
            false
        }
        if (stopBlocked || eval("window.count") != JsonPrimitive(1)) failures++
        gateway.beginRun("deep-test-after-stop", File(key))
        val running = scope.async {
            gateway.invoke("browser", Json.parseToJsonElement("""{"operation":"plan","steps":[{"operation":"click","selector":"#b"},{"operation":"wait","text":"Never present in this fixture","timeoutMs":15000},{"operation":"click","selector":"#b"}]}""").jsonObject)
        }
        repeat(30) { if (eval("window.count") != JsonPrimitive(2)) delay(100) }
        gateway.revoke()
        running.cancelAndJoin()
        val stoppedCount = eval("window.count")
        row("stop-mid-plan-verdict", buildJsonObject { put("count", stoppedCount ?: JsonNull); put("pass", stoppedCount == JsonPrimitive(2)) })
        if (stoppedCount != JsonPrimitive(2)) failures++
        gateway.beginRun("deep-test-long-plan", File(key))
        val longPlan = buildJsonArray {
            add(buildJsonObject { put("operation", "fill"); put("selector", "#q"); put("value", "24 steps שלום") })
            add(buildJsonObject { put("operation", "check"); put("checks", buildJsonArray {
                add(buildJsonObject { put("selector", "#q"); put("value", "24 steps שלום") })
            }) })
            repeat(21) { add(buildJsonObject { put("operation", "click"); put("selector", "#b") }) }
            add(buildJsonObject { put("operation", "check"); put("checks", buildJsonArray {
                add(buildJsonObject { put("selector", "#q"); put("value", "24 steps שלום") })
                add(buildJsonObject { put("selector", "#m"); put("checked", true) })
            }) })
        }
        call("24-steps-and-handoff", buildJsonObject { put("operation", "plan"); put("show", true); put("steps", longPlan) })
        val finalCount = eval("window.count")
        if (finalCount != JsonPrimitive(23)) failures++
        row("24-step-independent-judge", buildJsonObject { put("count", finalCount ?: JsonNull); put("pass", finalCount == JsonPrimitive(23)) })
        host.giveBack(key)
        host.detach(key, frame)
        val second = File(filesDir, "deep-second-chat").absolutePath
        check(File(second).isDirectory || File(second).mkdirs())
        val other = WebToolGateway(host).also { it.beginRun("second-chat", File(second)) }
        val opened = other.invoke("browser", Json.parseToJsonElement("""{"operation":"open","url":"https://example.com"}""").jsonObject)
        if (!opened.success) failures++
        host.attach(second, frame)
        host.detach(second, frame)
        host.attach(key, frame)
        call("two-chat-state-retained", """{"operation":"check","checks":[{"selector":"#q","value":"24 steps שלום"},{"selector":"#m","checked":true}]}""")
        host.close(second)
        row("two-chat-host-verdict", buildJsonObject { put("pages", host.pageStates.value.size); put("attached", frame.childCount == 1) })
        if (host.pageStates.value.size != 1 || frame.childCount != 1) failures++
        call("controls-final-show", """{"operation":"show"}""")
    }

    override fun onDestroy() {
        scope.cancel(); client?.close(); super.onDestroy()
    }
}
