package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.app.TaskDeskState
import dev.androidagent.core.*
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fixture data only: no model, notifications, personal data or device actions. */
class TaskBoardUiTest {
    @get:Rule val compose = createComposeRule()
    private val card = MikeTask(id = "draft", title = "להכין טיוטה לדנה", why = "דנה ביקשה מסמך לבדיקה", plan = "להכין טיוטה ולהציג אותה",
        evidence = listOf(TaskEvidence("whatsapp", "אפשר להכין טיוטה?", "דנה")), originKind = "signal", topicKey = "draft", kind = "draft", createdAt = 1, updatedAt = 1)

    @Test fun hebrewActionsAtLargeFontCarryTheUnsavedNote() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale("he"))
        val decisions = mutableListOf<Pair<String, String?>>()
        try {
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(1f, 1.8f)) {
                        CardActions(card, TaskDeskState(), AgentUiActions(onTaskDecide = { _, choice, note, _ -> decisions += choice to note }), "להוסיף סיכום")
                    }
                }
            }
            compose.onNodeWithText("מייק, תבצע").assertIsDisplayed().performClick()
            assertEquals(listOf("do" to "להוסיף סיכום"), decisions)
            compose.onNodeWithText("אני אבצע").assertIsDisplayed()
            capture("task-actions-hebrew-large-font")
        } finally { Locale.setDefault(before) }
    }

    @Test fun modelChoiceKeepsTheEngineThatOwnsTheSelectedModel() {
        val selected = mutableListOf<Pair<EngineKind, String?>>()
        compose.setContent {
            MaterialTheme { ModelChoiceRow("Models", EngineKind.CODEX, null,
                listOf(AgentModel("codex-model", "Codex fixture"), AgentModel("claude-model", "Claude fixture", engine = EngineKind.CLAUDE))) { engine, model -> selected += engine to model } }
        }
        compose.onNodeWithText("Models").performClick()
        compose.onNodeWithText("Claude fixture · Claude").performClick()
        assertEquals(listOf(EngineKind.CLAUDE to "claude-model"), selected)
    }

    @Test fun boardShowsTheProposalAndTeamEntryInHebrew() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale("he"))
        try {
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        TaskBoardSheet(AgentUiState(taskDesk = TaskDeskState(ready = true, tasks = listOf(card))), AgentUiActions())
                    }
                }
            }
            compose.onNodeWithText(card.title).assertIsDisplayed()
            compose.onNodeWithContentDescription("הצוות של מייק").assertIsDisplayed()
            capture("task-board-hebrew")
        } finally { Locale.setDefault(before) }
    }

    @Test fun calendarPermissionIsAnExplicitUserActionInSources() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale("he"))
        var requests = 0
        try {
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    TaskBoardSheet(AgentUiState(taskBoardPage = "sources", taskDesk = TaskDeskState(ready = true)),
                        AgentUiActions(onTaskCalendarPermission = { requests++ }))
                }
            }
            compose.onNodeWithText("לאפשר קריאת היומן").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(1, requests)
            capture("task-sources-hebrew")
        } finally { Locale.setDefault(before) }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "ui-evidence/$name.png")
        file.parentFile!!.mkdirs()
        compose.waitForIdle()
        // A modal sheet owns another window: capturing the activity root gives a blank background.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bitmap)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
