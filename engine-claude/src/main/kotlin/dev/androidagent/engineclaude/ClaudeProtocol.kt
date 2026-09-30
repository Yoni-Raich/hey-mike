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

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AgentModel
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.ReasoningEffortOption
import dev.androidagent.core.TokenUsage
import dev.androidagent.core.UsageLimit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * The wire shapes of `claude -p` in stream-json mode (Claude Code 2.1.285).
 *
 * Everything here is a pure function so the codec can be tested against
 * recorded lines without a process. Shapes were recorded from a real CLI:
 * `system/init`, `stream_event`, one-block `assistant` frames, `user` replays
 * carrying the frame `uuid`, `command_lifecycle`, `rate_limit_event` with
 * `unifiedWindows`, `result`, and `control_request` / `control_response`.
 */
internal object ClaudeProtocol {
    /** The only Claude Code version the app installs. `system/init` is checked against it. */
    const val PINNED_VERSION = "2.1.285"

    const val MCP_SERVER_NAME = "mike"
    const val MCP_TOOL_PREFIX = "mcp__${MCP_SERVER_NAME}__"

    /**
     * Built-in Claude Code tools a chat may use. File tools are confined to
     * the chat workspace by [ALLOWED_TOOLS]; `Skill` loads the on-device
     * skills. `Grep` and `Bash` stay off until they are proven on a phone.
     */
    val BUILTIN_TOOLS: List<String> = listOf("Read", "Edit", "Write", "Glob", "Skill")

    /**
     * Permission rules for `--permission-mode dontAsk`: anything not listed
     * is denied without a prompt. The `./` paths are relative to the working
     * directory, which is the chat workspace; `Edit` rules also cover `Write`.
     * Verified on a desktop CLI: a read or write outside the workspace is
     * denied, inside it succeeds.
     */
    val ALLOWED_TOOLS: List<String> = listOf("$MCP_TOOL_PREFIX*", "Read(./**)", "Edit(./**)", "Skill")

    /**
     * How a steer joins a running turn. `next` delivers it at the next model
     * call inside the same turn, so one `result` still ends the turn; a
     * desktop CLI honoured it. Kept in one place so a phone finding can flip
     * it to `now`.
     */
    const val STEER_PRIORITY = "next"

    const val DEFAULT_MODEL = "sonnet"
    val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

    /** Environment for chat processes, on top of what the host sets. */
    val CHAT_ENV: Map<String, String> = mapOf(
        "ENABLE_TOOL_SEARCH" to "false",
        "MCP_TOOL_TIMEOUT" to "600000",
        "MCP_TIMEOUT" to "30000",
    )

    /** Arguments for one chat process. Never `--bare`: it ignores subscriptions. */
    fun chatArgs(
        sessionId: String,
        resume: Boolean,
        model: String,
        effort: String?,
        mcpConfig: String,
        systemPromptFile: String,
    ): List<String> = buildList {
        add("-p")
        add("--input-format"); add("stream-json")
        add("--output-format"); add("stream-json")
        add("--verbose")
        add("--include-partial-messages")
        add("--replay-user-messages")
        if (resume) { add("--resume"); add(sessionId) } else { add("--session-id"); add(sessionId) }
        add("--model"); add(model)
        if (effort != null) { add("--effort"); add(effort) }
        add("--tools"); add(BUILTIN_TOOLS.joinToString(","))
        add("--strict-mcp-config")
        add("--mcp-config"); add(mcpConfig)
        add("--allowedTools"); add(ALLOWED_TOOLS.joinToString(","))
        add("--permission-mode"); add("dontAsk")
        // Only the app-owned user settings under the private HOME: that is
        // where the on-device skills are installed. Project and local
        // settings never apply.
        add("--setting-sources"); add("user")
        add("--system-prompt-file"); add(systemPromptFile)
    }

    /** A process that only answers `initialize`, to read the model list. */
    fun probeArgs(): List<String> = listOf(
        "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
        "--tools", "", "--strict-mcp-config", "--setting-sources", "",
    )

