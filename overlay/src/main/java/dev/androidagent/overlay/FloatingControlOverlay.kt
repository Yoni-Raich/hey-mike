package dev.androidagent.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.androidagent.core.ControlOverlay
import dev.androidagent.core.OverlayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Native floating controls used for the full lifetime of an agent run.
 *
 * A pill under the status bar says what the agent is doing right now, with
 * Stop always on it. A tap grows it into a card with the agent's latest words
 * and a field to steer; another tap on the pill shrinks it back. The pill is
 * the only touchable window, so other apps keep receiving their own input
 * outside its bounds.
 * There is no AccessibilityService dependency here; device actions stay in
 * the core gateways.
 */
class FloatingControlOverlay(
    context: Context,
    private val onStop: () -> Unit,
    private val onSend: (String) -> Unit,
    private val onOpenApp: () -> Unit,
) : ControlOverlay {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var controlRoot: FrameLayout? = null
    private var card: LinearLayout? = null
    private var header: LinearLayout? = null
    private var body: LinearLayout? = null
    private var headlineView: TextView? = null
    private var commentaryView: TextView? = null
    private var steerRow: View? = null
    private var approveButton: View? = null
    private var orbView: OverlayOrbView? = null
    private var inputView: EditText? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var configCallbacks: ComponentCallbacks? = null
    private var attachListener: View.OnAttachStateChangeListener? = null
    private var morph: ValueAnimator? = null
    private var showing = false
    private var inputFocusEnabled = false
    private var captureHidden = false
    private var finishRunnable: Runnable? = null
    private var appForeground = false
    private var collapsed = true
    private var runActive = false
    private var currentStatus = "Ready"
    private var content: OverlayContent? = null

    // Horizontal centre the pill keeps as it grows and shrinks, in screen px.
    // Null until the user drags it, which means "centred on the screen".
    private var anchorX: Int? = null
    private var imeBottomInsetPx = 0

    override suspend fun show(status: String) {
        requireOverlayPermission()
        withContext(Dispatchers.Main.immediate) {
            // Check again on the UI thread immediately before addView. This
            // keeps permission denial ahead of any visible/device action.
            requireOverlayPermission()
            currentStatus = status.ifBlank { "Ready" }
            if (!runActive) {
                // Every run starts as the pill, with nothing said yet.
                collapsed = true
                content = null
            }
            runActive = true
            if (appForeground) {
                // The app owns the foreground surface, so keep the run state
                // without placing a window over the app. It will be rebuilt
                // when the user leaves the app.
                removeViews()
                return@withContext
            }

            finishRunnable?.let(mainHandler::removeCallbacks)
            finishRunnable = null
            addViewsIfNeeded()
            val control = controlRoot ?: error("Overlay controls were not created")
            try {
                waitForAttach(control)
            } catch (error: Throwable) {
                removeViews()
                throw error
            }
        }
    }

    override fun update(status: String) {
        runOnMain {
            currentStatus = status.ifBlank { "Ready" }
            applyStatus(currentStatus)
        }
    }

    override fun finish(state: OverlayState) {
        runOnMain {
            finishRunnable?.let(mainHandler::removeCallbacks)
            finishRunnable = null
            currentStatus = state.label
            val hadControl = runActive
            runActive = false
            if (!hadControl) { removeViews(); return@runOnMain }
            if (appForeground) {
                removeViews()
                return@runOnMain
            }
            applyStatus(currentStatus)
            if (!showing) {
                removeViews()
                openAppAfterFinish()
                return@runOnMain
            }
            val callback = Runnable {
                finishRunnable = null
                removeViews()
                openAppAfterFinish()
            }
            finishRunnable = callback
            mainHandler.postDelayed(callback, FINISH_DISPLAY_MS)
        }
    }

    override fun hide() {
        runOnMain {
            runActive = false
            removeViews()
        }
    }

    /**
     * Keeps the run alive while the app owns the foreground window. The
     * contract has a default implementation in core so other overlays can
     * ignore this lifecycle hint.
     */
    fun setAppForeground(foreground: Boolean) {
        runOnMain {
            if (appForeground == foreground) return@runOnMain
            appForeground = foreground
            if (foreground) {
                // Removing the window makes the foreground app completely
                // unobstructed and also removes it from capture surfaces.
                removeViews()
            } else if (runActive) {
                // A run may have started from the app while this flag was
                // true. Restore the latest status as soon as another app is
                // visible. Lifecycle callbacks must not crash the process if
                // permission was revoked while the app was away.
                runCatching {
                    requireOverlayPermission()
                    addViewsIfNeeded()
                }.onFailure { removeViews() }
            }
        }
    }

    /**
     * Temporarily removes the overlay from the captured view without
     * clearing the edit text. Only needed where a capture cannot leave our
     * window out by itself; core restores it straight after.
     */
    override suspend fun setCaptureHidden(hidden: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            val control = controlRoot ?: return@withContext
            if (!control.isAttachedToWindow) waitForAttach(control)
            if (captureHidden == hidden) return@withContext
            if (hidden) {
                // Device actions must not leave the overlay IME focused while
                // the card is hidden from the captured surface.
                disableInputFocus()
                hideKeyboard()
            }
            captureHidden = hidden
            val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            // Visibility preserves EditText contents and the current focus
            // state while making the window absent from a screenshot.
            control.visibility = visibility
            if (!hidden) clampPosition()
        }
    }

    /** Move the pill away when a planned device tap would hit it. */
    override fun avoidTouch(x: Int, y: Int) {
        runOnMain {
            val control = controlRoot ?: return@runOnMain
            val lp = controlParams ?: return@runOnMain
            if (!control.isAttachedToWindow || captureHidden) return@runOnMain

            val width = lp.width.takeIf { it > 0 } ?: control.width.takeIf { it > 0 } ?: windowWidthPx()
            val height = control.height.takeIf { it > 0 } ?: dp(HEADER_DP + ROOT_PAD_DP * 2)
            val bounds = screenBounds()
            if (!pointInside(lp.x, lp.y, width, height, x, y)) return@runOnMain

            val margin = dp(12)
            val maxX = (bounds.width() - width - margin).coerceAtLeast(margin)
            val maxY = bottomLimit(bounds, height, margin)
            val currentX = lp.x
            val currentY = lp.y
            val candidates = listOf(
                currentX.coerceIn(margin, maxX) to
                    (if (currentY > bounds.height() / 2) margin else maxY),
                (if (currentX > bounds.width() / 2) margin else maxX) to
                    currentY.coerceIn(margin, maxY),
                margin to margin,
                maxX to maxY,
            )
            val destination = candidates.firstOrNull { (candidateX, candidateY) ->
                !pointInside(candidateX, candidateY, width, height, x, y)
            } ?: return@runOnMain
            lp.x = destination.first
            lp.y = destination.second
            anchorX = lp.x + width / 2
            updateControlLayout(control, lp)
        }
    }

    // ---------- view construction; main thread only ----------

    private fun addViewsIfNeeded() {
        if (showing && controlRoot != null) {
            applyStatus(currentStatus)
            return
        }
        if (controlRoot != null || controlParams != null) removeViews()
        buildViews()
        val control = controlRoot ?: error("Overlay controls were not created")
        val controlLayout = controlParams ?: error("Overlay control parameters were not created")
        try {
            windowManager.addView(control, controlLayout)
            showing = true
            registerConfigurationCallbacks()
            applyStatus(currentStatus)
        } catch (error: Throwable) {
            removeViews()
            throw error
        }
    }

    private fun buildViews() {
        val root = FrameLayout(appContext).apply {
            // The window owns the shadow halo. Keep padding stable so IME
            // insets never create an invisible, touch-blocking strip.
            val pad = dp(ROOT_PAD_DP)
            setPadding(pad, pad, pad, pad)
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val orb = OverlayOrbView(appContext)
        orbView = orb
        val headline = TextView(appContext).apply {
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = false
            isFocusable = false
        }
        headlineView = headline
        val stopButton = circleButton(
            description = "Stop run",
            diameterDp = 36,
            fill = INK,
            stroke = Color.TRANSPARENT,
            icon = ActionIcon.STOP,
            tint = ON_LIGHT,
            iconDp = 13,
        ) {
            // Release any IME focus before the immediate local stop callback.
            disableInputFocus()
            hideKeyboard()
            onStop()
        }
        val headerRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(8), 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { setCollapsed(!collapsed) }
            addView(orb, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
            addView(headline, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        header = headerRow
        // The pill is also its own drag handle; a tap without movement toggles it.
        attachDrag(headerRow)

        val commentary = TextView(appContext).apply {
            setTextColor(INK_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.3f)
            includeFontPadding = false
            // Room for the agent's own message, not just a status line.
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPaddingRelative(dp(16), dp(2), dp(16), 0)
            visibility = View.GONE
        }
        commentaryView = commentary

        val openButton = circleButton(
            description = "Open Hey Mike",
            diameterDp = 40,
            fill = Color.TRANSPARENT,
            stroke = OUTLINE,
            icon = ActionIcon.OPEN,
            tint = INK,
            iconDp = 18,
        ) {
            // Release focus before handing control back to the app window.
            disableInputFocus()
            hideKeyboard()
            onOpenApp()
        }
        val input = EditText(appContext).apply {
            hint = "Steer or reply"
            setHintTextColor(HINT)
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 1
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            background = rounded(fill = FIELD, stroke = OUTLINE, radiusDp = 22f)
            setPaddingRelative(dp(16), 0, dp(16), 0)
            contentDescription = "Steer or reply"
            setOnClickListener { enableInputFocus() }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) enableInputFocus()
                false
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendFromInput()
                    true
                } else {
                    false
                }
            }
        }
        inputView = input
        val sendButton = circleButton(
            description = "Send message",
            diameterDp = 40,
            fill = INK,
            stroke = Color.TRANSPARENT,
            icon = ActionIcon.ARROW_UP,
            tint = ON_LIGHT,
            iconDp = 20,
        ) {
            sendFromInput()
        }
        val steer = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            addView(openButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(input, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
            addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        steerRow = steer

        // Shown instead of the steer field while an approval is waiting: the
        // approval card lives only in the app.
        val approve = TextView(appContext).apply {
            text = "Review in Hey Mike"
            setTextColor(APPROVE_INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(fill = APPROVE, stroke = Color.TRANSPARENT, radiusDp = 22f)
            foreground = ripple(APPROVE_INK, rounded(Color.WHITE, Color.TRANSPARENT, 22f))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                disableInputFocus()
                hideKeyboard()
                onOpenApp()
            }
        }
        approveButton = approve

        val bodyColumn = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(commentary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(steer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(approve, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        body = bodyColumn

        val cardView = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(fill = GLASS, stroke = GLASS_EDGE, radiusDp = CARD_RADIUS_DP)
            // Clip to the rounded card so the body can grow out of the pill.
            clipToOutline = true
            elevation = dpF(12f)
            isClickable = true
            isFocusable = false
            addView(headerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(HEADER_DP)))
            addView(bodyColumn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        card = cardView
        root.addView(
            cardView,
            FrameLayout.LayoutParams(cardWidthPx(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomInset = maxOf(ime.bottom, bars.bottom)
            // Keep the card's measured size stable and use the inset only as
            // a positioning bound while the keyboard is visible.
            if (imeBottomInsetPx != bottomInset) {
                imeBottomInsetPx = bottomInset
                runOnMain { resizeControlWindow() }
            }
            insets
        }

        controlRoot = root
        inputFocusEnabled = false
        captureHidden = false

        controlParams = WindowManager.LayoutParams(
            windowWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // x is an absolute screen coordinate, also on Hebrew/RTL devices.
            gravity = Gravity.TOP or Gravity.LEFT
            y = defaultTopPx()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            token = null
            // The accessibility screenshot recognises our window by this title.
            this.title = WINDOW_TITLE
        }.also(::positionFromAnchor)
        updateHeaderDescription()
    }

    /** A round button with a 48dp touch target around a smaller visible disc. */
    private fun circleButton(
        description: String,
        diameterDp: Int,
        fill: Int,
        stroke: Int,
        icon: ActionIcon,
        tint: Int,
        iconDp: Int,
        action: () -> Unit,
    ): FrameLayout = FrameLayout(appContext).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        addView(
            View(appContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(fill)
                    if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(dp(diameterDp), dp(diameterDp), Gravity.CENTER),
        )
        addView(
            ActionIconView(appContext, icon, tint).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER),
        )
        foreground = ripple(if (fill == INK) ON_LIGHT else INK, ShapeDrawable(OvalShape()))
        setOnClickListener { action() }
    }

    private fun attachDrag(handle: View) {
        val slop = ViewConfiguration.get(appContext).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        handle.setOnTouchListener { _, event ->
            val lp = controlParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        clampPosition(lp)
                        anchorX = lp.x + lp.width / 2
                        updateControlLayout(controlRoot, lp)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val moved = dragging
                    dragging = false
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    moved
                }
                else -> false
            }
        }
    }

    private fun sendFromInput() {
        val input = inputView ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        input.setText("")
        hideKeyboard()
        disableInputFocus()
        onSend(text)
    }

    private fun enableInputFocus() {
        runOnMain {
            val root = controlRoot ?: return@runOnMain
            val input = inputView ?: return@runOnMain
            val lp = controlParams ?: return@runOnMain
            if (!showing || captureHidden) return@runOnMain
            if (!inputFocusEnabled) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                updateControlLayout(root, lp)
                inputFocusEnabled = true
            }
            input.requestFocus()
            input.post {
                val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun disableInputFocus() {
        val root = controlRoot ?: return
        val input = inputView
        input?.clearFocus()
        val lp = controlParams ?: return
        if (inputFocusEnabled || lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            inputFocusEnabled = false
            updateControlLayout(root, lp)
        }
    }

    // ---------- pill and card ----------

    private fun setCollapsed(value: Boolean) {
        disableInputFocus()
        hideKeyboard()
        if (collapsed == value) return
        collapsed = value
        val cardView = card ?: return
        val bodyView = body ?: return
        val lp = controlParams ?: return
        val previous = morph
        morph = null
        previous?.cancel()
        if (!showing || !ValueAnimator.areAnimatorsEnabled()) {
            settleLayout()
            return
        }

        val panel = panelWidthPx()
        val fromWidth = cardView.width.takeIf { it > 0 } ?: cardView.layoutParams.width
        val toWidth = cardWidthPx()
        bodyView.visibility = View.VISIBLE
        bodyView.measure(
            View.MeasureSpec.makeMeasureSpec(panel, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullBody = bodyView.measuredHeight
        val fromBody = if (value) bodyView.height.takeIf { it > 0 } ?: fullBody else 0
        val toBody = if (value) 0 else fullBody

        // Hold the window at the card's largest size while the card changes
        // shape inside it, so the window manager relayouts twice, not per frame.
        lp.width = panel + dp(ROOT_PAD_DP) * 2
        lp.height = dp(HEADER_DP) + fullBody + dp(ROOT_PAD_DP) * 2
        positionFromAnchor(lp)
        updateControlLayout(controlRoot, lp)
        setBodyHeight(bodyView, fromBody)

        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_MS
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                cardView.layoutParams = cardView.layoutParams.also { it.width = lerp(fromWidth, toWidth, progress) }
                setBodyHeight(bodyView, lerp(fromBody, toBody, progress))
                // The body fades out quickly on the way in to the pill and
                // arrives late on the way out, so text never squeezes visibly.
                bodyView.alpha = if (value) {
                    (1f - progress / 0.5f).coerceIn(0f, 1f)
                } else {
                    ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (morph !== animation) return
                    morph = null
                    settleLayout()
                }
            })
            start()
        }
    }

    /** Final sizes for the current state, with the window fitted to the card. */
    private fun settleLayout() {
        val cardView = card ?: return
        val bodyView = body ?: return
        val lp = controlParams ?: return
        cardView.layoutParams = cardView.layoutParams.also { it.width = cardWidthPx() }
        setBodyHeight(bodyView, LinearLayout.LayoutParams.WRAP_CONTENT)
        bodyView.alpha = 1f
        bodyView.visibility = if (collapsed) View.GONE else View.VISIBLE
        lp.width = windowWidthPx()
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        positionFromAnchor(lp)
        updateControlLayout(controlRoot, lp)
        updateHeaderDescription()
        controlRoot?.post { clampPosition() }
    }

    private fun setBodyHeight(bodyView: View, height: Int) {
        bodyView.layoutParams = bodyView.layoutParams.also { it.height = height }
    }

    private fun updateHeaderDescription() {
        val headline = content?.headline ?: currentStatus
        header?.contentDescription = if (collapsed) "Expand agent controls · $headline" else "Collapse agent controls"
    }

    // ---------- window lifecycle ----------

    private fun requireOverlayPermission() {
        if (!Settings.canDrawOverlays(appContext)) {
            throw SecurityException("Overlay permission missing: enable Display over other apps before starting device control.")
        }
    }

    private suspend fun waitForAttach(view: View) {
        if (view.isAttachedToWindow) return
        withTimeout(2_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(attached: View) {
                        attached.removeOnAttachStateChangeListener(this)
                        if (attachListener === this) attachListener = null
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onViewDetachedFromWindow(detached: View) = Unit
                }
                attachListener = listener
                view.addOnAttachStateChangeListener(listener)
                if (view.isAttachedToWindow) {
                    view.removeOnAttachStateChangeListener(listener)
                    if (attachListener === listener) attachListener = null
                    if (continuation.isActive) continuation.resume(Unit)
                }
                continuation.invokeOnCancellation {
                    view.removeOnAttachStateChangeListener(listener)
                    if (attachListener === listener) attachListener = null
                }
            }
        }
    }

    private fun registerConfigurationCallbacks() {
        if (configCallbacks != null) return
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                runOnMain { resizeControlWindow() }
            }

            override fun onLowMemory() = Unit
        }
        configCallbacks = callbacks
        appContext.registerComponentCallbacks(callbacks)
    }

    private fun removeViews() {
        finishRunnable?.let(mainHandler::removeCallbacks)
        finishRunnable = null
        val running = morph
        morph = null
        running?.cancel()
        hideKeyboard()
        disableInputFocus()
        configCallbacks?.let {
            try {
                appContext.unregisterComponentCallbacks(it)
            } catch (_: Exception) {
            }
        }
        configCallbacks = null
        attachListener?.let { listener ->
            controlRoot?.removeOnAttachStateChangeListener(listener)
        }
        attachListener = null
        removeWindow(controlRoot)
        controlRoot = null
        card = null
        header = null
        body = null
        headlineView = null
        commentaryView = null
        steerRow = null
        approveButton = null
        orbView = null
        inputView = null
        controlParams = null
        imeBottomInsetPx = 0
        showing = false
        captureHidden = false
    }

    private fun openAppAfterFinish() {
        if (!appForeground) onOpenApp()
    }

    private fun removeWindow(view: View?) {
        if (view == null) return
        try {
            if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
        } catch (_: Exception) {
        }
    }

    private fun hideKeyboard() {
        val input = inputView ?: return
        try {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun updateControlLayout(
        root: View?,
        lp: WindowManager.LayoutParams,
    ) {
        if (!showing || root == null || !root.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (_: IllegalArgumentException) {
        } catch (_: Exception) {
        }
    }

    private fun clampPosition() {
        val lp = controlParams ?: return
        clampPosition(lp)
        updateControlLayout(controlRoot, lp)
    }

    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val root = controlRoot
        val width = lp.width.takeIf { it > 0 } ?: root?.width?.takeIf { it > 0 } ?: windowWidthPx()
        val height = root?.height?.takeIf { it > 0 } ?: dp(HEADER_DP + ROOT_PAD_DP * 2)
        val bounds = screenBounds()
        val margin = dp(4)
        lp.x = lp.x.coerceIn(margin, (bounds.width() - width - margin).coerceAtLeast(margin))
        lp.y = lp.y.coerceIn(margin, bottomLimit(bounds, height, margin))
    }

    /** Centre the window on the pill's anchor, which survives every resize. */
    private fun positionFromAnchor(lp: WindowManager.LayoutParams) {
        val centre = anchorX ?: (screenBounds().width() / 2)
        lp.x = centre - lp.width / 2
        clampPosition(lp)
    }

    private fun screenBounds() = windowManager.currentWindowMetrics.bounds

    /** Just under the status bar, where the pill is out of the way of most content. */
    private fun defaultTopPx(): Int {
        val statusBar = runCatching {
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.statusBars()).top
        }.getOrDefault(0)
        return statusBar + dp(8) - dp(ROOT_PAD_DP)
    }

    private fun panelWidthPx(): Int = minOf(
        dp(PANEL_WIDTH_DP),
        (screenBounds().width() - dp(16)).coerceAtLeast(dp(1)),
    )

    private fun cardWidthPx(): Int = if (collapsed) minOf(dp(PILL_WIDTH_DP), panelWidthPx()) else panelWidthPx()

    private fun windowWidthPx(): Int = cardWidthPx() + dp(ROOT_PAD_DP) * 2

    private fun bottomLimit(bounds: android.graphics.Rect, height: Int, margin: Int): Int =
        (bounds.height() - imeBottomInsetPx - height - margin).coerceAtLeast(margin)

    private fun resizeControlWindow() {
        if (morph != null) return
        val lp = controlParams ?: return
        card?.let { cardView -> cardView.layoutParams = cardView.layoutParams.also { it.width = cardWidthPx() } }
        lp.width = windowWidthPx()
        positionFromAnchor(lp)
        updateControlLayout(controlRoot, lp)
    }

    private fun pointInside(left: Int, top: Int, width: Int, height: Int, x: Int, y: Int): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    /**
     * The agent's own latest words, as the chat shows them. They outlive the
     * status label: a tool call changes the headline and leaves these on the
     * card until the agent says something new.
     */
    override fun say(text: String) {
        runOnMain {
            val line = oneLine(text)
            if (line.isEmpty() || content?.commentary == line) return@runOnMain
            // Speech wins over whatever the current label carried, including
            // the empty commentary a fresh run starts with.
            applyContent(overlayContent(currentStatus, line).copy(commentary = line))
        }
    }

    private fun applyStatus(status: String) {
        applyContent(overlayContent(status.ifBlank { "Ready" }, content?.commentary))
    }

    private fun applyContent(next: OverlayContent) {
        val headlineChanged = next.headline != content?.headline
        content = next
        headlineView?.text = next.headline
        commentaryView?.apply {
            text = next.commentary.orEmpty()
            visibility = if (next.commentary.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        steerRow?.visibility = if (next.needsApproval) View.GONE else View.VISIBLE
        approveButton?.visibility = if (next.needsApproval) View.VISIBLE else View.GONE
        orbView?.let { orb ->
            orb.setTone(toneColor(next.tone), toneActivity(next.tone))
            if (headlineChanged && next.tone == OverlayTone.CONTROLLING) orb.pulse()
        }
        updateHeaderDescription()
    }

    private fun toneColor(tone: OverlayTone): Int = when (tone) {
        OverlayTone.ACTIVE -> TONE_WORKING
        OverlayTone.CONTROLLING -> TONE_CONTROLLING
        OverlayTone.WAITING, OverlayTone.STOPPING -> TONE_WAITING
        OverlayTone.DONE -> TONE_DONE
        OverlayTone.ERROR -> TONE_ERROR
    }

    // How hard the sphere churns: busiest while acting on the screen.
    private fun toneActivity(tone: OverlayTone): Float = when (tone) {
        OverlayTone.ACTIVE -> 0.28f
        OverlayTone.CONTROLLING -> 0.42f
        OverlayTone.WAITING, OverlayTone.STOPPING -> 0.2f
        OverlayTone.DONE -> 0.12f
        OverlayTone.ERROR -> 0.08f
    }

    // ---------- small visual helpers ----------

    private fun ripple(tint: Int, mask: android.graphics.drawable.Drawable) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(45, Color.red(tint), Color.green(tint), Color.blue(tint))),
        null,
        mask,
    )

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun lerp(from: Int, to: Int, fraction: Float): Int = (from + (to - from) * fraction).toInt()

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        appContext.resources.displayMetrics,
    ).toInt()

    private fun dpF(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        appContext.resources.displayMetrics,
    )

    private companion object {
        const val PANEL_WIDTH_DP = 358
        const val PILL_WIDTH_DP = 248
        const val HEADER_DP = 48
        const val ROOT_PAD_DP = 6
        const val CARD_RADIUS_DP = 24f
        const val MORPH_MS = 320L
        const val FINISH_DISPLAY_MS = 350L

        /** The accessibility screenshot leaves the window with this title out. */
        const val WINDOW_TITLE = "AndroidAgentControl"

        // Always dark, like the app: the same ink, field and outline colours
        // as its composer, on near-black glass.
        val GLASS = Color.argb(235, 18, 18, 18)
        val GLASS_EDGE = Color.argb(23, 255, 255, 255)
        val INK = Color.parseColor("#F2F2F2")
        val INK_DIM = Color.parseColor("#BDBDBD")
        val ON_LIGHT = Color.parseColor("#111111")
        val FIELD = Color.parseColor("#202020")
        val OUTLINE = Color.parseColor("#383838")
        val HINT = Color.parseColor("#8A8A8A")
        val APPROVE = Color.parseColor("#F6B86A")
        val APPROVE_INK = Color.parseColor("#2A1A05")
        val TONE_WORKING = Color.parseColor("#83D9CA")
        val TONE_CONTROLLING = Color.parseColor("#69A7FF")
        val TONE_WAITING = Color.parseColor("#F6B86A")
        val TONE_DONE = Color.parseColor("#78E2B4")
        val TONE_ERROR = Color.parseColor("#FFB4AB")
    }

    private enum class ActionIcon {
        ARROW_UP,
        OPEN,
        STOP,
    }

    /** Icons drawn on a 24-unit grid, scaled to the view. */
    private class ActionIconView(
        context: Context,
        private val icon: ActionIcon,
        tint: Int,
    ) : View(context) {
        private val path = Path()
        private val rect = RectF()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = minOf(width, height) / 24f
            stroke.strokeWidth = (if (icon == ActionIcon.ARROW_UP) 2.2f else 1.9f) * unit
            fun x(value: Float) = value * unit
            when (icon) {
                ActionIcon.ARROW_UP -> {
                    path.reset()
                    path.moveTo(x(12f), x(19f))
                    path.lineTo(x(12f), x(5f))
                    path.moveTo(x(6f), x(11f))
                    path.lineTo(x(12f), x(5f))
                    path.lineTo(x(18f), x(11f))
                    canvas.drawPath(path, stroke)
                }
                ActionIcon.OPEN -> {
                    path.reset()
                    path.moveTo(x(14f), x(4f))
                    path.lineTo(x(20f), x(4f))
                    path.lineTo(x(20f), x(10f))
                    path.moveTo(x(20f), x(4f))
                    path.lineTo(x(11f), x(13f))
                    path.moveTo(x(18f), x(14f))
                    path.lineTo(x(18f), x(20f))
                    path.lineTo(x(4f), x(20f))
                    path.lineTo(x(4f), x(6f))
                    path.lineTo(x(10f), x(6f))
                    canvas.drawPath(path, stroke)
                }
                ActionIcon.STOP -> {
                    rect.set(x(1f), x(1f), x(23f), x(23f))
                    canvas.drawRoundRect(rect, x(5f), x(5f), fill)
                }
            }
        }
    }
}
