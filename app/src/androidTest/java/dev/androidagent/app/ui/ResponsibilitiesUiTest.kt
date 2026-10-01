/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.androidagent.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

/** UI fixtures only: these do not claim real background execution or phone control. */
class ResponsibilitiesUiTest {
    @get:Rule val compose = createComposeRule()
    private val item = Responsibility("delivery", "My delivery", "Watch the delivery date",
        listOf("delivery-update"), createdAt = 1, updatedAt = 1)

    @Test fun unknownResultMustBeReviewedBeforeActivation() {
        var operation: String? = null
        compose.setContent {
            MaterialTheme {
                ResponsibilitiesSheet(AgentUiState(responsibilities = ResponsibilitySnapshot(
                    responsibilities = listOf(item.copy(state = ResponsibilityState.NEEDS_ATTENTION)),
                )), AgentUiActions(onResponsibilityState = { _, action -> operation = action; null }))
            }
        }
        compose.onNodeWithText("My delivery").performClick()
        compose.onNodeWithText("Activate reviewed rules").assertDoesNotExist()
        screenshot("responsibility-unknown-result")
        compose.onNodeWithText("I checked the result · keep paused").performScrollTo().performClick()
        assertEquals("acknowledge", operation)
    }

    @Test fun activationShowsTheActualRuleBeforeForwardingTheUserChoice() {
        var operation: Pair<String, String>? = null
        val rule = AutomationSummary(id = "delivery-update", name = "Delivery update", chipName = "Delivery",
            trigger = "Every day at 08:00", status = AutomationSummary.Status.ON,
            attention = AutomationAttention.NONE, detail = "Ready",
            actions = listOf("Post a delivery update"))
        compose.setContent {
            MaterialTheme {
                ResponsibilitiesSheet(AgentUiState(
                    responsibilities = ResponsibilitySnapshot(responsibilities = listOf(item)),
                    automations = AutomationsStatus(overview = AutomationOverview.EMPTY.copy(summaries = listOf(rule))),
                ), AgentUiActions(onResponsibilityState = { id, action -> operation = id to action; null }))
            }
        }
        compose.onNodeWithText("My delivery").performClick()
        compose.onNodeWithText("Post a delivery update").assertExists()
        screenshot("responsibility-review")
        compose.onNodeWithText("Activate reviewed rules").performScrollTo().performClick()
        assertEquals("delivery" to "activate", operation)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No UI screenshot")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