    val AUTH_STATUS_ARGS = listOf("auth", "status", "--json")
    val AUTH_LOGIN_ARGS = listOf("auth", "login", "--claudeai")
    val AUTH_LOGOUT_ARGS = listOf("auth", "logout")

    private val SESSION_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Claude takes only UUID session ids; anything else is not a Claude chat. */
    fun isSessionId(value: String?): Boolean = value != null && SESSION_ID.matches(value)

    private val MODEL_SHAPE = Regex("^[A-Za-z0-9][A-Za-z0-9._\\-\\[\\]]{0,79}$")
    private val MODEL_ALIASES = Regex("^(default|sonnet|opus|haiku|fable|opusplan)(\\[1m])?$")

    /**
     * The model to pass to `--model`. A value that is not a Claude model,
     * such as a Codex model id left over in the composer, falls back to the
     * default instead of failing the turn.
     */
    fun normalizeModel(model: String?, known: Collection<String> = emptyList()): String {
        val value = model?.trim().orEmpty()
        if (!MODEL_SHAPE.matches(value)) return DEFAULT_MODEL
        return if (value in known || MODEL_ALIASES.matches(value) || value.startsWith("claude-")) value else DEFAULT_MODEL
    }

    /** `--effort` value, or null to keep the model default. Codex-only levels are dropped. */
    fun normalizeEffort(effort: String?): String? = effort?.trim()?.lowercase()?.takeIf { it in EFFORTS }

    // ---- frames the app writes -------------------------------------------------

    /** One stream-json user message. [uuid] comes back in the replay and in `command_lifecycle`. */
    fun userFrame(uuid: String, content: JsonArray, priority: String? = null): JsonObject = buildJsonObject {
        put("type", "user")
        put("uuid", uuid)
        put("parent_tool_use_id", JsonNull)
        put("message", buildJsonObject {
            put("role", "user")
            put("content", content)
        })
        if (priority != null) put("priority", priority)
    }

    fun controlRequest(requestId: String, subtype: String, extra: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        put("request", JsonObject(mapOf("subtype" to JsonPrimitive(subtype)) + extra))
    }

    fun interruptRequest(requestId: String): JsonObject =
        controlRequest(requestId, "interrupt", buildJsonObject { put("cancel_queued", true) })

    fun controlSuccess(requestId: String, response: JsonObject): JsonObject = buildJsonObject {
        put("type", "control_response")
        put("response", buildJsonObject {
            put("subtype", "success")
            put("request_id", requestId)
            put("response", response)
        })
    }

    fun controlError(requestId: String, error: String): JsonObject = buildJsonObject {
        put("type", "control_response")
        put("response", buildJsonObject {
            put("subtype", "error")
            put("request_id", requestId)
            put("error", error)
        })
    }

    /** Reply to a CLI-side control request. Permission prompts are denied; nothing else is served. */
    fun answerControlRequest(requestId: String, request: JsonObject): JsonObject =
        if (request.string("subtype") == "can_use_tool") {
            controlSuccess(requestId, buildJsonObject {
                put("behavior", "deny")
                put("message", "Hey Mike does not allow this tool here.")
            })
        } else {
            controlError(requestId, "Not supported by Hey Mike")
        }

    /** Largest picture sent inline. The API refuses bigger ones. */
    const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

