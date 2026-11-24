package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.EngineKind
import dev.androidagent.core.connections.AccountConnector
import dev.androidagent.core.connections.AccountConnectionsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Fixtures only: no engine, account, connector or real message is contacted by these tests. */
class AccountConnectionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val gmail = AccountConnector(EngineKind.CODEX, "ChatGPT Gmail", "connected", listOf("gmail.read", "gmail.search"), 3)
    private val calendar = AccountConnector(EngineKind.CLAUDE, "claude.ai Google Calendar", "connected", listOf("calendar.read"), 1)

    @Test fun bothProvidersAndAnExplicitOffSwitchAreVisible() {
        var toggled: Pair<String, Boolean>? = null
        render(AccountConnectionsState(accounts = listOf(gmail, calendar), checked = EngineKind.entries.toSet()),
            onToggle = { id, on -> toggled = id to on })
        compose.onNodeWithText("ChatGPT · Codex").assertIsDisplayed()
        compose.onNodeWithText("Gmail").assertIsDisplayed()
        compose.onNodeWithTag("account-connection-${gmail.id}").assertIsOff().performClick()
        assertEquals(gmail.id to true, toggled)
        compose.onNodeWithText("Claude Code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Google Calendar").performScrollTo().assertIsDisplayed()
        screenshot("account-connections-providers")
    }

    @Test fun anUnavailableSavedGrantCanStillBeTurnedOff() {
        var toggled: Pair<String, Boolean>? = null
        render(AccountConnectionsState(accounts = listOf(gmail.copy(state = "unavailable")), enabled = setOf(gmail.id)),
            onToggle = { id, on -> toggled = id to on })
        compose.onNodeWithTag("account-connection-${gmail.id}").assertIsOn().performClick()
        assertEquals(gmail.id to false, toggled)
    }

    @Test fun emptyAccountsShowSignInAndRefreshWithoutEnablingAnything() {
        var refreshes = 0
        var signIns = 0
        render(AccountConnectionsState(checked = EngineKind.entries.toSet()), onRefresh = { refreshes++ }, onOpenAccounts = { signIns++ })
        compose.onNodeWithText("Refresh connections").performClick()
        compose.onNodeWithText("Accounts").performClick()
        assertEquals(1, refreshes)
        assertEquals(1, signIns)
        screenshot("account-connections-empty")
    }

    private fun render(state: AccountConnectionsState, onToggle: (String, Boolean) -> Unit = { _, _ -> }, onRefresh: () -> Unit = {}, onOpenAccounts: () -> Unit = {}) {
        compose.setContent {
            AndroidAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                        AccountConnectionsPage(state, onRefresh, onToggle, onOpenAccounts)
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
