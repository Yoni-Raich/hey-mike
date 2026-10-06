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

    @Test fun everythingIsAFlatRecentListAndAComputerShowsItsProjects() {
        show()
        compose.onNodeWithText("Everything").assertIsDisplayed()
        compose.onNodeWithText("Plan the week").assertIsDisplayed()
        compose.onNodeWithText("Review project 0").assertIsDisplayed()
        screenshot("library-everything")
        compose.onNodeWithContentDescription("Work PC, connected").performClick()
        compose.onNodeWithText("project-0", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Plan the week").assertDoesNotExist()
        screenshot("library-computer")
    }

    @Test fun aProjectsNewChatGoesToThatProject() {
        var destination: Pair<String, String>? = null
        show(AgentUiActions(onNewChatInProject = { computer, path -> destination = computer to path }))
        compose.onNodeWithContentDescription("Server, connected").performClick()
        compose.onNodeWithText("Fix the upload").assertIsDisplayed()
        compose.onNodeWithText("Plan the week").assertDoesNotExist()
        compose.onNodeWithContentDescription("New chat in mobile-app").performClick()
        compose.runOnIdle { assertEquals("server" to "/work/mobile-app", destination) }
    }

    @Test fun searchNarrowsTheChosenComputer() {
        show()
        compose.onNodeWithContentDescription("Work PC, connected").performClick()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search chats or projects").performTextInput("project 10")
        compose.onNodeWithText("Review project 10", substring = false).assertIsDisplayed()
        compose.onNodeWithText("project-3", substring = false).assertDoesNotExist()
    }

    @Test fun theRailKeepsSettingsAndAutomationsReachable() {
        var openedAutomations = false
        val rule = AutomationSummary("fixture", "Daily check", "Check", "Every day", AutomationSummary.Status.BLOCKED, AutomationAttention.NONE, "Needs notification access")
        show(AgentUiActions(onOpenAutomations = { openedAutomations = true }), fixture.copy(
            automations = AutomationsStatus(overview = AutomationOverview.EMPTY.copy(summaries = listOf(rule), enabled = 1, blocked = 1)),
        ))
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close chats").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings", substring = true).assertIsDisplayed()
        screenshot("library-rail")
        compose.onNodeWithContentDescription("Automations, 1 needs you").performClick()
        compose.runOnIdle { assertEquals(true, openedAutomations) }
    }

    @Test fun withoutComputersTheLibraryOffersToAddOne() {
        var opened = false
        show(
            AgentUiActions(onOpenComputers = { opened = true }),
            fixture.copy(computers = emptyList(), pcThreads = emptyMap(), computerSetup = emptyMap()),
        )
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.onNodeWithText("Add a computer").assertIsDisplayed()
        screenshot("library-add-computer")
        compose.onNodeWithText("Add a computer").performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }

    @Test fun withAComputerTheAddCardGivesWayToTheRail() {
        show()
        compose.onNodeWithText("Add a computer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Manage computers").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mikeHasOneStableEntryAndMemoryHasAnObviousEditPath() {
        var opened = false
        var managed = false
        show(AgentUiActions(onOpenMike = { opened = true }, onOpenMikeState = { managed = true }), fixture.copy(
            sessions = fixture.sessions + ChatSession("main", "Mike", now, now, isMike = true),
        ))
        compose.onAllNodesWithText("Mike").assertCountEquals(1)
        compose.onNodeWithTag("mike-home").assertIsDisplayed()
        compose.onNodeWithText("Your ongoing conversation").performClick()
        compose.runOnIdle { assertEquals(true, opened) }
        compose.onNodeWithTag("mike-manage").performClick()
        compose.runOnIdle { assertEquals(true, managed) }
        screenshot("persistent-mike-home")
    }

    @Test fun aMemoryCorrectionKeepsTheSameKeyAndRevision() {
        var saved: List<Any?>? = null
        val memory = dev.androidagent.core.MikeMemory("reply.language", "English", "preference", "chat", now, 4)
        compose.setContent { AndroidAgentTheme {
            MikeStateSheet(AgentUiState(mike = dev.androidagent.core.MikeState(memories = listOf(memory))), AgentUiActions(
                onSaveMikeMemory = { key, text, kind, revision -> saved = listOf(key, text, kind, revision) },
            ))
        } }
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onNodeWithText("Correct").performClick()
        compose.onNodeWithText("What should Mike remember?").performTextReplacement("Hebrew")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("reply.language", "Hebrew", "preference", 4L), saved) }
    }

    @Test fun anUnknownTaskRequiresAVisibleCheckedRetry() {
        var retry: Pair<String, Boolean>? = null
        val task = dev.androidagent.core.MikeTask("task", "Check transfer", "Read file back", "chat", status = dev.androidagent.core.MikeTaskStatus.UNKNOWN, updatedAt = now)
        compose.setContent { AndroidAgentTheme {
            MikeStateSheet(AgentUiState(mike = dev.androidagent.core.MikeState(tasks = listOf(task))), AgentUiActions(
                onRunMikeTask = { id, checked -> retry = id to checked },
            ))
        } }
        compose.onNodeWithText("Tasks", substring = false).performClick()
        compose.onNodeWithText("Result needs checking").assertIsDisplayed()
        compose.onNodeWithText("Review retry").performClick()
        compose.runOnIdle { assertEquals(null, retry) }
        compose.onNodeWithText("Checked — retry").performClick()
        compose.runOnIdle { assertEquals("task" to true, retry) }
    }
}
