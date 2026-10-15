package dev.androidagent.web

import dev.androidagent.core.ToolResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files

/** Live CDP/DOM tests. Opt in with HEYMIKE_TEST_CHROME; ordinary CI can still run policy tests. */
class BrowserIntegrationTest {
    companion object {
        private lateinit var process: java.lang.Process
        private lateinit var directory: File
        private lateinit var server: ServerSocket
        private var port = 0
        private var fixtureUrl = ""
        @JvmStatic @BeforeClass fun startChrome() {
            val executable = System.getenv("HEYMIKE_TEST_CHROME")
            Assume.assumeTrue("Set HEYMIKE_TEST_CHROME for live CDP tests", executable != null && File(executable).isFile)
            directory = Files.createTempDirectory("hey-mike-browser-test-").toFile()
            process = ProcessBuilder(executable, "--headless=new", "--remote-debugging-port=0", "--user-data-dir=${directory.absolutePath}",
                "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank")
                .redirectErrorStream(true).redirectOutput(File(directory, "chrome.log")).start()
            val activePort = File(directory, "DevToolsActivePort")
            val deadline = System.nanoTime() + 15_000_000_000
            while (!activePort.isFile && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(activePort.isFile) { "Chrome did not start: ${File(directory, "chrome.log").readText().takeLast(1500)}" }
            port = activePort.readLines().first().toInt()
            server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
            thread(isDaemon = true, name = "browser-fixture-http") {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching { client.use {
                            it.soTimeout = 5000
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                            if (request.contains(" /slow-image ")) Thread.sleep(8_000)
                            val html = when {
                                request.contains(" /slow-page ") -> "<h1>Usable page</h1><input aria-label='Ready field'><img src='/slow-image'>"
                                request.contains(" /frame ") -> "<button onclick='this.textContent=\"Frame done\"'>Frame button</button>"
                                request.contains(" /form ") -> "<form onsubmit='parent.frameSubmits++;return false'><label>Message <input></label></form>"
                                request.contains(" /spa ") -> "<title>Router</title><h1 id='view'>Repository</h1><button onclick=\"history.pushState({},'','/spa/issues');view.textContent='Issues list'\">Issues</button>" +
                                    "<script>addEventListener('popstate',()=>view.textContent='Repository')</script>"
                                request.contains(" /secret ") -> "<title>Router</title><p>Router admin password</p>"
                                else -> FIXTURE
                            }
                            val bytes = html.toByteArray()
                            it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            it.getOutputStream().write(bytes); it.getOutputStream().flush()
                        } }
                    }
                }
            }
            fixtureUrl = "http://127.0.0.1:${server.localPort}/"
        }
        @JvmStatic @AfterClass fun stopChrome() {
            if (::server.isInitialized) server.close()
            if (::process.isInitialized) {
                process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            if (::directory.isInitialized) directory.deleteRecursively()
        }
        private val FIXTURE = """<!doctype html><title>Browser fixture</title>
          <h1>Fixture</h1><label>Query <input id="query" type="search"></label>
          <label>Address <input id="address"></label><label>Password <input type="password" id="secret" value="hidden-secret"></label>
          <label>Card <input autocomplete="cc-number" value="4111111111111111"></label>
          <select aria-label="Choice"><option value="a">Alpha</option><option value="b">Beta</option></select>
          <button id="apply" type="button" onclick="clicks++; document.querySelector('#status').textContent='Applied '+document.querySelector('#query').value+' '+event.isTrusted">Apply</button>
          <button type="button">Duplicate</button><button type="button">Duplicate</button>
          <button id="send" type="button" onclick="sends++">Send</button><p id="status">Ready</p>
          <div id="shadow"></div><iframe src="/frame" title="Inner frame"></iframe>
          <article><h2>Story</h2><p>Readable article paragraph.</p></article>
          <label>Stolen <input id="stolen" onfocus="address.focus()"></label>
          <span style="position:relative;display:inline-block"><button id="covered" type="button" onclick="coveredClicks++">Covered</button><span onclick="bannerClicks++" style="position:absolute;left:0;top:0;right:0;bottom:0;background:#fff">Cookie banner</span></span>
          <span class="rm">Remove row</span> <span class="rm">Remove row</span>
          <iframe src="/form" title="Form frame"></iframe><iframe src="/secret" title="Secret frame"></iframe>
          <script>var clicks=0,sends=0,inputs=[],coveredClicks=0,bannerClicks=0,removes=0,frameSubmits=0;query.addEventListener('input',e=>inputs.push(e.isTrusted));
            document.querySelectorAll('.rm').forEach(s=>s.addEventListener('click',()=>removes++));
            const root=shadow.attachShadow({mode:'open'});root.innerHTML='<label>Shadow field <input></label><button type="button">Shadow button</button>';
            root.querySelector('button').onclick=function(){this.textContent='Shadow done'};
          </script>"""
    }

    private lateinit var cdp: CdpClient
    private lateinit var page: BrowserSession
    private lateinit var gateway: WebToolGateway
    private var approve = true
    private var confirmDelayMs = 0L
    private var duringApproval: (suspend () -> Unit)? = null
    private var loseReplyFor: String? = null
    private var failReadFor: String? = null
    private val confirmations = mutableListOf<String>()
    private val dialer = DevToolsDialer {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 10_000
        DevToolsStream(socket.getInputStream(), socket.getOutputStream(), { socket.close() }, { socket.soTimeout = 0 })
    }

