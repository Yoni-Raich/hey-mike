package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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

    @Test fun ownedCommitmentShowsItsFollowupAndTomorrowDoesNotExecute() {
        val before = Locale.getDefault(); Locale.setDefault(Locale("he"))
        val decisions = mutableListOf<String>()
        try {
            val task = card.copy(status = TaskStatus.MINE, commitment = TaskCommitment("user", 1_800_000_000_000L,
                completionEvidence = "דנה כתבה שהטופס הגיע"))
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                TaskBoardSheet(AgentUiState(taskBoardPage = "card:draft", taskDesk = TaskDeskState(ready = true, tasks = listOf(task))),
                    AgentUiActions(onTaskDecide = { _, choice, _, _ -> decisions += choice }))
            } }
            compose.onNodeWithText("מעקב").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("בדיקה חוזרת מחר").performScrollTo().performClick()
            assertEquals(listOf("check_later"), decisions)
            capture("follow-up")
        } finally { Locale.setDefault(before) }
    }

    @Test fun faceCanBeCapturedAsAStillFrame() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.background(Color.Black)) {
                    MikeEye(EyeMood.IDLE, Modifier.size(240.dp), animate = false, description = "Mike face")
                }
            }
        }
        val bitmap = compose.onNodeWithContentDescription("Mike face").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "ui-evidence/mike-face.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test fun detailPassesTheEditedInstructionsWithTheDecision() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale("he"))
        val decisions = mutableListOf<Pair<String, String?>>()
        try {
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                TaskBoardSheet(AgentUiState(taskBoardPage = "card:draft", taskDesk = TaskDeskState(ready = true,
                    tasks = listOf(card.copy(why = "נא להכין **מסמך** לדנה")))),
                    AgentUiActions(onTaskDecide = { _, choice, note, _ -> decisions += choice to note }))
            } }
            compose.onNodeWithText("נא להכין מסמך לדנה").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("ההנחיות שלך").performScrollTo().performTextInput("להוסיף סיכום")
            compose.onNodeWithText("מייק, תבצע").performScrollTo().performClick()
            assertEquals(listOf("do" to "להוסיף סיכום"), decisions)
        } finally { Locale.setDefault(before) }
    }

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
                        TaskBoardSheet(AgentUiState(taskDesk = TaskDeskState(ready = true, tasks = listOf(card),
                            managerSessionId = "fixture-manager", members = TeamRoster.BUILT_INS)), AgentUiActions())
                    }
                }
            }
            compose.onNodeWithText(card.title).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("משימות").performScrollTo()
            compose.onNodeWithContentDescription("הצוות של מייק").assertIsDisplayed()
            compose.onNodeWithText("מייק · המנהל").assertIsDisplayed()
            capture("task-board-hebrew")
        } finally { Locale.setDefault(before) }
    }

    @Test fun postponedCardsReturnWithWakeAndSuccessStatusNeverUsesAttemptTime() {
        val before = Locale.getDefault(); Locale.setDefault(Locale("he"))
        val decisions = mutableListOf<String>()
        try {
            val desk = TaskDeskState(ready = true, tasks = listOf(card.copy(status = TaskStatus.SNOOZED)),
                settings = TaskBoardSettings(discoveryOn = true, lastPassAt = System.currentTimeMillis()), waitReason = "waiting")
            assertEquals("עדיין לא הושלם סבב חיפוש", boardSubtitle(desk))
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                TaskBoardSheet(AgentUiState(taskDesk = desk), AgentUiActions(onTaskDecide = { _, choice, _, _ -> decisions += choice }))
            } }
            compose.onNodeWithText("להחזיר עכשיו").performScrollTo().performClick()
            assertEquals(listOf("wake"), decisions)
        } finally { Locale.setDefault(before) }
    }

    @Test fun partialContextOnlyOpensAUserDraftAndShowsReadReceiptNotice() {
        val before = Locale.getDefault(); Locale.setDefault(Locale("he"))
        var requests = 0
        try {
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                TaskBoardSheet(AgentUiState(taskBoardPage = "card:draft", taskDesk = TaskDeskState(ready = true,
                    tasks = listOf(card.copy(partialContext = true)))), AgentUiActions(onTaskContext = { requests++ }))
            } }
            compose.onNodeWithText("לבקש ממייק לקרוא את ההקשר").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(1, requests)
            compose.onNodeWithText("פתיחת אפליקציה עשויה לקחת את המסך ולסמן הודעות כנקראו.", substring = true).assertExists()
            capture("task-context-hebrew")
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
        // Semantics can be ready before the separate modal window is drawn.
        // Wait for its dark surface rather than accepting a blank activity image.
        var bitmap: Bitmap? = null
        if(name != "task-actions-hebrew-large-font") compose.waitUntil(timeoutMillis = 3_000) {
            bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            bitmap?.let { shot ->
                listOf(shot.height / 8, shot.height / 2).all { y ->
                    val pixel = shot.getPixel(shot.width - 8, y)
                    android.graphics.Color.red(pixel) < 90 && android.graphics.Color.green(pixel) < 90 && android.graphics.Color.blue(pixel) < 90
                }
            } == true
        }
        // A modal sheet owns another window: capturing the activity root gives a blank background.
        val captured = bitmap ?: InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(captured)
        file.outputStream().use { captured.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
