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
import dev.androidagent.core.SecretRedactor
import dev.androidagent.engineclaude.ClaudeProtocol.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Turns the output of one `claude` chat process into [EngineEvent]s.
 *
 * Not thread-safe: the owner serialises calls. It never does IO, so it is
 * tested line by line against recorded output.
 *
 * **Which turn a line belongs to.** The CLI runs one cycle per user message
 * it dequeues. Every frame the app writes carries a `uuid`, and the CLI
 * echoes it (`command_lifecycle` "started", and the `user` replay) before the
 * cycle's output. A cycle started by a frame of the running turn belongs to
 * it; one started by a frame of a turn that already ended (a steer that lost
 * the race with the turn's `result`) belongs to nobody, so its text is not
 * shown and its tool calls are refused. Echoes of uuids the app never wrote
 * (the CLI's own messages) change nothing. When the CLI is idle a new turn
 * owns the next cycle at once, so a CLI that stops echoing still works.
 *
 * **Final answer.** The last text of a completed turn is sent again with
 * phase `final_answer` just before `TurnFinished`: the same item id and text,
 * so the chat only marks it final. Text followed by a tool call is commentary.
 */
internal class ClaudeStreamMapper(
    private val threadId: String,
    private val workspace: File? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
) {
    class Turn(val turnId: String, val internal: Boolean) {
        val frames: MutableSet<String> = mutableSetOf()
        @Volatile var interruptRequested = false
        var rejection: String? = null
        var syntheticError: String? = null
        var ended = false
            internal set
        var error: String? = null
            internal set
        /** Completes with the final status once the turn has ended. */
        val done = CompletableDeferred<String>()
    }

    sealed interface Signal {
        data class Emit(val event: EngineEvent) : Signal
        data class ControlResponse(val requestId: String, val response: JsonObject?, val error: String?) : Signal
        data class ControlRequest(val requestId: String, val request: JsonObject) : Signal
        data class Init(val message: JsonObject) : Signal
        data class Ended(val turn: Turn, val status: String) : Signal
    }

    /** The turn the app considers running. */
    var active: Turn? = null
        private set

    /** The turn the CLI's current cycle belongs to; null for an orphan cycle or none. */
    private var owner: Turn? = null
    private var busy = false
    private val retired = LinkedHashSet<String>()
    private var messageId: String? = null
    private var textIndex: Int? = null
    private var generatedText = 0
    private var lastText: EngineEvent.MessageCompleted? = null
    private val builtinTools = mutableMapOf<String, Pair<String, String>>()

    fun begin(turnId: String, frameUuid: String, internal: Boolean = false): Turn {
        check(active == null) { "A turn is already running in this chat." }
        val turn = Turn(turnId, internal).also { it.frames += frameUuid }
        active = turn
        if (!busy) owner = turn
        lastText = null
        builtinTools.clear()
        return turn
    }

    /** A steer or other extra frame that belongs to [turn]. */
    fun addFrame(turn: Turn, frameUuid: String) {
        turn.frames += frameUuid
    }

    /**
     * The turn a tool call from the CLI right now belongs to, or null when
     * none may run: no turn, an orphan cycle, compaction, or a stop.
     */
    fun toolTurn(): Turn? = owner?.takeIf { it === active && !it.internal && !it.ended && !it.interruptRequested }

    /** Forget [turn] without any event, for a turn whose frame never reached the CLI. */
    fun abandon(turn: Turn) {
        if (turn.ended) return
        turn.ended = true
        retire(turn)
        turn.done.complete("failed")
    }

    /**
     * End [turn] with [status]. Idempotent: the watchdog and the reader can
     * both try, and only the first counts.
     */
    fun end(turn: Turn, status: String, error: String?): List<Signal> {
        if (turn.ended) return emptyList()
        turn.ended = true
        turn.error = error
        val signals = mutableListOf<Signal>()
        if (!turn.internal) {
            if (status == "completed") lastText?.let { signals += Signal.Emit(it.copy(phase = "final_answer")) }
            signals += Signal.Emit(EngineEvent.TurnFinished(status, error, threadId, turn.turnId))
        }
        signals += Signal.Ended(turn, status)
        retire(turn)
        turn.done.complete(status)
        return signals
    }

    /** The process is gone. A running turn fails, or reads as interrupted when a stop asked for it. */
    fun processEnded(detail: String): List<Signal> {
        busy = false
        owner = null
        val turn = active?.takeIf { !it.ended } ?: return emptyList()
        if (turn.interruptRequested) return end(turn, "interrupted", null)
        val message = SecretRedactor.redact("Claude stopped unexpectedly${if (detail.isBlank()) "" else ": $detail"}")
        val signals = mutableListOf<Signal>()
        if (!turn.internal) signals += Signal.Emit(EngineEvent.Failure(message, threadId, turn.turnId))
        return signals + end(turn, "failed", message)
    }

    fun map(message: JsonObject): List<Signal> = when (message.string("type")) {
        "control_response" -> controlResponse(message)
        "control_request" -> message.string("request_id").takeIf { it.isNotBlank() }?.let { id ->
            listOf(Signal.ControlRequest(id, message["request"] as? JsonObject ?: JsonObject(emptyMap())))
        }.orEmpty()
        "command_lifecycle" -> {
            if (message.string("state") == "started") claim(message.string("command_uuid"))
            emptyList()
        }
        "system" -> system(message)
        "user" -> user(message)
        "stream_event" -> streamEvent(message)
        "assistant" -> assistant(message)
        "rate_limit_event" -> rateLimit(message)
        "result" -> result(message)
        else -> emptyList()
    }

    private fun controlResponse(message: JsonObject): List<Signal> {
        val response = message["response"] as? JsonObject ?: return emptyList()
        val id = response.string("request_id").ifBlank { return emptyList() }
        return if (response.string("subtype") == "success") {
            listOf(Signal.ControlResponse(id, response["response"] as? JsonObject ?: JsonObject(emptyMap()), null))
        } else {
            listOf(Signal.ControlResponse(id, null, response.string("error").ifBlank { "Claude refused the request" }))
        }
    }

    private fun claim(uuid: String) {
        if (uuid.isBlank()) return
        val turn = active
        when {
            turn != null && !turn.ended && uuid in turn.frames -> { owner = turn; busy = true }
            uuid in retired -> { owner = null; busy = true }
        }
    }

    /** The running turn, when this cycle is its own and it still takes output. */
    private fun cycleTurn(): Turn? = owner?.takeIf { it === active && !it.ended }

    private fun visibleTurn(message: JsonObject): Turn? {
        if (message["parent_tool_use_id"] is JsonPrimitive && message.string("parent_tool_use_id").isNotBlank()) return null
        return cycleTurn()?.takeIf { !it.internal }
    }

    private fun system(message: JsonObject): List<Signal> {
        val turn = visibleTurn(message)
        return when (message.string("subtype")) {
            "init" -> {
                busy = true
                listOf(Signal.Init(message))
            }
            "api_retry" -> turn?.let {
                val attempt = (message["attempt"] as? JsonPrimitive)?.longOrNull
                val max = (message["max_retries"] as? JsonPrimitive)?.longOrNull
                val text = if (attempt != null && max != null) "Retrying the request ($attempt of $max)" else "Retrying the request"
                listOf(Signal.Emit(EngineEvent.Activity(text, threadId, it.turnId)))
            }.orEmpty()
            "compact_boundary" -> turn?.let {
                listOf(Signal.Emit(EngineEvent.Activity("Making room in context", threadId, it.turnId)))
            }.orEmpty()
            else -> emptyList()
        }
    }

    private fun user(message: JsonObject): List<Signal> {
        if ((message["isReplay"] as? JsonPrimitive)?.booleanOrNull == true) {
            claim(message.string("uuid"))
            return emptyList()
        }
        val turn = visibleTurn(message) ?: return emptyList()
        val content = (message["message"] as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            if (block.string("type") != "tool_result") return@mapNotNull null
            val id = block.string("tool_use_id")
            val (title, detail) = builtinTools.remove(id) ?: return@mapNotNull null
            val failed = (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true
            Signal.Emit(EngineEvent.ItemActivity(id, title, detail, if (failed) "failed" else "complete", threadId, turn.turnId))
        }
    }

    private fun streamEvent(message: JsonObject): List<Signal> {
        val turn = visibleTurn(message) ?: return emptyList()
        val event = message["event"] as? JsonObject ?: return emptyList()
        return when (event.string("type")) {
            "message_start" -> {
                messageId = (event["message"] as? JsonObject)?.string("id")?.ifBlank { null }
                textIndex = null
                emptyList()
            }
            "content_block_start" -> {
                val block = event["content_block"] as? JsonObject
                val index = (event["index"] as? JsonPrimitive)?.longOrNull?.toInt()
                when (block?.string("type")) {
                    "text" -> { textIndex = index; emptyList() }
                    "thinking", "redacted_thinking" -> listOf(Signal.Emit(EngineEvent.Activity("Working", threadId, turn.turnId)))
                    else -> emptyList()
                }
            }
            "content_block_delta" -> {
                val delta = event["delta"] as? JsonObject
                if (delta?.string("type") != "text_delta") return emptyList()
                val text = delta.string("text").ifEmpty { return emptyList() }
                val index = (event["index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: textIndex
                listOf(Signal.Emit(EngineEvent.TextDelta(text, threadId, turn.turnId, itemId(index))))
            }
            "content_block_stop" -> {
                val index = (event["index"] as? JsonPrimitive)?.longOrNull?.toInt()
                if (index != null && index == textIndex) textIndex = null
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun itemId(index: Int?): String? {
        val message = messageId ?: return null
        return if (index != null) "$message:$index" else null
    }

    private fun assistant(message: JsonObject): List<Signal> {
        val turn = visibleTurn(message) ?: return emptyList()
        val body = message["message"] as? JsonObject ?: return emptyList()
        val blocks = (body["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // API failures come back as a made-up assistant message ("Invalid API
        // key · Please run /login"). It is the turn's error, not Mike's words.
        if (body.string("model") == "<synthetic>" || message.string("error").isNotBlank()) {
            blocks.filter { it.string("type") == "text" }.joinToString("\n") { it.string("text") }
                .trim().takeIf { it.isNotEmpty() }?.let { turn.syntheticError = SecretRedactor.redactUiText(it) }
            return emptyList()
        }
        val bodyId = body.string("id").ifBlank { null }
        if (bodyId != null && bodyId != messageId) { messageId = bodyId; textIndex = null }
        val signals = mutableListOf<Signal>()
        for (block in blocks) {
            when (block.string("type")) {
                "text" -> {
                    val text = block.string("text")
                    if (text.isBlank()) continue
                    val itemId = itemId(textIndex) ?: "${bodyId ?: "message"}:t${generatedText++}"
                    val completed = EngineEvent.MessageCompleted(text, threadId, turn.turnId, itemId, "commentary")
                    lastText = completed
                    signals += Signal.Emit(completed)
                }
                "tool_use" -> {
                    lastText = null
                    val name = block.string("name")
                    val id = block.string("id")
                    if (name.startsWith("mcp__") || id.isBlank()) continue
                    val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                    val (title, activity) = builtinTitle(name)
                    val detail = builtinDetail(input)
                    builtinTools[id] = title to detail
                    signals += Signal.Emit(EngineEvent.ItemActivity(id, title, detail, "streaming", threadId, turn.turnId))
                    signals += Signal.Emit(EngineEvent.Activity(activity, threadId, turn.turnId))
                }
            }
        }
        return signals
    }

    private fun builtinTitle(name: String): Pair<String, String> = when (name) {
        "Read" -> "Read file" to "Reading session files"
        "Edit" -> "Edit file" to "Updating session files"
        "Write" -> "Write file" to "Updating session files"
        "Glob" -> "Find files" to "Looking through session files"
        "Grep" -> "Search files" to "Looking through session files"
        "Skill" -> "Load skill" to "Loading a skill"
        // Only a computer's Claude Code has a shell.
        "Bash", "PowerShell" -> "Run command" to "Running a command"
        else -> "Tool: $name" to "Working"
    }

    private fun builtinDetail(input: JsonObject): String {
        val path = input.string("file_path").ifBlank { input.string("path") }
        if (path.isNotBlank()) return relative(path)
        return input.string("pattern").ifBlank { input.string("skill") }.ifBlank { input.string("command").take(MAX_COMMAND_SHOWN) }
    }

    private fun relative(path: String): String {
        val root = workspace?.absolutePath?.trimEnd('/', '\\') ?: return path
        return if (path.startsWith("$root/") || path.startsWith("$root\\")) path.substring(root.length + 1) else path
    }

    private fun rateLimit(message: JsonObject): List<Signal> {
        val info = message["rate_limit_info"] as? JsonObject ?: return emptyList()
        cycleTurn()?.let { turn -> ClaudeProtocol.rejectionMessage(info, zone, clock())?.let { turn.rejection = it } }
        val limits = ClaudeProtocol.parseRateLimits(info)
        return if (limits.isEmpty()) emptyList() else listOf(Signal.Emit(EngineEvent.UsageChanged(null, limits = limits)))
    }

    private fun result(message: JsonObject): List<Signal> {
        busy = false
        val turn = cycleTurn()
        owner = null
        val signals = mutableListOf<Signal>()
        ClaudeProtocol.parseUsage(message)?.let { signals += Signal.Emit(EngineEvent.UsageChanged(threadId, usage = it)) }
        if (turn == null) return signals
        val failed = (message["is_error"] as? JsonPrimitive)?.booleanOrNull == true || message.string("subtype") != "success"
        val status = when {
            turn.interruptRequested -> "interrupted"
            failed -> "failed"
            else -> "completed"
        }
        val error = if (status == "failed") errorOf(message, turn) else null
        return signals + end(turn, status, error)
    }

    private fun errorOf(message: JsonObject, turn: Turn): String {
        turn.rejection?.let { return it }
        val text = message.string("result").trim().ifBlank { null }
            ?: turn.syntheticError
            ?: (message["errors"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("[ede_diagnostic]") }
                .joinToString("; ").ifBlank { null }
            ?: (message["api_error_status"] as? JsonPrimitive)?.contentOrNull?.let { "The Claude service answered with HTTP $it" }
            ?: "Claude could not finish (${message.string("subtype").ifBlank { "unknown error" }})"
        return SecretRedactor.redactUiText(text).take(2_000)
    }

    private fun retire(turn: Turn) {
        retired += turn.frames
        while (retired.size > MAX_RETIRED) retired.remove(retired.first())
        if (active === turn) active = null
        if (owner === turn) owner = null
        lastText = null
        builtinTools.clear()
    }

    private companion object {
        const val MAX_RETIRED = 256
        const val MAX_COMMAND_SHOWN = 400
    }
}
