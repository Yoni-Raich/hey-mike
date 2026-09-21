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

package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.AgentModel
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.ReasoningEffortOption
import dev.androidagent.core.RunPhase
import dev.androidagent.core.RunState
import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatUiTest {
    @Test fun localIntentApprovalShowsExactRequestAndForwardsItsId() {
        var answer: Pair<String, Boolean>? = null
        val approval = EngineEvent.Approval(
            requestId = "local-intent-1",
            method = "open_intent",
            details = buildJsonObject {
                put("reason", "Start a payment")
                put("action", "android.intent.action.VIEW")
                put("uri", "https://pay.example/checkout?amount=10")
                put("package", "com.example.pay")
            },
            threadId = "thread",
            turnId = "turn",
        )
        compose.setContent {
            AndroidAgentScreen(
                fixture.copy(
                    runState = RunState(
                        phase = RunPhase.CONTROLLING,
                        sessionId = "ui-fixture",
                        status = "Waiting for approval",
                        approval = approval,
                    ),
                ),
                AgentUiActions(onApproval = { requestId, allow -> answer = requestId to allow }),
            )
        }

        compose.onNodeWithText("Approval needed").assertIsDisplayed()
        compose.onNodeWithText("https://pay.example/checkout?amount=10", substring = true).assertIsDisplayed()
        compose.onNodeWithText("com.example.pay", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Allow").performClick()
        compose.runOnIdle { assertEquals("local-intent-1" to true, answer) }
    }

    @Test fun newChatRequiresConfirmationWhileAnotherSessionRuns() {
        var created = 0
        compose.setContent { AndroidAgentScreen(fixture.copy(runState = RunState(RunPhase.THINKING, "ui-fixture")), AgentUiActions(onNewChat = { created++ })) }
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithText("Start a new chat?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, created) }
        compose.onNodeWithText("New chat").performClick()
        compose.runOnIdle { assertEquals(1, created) }
    }

    @Test fun dedicatedVoiceStopIsVisibleAndDispatchesLocalStop() {
        var stopped = 0
        compose.setContent { AndroidAgentScreen(fixture.copy(voiceState = VoiceState(VoicePhase.SPEAKING, "Speaking", "voice")), AgentUiActions(onStop = { stopped++ })) }
        compose.onNodeWithText("End voice").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
        screenshot("voice-stop")
    }

    @Test fun voiceModeMuteTogglesAndShowsMicrophoneOff() {
        var toggles = 0
        val listening = fixture.copy(voiceState = VoiceState(VoicePhase.LISTENING, "Listening", "voice"))
        compose.setContent { AndroidAgentScreen(listening, AgentUiActions(onVoiceMuteToggle = { toggles++ })) }
        compose.onNodeWithContentDescription("Mute microphone").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }

        compose.setContent { AndroidAgentScreen(listening.copy(voiceMuted = true), AgentUiActions()) }
        compose.onNodeWithContentDescription("Unmute microphone").assertIsDisplayed()
        compose.onNodeWithText("Microphone off").assertIsDisplayed()
        screenshot("voice-muted")
    }

    @Test fun workStatusStaysOutsideTheScrollingConversation() {
        compose.setContent { AndroidAgentScreen(fixture.copy(
            runState = RunState(RunPhase.TOOL, "ui-fixture", "Reading current screen"),
            messages = (1..60).map { ChatMessage("m$it", "ui-fixture", "user", "Message $it", it.toLong()) }), AgentUiActions()) }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithText("Reading current screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop agent").assertIsDisplayed()
    }

    @Test fun richMarkdownAndQueuedSessionControlsRender() {
        var cancelled = ""
        val task = dev.androidagent.core.QueuedTurn(id = "queued", sessionId = "other", prompt = "Queued work")
        compose.setContent { AndroidAgentScreen(fixture.copy(messages = listOf(ChatMessage("rich", "ui-fixture", "assistant",
            "# שלום World\n\n*Italic* and **bold** and [link](https://example.com)\n\n> Quoted text\n\n1. First\n   - Nested\n2. Second\n\n| Name | Value |\n| --- | --- |\n| בדיקה | 42 |\n\n```kotlin\nval answer = 42\n```", 1)),
            queuedTurns = listOf(task), queuePaused = true), AgentUiActions(onCancelQueued = { cancelled = it })) }
        compose.onNodeWithText("Resume queue").assertIsDisplayed()
        compose.onNodeWithText("Cancel task").performClick()
        compose.runOnIdle { assertEquals("queued", cancelled) }
        screenshot("rich-markdown-queue")
    }

    @get:Rule val compose = createComposeRule()
    private val fixture = AgentUiState(
        activeSessionId = "ui-fixture", activeSessionTitle = "תכנון היום",
        selectedModel = "gpt-5.6-luna", availableModels = listOf("gpt-5.6-luna"),
        modelCatalog = listOf(
            AgentModel(
                id = "gpt-5.6-luna",
                reasoningEfforts = listOf(
                    ReasoningEffortOption("low", "Fast"),
                    ReasoningEffortOption("high", "Deep"),
                ),
                defaultReasoningEffort = "low",
            )
        ),
        messages = listOf(
            ChatMessage("user", "ui-fixture", "user", "עזור לי לתכנן את היום שלי", 1),
            ChatMessage("assistant", "ui-fixture", "assistant",
                "בשמחה. נתחיל בדברים החשובים לך היום.\n\nאפשר להכין רשימת משימות, לבחור מה לעשות קודם ולשמור זמן להפסקה. מה תרצה להספיק?", 2)
        )
    )
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(700)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun hebrewMessageSendsAndClearsDraft() {
        var sent = ""
        compose.setContent { AndroidAgentScreen(fixture, AgentUiActions(onSend = { text, _ -> sent = text })) }
        compose.onNodeWithText("עזור לי לתכנן את היום שלי").assertIsDisplayed()
        // An empty draft offers voice rather than a disabled send.
        compose.onNodeWithContentDescription("Send message").assertDoesNotExist()
        screenshot("chat-hebrew")
        compose.onNodeWithContentDescription("Message input").performClick().performTextInput("נכין רשימה")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals("נכין רשימה", sent) }
        compose.onNodeWithContentDescription("Message input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }
    @Test fun stopStaysReachableWhileSteering() {
        var stopped = 0
        var steered = ""
        compose.setContent { AndroidAgentScreen(fixture.copy(runState = RunState(
            phase = RunPhase.THINKING, sessionId = "ui-fixture", status = "Thinking")),
            AgentUiActions(onStop = { stopped++ }, onSteer = { steered = it })) }
        compose.onNodeWithContentDescription("Message input").performClick().performTextInput("קודם את המשימה החשובה")
        compose.onNodeWithContentDescription("Stop agent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Steer agent").assertIsDisplayed()
        screenshot("chat-steering")
        compose.onNodeWithContentDescription("Steer agent").performClick()
        compose.runOnIdle { assertEquals("קודם את המשימה החשובה", steered) }
        compose.onNodeWithContentDescription("Stop agent").performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
    }
    @Test fun stoppingKeepsDraftAndBlocksSteering() {
        compose.setContent { AndroidAgentScreen(fixture.copy(runState = RunState(
            phase = RunPhase.STOPPING, sessionId = "ui-fixture")), AgentUiActions()) }
        compose.onNodeWithContentDescription("Message input").performTextInput("Keep this draft")
        compose.onNodeWithContentDescription("Steer agent").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop agent").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Message input").assertTextContains("Keep this draft")
    }
    @Test fun reasoningSelectorUsesAdvertisedOptions() {
        var selected: String? = null
        compose.setContent {
            AndroidAgentScreen(
                fixture,
                AgentUiActions(onReasoningEffortSelected = { selected = it }),
            )
        }
        compose.onNodeWithContentDescription("Choose model and reasoning").performClick()
        compose.onNodeWithText("high").performClick()
        compose.runOnIdle { assertEquals("high", selected) }
    }
    @Test fun markdownAndCopyControlAreVisible() {
        compose.setContent { AndroidAgentScreen(fixture.copy(messages = listOf(
            ChatMessage("assistant", "ui-fixture", "assistant", "# כותרת\n\n- פריט ראשון", 3)
        )), AgentUiActions()) }
        compose.onNodeWithText("כותרת", substring = true).assertIsDisplayed()
        compose.onNodeWithText("פריט ראשון", substring = true).assertIsDisplayed()
        screenshot("chat-markdown")
    }
    @Test fun diagnosticsAreCollapsedAndRemainAvailable() {
        val details = "[HTTP] Connection failed | diagnostic trace"
        compose.setContent { AndroidAgentScreen(fixture.copy(errorMessage = details), AgentUiActions()) }
        compose.onNodeWithText(details).assertDoesNotExist()
        compose.onNodeWithText("Show details").performClick()
        compose.onNodeWithText(details).assertIsDisplayed()
        compose.onNodeWithText("Hide details").performClick()
        compose.onNodeWithText(details).assertDoesNotExist()
        screenshot("chat-error-collapsed")
    }
    @Test fun voiceButtonStartsAndShowsActiveState() {
        var toggles = 0
        compose.setContent {
            AndroidAgentScreen(fixture, AgentUiActions(onVoiceToggle = { toggles++ }))
        }
        compose.onNodeWithContentDescription("Start voice conversation").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }

        compose.setContent {
            AndroidAgentScreen(
                fixture.copy(voiceState = VoiceState(VoicePhase.LISTENING, "Listening", "thread-1")),
                AgentUiActions(onVoiceToggle = { toggles++ }),
            )
        }
        compose.onNodeWithContentDescription("End voice conversation").assertIsDisplayed()
        compose.onNodeWithText("Listening").assertIsDisplayed()
    }

    @Test fun deviceActionsFoldIntoOneRowThatOpens() {
        compose.setContent { AndroidAgentScreen(fixture.copy(messages = listOf(
            ChatMessage("u", "ui-fixture", "user", "פתח את וואטסאפ", 1),
            ChatMessage("t1", "ui-fixture", "tool", "open_app: ok", 2),
            ChatMessage("t2", "ui-fixture", "tool", "read_ui: {}", 3),
            ChatMessage("t3", "ui-fixture", "tool", "tap: ok", 4),
        )), AgentUiActions()) }
        compose.onNodeWithText("3 actions on your phone").assertIsDisplayed()
        compose.onNodeWithText("Read the screen").assertDoesNotExist()
        compose.onNodeWithText("3 actions on your phone").performClick()
        compose.onNodeWithText("Read the screen").assertIsDisplayed()
        screenshot("chat-device-actions")
    }

    @Test fun phoneControlStatusOpensFromTheAgentOrb() {
        compose.setContent {
            AndroidAgentScreen(
                fixture.copy(
                    adbStatus = AdbStatus(
                        phase = ConnectionPhase.CONNECTED,
                        message = "Connected to 127.0.0.1:37123",
                        port = 37123,
                    ),
                ),
                AgentUiActions(),
            )
        }

        compose.onNodeWithContentDescription("Open status and usage", substring = true).performClick()
        compose.onNodeWithText("Ready to control your phone through ADB").assertIsDisplayed()
        compose.onNodeWithText("Connected · port 37123").assertIsDisplayed()

        compose.setContent {
            AndroidAgentScreen(
                fixture.copy(
                    adbStatus = AdbStatus(
                        phase = ConnectionPhase.CONNECTING,
                        message = "Connecting",
                    ),
                ),
                AgentUiActions(),
            )
        }
        compose.onNodeWithContentDescription("Open status and usage", substring = true).performClick()
        compose.onNodeWithText("The agent cannot control your phone yet").assertIsDisplayed()
        compose.onNodeWithText("Reconnecting").assertIsDisplayed()
    }
}
