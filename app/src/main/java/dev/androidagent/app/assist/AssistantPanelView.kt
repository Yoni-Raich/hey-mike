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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.overlay.OverlayOrbView

/** What the panel shows. Built from the voice and run state by the session. */
internal data class PanelContent(
    val status: String,
    val transcript: String = "",
    val tone: PanelTone = PanelTone.LISTENING,
    val muted: Boolean = false,
    /** False until voice is live: mute means nothing before then. */
    val live: Boolean = false,
)

internal enum class PanelTone { WAKING, LISTENING, SPEAKING, WORKING, CONTROLLING, ERROR }

/**
 * The assistant panel: the edge glow over the whole screen and one card at
 * the bottom. Only the card takes touches (see [touchableRegion]), so the app
 * underneath can still be scrolled and read while the user talks about it.
 */
internal class AssistantPanelView(
    context: Context,
    level: () -> Float,
    private val onMute: () -> Unit,
    private val onOpen: () -> Unit,
    private val onEnd: () -> Unit,
) : FrameLayout(context) {
    private val glow = EdgeGlowView(context, level)
    private val orb = OverlayOrbView(context)
    private val status = TextView(context)
    private val transcript = TextView(context)
    private val mute = pill("Mute", onMute)
    private val card = LinearLayout(context)
    private var bottomInset = 0
    private var controlling = false

    init {
        clipChildren = false
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        status.apply {
            setTextColor(INK_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textDirection = TEXT_DIRECTION_FIRST_STRONG
        }
        transcript.apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.START
            textDirection = TEXT_DIRECTION_FIRST_STRONG
            // Read out as it changes, like a live caption.
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(orb, LinearLayout.LayoutParams(dp(ORB_DP), dp(ORB_DP)))
            addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(mute)
            addView(pill("Open Mike", onOpen), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(pill("End", onEnd, filled = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(GLASS)
                cornerRadius = dp(CARD_RADIUS_DP).toFloat()
                setStroke(dp(1), GLASS_EDGE)
            }
            elevation = dp(8).toFloat()
            isClickable = true
            addView(top)
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        addView(card, LayoutParams(minOf(dp(MAX_WIDTH_DP), resources.displayMetrics.widthPixels - dp(24)), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        card.visibility = INVISIBLE
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomInset = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
        (card.layoutParams as LayoutParams).bottomMargin = bottomInset + dp(12)
        card.requestLayout()
        return super.onApplyWindowInsets(insets)
    }

    /** The glow sweeps in from the power button, then the card springs up from below. */
    fun enter() {
        glow.enter()
        card.visibility = VISIBLE
        if (!ValueAnimator.areAnimatorsEnabled()) return
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.translationY = dp(120).toFloat()
        card.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(CARD_DELAY_MS)
            .setDuration(CARD_MS)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    /** The card drops away and the glow fades, then [done]. */
    fun exit(done: () -> Unit) {
        if (ValueAnimator.areAnimatorsEnabled()) {
            card.animate().alpha(0f).translationY(dp(80).toFloat()).setStartDelay(0).setDuration(EXIT_MS).start()
        }
        glow.exit(done)
    }

    fun render(content: PanelContent) {
        status.text = content.status
        transcript.text = content.transcript
        transcript.visibility = if (content.transcript.isBlank()) GONE else VISIBLE
        mute.text = if (content.muted) "Unmute" else "Mute"
        mute.isEnabled = content.live
        mute.alpha = if (content.live) 1f else 0.4f
        val color = toneColor(content.tone)
        orb.setTone(color, toneActivity(content.tone))
        // The spectrum means "listening"; a single colour means Mike is busy with something else.
        glow.tint = when (content.tone) {
            PanelTone.CONTROLLING, PanelTone.WORKING, PanelTone.ERROR -> color
            else -> null
        }
        // Mike's taps on the app must not land on the card, and a faded card says why.
        val nowControlling = content.tone == PanelTone.CONTROLLING
        if (nowControlling != controlling) {
            card.animate().alpha(if (nowControlling) CONTROLLING_ALPHA else 1f).setStartDelay(0).setDuration(FADE_MS).start()
            controlling = nowControlling
            // The touchable region is read on the next layout.
            requestLayout()
        }
    }

    /** Where touches go to the panel; everywhere else they reach the app below. */
    fun touchableRegion(into: Region) {
        into.setEmpty()
        if (controlling || card.visibility != VISIBLE || card.width == 0) return
        val bounds = Rect()
        card.getHitRect(bounds)
        into.set(bounds)
    }

    private fun pill(label: String, onClick: () -> Unit, filled: Boolean = false) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) ON_LIGHT else INK)
        minHeight = dp(40)
        minWidth = dp(64)
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply {
            setColor(if (filled) INK else FIELD)
            cornerRadius = dp(20).toFloat()
            if (!filled) setStroke(dp(1), OUTLINE)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun toneColor(tone: PanelTone): Int = when (tone) {
        PanelTone.WAKING, PanelTone.LISTENING, PanelTone.SPEAKING -> TONE_LIVE
        PanelTone.WORKING -> TONE_WORKING
        PanelTone.CONTROLLING -> TONE_CONTROLLING
        PanelTone.ERROR -> TONE_ERROR
    }

    private fun toneActivity(tone: PanelTone): Float = when (tone) {
        PanelTone.WAKING -> 0.35f
        PanelTone.LISTENING -> 0.25f
        PanelTone.SPEAKING -> 0.8f
        PanelTone.WORKING, PanelTone.CONTROLLING -> 0.6f
        PanelTone.ERROR -> 0.1f
    }

    private fun dp(value: Int): Int = dp(value.toFloat())
    private fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private companion object {
        const val MAX_WIDTH_DP = 420
        const val ORB_DP = 56
        const val CARD_RADIUS_DP = 28f
        const val CARD_DELAY_MS = 180L
        const val CARD_MS = 420L
        const val EXIT_MS = 220L
        const val FADE_MS = 200L
        const val CONTROLLING_ALPHA = 0.45f

        // The floating card's colours: dark glass that reads over any app.
        val GLASS = Color.argb(240, 18, 18, 18)
        val GLASS_EDGE = Color.argb(28, 255, 255, 255)
        val INK = Color.parseColor("#F2F2F2")
        val INK_DIM = Color.parseColor("#BDBDBD")
        val ON_LIGHT = Color.parseColor("#111111")
        val FIELD = Color.parseColor("#202020")
        val OUTLINE = Color.parseColor("#383838")
        val TONE_LIVE = Color.parseColor("#B79CFF")
        val TONE_WORKING = Color.parseColor("#83D9CA")
        val TONE_CONTROLLING = Color.parseColor("#69A7FF")
        val TONE_ERROR = Color.parseColor("#FFB4AB")
    }
}
