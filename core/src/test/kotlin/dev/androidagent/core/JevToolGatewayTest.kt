package dev.androidagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JevToolGatewayTest {
    private val state = MutableStateFlow(JevProviderState(enabled = true, tokenConfigured = true))
    private val provider = FakeProvider(state)
    private val gateway = JevToolGateway(provider) {
        ToolResult(
            buildJsonObject {
                put("ok", true)
                put("observationId", "ui-42")
                put("activePackage", "com.android.settings")
                put("truncated", false)
                put("nodes", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("nodeId", "n17")
                        put("text", "Network & internet")
                        put("enabled", true)
                        put("clickable", true)
                    })
                })
            }.toString(),
        )
    }

    @Test
    fun `tool exposes a decision but never executes a device action`() = runBlocking {
        gateway.beginRun("run-1", File("."))
        val result = gateway.invoke("jev_choose_ui_action", requestJson())

        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("A000", json["choice"]?.toString()?.trim('"'))
        assertTrue(json["canSuggestAction"]?.toString() == "true")
        assertEquals(1, provider.calls)
        assertFalse(gateway.needsControl("jev_choose_ui_action"))
    }

    @Test
    fun `disabled provider refuses without calling network`() = runBlocking {
        state.value = JevProviderState(enabled = false, tokenConfigured = true)
        gateway.beginRun("run-1", File("."))

        val error = runCatching { gateway.invoke("jev_choose_ui_action", requestJson()) }.exceptionOrNull()

        assertTrue(error is ToolNotServiceable)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `unknown provider choice becomes a failed result`() = runBlocking {
        provider.decision = JevDecision("invented", 1.0, mapOf("invented" to 1.0), "jev-test")
        gateway.beginRun("run-1", File("."))

        val result = gateway.invoke("jev_choose_ui_action", requestJson())

        assertFalse(result.success)
        assertEquals(1, provider.calls)
    }

    private fun requestJson() = buildJsonObject {
        put("goal", "Open Settings")
        put("observation", "A home screen with a Settings icon")
    }

    private class FakeProvider(
        override val state: MutableStateFlow<JevProviderState>,
    ) : JevDecisionProvider {
        var calls = 0
        var decision = JevDecision("A000", 0.9, mapOf("A000" to 0.9, "ESCALATE" to 0.1), "jev-test")

        override suspend fun choose(request: JevDecisionRequest): JevDecision {
            calls++
            return decision
        }
    }
}
