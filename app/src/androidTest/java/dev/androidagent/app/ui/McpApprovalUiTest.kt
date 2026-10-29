package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.EngineEvent
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class McpApprovalUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun computerUseCardNamesTheAppAndForwardsOnlyTheExplicitAnswer() {
        var answer: Pair<String, Boolean>? = null
        val approval = EngineEvent.Approval("remote|pc|confirmation", "mcpServer/elicitation/request", buildJsonObject {
            put("serverName", "cua")
            put("message", "Allow Codex to use Google Chrome?")
        }, "thread", "turn")
        compose.setContent {
            AndroidAgentTheme {
                Surface {
                    ApprovalCard(approval, { id, allow -> answer = id to allow },
                        { _, _ -> error("A plugin confirmation has no standing send grant") }, Modifier.padding(20.dp))
                }
            }
        }
        compose.onNodeWithText("Allow this plugin request?").assertIsDisplayed()
        compose.onNodeWithText("Request: Allow Codex to use Google Chrome?").assertIsDisplayed()
        compose.onNodeWithText("For: This request only").assertIsDisplayed()
        compose.onNodeWithText("Always allow", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertNull(answer) }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(directory, "computer-use-approval.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Allow", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("remote|pc|confirmation" to true, answer) }
    }
}
