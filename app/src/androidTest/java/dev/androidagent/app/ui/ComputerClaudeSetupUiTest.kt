package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.remote.ComputerClaude
import dev.androidagent.remote.ComputerClaudeSignInState
import dev.androidagent.remote.RemoteBinding
import dev.androidagent.remote.RemoteComputer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic data only. No connection, real sign-in, or saved account is used. */
class ComputerClaudeSetupUiTest {
    @get:Rule val compose = createComposeRule()
    private val computer = RemoteComputer("pc", "Pc", "", user = "test")
    private val url = "https://claude.ai/oauth/authorize?state=fixture"

    @Test fun theConnectButtonNamesTheComputerAndForwardsItsId() {
        var started: String? = null
        compose.setContent {
            AndroidAgentTheme { Surface { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ComputerClaudeSetup(computer, ComputerClaude(installed = true), null, AgentUiActions(onComputerClaudeLogin = { started = it }))
            } } }
        }
        compose.onNodeWithText("Connect Claude to Pc").assertIsDisplayed()
        screenshot("computer-claude-connect")
        compose.onNodeWithText("Connect Claude to Pc").performClick()
        compose.runOnIdle { assertEquals("pc", started) }
    }

    @Test fun thePastedCodeIsMaskedAndClearedBeforeSubmission() {
        var submitted: Pair<String, String>? = null
        val signIn = mutableStateOf(ComputerClaudeSignInState(loginUrl = url))
        compose.setContent {
            AndroidAgentTheme { Surface { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ComputerClaudeSetup(computer, ComputerClaude(installed = true), signIn.value, AgentUiActions(onComputerClaudeCode = { id, code -> submitted = id to code }))
            } } }
        }
        compose.onNodeWithText("Finish sign-in on Pc").assertIsNotEnabled()
        screenshot("computer-claude-code")
        val field = compose.onNode(hasSetTextAction())
        field.assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        field.performTextInput("fixture-code#state")
        compose.onNodeWithText("Finish sign-in on Pc").performClick()
        compose.runOnIdle { assertEquals("pc" to "fixture-code#state", submitted) }
        field.assertTextEquals("")
        compose.onNodeWithText("Finish sign-in on Pc").assertIsNotEnabled()
        compose.runOnIdle { signIn.value = ComputerClaudeSignInState(error = "Sign-in expired. Start again.") }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        screenshot("computer-claude-retry")
    }

    @Test fun cancellationForwardsTheRightComputerAndBusyStateBlocksAnotherLogin() {
        var cancelled: String? = null
        compose.setContent {
            AndroidAgentTheme { Surface { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ComputerClaudeSetup(computer, ComputerClaude(installed = true), ComputerClaudeSignInState(busy = true), AgentUiActions(onCancelComputerClaudeLogin = { cancelled = it }))
            } } }
        }
        compose.onNodeWithText("Connect Claude to Pc").assertDoesNotExist()
        compose.onNodeWithText("Cancel sign-in").performClick()
        compose.runOnIdle { assertEquals("pc", cancelled) }
    }

    @Test fun aComputerChatCanReachSetupFromTheModelMenu() {
        var computersOpened = false
        compose.setContent {
            AndroidAgentTheme {
                ModelMenu(
                    AgentUiState(activeSessionId = "chat", computers = listOf(computer), remoteBindings = mapOf("chat" to RemoteBinding("pc", "C:\\fixture"))),
                    AgentUiActions(onOpenComputers = { computersOpened = true }), expanded = true, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Model", substring = false).performClick()
        compose.onNodeWithText("Set up on Pc").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, computersOpened) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
