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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `/` offers in a Claude chat. The fixture is a trimmed `initialize`
 * response recorded from Claude Code 2.1.295 (the Agent SDK's
 * `supportedCommands()`); the command runs are recorded from the same CLI in
 * stream-json mode.
 */
class ClaudeSlashCatalogTest {
    private val thread = "e2b0aead-c4ad-4805-82f8-7a335b3a5f54"

    private val recorded: JsonObject by lazy {
        val text = javaClass.getResource("/claude/initialize-commands-2.1.295.json")!!.readText().trim()
        Json.parseToJsonElement(text).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject
    }

    @Test fun builtInCommandsAreTheCliOwnWithItsHintsAndAliases() {
        val commands = ClaudeProtocol.parseSlashCatalog(recorded).commands.associateBy { it.name }
        assertEquals("", commands.getValue("context").argumentHint)
        assertEquals(listOf("cost", "stats"), commands.getValue("usage").aliases)
        assertEquals("<optional custom summarization instructions>", commands.getValue("compact").argumentHint)
        assertTrue("a bundled skill is listed by the CLI as built in", "verify" in commands)
    }

    @Test fun commandsThatCannotWorkInAChatOnAPhoneAreLeftOut() {
        val names = ClaudeProtocol.parseSlashCatalog(recorded).commands.map { it.name }
        // The CLI's own terminal-only commands, settings and tool-server controls, one that opens a browser, and an internal one.
        listOf("doctor", "config", "mcp", "extra-usage", "__remote-workflow").forEach { assertFalse("$it is hidden", it in names) }
    }

    @Test fun skillsAreTheEntriesTheCliDoesNotCallBuiltInWithoutTheirSourceTag() {
        val skills = ClaudeProtocol.parseSlashCatalog(recorded).skills
        assertEquals(listOf("docx", "figma"), skills.map { it.name })
        assertFalse(skills.any { it.description.endsWith("(user)") || it.description.endsWith("(claude.ai sync)") })
        assertEquals(skills.size, skills.map { it.path }.toSet().size)
    }

    @Test fun anAnswerWithoutACommandListIsEmpty() {
        val catalog = ClaudeProtocol.parseSlashCatalog(JsonObject(emptyMap()))
        assertTrue(catalog.commands.isEmpty() && catalog.skills.isEmpty())
    }

    @Test fun aSkillPickedInTheComposerIsSentOnceAsASlashCommand() {
        val content = ClaudeProtocol.turnContent("\$device-automation open settings", emptyList(), "device-automation", null, planMode = false)
        assertEquals("/device-automation open settings", (content.last().jsonObject["text"] as kotlinx.serialization.json.JsonPrimitive).content)
        val bare = ClaudeProtocol.turnContent("\$device-automation", emptyList(), "device-automation", null, planMode = false)
        assertEquals("/device-automation", (bare.last().jsonObject["text"] as kotlinx.serialization.json.JsonPrimitive).content)
        val longer = ClaudeProtocol.turnContent("\$device-automation2 hi", emptyList(), "device-automation", null, planMode = false)
        assertEquals("/device-automation \$device-automation2 hi", (longer.last().jsonObject["text"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun theProbeForTheCatalogLoadsUserSettingsOnThePhoneAndAllOfThemOnAComputer() {
        val phone = ClaudeProtocol.commandProbeArgs(userSettingsOnly = true)
        assertEquals("user", phone[phone.indexOf("--setting-sources") + 1])
        assertTrue("--no-session-persistence" in phone)
        assertFalse("--setting-sources" in ClaudeProtocol.commandProbeArgs(userSettingsOnly = false))
    }

    @Test fun aCommandTheCliAnswersItselfShowsItsAnswerAsTheChatMessage() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "11111111-1111-4111-8111-111111111111")
        val lines = listOf(
            """{"type":"command_lifecycle","command_uuid":"11111111-1111-4111-8111-111111111111","state":"started"}""",
            """{"type":"user","message":{"role":"user","content":"<command-name>/context</command-name>"},"isReplay":true,"uuid":"11111111-1111-4111-8111-111111111111"}""",
            """{"type":"assistant","message":{"id":"s1","model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"## Context Usage\n\n**Tokens:** 44.2k / 1m (4%)"}]},"parent_tool_use_id":null}""",
            """{"type":"result","subtype":"success","is_error":false,"num_turns":0,"result":"## Context Usage\n\n**Tokens:** 44.2k / 1m (4%)","total_cost_usd":0}""",
        )
        val events = lines.flatMap { mapper.map(Json.parseToJsonElement(it).jsonObject) }.filterIsInstance<Signal.Emit>().map { it.event }
        val messages = events.filterIsInstance<EngineEvent.MessageCompleted>()
        assertEquals(listOf("commentary", "final_answer"), messages.map { it.phase })
        assertEquals("## Context Usage\n\n**Tokens:** 44.2k / 1m (4%)", messages.first().text)
        assertEquals("completed", events.filterIsInstance<EngineEvent.TurnFinished>().single().status)
    }

    @Test fun aCommandWithNoAnswerShowsNothing() {
        val mapper = ClaudeStreamMapper(thread)
        mapper.begin("turn-1", "frame-1")
        val events = mapper.map(Json.parseToJsonElement("""{"type":"result","subtype":"success","is_error":false,"num_turns":0,"result":""}""").jsonObject)
            .filterIsInstance<Signal.Emit>().map { it.event }
        assertTrue(events.none { it is EngineEvent.MessageCompleted })
        assertEquals("completed", events.filterIsInstance<EngineEvent.TurnFinished>().single().status)
    }
}
