/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.app.ui.widget.AssistantMessageBody
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Draws the control-center widget for real. Besides asserting that the inputs
 * drive the numbers, it writes screenshots to the app's external files dir
 * (`widget-shots/`) so a person can look at them: pixels are the only proof a
 * chart is legible.
 */
class WidgetRenderUiTest {
    @get:Rule val compose = createComposeRule()

    private val asked = ArrayList<String>()
    private lateinit var scroll: ScrollState
    private lateinit var scope: CoroutineScope

    private fun widgetJson(): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("widget-control-center.json")
            .bufferedReader().use { it.readText() }

    private fun show(colors: ColorScheme = darkColorScheme(), body: String = widgetJson()) {
        // The dot grid asks for a frame forever, so the test clock is driven by hand.
        compose.mainClock.autoAdvance = false
        val text = "הנה הדשבורד שביקשת. הזיזו את הסליידרים וראו מה משתנה.\n\n```mike-widget\n$body\n```\n\nזה הכול — נתונים לדוגמה."
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    scroll = rememberScrollState()
                    scope = rememberCoroutineScope()
                    Column(Modifier.verticalScroll(scroll).padding(12.dp)) {
                        AssistantMessageBody(
                            messageId = "demo",
                            text = text,
                            streaming = false,
                            textColor = colors.onSurface,
                            onLongPress = {},
                            onAsk = { asked += it },
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 1_500) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /**
     * Brings the node with [text] near the top. `performScrollTo` waits for
     * frames, which never come while the test clock is held for the dot grid.
     */
    private fun scrollTo(text: String) {
        val y = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { scope.launch { scroll.scrollTo((scroll.value + y - 120f).toInt()) } }
        settle(300)
    }

    private fun shot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("widget-shots")!!
        dir.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setSlider(index: Int, value: Float) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))[index]
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        settle(600)
    }

    @Test fun theSlidersDriveTheStatCardsAndTheTableAtOnce() {
        show()
        compose.onNodeWithText("Agent Control Center").assertExists()
        // Numbers carry a left-to-right mark, so they stay in order inside Hebrew text.
        compose.onNodeWithText("840", substring = true).assertExists()
        setSlider(0, 200f)
        compose.onNodeWithText("1,400", substring = true).assertExists()
        compose.onNodeWithText("840", substring = true).assertDoesNotExist()
    }

    @Test fun theAskButtonSendsTheCurrentValuesBackAsAMessage() {
        show()
        setSlider(0, 250f)
        scrollTo("שלח את ההגדרות למייק")
        compose.onNodeWithText("שלח את ההגדרות למייק").performClick()
        settle(300)
        assertEquals(1, asked.size)
        assertTrue(asked[0], asked[0].contains("```json"))
        assertTrue(asked[0], asked[0].contains("\"perDay\": 250"))
        assertTrue(asked[0], asked[0].contains("activity"))
    }

    @Test fun screenshotsOfTheWholeWidgetInDarkAndLight() {
        show()
        shot("dark-1-top")
        scrollTo("פעילות הסוכן")
        shot("dark-2-chart")
        scrollTo("גרף עוגה")
        shot("dark-3-gauge-pie")
        scrollTo("בונוס: תצוגה אינטראקטיבית")
        shot("dark-4-dots")
        scrollTo("העתק נתונים כ-JSON")
        shot("dark-5-table-buttons")
    }

    @Test fun screenshotOfTheTopInLightTheme() {
        show(colors = lightColorScheme())
        shot("light-1-top")
    }

    @Test fun aBrokenBlockIsShownWithItsReasonInsteadOfCrashing() {
        show(body = "{\"body\": [")
        compose.onNodeWithText("This widget could not be drawn", substring = true).assertExists()
        compose.onNodeWithText("Building widget…").assertDoesNotExist()
        shot("broken")
    }
}
