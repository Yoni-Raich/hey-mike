/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.engineclaude

import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** A Claude chat on one of the user's computers: the same engine, started and fed differently. */
class ClaudeComputerTest {
    private val home = Files.createTempDirectory("claude-computer").toFile()
    private val host = FakeHost(home)
    private val servers = FakeToolServers()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<EngineEvent>()
    private val engines = mutableListOf<ClaudeCodeEngine>()
    private val tapTool = ToolDefinition("tap", "Tap the screen", buildJsonObject { put("type", "object") })

    private class Launch(val chatId: String, val cwd: String, val files: Map<String, String>, val env: Map<String, String>, val process: FakeClaude)

    private inner class FakeComputer(override val askUser: Boolean = true) : ClaudeComputer {
        override val instructions = "You are Mike, on the user's computer."
        override val permissionMode get() = if (askUser) "acceptEdits" else "bypassPermissions"
        override val notReady = "Claude Code is not on PC."
        val chats = CopyOnWriteArrayList<Launch>()

        override suspend fun startChat(
            chatId: String,
            cwd: String,
            files: Map<String, String>,
            env: Map<String, String>,
            args: (paths: Map<String, String>) -> List<String>,
        ): Process {
            val process = FakeClaude(args(files.keys.associateWith { "C:\\Users\\me\\.hey-mike\\claude\\$it" }), File("."), env)
            host.script(process)
            chats += Launch(chatId, cwd, files, env, process)
            return process
        }
    }

    private fun engine(computer: ClaudeComputer): ClaudeCodeEngine =
        ClaudeCodeEngine(host, servers, interruptGraceMs = 3_000, computer = computer).also { engine ->
            engines += engine
            scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        }

    @After fun tearDown() {
        runBlocking { engines.forEach { runCatching { withTimeout(10_000) { it.close() } } } }
        scope.cancel()
    }

