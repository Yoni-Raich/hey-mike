/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.androidagent.core.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MessageActionsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var clipboard: ClipboardManager

    private fun message(role: String, text: String, wholeReply: String? = null) {
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(20.dp)) {
                        MessageBubble(ChatMessage("message", "fixture", role, text, 0L), wholeReply,
                            showCopyAction = role == "assistant")
                    }
                }
            }
        }
    }

    @Test fun userMenuCopiesTheOriginalTextWithoutDirectionMarkers() {
        val text = "שלום Mike\nשורה שנייה 123"
        message("user", text)
        compose.onNodeWithContentDescription("Copy response").assertDoesNotExist()
        compose.onNodeWithText(withRtlLines(text)).performTouchInput { longClick() }
        compose.onNodeWithText("Copy", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(text, clipboard.getText()?.text) }
    }

    @Test fun responseShortcutCopiesAllReplyBlocks() {
        val whole = "I checked the app.\n\nThe answer is ready."
        message("assistant", "The answer is ready.", whole)
        compose.onNodeWithContentDescription("Copy response").performClick()
        compose.runOnIdle { assertEquals(whole, clipboard.getText()?.text) }
        compose.onNode(hasStateDescription("Copied")).assertExists()
    }

    @Test fun userCanSelectTextInAFullScreenAndReturn() {
        message("user", "Select part of my prompt")
        compose.onNodeWithText("Select part of my prompt").performTouchInput { longClick() }
        compose.onNodeWithText("Select text", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Copy all").assertIsDisplayed()
        val selectedText = hasText("Select part of my prompt") and hasAnyAncestor(isDialog())
        compose.onNode(selectedText).assertIsDisplayed()
        compose.onNode(selectedText).performTouchInput {
            longClick(percentOffset(0.18f, 0.5f))
        }
        val copy = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .wait(Until.findObject(By.text("Copy")), 3_000)
        assertNotNull("Android's text selection toolbar", copy)
        copy!!.click()
        compose.runOnIdle {
            val selected = clipboard.getText()?.text.orEmpty()
            assertTrue(selected.isNotBlank() && selected != "Select part of my prompt")
            assertTrue("Select part of my prompt".contains(selected))
        }
        compose.onNodeWithContentDescription("Close text selection").performClick()
        compose.onNodeWithContentDescription("Copy all").assertDoesNotExist()
        compose.onNodeWithText("Select part of my prompt").assertIsDisplayed()
    }

    @Test fun responseLongPressHasTheSameMenuAndSelectsTheWholeReply() {
        val whole = "Checked the app.\n\nשלום Mike\n\nThe answer is ready."
        message("assistant", "The answer is ready.", whole)
        // TextView uses Android's real long-press clock, not Compose's test clock.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .wait(Until.findObject(By.text("The answer is ready.")), 3_000).longClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onNodeWithText("Copy", useUnmergedTree = true).isDisplayed()
        }
        compose.onNodeWithText("Copy", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Select text", useUnmergedTree = true).performClick()
        compose.onNodeWithText(whole).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy all").performClick()
        compose.runOnIdle { assertEquals(whole, clipboard.getText()?.text) }
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-evidence")
        folder.mkdirs()
        File(folder, "message-select-text.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
