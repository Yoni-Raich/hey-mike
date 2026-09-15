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

package dev.androidagent.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.view.Display
import dev.androidagent.core.ToolNotServiceable
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures the display through the accessibility service, so the Vision
 * fallback keeps working with no ADB connection.
 *
 * This composites the real display rather than reading the node tree, which
 * is the whole point: it is what the agent falls back to on screens that
 * expose no usable nodes — games, canvas surfaces, unexposed WebViews.
 *
 * Two hazards drive the shape of this file. The framework hands back a
 * [HardwareBuffer] that the caller owns, and leaking it leaks graphics memory
 * that no JVM test here would ever catch — so it is closed in a `finally`, on
 * every path, including the one where the coroutine was already cancelled
 * when the callback fired. And the platform rate-limits captures; hitting
 * that limit is an ordinary condition rather than a fault, so it raises
 * [ToolNotServiceable] and lets the composite fall through to ADB.
 */
internal object Screenshotter {

    /** Ignored for PNG, but the API demands an argument. */
    private const val PNG_QUALITY = 100

    private const val DEFAULT_ENCODE_BUFFER = 512 * 1024

    /** API 34 constants, named by value because this module supports API 30. */
    private const val INVALID_WINDOW_ERROR = 5
    private const val SECURE_WINDOW_ERROR = 6

    /**
     * Capture the default display as PNG bytes.
     *
     * @throws ToolNotServiceable when the platform declines in a way ADB could
     *   still serve, or on a secure window that no backend can capture.
     */
    suspend fun capturePng(service: AccessibilityService): ByteArray {
        val capture = takeScreenshot(service)
        // The buffer is ours from the moment the callback fired. Everything
        // that reads it happens inside this block so the close cannot be
        // skipped by an early return or a thrown encode failure.
        val software = try {
            val wrapped = Bitmap.wrapHardwareBuffer(capture.buffer, capture.colorSpace)
                ?: throw ToolNotServiceable(
                    "screenshot_unreadable",
                    "The display returned a buffer this device cannot read as a bitmap.",
                )
            try {
                // The wrapped bitmap is hardware-backed and cannot be encoded
                // directly on every device; the software copy is what compresses.
                wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw ToolNotServiceable(
                        "screenshot_unreadable",
                        "The captured frame could not be copied into software memory.",
                    )
            } finally {
                wrapped.recycle()
            }
        } finally {
            capture.buffer.close()
        }
        return try {
            val out = ByteArrayOutputStream(DEFAULT_ENCODE_BUFFER)
            check(software.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                "PNG encoding failed"
            }
            out.toByteArray()
        } finally {
            software.recycle()
        }
    }

    /**
     * Capture every window on the display except [ownPackage]'s, composited
     * bottom to top, so the floating controls never have to leave the screen
     * for the agent to see it. Android 14+ only: it needs per-window capture.
     *
     * Only on-screen windows are captured, so the wallpaper behind a launcher
     * comes out black. A system window that refuses is left out; an app window
     * that refuses fails the whole capture, because a frame missing the app is
     * not the screen.
     *
     * @throws ToolNotServiceable when the capture cannot be trusted, so the
     *   caller can fall back to a whole-display capture.
     */
    @android.annotation.TargetApi(34)
    suspend fun capturePngWithout(service: AccessibilityService, ownPackage: String): ByteArray {
        val windows = service.windows
            .filterNot { window -> isOwnWindow(window, ownPackage) }
            .sortedBy { it.layer }
        val display = service.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val frame = Bitmap.createBitmap(display.width(), display.height(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(frame)
            canvas.drawColor(android.graphics.Color.BLACK)
            val bounds = android.graphics.Rect()
            var drawn = 0
            for (window in windows) {
                window.getBoundsInScreen(bounds)
                if (bounds.isEmpty) continue
                val appWindow = window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                val capture = try {
                    takeWindowScreenshot(service, window.id)
                } catch (refused: ToolNotServiceable) {
                    if (appWindow) throw refused
                    continue
                }
                val software = try {
                    Bitmap.wrapHardwareBuffer(capture.buffer, capture.colorSpace)?.let { wrapped ->
                        try {
                            wrapped.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            wrapped.recycle()
                        }
                    }
                } finally {
                    capture.buffer.close()
                }
                if (software == null) {
                    if (appWindow) throw ToolNotServiceable("screenshot_unreadable", "A window came back in a format this device cannot read.")
                    continue
                }
                try {
                    canvas.drawBitmap(software, null, bounds, null)
                    drawn++
                } finally {
                    software.recycle()
                }
            }
            if (drawn == 0) throw ToolNotServiceable("screenshot_no_windows", "No window on screen could be captured on its own.")
            val out = ByteArrayOutputStream(DEFAULT_ENCODE_BUFFER)
            check(frame.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
            return out.toByteArray()
        } finally {
            frame.recycle()
        }
    }

    /**
     * Our floating card is an overlay window of this package. It is matched by
     * package and, in case its node tree is not available, by the title the
     * overlay gives its window.
     */
    private fun isOwnWindow(window: android.view.accessibility.AccessibilityWindowInfo, ownPackage: String): Boolean {
        if (window.title?.toString() == OVERLAY_WINDOW_TITLE) return true
        return runCatching { window.root?.packageName?.toString() }.getOrNull() == ownPackage
    }

    private suspend fun takeScreenshot(service: AccessibilityService): Capture =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, Executor { it.run() }, captureCallback(continuation))
        }

