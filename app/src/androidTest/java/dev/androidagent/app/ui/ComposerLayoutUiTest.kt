package dev.androidagent.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.androidagent.core.LimitPlan
import dev.androidagent.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Layout fixtures only; no model, account or device tools are started. */
class ComposerLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowComposerKeepsAutoOnOneLine() = checkLayout(320, 1f, LayoutDirection.Ltr)
    @Test fun largeTextKeepsWholeChipsVisible() = checkLayout(360, 1.5f, LayoutDirection.Ltr)
    @Test fun hebrewLayoutKeepsWholeChipsVisible() = checkLayout(360, 1.5f, LayoutDirection.Rtl)

    private fun checkLayout(width: Int, scale: Float, direction: LayoutDirection) {
        var opened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides direction,
            ) {
                AndroidAgentTheme {
                    Box(Modifier.requiredWidth(width.dp).testTag("composer-fixture")) {
                        AgentComposer(
                            AgentUiState(
                                activeSessionId = "fixture",
                                selectedModel = "claude-sonnet-5-5",
                                fullAccess = true,
                                planMode = true,
                                limits = ChatLimits(plan = LimitPlan(continueAtReset = true)),
                                tokenUsage = TokenUsage(1000, 800, 200, contextWindow = 10000, context = 1000),
                            ),
                            AgentUiActions(onOpenLimitPlan = { opened++ }),
                        )
                    }
                }
            }
        }
        val parent = compose.onNodeWithTag("composer-fixture").fetchSemanticsNode().boundsInRoot
        val descriptions = listOf("Skills and commands", "Choose model and reasoning",
            "Permissions: full access", "Turn plan mode off", "Usage limit plan is on. Edit it")
        val bounds = descriptions.map { label ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach {
            assertTrue("Chip must stay inside composer", it.left >= parent.left - 1 && it.right <= parent.right + 1)
        }
        bounds.forEachIndexed { index, first ->
            bounds.drop(index + 1).forEach { second ->
                assertTrue("Chips must not overlap", !first.overlaps(second))
            }
        }
        listOf("Skills", "Full access", "Plan", "Auto").forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals("$label stays on one line", 1, results.single().lineCount)
            assertTrue("$label must be fully visible", !results.single().hasVisualOverflow)
        }
        compose.onNodeWithText("Auto").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
