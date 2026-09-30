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

import dev.androidagent.core.AdbStatus
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

class ClaudeCodeEngineTest {
    private val home = Files.createTempDirectory("claude-home").toFile()
    private val workspace = Files.createTempDirectory("claude-ws").toFile()
    private val host = FakeHost(home)
    private val servers = FakeToolServers()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<EngineEvent>()
    private val engines = mutableListOf<ClaudeCodeEngine>()
    private val tapTool = ToolDefinition("tap", "Tap the screen", buildJsonObject { put("type", "object") })

    private fun engine(grace: Long = 3_000): ClaudeCodeEngine =
        ClaudeCodeEngine(host, servers, interruptGraceMs = grace, clock = { ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneId.of("UTC")) })
            .also { engine ->
                engines += engine
                scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
            }

    @After fun tearDown() {
        runBlocking { engines.forEach { runCatching { withTimeout(10_000) { it.close() } } } }
        scope.cancel()
    }

    private inline fun <reified T : EngineEvent> awaitEvent(timeoutMs: Long = 5_000, predicate: (T) -> Boolean = { true }): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            events.filterIsInstance<T>().firstOrNull(predicate)?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("No ${T::class.simpleName} in $events")
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out; events: $events")
            Thread.sleep(10)
        }
    }

    private fun fixture(name: String): List<String> =
        javaClass.getResource("/claude/$name")!!.readText().lines().filter { it.isNotBlank() }

    private fun result(extra: String = """"subtype":"success","is_error":false,"result":"ok""""): String = """{"type":"result",$extra}"""

    private suspend fun ClaudeCodeEngine.begin(prompt: String = "hello", effort: String? = null, threadId: String? = null): Pair<String, String> {
        val id = openSession(workspace, threadId, "sonnet", listOf(tapTool))
        val turn = startTurn(id, prompt, emptyList(), effort, null, DeviceCapabilities(ready = setOf("tap")), null)
        return id to turn
    }

    @Test fun aTurnRunsInItsOwnProcessAndStreamsTheRecordedReply() = runBlocking {
        File(workspace, "AGENTS.md").writeText("# Mike manual\nUse act_plan.")
        val engine = engine()
        val id = engine.openSession(workspace, null, "opus", listOf(tapTool))
        assertTrue(ClaudeProtocol.isSessionId(id))
        assertTrue("the process starts with the first turn", host.started.isEmpty())

        val turnId = engine.startTurn(id, "hello", emptyList(), "high", null, DeviceCapabilities(ready = setOf("tap")), null)
        val chat = host.chats().single()
        assertEquals(workspace.absoluteFile, chat.cwd)
        assertEquals(id, chat.after("--session-id"))
        assertEquals("opus", chat.after("--model"))
        assertEquals("high", chat.after("--effort"))
        assertEquals(ClaudeProtocol.CHAT_ENV, chat.env)
        val mcpConfig = File(chat.after("--mcp-config"))
        assertTrue(mcpConfig.readText().contains("Bearer test-token"))
        assertTrue("the MCP token stays in the private home", mcpConfig.canonicalPath.startsWith(home.canonicalPath))
        val prompt = File(chat.after("--system-prompt-file")).readText()
        assertTrue(prompt.contains("Your name is Mike") && prompt.endsWith("# Mike manual\nUse act_plan."))
        assertTrue(servers.servers.single().started)

        chat.nextFrame { it.type() == "control_request" && it.requestSubtype() == "initialize" }
        val frame = chat.nextFrame { it.type() == "user" }
        val content = frame["message"]!!.jsonObject["content"]!!.jsonArray
        assertTrue(content.first().jsonObject["text"]!!.jsonPrimitive.content.startsWith("[Trusted Android Agent runtime context]"))
        assertEquals("hello", content.last().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(turnId, awaitEvent<EngineEvent.TurnStarted>().turnId)

        fixture("turn-text.jsonl").forEach(chat::send)
        val finished = awaitEvent<EngineEvent.TurnFinished>()
        assertEquals("completed", finished.status)
        assertEquals(id, finished.threadId)
        assertEquals(turnId, finished.turnId)
        assertEquals("hi there", awaitEvent<EngineEvent.TextDelta>().text)
        assertEquals(turnId, awaitEvent<EngineEvent.MessageCompleted> { it.phase == "final_answer" }.turnId)
        // The model list now comes from the running CLI.
        assertTrue(engine.modelCatalog().any { it.id == "claude-opus-4-8" })
    }

    @Test fun aToolCallReachesTheAppAndWaitsForItsAnswer() = runBlocking {
        val engine = engine()
        val (id, turnId) = engine.begin()
        val chat = host.chats().single()
        val server = servers.servers.single()
        assertEquals(listOf("tap"), server.tools().map { it.name })

        val call = async(Dispatchers.Default) { server.call("mcp__mike__tap", buildJsonObject { put("x", 5) }) }
        val toolCall = awaitEvent<EngineEvent.ToolCall>()
        assertEquals("tap", toolCall.name)
        assertEquals(id, toolCall.threadId)
        assertEquals(turnId, toolCall.turnId)
        assertEquals(5, toolCall.arguments["x"]!!.jsonPrimitive.int)
        assertFalse("the call waits for the app", call.isCompleted)
        engine.answerTool(toolCall.requestId, ToolResult("tapped", imageBase64 = "AAAA"))
        assertEquals("tapped", call.await().text)

        chat.send(result())
        awaitEvent<EngineEvent.TurnFinished>()
        val late = server.call("tap", JsonObject(emptyMap()))
        assertFalse(late.success)
        assertTrue(late.text.contains("No task is running"))
    }

    @Test fun stopRevokesToolCallsThenKillsWhenNoResultArrives() = runBlocking {
        val engine = engine(grace = 300)
        val (id, turnId) = engine.begin()
        val chat = host.chats().single()
        val call = async(Dispatchers.Default) { servers.servers.single().call("tap", JsonObject(emptyMap())) }
        awaitEvent<EngineEvent.ToolCall>()

        engine.interrupt(id, turnId)
        assertFalse("a pending call is revoked", call.await().success)
        val interrupt = chat.nextFrame { it.type() == "control_request" && it.requestSubtype() == "interrupt" }
        assertEquals(JsonPrimitive(true), interrupt["request"]!!.jsonObject["cancel_queued"])

        val finished = awaitEvent<EngineEvent.TurnFinished>()
        assertEquals("interrupted", finished.status)
        assertEquals(turnId, finished.turnId)
        assertTrue("SIGKILL after the grace period", chat.forced)
        assertTrue(events.none { it is EngineEvent.Failure })
        assertTrue(events.filterIsInstance<EngineEvent.TurnFinished>().size == 1)
    }

    @Test fun aStopTheCliHonoursEndsTheTurnWithoutAKill() = runBlocking {
        val engine = engine(grace = 300)
        val (id, turnId) = engine.begin()
        val chat = host.chats().single()
        engine.interrupt(id, turnId)
        chat.nextFrame { it.requestSubtype() == "interrupt" }
        chat.send(result(""""subtype":"error_during_execution","is_error":true,"terminal_reason":"aborted_streaming""""))
        assertEquals("interrupted", awaitEvent<EngineEvent.TurnFinished>().status)
        Thread.sleep(600)
        assertFalse(chat.forced)
        assertTrue(chat.isAlive)
    }

    @Test fun aProcessThatDiesMidTurnFailsAndTheNextTurnResumes() = runBlocking {
        val engine = engine()
        val (id, turnId) = engine.begin()
        val chat = host.chats().single()
        chat.err("fatal: bad token sk-ant-api03-SECRETSECRETSECRET")
        chat.finish(1)
        val failure = awaitEvent<EngineEvent.Failure>()
        assertEquals(turnId, failure.turnId)
        assertFalse(failure.message.contains("SECRETSECRETSECRET"))
        assertEquals("failed", awaitEvent<EngineEvent.TurnFinished>().status)

        engine.startTurn(id, "again", emptyList(), null, null, DeviceCapabilities(), null)
        val second = host.chats()[1]
        assertEquals(id, second.after("--resume"))
    }

    @Test fun aSessionThatNeverGotAMessageStartsAgainUnderTheSameId() = runBlocking {
        val known = "0b9f6a8e-3f7d-4c1a-9e2b-5d6c7e8f9a0b"
        host.script = { process ->
            if ("--resume" in process.args) {
                process.err("No conversation found with session ID: $known")
                process.send(result(""""subtype":"error_during_execution","is_error":true,"num_turns":0"""))
                process.finish(1)
            } else {
                process.answerInitialize()
            }
        }
        val engine = engine()
        val (id, _) = engine.begin(threadId = known)
        assertEquals(known, id)
        val chats = host.chats()
        assertEquals(2, chats.size)
        assertEquals(known, chats[0].after("--resume"))
        assertEquals(known, chats[1].after("--session-id"))
        assertNotNull(chats[1].nextFrame { it.type() == "user" })
    }

    @Test fun aStartFailureIsReportedWithoutSecrets() = runBlocking {
        host.script = { process ->
            process.err("boom sk-ant-oat01-ZZZZZZZZZZZZ")
            process.finish(1)
        }
        val engine = engine()
        val id = engine.openSession(workspace, null, "sonnet", emptyList())
        try {
            engine.startTurn(id, "hi", emptyList(), null, null, DeviceCapabilities(), null)
            fail("the turn must not start")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.startsWith("Claude could not open this chat"))
            assertFalse(error.message!!.contains("ZZZZZZZZZZZZ"))
        }
    }

    @Test fun anEffortChangeRestartsTheChatWithResume() = runBlocking {
        val engine = engine()
        val (id, _) = engine.begin(effort = "high")
        val first = host.chats().single()
        first.send(result())
        awaitEvent<EngineEvent.TurnFinished>()

        engine.startTurn(id, "again", emptyList(), "low", null, DeviceCapabilities(), null)
        val second = host.chats()[1]
        assertEquals(id, second.after("--resume"))
        assertEquals("low", second.after("--effort"))
        assertFalse("the idle process was closed, not killed", first.forced)
        assertFalse(first.isAlive)

        // Same settings: the running process is reused.
        second.send(result())
        waitUntil { events.filterIsInstance<EngineEvent.TurnFinished>().size == 2 }
        engine.startTurn(id, "third", emptyList(), "low", null, DeviceCapabilities(), null)
        assertEquals(2, host.chats().size)
    }

    @Test fun steerJoinsTheRunningTurnOnly() = runBlocking {
        val engine = engine()
        val (id, turnId) = engine.begin()
        val chat = host.chats().single()
        chat.nextFrame { it.type() == "user" }
        engine.steer(id, turnId, "also this")
        val steer = chat.nextFrame { it.type() == "user" }
        assertEquals("next", steer["priority"]!!.jsonPrimitive.content)
        assertEquals("also this", steer["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)

        chat.send(result())
        awaitEvent<EngineEvent.TurnFinished>()
        try {
            engine.steer(id, turnId, "too late")
            fail("a finished turn takes no steer")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test fun compactWritesTheCommandAndWaitsForItsResult() = runBlocking {
        val engine = engine()
        val (id, _) = engine.begin()
        val chat = host.chats().single()
        chat.send(result())
        awaitEvent<EngineEvent.TurnFinished>()

        val compact = async(Dispatchers.Default) { engine.compact(id) }
        val frame = chat.nextFrame { it.type() == "user" && it.toString().contains("/compact") }
        assertNotNull(frame["uuid"])
        assertFalse(compact.isCompleted)
        chat.send("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual"}}""")
        chat.send(result(""""subtype":"success","is_error":false,"result":"""""))
        compact.await()
        assertEquals(1, events.filterIsInstance<EngineEvent.TurnFinished>().size)
    }

    @Test fun signInRelaysThePastedCodeToTheLoginProcessOnly() = runBlocking {
        val url = "https://claude.ai/oauth/authorize?code=true&client_id=abc&response_type=code&state=xyz"
        host.script = { process ->
            when (process.args.take(2)) {
                listOf("auth", "login") -> {
                    process.send("Opening browser to sign in…")
                    process.send("If the browser didn't open, visit: $url")
                    process.sendRaw("Paste code here if prompted > ")
                    process.onLine = { p, line -> if (line == "the-code#the-state") p.finish(0) else p.finish(1) }
                }
                listOf("auth", "status") -> {
                    process.send("""{"loggedIn":true,"authMethod":"claude.ai","email":"user@example.com"}""")
                    process.finish(0)
                }
            }
        }
        val engine = engine()
        val started = engine.login()
        assertFalse(started.signedIn)
        assertEquals(url, started.loginUrl)
        val login = host.started.single()
        assertEquals(listOf("auth", "login", "--claudeai"), login.args)
        assertEquals(home, login.cwd)

        try {
            engine.completeLogin("a\nb")
            fail("a code with a line break must be refused")
        } catch (expected: IllegalArgumentException) {
        }
        val status = engine.completeLogin("  the-code#the-state \n")
        assertEquals(0, login.exitCode)
        assertTrue(status.signedIn)
        assertEquals("user@example.com", status.label)
        assertEquals(status, awaitEvent<EngineEvent.AccountChanged>().status)
        assertEquals(listOf("auth", "status", "--json"), host.started.last().args)
    }

    @Test fun nothingRunsBeforeTheBinaryIsDownloaded() = runBlocking {
        host.status.value = RuntimeStatus(RuntimePhase.MISSING, "Not downloaded")
        val engine = engine()
        val status = engine.account()
        assertFalse(status.signedIn)
        assertEquals(ClaudeCodeEngine.DOWNLOAD_FIRST, status.label)
        try {
            engine.connect()
            fail("connect needs the binary")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(ClaudeProtocol.FALLBACK_MODELS, engine.modelCatalog())
        assertEquals("no silent download", 0, host.prepareCalls)
        assertTrue(host.started.isEmpty())
    }

    @Test fun theModelListIsProbedFromTheCliWhenNoChatRuns() = runBlocking {
        val engine = engine()
        val models = engine.modelCatalog()
        assertEquals("sonnet", models.first().id)
        val probe = host.started.single()
        assertFalse("the probe never sends a message", probe.lines.any { it.contains("\"type\":\"user\"") })
        assertFalse(probe.isAlive)
    }

    @Test fun skillsAreReadFromTheInstalledSkillFilesOnly() = runBlocking {
        val skills = File(home, ".claude/skills")
        File(skills, "quick-actions").mkdirs()
        File(skills, "quick-actions/SKILL.md").writeText("---\nname: quick-actions\ndescription: Fast things\n---\n")
        File(skills, "device-automation").mkdirs()
        File(skills, "device-automation/SKILL.md").writeText("---\nname: device-automation\ndescription: Drive the phone\n---\n")
        File(skills, "empty").mkdirs()
        val catalog = engine().skillCatalog(workspace)
        assertEquals(listOf("device-automation", "quick-actions"), catalog.map { it.name })
        assertEquals("Drive the phone", catalog.first().description)
    }

    @Test fun closeStopsEveryChatAndTheHost() = runBlocking {
        val engine = engine()
        val (_, turnId) = engine.begin()
        val chat = host.chats().single()
        engine.close()
        assertFalse(chat.isAlive)
        assertTrue(servers.servers.single().stopped)
        assertEquals(1, host.stopAllCalls)
        assertEquals("interrupted", awaitEvent<EngineEvent.TurnFinished> { it.turnId == turnId }.status)
    }

    @Test fun cliPermissionPromptsAreDenied() = runBlocking {
        val engine = engine()
        engine.begin()
        val chat = host.chats().single()
        chat.send("""{"type":"control_request","request_id":"cli-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}""")
        val reply = chat.nextFrame { it.type() == "control_response" }
        val response = reply["response"]!!.jsonObject
        assertEquals("cli-1", response["request_id"]!!.jsonPrimitive.content)
        assertEquals("deny", response["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content)
    }

    @Test fun olderAdbOverloadsStillStartATurn() = runBlocking {
        val engine = engine()
        val id = engine.openSession(workspace, null, null, emptyList())
        val turn = engine.startTurn(id, "hi", emptyList(), null, null, AdbStatus())
        assertTrue(turn.isNotBlank())
        val frame = host.chats().single().nextFrame { it.type() == "user" }
        assertEquals("sonnet", host.chats().single().after("--model"))
        assertTrue(Json.encodeToString(JsonObject.serializer(), frame).contains("[Trusted Android Agent runtime context]"))
    }
}
