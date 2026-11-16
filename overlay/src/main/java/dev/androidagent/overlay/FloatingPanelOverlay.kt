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

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import dev.androidagent.core.PanelBind
import dev.androidagent.core.PanelFace
import dev.androidagent.core.PanelOp
import dev.androidagent.core.Tone

/**
 * Saved panels drawn as cards over whatever app is in front.
 *
 * One window holds every floating card, stacked, near the bottom of the
 * screen. It never takes focus and lets touches outside its bounds through.
 * Its title is the one the accessibility screenshot leaves out, and its
 * package is ours, so Mike's own taps refuse to land on it: a card is only
 * ever tapped by the user. Main thread only.
 */
class FloatingPanelOverlay(
    context: Context,
    private val accessibilityService: () -> AccessibilityService?,
    private val onAction: (panelId: String, actionId: String) -> Unit,
    private val onSlider: (panelId: String, sliderId: String, value: Double) -> Unit,
    private val onClose: (panelId: String) -> Unit,
    /** A change to the panel's own state from the card: a tick, a count, a timer, typed text. */
    private val onOp: (panelId: String, op: PanelOp) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private var windowService: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var column: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val cards = LinkedHashMap<String, Card>()

    /** True when a card can be drawn at all: the accessibility service or Display over other apps. */
    fun canShow(): Boolean = accessibilityService() != null || Settings.canDrawOverlays(appContext)

    /**
     * Draws exactly [faces], updating cards in place so a slider being
     * dragged keeps its thumb. Returns the ids now on screen.
     */
    fun render(faces: List<PanelFace>): Set<String> {
        if (faces.isEmpty()) {
            hide()
            return emptySet()
        }
        if (!attach()) return emptySet()
        val column = column ?: return emptySet()
        val ids = faces.map { it.id }.toSet()
        (cards.keys - ids).forEach { gone -> cards.remove(gone)?.let { column.removeView(it.root) } }
        faces.forEachIndexed { index, face ->
            val card = cards.getOrPut(face.id) { Card(face.id).also { column.addView(it.root, index.coerceAtMost(column.childCount)) } }
            if (column.indexOfChild(card.root) != index) {
                column.removeView(card.root)
                column.addView(card.root, index.coerceAtMost(column.childCount))
            }
            card.bind(face)
        }
        root?.keepScreenOn = faces.any { it.keepScreenOn }
        return ids
    }

    /** Removes the window and every card. */
    fun hide() {
        editing = null
        val view = root
        if (view != null) runCatching { windowManager?.removeViewImmediate(view) }
        root = null
        column = null
        params = null
        cards.clear()
    }

    /** Draws again with the new service's window token after it connects or goes. */
    fun refreshWindowHost() {
        if (root != null && windowService !== accessibilityService()) hide()
    }

    private fun attach(): Boolean {
        val service = accessibilityService()
        if (root?.isAttachedToWindow == true && windowService === service) return true
        hide()
        if (service == null && !Settings.canDrawOverlays(appContext)) return false
        val windowContext: Context = service ?: appContext
        val manager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val stack = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(windowContext).apply {
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            addView(stack, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val layout = WindowManager.LayoutParams(
            minOf(dp(CARD_WIDTH_DP), appContext.resources.displayMetrics.widthPixels - dp(16)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Above the navigation bar and a launcher's dock, which the bar's inset does not include.
            y = navigationInset(manager) + dp(BOTTOM_DP)
            // The accessibility screenshot leaves a window with this title out.
            title = WINDOW_TITLE
        }
        return try {
            manager.addView(frame, layout)
            windowService = service
            windowManager = manager
            root = frame
            column = stack
            params = layout
            true
        } catch (error: RuntimeException) {
            false
        }
    }

    private fun navigationInset(manager: WindowManager): Int = runCatching {
        manager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()).bottom
    }.getOrDefault(0)

    /** The field being typed in, while the window takes focus for the keyboard. */
    private var editing: EditText? = null

    /**
     * The window takes focus only while the user types on a card, so the
     * keyboard can open; it gives focus back as soon as they are done.
     */
    private fun focus(field: EditText?) {
        val view = root ?: return
        val layout = params ?: return
        editing = field
        layout.flags = if (field != null) layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        runCatching { windowManager?.updateViewLayout(view, layout) }
        val keyboard = appContext.getSystemService(InputMethodManager::class.java)
        if (field != null) {
            field.requestFocus()
            field.post { keyboard?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            keyboard?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun moveBy(dy: Int) {
        val view = root ?: return
        val layout = params ?: return
        val max = (appContext.resources.displayMetrics.heightPixels - view.height - dp(24)).coerceAtLeast(0)
        layout.y = (layout.y - dy).coerceIn(0, max)
        runCatching { windowManager?.updateViewLayout(view, layout) }
    }

    /** One card: a title row with close, then a view per row of the panel's face. */
    private inner class Card(val panelId: String) {
        val title = text(15f, INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val body = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        val root: LinearLayout
        private var shape: List<String> = emptyList()
        private val binders = mutableListOf<(PanelFace.Row) -> Unit>()

        init {
            val close = text(18f, INK_DIM).apply {
                text = "×"
                gravity = Gravity.CENTER
                contentDescription = "Close"
                setOnClickListener { onClose(panelId) }
            }
            val header = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            dragWith(header)
            root = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(GLASS, OUTLINE, 20f)
                setPadding(dp(14), dp(4), dp(6), dp(10))
                addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
                addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                })
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            }
        }

        fun bind(face: PanelFace) {
            title.text = face.title
            val next = face.rows.map(::key)
            if (next != shape) {
                body.removeAllViews()
                binders.clear()
                face.rows.forEach { row -> binders += build(row) }
                shape = next
            }
            face.rows.forEachIndexed { index, row -> binders[index](row) }
        }

        private fun key(row: PanelFace.Row): String = when (row) {
            is PanelFace.Row.Note -> "note"
            is PanelFace.Row.Reading -> "reading"
            is PanelFace.Row.Tile -> "tile:${row.actionId}:${row.on != null}"
            is PanelFace.Row.Track -> "track:${row.sliderId}:${row.min}:${row.max}:${row.step}"
            is PanelFace.Row.Checks -> "checks:${row.blockId}:${row.canAdd}:${row.items.joinToString(",") { it.id }}"
            is PanelFace.Row.Count -> "count:${row.blockId}"
            is PanelFace.Row.Clock -> "clock:${row.blockId}"
            is PanelFace.Row.Bar -> "bar"
            is PanelFace.Row.Memo -> "memo:${row.blockId}"
        }

        private fun build(row: PanelFace.Row): (PanelFace.Row) -> Unit = when (row) {
            is PanelFace.Row.Note -> {
                val view = text(13f, INK_DIM)
                body.addView(view, rowParams())
                val bind: (PanelFace.Row) -> Unit = { r ->
                    (r as PanelFace.Row.Note).let { view.text = it.text; view.setTextColor(tone(it.tone, INK_DIM)) }
                }
                bind
            }
            is PanelFace.Row.Reading -> {
                val label = text(13f, INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                val value = text(14f, INK).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END }
                body.addView(LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
                    addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, rowParams())
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val reading = r as PanelFace.Row.Reading
                    label.text = reading.label
                    label.visibility = if (reading.label.isBlank()) View.GONE else View.VISIBLE
                    value.text = reading.value
                    value.gravity = if (reading.label.isBlank()) Gravity.START else Gravity.END
                    value.setTextColor(tone(reading.tone, INK))
                }
                bind
            }
            is PanelFace.Row.Tile -> {
                val label = text(14f, INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                val caption = text(12f, INK_DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                val state = text(12f, INK_DIM).apply { gravity = Gravity.CENTER }
                val tile = LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(FIELD, OUTLINE, 14f)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    isClickable = true
                    addView(LinearLayout(appContext).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label)
                        addView(caption)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(state, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                    setOnClickListener { onAction(panelId, row.actionId) }
                }
                body.addView(tile, rowParams(top = 6))
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val t = r as PanelFace.Row.Tile
                    label.text = t.label
                    caption.text = t.problem ?: t.caption
                    caption.setTextColor(if (t.problem != null) TONE_ERROR else INK_DIM)
                    state.text = when {
                        t.running -> "…"
                        t.on == true -> "On"
                        t.on == false -> "Off"
                        else -> ""
                    }
                    state.setTextColor(if (t.on == true) TONE_DONE else INK_DIM)
                    tile.isEnabled = !t.running
                    tile.alpha = if (t.running) 0.6f else 1f
                    tile.contentDescription = listOf(t.label, state.text, caption.text).filter { it.isNotBlank() }.joinToString(", ")
                }
                bind
            }
            is PanelFace.Row.Track -> {
                val label = text(13f, INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                val number = text(13f, INK)
                var current: PanelFace.Row.Track = row
                var dragging = false
                val bar = SeekBar(appContext).apply {
                    max = row.intervals
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser) number.text = trim(current.valueAt(progress))
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar) { dragging = true }
                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            dragging = false
                            onSlider(panelId, current.sliderId, current.valueAt(seekBar.progress))
                        }
                    })
                }
                body.addView(LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(number)
                }, rowParams(top = 6))
                body.addView(bar, rowParams())
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val track = r as PanelFace.Row.Track
                    current = track
                    label.text = track.label
                    bar.contentDescription = track.label
                    // The user's finger wins over a value read while they drag.
                    if (!dragging) {
                        bar.progress = track.progress
                        number.text = track.valueText
                    }
                }
                bind
            }
            is PanelFace.Row.Checks -> {
                val label = text(13f, INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                body.addView(label, rowParams(top = 6))
                val lines = row.items.map { item ->
                    text(14f, INK).apply {
                        minHeight = dp(40)
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                        setOnClickListener { onOp(panelId, PanelOp("toggle", row.blockId, item = item.id)) }
                        body.addView(this, rowParams(top = 0))
                    }
                }
                val add = if (row.canAdd) entry("Add an item", multiLine = false) { typed ->
                    if (typed.isNotBlank()) onOp(panelId, PanelOp("add", row.blockId, text = typed))
                } else null
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val checks = r as PanelFace.Row.Checks
                    label.text = checks.label.ifBlank { "List" } + " · ${checks.left} left"
                    label.visibility = View.VISIBLE
                    checks.items.forEachIndexed { i, item ->
                        val line = lines.getOrNull(i) ?: return@forEachIndexed
                        val words = (item.qty?.let { "$it × " } ?: "") + item.text
                        line.text = (if (item.checked) "☑  " else "☐  ") + words
                        line.setTextColor(if (item.checked) INK_DIM else INK)
                        line.contentDescription = "$words, " + if (item.checked) "done" else "not done"
                    }
                    add?.let { it.hint = "Add to ${checks.label.ifBlank { "the list" }}" }
                }
                bind
            }
            is PanelFace.Row.Count -> {
                val label = text(13f, INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                val number = text(16f, INK, bold = true).apply { gravity = Gravity.CENTER }
                val minus = button("−") { onOp(panelId, PanelOp("dec", row.blockId)) }
                val plus = button("+") { onOp(panelId, PanelOp("inc", row.blockId)) }
                body.addView(LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(minus, LinearLayout.LayoutParams(dp(44), dp(40)))
                    addView(number, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(plus, LinearLayout.LayoutParams(dp(44), dp(40)))
                }, rowParams(top = 6))
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val count = r as PanelFace.Row.Count
                    label.text = count.label
                    number.text = count.goal?.let { "${count.count} / $it" } ?: count.count.toString()
                    val goal = count.goal
                    number.setTextColor(if (goal != null && count.count >= goal) TONE_DONE else INK)
                    minus.isEnabled = count.count > count.min
                    plus.isEnabled = count.count < count.max
                    minus.contentDescription = "${count.label} down"
                    plus.contentDescription = "${count.label} up"
                }
                bind
            }
            is PanelFace.Row.Clock -> {
                val label = text(13f, INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                // A running timer counts down by itself: nothing redraws the card each second.
                val clock = Chronometer(appContext).apply {
                    isCountDown = true
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(INK)
                }
                val still = text(18f, INK)
                var current: PanelFace.Row.Clock = row
                val toggle = button("Start") {
                    // Start also resumes a paused timer from where it stopped.
                    onOp(panelId, PanelOp(if (current.running) "pause" else "start", current.blockId))
                }
                val reset = button("Reset") { onOp(panelId, PanelOp("reset", current.blockId)) }
                body.addView(label, rowParams(top = 6))
                body.addView(LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(clock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(still, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(toggle, LinearLayout.LayoutParams(dp(84), dp(40)))
                    addView(reset, LinearLayout.LayoutParams(dp(84), dp(40)).apply { marginStart = dp(6) })
                }, rowParams())
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val c = r as PanelFace.Row.Clock
                    current = c
                    label.text = c.label
                    label.visibility = if (c.label.isBlank()) View.GONE else View.VISIBLE
                    val ends = c.endsAtMs
                    if (ends != null) {
                        clock.base = SystemClock.elapsedRealtime() + (ends - System.currentTimeMillis())
                        clock.start()
                        clock.visibility = View.VISIBLE
                        still.visibility = View.GONE
                    } else {
                        clock.stop()
                        clock.visibility = View.GONE
                        still.visibility = View.VISIBLE
                        still.text = if (c.done) "Done" else PanelBind.clock(c.leftMs)
                        still.setTextColor(if (c.done) TONE_DONE else INK)
                    }
                    toggle.text = when {
                        c.running -> "Pause"
                        c.done -> "Again"
                        else -> "Start"
                    }
                    toggle.contentDescription = "${toggle.text} ${c.label}".trim()
                }
                bind
            }
            is PanelFace.Row.Bar -> {
                val label = text(13f, INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                val number = text(13f, INK)
                val bar = ProgressBar(appContext, null, android.R.attr.progressBarStyleHorizontal).apply { max = BAR_STEPS }
                body.addView(LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(number)
                }, rowParams(top = 6))
                body.addView(bar, rowParams())
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val b = r as PanelFace.Row.Bar
                    label.text = b.label
                    number.text = "${trim(b.done)} / ${trim(b.total)}"
                    bar.progress = Math.round(b.fraction * BAR_STEPS)
                    bar.contentDescription = "${b.label} ${number.text}"
                }
                bind
            }
            is PanelFace.Row.Memo -> {
                val label = text(13f, INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                body.addView(label, rowParams(top = 6))
                val field = entry(row.placeholder, multiLine = true) { typed -> onOp(panelId, PanelOp("set", row.blockId, text = typed)) }
                val bind: (PanelFace.Row) -> Unit = { r ->
                    val memo = r as PanelFace.Row.Memo
                    label.text = memo.label
                    label.visibility = if (memo.label.isBlank()) View.GONE else View.VISIBLE
                    field.hint = memo.placeholder
                    // The user's typing wins over a value stored meanwhile.
                    if (editing !== field && field.text.toString() != memo.text) field.setText(memo.text)
                }
                bind
            }
        }

        /**
         * A text field on the card. Tapping it lets the window take focus;
         * Done (or the keyboard's action) hands the text to [done] and gives
         * focus back. An add field clears itself; a note keeps its text.
         */
        private fun entry(hint: String, multiLine: Boolean, done: (String) -> Unit): EditText {
            val field = EditText(appContext).apply {
                this.hint = hint
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(INK)
                setHintTextColor(INK_DIM)
                background = rounded(FIELD, OUTLINE, 12f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isFocusableInTouchMode = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                imeOptions = EditorInfo.IME_ACTION_DONE
                if (multiLine) { minLines = 2; maxLines = 5 } else maxLines = 1
            }
            fun finish() {
                if (editing !== field) return
                done(field.text.toString())
                if (!multiLine) field.setText("")
                field.clearFocus()
                focus(null)
            }
            field.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP && editing !== field) focus(field)
                view.performClick()
                false
            }
            field.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { finish(); true } else false
            }
            val save = text(13f, TONE_DONE, bold = true).apply {
                text = "Done"
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { finish() }
            }
            body.addView(LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(save, LinearLayout.LayoutParams(dp(56), dp(40)))
            }, rowParams())
            return field
        }

        private fun button(label: String, tap: () -> Unit) = text(15f, INK, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            background = rounded(FIELD, OUTLINE, 12f)
            isClickable = true
            setOnClickListener { tap() }
        }

        private fun rowParams(top: Int = 4) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }
    }

    /** Dragging a card's title row moves the whole stack up or down. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragWith(handle: View) {
        var lastY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    moveBy((event.rawY - lastY).toInt())
                    lastY = event.rawY
                    true
                }
                else -> false
            }
        }
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(appContext).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun tone(tone: Tone, neutral: Int): Int = when (tone) {
        Tone.NEUTRAL -> neutral
        Tone.GOOD -> TONE_DONE
        Tone.WARN -> TONE_WARN
        Tone.BAD -> TONE_ERROR
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, appContext.resources.displayMetrics)
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), appContext.resources.displayMetrics,
    ).toInt()

    companion object {
        const val CARD_WIDTH_DP = 340
        /** Above the navigation bar: clears a launcher's dock row. */
        const val BOTTOM_DP = 112
        private const val BAR_STEPS = 1_000
        /** The same title as the control overlay: the accessibility screenshot leaves it out. */
        const val WINDOW_TITLE = "AndroidAgentControl"

        private val GLASS = Color.argb(235, 18, 18, 18)
        private val INK = Color.parseColor("#F2F2F2")
        private val INK_DIM = Color.parseColor("#BDBDBD")
        private val FIELD = Color.parseColor("#202020")
        private val OUTLINE = Color.parseColor("#383838")
        private val TONE_DONE = Color.parseColor("#78E2B4")
        private val TONE_WARN = Color.parseColor("#F6B86A")
        private val TONE_ERROR = Color.parseColor("#FFB4AB")

        /** A slider's number while it is dragged: no trailing zeros. */
        internal fun trim(value: Double): String =
            if (value == Math.rint(value)) value.toLong().toString()
            else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    }
}
