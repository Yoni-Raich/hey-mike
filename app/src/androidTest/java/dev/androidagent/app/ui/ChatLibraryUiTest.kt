package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.ChatSession
import dev.androidagent.core.AutomationAttention
import dev.androidagent.core.AutomationOverview
import dev.androidagent.core.AutomationSummary
import dev.androidagent.enginecodex.CodexThread
import dev.androidagent.remote.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic chats only: UI evidence without a real account or personal history. */
class ChatLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val fixture = AgentUiState(
        isDrawerOpen = true,
        activeSessionId = "phone-0",
        sessions = List(257) { ChatSession("phone-$it", if (it == 0) "Plan the week" else "Phone chat $it", now, now - it * 60_000) },
        computers = listOf(RemoteComputer("pc", "Work PC", "", user = "test"), RemoteComputer("server", "Server", "", user = "test")),
        pcThreads = mapOf(
            "pc" to List(41) { CodexThread("pc-$it", "Review project $it", "C:\\projects\\project-$it", now - (it + 1) * 60_000) },
            "server" to listOf(CodexThread("server-chat", "Fix the upload", "/work/mobile-app", now - 90_000)),
        ),
        computerSetup = mapOf(
            "pc" to RemoteSetup.Ready(HostProbe("Work PC", "C:\\Users\\test", "x86_64", "", true), "test@example.com", RemoteRoute("", true)),
            "server" to RemoteSetup.Ready(HostProbe("Server", "/home/test", "x86_64", "", true), "test@example.com", RemoteRoute("", true)),
        ),
    )

    private fun show(actions: AgentUiActions = AgentUiActions(), state: AgentUiState = fixture) {
        compose.setContent { AndroidAgentTheme { Surface(modifier = Modifier.widthIn(max = 360.dp).fillMaxSize(), color = DrawerSurface, contentColor = MaterialTheme.colorScheme.onSurface) { ChatLibraryDrawer(state, actions, {}) } } }
    }

    @Test fun hundredsOfChatsShowAFlatRecentListAndASeparateProjectList() {
        show()
        compose.onNodeWithText("Plan the week").assertIsDisplayed()
        compose.onNodeWithText("Review project 0").assertIsDisplayed()
        compose.onNodeWithText("project-0", substring = false).assertDoesNotExist()
        screenshot("library-recent")
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithText("project-0", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Plan the week").assertDoesNotExist()
        screenshot("library-projects")
    }

    @Test fun deviceAndProjectChoicesRouteNewChatsToThatProject() {
        var destination: Pair<String, String>? = null
        show(AgentUiActions(onNewChatInProject = { computer, path -> destination = computer to path }))
        compose.onNodeWithText("All devices").performClick()
        compose.onNodeWithText("Server", substring = false).performClick()
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithText("mobile-app", substring = false).performClick()
        compose.onNodeWithText("Fix the upload").assertIsDisplayed()
        compose.onNodeWithText("Plan the week").assertDoesNotExist()
        screenshot("library-project-chats")
        compose.onNodeWithText("New chat").performClick()
        compose.runOnIdle { assertEquals("server" to "/work/mobile-app", destination) }
    }

    @Test fun searchKeepsTheSelectedDeviceAndBackRestoresTheProjects() {
        show()
        compose.onNodeWithText("All devices").performClick()
        compose.onNodeWithText("Server", substring = false).performClick()
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithContentDescription("Search chats or projects").performTextInput("mobile")
        compose.onNodeWithText("mobile-app", substring = false).performClick()
        compose.onNodeWithContentDescription("Back to projects").performClick()
        compose.onNodeWithContentDescription("Search chats or projects").assertTextContains("mobile")
        compose.onNodeWithText("mobile-app", substring = false).assertIsDisplayed()
    }

    @Test fun smallScreensKeepActionsReachableWhileTheLibraryScrolls() {
        var openedAutomations = false
        val rule = AutomationSummary("fixture", "Daily check", "Check", "Every day", AutomationSummary.Status.BLOCKED, AutomationAttention.NONE, "Needs notification access")
        show(AgentUiActions(onOpenAutomations = { openedAutomations = true }), fixture.copy(
            automations = AutomationsStatus(overview = AutomationOverview.EMPTY.copy(summaries = listOf(rule), enabled = 1, blocked = 1)),
        ))
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close chats").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Automations, 1 needs you").assertIsDisplayed()
        screenshot("library-responsive")
        compose.onNodeWithTag("chat-library").performScrollToNode(hasText("Projects", substring = false))
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithTag("chat-library").performScrollToNode(hasText("project-10", substring = false))
        compose.onNodeWithText("project-10", substring = false).performClick()
        compose.onNodeWithTag("chat-library").performScrollToNode(hasText("Review project 10", substring = false))
        compose.onNodeWithText("Review project 10", substring = false).assertIsDisplayed()
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Automations, 1 needs you").performClick()
        compose.runOnIdle { assertEquals(true, openedAutomations) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
