package dev.androidagent.app.ui

import dev.androidagent.core.AgentModel
import dev.androidagent.core.ReasoningEffortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelMenuTest {
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
