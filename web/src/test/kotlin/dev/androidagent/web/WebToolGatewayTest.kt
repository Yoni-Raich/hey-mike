package dev.androidagent.web

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** The gateway's contract with its host and the coordinator, with no browser behind it. */
class WebToolGatewayTest {
    private class Host : BrowserHost {
        val screens = mutableListOf<String>()
        var failure: Exception = IOException("socket closed")
        var opened = 0
        var delayMs = 0L
        override fun unavailable(): WebFailure? = null
        override suspend fun session(key: String): BrowserSession { opened++; delay(delayMs); throw failure }
        override suspend fun show(key: String) = true
        override suspend fun hide(key: String) = Unit
        override suspend fun close(key: String) = false
        override fun statusLine() = "Test"
        override suspend fun screenInUse(key: String) { screens += key }
        override fun conditions() = "the screen is off and the phone is locked."
    }

    private val host = Host()
    private val gateway = WebToolGateway(host).also { it.beginRun("run", File("chat-one")) }

    private suspend fun call(json: String) = gateway.invoke("browser", Json.parseToJsonElement(json).jsonObject)

    @Test fun timingLogHasNoTargetsOrFailureMessage() = runBlocking {
        val logs = mutableListOf<String>()
        host.failure = IOException("private.example/user-secret")
        val measured = WebToolGateway(host, logs::add).also { it.beginRun("test", File("private-chat")) }
        val result = measured.invoke("browser", Json.parseToJsonElement("""{"operation":"fill","text":"private-field","value":"secret-text"}""").jsonObject)
        assertFalse(result.success)
        assertTrue(result.text.contains("timings:"))
        assertTrue(logs.isNotEmpty())
        for (secret in listOf("private.example", "user-secret", "private-field", "secret-text", "private-chat"))
            assertFalse(secret, logs.joinToString().contains(secret))
    }

    @Test fun aBrokenLogSinkDoesNotFailACompletedAction() = runBlocking {
        val measured = WebToolGateway(host) { throw IOException("Log is unavailable") }
        measured.beginRun("test", File("chat"))
        val result = measured.invoke("browser", Json.parseToJsonElement("""{"operation":"hide"}""").jsonObject)
        assertTrue(result.text, result.success)
    }

    @Test fun stopPropagatesAndLogsCancellation() = runBlocking {
        val logs = mutableListOf<String>()
        host.delayMs = 60_000
        val measured = WebToolGateway(host, logs::add).also { it.beginRun("test", File("chat")) }
        val job = launch { measured.invoke("browser", Json.parseToJsonElement("""{"operation":"snapshot"}""").jsonObject) }
        while (host.opened == 0) delay(1)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(logs.joinToString().contains("\"status\":\"cancelled\""))
        assertEquals(1, host.opened)
    }

    @Test fun layoutWithoutAModeOpensNothing() = runBlocking {
        val result = call("""{"operation":"layout","mode":"tablet"}""")
        assertFalse(result.success)
        assertTrue(result.text, result.text.contains("error: no_mode"))
        assertEquals(0, host.opened)
    }

    @Test fun layoutIsOneOfTheToolsModes() {
        val properties = WebToolGateway.DEFINITION.inputSchema.getValue("properties").jsonObject
        assertTrue(properties.getValue("operation").toString().contains("\"layout\""))
        assertTrue(properties.getValue("mode").toString().contains("\"desktop\""))
    }

    @Test fun screenUseByARunReachesTheHostWithThatRunsPage() = runBlocking {
        gateway.screenInUse("run", File("chat-two"))
        assertEquals(listOf(File("chat-two").absolutePath), host.screens)
    }

    @Test fun aLostConnectionBeforeAnyInputIsNotAnActionAndSaysHowThePhoneIs() = runBlocking {
        val result = call("""{"operation":"click","text":"Send"}""")
        assertFalse(result.success)
        assertTrue(result.text, result.text.contains("error: browser_disconnected"))
        assertFalse(result.text, result.text.contains("mayHaveActed: true"))
        assertTrue(result.text, result.text.contains("Nothing was sent"))
        assertTrue(result.text, result.text.contains("phone: the screen is off"))
    }

    @Test fun aRefusalIsReportedAsItsOwnTypedError() = runBlocking {
        host.failure = WebFailure("browser_overlay_permission", "Allow the floating window first.")
        val result = call("""{"operation":"snapshot"}""")
        assertFalse(result.success)
        assertTrue(result.text, result.text.contains("error: browser_overlay_permission"))
        assertFalse(result.text, result.text.contains("phone:"))
    }

    @Test fun anInvalidPlanOrStepIsRefusedBeforeAnyPageIsOpened() = runBlocking {
        for (json in listOf(
            """{"operation":"plan","steps":[{"operation":"click","text":"Apply"},{"operation":"fill","text":"Query"}]}""",
            """{"operation":"fill","text":"Query"}""",
            """{"operation":"plan","steps":[{"operation":"click","text":"Apply","verify":{}}]}""",
            """{"operation":"plan","steps":[{"operation":"click","text":"Apply","verify":"Ready"}]}""",
            """{"operation":"plan","steps":[{"operation":"click","text":"Apply"},{"operation":"check","checks":[{"text":"Query"}]}]}""",
            """{"operation":"check","checks":[]}""",
            """{"operation":"check","checks":[{"text":"Query","checked":"maybe"}]}""",
        )) {
            val result = call(json)
            assertFalse(result.text, result.success)
            assertTrue(result.text, result.text.contains(if (json.contains("verify")) "bad_verify" else "bad_step"))
        }
        assertEquals(0, host.opened)
    }

    @Test fun aStoppedRunRefusesBeforeTouchingTheHost() = runBlocking {
        gateway.revokeIndependentTools()
        val refused = runCatching { call("""{"operation":"snapshot"}""") }
        assertTrue(refused.exceptionOrNull() is IllegalStateException)
        assertEquals(0, host.opened)
    }
}
