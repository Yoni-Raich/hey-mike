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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ChatSession
import dev.androidagent.core.MikeChatNote
import dev.androidagent.core.MikeMemory
import dev.androidagent.core.MikeState
import dev.androidagent.core.MikeTask
import dev.androidagent.core.MikeTaskStatus
import dev.androidagent.remote.HostProbe
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteRoute
import dev.androidagent.remote.RemoteSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Mike's drawer entry, his sheet and what a task leaves in a chat. Synthetic data only. */
class MikeUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private val chats = listOf(
        ChatSession("main", "Mike", now, now, isMike = true),
        ChatSession("trip", "Plan the Lisbon trip", now, now - 2 * hour),
        ChatSession("t-invoice", "Send the invoice to Dana", now, now - 20 * minute),
        ChatSession("t-flights", "Compare flight prices", now, now - minute),
        ChatSession("t-parcel", "Watch the parcel", now, now - 3 * hour),
        ChatSession("t-tidy", "Tidy the Downloads folder", now, now - 5 * hour),
        ChatSession("t-dentist", "Find a dentist nearby", now, now - day),
    )
    private val memories = listOf(
        MikeMemory("reply.style", "Answer in Hebrew, short, with the result first.", "preference", null, now - 2 * hour, 4),
        MikeMemory("coffee", "Oat milk flat white, no sugar.", "preference", "trip", now - 6 * day, 1),
        MikeMemory("home", "Lives in Haifa. The office is in Yokneam, a 25 minute drive.", "fact", "trip", now - 26 * hour, 1),
        MikeMemory("evenings", "The kids sleep from 20:00. Nothing may play sound after that without asking.", "fact", "main", now - 3 * day, 2),
        MikeMemory("lesson.send", "In WhatsApp, read the message back after typing it and before pressing send.", "lesson", "main", now - 5 * day, 1),
    )
    private val tasks = listOf(
        MikeTask("invoice", "Send the invoice to Dana", "Find last month's invoice in Drive and email it to Dana.", "t-invoice", MikeTaskStatus.UNKNOWN,
            nextStep = "The app stopped during this task. Check what completed before running it again.", updatedAt = now - 20 * minute),
        MikeTask("flights", "Compare flight prices", "Compare Tel Aviv to Lisbon for the first week of November on three airlines.", "t-flights", MikeTaskStatus.RUNNING,
            nextStep = "Two airlines checked. Reading the third.", updatedAt = now - minute, turnActive = true),
        MikeTask("parcel", "Watch the parcel", "Check the delivery page each morning until the parcel arrives, then tell me.", "t-parcel", MikeTaskStatus.WAITING,
            nextStep = "Still at customs. Look again in the morning.", wakeAt = now + 9 * hour, updatedAt = now - 3 * hour),
        MikeTask("tidy", "Tidy the Downloads folder", "Sort files older than a month into folders by type.", "t-tidy", MikeTaskStatus.READY, updatedAt = now - 5 * hour),
        MikeTask("dentist", "Find a dentist nearby", "Find a dentist within 2 km with a free slot this week.", "t-dentist", MikeTaskStatus.DONE,
            result = "Dr. Levi, 1.2 km away, has a slot on Thursday at 16:30. The number is saved in your contacts.", updatedAt = now - day),
        MikeTask("gift", "Pick a gift for Noa", "Three ideas under 200 shekels.", "gone-1", MikeTaskStatus.DONE, result = "A sketchbook set, a board game, a climbing voucher.", updatedAt = now - 2 * day),
        MikeTask("bill", "Check the electricity bill", "Is this month higher than last?", "gone-2", MikeTaskStatus.DONE, result = "8% higher than September.", updatedAt = now - 3 * day),
        MikeTask("backup", "Back up the photos", "Copy this month's photos to the computer.", "gone-3", MikeTaskStatus.DONE, result = "412 photos copied.", updatedAt = now - 4 * day),
    )
    private val full = AgentUiState(sessions = chats, activeSessionId = "trip", mike = MikeState(revision = 9, memories = memories, tasks = tasks))

    private fun sheet(state: AgentUiState, actions: AgentUiActions = AgentUiActions()) {
        compose.setContent {
            AndroidAgentTheme {
                Surface(Modifier.fillMaxSize(), color = MikeSheetFill, contentColor = MaterialTheme.colorScheme.onSurface) {
                    MikePages(state, actions, Modifier.fillMaxSize().padding(top = 12.dp))
                }
            }
        }
    }

    private fun drawer(state: AgentUiState, actions: AgentUiActions = AgentUiActions()) {
        compose.setContent {
            AndroidAgentTheme {
                Surface(Modifier.widthIn(max = 360.dp).fillMaxSize(), color = DrawerSurface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    ChatLibraryDrawer(state.copy(isDrawerOpen = true), actions, {})
                }
            }
        }
    }

    /**
     * The sheet in its own window, as the app shows it. Everything else here
     * draws the pages on a plain surface, which hid two faults a phone showed
     * at once: a title drawn black on the dark fill, and a sheet pinned to the
     * top of the screen with the chat showing under it.
     */
    @Test fun theSheetItselfSitsBelowTheStatusBarAndReachesTheBottom() {
        compose.setContent { AndroidAgentTheme { MikeStateSheet(full.copy(mikePanel = MikePanel.MEMORY), AgentUiActions()) } }
        compose.waitForIdle()
        compose.onNodeWithText("Mike").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(output, "mike-sheet-window.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val resources = instrumentation.targetContext.resources
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 }?.let(resources::getDimensionPixelSize) ?: 0
        // The pages, not the sheet's own node: that one spans the window whatever the sheet does.
        val pages = compose.onNodeWithTag("mike-pages").fetchSemanticsNode().boundsInWindow
        assertTrue("the pages start at ${pages.top}, under the status bar ($statusBar)", pages.top > statusBar)
        assertTrue("the pages end at ${pages.bottom} of ${shot.height}, leaving the chat showing below", pages.bottom >= shot.height * 0.9f)
        // Lit text on the dark fill: the title used to be drawn black.
        val title = compose.onNodeWithText("Mike").fetchSemanticsNode().boundsInWindow
        val lit = (title.left.toInt() until title.right.toInt()).any { x ->
            (title.top.toInt() until title.bottom.toInt()).any { y -> shot.getPixel(x, y).let { (it shr 16 and 0xFF) > 0xC0 } }
        }
        assertTrue("the title is not legible on the sheet", lit)
    }

    @Test fun mikeHasOneEntryThatOpensHisChatAndEachSideOfHisSheet() {
        var opened = false
        val panels = mutableListOf<MikePanel>()
        drawer(full, AgentUiActions(onOpenMike = { opened = true }, onOpenMikeState = { panels += it }))
        compose.onAllNodesWithText("Mike").assertCountEquals(1)
        compose.onNodeWithTag("mike-home").assertIsDisplayed()
        // The interrupted task is the one thing here that needs the person.
        compose.onNodeWithText("1 needs you").assertIsDisplayed()
        // A chat Mike opened for a task says so in the list.
        compose.onNodeWithText("Task · needs you").assertIsDisplayed()
        compose.onNodeWithText("5 saved").assertIsDisplayed()
        screenshot("mike-drawer")
        compose.onNodeWithText("Your ongoing conversation").performClick()
        compose.onNodeWithTag("mike-memory").performClick()
        compose.onNodeWithTag("mike-tasks").performClick()
        compose.runOnIdle {
            assertEquals(true, opened)
            assertEquals(listOf(MikePanel.MEMORY, MikePanel.TASKS), panels)
        }
    }

    @Test fun theEntrySitsBesideTheRailWhenThereAreComputers() {
        val pc = RemoteComputer("pc", "Work PC", "", user = "test")
        drawer(full.copy(
            activeSessionId = "main",
            mike = full.mike.copy(tasks = tasks.filterNot { it.status == MikeTaskStatus.UNKNOWN }),
            computers = listOf(pc),
            computerSetup = mapOf("pc" to RemoteSetup.Ready(HostProbe("Work PC", "C:\\Users\\test", "x86_64", "", true), "test@example.com", RemoteRoute("", true))),
        ))
        compose.onNodeWithText("1 working").assertIsDisplayed()
        screenshot("mike-drawer-rail")
    }

    @Test fun aNewPhoneSaysThereIsNothingYet() {
        drawer(AgentUiState(sessions = chats.take(2), activeSessionId = "trip"))
        compose.onNodeWithText("Nothing yet").assertIsDisplayed()
        compose.onNodeWithText("None open").assertIsDisplayed()
        screenshot("mike-drawer-empty")
    }

    @Test fun aMemoryCorrectionKeepsTheSameKeyAndRevision() {
        var saved: List<Any?>? = null
        sheet(full.copy(mikePanel = MikePanel.MEMORY), AgentUiActions(onSaveMikeMemory = { key, text, kind, revision -> saved = listOf(key, text, kind, revision) }))
        // What Mike wrote down on his own says so, and where.
        compose.onAllNodesWithText("Saved by Mike in \u201cPlan the Lisbon trip\u201d", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Added by you", substring = true).assertIsDisplayed()
        screenshot("mike-memory")
        compose.onNodeWithText("Answer in Hebrew, short, with the result first.").performClick()
        compose.onNodeWithText("Forget this").assertIsDisplayed()
        screenshot("mike-memory-page")
        compose.onNodeWithTag("mike-memory-text").performTextReplacement("Answer in English.")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("reply.style", "Answer in English.", "preference", 4L), saved) }
        // Saved, and back on the list.
        compose.onNodeWithTag("mike-add-memory").assertIsDisplayed()
    }

    @Test fun forgettingAsksFirstAndPassesTheRevisionItSaw() {
        var forgotten: Pair<String, Long>? = null
        sheet(full.copy(mikePanel = MikePanel.MEMORY), AgentUiActions(onForgetMikeMemory = { key, revision -> forgotten = key to revision }))
        compose.onNodeWithText("Answer in Hebrew, short, with the result first.").performClick()
        compose.onNodeWithText("Forget this").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, forgotten) }
        compose.onNodeWithText("Forget").performClick()
        compose.runOnIdle { assertEquals("reply.style" to 4L, forgotten) }
    }

    @Test fun anInterruptedTaskCanOnlyBeRetriedFromItsOwnPage() {
        var retry: Pair<String, Boolean>? = null
        sheet(full.copy(mikePanel = MikePanel.TASKS), AgentUiActions(onRunMikeTask = { id, checked -> retry = id to checked }))
        compose.onNodeWithText("Interrupted · check before retrying").assertIsDisplayed()
        screenshot("mike-tasks")
        compose.onNodeWithTag("mike-task-invoice").performClick()
        compose.onNodeWithText("Check what already happened").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, retry) }
        screenshot("mike-task-interrupted")
        compose.onNodeWithTag("mike-task-retry").performClick()
        compose.runOnIdle { assertEquals("invoice" to true, retry) }
    }

    @Test fun aWaitingTaskSaysWhenItWakesAndCanBeToldToStopWaiting() {
        var paused: String? = null
        sheet(full.copy(mikePanel = MikePanel.TASKS), AgentUiActions(onPauseMikeTask = { paused = it }))
        compose.onNodeWithTag("mike-task-parcel").performClick()
        compose.onNodeWithText("Next step", ignoreCase = true).assertIsDisplayed()
        screenshot("mike-task-waiting")
        compose.onNodeWithTag("mike-task-pause").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("parcel", paused) }
    }

    @Test fun stopHoldingEveryTaskIsSaidOutLoudWithTheWayBack() {
        var resumed = false
        sheet(full.copy(mikePanel = MikePanel.TASKS, mike = full.mike.copy(paused = true)), AgentUiActions(onResumeMike = { resumed = true }))
        compose.onNodeWithText("Tasks are on hold").assertIsDisplayed()
        screenshot("mike-tasks-held")
        compose.onNodeWithText("Resume").performClick()
        compose.runOnIdle { assertEquals(true, resumed) }
    }

    @Test fun aNewTaskStartsNowOrIsKeptForLater() {
        val created = mutableListOf<Triple<String, String, Boolean>>()
        sheet(AgentUiState(sessions = chats.take(1), mikePanel = MikePanel.TASKS), AgentUiActions(onCreateMikeTask = { title, instruction, start -> created += Triple(title, instruction, start) }))
        compose.onNodeWithText("No tasks yet").assertIsDisplayed()
        screenshot("mike-tasks-empty")
        compose.onNodeWithTag("mike-add-task").performClick()
        compose.onNodeWithTag("mike-task-title").performTextInput("Watch the parcel")
        compose.onNodeWithTag("mike-task-instruction").performTextInput("Check the delivery page each morning.")
        screenshot("mike-task-new")
        compose.onNodeWithText("Start now").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("Watch the parcel", "Check the delivery page each morning.", true)), created) }
    }

    @Test fun aRefusedChangeIsShownInTheSheet() {
        sheet(AgentUiState(sessions = chats.take(1), mikePanel = MikePanel.MEMORY, mikeActionError = "Memory changed. Read it again before editing."))
        compose.onNodeWithText("Memory changed. Read it again before editing.").assertIsDisplayed()
        compose.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        screenshot("mike-memory-empty")
    }

    @Test fun whatATaskLeavesInAChatReadsAsWhatHappened() {
        var opened: String? = null
        val dentist = tasks.first { it.id == "dentist" }
        val parcel = tasks.first { it.id == "parcel" }
        val messages = listOf(
            ChatMessage("u1", "main", "user", "Find me a dentist nearby and keep an eye on the parcel", now - day),
            ChatMessage("a1", "main", "assistant", "On it. I started two tasks and will tell you here how they go.", now - day),
            ChatMessage("r1", "main", "system", MikeChatNote.report(dentist), now - hour),
            ChatMessage("h1", "main", "user", MikeChatNote.handoff(dentist), now - hour),
            ChatMessage("r2", "main", "system", MikeChatNote.report(parcel), now - minute),
            ChatMessage("b1", "t-parcel", "user", MikeChatNote.brief(parcel.copy(nextStep = "")), now - 2 * day),
            ChatMessage("b2", "t-parcel", "user", MikeChatNote.brief(parcel), now - minute),
        )
        val rows = chatRows(messages, running = false)
        assertEquals(5, rows.count { it is MikeNoteRow })
        compose.setContent {
            AndroidAgentTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        rows.forEach { row ->
                            when (row) {
                                is MikeNoteRow -> MikeChatNoteRow(row, full, AgentUiActions(onSelectSession = { opened = it }))
                                is MessageRow -> Text(row.message.text, style = MaterialTheme.typography.bodyLarge)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        // No prompt text and no JSON reach the screen.
        compose.onAllNodesWithText("quoted task data", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Task ID:", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Find a dentist nearby").assertIsDisplayed()
        screenshot("mike-chat-notes")
        compose.onAllNodesWithText("Open its chat")[0].performClick()
        compose.runOnIdle { assertEquals("t-dentist", opened) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