    private inline fun <reified T : EngineEvent> awaitEvent(timeoutMs: Long = 5_000): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            events.filterIsInstance<T>().firstOrNull()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("No ${T::class.simpleName} in $events")
    }

    private suspend fun ClaudeCodeEngine.begin(computer: FakeComputer): Triple<String, String, FakeClaude> {
        val id = openSessionAt(PROJECT, null, "opus", listOf(tapTool))
        val turn = startTurn(id, "hello", emptyList(), null, null, DeviceCapabilities(ready = setOf("tap")), null)
        return Triple(id, turn, computer.chats.single().process)
    }

    private fun control(id: String, request: String): String = """{"type":"control_request","request_id":"$id","request":$request}"""

    private fun FakeClaude.responseTo(id: String): JsonObject =
        nextFrame { it.type() == "control_response" && it["response"]!!.jsonObject["request_id"]!!.jsonPrimitive.content == id }["response"]!!.jsonObject

    @Test fun aComputerChatStartsTheComputersOwnClaudeInTheProjectFolder() = runBlocking {
        val computer = FakeComputer()
        val (id, _, chat) = engine(computer).begin(computer)
        val launch = computer.chats.single()

        // The folder is named as the computer spells it, never turned into a phone path.
        assertEquals(PROJECT, launch.cwd)
        assertEquals(id, launch.chatId)
        assertEquals(ClaudeProtocol.CHAT_ENV, launch.env)
        assertEquals("You are Mike, on the user's computer.", launch.files["system-prompt.md"])
        assertEquals("""{"mcpServers":{"mike":{"type":"sdk","name":"mike"}}}""", launch.files["mcp.json"])
        assertEquals("C:\\Users\\me\\.hey-mike\\claude\\system-prompt.md", chat.after("--append-system-prompt-file"))
        assertEquals("C:\\Users\\me\\.hey-mike\\claude\\mcp.json", chat.after("--mcp-config"))
        assertEquals("opus", chat.after("--model"))
        assertEquals("mcp__mike__*", chat.after("--allowedTools"))
        assertEquals("acceptEdits", chat.after("--permission-mode"))
        assertEquals("stdio", chat.after("--permission-prompt-tool"))
        // Claude Code keeps its own tools, settings and MCP servers there.
        listOf("--tools", "--strict-mcp-config", "--setting-sources", "--system-prompt-file").forEach { assertFalse(it, it in chat.args) }
        assertFalse("--bare ignores subscriptions", "--bare" in chat.args)

        val initialize = chat.nextFrame { it.requestSubtype() == "initialize" }["request"]!!.jsonObject
        assertEquals(listOf("mike"), initialize["sdkMcpServers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("the host looks for claude on the computer first", host.prepareCalls > 0)
        assertTrue("no loopback server is started for a computer", servers.servers.isEmpty())
    }

    @Test fun thePhoneToolsReachAComputerChatOnItsOwnStreams() = runBlocking {
        val computer = FakeComputer()
        val engine = engine(computer)
        val (id, turn, chat) = engine.begin(computer)

        chat.send(control("mcp-1", """{"subtype":"mcp_message","server_name":"mike","message":{"method":"tools/list","jsonrpc":"2.0","id":1}}"""))
        val listed = chat.responseTo("mcp-1")
        assertEquals("success", listed["subtype"]!!.jsonPrimitive.content)
        val tools = listed["response"]!!.jsonObject["mcp_response"]!!.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("tap"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })

        // Recorded from Claude Code 2.1.286.
        chat.send(control("mcp-2", """{"subtype":"mcp_message","server_name":"mike","message":{"method":"tools/call","params":{"name":"tap","arguments":{"x":5},"_meta":{"claudecode/toolUseId":"toolu_015i1F2dtBMHz4RErNtRbecA","progressToken":2}},"jsonrpc":"2.0","id":2}}"""))
        val call = awaitEvent<EngineEvent.ToolCall>()
        assertEquals("tap", call.name)
        assertEquals("5", call.arguments["x"]!!.jsonPrimitive.content)
        assertEquals(id, call.threadId)
        assertEquals(turn, call.turnId)

        engine.answerTool(call.requestId, ToolResult("Tapped", imageBase64 = "AAAA"))
        val reply = chat.responseTo("mcp-2")["response"]!!.jsonObject["mcp_response"]!!.jsonObject
        assertEquals(2, reply["id"]!!.jsonPrimitive.content.toInt())
        val result = reply["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("text", "image"), result["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("Tapped", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun anImportedMissingSessionIsNeverReplacedWithAnEmptyOne() = runBlocking {
        val computer = FakeComputer()
        val known = "11111111-1111-4111-8111-111111111111"
        host.script = { process ->
            process.err("No conversation found with session ID: $known")
            process.finish(1)
        }
        val engine = engine(computer)
        val id = engine.openSessionAt(PROJECT, known, "sonnet", emptyList(), requireExisting = true)
        try { engine.startTurn(id, "continue", emptyList()); throw AssertionError("Missing session was resumed") }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("could not open")) }
        assertEquals(1, computer.chats.size)
        assertEquals(known, computer.chats.single().process.after("--resume"))
    }

    @Test fun handoffCannotStopAnActiveTurnButReleasesAnIdleProcess() = runBlocking {
        val computer = FakeComputer()
        val engine = engine(computer)
        val (id, _, chat) = engine.begin(computer)
        try { engine.releaseSession(id); throw AssertionError("Live turn was released") }
        catch (_: IllegalStateException) { }
        assertTrue(chat.isAlive)
        chat.send("""{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"$id"}""")
        awaitEvent<EngineEvent.TurnFinished>()
        engine.releaseSession(id)
        assertFalse(chat.isAlive)
    }

    @Test fun nativeIdsAreReportedBeforeCompletedText() = runBlocking {
        val computer = FakeComputer()
        val engine = engine(computer)
        val ids = CopyOnWriteArrayList<String>()
        engine.onNativeMessage = { _, message -> ids += message }
        val (_, _, chat) = engine.begin(computer)
        val native = "22222222-2222-4222-8222-222222222222"
        chat.send("""{"type":"assistant","uuid":"$native","message":{"id":"msg_test","model":"sonnet","content":[{"type":"text","text":"answer"}]}}""")
        awaitEvent<EngineEvent.MessageCompleted>()
        assertTrue(native in ids)
    }

    @Test fun aToolThatNeedsPermissionAsksOnThePhoneAndRunsAfterYes() = runBlocking {
        val computer = FakeComputer()
        val engine = engine(computer)
        val (id, turn, chat) = engine.begin(computer)

        chat.send(control("perm-1", CAN_USE_TOOL))
        val approval = awaitEvent<EngineEvent.Approval>()
        assertEquals("claude/can_use_tool", approval.method)
        assertEquals(id, approval.threadId)
        assertEquals(turn, approval.turnId)
        assertEquals("command", approval.details["kind"]!!.jsonPrimitive.content)
        assertEquals("curl -s https://example.com -o out.html", approval.details["command"]!!.jsonPrimitive.content)
        assertEquals(PROJECT, approval.details["cwd"]!!.jsonPrimitive.content)

        engine.answerApproval(approval.requestId, true)
        val answer = chat.responseTo("perm-1")["response"]!!.jsonObject
        assertEquals("allow", answer["behavior"]!!.jsonPrimitive.content)
        // The input goes back as it came; `allow` without it is refused by the CLI.
        assertEquals("curl -s https://example.com -o out.html", answer["updatedInput"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test fun aNoIsToldToClaude() = runBlocking {
        val computer = FakeComputer()
        val engine = engine(computer)
        val (_, _, chat) = engine.begin(computer)

        chat.send(control("perm-1", CAN_USE_TOOL))
        engine.answerApproval(awaitEvent<EngineEvent.Approval>().requestId, false)

        val answer = chat.responseTo("perm-1")["response"]!!.jsonObject
        assertEquals("deny", answer["behavior"]!!.jsonPrimitive.content)
        assertNull(answer["updatedInput"])
    }

    @Test fun stopRefusesAPromptNobodyAnswered() = runBlocking {
        val computer = FakeComputer()
        val engine = engine(computer)
        val (id, turn, chat) = engine.begin(computer)
        chat.send(control("perm-1", CAN_USE_TOOL))
        val approval = awaitEvent<EngineEvent.Approval>()

        engine.interrupt(id, turn)

        assertEquals("deny", chat.responseTo("perm-1")["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content)
        // A late answer from the card changes nothing.
        engine.answerApproval(approval.requestId, true)
    }

    @Test fun withFullAccessNothingIsAsked() = runBlocking {
        val computer = FakeComputer(askUser = false)
        val (_, _, chat) = engine(computer).begin(computer)

        assertEquals("bypassPermissions", chat.after("--permission-mode"))
        assertFalse("--permission-prompt-tool" in chat.args)
    }

    @Test fun aComputerWithoutClaudeSaysSoInsteadOfAskingForADownload() = runBlocking {
        host.status.value = RuntimeStatus(RuntimePhase.MISSING, "not there")
        val engine = engine(FakeComputer())

        val failure = runCatching { engine.openSessionAt(PROJECT, null, "sonnet", emptyList()) }.exceptionOrNull()

        assertEquals("Claude Code is not on PC.", failure?.message)
        assertEquals("Claude Code is not on PC.", engine.account().label)
    }

    @Test fun anMcpNotificationIsAnsweredAndAnUnknownMethodIsRefused() = runBlocking {
        suspend fun answer(message: String) = StdioMcp.answer("mike", Json.parseToJsonElement(message).jsonObject, listOf(tapTool)) { _, _ -> ToolResult("unused") }

        // As recorded: a notification has no id and is answered under id 0.
        val notified = answer("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")["mcp_response"]!!.jsonObject
        assertEquals(JsonPrimitive(0), notified["id"])
        assertEquals(JsonObject(emptyMap()), notified["result"])

        val started = answer("""{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{}},"jsonrpc":"2.0","id":0}""")
        assertEquals("2025-11-25", started["mcp_response"]!!.jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)

        val unknown = answer("""{"jsonrpc":"2.0","method":"resources/list","id":7}""")["mcp_response"]!!.jsonObject
        assertEquals(-32601, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())

        // A message for a server Mike does not serve is not Mike's to answer.
        val other = Json.parseToJsonElement("""{"subtype":"mcp_message","server_name":"other","message":{"method":"tools/list","id":1}}""").jsonObject
        assertNull(StdioMcp.messageOf(other, "mike"))
    }

    @Test fun aFileToolIsShownByItsFile() {
        val request = Json.parseToJsonElement(
            """{"subtype":"can_use_tool","tool_name":"Write","display_name":"Write","input":{"file_path":"C:\\Windows\\notes.txt","content":"x"},"decision_reason":"Outside the working directory"}""",
        ).jsonObject

        val details = ClaudePermission.details(request, PROJECT)

        assertEquals("file", details["kind"]!!.jsonPrimitive.content)
        assertEquals("C:\\Windows\\notes.txt", details["path"]!!.jsonPrimitive.content)
        assertNull("the file's content is not put on the card", details["input"])
    }

    private companion object {
        const val PROJECT = "C:\\Users\\me\\My Projects\\site"

        /** Recorded from Claude Code 2.1.286 with `--permission-prompt-tool stdio`. */
        const val CAN_USE_TOOL = """{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"curl -s https://example.com -o out.html","description":"Fetch https://example.com and save to out.html"},"description":"Fetch https://example.com and save to out.html","permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"curl -s https://example.com -o out.html"}],"behavior":"allow","destination":"localSettings"}],"decision_reason":"This command requires approval","decision_reason_type":"other","tool_use_id":"toolu_01K58zCxeJWTfsuP6auAoYrQ"}"""
    }
}
