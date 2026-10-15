package dev.androidagent.web

import kotlinx.coroutines.runBlocking
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
        override fun unavailable(): WebFailure? = null
        override suspend fun session(key: String): BrowserSession { opened++; throw failure }
        override suspend fun show(key: String) = Unit
        override suspend fun hide(key: String) = Unit
        override suspend fun close(key: String) = false
        override fun statusLine() = "Test"
        override fun screenInUse(key: String) { screens += key }
        override fun conditions() = "the screen is off and the phone is locked."
    }

    private val host = Host()
    private val gateway = WebToolGateway(host).also { it.beginRun("run", File("chat-one")) }

    private suspend fun call(json: String) = gateway.invoke("browser", Json.parseToJsonElement(json).jsonObject)

    @Test fun screenUseByARunReachesTheHostWithThatRunsPage() {
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
        )) {
            val result = call(json)
            assertFalse(result.text, result.success)
            assertTrue(result.text, result.text.contains("bad_step"))
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
