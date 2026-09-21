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

package dev.androidagent.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The agent's live indicator on the floating controls: the voice-mode sphere
 * shrunk to a few hundred points. It churns harder the busier the agent is and
 * takes the colour of the run's state. It only animates while it is on screen.
 */
internal class OverlayOrbView(context: Context) : View(context) {
    private val points = FloatArray(POINTS * 3).also { points ->
        for (index in 0 until POINTS) {
            val y = 1f - index / (POINTS - 1f) * 2f
            val radius = sqrt(1f - y * y)
            val angle = index * 2.399963f
            points[index * 3] = cos(angle) * radius
            points[index * 3 + 1] = y
            points[index * 3 + 2] = sin(angle) * radius
        }
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bands = FloatArray(BANDS)
    private var color = Color.WHITE
    private var colorAnimator: ValueAnimator? = null
    private var activity = 0.2f
    private var burst = 0f
    private var level = 0.2f
    private var seconds = 0f
    private var spin = 0f
    private var lastFrameNanos = 0L
    private var ticking = false
    private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Move to [target]'s colour and churn at [targetActivity] (0 calm, 1 busy). */
    fun setTone(target: Int, targetActivity: Float) {
        activity = targetActivity
        if (target == color) return
        colorAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) {
            color = target
            invalidate()
            return
        }
        colorAnimator = ValueAnimator.ofArgb(color, target).apply {
            duration = COLOR_MS
            addUpdateListener {
                color = it.animatedValue as Int
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (colorAnimator === animation) colorAnimator = null
                }
            })
            start()
        }
    }

    /** A short surge, for the moment an action lands on the screen. */
    fun pulse() {
        burst = 0.6f
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateTicking(isVisible)
    }

    override fun onDetachedFromWindow() {
        updateTicking(false)
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateTicking(visible: Boolean) {
        val shouldTick = visible && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()
        if (shouldTick && !ticking) {
            ticking = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else if (!shouldTick && ticking) {
            ticking = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    private fun onFrame(now: Long) {
        if (!ticking) return
        // Cap the step so a dropped frame cannot jump the sphere.
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos).coerceIn(0L, 50_000_000L) / 1_000_000_000f)
        lastFrameNanos = now
        step(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun step(dt: Float) {
        seconds += dt
        val target = activity + burst
        burst *= exp(-dt * 6f)
        level += (target - level) * approach(dt, if (target > level) 20f else 6f)
        for (band in 0 until BANDS) {
            val shape = exp(-((band - 3.4f) / 3f).pow(2))
            val flutter = 0.5f + 0.5f * sin(seconds * (4.3f + band * 1.7f) + band * 2.1f) *
                sin(seconds * (2.9f + band * 0.9f) + band)
            val goal = level * shape * (0.4f + 0.6f * flutter)
            bands[band] += (goal - bands[band]) * approach(dt, if (goal > bands[band]) 24f else 8f)
        }
        spin += dt * (0.35f + 0.9f * level)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = size * 0.33f
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            size * 0.5f,
            intArrayOf(ColorUtils.setAlphaComponent(color, alpha(0.2f + 0.3f * level)), ColorUtils.setAlphaComponent(color, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, size * 0.5f, glowPaint)

        val cosY = cos(spin)
        val sinY = sin(spin)
        val cosX = cos(TILT)
        val sinX = sin(TILT)
        val unit = size / 40f
        for (index in 0 until POINTS) {
            val x = points[index * 3]
            val y = points[index * 3 + 1]
            val z = points[index * 3 + 2]
            val band = band(1f - abs(y))
            val wobble = sin(3.1f * x + seconds * 1.9f) * sin(2.7f * y - seconds * 1.3f) * sin(3.3f * z + seconds * 2.2f)
            val push = 1f + 0.06f * wobble * (0.4f + level) + 0.3f * band * (0.55f + 0.45f * wobble)
            val px = x * push
            val py = y * push
            val pz = z * push
            val turnedX = px * cosY + pz * sinY
            val turnedZ = -px * sinY + pz * cosY
            val tiltedY = py * cosX - turnedZ * sinX
            val tiltedZ = py * sinX + turnedZ * cosX
            val perspective = 2.6f / (2.6f - tiltedZ)
            val depth = ((tiltedZ + 1f) / 2f).coerceIn(0f, 1f)
            val tint = ColorUtils.blendARGB(color, Color.WHITE, (level * 0.6f * depth).coerceIn(0f, 1f))
            dotPaint.color = ColorUtils.setAlphaComponent(tint, alpha(0.1f + 0.9f * depth * depth))
            canvas.drawCircle(
                cx + turnedX * radius * perspective,
                cy + tiltedY * radius * perspective,
                (0.35f + 0.75f * depth) * unit * (1f + 0.3f * level),
                dotPaint,
            )
        }
    }

    private fun band(position: Float): Float {
        val at = position.coerceIn(0f, 1f) * (BANDS - 1)
        val low = at.toInt().coerceAtMost(BANDS - 1)
        val high = min(BANDS - 1, low + 1)
        return bands[low] + (bands[high] - bands[low]) * (at - low)
    }

    private fun alpha(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 255).toInt()

    private fun approach(dt: Float, rate: Float): Float = 1f - exp(-dt * rate)

    private companion object {
        const val POINTS = 260
        const val BANDS = 8
        const val COLOR_MS = 400L

        // Tilt towards the viewer, so the poles read as a globe rather than a disc.
        const val TILT = 0.38f
    }
}
