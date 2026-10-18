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

import dev.androidagent.core.EngineEvent
import dev.androidagent.engineclaude.ClaudeStreamMapper.Signal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ClaudeStreamMapperTest {
    private val thread = "e2b0aead-c4ad-4805-82f8-7a335b3a5f54"

    private fun fixture(name: String): List<JsonObject> =
        javaClass.getResource("/claude/$name")!!.readText().lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

    private fun line(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun ClaudeStreamMapper.feed(lines: List<JsonObject>): List<Signal> = lines.flatMap { map(it) }

    private fun List<Signal>.events(): List<EngineEvent> = filterIsInstance<Signal.Emit>().map { it.event }

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content

    @Test fun authFailureAfterABuiltinWriteDoesNotReplayTheTurn() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "frame-1")
        mapper.map(line("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"write-1","name":"Write","input":{"file_path":"qa.txt"}}]}}"""))
        val signals = mapper.map(line("""{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Failed to refresh OAuth token: Another Claude Code process is refreshing it or exited mid-refresh."]}"""))
        assertTrue(signals.none { it is Signal.RetryAuth })
        assertEquals("failed", signals.events().filterIsInstance<EngineEvent.TurnFinished>().single().status)
    }

    @Test fun permanentAuthFailureIsNotRetried() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "frame-1")
        val signals = mapper.map(line("""{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Failed to refresh OAuth token: invalid_grant. Sign in again."]}"""))
        assertTrue(signals.none { it is Signal.RetryAuth })
        assertEquals("failed", signals.events().filterIsInstance<EngineEvent.TurnFinished>().single().status)
    }

    @Test fun aRecordedTextTurnStreamsThenMarksTheLastTextFinal() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "frame-1")
        val signals = mapper.feed(fixture("turn-text.jsonl"))
        val events = signals.events()

        assertTrue(signals.any { it is Signal.Init })
        val delta = events.filterIsInstance<EngineEvent.TextDelta>().single()
        assertEquals("hi there", delta.text)
        assertEquals("turn-1", delta.turnId)
        assertEquals(thread, delta.threadId)
        assertEquals("msg_011CfZbJRrehMzcNLwDxSeZ5:1", delta.itemId)

        val completed = events.filterIsInstance<EngineEvent.MessageCompleted>()
        assertEquals(listOf("commentary", "final_answer"), completed.map { it.phase })
        assertTrue(completed.all { it.text == "hi there" && it.itemId == delta.itemId && it.turnId == "turn-1" })

        val limits = events.filterIsInstance<EngineEvent.UsageChanged>().first { it.limits != null }.limits!!
        assertEquals(listOf("5-hour", "weekly"), limits.map { it.name })
        assertNotNull(events.filterIsInstance<EngineEvent.UsageChanged>().single { it.usage != null }.usage)

        val finished = events.last() as EngineEvent.TurnFinished
        assertEquals("completed", finished.status)
        assertNull(finished.error)
        assertEquals("turn-1", finished.turnId)
        // The final answer comes right before the turn ends, so the chat marks the message it already shows.
        assertEquals("final_answer", (events[events.size - 2] as EngineEvent.MessageCompleted).phase)
        assertNull(mapper.active)
        assertTrue(signals.any { it is Signal.Ended && it.status == "completed" })
    }

    @Test fun anInterruptedTurnThenAToolTurnWithAMergedSteer() {
        val lines = fixture("turn-interrupt-steer-glob.jsonl")
        val firstResult = lines.indexOfFirst { it.type() == "result" }
        val mapper = ClaudeStreamMapper(thread)

        val first = mapper.begin("turn-1", "frame-1")
        val interruptSignals = mapper.feed(lines.subList(0, firstResult).filter {
            // Stop before the interrupt is answered, then ask for it.
            it.type() != "control_response"
        })
        assertTrue(interruptSignals.events().any { it is EngineEvent.TextDelta })
        first.interruptRequested = true
        assertNull(mapper.toolTurn())
        val control = mapper.map(lines.first { it.type() == "control_response" })
        assertEquals("int-1", (control.single() as Signal.ControlResponse).requestId)
        val ended = mapper.map(lines[firstResult]).events()
        val finished = ended.filterIsInstance<EngineEvent.TurnFinished>().single()
        assertEquals("interrupted", finished.status)
        // An interrupted turn has no final answer.
        assertTrue(ended.none { it is EngineEvent.MessageCompleted })

        mapper.begin("turn-2", "frame-2")
        val events = mapper.feed(lines.subList(firstResult + 1, lines.size)).events()
        val glob = events.filterIsInstance<EngineEvent.ItemActivity>()
        assertEquals(listOf("streaming", "complete"), glob.map { it.state })
        assertTrue(glob.all { it.title == "Find files" && it.detail == "*.txt" && it.turnId == "turn-2" })
        assertTrue(events.any { it is EngineEvent.Activity && it.text == "Looking through session files" })
        val final = events.filterIsInstance<EngineEvent.MessageCompleted>().last()
        assertEquals("final_answer", final.phase)
        assertTrue(final.text.contains("Banana"))
        assertEquals("completed", events.filterIsInstance<EngineEvent.TurnFinished>().single().status)
    }

    @Test fun aSteerThatLostTheRaceRunsAsAnOrphanAndCannotCallTools() {
        val mapper = ClaudeStreamMapper(thread)
        val turn = mapper.begin("turn-1", "a")
        mapper.addFrame(turn, "b")
        mapper.map(line("""{"type":"command_lifecycle","command_uuid":"a","state":"started"}"""))
        assertSame(turn, mapper.toolTurn())
        mapper.map(line("""{"type":"result","subtype":"success","is_error":false,"result":"done"}"""))
        assertNull(mapper.active)

        // The steer "b" starts its own cycle after the turn ended.
        mapper.map(line("""{"type":"command_lifecycle","command_uuid":"b","state":"started"}"""))
        val next = mapper.begin("turn-2", "c")
        assertNull("an orphan cycle must not drive the phone", mapper.toolTurn())
        val orphan = mapper.feed(listOf(
            line("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m9"}}}"""),
            line("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}"""),
            line("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"late"}}}"""),
            line("""{"type":"result","subtype":"success","is_error":false,"result":"late"}"""),
        )).events()
        assertTrue(orphan.none { it is EngineEvent.TextDelta || it is EngineEvent.TurnFinished })
        assertSame(next, mapper.active)

        // Turn 2's own frame claims the next cycle.
        mapper.map(line("""{"type":"user","uuid":"c","isReplay":true,"message":{"role":"user","content":"go"}}"""))
        assertSame(next, mapper.toolTurn())
        val done = mapper.map(line("""{"type":"result","subtype":"success","is_error":false}""")).events()
        assertEquals("turn-2", (done.last() as EngineEvent.TurnFinished).turnId)
    }

    @Test fun echoesOfTheClisOwnMessagesChangeNothing() {
        val mapper = ClaudeStreamMapper(thread)
        val turn = mapper.begin("turn-1", "a")
        mapper.map(line("""{"type":"user","uuid":"cli-made","isReplay":true,"message":{"role":"user","content":"x"}}"""))
        assertSame(turn, mapper.toolTurn())
    }

    @Test fun compactionRunsSilentlyAndEndsOnItsResult() {
        val lines = fixture("turn-then-compact.jsonl")
        val firstResult = lines.indexOfFirst { it.type() == "result" }
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "11111111-1111-4111-8111-111111111111")
        mapper.feed(lines.subList(0, firstResult + 1))
        val compact = mapper.begin("compact-1", "22222222-2222-4222-8222-222222222222", internal = true)
        assertNull("no tools during compaction", mapper.toolTurn())
        val signals = mapper.feed(lines.subList(firstResult + 1, lines.size))
        assertTrue(signals.events().none { it is EngineEvent.TurnFinished || it is EngineEvent.MessageCompleted || it is EngineEvent.TextDelta })
        assertTrue(compact.done.isCompleted)
        assertTrue(signals.any { it is Signal.Ended && it.turn === compact && it.status == "completed" })
    }

    @Test fun aSyntheticAuthErrorIsTheTurnErrorNotMikesWords() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "a")
        val events = mapper.feed(listOf(
            line("""{"type":"assistant","message":{"id":"x","model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"Invalid API key · Please run /login"}]},"parent_tool_use_id":null,"error":"authentication_failed"}"""),
            line("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login","num_turns":1}"""),
        )).events()
        assertTrue(events.none { it is EngineEvent.MessageCompleted })
        val finished = events.filterIsInstance<EngineEvent.TurnFinished>().single()
        assertEquals("failed", finished.status)
        assertEquals("Invalid API key · Please run /login", finished.error)
    }

    @Test fun aRejectedLimitFailsTheTurnWithTheResetTime() {
        val mapper = ClaudeStreamMapper(thread, zone = ZoneId.of("UTC"), clock = { Instant.ofEpochSecond(1790785800L - 600) })
        mapper.begin("turn-1", "a")
        val events = mapper.feed(listOf(
            line("""{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":1790785800,"rateLimitType":"five_hour","unifiedWindows":{"five_hour":{"utilization":1.0,"resetsAt":1790785800}}}}"""),
            line("""{"type":"result","subtype":"success","is_error":true,"result":"Claude AI usage limit reached|1790785800"}"""),
        )).events()
        assertEquals(100.0, events.filterIsInstance<EngineEvent.UsageChanged>().single().limits!!.single().usedPercent!!, 0.001)
        assertEquals(
            "You have reached your Claude 5-hour usage limit. It resets at 16:30.",
            events.filterIsInstance<EngineEvent.TurnFinished>().single().error,
        )
    }

    @Test fun mcpToolUseIsLeftToTheToolBridge() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "a")
        val events = mapper.feed(listOf(
            line("""{"type":"assistant","message":{"id":"m1","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Tapping now."}]},"parent_tool_use_id":null}"""),
            line("""{"type":"assistant","message":{"id":"m1","model":"claude-sonnet-5-5","content":[{"type":"tool_use","id":"toolu_1","name":"mcp__mike__tap","input":{"x":1}}]},"parent_tool_use_id":null}"""),
            line("""{"type":"result","subtype":"success","is_error":false,"result":"Tapping now."}"""),
        )).events()
        assertTrue(events.none { it is EngineEvent.ItemActivity })
        // Text followed by a tool call is commentary; nothing is re-sent as final.
        assertEquals(listOf("commentary"), events.filterIsInstance<EngineEvent.MessageCompleted>().map { it.phase })
    }

    @Test fun aProcessThatDiesMidTurnFailsItWithARedactedReason() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "a")
        val events = mapper.processEnded("fatal: token sk-ant-oat01-abcdefghijklmnop rejected").events()
        val failure = events.filterIsInstance<EngineEvent.Failure>().single()
        assertFalse(failure.message.contains("sk-ant-oat01-abcdefghijklmnop"))
        assertEquals("turn-1", failure.turnId)
        assertEquals("failed", events.filterIsInstance<EngineEvent.TurnFinished>().single().status)
    }

    @Test fun aKilledProcessAfterAStopReadsAsInterrupted() {
        val mapper = ClaudeStreamMapper(thread)
        val turn = mapper.begin("turn-1", "a")
        turn.interruptRequested = true
        val events = mapper.processEnded("killed").events()
        assertTrue(events.none { it is EngineEvent.Failure })
        assertEquals("interrupted", events.filterIsInstance<EngineEvent.TurnFinished>().single().status)
    }

    @Test fun endingTwiceCountsOnce() {
        val mapper = ClaudeStreamMapper(thread)
        val turn = mapper.begin("turn-1", "a")
        assertEquals(1, mapper.end(turn, "interrupted", null).events().size)
        assertTrue(mapper.end(turn, "interrupted", null).isEmpty())
        assertTrue(mapper.processEnded("x").isEmpty())
    }

    @Test fun unknownTypesAndSubagentOutputAreIgnored() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "a")
        assertTrue(mapper.map(line("""{"type":"something_new","x":1}""")).isEmpty())
        assertTrue(mapper.map(line("""{"type":"system","subtype":"post_turn_summary"}""")).isEmpty())
        assertTrue(mapper.map(line("""{"type":"assistant","message":{"id":"s","content":[{"type":"text","text":"sub"}]},"parent_tool_use_id":"toolu_9"}""")).isEmpty())
    }

    @Test fun apiRetriesShowAsActivity() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "a")
        val event = mapper.map(line("""{"type":"system","subtype":"api_retry","attempt":1,"max_retries":2,"retry_delay_ms":500,"error_status":529}""")).events().single()
        assertEquals("Retrying the request (1 of 2)", (event as EngineEvent.Activity).text)
    }
}
