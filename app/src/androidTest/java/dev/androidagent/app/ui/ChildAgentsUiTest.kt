package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** UI fixtures only. These never start a model or microphone. */
class ChildAgentsUiTest {
    @get:Rule val compose = createComposeRule()
    private val root = ChatSession("root", "Plan the weekend", 0, 0)
    private val child = ChatSession("child", "Find places", 1, 1, parentSessionId = "root",
        model = "gpt-6.1-sol", reasoningEffort = "high")
    private val card = ChildAgentItem("task", "root", "child", "Find places", "Find three quiet places nearby", "running",
        "Checking the options", "Pc · Project X", model = "gpt-6.1-sol", reasoningEffort = "high")
    private fun fixture() = AgentUiState(
        sessions = listOf(root, child), activeSessionId = "root", activeSessionTitle = root.title,
        messages = listOf(ChatMessage("task", "root", "subagent", "Find places", 1)), childAgents = listOf(card),
        accountStatus = AccountStatus(true, "Fixture"), onboarding = OnboardingProgress(welcomed = true,
            consentVersion = Onboarding.CONSENT_VERSION, finished = true),
    )

    @Test fun cardOpensOrdinaryChildChatAndParentLinkGoesBack() {
        val state = mutableStateOf(fixture())
        val sent = mutableListOf<String>()
        val actions = AgentUiActions(onSelectSession = { id -> state.value = state.value.copy(
            activeSessionId = id, activeSessionTitle = if (id == "child") child.title else root.title,
            messages = if (id == "child") listOf(ChatMessage("reply", "child", "assistant", "I found three options.", 2))
                else fixture().messages,
        ) }, onSend = { text, _ -> sent += text })
        compose.setContent { AndroidAgentScreen(state.value, actions) }
        compose.onNodeWithContentDescription("Open subagent chat: Find places").assertIsDisplayed()
        capture("subagents-parent")
        compose.onNodeWithContentDescription("Open subagent chat: Find places").performClick()
        compose.onNodeWithText("I found three options.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Tell me more")
        compose.onNodeWithContentDescription("Send message").performClick()
        assertEquals(listOf("Tell me more"), sent)
        capture("subagents-child")
        compose.onNodeWithContentDescription("Open parent chat: Plan the weekend").performClick()
        assertEquals("root", state.value.activeSessionId)
    }

    @Test fun voiceCardCanOpenChildAndSendWhileSourceVoiceStaysActive() {
        val state = mutableStateOf(fixture().copy(voiceState = VoiceState(VoicePhase.LISTENING, "Listening"), voiceSessionId = "root"))
        val sent = mutableListOf<String>()
        var keyboardHides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { keyboardHides++ }
        }
        val actions = AgentUiActions(
            onSelectSession = { state.value = state.value.copy(activeSessionId = it, activeSessionTitle = child.title,
                voiceBrowsing = true, messages = listOf(ChatMessage("reply", "child", "assistant", "Working on the list.", 3))) },
            onSend = { text, _ -> sent += text },
            onReturnToVoice = { state.value = state.value.copy(activeSessionId = "root", activeSessionTitle = root.title, voiceBrowsing = false) },
        )
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                AndroidAgentScreen(state.value, actions)
            }
        }
        compose.waitForIdle()
        assertEquals(1, keyboardHides)
        compose.onNodeWithContentDescription("Open subagent chat: Find places").assertIsDisplayed()
        capture("subagents-voice")
        compose.onNodeWithContentDescription("Open subagent chat: Find places").performClick()
        compose.waitForIdle()
        assertEquals(1, keyboardHides)
        compose.onNodeWithText("Voice · Plan the weekend").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start voice conversation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Use the second option")
        compose.onNodeWithContentDescription("Send message").performClick()
        assertEquals(listOf("Use the second option"), sent)
        assertTrue(state.value.voiceState.active)
        capture("subagents-voice-browsing")
        compose.onNodeWithText("Return").performClick()
        compose.waitForIdle()
        assertFalse(state.value.voiceBrowsing)
        assertTrue(state.value.voiceState.active)
        assertEquals(2, keyboardHides)
    }

    @Test fun childStopTargetsOnlyThatChild() {
        val stopped = mutableListOf<String>()
        compose.setContent { AndroidAgentTheme { ChildAgentCard(card, AgentUiActions(onStopSession = { stopped += it })) } }
        compose.onNodeWithContentDescription("Stop subagent: Find places").performClick()
        assertEquals(listOf("child"), stopped)
    }

    @Test fun multipleChildrenOpenFromTheSheetAndTerminalCardsHaveNoStopButton() {
        val done = card.copy(id = "done", sessionId = "done-chat", title = "Review plan", status = "completed",
            model = "opus", reasoningEffort = "medium", result = "Review finished", place = "Pc · Project Y")
        val selected = mutableListOf<String>()
        compose.setContent { AndroidAgentScreen(fixture().copy(childAgents = listOf(card, done)),
            AgentUiActions(onSelectSession = { selected += it })) }
        compose.onNodeWithContentDescription("Subagents (2)").performClick()
        compose.onNodeWithText("Review finished").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop subagent: Review plan").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open subagent chat: Review plan").performClick()
        assertEquals(listOf("done-chat"), selected)
    }

    @Test fun longHebrewCardAtLargeFontKeepsOpenAndStopActionsAccessible() {
        val title = "בדיקת מסמכי הפרויקט והכנת סיכום מפורט"
        val long = card.copy(title = title, task = "בדוק את המסמכים ושמור את כל הפרטים החשובים לצורך המשך העבודה",
            status = "waiting_for_user", progress = "Waiting for your answer")
        val selected = mutableListOf<String>(); val stopped = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                AndroidAgentTheme { ChildAgentCard(long, AgentUiActions(onSelectSession = { selected += it }, onStopSession = { stopped += it })) }
            }
        }
        compose.onNodeWithText("Needs you").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open subagent chat: $title").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Stop subagent: $title").assertIsDisplayed().performClick()
        assertEquals(listOf("child"), selected); assertEquals(listOf("child"), stopped)
        capture("subagents-hebrew-large-font")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun delayedDraftEchoCannotEraseTypingAndExplicitDraftStillLoads() {
        val state = mutableStateOf(fixture().copy(composerSeeds = mapOf("root" to "Use ")))
        val sent = mutableListOf<String>()
        compose.setContent { AndroidAgentScreen(state.value, AgentUiActions(onSend = { text, _ -> sent += text })) }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(4))
        field.performTextInput("session_")
        field.performTextInput("agents options.")
        compose.runOnIdle { state.value = state.value.copy(composerSeeds = mapOf("root" to "Use session_")) }
        field.assertTextContains("Use session_agents options.")
        compose.onNodeWithContentDescription("Send message").performClick()
        assertEquals(listOf("Use session_agents options."), sent)
        compose.runOnIdle { state.value = state.value.copy(composerSeeds = mapOf("root" to "Read README.md"),
            composerDraftVersions = mapOf("root" to 1L)) }
        field.assertTextContains("Read README.md")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "ui-fixtures/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
