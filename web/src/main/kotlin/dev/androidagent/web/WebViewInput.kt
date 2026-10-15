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

package dev.androidagent.web

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Input goes only to this app-owned WebView, never to the phone's global screen. */
object WebViewInput {
    @Suppress("DEPRECATION")
    suspend fun tap(view: WebView, cssX: Double, cssY: Double) = withContext(Dispatchers.Main.immediate) {
        // CSS pixels to view pixels: density times page zoom. Exact at the
        // default zoom; a pinch-zoomed page has not been checked on a device.
        val scale = view.scale.takeIf { it > 0f } ?: view.resources.displayMetrics.density
        val x = (cssX * scale).toFloat()
        val y = (cssY * scale).toFloat()
        if (!view.isAttachedToWindow || x < 0 || y < 0 || x >= view.width || y >= view.height)
            throw WebFailure("not_visible", "The target is outside the internal WebView viewport. Nothing was pressed.")
        val downAt = SystemClock.uptimeMillis()
        val pointer = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        fun dispatch(action: Int): Boolean {
            // A finger on the touchscreen, as a hand would give it; the simple
            // obtain() leaves the source and tool type unknown.
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 1, pointer, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        val accepted = dispatch(MotionEvent.ACTION_DOWN)
        try {
            if (!accepted) throw WebFailure("input_refused", "The WebView refused touch input. Inspect the page before retrying.", mayHaveActed = true)
            delay(50)
            dispatch(MotionEvent.ACTION_UP)
        } catch (failure: Throwable) {
            // Stop between down/up cancels the gesture instead of completing a click.
            dispatch(MotionEvent.ACTION_CANCEL)
            throw failure
        }
    }
}
