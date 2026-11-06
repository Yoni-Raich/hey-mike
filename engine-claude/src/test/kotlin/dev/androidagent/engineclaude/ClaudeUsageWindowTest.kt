package dev.androidagent.engineclaude

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Starting the Claude plan's 5-hour window with one short message. */
class ClaudeUsageWindowTest {
    @get:Rule val temp = TemporaryFolder()

    private val host by lazy { FakeHost(temp.newFolder("home")) }
    private val engine by lazy { ClaudeCodeEngine(host, FakeToolServers()) }

    private val rateLimit =
        """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1790785800,"rateLimitType":"five_hour",""" +
            """"unifiedWindows":{"five_hour":{"utilization":0.01,"resetsAt":1790785800},"seven_day":{"utilization":0.32,"resetsAt":1790827200}}}}"""

    /** Answer the message like the CLI: a limits event, a result, then the `get_usage` reply. */
    private fun script(result: String, usage: Boolean = true) {
        host.script = { process ->
            process.onLine = { p, line ->
                val frame = Json.parseToJsonElement(line).jsonObject
                when {
                    frame.type() == "user" -> { p.send(rateLimit); p.send(result) }
                    frame.type() == "control_request" && frame.requestSubtype() == "get_usage" -> {
                        val id = frame["request_id"]!!.jsonPrimitive.content
                        if (usage) p.send("""{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":$GET_USAGE_REPLY}}""")
                        else p.send("""{"type":"control_response","response":{"subtype":"error","request_id":"$id","error":"unknown"}}""")
                    }
                }
            }
        }
    }

    @Test fun oneShortMessageWithNoToolsThenTheFullReading() = runBlocking {
        script("""{"type":"result","subtype":"success","is_error":false,"result":"Hi"}""")
        val limits = engine.startUsageWindow()
        val process = host.started.single()
        assertEquals("haiku", process.after("--model"))
        assertEquals("", process.after("--tools"))
        assertTrue("--no-session-persistence" in process.args)
        val message = process.nextFrame { it.type() == "user" }
        val text = message["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("hi", text)
        // The get_usage reply has both windows, with ISO times.
        assertEquals(listOf("5-hour", "weekly"), limits.map { it.name })
        assertEquals(300L, limits.first().windowMinutes)
        assertTrue("the process is not left running", process.forced)
    }

    @Test fun withoutAFullReadingTheMessagesOwnLimitsAreUsed() = runBlocking {
        script("""{"type":"result","subtype":"success","is_error":false,"result":"Hi"}""", usage = false)
        val limits = engine.startUsageWindow()
        assertEquals(1790785800L, limits.first { it.name == "5-hour" }.resetsAt)
    }

    @Test fun anErrorResultFailsTheStart() = runBlocking {
        script("""{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login"}""")
        val failure = runCatching { engine.startUsageWindow() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("Not logged in"))
    }
}