    @android.annotation.TargetApi(34)
    private suspend fun takeWindowScreenshot(service: AccessibilityService, windowId: Int): Capture =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshotOfWindow(windowId, Executor { it.run() }, captureCallback(continuation))
        }

    private fun captureCallback(
        continuation: kotlinx.coroutines.CancellableContinuation<Capture>,
    ) = object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            val buffer = screenshot.hardwareBuffer
            if (!continuation.isActive) {
                // Stop happened while the capture was in flight. Nobody
                // downstream will ever see this buffer, so this is the only
                // place it can be released.
                buffer.close()
                return
            }
            continuation.resume(Capture(buffer, screenshot.colorSpace))
        }

        override fun onFailure(errorCode: Int) {
            if (continuation.isActive) {
                continuation.resumeWithException(failureFor(errorCode))
            }
        }
    }

    /** Must match the window title FloatingControlOverlay sets. */
    private const val OVERLAY_WINDOW_TITLE = "AndroidAgentControl"

    private class Capture(val buffer: HardwareBuffer, val colorSpace: ColorSpace)

    /**
     * Map a platform error to something the model can act on.
     *
     * Rate limiting and an unavailable display are [ToolNotServiceable]:
     * nothing was captured, so falling through to ADB is safe and useful. A
     * secure window is also typed, but says plainly that no backend will
     * succeed — ADB's `screencap` returns a black frame there, and a black
     * frame presented as the screen is worse than an honest refusal. An
     * internal error stays untyped, because retrying it elsewhere would mask
     * a real fault.
     */
    private fun failureFor(errorCode: Int): Throwable = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> ToolNotServiceable(
            "screenshot_rate_limited",
            "The platform rate-limits accessibility screenshots and this one came too soon " +
                "after the last. Wait about a second, or use the ADB backend.",
        )
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> ToolNotServiceable(
            "screenshot_invalid_display",
            "The default display is not available for capture.",
        )
        INVALID_WINDOW_ERROR -> ToolNotServiceable(
            "screenshot_invalid_window",
            "The target window disappeared before Android could capture it. Try again or use " +
                "the ADB backend.",
        )
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> ToolNotServiceable(
            "screenshot_no_access",
            "The accessibility service is not permitted to capture the screen. Ask the user " +
                "to re-enable Hey Mike in Settings > Accessibility.",
        )
        SECURE_WINDOW_ERROR -> ToolNotServiceable(
            "screenshot_secure_window",
            "A window on screen is marked FLAG_SECURE and the platform refused the capture. " +
                "No backend can photograph this screen; read it with read_ui instead.",
        )
        else -> IllegalStateException("Accessibility screenshot failed with code $errorCode")
    }
}
