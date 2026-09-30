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
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.DeviceCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ClaudeProtocolTest {
    private fun fixture(name: String): List<JsonObject> =
        javaClass.getResource("/claude/$name")!!.readText().lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

    @Test fun chatArgsStartANewSessionWithTheConfinedToolSet() {
        val args = ClaudeProtocol.chatArgs("11111111-2222-4333-8444-555555555555", resume = false, "sonnet", "high", "/p/mcp.json", "/p/prompt.md")
        fun after(flag: String) = args[args.indexOf(flag) + 1]
        assertEquals("-p", args.first())
        assertEquals("stream-json", after("--input-format"))
        assertEquals("stream-json", after("--output-format"))
        assertTrue(args.containsAll(listOf("--verbose", "--include-partial-messages", "--replay-user-messages", "--strict-mcp-config")))
        assertEquals("11111111-2222-4333-8444-555555555555", after("--session-id"))
        assertFalse("--resume" in args)
        assertEquals("sonnet", after("--model"))
        assertEquals("high", after("--effort"))
        assertEquals("Read,Edit,Write,Glob,Skill", after("--tools"))
        assertEquals("mcp__mike__*,Read(./**),Edit(./**),Skill", after("--allowedTools"))
        assertEquals("dontAsk", after("--permission-mode"))
        assertEquals("user", after("--setting-sources"))
        assertEquals("/p/mcp.json", after("--mcp-config"))
        assertEquals("/p/prompt.md", after("--system-prompt-file"))
        // Compliance: never --bare (it ignores subscriptions), and no shell or search tools.
        assertFalse(args.any { it == "--bare" || it.contains("Bash") || it.contains("Grep") })
    }

    @Test fun probeArgsLoadNothingAndKeepNoTranscript() {
        val args = ClaudeProtocol.probeArgs()
        assertEquals("", args[args.indexOf("--tools") + 1])
        assertEquals("", args[args.indexOf("--setting-sources") + 1])
        assertTrue(args.containsAll(listOf("--strict-mcp-config", "--no-session-persistence")))
        assertFalse(args.any { it == "--bare" || it == "--resume" || it == "--session-id" })
    }

    @Test fun chatArgsResumeAKnownSessionAndOmitEffortWhenUnset() {
        val args = ClaudeProtocol.chatArgs("11111111-2222-4333-8444-555555555555", resume = true, "opus", null, "m", "s")
        assertEquals("11111111-2222-4333-8444-555555555555", args[args.indexOf("--resume") + 1])
        assertFalse("--session-id" in args)
        assertFalse("--effort" in args)
    }

    @Test fun chatEnvironmentTurnsOffToolSearchAndCarriesNoCredentials() {
        assertEquals("false", ClaudeProtocol.CHAT_ENV["ENABLE_TOOL_SEARCH"])
        assertEquals("600000", ClaudeProtocol.CHAT_ENV["MCP_TOOL_TIMEOUT"])
        assertEquals("30000", ClaudeProtocol.CHAT_ENV["MCP_TIMEOUT"])
        assertTrue(ClaudeProtocol.CHAT_ENV.keys.none { it.startsWith("ANTHROPIC_") || it.startsWith("CLAUDE_CODE_") })
    }

    @Test fun modelsAndEffortsAreNormalised() {
        assertEquals("sonnet", ClaudeProtocol.normalizeModel(null))
        assertEquals("sonnet", ClaudeProtocol.normalizeModel("gpt-5.5-codex"))
        assertEquals("sonnet", ClaudeProtocol.normalizeModel("--dangerously-skip-permissions"))
        assertEquals("opus", ClaudeProtocol.normalizeModel("opus"))
        assertEquals("sonnet[1m]", ClaudeProtocol.normalizeModel("sonnet[1m]"))
        assertEquals("claude-opus-4-8", ClaudeProtocol.normalizeModel("claude-opus-4-8"))
        assertEquals("custom", ClaudeProtocol.normalizeModel("custom", known = listOf("custom")))
        assertEquals("xhigh", ClaudeProtocol.normalizeEffort("XHigh"))
        assertNull(ClaudeProtocol.normalizeEffort("minimal"))
        assertNull(ClaudeProtocol.normalizeEffort(null))
    }

    @Test fun turnContentPutsContextImagesPlanThenPromptWithSkillPrefix() {
        val dir = Files.createTempDirectory("claude-proto").toFile()
        val image = File(dir, "shot.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val content = ClaudeProtocol.turnContent(
            prompt = "open the settings",
            images = listOf(image),
            skillName = "device-automation",
            capabilities = DeviceCapabilities(adbStatus = AdbStatus(ConnectionPhase.CONNECTED), ready = setOf("tap")),
            planMode = true,
            now = ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneId.of("UTC")),
        )
        assertEquals(4, content.size)
        val context = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(context.startsWith("[Trusted Android Agent runtime context]"))
        assertTrue(context.contains("Device tools you can call now: tap"))
        assertTrue(context.contains("Wednesday, 30 September 2026, 12:00"))
        val picture = content[1].jsonObject
        assertEquals("image", picture["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", picture["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("AQID", picture["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertTrue(content[2].jsonObject["text"]!!.jsonPrimitive.content.startsWith("Plan mode"))
        assertEquals("/device-automation open the settings", content[3].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun anUnsafeSkillNameIsNotTurnedIntoACommand() {
        val content = ClaudeProtocol.turnContent("hi", emptyList(), "../../x y", null, planMode = false)
        assertEquals(1, content.size)
        assertEquals("hi", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun aMissingOrHugePictureBecomesANote() {
        val block = ClaudeProtocol.imageBlock(File("/does/not/exist.png"))
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
    }

    @Test fun framesMatchTheStreamJsonShapes() {
        val frame = ClaudeProtocol.userFrame("u-1", kotlinx.serialization.json.JsonArray(listOf(ClaudeProtocol.textBlock("go"))), "next")
        assertEquals("user", frame["type"]!!.jsonPrimitive.content)
        assertEquals("u-1", frame["uuid"]!!.jsonPrimitive.content)
        assertEquals("next", frame["priority"]!!.jsonPrimitive.content)
        assertEquals("user", frame["message"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("go", frame["message"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val interrupt = ClaudeProtocol.interruptRequest("r-1")
        assertEquals("control_request", interrupt["type"]!!.jsonPrimitive.content)
        assertEquals("r-1", interrupt["request_id"]!!.jsonPrimitive.content)
        assertEquals("interrupt", interrupt["request"]!!.jsonObject["subtype"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), interrupt["request"]!!.jsonObject["cancel_queued"])
    }

    @Test fun cliPermissionPromptsAreDeniedAndOtherRequestsRefused() {
        val deny = ClaudeProtocol.answerControlRequest("c-1", Json.parseToJsonElement("""{"subtype":"can_use_tool","tool_name":"Bash"}""").jsonObject)
        val body = deny["response"]!!.jsonObject
        assertEquals("success", body["subtype"]!!.jsonPrimitive.content)
        assertEquals("deny", body["response"]!!.jsonObject["behavior"]!!.jsonPrimitive.content)
        val other = ClaudeProtocol.answerControlRequest("c-2", Json.parseToJsonElement("""{"subtype":"hook_callback"}""").jsonObject)
        assertEquals("error", other["response"]!!.jsonObject["subtype"]!!.jsonPrimitive.content)
    }

    @Test fun authStatusIsReadFromTheCommandOnly() {
        val signedIn = ClaudeProtocol.parseAuthStatus(
            """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"user@example.com","subscriptionType":"max"}""",
        )
        assertTrue(signedIn.signedIn)
        assertEquals("user@example.com", signedIn.label)
        val out = ClaudeProtocol.parseAuthStatus("""{"loggedIn":false,"authMethod":"none"}""")
        assertFalse(out.signedIn)
        assertEquals("Sign in to Claude", out.label)
        assertFalse(ClaudeProtocol.parseAuthStatus("not json").signedIn)
    }

    @Test fun theLoginUrlIsReadOnlyOnceTheLineIsComplete() {
        val url = "https://claude.ai/oauth/authorize?code=true&client_id=9d1c&response_type=code&state=abc"
        assertNull(ClaudeProtocol.parseLoginUrl("If the browser didn't open, visit: ${url.take(40)}"))
        assertEquals(url, ClaudeProtocol.parseLoginUrl("Opening browser to sign in…\nIf the browser didn't open, visit: $url\nPaste code here if prompted > "))
        assertEquals(url, ClaudeProtocol.parseLoginUrl("visit: <$url>\n"))
        assertNull(ClaudeProtocol.parseLoginUrl("no link here\n"))
    }

    @Test fun theInitializeModelListIsParsedFromARecordedResponse() {
        val response = fixture("initialize-response.jsonl").single()["response"]!!.jsonObject["response"]!!.jsonObject
        val models = ClaudeProtocol.parseModels(response)
        assertEquals("sonnet", models.first().id)
        assertFalse(models.any { it.id == "default" })
        val opus = models.first { it.id == "opus" }
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), opus.reasoningEfforts.map { it.value })
        assertTrue(models.first { it.id == "haiku" }.reasoningEfforts.isEmpty())
    }

    @Test fun recordedModelsKeepTheirValueAndShowTheCliNameAndDescription() {
        val response = fixture("initialize-response.jsonl").single()["response"]!!.jsonObject["response"]!!.jsonObject
        val models = ClaudeProtocol.parseModels(response).associateBy { it.id }
        assertEquals("Sonnet 5.5", models.getValue("sonnet").displayName)
        assertEquals("Opus 5.5", models.getValue("opus").displayName)
        assertEquals("Haiku 4.5", models.getValue("haiku").displayName)
        assertEquals("Efficient for routine tasks", models.getValue("sonnet").description)
        val fable = models.getValue("claude-fable-5-1")
        assertEquals("Fable 5.1", fable.displayName)
        assertTrue(fable.description, fable.description.startsWith(ClaudeProtocol.FABLE_NOTE))
        assertFalse(models.getValue("opus").description.contains(ClaudeProtocol.FABLE_NOTE))
    }

    @Test fun modelsWithoutACliNameGetThePinnedName() {
        val response = Json.parseToJsonElement(
            """{"models":[{"value":"sonnet"},{"value":"opus","displayName":"opus"},{"value":"claude-fable-5-1"},{"value":"haiku"},{"value":"claude-new-9"}]}""",
        ).jsonObject
        val models = ClaudeProtocol.parseModels(response).associateBy { it.id }
        assertEquals("Sonnet 5.5", models.getValue("sonnet").displayName)
        assertEquals("Opus 5.5", models.getValue("opus").displayName)
        assertEquals("Fable 5.1", models.getValue("claude-fable-5-1").displayName)
        assertEquals(ClaudeProtocol.FABLE_NOTE, models.getValue("claude-fable-5-1").description)
        assertEquals("Haiku 4.5", models.getValue("haiku").displayName)
        assertEquals("claude-new-9", models.getValue("claude-new-9").displayName)
    }

    @Test fun fallbackModelsAreTheThreeAliases() {
        assertEquals(listOf("sonnet", "opus", "haiku"), ClaudeProtocol.FALLBACK_MODELS.map { it.id })
        assertEquals(listOf("Sonnet 5.5", "Opus 5.5", "Haiku 4.5"), ClaudeProtocol.FALLBACK_MODELS.map { it.displayName })
        assertEquals(ClaudeProtocol.EFFORTS, ClaudeProtocol.FALLBACK_MODELS.first().reasoningEfforts.map { it.value })
    }

    @Test fun rateLimitsAreNamedFiveHourAndWeekly() {
        val event = fixture("turn-text.jsonl").first { it["type"]!!.jsonPrimitive.content == "rate_limit_event" }
        val limits = ClaudeProtocol.parseRateLimits(event["rate_limit_info"]!!.jsonObject)
        assertEquals(listOf("5-hour", "weekly"), limits.map { it.name })
        assertEquals(4.0, limits[0].usedPercent!!, 0.001)
        assertEquals(32.0, limits[1].usedPercent!!, 0.001)
        assertEquals(1790785800L, limits[0].resetsAt)
        assertEquals(300L, limits[0].windowMinutes)
        assertEquals(10_080L, limits[1].windowMinutes)
    }

    @Test fun theUsageRequestIsTheSdkGetUsageFrame() {
        val frame = ClaudeProtocol.usageRequest("req-1")
        assertEquals(
            """{"type":"control_request","request_id":"req-1","request":{"subtype":"get_usage","skip_behaviors":true}}""",
            frame.toString(),
        )
    }

    @Test fun theUsageReplyGivesTheSameNamesAsTheRateLimitEvent() {
        val limits = ClaudeProtocol.parseUsageReply(GET_USAGE_REPLY)
        assertEquals(listOf("5-hour", "weekly"), limits.map { it.name })
        // A percent already: 0.5 stays 0.5 %, not 50 %.
        assertEquals(12.5, limits[0].usedPercent!!, 0.001)
        assertEquals(0.5, limits[1].usedPercent!!, 0.001)
        assertEquals(1790785800L, limits[0].resetsAt)
        assertEquals(1790827200L, limits[1].resetsAt)
        assertEquals(300L, limits[0].windowMinutes)
        assertEquals(10_080L, limits[1].windowMinutes)
    }

    @Test fun aUsageReplyWithoutPlanLimitsGivesNone() {
        fun reply(text: String) = ClaudeProtocol.parseUsageReply(Json.parseToJsonElement(text).jsonObject)
        assertTrue(reply("""{"rate_limits_available":false,"rate_limits":null}""").isEmpty())
        assertTrue(reply("""{"rate_limits_available":false,"rate_limits":{"five_hour":{"utilization":5,"resets_at":null}}}""").isEmpty())
        assertTrue(reply("""{"rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":null,"resets_at":null}}}""").isEmpty())
        assertTrue(reply("""{}""").isEmpty())
        // A reset time that is not a date is dropped, the percent kept.
        val odd = reply("""{"rate_limits":{"five_hour":{"utilization":250,"resets_at":"soon"}}}""").single()
        assertEquals(100.0, odd.usedPercent!!, 0.001)
        assertNull(odd.resetsAt)
    }

    @Test fun aRejectedLimitGivesTheResetTime() {
        val info = Json.parseToJsonElement(
            """{"status":"rejected","resetsAt":1790785800,"rateLimitType":"five_hour"}""",
        ).jsonObject
        val message = ClaudeProtocol.rejectionMessage(info, ZoneId.of("UTC"), Instant.ofEpochSecond(1790785800L - 3600))
        assertEquals("You have reached your Claude 5-hour usage limit. It resets at 16:30.", message)
        assertEquals(100.0, ClaudeProtocol.parseRateLimits(info).single().usedPercent!!, 0.001)
        assertNull(ClaudeProtocol.rejectionMessage(Json.parseToJsonElement("""{"status":"allowed"}""").jsonObject))
    }

    @Test fun resultUsageCountsCacheAsInput() {
        val result = fixture("turn-text.jsonl").first { it["type"]!!.jsonPrimitive.content == "result" }
        val usage = ClaudeProtocol.parseUsage(result)!!
        assertEquals(10L + 6925L, usage.input)
        assertEquals(43L, usage.output)
        assertEquals(0L, usage.cachedInput)
        assertEquals(10L + 6925L + 43L, usage.total)
        assertEquals(200_000L, usage.contextWindow)
    }

    @Test fun skillFrontMatterIsParsedIncludingFoldedDescriptions() {
        val file = File("/home/.claude/skills/device-automation/SKILL.md")
        val skill = ClaudeProtocol.parseSkill(
            file,
            "---\nname: device-automation\ndescription: >\n  Operate the phone\n  step by step.\nlicense: MIT\n---\n\n# Body\n",
        )!!
        assertEquals("device-automation", skill.name)
        assertEquals("Operate the phone step by step.", skill.description)
        assertEquals("user", skill.scope)
        val quoted = ClaudeProtocol.parseSkill(File("/s/x/SKILL.md"), "---\nname: \"quick-actions\"\ndescription: 'Fast things'\n---\n")!!
        assertEquals("quick-actions", quoted.name)
        assertEquals("Fast things", quoted.description)
        assertNull(ClaudeProtocol.parseSkill(file, "no front matter"))
    }

    @Test fun sessionIdsMustBeUuids() {
        assertTrue(ClaudeProtocol.isSessionId("e2b0aead-c4ad-4805-82f8-7a335b3a5f54"))
        assertFalse(ClaudeProtocol.isSessionId("thread-1"))
        assertFalse(ClaudeProtocol.isSessionId("../../etc"))
        assertFalse(ClaudeProtocol.isSessionId(null))
    }

    @Test fun theSystemPromptIsEngineNeutralAndCarriesAgentsMd() {
        val prompt = ClaudeInstructions.systemPrompt("# Manual\nUse act_plan.")
        assertTrue(prompt.contains("Your name is Mike"))
        assertTrue(prompt.contains("[Trusted Android Agent runtime context]"))
        assertTrue(prompt.endsWith("# Manual\nUse act_plan."))
        assertFalse(prompt.contains("OpenAI"))
        assertFalse(prompt.contains("Codex"))
        val huge = ClaudeInstructions.systemPrompt("x".repeat(ClaudeInstructions.MAX_AGENTS_MD_CHARS + 10))
        assertTrue(huge.endsWith("[AGENTS.md was cut here.]"))
        assertEquals(ClaudeInstructions.AGENT_INSTRUCTIONS, ClaudeInstructions.systemPrompt(null))
    }
}