    private val SKILL_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_:.\\-]{0,63}$")

    const val PLAN_INSTRUCTION =
        "Plan mode: before you act, reply with a short numbered plan and ask the user to agree to it. " +
            "Do not call any tool that changes something on the phone or in the files until the user agrees."

    /**
     * The content blocks of one turn, in order: the trusted runtime
     * snapshot, the pictures, the plan-mode instruction, then the prompt. A
     * skill becomes a `/name` prefix, which the CLI expands; the other blocks
     * still reach the model.
     */
    fun turnContent(
        prompt: String,
        images: List<File>,
        skillName: String?,
        capabilities: DeviceCapabilities?,
        planMode: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): JsonArray = buildJsonArray {
        capabilities?.let { add(textBlock(ClaudeInstructions.deviceRuntimeContext(it, now))) }
        images.forEach { file -> add(imageBlock(file)) }
        if (planMode) add(textBlock(PLAN_INSTRUCTION))
        val skill = skillName?.trim()?.takeIf { SKILL_NAME.matches(it) }
        add(textBlock(if (skill != null) "/$skill $prompt".trimEnd() else prompt))
    }

    fun textBlock(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

    internal fun imageBlock(file: File): JsonObject {
        if (!file.isFile || file.length() > MAX_IMAGE_BYTES) {
            return textBlock("[The attached picture ${file.name} could not be sent: it is missing or larger than 5 MB.]")
        }
        return buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", mediaType(file))
                put("data", Base64.getEncoder().encodeToString(file.readBytes()))
            })
        }
    }

    private fun mediaType(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    // ---- things the app reads --------------------------------------------------

    /**
     * `claude auth status` JSON. Only the documented fields are read; the
     * app never opens the files under `CLAUDE_CONFIG_DIR`.
     */
    fun parseAuthStatus(stdout: String): AccountStatus {
        val value = firstJsonObject(stdout) ?: return AccountStatus(false, SIGN_IN_LABEL)
        val signedIn = (value["loggedIn"] as? JsonPrimitive)?.booleanOrNull == true
        if (!signedIn) return AccountStatus(false, SIGN_IN_LABEL)
        val label = value.string("email").trim().ifBlank { "Signed in to Claude" }
        return AccountStatus(true, label)
    }

    const val SIGN_IN_LABEL = "Sign in to Claude"

    private val LOGIN_URL = Regex("""https://[^\s<>"']+""")

    /**
     * The sign-in link from `claude auth login` output. A link counts only
     * once the whitespace after it has arrived, so a line read in pieces is
     * never returned cut short.
     */
    fun parseLoginUrl(output: String): String? {
        for (match in LOGIN_URL.findAll(output)) {
            val end = match.range.last + 1
            if (end >= output.length) return null
            val url = match.value.trimEnd('.', ',', ')', ';')
            if (url.length > "https://".length) return url
        }
        return null
    }

    /** Models from the `initialize` control response. The account-default entry is dropped; `sonnet` leads. */
    fun parseModels(initialize: JsonObject): List<AgentModel> {
        val models = (initialize["models"] as? JsonArray).orEmpty().mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val id = model.string("value").trim()
            if (id.isBlank() || id == "default" || !MODEL_SHAPE.matches(id)) return@mapNotNull null
            val supportsEffort = (model["supportsEffort"] as? JsonPrimitive)?.booleanOrNull == true
            val efforts = if (!supportsEffort) emptyList() else (model["supportedEffortLevels"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .map { ReasoningEffortOption(it) }
            AgentModel(
                id = id,
                displayName = model.string("displayName").trim().ifBlank { id },
                reasoningEfforts = efforts,
            )
        }.distinctBy { it.id }
        return models.sortedBy { if (it.id == DEFAULT_MODEL) 0 else 1 }
    }

    /** Used until a process has answered `initialize`. */
    val FALLBACK_MODELS: List<AgentModel> = listOf(
        AgentModel("sonnet", "Sonnet", EFFORTS.map { ReasoningEffortOption(it) }),
        AgentModel("opus", "Opus", EFFORTS.map { ReasoningEffortOption(it) }),
        AgentModel("haiku", "Haiku"),
    )

    private val WINDOW_NAMES = mapOf(
        "five_hour" to ("5-hour" to 300L),
        "seven_day" to ("weekly" to 10_080L),
        "seven_day_opus" to ("weekly · Opus" to 10_080L),
        "seven_day_sonnet" to ("weekly · Sonnet" to 10_080L),
    )

    private fun windowName(key: String): Pair<String, Long?> =
        WINDOW_NAMES[key] ?: (key.replace('_', ' ') to null)

    /** `rate_limit_event.rate_limit_info` as usage limits named `5-hour` and `weekly`. */
    fun parseRateLimits(info: JsonObject): List<UsageLimit> {
        val windows = info["unifiedWindows"] as? JsonObject
        if (!windows.isNullOrEmpty()) {
            return windows.entries.mapNotNull { (key, value) ->
                val window = value as? JsonObject ?: return@mapNotNull null
                val (name, minutes) = windowName(key)
                UsageLimit(name, percent(window["utilization"]), (window["resetsAt"] as? JsonPrimitive)?.longOrNull, minutes)
            }
        }
        val type = info.string("rateLimitType").ifBlank { return emptyList() }
        if (type == "overage") return emptyList()
        val (name, minutes) = windowName(type)
        val used = percent(info["utilization"]) ?: if (info.string("status") == "rejected") 100.0 else null
        return listOf(UsageLimit(name, used, (info["resetsAt"] as? JsonPrimitive)?.longOrNull, minutes))
    }

    /** Utilization arrives as a fraction; a value above 1 is read as a percent already. */
    private fun percent(value: JsonElement?): Double? {
        val raw = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        return (if (raw <= 1.0) raw * 100 else raw).coerceIn(0.0, 100.0)
    }

    /** The turn error for a `rejected` limit, with its reset time. Null when the limit is not reached. */
    fun rejectionMessage(info: JsonObject, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String? {
        if (info.string("status") != "rejected") return null
        val type = info.string("rateLimitType")
        if (type == "overage") return null
        val name = windowName(type.ifBlank { "five_hour" }).first
        val resetsAt = (info["resetsAt"] as? JsonPrimitive)?.longOrNull
            ?: ((info["unifiedWindows"] as? JsonObject)?.get(type) as? JsonObject)?.let { (it["resetsAt"] as? JsonPrimitive)?.longOrNull }
        val base = "You have reached your Claude $name usage limit."
        if (resetsAt == null || resetsAt <= 0) return base
        val reset = Instant.ofEpochSecond(resetsAt).atZone(zone)
        val pattern = if (resetsAt - now.epochSecond < 24 * 3600) "HH:mm" else "EEE d MMM, HH:mm"
        return "$base It resets at ${reset.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))}."
    }

    /** Token usage from a `result` frame. Input counts cache reads and writes, as the context does. */
    fun parseUsage(result: JsonObject): TokenUsage? {
        val usage = result["usage"] as? JsonObject ?: return null
        fun count(key: String) = (usage[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0L
        val cached = count("cache_read_input_tokens")
        val input = count("input_tokens") + count("cache_creation_input_tokens") + cached
        val output = count("output_tokens")
        val window = (result["modelUsage"] as? JsonObject)?.values
            ?.mapNotNull { ((it as? JsonObject)?.get("contextWindow") as? JsonPrimitive)?.longOrNull }
            ?.maxOrNull()
        return TokenUsage(input + output, input, output, cached, window)
    }

    /**
     * One skill from its `SKILL.md` front matter. Only `name` and
     * `description` are read, including YAML folded (`>`) and literal (`|`)
     * descriptions.
     */
    fun parseSkill(file: File, text: String): AgentSkill? {
        val lines = text.replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) ?: return null
        val front = lines.subList(1, end)
        val fields = mutableMapOf<String, String>()
        var index = 0
        while (index < front.size) {
            val line = front[index]
            val match = Regex("^([A-Za-z_-]+):\\s*(.*)$").find(line)
            if (match == null || line.startsWith(" ")) { index++; continue }
            val key = match.groupValues[1]
            var value = match.groupValues[2].trim()
            if (value == ">" || value == "|" || value == ">-" || value == "|-" || value.isEmpty()) {
                val block = mutableListOf<String>()
                while (index + 1 < front.size && (front[index + 1].startsWith(" ") || front[index + 1].isBlank())) {
                    block += front[++index].trim()
                }
                value = if (value.startsWith("|")) block.joinToString("\n").trim() else block.filter { it.isNotEmpty() }.joinToString(" ")
            }
            fields[key] = unquote(value)
            index++
        }
        val name = fields["name"]?.trim()?.takeIf { SKILL_NAME.matches(it) } ?: file.parentFile?.name?.takeIf { SKILL_NAME.matches(it) } ?: return null
        return AgentSkill(
            name = name,
            description = fields["description"].orEmpty().trim(),
            path = file.absolutePath,
            scope = "user",
        )
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\'')))) {
            value.substring(1, value.length - 1)
        } else value

    private fun firstJsonObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    internal fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
