package dev.androidagent.web

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File

/** A delayed read must not turn the 3s settling allowance into a 10s command wait. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserWaitTest {
    @Test fun verificationBudgetIncludesProtocolReads() = runTest {
        val connection = object : CdpConnection {
            override val isOpen = true
            override fun close() {}
            override fun onEvent(listener: (String, JsonObject) -> Unit) = Closeable {}
            override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
                delay(10_000)
                return JsonObject(emptyMap())
            }
        }
        val session = BrowserSession(connection, WebUrlPolicy())
        val before = currentTime
        try {
            session.waitFor(null, null, "/wanted", false, 200)
            fail("Expected a bounded verification failure")
        } catch (failure: WebFailure) {
            assertEquals("wait_timeout", failure.errorType)
            assertTrue("Verification took ${currentTime - before}ms", currentTime - before <= 200)
        } finally { session.close() }
    }

    @Test fun settlingBudgetIncludesNestedProtocolReads() = runTest {
        var inputSent = false
        val connection = object : CdpConnection {
            override val isOpen = true
            override fun close() {}
            override fun onEvent(listener: (String, JsonObject) -> Unit) = Closeable {}
            override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
                if (method == "Input.dispatchKeyEvent") inputSent = true
                if (method == "Page.getFrameTree" && inputSent) delay(10_000)
                return Json.parseToJsonElement(when (method) {
                    "Page.getFrameTree" -> """{"frameTree":{"frame":{"id":"main","url":"about:blank"}}}"""
                    "Page.getNavigationHistory" -> """{"currentIndex":0,"entries":[{"url":"about:blank"}]}"""
                    "Page.createIsolatedWorld" -> """{"executionContextId":1}"""
                    "Runtime.evaluate" -> if (params["expression"]?.jsonPrimitive?.content == "document.readyState")
                        """{"result":{"value":"complete"}}""" else """{"result":{"value":true}}"""
                    else -> "{}"
                }).jsonObject
            }
        }
        val session = BrowserSession(connection, WebUrlPolicy())
        try {
            session.start()
            val host = object : BrowserHost {
                override fun unavailable(): WebFailure? = null
                override suspend fun session(key: String) = session
                override suspend fun show(key: String) = false
                override suspend fun hide(key: String) {}
                override suspend fun close(key: String) = false
                override fun statusLine() = "Test"
            }
            val gateway = WebToolGateway(host).also { it.beginRun("test", File("test-chat")) }
            val before = currentTime
            val result = gateway.invoke("browser", Json.parseToJsonElement("""{"operation":"press","key":"Tab","observe":false}""").jsonObject)
            assertTrue(result.text, result.success)
            assertTrue("Nested command exceeded settling budget: ${currentTime - before}ms", currentTime - before <= 3_050)
            assertTrue(result.text, result.text.contains("settleBudgetExpired"))
        } finally { session.close() }
    }
}
