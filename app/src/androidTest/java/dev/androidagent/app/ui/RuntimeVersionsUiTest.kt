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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.remote.CodexComputerVersion
import dev.androidagent.remote.ComputerClaude
import dev.androidagent.remote.HostProbe
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteSetup
import dev.androidagent.runtime.CodexUpdateCheck
import java.io.File
import org.junit.Rule
import org.junit.Test

class RuntimeVersionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneSeparatesInstalledVersionFromAvailableApkUpdate() {
        val state = AgentUiState(
            runtimeStatus = RuntimeStatus(RuntimePhase.READY, "Codex runtime ready", version = "0.163.0"),
            codexUpdate = CodexUpdateCheck.info("0.163.0", "0.164.0"),
            claude = ClaudeUiState(version = "2.1.302"),
        )
        compose.setContent {
            AndroidAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { RuntimeSettings(state, AgentUiActions()) }
                }
            }
        }
        compose.onNodeWithText("Codex 0.163.0").assertIsDisplayed()
        compose.onNodeWithText("Claude Code 2.1.302").assertIsDisplayed()
        compose.onNodeWithText("Codex 0.164.0 is available. Update the Mike app to update this phone.").assertIsDisplayed()
        screenshot("runtime-versions-phone")
    }

    @Test fun computerSeparatesRunningVersionFromNextStart() {
        val computer = RemoteComputer("pc", "Pc", "pc.example", user = "tester")
        val probe = HostProbe("PC", "C:/Users/tester", "x86_64", "C:/runtime/codex-app-server.exe", true)
        val state = AgentUiState(
            computers = listOf(computer), defaultComputerId = "pc",
            computerSetup = mapOf("pc" to RemoteSetup.Ready(probe, "Test account")),
            computerCodexVersions = mapOf("pc" to CodexComputerVersion("0.164.0", "0.163.0", automatic = true)),
            computerClaude = mapOf("pc" to ComputerClaude(true, "2.1.302 (Claude Code)")),
        )
        compose.setContent { AndroidAgentTheme { ComputersSheet(state, AgentUiActions()) } }
        compose.onNodeWithText("0.163.0").assertIsDisplayed()
        compose.onNodeWithText("0.164.0 ready for the next start").assertIsDisplayed()
        compose.onNodeWithText("2.1.302").assertIsDisplayed()
        compose.onNodeWithText("Codex updates automatically").assertIsDisplayed()
        screenshot("runtime-versions-computer")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
