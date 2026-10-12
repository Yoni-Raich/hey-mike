package dev.androidagent.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.junit.Rule
import org.junit.Test
import java.io.File

class CompactAndContextUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contextStartsShortAndDetailsCanOpenAndClose() {
        val answer = """
            ## Context Usage
            **Model:** claude-sonnet-5-5
            **Tokens:** 403.7k / 1m (40%)
            ### Estimated usage by category
            | Category | Tokens | Percentage |
            |----------|--------|------------|
            | Messages | 313.2k | 31.3% |
            | Free space | 563.3k | 56.3% |
        """.trimIndent()
        compose.setContent { AndroidAgentTheme { ContextUsageCard(parseContextUsage(answer)!!) } }
        compose.onNodeWithText("403.7k / 1m").assertIsDisplayed()
        compose.onNodeWithText("40% used").assertIsDisplayed()
        compose.onNodeWithText("Messages").assertDoesNotExist()
        capture("context-summary")
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Messages").assertIsDisplayed()
        compose.onNodeWithText("313.2k").assertIsDisplayed()
        capture("context-details")
        compose.onNodeWithText("Hide details").performClick()
        compose.onNodeWithText("Messages").assertDoesNotExist()
    }

    @Test fun compactionStaysVisibleUntilTheStateEnds() {
        val active = mutableStateOf(true)
        compose.setContent {
            AndroidAgentTheme {
                if (active.value) CompactingCard(System.currentTimeMillis() - 5_000)
                else androidx.compose.material3.Text("Chat compacted")
            }
        }
        compose.onNodeWithText("Compacting the chat…").assertIsDisplayed()
        capture("compact-running")
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Compacting the chat…").assertIsDisplayed()
        compose.runOnIdle { active.value = false }
        compose.onNodeWithText("Compacting the chat…").assertDoesNotExist()
        compose.onNodeWithText("Chat compacted").assertIsDisplayed()
    }

    @Test fun compactBlocksSendVoiceAndModelInTheComposer() {
        compose.setContent {
            AndroidAgentTheme {
                AgentComposer(
                    state = AgentUiState(activeSessionId = "fixture", compacting = mapOf("fixture" to System.currentTimeMillis()),
                        composerSeeds = mapOf("fixture" to "Keep this draft"), composerDraftVersions = mapOf("fixture" to 1L)),
                    actions = AgentUiActions(),
                )
            }
        }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Choose model and reasoning").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "context-compact-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
