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

package dev.androidagent.app.assist

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.util.TypedValue
import android.view.Choreographer
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import kotlin.math.exp

/**
 * A glow that runs around the edge of the screen while Mike listens.
 *
 * It opens from the power button: two arcs leave the right edge and race each
 * other around the screen until they meet on the far side. Then the colours
 * keep turning, and the glow swells with [level], the loudness of whoever is
 * speaking. It draws nothing inside the screen, so the app underneath stays
 * readable, and it takes no touches.
 */
internal class EdgeGlowView(context: Context, private val level: () -> Float) : View(context) {
    private val edge = Path()
    private val visible = Path()
    private val measure = PathMeasure()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rotation = Matrix()
    private var sweep: SweepGradient? = null
    private var length = 0f
    private var cornerRadius = dp(36f)
    private var reveal = 0f
    private var fade = 1f
    private var angle = 0f
    private var loud = 0f
    private var lastFrameNanos = 0L
    private var ticking = false
    private var animator: ValueAnimator? = null
    private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

    /** One colour for a state that needs it (error, working on the phone); null for the full spectrum. */
    var tint: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    /** Run the opening sweep from the power button. */
    fun enter() {
        fade = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            reveal = 1f
            invalidate()
            return
        }
        animate(0f, 1f, ENTER_MS) { reveal = it }
    }

    /** Fade out, then call [done]. */
    fun exit(done: () -> Unit) {
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) {
            done()
            return
        }
        animate(fade, 0f, EXIT_MS, done) { fade = it }
    }

    private fun animate(from: Float, to: Float, duration: Long, done: (() -> Unit)? = null, apply: (Float) -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                apply(it.animatedValue as Float)
                invalidate()
            }
            if (done != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = done()
            })
            start()
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 31) {
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.takeIf { it > 0 }?.let {
                cornerRadius = it.toFloat()
                rebuild(width, height)
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild(w, h)
    }

    /**
     * The screen outline, starting on the right edge where the power button
     * usually sits, so the reveal can grow from distance zero both ways.
     */
    private fun rebuild(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val right = w.toFloat()
        val bottom = h.toFloat()
        val r = cornerRadius.coerceAtMost(minOf(right, bottom) / 2f)
        val origin = (bottom * POWER_BUTTON_HEIGHT).coerceIn(r, bottom - r)
        edge.reset()
        edge.moveTo(right, origin)
        edge.lineTo(right, bottom - r)
        edge.arcTo(RectF(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f)
        edge.lineTo(r, bottom)
        edge.arcTo(RectF(0f, bottom - 2 * r, 2 * r, bottom), 90f, 90f)
        edge.lineTo(0f, r)
        edge.arcTo(RectF(0f, 0f, 2 * r, 2 * r), 180f, 90f)
        edge.lineTo(right - r, 0f)
        edge.arcTo(RectF(right - 2 * r, 0f, right, 2 * r), 270f, 90f)
        edge.lineTo(right, origin)
        measure.setPath(edge, false)
        length = measure.length
        sweep = SweepGradient(right / 2f, bottom / 2f, SPECTRUM, null)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateTicking(isVisible)
    }

    override fun onDetachedFromWindow() {
        updateTicking(false)
        animator?.cancel()
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
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos).coerceIn(0L, 50_000_000L) / 1_000_000_000f)
        lastFrameNanos = now
        val target = level().coerceIn(0f, 1f)
        // Quick to swell, slow to settle, like the voice sphere.
        loud += (target - loud) * (1f - exp(-dt * if (target > loud) 18f else 5f))
        angle = (angle + dt * (TURN_DEGREES_PER_SECOND * (1f + 1.5f * loud))) % 360f
        invalidate()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (length <= 0f || fade <= 0f || reveal <= 0f) return
        val path = if (reveal >= 1f) edge else {
            // Two arcs from the origin, one each way round.
            visible.reset()
            val half = length * reveal / 2f
            measure.getSegment(0f, half, visible, true)
            measure.getSegment(length - half, length, visible, true)
            visible
        }
        val solid = tint
        if (solid == null) {
            rotation.setRotate(angle, width / 2f, height / 2f)
            sweep?.setLocalMatrix(rotation)
            paint.shader = sweep
            paint.color = Color.WHITE
        } else {
            paint.shader = null
            paint.color = solid
        }
        val swell = 1f + 0.9f * loud
        for (layer in LAYERS) {
            // The stroke is centred on the screen edge, so only its inner half shows.
            paint.strokeWidth = dp(layer.widthDp) * swell
            paint.alpha = (layer.alpha * fade * (0.75f + 0.25f * loud) * 255).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private class Layer(val widthDp: Float, val alpha: Float)

    private companion object {
        const val ENTER_MS = 750L
        const val EXIT_MS = 260L
        const val TURN_DEGREES_PER_SECOND = 40f
        /** Where the reveal starts, as a fraction of the screen height on the right edge. */
        const val POWER_BUTTON_HEIGHT = 0.33f
        // A bright core line and three wider, fainter ones make the glow
        // without a blur, which would need a software layer the size of the screen.
        val LAYERS = listOf(Layer(4f, 1f), Layer(10f, 0.5f), Layer(22f, 0.22f), Layer(44f, 0.1f))
        // Mike's state colours, going round: working, controlling, violet, pink, waiting.
        val SPECTRUM = intArrayOf(
            Color.parseColor("#83D9CA"),
            Color.parseColor("#69A7FF"),
            Color.parseColor("#B79CFF"),
            Color.parseColor("#FF8AC8"),
            Color.parseColor("#F6B86A"),
            Color.parseColor("#83D9CA"),
        )
    }
}
