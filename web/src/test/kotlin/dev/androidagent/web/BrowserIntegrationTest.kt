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
                            val html = when {
                                request.contains(" /frame ") -> "<button onclick='this.textContent=\"Frame done\"'>Frame button</button>"
                                request.contains(" /form ") -> "<form onsubmit='parent.frameSubmits++;return false'><label>Message <input></label></form>"
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
        override suspend fun show(key: String) { session.userControlled = true }
        override suspend fun hide(key: String) { session.userControlled = false }
        override suspend fun close(key: String): Boolean { session.close(); return true }
        override fun statusLine() = "Fixture"
    }

    @Before fun openFixture() = runBlocking {
        cdp = CdpClient.attach(dialer, CdpClient.pages(dialer).first().id)
        page = BrowserSession(cdp, policy, BrowserSession.Pointer.MOUSE) {
            confirmations += it; delay(confirmDelayMs); approve
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

    @Test fun planTypesWithTrustedInputAndClicksOnce() = runBlocking {
        val result = call("""{"operation":"plan","steps":[{"operation":"fill","text":"Query","value":"a & b  שאלה"},{"operation":"fill","text":"Address","value":"value"},{"operation":"click","text":"Apply","verify":{"text":"Applied a & b שאלה true"}}]}""")
        assertTrue(result.text, result.success)
        assertEquals(JsonPrimitive(1), value("clicks"))
        assertEquals(JsonPrimitive(true), value("inputs[0]"))
        assertEquals(JsonPrimitive("a & b  שאלה"), value("query.value"))
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
}
