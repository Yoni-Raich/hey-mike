package dev.androidagent.enginecodex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexAccountReadsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private class Rpc {
        var readOnly = true
        var calls = mutableListOf<Pair<String, JsonObject>>()
        var callWait: CompletableDeferred<JsonObject>? = null
        val entered = CompletableDeferred<Unit>()
        suspend fun request(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return when (method) {
                "mcpServerStatus/list" -> Json.parseToJsonElement("""{"data":[{"name":"codex_apps","tools":{"gmail.read":{"_meta":{"connector_name":"Gmail"},"description":"Read a fixture","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":$readOnly}}}}]}""").jsonObject
                "thread/start" -> Json.parseToJsonElement("""{"thread":{"id":"account-read-thread"}}""").jsonObject
                "mcpServer/tool/call" -> {
                    entered.complete(Unit)
                    callWait?.await() ?: Json.parseToJsonElement("""{"content":[{"type":"text","text":"fixture"}]}""").jsonObject
                }
                else -> error("Unexpected RPC: $method")
            }
        }
    }

    @Test fun everyDispatchRechecksTheServiceAnnotation() = runBlocking {
        val rpc = Rpc()
        val reads = CodexAccountReads(true, {}, rpc::request)
        reads.connectors()
        reads.allow(listOf("gmail.read"))
        rpc.readOnly = false
        assertFalse(reads.read("gmail.read", buildJsonObject {}) { true }.success)
        assertFalse(rpc.calls.any { it.first == "mcpServer/tool/call" || it.first == "thread/start" })
    }

    @Test fun onlyTheAllowedToolRunsOnAnEphemeralReadOnlyAccountThread() = runBlocking {
        val rpc = Rpc()
        val reads = CodexAccountReads(true, {}, rpc::request)
        reads.allow(listOf("gmail.read", "gmail.send"))
        assertFalse(reads.read("gmail.send", buildJsonObject {}) { true }.success)
        assertTrue(reads.read("gmail.read", obj("""{"query":"fixture"}""")) { true }.success)
        val thread = rpc.calls.single { it.first == "thread/start" }.second
        assertEquals(JsonPrimitive(true), thread["ephemeral"])
        assertEquals(JsonPrimitive("read-only"), thread["sandbox"])
        assertEquals(JsonPrimitive("never"), thread["approvalPolicy"])
        val call = rpc.calls.single { it.first == "mcpServer/tool/call" }.second
        assertEquals(JsonPrimitive("codex_apps"), call["server"])
        assertEquals(JsonPrimitive("gmail.read"), call["tool"])
        assertEquals(JsonPrimitive("account-read-thread"), call["threadId"])
    }

    @Test fun stopOrTurningOffTheGrantSuppressesAnInFlightResult() = runBlocking {
        val rpc = Rpc().apply { callWait = CompletableDeferred() }
        val reads = CodexAccountReads(true, {}, rpc::request)
        reads.allow(listOf("gmail.read"))
        var permitted = true
        val reading = async { reads.read("gmail.read", buildJsonObject {}) { permitted } }
        withTimeout(5_000) { rpc.entered.await() }
        assertTrue(rpc.calls.any { it.first == "mcpServer/tool/call" })
        permitted = false
        reads.allow(emptyList())
        rpc.callWait!!.complete(obj("""{"content":[{"type":"text","text":"do not return"}]}"""))
        val result = reading.await()
        assertFalse(result.success)
        assertFalse(result.text.contains("do not return"))
    }
}