    /** The fixture is on loopback, so private addresses are allowed; "/secret" stands for a blocked address. */
    private val policy = object : WebUrlPolicy(allowPrivateNetwork = true) {
        override fun check(raw: String, lookUp: Boolean): Decision =
            if (raw.contains("/secret")) Decision.Blocked("private_network_blocked", "Blocked fixture address.", "Nothing was opened.")
            else super.check(raw, lookUp)
    }

    private fun host(session: BrowserSession) = object : BrowserHost {
        override fun unavailable(): WebFailure? = null
        override suspend fun session(key: String) = session
        override suspend fun show(key: String): Boolean { session.userControlled = true; return true }
        override suspend fun hide(key: String) { session.userControlled = false }
        override suspend fun close(key: String): Boolean { session.close(); return true }
        override fun statusLine() = "Fixture"
    }

    @Before fun openFixture() = runBlocking {
        cdp = CdpClient.attach(dialer, CdpClient.pages(dialer).first().id)
        val connection = object : CdpConnection by cdp {
            override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
                val expression = params["expression"]?.jsonPrimitive?.content.orEmpty()
                if (failReadFor?.let(expression::contains) == true)
                    throw CdpException(method, -32000, "Fixture read unavailable")
                val result = cdp.call(method, params, timeoutMs)
                if (loseReplyFor?.let(expression::contains) == true && result["result"]?.jsonObject?.get("value") != JsonPrimitive("__mike_missing__")) {
                    loseReplyFor = null
                    throw CdpException(method, CdpClient.TIMEOUT_CODE, "Fixture: reply lost after execution")
                }
                return result
            }
        }
        page = BrowserSession(connection, policy, BrowserSession.Pointer.MOUSE) {
            confirmations += it; delay(confirmDelayMs); duringApproval?.invoke(); approve
        }
        page.start(); page.open(fixtureUrl)
        gateway = WebToolGateway(host(page))
        gateway.beginRun("test", directory)
    }
    @After fun closeFixture() { if (::page.isInitialized) page.close() }
    private suspend fun call(json: String): ToolResult = gateway.invoke("browser", Json.parseToJsonElement(json).jsonObject)
    private suspend fun value(expression: String): JsonElement? = (cdp.call("Runtime.evaluate", buildJsonObject {
        put("expression", expression); put("returnByValue", true)
    })["result"] as JsonObject)["value"]

    @Test fun slowSubresourceDoesNotBlockAnInteractiveDocument() = runBlocking {
        val started = System.nanoTime()
        val result = call("""{"operation":"open","url":"${fixtureUrl}slow-page"}""")
        val elapsed = (System.nanoTime() - started) / 1_000_000
        println("BROWSER_BENCHMARK slow-image-8000 openMs=$elapsed")
        assertTrue(result.text, result.success)
        assertTrue(result.text, result.text.contains("Ready field"))
        assertTrue("An 8s image blocked a usable document for ${elapsed}ms", elapsed < 5_000)
    }

    @Test fun backWithinAClientRouterSaysItStayedInTheSameDocument() = runBlocking {
        assertTrue(call("""{"operation":"open","url":"${fixtureUrl}spa"}""").success)
        val clicked = call("""{"operation":"plan","steps":[{"operation":"click","text":"Issues","role":"button","verify":{"text":"Issues list"}}]}""")
        assertTrue(clicked.text, clicked.success)
        val back = call("""{"operation":"back"}""")
        assertTrue(back.text, back.success)
        assertTrue(back.text, back.text.contains("same document"))
        assertTrue(back.text, back.text.contains("heading \"Repository\""))
    }

    @Test fun backAcrossDocumentsHasNoSameDocumentNote() = runBlocking {
        assertTrue(call("""{"operation":"open","url":"${fixtureUrl}slow-page"}""").success)
        val back = call("""{"operation":"back"}""")
        assertTrue(back.text, back.success)
        assertFalse(back.text, back.text.contains("same document"))
        assertTrue(back.text, back.text.contains("Browser fixture"))
    }

    @Test fun aSettleReadThatLosesTheDocumentDoesNotFailASentClick() = runBlocking {
        failReadFor = "__mike.settle"
        val result = call("""{"operation":"plan","steps":[{"operation":"click","text":"Apply","role":"button"}]}""")
        failReadFor = null
        assertTrue(result.text, result.success)
        assertEquals(JsonPrimitive(1), value("clicks"))
        assertTrue(result.text, result.text.contains("did not settle"))
        assertTrue(result.text, result.text.contains("navigationReadRaces"))
    }

    @Test fun aFailedStepNamesItsErrorTypeInTheTimings() = runBlocking {
        val result = call("""{"operation":"plan","steps":[{"operation":"click","text":"No such control","role":"button"}]}""")
        assertFalse(result.text, result.success)
        val timings = Json.parseToJsonElement(result.text.substringAfterLast("\ntimings: ")).jsonObject
        assertEquals("not_found", timings["errorType"]!!.jsonPrimitive.content)
        assertEquals("not_found", timings["steps"]!!.jsonArray[0].jsonObject["timings"]!!.jsonObject["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun planReturnsStepTimingsAndOnlyOneFinalSnapshot() = runBlocking {
        val result = call("""{"operation":"plan","steps":[{"operation":"fill","text":"Query","value":"timing test"},{"operation":"click","text":"Apply","verify":{"text":"Applied timing test true"}}]}""")
        assertTrue(result.text, result.success)
        val timings = Json.parseToJsonElement(result.text.substringAfterLast("\ntimings: ")).jsonObject
        assertEquals(2, timings["steps"]!!.jsonArray.size)
        val second = timings["steps"]!!.jsonArray[1].jsonObject["timings"]!!.jsonObject
        assertEquals("not_requested", timings["steps"]!!.jsonArray[0].jsonObject["verification"]!!.jsonPrimitive.content)
        assertEquals("passed", timings["steps"]!!.jsonArray[1].jsonObject["verification"]!!.jsonPrimitive.content)
        assertTrue(second["phasesMs"]!!.jsonObject.containsKey("verify"))
        assertFalse(second["phasesMs"]!!.jsonObject.containsKey("snapshot"))
        assertTrue(timings["phasesMs"]!!.jsonObject.containsKey("snapshot"))
        assertFalse(timings.toString().contains("timing test"))
    }

    @Test fun aControlAboveTheVisualViewportIsNotClicked() = runBlocking {
        page.snapshot() // Install the helper in its isolated world.
        val context = cdp.call("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", cdp.call("Page.getFrameTree")["frameTree"]!!.jsonObject["frame"]!!.jsonObject["id"]!!)
            put("worldName", "hey_mike")
        })["executionContextId"]!!
        cdp.call("Runtime.evaluate", buildJsonObject {
            put("contextId", context)
            put("expression", "Object.defineProperty(window,'visualViewport',{configurable:true,value:{offsetLeft:0,offsetTop:500,width:innerWidth,height:100}})")
        })
        val result = call("""{"operation":"click","text":"Apply"}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("not_visible"))
        assertEquals(JsonPrimitive(0), value("clicks"))
        cdp.call("Runtime.evaluate", buildJsonObject { put("contextId", context); put("expression", "delete window.visualViewport") })
        Unit
    }

    /** Xiaomi WebView quads are visual coordinates; DOM hit testing uses layout coordinates. */
    private suspend fun pannedClick(wrongFrame: Boolean): Pair<ToolResult, Int> {
        val offsetX = 7.0
        val offsetY = 12.0
        var taps = 0
        val connection = object : CdpConnection by cdp {
            override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
                val result = cdp.call(method, params, timeoutMs)
                return when (method) {
                    "DOM.getContentQuads" -> JsonObject(result + ("quads" to buildJsonArray {
                        result["quads"]!!.jsonArray.forEach { quad -> add(buildJsonArray {
                            quad.jsonArray.forEachIndexed { index, p -> add(p.jsonPrimitive.double - if (index % 2 == 0) offsetX else offsetY) }
                        }) }
                    }))
                    "Page.getLayoutMetrics" -> JsonObject(result + ("cssVisualViewport" to buildJsonObject {
                        put("offsetX", offsetX); put("offsetY", offsetY)
                        put("pageX", offsetX); put("pageY", offsetY)
                    }))
                    "DOM.getNodeForLocation" -> if (wrongFrame) JsonObject(result + ("frameId" to JsonPrimitive("covered-frame"))) else result
                    else -> result
                }
            }
        }
        page = BrowserSession(connection, policy, BrowserSession.Pointer.TOUCH, nativeTap = { x, y ->
            taps++
            // The native Android view consumes visual coordinates. This desktop
            // fixture has no keyboard pan, so translate only its input adapter.
            for (type in listOf("mousePressed", "mouseReleased")) cdp.call("Input.dispatchMouseEvent", buildJsonObject {
                put("type", type); put("button", "left"); put("clickCount", 1)
                put("x", x + offsetX); put("y", y + offsetY)
            })
        }) { true }
        page.start()
        val pannedGateway = WebToolGateway(host(page)).also { it.beginRun("panned", directory) }
        return pannedGateway.invoke("browser", Json.parseToJsonElement("""{"operation":"click","text":"Apply"}""").jsonObject) to taps
    }

    @Test fun pannedNativeTapKeepsVisualCoordinatesAndTranslatesTheHitTest() = runBlocking {
        val (result, taps) = pannedClick(wrongFrame = false)
        assertTrue(result.text, result.success)
        assertEquals(1, taps)
        assertEquals(JsonPrimitive(1), value("clicks"))
    }

    @Test fun pannedHitTestStillRefusesAnotherFrameBeforeTapping() = runBlocking {
        val (result, taps) = pannedClick(wrongFrame = true)
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("obscured"))
        assertEquals(0, taps)
        assertEquals(JsonPrimitive(0), value("clicks"))
    }

    @Test fun aClickAfterScrollingUsesDocumentCoordinatesForTheHitTest() = runBlocking {
        value("""document.body.insertAdjacentHTML('beforeend','<button id="below" style="display:block;margin-top:1800px" onclick="clicks++">Below fold</button>');1""")
        val result = call("""{"operation":"click","text":"Below fold","verify":{"text":"Below fold"}}""")
        assertTrue(result.text, result.success)
        assertTrue(value("scrollY")!!.jsonPrimitive.double > 1000)
        assertEquals(JsonPrimitive(1), value("clicks"))
    }

    @Test fun aClickCanScrollPastCssSmoothScrollingBeforeHitTesting() = runBlocking {
        value("""document.documentElement.style.scrollBehavior='smooth';document.body.insertAdjacentHTML('beforeend','<button id="below" style="display:block;margin-top:1800px" onclick="clicks++">Below fold</button>');1""")
        val result = call("""{"operation":"click","text":"Below fold"}""")
        assertTrue(result.text, result.success)
        assertEquals(JsonPrimitive(1), value("clicks"))
    }

    @Test fun displayContentsKeepsItsVisibleChildrenReadableAndActionable() = runBlocking {
        value("""document.querySelector('article').insertAdjacentHTML('beforeend','<section style="display:contents"><label>Contents field<input id=contents></label><button onclick="clicks++">Wrapped action</button><p>Wrapped article text.</p></section>');1""")
        val snapshot = call("""{"operation":"snapshot","force":true}""")
        assertTrue(snapshot.text, snapshot.text.contains("Contents field"))
        val result = call("""{"operation":"plan","steps":[{"operation":"fill","text":"Contents field","value":"kept"},{"operation":"click","text":"Wrapped action"}]}""")
        assertTrue(result.text, result.success)
        assertEquals(JsonPrimitive("kept"), value("contents.value"))
        assertEquals(JsonPrimitive(1), value("clicks"))
        assertTrue(call("""{"operation":"read"}""").text.contains("Wrapped article text"))
    }

    @Test fun displayContentsDoesNotExposeAriaHiddenOrDisplayNoneSubtrees() = runBlocking {
        value("""document.body.insertAdjacentHTML('beforeend','<section style="display:contents" aria-hidden="true"><button>Hidden contents action</button></section><div style="display:none"><section style="display:contents"><button>Hidden ancestor action</button></section></div>');1""")
        val result = call("""{"operation":"snapshot","force":true}""")
        assertFalse(result.text.contains("Hidden contents action"))
        assertFalse(result.text.contains("Hidden ancestor action"))
    }

    @Test fun planTypesWithTrustedInputAndClicksOnce() = runBlocking {
        val result = call("""{"operation":"plan","steps":[{"operation":"fill","text":"Query","value":"a & b  שאלה"},{"operation":"fill","text":"Address","value":"value"},{"operation":"click","text":"Apply","verify":{"text":"Applied a & b שאלה true"}}]}""")
        assertTrue(result.text, result.success)
        assertEquals(JsonPrimitive(1), value("clicks"))
        assertEquals(JsonPrimitive(true), value("inputs[0]"))
        assertEquals(JsonPrimitive("a & b  שאלה"), value("query.value"))
    }

    @Test fun onePlanFillsChecksAndHandsOverWithoutAnotherCall() = runBlocking {
        value("""document.querySelector('article').innerHTML='<label>Mushroom <input id="mushroom" type="checkbox"></label><label>Bacon <input id="bacon" type="checkbox"></label>';1""")
        val result = call("""{"operation":"plan","show":true,"steps":[
            {"operation":"fill","text":"Query","value":"בדיקה  עם רווחים"},
            {"operation":"click","text":"Mushroom","role":"checkbox"},
            {"operation":"check","checks":[
                {"text":"Query","value":"בדיקה  עם רווחים"},
                {"text":"Mushroom","role":"checkbox","checked":true},
                {"text":"Bacon","role":"checkbox","checked":false}
            ]}
        ]}""")
        assertTrue(result.text, result.success)
        assertTrue(result.text, result.text.contains("checked 3 controls"))
        assertTrue(result.text, result.text.contains("they have control"))
        assertTrue(page.userControlled)
        assertEquals(JsonPrimitive("בדיקה  עם רווחים"), value("query.value"))
    }

    @Test fun aFailedCheckStopsThePlanAndDoesNotHandOverOrRepeatInputs() = runBlocking {
        val result = call("""{"operation":"plan","show":true,"steps":[
            {"operation":"click","text":"Apply"},
            {"operation":"check","timeoutMs":200,"checks":[{"text":"Query","value":"wrong"}]},
            {"operation":"click","text":"Send"}
        ]}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("check_failed"))
        assertTrue(result.text, result.text.contains("must not be sent again"))
        assertEquals(JsonPrimitive(1), value("clicks"))
        assertEquals(JsonPrimitive(0), value("sends"))
        assertFalse(page.userControlled)
    }

    @Test fun checksReadLivePropertiesAndNeverSendInput() = runBlocking {
        value("""document.querySelector('article').innerHTML='<label>Toggle <input type="checkbox" id="toggle"></label>';query.value='live value';toggle.checked=true;1""")
        val before = page.inputs
        val result = call("""{"operation":"check","checks":[{"text":"Query","value":"live value"},{"text":"Toggle","checked":true}]}""")
        assertTrue(result.text, result.success)
        assertEquals(before, page.inputs)
        assertTrue(confirmations.isEmpty())
        val wrongType = call("""{"operation":"check","timeoutMs":200,"checks":[{"text":"Query","checked":false}]}""")
        assertFalse(wrongType.success)
        assertEquals(before, page.inputs)
    }

    @Test fun checksWaitForAllRequestedStateWithinOneBudget() = runBlocking {
        value("setTimeout(()=>query.value='arrived',250);1")
        val result = call("""{"operation":"check","timeoutMs":1500,"checks":[{"text":"Query","value":"arrived"}]}""")
        assertTrue(result.text, result.success)
    }

    @Test fun checksNeverReadPasswordOrCardValues() = runBlocking {
        for (label in listOf("Password", "Card")) {
            val result = call("""{"operation":"check","checks":[{"text":"$label","value":"probe"}]}""")
            assertFalse(result.success)
            assertTrue(result.text, result.text.contains("secret_field"))
            assertFalse(result.text.contains("hidden-secret"))
            assertFalse(result.text.contains("4111111111111111"))
        }
    }

    @Test fun aKnownLongFormFitsOnePlanAndIsCheckedBeforeHandoff() = runBlocking {
        value("document.querySelector('article').innerHTML=Array.from({length:14},(_,i)=>'<label>Item '+i+' <input id=field'+i+'></label>').join('');1")
        val steps = (0 until 14).map { """{"operation":"fill","selector":"#field$it","value":"value $it"}""" }
        val checks = (0 until 14).joinToString(",") { """{"selector":"#field$it","value":"value $it"}""" }
        val result = call("""{"operation":"plan","show":true,"steps":[${steps.joinToString(",")},{"operation":"check","checks":[$checks]}]}""")
        assertTrue(result.text, result.success)
        assertTrue(result.text, result.text.contains("15 of 15 steps ran"))
        assertTrue(page.userControlled)
    }

    @Test fun aHandoffErrorKeepsTheCompletedPlanAndDoesNotInviteReplay() = runBlocking {
        val failingHost = object : BrowserHost by host(page) {
            override suspend fun show(key: String): Boolean {
                page.userControlled = true
                throw java.io.IOException("Presentation reply lost")
            }
        }
        val measured = WebToolGateway(failingHost).also { it.beginRun("test", directory) }
        val result = measured.invoke("browser", Json.parseToJsonElement("""{"operation":"plan","show":true,"steps":[{"operation":"click","text":"Apply"}]}""").jsonObject)
        assertFalse(result.success)
        assertTrue(result.text, result.text.startsWith("ok: false"))
        assertTrue(result.text, result.text.contains("completed plan must not be repeated"))
        assertEquals(JsonPrimitive(1), value("clicks"))
        assertTrue(page.userControlled)
    }
    @Test fun failureKeepsCompletedPrefixAndResumeSkipsIt() = runBlocking {
        val steps = """[{"id":"one","operation":"click","text":"Apply"},{"id":"two","operation":"click","text":"Missing"},{"id":"three","operation":"fill","text":"Query","value":"last"}]"""
        val first = call("""{"operation":"plan","steps":$steps}""")
        assertFalse(first.success); assertTrue(first.text, first.text.contains("1-1 already happened"))
        assertEquals(JsonPrimitive(1), value("clicks"))
        val resumed = call("""{"operation":"plan","startAt":"three","steps":$steps}""")
        assertTrue(resumed.text, resumed.success); assertEquals(JsonPrimitive(1), value("clicks"))
        assertEquals(JsonPrimitive("last"), value("query.value"))
    }
    @Test fun verificationFailureDoesNotInviteRepeatingTheAction() = runBlocking {
        val result = call("""{"operation":"plan","steps":[{"operation":"click","text":"Apply","verify":{"text":"Never there","timeoutMs":200}},{"operation":"click","text":"Send"}]}""")
        assertFalse(result.success); assertTrue(result.text, result.text.contains("only verification failed"))
        assertEquals(JsonPrimitive(1), value("clicks")); assertEquals(JsonPrimitive(0), value("sends"))
    }
    @Test fun invalidLaterStepRefusesTheWholePlan() = runBlocking {
        val result = call("""{"operation":"plan","steps":[{"operation":"click","text":"Apply"},{"operation":"fill","text":"Query"}]}""")
        assertFalse(result.success); assertEquals(JsonPrimitive(0), value("clicks"))
    }
    @Test fun timeoutStopsBeforeNextAction() = runBlocking {
        val result = call("""{"operation":"plan","totalBudgetMs":1000,"steps":[{"operation":"wait","text":"Never there","timeoutMs":30000},{"operation":"click","text":"Apply"}]}""")
        assertFalse(result.success); assertTrue(result.text, result.text.contains("plan_out_of_time"))
        assertEquals(JsonPrimitive(0), value("clicks"))
    }
    @Test fun passwordAndCardAreHiddenAndCannotBeFilled() = runBlocking {
        val snapshot = page.snapshot()
        assertFalse(snapshot.contains("hidden-secret")); assertFalse(snapshot.contains("4111111111111111"))
        for (label in listOf("Password", "Card")) {
            val result = call("""{"operation":"fill","text":"$label","value":"new-secret"}""")
            assertFalse(result.success); assertTrue(result.text, result.text.contains("secret_field"))
        }
    }
    @Test fun sendAndEvalUseApprovalAndDenialDoesNothing() = runBlocking {
        approve = false
        assertFalse(call("""{"operation":"click","text":"Send"}""").success)
        assertEquals(JsonPrimitive(0), value("sends"))
        assertFalse(call("""{"operation":"eval","script":"sends++"}""").success)
        assertEquals(JsonPrimitive(0), value("sends")); assertEquals(2, confirmations.size)
    }

    @Test fun postPathInAWebLinkIsNavigationWithoutApproval() = runBlocking {
        approve = false
        value("""document.body.innerHTML='<a href="/form"><span id="result">forms/post</span></a>';1""")
        val result = call("""{"operation":"click","selector":"#result"}""")
        assertTrue(result.text, result.success)
        assertTrue(result.text, result.text.contains("Message"))
        assertEquals(0, confirmations.size)
    }

    @Test fun aFullUrlLabelContainingPostDoesNotAskForApproval() = runBlocking {
        approve = false
        value("""document.body.innerHTML='<a id="result" href="/form">https://httpbin.org/forms/post</a>';1""")
        val result = call("""{"operation":"click","selector":"#result"}""")
        assertTrue(result.text, result.success)
        assertEquals(0, confirmations.size)
    }

    @Test fun aNestedLinkRoleUsesItsNavigationAnchorForApproval() = runBlocking {
        approve = false
        value("""document.body.innerHTML='<a href="/form"><span role="text">https://httpbin.org</span><div role="heading"><div role="link"><span>forms/post</span></div></div></a>';1""")
        val result = call("""{"operation":"click","text":"forms/post","role":"link","exact":true}""")
        assertTrue(result.text, result.success)
        assertTrue(result.text, result.text.contains("Message"))
        assertEquals(0, confirmations.size)
    }

    @Test fun postActionsAndOtherSensitiveLinksStillNeedApproval() = runBlocking {
        approve = false
        value("""document.body.innerHTML='<a id="publish" href="/form">Post comment</a><button id="pathButton" onclick="sends++">forms/post</button><a id="delete" href="/form">/delete-account</a><form onsubmit="sends++;return false" method="post"><button id="continue">Continue</button></form><a id="roleButton" role="button" href="/form">forms/post</a><a id="postLink" data-method="post" href="/form">forms/post</a><a id="turboLink" data-turbo-method="post" href="/form">forms/post</a><a href="/form"><div role="link" id="nestedPublish">Post comment</div></a><a id="download" download href="/form">forms/post</a>';1""")
        for (selector in listOf("#publish", "#pathButton", "#delete", "#continue", "#roleButton", "#postLink", "#turboLink", "#nestedPublish", "#download")) {
            val result = call("""{"operation":"click","selector":"$selector"}""")
            assertFalse(result.text, result.success)
            assertTrue(result.text, result.text.contains("approval_denied"))
        }
        assertEquals(9, confirmations.size)
        assertEquals(JsonPrimitive(0), value("sends"))
    }

    @Test fun postOnANonWebLinkIsNotExempted() = runBlocking {
        approve = false
        value("""document.body.innerHTML='<a id="result" href="javascript:sends++">forms/post</a>';1""")
        val result = call("""{"operation":"click","selector":"#result"}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("approval_denied"))
        assertEquals(1, confirmations.size)
        assertEquals(JsonPrimitive(0), value("sends"))
    }
    @Test fun showPausesActionsUntilHide() = runBlocking {
        call("""{"operation":"show"}""")
        assertFalse(call("""{"operation":"click","text":"Apply"}""").success)
        call("""{"operation":"hide"}""")
        assertTrue(call("""{"operation":"click","text":"Apply"}""").success)
        assertEquals(JsonPrimitive(1), value("clicks"))
    }
    @Test fun ambiguousLabelIsRefusedAndRefWorks() = runBlocking {
        val result = call("""{"operation":"click","text":"Duplicate","exact":true}""")
        assertFalse(result.success); assertTrue(result.text, result.text.contains("ambiguous_target"))
        val ref = Regex("button \\\"Duplicate\\\" \\[([^]]+)]").find(page.snapshot())!!.groupValues[1]
        assertTrue(call("""{"operation":"click","ref":"$ref"}""").success)
    }
    @Test fun refsCannotTargetNewDocumentAfterReload() = runBlocking {
        val ref = Regex("button \\\"Apply\\\" \\[([^]]+)]").find(page.snapshot())!!.groupValues[1]
        page.reload(); page.snapshot()
        val result = call("""{"operation":"click","ref":"$ref"}""")
        assertFalse(result.success); assertTrue(result.text, result.text.contains("stale_ref"))
        assertEquals(JsonPrimitive(0), value("clicks"))
    }
    @Test fun readsShadowDomAndFrameAndSelectsNativeOption() = runBlocking {
        val result = call("""{"operation":"plan","steps":[{"operation":"fill","text":"Shadow field","value":"shadow"},{"operation":"click","text":"Shadow button"},{"operation":"click","text":"Frame button"},{"operation":"select","text":"Choice","value":"Beta"}]}""")
        assertTrue(result.text, result.success)
        val snapshot = page.snapshot(SnapshotQuery(force = true))
        assertTrue(snapshot, snapshot.contains("Shadow done")); assertTrue(snapshot, snapshot.contains("Frame done"))
        assertEquals(JsonPrimitive("b"), value("document.querySelector('select').value"))
    }
    @Test fun snapshotSuppressionReadAndScreenshotWork() = runBlocking {
        page.snapshot(); assertTrue(page.snapshot().contains("unchanged since"))
        assertTrue(page.snapshot(SnapshotQuery(force = true)).contains("button"))
        assertTrue(page.read(0, 1000).contains("Readable article paragraph"))
        value("document.querySelector('iframe[src=\"/secret\"]').remove();1")
        assertTrue(page.screenshot()!!.startsWith("iVBOR"))
    }
    @Test fun stoppedGatewayRefusesNewActions() = runBlocking {
        gateway.revoke()
        try { call("""{"operation":"click","text":"Apply"}"""); fail("Should refuse stopped run") }
        catch (_: IllegalStateException) {}
        assertEquals(JsonPrimitive(0), value("clicks"))
    }
    @Test fun stopDuringPlanPreventsLaterClicks() = runBlocking {
        val job = launch { call("""{"operation":"plan","steps":[{"operation":"click","text":"Apply"},{"operation":"wait","text":"Never there","timeoutMs":30000},{"operation":"click","text":"Apply"}]}""") }
        withTimeout(5000) { while (value("clicks") != JsonPrimitive(1)) delay(50) }
        gateway.revoke(); job.cancelAndJoin()
        assertEquals(JsonPrimitive(1), value("clicks"))
    }

    // --- regressions found in review --------------------------------------

    @Test fun fillNeverTypesIntoAFieldThatTookTheFocus() = runBlocking {
        // The field hands its focus to "Address" when focused. Typing then would
        // have gone into Address, and been reported as a fill of Stolen.
        val result = call("""{"operation":"fill","text":"Stolen","value":"typed here"}""")
        assertEquals(JsonPrimitive(""), value("address.value"))
        if (result.success) assertEquals(JsonPrimitive("typed here"), value("stolen.value"))
        else assertTrue(result.text, result.text.contains("not_focused"))
    }

    @Test fun clickRefusesATargetCoveredByAnotherElement() = runBlocking {
        val result = call("""{"operation":"click","text":"Covered","role":"button"}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("obscured"))
        assertTrue(result.text, result.text.contains("Cookie banner"))
        assertFalse(result.text, result.text.contains("mayHaveActed: true"))
        assertEquals(JsonPrimitive(0), value("coveredClicks"))
        assertEquals(JsonPrimitive(0), value("bannerClicks"))
    }

    @Test fun plainTextShownTwiceIsAmbiguousRatherThanTheFirstOne() = runBlocking {
        val result = call("""{"operation":"click","text":"Remove row"}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("ambiguous_target"))
        assertEquals(JsonPrimitive(0), value("removes"))
    }

    @Test fun enterInAFormInsideAFrameAsksForApproval() = runBlocking {
        approve = false
        val result = call("""{"operation":"fill","text":"Message","value":"hello","submit":true}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("approval_denied"))
        assertEquals(1, confirmations.size)
        assertEquals(JsonPrimitive(0), value("frameSubmits"))
    }

    @Test fun refsFromAnEarlierConnectionNeverNameAnotherElement() = runBlocking {
        val ref = Regex("button \\\"Apply\\\" \\[([^]]+)]").find(page.snapshot())!!.groupValues[1]
        value("var inserted=0;(()=>{const b=document.createElement('button');b.type='button';b.textContent='Inserted';" +
            "b.onclick=()=>inserted++;document.body.insertBefore(b,document.body.firstChild)})();1")
        // A dropped connection is attached again as a new session on the same page.
        val again = BrowserSession(CdpClient.attach(dialer, CdpClient.pages(dialer).first().id), policy, BrowserSession.Pointer.MOUSE) { true }
        try {
            again.start()
            again.snapshot()
            val second = WebToolGateway(host(again)).also { it.beginRun("again", directory) }
            second.invoke("browser", Json.parseToJsonElement("""{"operation":"click","ref":"$ref","observe":false}""").jsonObject)
            assertEquals(JsonPrimitive(0), value("inserted"))
        } finally {
            again.close()
        }
    }

    @Test fun failureRepliesKeepThePageNotes() = runBlocking {
        page.note("A download was refused: https://example.com/file.zip")
        val result = call("""{"operation":"click","text":"Missing"}""")
        assertFalse(result.success)
        assertTrue(result.text, result.text.contains("note: A download was refused"))
    }

    @Test fun planTimeoutWhileWaitingForApprovalSaysTheStepDidNotHappen() = runBlocking {
        confirmDelayMs = 3_000
        val result = call("""{"operation":"plan","totalBudgetMs":1000,"steps":[{"id":"send","operation":"click","text":"Send"}]}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("startAt=send"))
        assertFalse(result.text, result.text.contains("may have happened"))
        assertEquals(JsonPrimitive(0), value("sends"))
    }

    @Test fun anInputSentBeforeAFailureMakesTheStepUncertain() = runBlocking {
        // The click lands, then the page takes itself to a blocked address.
        value("document.querySelector('#apply').addEventListener('click',()=>{location.href='/secret'});1")
        val result = call("""{"operation":"plan","steps":[{"id":"apply","operation":"click","text":"Apply"},{"operation":"click","text":"Send"}]}""")
        assertFalse(result.text, result.success)
        assertFalse(result.text, result.text.contains("startAt=apply"))
        assertEquals(JsonPrimitive(0), value("typeof sends === 'undefined' ? 0 : sends"))
    }

    @Test fun blockedFramesAndPagesAreNeverRead() = runBlocking {
        val snapshot = call("""{"operation":"snapshot","force":true}""")
        assertFalse(snapshot.text, snapshot.text.contains("Router admin"))
        // The page sends itself somewhere blocked without any action of the agent.
        value("location.href='/secret';1")
        delay(800)
        for (operation in listOf("snapshot", "read")) {
            val result = call("""{"operation":"$operation","force":true}""")
            assertFalse(result.text, result.text.contains("Router admin"))
        }
    }

    @Test fun aLostConnectionDuringAReadIsNotReportedAsAnAction() = runBlocking {
        cdp.close()
        val result = call("""{"operation":"snapshot"}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("browser_disconnected"))
        assertFalse(result.text, result.text.contains("mayHaveActed: true"))
        assertTrue(result.text, result.text.contains("Nothing was sent"))
    }

    @Test fun nestedSubmitTargetCannotSkipApproval() = runBlocking {
        value("""document.body.innerHTML='<form onsubmit="sends++;return false"><button><span id="nested">Continue</span></button></form>';1""")
        approve = false
        val result = call("""{"operation":"click","selector":"#nested"}""")
        assertFalse(result.text, result.success)
        assertEquals(1, confirmations.size)
        assertEquals(JsonPrimitive(0), value("sends"))
    }

    @Test fun stolenFrameFocusNeverSubmitsTheOtherForm() = runBlocking {
        value("""document.body.insertAdjacentHTML('afterbegin','<form onsubmit="sends++;return false"><input id="outside"></form>');
            const field=document.querySelector('iframe[src="/form"]').contentDocument.querySelector('input');
            field.onfocus=()=>outside.focus();1""")
        approve = false
        val result = call("""{"operation":"fill","text":"Message","value":"hello","submit":true}""")
        assertFalse(result.text, result.success)
        assertEquals(JsonPrimitive(0), value("sends"))
        assertEquals(JsonPrimitive(0), value("frameSubmits"))
    }

    @Test fun lostMutationReplyDoesNotRepeatChangeHandlers() = runBlocking {
        value("var changes=0;document.querySelector('select').onchange=()=>changes++;1")
        loseReplyFor = "__mike.choose("
        val result = call("""{"operation":"select","text":"Choice","value":"Beta"}""")
        assertEquals(JsonPrimitive(1), value("changes"))
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("mayHaveActed"))
    }

    @Test fun parentOverlayPreventsClickInsideFrame() = runBlocking {
        value("""var outerClicks=0;
            const f=document.querySelector('iframe[src="/frame"]');
            f.style.cssText='position:fixed;left:20px;top:20px;width:300px;height:150px;z-index:10';
            const cover=document.createElement('div');cover.textContent='Parent cover';
            cover.style.cssText='position:fixed;left:0;top:0;width:400px;height:220px;z-index:20;background:white';
            cover.onclick=()=>outerClicks++;document.body.append(cover);1""")
        val result = call("""{"operation":"click","text":"Frame button"}""")
        assertFalse(result.text, result.success)
        assertEquals(JsonPrimitive(0), value("outerClicks"))
        assertEquals(JsonPrimitive("Frame button"), value("""document.querySelector('iframe[src="/frame"]').contentDocument.querySelector('button').textContent"""))
    }

    @Test fun duplicateAcrossFramesRequiresARef() = runBlocking {
        value("""const b=document.createElement('button');b.type='button';b.textContent='Frame button';b.onclick=()=>clicks++;document.body.prepend(b);1""")
        val result = call("""{"operation":"click","text":"Frame button","exact":true}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("ambiguous_target"))
        assertEquals(JsonPrimitive(0), value("clicks"))
    }

    @Test fun shadowFillUsesTrustedInputAndSupportsContentEditable() = runBlocking {
        value("""var shadowTrusted=null;shadow.shadowRoot.querySelector('input').oninput=e=>shadowTrusted=e.isTrusted;
            const editor=document.createElement('div');editor.contentEditable='true';editor.setAttribute('aria-label','Shadow editor');editor.textContent='old';shadow.shadowRoot.append(editor);1""")
        assertTrue(call("""{"operation":"fill","text":"Shadow field","value":"hello"}""").success)
        assertEquals(JsonPrimitive(true), value("shadowTrusted"))
        val result = call("""{"operation":"fill","text":"Shadow editor","value":"edited"}""")
        assertTrue(result.text, result.success)
        assertEquals(JsonPrimitive("edited"), value("shadow.shadowRoot.querySelector('[contenteditable]').innerText"))
    }

    @Test fun mainSnapshotFailureIsNotAnEmptySuccess() = runBlocking {
        failReadFor = "__mike.snapshot("
        val result = call("""{"operation":"snapshot"}""")
        assertFalse(result.text, result.success)
    }

    @Test fun deniedSubmitDoesNotSuggestRepeatingCompletedFill() = runBlocking {
        approve = false
        val result = call("""{"operation":"plan","steps":[{"id":"message","operation":"fill","text":"Message","value":"hello","submit":true}]}""")
        assertFalse(result.text, result.success)
        assertFalse(result.text, result.text.contains("Resume with startAt=message"))
        assertTrue(result.text, result.text.contains("fill") && result.text.contains("not"))
        assertEquals(JsonPrimitive("hello"), value("""document.querySelector('iframe[src="/form"]').contentDocument.querySelector('input').value"""))
    }

    @Test fun aScrollWithLostReplyIsUncertainAndNotRepeated() = runBlocking {
        value("document.body.style.height='5000px';window.scrollTo(0,0);1")
        loseReplyFor = "__mike.scroll("
        val result = call("""{"operation":"scroll","direction":"down","observe":false}""")
        assertFalse(result.text, result.success)
        assertTrue(result.text, result.text.contains("mayHaveActed"))
        assertEquals(JsonPrimitive(true), value("scrollY <= Math.round(innerHeight * 0.8) + 1"))
    }

    @Test fun approvalCannotFollowAChangedSubmitDestination() = runBlocking {
        value("""document.body.innerHTML='<form action="/first" onsubmit="sends++;return false"><button>Continue</button></form>';1""")
        duringApproval = { value("document.querySelector('form').action='/second';1"); Unit }
        val result = call("""{"operation":"click","text":"Continue"}""")
        assertFalse(result.text, result.success)
        assertEquals(JsonPrimitive(0), value("sends"))
    }

    @Test fun screenshotRefusesBlockedChildFrames() = runBlocking {
        val result = call("""{"operation":"screenshot"}""")
        assertFalse(result.text, result.success)
        assertNull(result.imageBase64)
    }
}
