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

package dev.androidagent.app.overlay

import android.content.Intent
import android.provider.Settings
import android.os.SystemClock
import android.app.Instrumentation
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import dev.androidagent.core.OverlayPhase
import dev.androidagent.core.OverlayState
import dev.androidagent.overlay.FloatingControlOverlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FloatingControlOverlayTest {
    @Test
    fun pillHidesForOwnAppRestoresDuringRunAndCapturesSyntheticSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        // Overlay permission is intentionally managed by the host test setup;
        // do not change app-ops or device state from this test.
        assumeTrue(Settings.canDrawOverlays(targetContext))

        val fixture = instrumentation.startActivitySync(
            Intent(targetContext, OverlayFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val device = UiDevice.getInstance(instrumentation)
        var opened = 0
        val overlay = FloatingControlOverlay(
            fixture,
            onStop = {},
            onSend = {},
            onOpenApp = { opened++ },
        )
        try {
            runBlocking { overlay.show("Thinking") }
            waitForIdle(instrumentation)
            // A run starts as the pill: what is happening, and Stop.
            assertTrue(device.hasObject(By.desc("Stop run")))
            assertTrue(device.hasObject(By.text("Working")))
            assertFalse(device.hasObject(By.desc("Send message")))
            val target = (48 * targetContext.resources.displayMetrics.density).toInt()
            val pill = device.findObject(By.descStartsWith("Expand agent controls"))
            assertTrue("Pill touch target", pill.visibleBounds.height() >= target)
            saveScreenshot(device, targetContext, "overlay-pill")

            pill.click()
            SystemClock.sleep(450L)
            waitForIdle(instrumentation)

            // Catch a crowded card: controls must remain usable and the
            // steer field must sit below the pill row, within the screen.
            val stop = device.findObject(By.desc("Stop run")).visibleBounds
            val send = device.findObject(By.desc("Send message")).visibleBounds
            val input = device.findObject(By.desc("Steer or reply")).visibleBounds
            assertTrue("Stop touch target", stop.width() >= target && stop.height() >= target)
            assertTrue("Send touch target", send.width() >= target && send.height() >= target)
            assertTrue("Steer field below the pill row", input.top >= stop.bottom)
            assertTrue("Steer field has space", input.width() >= target * 3)
            assertTrue("Send stays on screen", send.right <= device.displayWidth)
            saveScreenshot(device, targetContext, "overlay-card")

            device.findObject(By.desc("Collapse agent controls")).click()
            SystemClock.sleep(450L)
            waitForIdle(instrumentation)
            assertFalse(device.hasObject(By.desc("Send message")))
            assertTrue(device.hasObject(By.desc("Stop run")))

            overlay.setAppForeground(true)
            waitForIdle(instrumentation)
            assertFalse(device.hasObject(By.desc("Stop run")))
            assertTrue(device.hasObject(By.desc("Synthetic overlay test surface")))

            overlay.setAppForeground(false)
            waitForIdle(instrumentation)
            assertTrue(device.hasObject(By.desc("Stop run")))
            assertTrue(device.hasObject(By.text("Thinking")))

            overlay.finish(OverlayState(OverlayPhase.DONE))
            SystemClock.sleep(500L)
            waitForIdle(instrumentation)
            assertFalse(device.hasObject(By.desc("Stop run")))
            assertEquals(1, opened)
        } finally {
            overlay.hide()
            fixture.finish()
        }
    }

    @Test fun manualExitDuringThinkingDoesNotShowOverlayOrReopenApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        var opened = 0
        val overlay = FloatingControlOverlay(instrumentation.targetContext, {}, {}, { opened++ })
        try {
            overlay.setAppForeground(true)
            overlay.updateState(OverlayState(OverlayPhase.THINKING))
            overlay.setAppForeground(false)
            waitForIdle(instrumentation)
            assertFalse(device.hasObject(By.desc("Stop run")))
            overlay.finish(OverlayState(OverlayPhase.DONE))
            waitForIdle(instrumentation)
            assertEquals(0, opened)
        } finally { overlay.hide() }
    }

    private fun waitForIdle(instrumentation: Instrumentation) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120L)
    }

    private fun saveScreenshot(device: UiDevice, context: android.content.Context, name: String) {
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val file = File(directory, "$name.png")
        assertTrue(device.takeScreenshot(file))
        assertTrue(file.isFile && file.length() > 0L)
    }
}
