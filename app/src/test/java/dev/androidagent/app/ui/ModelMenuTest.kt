package dev.androidagent.app.ui

import dev.androidagent.core.AgentModel
import dev.androidagent.core.ReasoningEffortOption
import dev.androidagent.core.EngineKind
import dev.androidagent.remote.ComputerClaude
import dev.androidagent.remote.RemoteBinding
import dev.androidagent.runtime.ClaudeInstallPhase
import dev.androidagent.runtime.ClaudeInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelMenuTest {
    @Test fun aComputerChatOffersSetupOnItsComputerEvenWhenPhoneClaudeIsUnsupported() {
        val state = AgentUiState(
            activeSessionId = "chat",
            remoteBindings = mapOf("chat" to RemoteBinding("pc", "C:\\project")),
            claude = ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.UNSUPPORTED)),
            computerClaude = mapOf("pc" to ComputerClaude(installed = true, signedIn = false)),
        )
        assertTrue(offersClaudeSetup(state))
        assertEquals("pc", claudeSetupComputerId(state))
        assertFalse(offersClaudeSetup(state.copy(computerClaude = mapOf("pc" to ComputerClaude(true, signedIn = true)))))
        assertTrue(offersClaudeSetup(state.copy(computerClaude = emptyMap())))
        assertFalse(offersClaudeSetup(state.copy(modelCatalog = listOf(AgentModel("sonnet", engine = EngineKind.CLAUDE)))))
    }

    @Test fun aPhoneChatStillOffersPhoneSetupOnlyOnSupportedPhones() {
        val state = AgentUiState(claude = ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.NOT_INSTALLED)))
        assertTrue(offersClaudeSetup(state))
        assertNull(claudeSetupComputerId(state))
        assertFalse(offersClaudeSetup(state.copy(claude = ClaudeUiState(ClaudeInstallState(ClaudeInstallPhase.UNSUPPORTED)))))
    }

    private val luna = AgentModel(
        id = "gpt-6-luna",
        reasoningEfforts = listOf("low", "medium", "high", "xhigh", "max").map { ReasoningEffortOption(it) },
        defaultReasoningEffort = "xhigh",
    )

    @Test fun levelsReadAsWordsInAMenu() {
        assertEquals("Low", effortLabel("low"))
        assertEquals("Extra High", effortLabel("xhigh"))
        assertEquals("Max", effortLabel("max"))
        assertEquals("Very Deep", effortLabel("very_deep"))
    }

    @Test fun modelsReadAsTheirNamesNotTheirIds() {
        assertEquals("6 Luna", shortModelName("gpt-6-luna"))
        assertEquals("6.1 Sol", shortModelName("gpt-6.1-sol"))
        assertEquals("5.5", shortModelName("gpt-5.5"))
        assertEquals("GPT-6 Luna", modelTitle("gpt-6-luna"))
        assertEquals("GPT-6.1 Sol", modelTitle("gpt-6.1-sol"))
        // The engine's own name wins when it says something more than the id.
        assertEquals("GPT-6.1 Sol (preview)", modelTitle("gpt-6.1-sol", "GPT-6.1 Sol (preview)"))
        assertEquals("GPT-6 Luna", modelTitle("gpt-6-luna", "gpt-6-luna"))
        assertEquals("Codex Mini", modelTitle("codex-mini"))
    }

    @Test fun aTurnRunsAtTheChosenLevelElseTheModelsOwn() {
        assertEquals("low", effectiveEffort(luna, "low"))
        assertEquals("xhigh", effectiveEffort(luna, null))
        // A level the model does not offer is ignored, not shown as if it applied.
        assertEquals("xhigh", effectiveEffort(luna, "minimal"))
        assertNull(effectiveEffort(AgentModel("gpt-5.5"), null))
        assertEquals("high", effectiveEffort(null, "high"))
    }
}
