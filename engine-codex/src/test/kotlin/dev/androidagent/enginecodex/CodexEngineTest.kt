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

package dev.androidagent.enginecodex

import dev.androidagent.core.AgentModel
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.RealtimeAudioChunk
import dev.androidagent.core.ReasoningEffortOption
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CodexEngineTest {
    @Test fun tokenUsageAndMultipleLimitWindowsAreParsedWithoutInventingMissingQuota() {
        val usage = CodexEngine.parseTokenUsage(Json.parseToJsonElement("""{"total":{"totalTokens":180,"inputTokens":120,"outputTokens":60,"cachedInputTokens":40},"modelContextWindow":200000}""").jsonObject)!!
        assertEquals(180L, usage.total)
        assertEquals(40L, usage.cachedInput)
        assertEquals(200000L, usage.contextWindow)
        assertNull(CodexEngine.parseTokenUsage(null))
        val limits = CodexEngine.parseRateLimits(Json.parseToJsonElement("""{"rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":25,"resetsAt":12345,"windowDurationMins":300},"secondary":{"usedPercent":null}}}}""").jsonObject)
        assertEquals(2, limits.size)
        assertEquals(25.0, limits[0].usedPercent!!, 0.0)
        assertNull(limits[1].usedPercent)
        assertTrue(CodexEngine.parseRateLimits(Json.parseToJsonElement("{}").jsonObject).isEmpty())
    }

    @Test fun skillCatalogParsesTheRequestedCwdAndEnabledSkills() {
        val workspace = File("workspace").absoluteFile
        val response = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "cwd": "${workspace.path.replace("\\", "\\\\")}",
                  "skills": [
                    {
                      "name": "device-automation",
                      "description": "Control Android through the device gateway",
                      "path": "/home/.agents/skills/device-automation/SKILL.md",
                      "scope": "user",
                      "enabled": true
                    },
                    {
                      "name": "disabled-skill",
                      "description": "Disabled",
                      "path": "/home/.agents/skills/disabled-skill/SKILL.md",
                      "scope": "user",
                      "enabled": false
                    }
                  ],
                  "errors": []
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        assertEquals(
            listOf(
                AgentSkill(
                    name = "device-automation",
                    description = "Control Android through the device gateway",
                    path = "/home/.agents/skills/device-automation/SKILL.md",
                    scope = "user",
                )
            ),
            CodexEngine.parseSkillCatalog(response, workspace),
        )
    }

    @Test fun skillCatalogKeepsTheSkillsOwnFace() {
        val workspace = File("workspace").absoluteFile
        val response = Json.parseToJsonElement(
            """{"data":[{"cwd":"${workspace.invariantSeparatorsPath}","skills":[
              {"name":"daily-briefing","description":"Long description","path":"/s/daily/SKILL.md","scope":"user","enabled":true,
               "interface":{"displayName":"Daily briefing","shortDescription":"One summary","brandColor":"#F2B155","defaultPrompt":"Brief me"}},
              {"name":"plain","description":"Plain","path":"/s/plain/SKILL.md","scope":"system","enabled":true,
               "shortDescription":"Top-level short","interface":{"brandColor":"orange"}}
            ]}]}"""
        ).jsonObject

        val (plain, daily) = CodexEngine.parseSkillCatalog(response, workspace).sortedBy { it.name }.let { it[1] to it[0] }
        assertEquals("Daily briefing", daily.label)
        assertEquals("One summary", daily.summary)
        assertEquals("#F2B155", daily.brandColor)
        assertEquals("Brief me", daily.defaultPrompt)
        // No display name falls back to the id; a colour that is not #RRGGBB is dropped.
        assertEquals("plain", plain.label)
        assertEquals("Top-level short", plain.summary)
        assertNull(plain.brandColor)
    }

    @Test fun planModeTravelsAsACollaborationModeWithTheTurnsModelAndEffort() {
        val plan = CodexEngine.turnStartParams("thread", "Plan it", emptyList(), "high", planModel = "gpt-5.6-luna")
        val mode = plan["collaborationMode"]!!.jsonObject
        assertEquals("plan", mode["mode"]?.jsonPrimitive?.content)
        val settings = mode["settings"]!!.jsonObject
        assertEquals("gpt-5.6-luna", settings["model"]?.jsonPrimitive?.content)
        assertEquals("high", settings["reasoning_effort"]?.jsonPrimitive?.content)
        // Null keeps Codex's own plan-mode instructions.
        assertEquals(JsonNull, settings["developer_instructions"])

        assertFalse(CodexEngine.turnStartParams("thread", "Do it", emptyList(), "high").containsKey("collaborationMode"))
    }

    @Test fun skillCatalogDoesNotUseAnotherWorkspaceEntry() {
        val workspace = File("workspace").absoluteFile
        val response = Json.parseToJsonElement(
            """{"data":[{"cwd":"${workspace.resolve("other").invariantSeparatorsPath}","skills":[{"name":"wrong","description":"wrong","path":"/wrong/SKILL.md","scope":"repo","enabled":true}]}]}"""
        ).jsonObject

        assertTrue(CodexEngine.parseSkillCatalog(response, workspace).isEmpty())
    }

    @Test fun modelCatalogPreservesAdvertisedReasoningOptions() {
        val response = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "gpt-5.6-luna",
                  "model": "gpt-5.6-luna",
                  "displayName": "GPT-5.6 Luna",
                  "defaultReasoningEffort": "medium",
                  "supportedReasoningEfforts": [
                    {"reasoningEffort": "low", "description": "Fast"},
                    {"reasoningEffort": "medium", "description": "Balanced"},
                    {"reasoningEffort": "high", "description": "Deep"}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        assertEquals(
            listOf(
                AgentModel(
                    id = "gpt-5.6-luna",
                    displayName = "GPT-5.6 Luna",
                    reasoningEfforts = listOf(
                        ReasoningEffortOption("low", "Fast"),
                        ReasoningEffortOption("medium", "Balanced"),
                        ReasoningEffortOption("high", "Deep"),
                    ),
                    defaultReasoningEffort = "medium",
                )
            ),
            CodexEngine.parseModelCatalog(response),
        )
    }

    @Test fun modelCatalogAcceptsLegacyReasoningLevelAliases() {
        val response = Json.parseToJsonElement(
            """
            {
              "data": [
                {
                  "slug": "legacy-model",
                  "supportedReasoningLevels": [
                    {"effort": "low", "description": "Short"},
                    {"effort": "high", "description": "Deep"}
                  ],
                  "defaultReasoningLevel": "low"
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val model = CodexEngine.parseModelCatalog(response).single()
        assertEquals("legacy-model", model.id)
        assertEquals(listOf("low", "high"), model.reasoningEfforts.map { it.value })
        assertEquals("low", model.defaultReasoningEffort)
    }

    @Test fun turnParamsTransmitSelectedEffortAndOmitAuto() {
        val selected = CodexEngine.turnStartParams("thread", "Hello", listOf(File("photo.png")), "high")
        assertEquals("high", selected["effort"]?.jsonPrimitive?.content)
        assertTrue(selected["input"].toString().contains("photo.png"))

        val automatic = CodexEngine.turnStartParams("thread", "Hello", emptyList(), null)
        assertFalse(automatic.containsKey("effort"))
    }

    @Test fun turnParamsIncludeNativeSkillInput() {
        val skill = AgentSkill(
            name = "device-automation",
            description = "Control Android",
            path = "/home/.agents/skills/device-automation/SKILL.md",
            scope = "user",
        )

        val params = CodexEngine.turnStartParams("thread", "\$device-automation read the screen", emptyList(), null, skill)
        val input = params["input"]!!.jsonArray
        assertEquals("text", input[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("skill", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("device-automation", input[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(skill.path, input[1].jsonObject["path"]?.jsonPrimitive?.content)
    }

    @Test fun turnParamsPutTheTrustedDeviceContextBeforeUserText() {
        val params = CodexEngine.turnStartParams(
            "thread",
            "Open Settings",
            emptyList(),
            null,
            capabilities = DeviceCapabilities(
                adbStatus = AdbStatus(ConnectionPhase.CONNECTED, "Connected", 37123),
                ready = setOf("read_ui", "tap", "shell"),
                backendStatus = "Accessibility: connected | Wireless ADB (optional): connected - Connected",
            ),
        )

        val input = params["input"]!!.jsonArray
        val context = input[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(context.contains("Backends: Accessibility: connected | Wireless ADB (optional): connected"))
        assertTrue(context.contains("Device tools you can call now: read_ui, shell, tap"))
        assertTrue(context.contains("Use the supplied device tools"))
        assertEquals("Open Settings", input[1].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test fun aDisconnectedAdbNeverBlocksTheToolsAccessibilityCanStillServe() {
        // Issue #44: the old snapshot derived one availability flag from the
        // ADB phase and said "Do not call device tools", which stopped
        // open_intent on a deep link that never needed ADB.
        val context = CodexEngine.deviceRuntimeContext(
            DeviceCapabilities(
                adbStatus = AdbStatus(ConnectionPhase.DISCONNECTED, "Not connected"),
                ready = setOf("read_ui", "open_intent", "tap", "type_text"),
                blocked = setOf("shell", "install_apk", "push_file", "pull_file"),
                backendStatus = "Accessibility: connected | Wireless ADB (optional): disconnected - Wireless Debugging is off",
            )
        )

        assertTrue(context.contains("Device tools you can call now: open_intent, read_ui, tap, type_text"))
        assertTrue(context.contains("Tools that need a backend that is off: install_apk, pull_file, push_file, shell"))
        assertTrue(context.contains("Call anything in the first list normally"))
        assertTrue(context.contains("being off is normal"))
        // The blanket block is exactly what must not come back.
        assertFalse(context.contains("Do not call device tools"))
        assertFalse(context.contains("Device tools available: no"))
        // Nor may the snapshot lead with the ADB phase.
        assertFalse(context.contains("Wireless ADB phase"))
        assertFalse(context.contains("Local ADB port"))
    }

    @Test fun theSnapshotNamesThePhonesDateSoTodayIsNeverGuessedFromTheScreen() {
        val now = java.time.ZonedDateTime.of(2026, 9, 14, 2, 30, 0, 0, java.time.ZoneId.of("Asia/Jerusalem"))
        val context = CodexEngine.deviceRuntimeContext(DeviceCapabilities(ready = setOf("tap")), now)
        assertTrue(context, context.contains("Phone local time: Monday, 14 September 2026, 02:30 (Asia/Jerusalem)"))
    }

    @Test fun theSnapshotOverridesEarlierClaimsThatToolsWereUnavailable() {
        // Chats from before the fix still hold ADB-era snapshots and refusals.
        val context = CodexEngine.deviceRuntimeContext(DeviceCapabilities(ready = setOf("tap")))
        assertTrue(context.contains("Phone local time: "))
        assertTrue(context.contains("any earlier statement in this chat that device tools were unavailable"))
    }

    @Test fun noLiveDeviceBackendAsksForAccessibilityAndCallsAdbOptional() {
        // Knowledge and workflow tools are always ready, so a non-empty list
        // must not hide that nothing can operate the screen.
        val context = CodexEngine.deviceRuntimeContext(
            DeviceCapabilities(
                adbStatus = AdbStatus(ConnectionPhase.DISCONNECTED, "Not connected"),
                ready = setOf("recall_capability", "device_status"),
                blocked = setOf("read_ui", "shell"),
                deviceBackendLive = false,
            )
        )

        assertTrue(context.contains("No device backend is live"))
        assertTrue(context.contains("Settings > Accessibility"))
        assertTrue(context.contains("opening an app or a deep link needs no backend"))
        assertFalse(context.contains("Call anything in the first list normally"))
    }

    @Test fun adbSetupInProgressStillPointsAtAccessibilityFirst() {
        val context = CodexEngine.deviceRuntimeContext(
            DeviceCapabilities(adbStatus = AdbStatus(ConnectionPhase.CONNECTING, "Connecting"))
        )

        assertTrue(context.contains("No device backend is live yet"))
        assertTrue(context.contains("needs only the Hey Mike accessibility service"))
    }

    @Test fun accessibilityOffWithAdbConnectedAsksForAccessibilityNotAdb() {
        val context = CodexEngine.deviceRuntimeContext(
            DeviceCapabilities(
                adbStatus = AdbStatus(ConnectionPhase.CONNECTED, "Connected", 37123),
                ready = setOf("read_ui", "shell", "tap"),
                blocked = setOf("tap_node", "open_intent"),
            )
        )

        assertTrue(context.contains("need the Hey Mike accessibility service"))
        assertFalse(context.contains("being off is normal"))
    }

    @Test fun theAdbOnlyOverloadStillProducesASnapshot() {
        // Older callers hand over an AdbStatus and nothing else. That path must
        // keep working, it just cannot name the accessibility tools.
        val context = CodexEngine.deviceRuntimeContext(
            DeviceCapabilities(adbStatus = AdbStatus(ConnectionPhase.CONNECTED, "Connected", 37123))
        )

        assertTrue(context.contains("Wireless ADB (optional): connected"))
        assertFalse(context.contains("No device backend is live"))
    }

    @Test fun realtimeStartUsesWebSocketV2ByDefault() {
        val params = CodexEngine.realtimeStartParams("thread-1", "voice-model")

        assertEquals("thread-1", params["threadId"]?.jsonPrimitive?.content)
        assertEquals("audio", params["outputModality"]?.jsonPrimitive?.content)
        assertEquals("v2", params["version"]?.jsonPrimitive?.content)
        assertEquals("true", params["flushTranscriptTailOnSessionEnd"]?.jsonPrimitive?.content)
        assertEquals("voice-model", params["model"]?.jsonPrimitive?.content)
        assertFalse(params.containsKey("transport"))
    }

    @Test fun realtimeStartUsesWebRtcV3AndSdpOffer() {
        val params = CodexEngine.realtimeStartParams(
            "thread-1",
            null,
            dev.androidagent.core.RealtimeTransport.WEBRTC,
            "v=0\\r\\n...offer",
        )

        assertEquals("audio", params["outputModality"]?.jsonPrimitive?.content)
        assertEquals("v3", params["version"]?.jsonPrimitive?.content)
        assertEquals("webrtc", params["transport"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("v=0\\r\\n...offer", params["transport"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content)
        assertFalse(params.containsKey("model"))
    }

    @Test fun realtimeStartRejectsMissingWebRtcOffer() {
        try {
            CodexEngine.realtimeStartParams(
                "thread-1",
                null,
                dev.androidagent.core.RealtimeTransport.WEBRTC,
                " ",
            )
            throw AssertionError("expected a missing WebRTC offer to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun realtimeStartRejectsSdpOnWebSocketFallback() {
        try {
            CodexEngine.realtimeStartParams(
                "thread-1",
                null,
                dev.androidagent.core.RealtimeTransport.WEBSOCKET,
                "v=0",
            )
            throw AssertionError("expected WebSocket SDP to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun realtimeSdpNotificationMapsToAnswerEvent() {
        val event = CodexEngine.parseRealtimeSdp(
            Json.parseToJsonElement(
                """{"threadId":"thread-1","sdp":"v=0\\r\\n...answer"}"""
            ).jsonObject
        )

        assertEquals("thread-1", event.threadId)
        assertEquals("v=0\\r\\n...answer", event.sdp)
    }

    @Test fun realtimeSdpNotificationRejectsMissingAnswer() {
        try {
            CodexEngine.parseRealtimeSdp(
                Json.parseToJsonElement("""{"threadId":"thread-1"}""").jsonObject
            )
            throw AssertionError("expected a missing SDP answer to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun realtimeAudioParamsEncodeBytesAndParserRoundTripsThem() {
        val chunk = RealtimeAudioChunk(
            data = byteArrayOf(0x00, 0x01, 0x02, 0x7f, 0xff.toByte()),
            sampleRate = 24_000,
            numChannels = 1,
            samplesPerChannel = 512,
        )
        val params = CodexEngine.realtimeAppendAudioParams("thread-1", chunk)
        val audio = params["audio"]!!.jsonObject

        assertEquals("AAECf/8=", audio["data"]?.jsonPrimitive?.content)
        assertEquals(24_000, audio["sampleRate"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, audio["numChannels"]?.jsonPrimitive?.intOrNull)
        assertEquals(512, audio["samplesPerChannel"]?.jsonPrimitive?.intOrNull)

        val decoded = CodexEngine.parseRealtimeAudio(audio)
        assertArrayEquals(chunk.data, decoded.data)
        assertEquals(chunk.sampleRate, decoded.sampleRate)
        assertEquals(chunk.numChannels, decoded.numChannels)
        assertEquals(chunk.samplesPerChannel, decoded.samplesPerChannel)
    }

    @Test fun realtimeTextAndSpeechParamsMatchPinnedProtocol() {
        val text = CodexEngine.realtimeAppendTextParams("thread-1", "hello", "assistant")
        assertEquals("thread-1", text["threadId"]?.jsonPrimitive?.content)
        assertEquals("hello", text["text"]?.jsonPrimitive?.content)
        assertEquals("assistant", text["role"]?.jsonPrimitive?.content)

        val speech = CodexEngine.realtimeAppendSpeechParams("thread-1", "say this")
        assertEquals("thread-1", speech["threadId"]?.jsonPrimitive?.content)
        assertEquals("say this", speech["text"]?.jsonPrimitive?.content)
        assertEquals("thread-1", CodexEngine.realtimeStopParams("thread-1")["threadId"]?.jsonPrimitive?.content)
    }

    @Test fun realtimeTextRejectsUnknownRole() {
        try {
            CodexEngine.realtimeAppendTextParams("thread-1", "hello", "system")
            throw AssertionError("expected an invalid role to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun sessionParamsDistinguishResumeAndStart() {
        val work = File("/tmp/workspace")
        val tools = listOf(
            dev.androidagent.core.ToolDefinition("test_tool", "A test tool", kotlinx.serialization.json.buildJsonObject {})
        )

        val resumeParams = CodexEngine.resumeSessionParams(work, "existing-thread-123", "custom-model")
        assertEquals("existing-thread-123", resumeParams["threadId"]?.jsonPrimitive?.content)
        assertEquals("custom-model", resumeParams["model"]?.jsonPrimitive?.content)
        // No tools means the key is absent, not an empty array: an empty array
        // would take every tool away from the thread being resumed.
        assertFalse(resumeParams.containsKey("dynamicTools"))
        assertEquals("never", resumeParams["approvalPolicy"]?.jsonPrimitive?.content)
        assertEquals("danger-full-access", resumeParams["sandbox"]?.jsonPrimitive?.content)

        val startParams = CodexEngine.startSessionParams(work, null, tools)
        assertFalse(startParams.containsKey("threadId"))
        assertFalse(startParams.containsKey("model"))
        assertTrue(startParams.containsKey("dynamicTools"))
        assertEquals(1, startParams["dynamicTools"]?.jsonArray?.size)
    }

    @Test fun aResumedThreadIsOfferedTheToolsThisVersionHas() {
        // A thread binds the tool list it was started with, so a chat opened
        // before an app update could never call a tool that update added -
        // while the turn snapshot, built from the live gateway, said it could.
        val work = File("/tmp/workspace")
        val tools = listOf(
            dev.androidagent.core.ToolDefinition("act_plan", "Run a seen sequence", kotlinx.serialization.json.buildJsonObject {}),
        )

        val carried = CodexEngine.resumeSessionParams(work, "old-thread", null, tools)
        assertEquals(1, carried["dynamicTools"]?.jsonArray?.size)
        assertEquals(
            "act_plan",
            carried["dynamicTools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
        // The same wire shape a fresh thread is given, so a resumed chat and a
        // new one advertise the same thing.
        assertEquals(
            CodexEngine.startSessionParams(work, null, tools)["dynamicTools"],
            carried["dynamicTools"],
        )
        // And the fallback attempt, for a server that will not take them.
        assertFalse(CodexEngine.resumeSessionParams(work, "old-thread", null, null).containsKey("dynamicTools"))
    }

    @Test fun developerInstructionsHoldIdentityAndRulesAndLeaveOperationToAgentsMd() {
        val work = File("workspace")
        val instructions = CodexEngine.startSessionParams(work, null, emptyList())["developerInstructions"]!!.jsonPrimitive.content
        assertEquals(instructions, CodexEngine.resumeSessionParams(work, "t", null)["developerInstructions"]!!.jsonPrimitive.content)

        assertTrue(instructions.contains("Your name is Mike"))
        assertTrue(instructions.contains("AGENTS.md in the current workspace is your operating manual"))
        assertTrue(instructions.contains("[Trusted Android Agent runtime context]"))
        // Operating guidance has one home, the workspace AGENTS.md. A second
        // copy here is how a stale one kept saying device control needed ADB.
        assertFalse(instructions.contains("Observe"))
        assertFalse(instructions.contains("Addressing"))
        assertFalse(instructions.contains("ADB availability"))
    }

    @Test fun openSessionFallsBackToThreadStartOnResumeFailure() = runBlocking {
        val serverIn = java.io.PipedInputStream()
        val clientOut = java.io.PipedOutputStream(serverIn)
        val clientIn = java.io.PipedInputStream()
        val serverOut = java.io.PipedOutputStream(clientIn)

        val fakeProcess = object : Process() {
            override fun getOutputStream() = clientOut
            override fun getInputStream() = clientIn
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
        }

        val fakeRuntime = object : dev.androidagent.core.RuntimeHost {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(dev.androidagent.core.RuntimeStatus())
            override val homeDirectory = File("/tmp/home")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer(): Process = fakeProcess
            override suspend fun stop() = Unit
        }

        val serverReader = serverIn.bufferedReader()
        val serverWriter = serverOut.bufferedWriter()

        val engine = CodexEngine(fakeRuntime)
        val calledMethods = java.util.Collections.synchronizedList(mutableListOf<String>())

        val serverJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                val line = serverReader.readLine() ?: break
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req["id"]?.jsonPrimitive?.content ?: continue
                val method = req["method"]?.jsonPrimitive?.content ?: continue
                calledMethods.add(method)
                when (method) {
                    "initialize" -> {
                        serverWriter.write("""{"id":$id,"result":{}}""" + "\n")
                        serverWriter.flush()
                    }
                    "thread/resume" -> {
                        serverWriter.write("""{"id":$id,"error":{"code":-32600,"message":"no rollout found for thread id stale-123"}}""" + "\n")
                        serverWriter.flush()
                    }
                    "thread/start" -> {
                        serverWriter.write("""{"id":$id,"result":{"thread":{"id":"fresh-456"}}}""" + "\n")
                        serverWriter.flush()
                    }
                }
            }
        }

        try {
            val opened = engine.openSession(File("/tmp/workspace"), "stale-123", null, emptyList())
            assertEquals("fresh-456", opened)
            assertEquals(listOf("initialize", "thread/resume", "thread/start"), calledMethods)
        } finally {
            engine.close()
            serverJob.cancel()
            runCatching { serverIn.close() }
            runCatching { clientIn.close() }
            runCatching { serverOut.close() }
            runCatching { clientOut.close() }
        }
    }

    @Test fun openSessionReturnsResumedThreadWithoutCallingStart() = runBlocking {
        val serverIn = java.io.PipedInputStream()
        val clientOut = java.io.PipedOutputStream(serverIn)
        val clientIn = java.io.PipedInputStream()
        val serverOut = java.io.PipedOutputStream(clientIn)

        val fakeProcess = object : Process() {
            override fun getOutputStream() = clientOut
            override fun getInputStream() = clientIn
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
        }

        val fakeRuntime = object : dev.androidagent.core.RuntimeHost {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(dev.androidagent.core.RuntimeStatus())
            override val homeDirectory = File("/tmp/home")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer(): Process = fakeProcess
            override suspend fun stop() = Unit
        }

        val serverReader = serverIn.bufferedReader()
        val serverWriter = serverOut.bufferedWriter()

        val engine = CodexEngine(fakeRuntime)
        val calledMethods = java.util.Collections.synchronizedList(mutableListOf<String>())

        val serverJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                val line = serverReader.readLine() ?: break
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req["id"]?.jsonPrimitive?.content ?: continue
                val method = req["method"]?.jsonPrimitive?.content ?: continue
                calledMethods.add(method)
                when (method) {
                    "initialize" -> {
                        serverWriter.write("""{"id":$id,"result":{}}""" + "\n")
                        serverWriter.flush()
                    }
                    "thread/resume" -> {
                        serverWriter.write("""{"id":$id,"result":{"thread":{"id":"resumed-789"}}}""" + "\n")
                        serverWriter.flush()
                    }
                    "thread/start" -> {
                        serverWriter.write("""{"id":$id,"result":{"thread":{"id":"fresh-456"}}}""" + "\n")
                        serverWriter.flush()
                    }
                }
            }
        }

        try {
            val opened = engine.openSession(File("/tmp/workspace"), "resumed-789", null, emptyList())
            assertEquals("resumed-789", opened)
            assertEquals(listOf("initialize", "thread/resume"), calledMethods)
            assertFalse(calledMethods.contains("thread/start"))
        } finally {
            engine.close()
            serverJob.cancel()
            runCatching { serverIn.close() }
            runCatching { clientIn.close() }
            runCatching { serverOut.close() }
            runCatching { clientOut.close() }
        }
    }

    @Test fun openSessionPropagatesCancellationExceptionWithoutFallback() = runBlocking {
        val serverIn = java.io.PipedInputStream()
        val clientOut = java.io.PipedOutputStream(serverIn)
        val clientIn = java.io.PipedInputStream()
        val serverOut = java.io.PipedOutputStream(clientIn)

        val fakeProcess = object : Process() {
            override fun getOutputStream() = clientOut
            override fun getInputStream() = clientIn
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
        }

        val fakeRuntime = object : dev.androidagent.core.RuntimeHost {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(dev.androidagent.core.RuntimeStatus())
            override val homeDirectory = File("/tmp/home")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer(): Process = fakeProcess
            override suspend fun stop() = Unit
        }

        val serverReader = serverIn.bufferedReader()
        val serverWriter = serverOut.bufferedWriter()

        val engine = CodexEngine(fakeRuntime)
        val calledMethods = java.util.Collections.synchronizedList(mutableListOf<String>())

        val serverJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                val line = serverReader.readLine() ?: break
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req["id"]?.jsonPrimitive?.content ?: continue
                val method = req["method"]?.jsonPrimitive?.content ?: continue
                calledMethods.add(method)
                when (method) {
                    "initialize" -> {
                        serverWriter.write("""{"id":$id,"result":{}}""" + "\n")
                        serverWriter.flush()
                    }
                    "thread/resume" -> {
                        // Delay response to allow cancellation
                    }
                    "thread/start" -> {
                        serverWriter.write("""{"id":$id,"result":{"thread":{"id":"fresh-456"}}}""" + "\n")
                        serverWriter.flush()
                    }
                }
            }
        }

        try {
            val job = launch {
                engine.openSession(File("/tmp/workspace"), "cancel-thread", null, emptyList())
            }
            while (!calledMethods.contains("thread/resume")) {
                delay(10)
            }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(calledMethods.contains("thread/resume"))
            assertFalse(calledMethods.contains("thread/start"))
        } finally {
            engine.close()
            serverJob.cancel()
            runCatching { serverIn.close() }
            runCatching { clientIn.close() }
            runCatching { serverOut.close() }
            runCatching { clientOut.close() }
        }
    }

    // Switching accounts closes the engine while the stderr read blocks. On a
    // phone that read then throws, and the app crashed with nobody catching it.
    @Test fun closeWhileStderrReadBlocksDoesNotCrash() = runBlocking {
        val serverIn = java.io.PipedInputStream()
        val clientOut = java.io.PipedOutputStream(serverIn)
        val clientIn = java.io.PipedInputStream()
        val serverOut = java.io.PipedOutputStream(clientIn)
        val destroyed = java.util.concurrent.CountDownLatch(1)
        val stderrFailed = java.util.concurrent.CountDownLatch(1)
        val stderr = object : java.io.InputStream() {
            override fun read(): Int {
                destroyed.await()
                stderrFailed.countDown()
                throw java.io.InterruptedIOException("read interrupted by close() on another thread")
            }
        }

        val fakeProcess = object : Process() {
            override fun getOutputStream() = clientOut
            override fun getInputStream() = clientIn
            override fun getErrorStream() = stderr
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = destroyed.countDown()
        }

        val fakeRuntime = object : dev.androidagent.core.RuntimeHost {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(dev.androidagent.core.RuntimeStatus())
            override val homeDirectory = File("/tmp/home")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer(): Process = fakeProcess
            override suspend fun stop() = fakeProcess.destroy()
        }

        val serverReader = serverIn.bufferedReader()
        val serverWriter = serverOut.bufferedWriter()
        val engine = CodexEngine(fakeRuntime)
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.set(error) }

        val serverJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                val line = serverReader.readLine() ?: break
                val req = Json.parseToJsonElement(line).jsonObject
                val id = req["id"]?.jsonPrimitive?.content ?: continue
                if (req["method"]?.jsonPrimitive?.content == "initialize") {
                    serverWriter.write("""{"id":$id,"result":{}}""" + "\n")
                    serverWriter.flush()
                }
            }
        }

        try {
            engine.connect()
            engine.close()
            assertTrue(stderrFailed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            delay(200)
            assertNull(uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            serverJob.cancel()
            runCatching { serverIn.close() }
            runCatching { clientIn.close() }
            runCatching { serverOut.close() }
            runCatching { clientOut.close() }
        }
    }
}
