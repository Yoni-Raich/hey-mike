package dev.androidagent.engineclaude

import dev.androidagent.core.DeviceCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ClaudeAccountEngineTest {
    @Test fun metadataDiscoveryUsesOnlyControlMessagesAndDestroysItsProbe() = runBlocking {
        val home = Files.createTempDirectory("claude-account-probe").toFile()
        val host = FakeHost(home)
        val engine = ClaudeCodeEngine(host, FakeToolServers())
        host.script = { process ->
            process.onLine = { p, line ->
                val frame = Json.parseToJsonElement(line).jsonObject
                val id = frame["request_id"]!!.jsonPrimitive.content
                when (frame.requestSubtype()) {
                    "initialize" -> p.send(FakeClaude.initializeResponse(id))
                    "mcp_status" -> p.send(buildJsonObject {
                        put("type", "control_response")
                        put("response", buildJsonObject {
                            put("subtype", "success"); put("request_id", id)
                            put("response", Json.parseToJsonElement("""{"mcpServers":[{"scope":"claudeai","name":"claude.ai Gmail","status":"connected","tools":[{"name":"read","annotations":{"readOnly":true,"openWorld":true}}]}]}"""))
                        })
                    })
                    else -> error("A metadata probe sent a model message or unknown control")
                }
            }
        }
        try {
            assertEquals(listOf("mcp__claude_ai_Gmail__read"), engine.accountConnectors().single().readTools)
            val process = host.started.single()
            assertTrue(process.forced)
            assertEquals("true", process.env["ENABLE_CLAUDEAI_MCP_SERVERS"])
            assertFalse("--system-prompt-file" in process.args)
            assertTrue(process.lines.toList().all { Json.parseToJsonElement(it).jsonObject.type() == "control_request" })
        } finally { engine.close(); home.deleteRecursively() }
    }

    @Test fun aGrantRemovedDuringStartupCannotLeakIntoTheFirstModelTurn() = runBlocking {
        val home = Files.createTempDirectory("claude-account-start-race").toFile()
        val host = FakeHost(home)
        val engine = ClaudeCodeEngine(host, FakeToolServers())
        host.script = { process -> process.onLine = { p, line ->
            val frame = Json.parseToJsonElement(line).jsonObject
            if (frame.requestSubtype() == "initialize") {
                engine.allowAccountTools(emptyList())
                p.send(FakeClaude.initializeResponse(frame["request_id"]!!.jsonPrimitive.content))
            }
        } }
        try {
            engine.allowAccountTools(listOf("mcp__claude_ai_Gmail__read"))
            val chat = engine.openSession(home.resolve("chat"), null, "sonnet", emptyList())
            try {
                engine.startTurn(chat, "must not reach the model", emptyList(), null, null, DeviceCapabilities(), null)
                fail("A revoked startup must fail before sending the message")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("permissions changed"))
            }
            assertTrue(host.chats().single().forced)
            assertTrue(host.chats().single().lines.toList().all { Json.parseToJsonElement(it).jsonObject.type() == "control_request" })
        } finally { engine.close(); home.deleteRecursively() }
    }

    @Test fun removingAnAccountGrantKillsTheLivePhoneProcessWithTheOldWhitelist() = runBlocking {
        val home = Files.createTempDirectory("claude-account-revoke").toFile()
        val host = FakeHost(home)
        val engine = ClaudeCodeEngine(host, FakeToolServers())
        try {
            val tool = "mcp__claude_ai_Gmail__read"
            engine.allowAccountTools(listOf(tool))
            val chat = engine.openSession(home.resolve("chat"), null, "sonnet", emptyList())
            engine.startTurn(chat, "fixture only", emptyList(), null, null, DeviceCapabilities(), null)
            val process = host.chats().single()
            assertTrue(process.after("--allowedTools").split(',').contains(tool))
            assertEquals("true", process.env["ENABLE_CLAUDEAI_MCP_SERVERS"])
            engine.allowAccountTools(emptyList())
            assertTrue(process.forced)
            assertFalse(process.isAlive)
        } finally { engine.close(); home.deleteRecursively() }
    }
}
