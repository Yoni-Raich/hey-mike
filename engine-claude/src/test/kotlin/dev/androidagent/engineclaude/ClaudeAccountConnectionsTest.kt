package dev.androidagent.engineclaude

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ClaudeAccountConnectionsTest {
    @Test fun onlyAccountScopedUnambiguousReadsAreOffered() {
        val status = Json.parseToJsonElement("""{"mcpServers":[
          {"scope":"claudeai","name":"claude.ai Gmail","status":"connected","tools":[
            {"name":"read","annotations":{"readOnly":true}},
            {"name":"send","annotations":{"readOnly":false}},
            {"name":"unknown"},
            {"name":"conflict","annotations":{"readOnly":true,"readOnlyHint":false}},
            {"name":"delete","annotations":{"readOnly":true,"destructiveHint":true}},
            {"name":"search_threads","annotations":{"readOnly":true,"openWorld":true,"openWorldHint":true}}]},
          {"scope":"user","name":"not-the-account","status":"connected","tools":[{"name":"read","annotations":{"readOnly":true}}]}
        ]}""").jsonObject
        val connection = ClaudeProtocol.parseAccountServers(status).single()
        assertEquals("Gmail", connection.name)
        assertEquals(listOf("mcp__claude_ai_Gmail__read", "mcp__claude_ai_Gmail__search_threads"), connection.readTools)
        assertEquals(4, connection.writeTools)
    }

    @Test fun phoneChatsOnlyLoadAccountServersWhenAnExplicitReadToolIsAllowed() {
        fun args(tools: List<String>) = ClaudeProtocol.chatArgs("chat", false, "sonnet", null, "mcp.json", "instructions.txt", tools)
        assertTrue("--strict-mcp-config" in args(emptyList()))
        val allowed = args(listOf("mcp__claude_ai_Gmail__read"))
        assertFalse("--strict-mcp-config" in allowed)
        assertTrue(allowed[allowed.indexOf("--allowedTools") + 1].contains("mcp__claude_ai_Gmail__read"))
        assertFalse(allowed[allowed.indexOf("--allowedTools") + 1].contains("mcp__claude_ai_Gmail__*"))
        assertEquals("dontAsk", allowed[allowed.indexOf("--permission-mode") + 1])
    }

    @Test fun metadataProbeSendsNoMessageAndKeepsNoSession() {
        val args = ClaudeProtocol.accountProbeArgs()
        assertEquals("", args[args.indexOf("--tools") + 1])
        assertEquals("", args[args.indexOf("--setting-sources") + 1])
        assertTrue("--no-session-persistence" in args)
        assertFalse("--resume" in args)
        assertFalse("--session-id" in args)
        assertEquals("true", ClaudeProtocol.ACCOUNT_ENV["ENABLE_CLAUDEAI_MCP_SERVERS"])
    }
}
