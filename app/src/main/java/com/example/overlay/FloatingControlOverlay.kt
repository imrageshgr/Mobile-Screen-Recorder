package com.example.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R

/**
 * SideSliderOverlay
 * True Edge Slide Menu for screen recording control.
 *
 * Designed specifically to replace intrusive floating bubbles:
 * 1. FLUSH EDGE ANCHOR: Stays strictly pinned to the screen edge (right margin by default).
 *    Cannot wander or float into the center of the screen.
 * 2. COLLAPSED HANDLE: A slim, translucent vertical edge tab (width 14dp, height 70dp).
 *    Rests flush against the edge, completely unobtrusive during games/movies.
 * 3. SLIDE-OUT DRAWER: Tapping or sliding from the edge tab smoothly slides out the control bar:
 *    - Live Recording Timer (00:00)
 *    - Pause / Resume toggle button
 *    - Prominent Stop & Save button (instant finalization)
 *    - Short Clip button (extracts current moment into ShortClips folder)
 *    - Collapse chevron (›) to tuck the bar back to the edge
 * 4. INSTANT CLEANUP: When Stop & Save is tapped, immediately detaches from WindowManager
 *    and unregisters all callbacks so it never reappears or loops.
 */
class FloatingControlOverlay(
    private val context: Context,
    private val onPauseClick: () -> Unit = {},
    private val onResumeClick: () -> Unit = {},
    private val onStopClick: () -> Unit = {},
    private val onEndClick: () -> Unit = {},
    private val onShortClipClick: () -> Unit = {}
) {
    // Secondary constructor for compatibility
    constructor(
        context: Context,
        micController: Any?,
        onStopClick: () -> Unit,
        onPauseResumeClick: () -> Unit
    ) : this(
        context = context,
        onPauseClick = onPauseResumeClick,
        onResumeClick = onPauseResumeClick,
        onStopClick = onStopClick,
        onEndClick = onStopClick,
        onShortClipClick = {}
    )

    companion object {
        private const val TAG = "SideSliderOverlay"
        private const val IDLE_AUTO_COLLAPSE_MS = 4000L
        private const val DOCKED_ALPHA = 0.40f
        private const val EXPANDED_ALPHA = 0.96f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var rootView: FrameLayout? = null
    private var edgeSliderHandle: LinearLayout? = null
    private var sliderExpandedBar: LinearLayout? = null

    private var handleTimerText: TextView? = null
    private var expandedTimerText: TextView? = null
    private var pauseResumeBtn: ImageView? = null
    private var stopSaveBtn: ImageView? = null
    private var shortClipBtn: ImageView? = null
    private var collapseChevron: TextView? = null

    private var isPaused = false
    private var isSaving = false
    private var isExpanded = false
    @Volatile
    private var isAttached = false
    private var isOnRightEdge = true

    private lateinit var layoutParams: WindowManager.LayoutParams

    private var initialY = 0
    private var initialTouchY = 0f
    private var isVerticalDragging = false

    private val autoCollapseRunnable = Runnable {
        collapseToEdge()
    }

    fun show() {
        if (isAttached) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show side slider: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        mainHandler.post {
            try {
                if (isAttached) return@post
                buildViewHierarchy()
                windowManager.addView(rootView, layoutParams)
                isAttached = true
                resetAutoCollapseTimer()
                Log.d(TAG, "Side slider overlay docked to screen edge")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach side slider overlay: ${e.message}", e)
            }
        }
    }

    fun dismiss() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!isAttached && rootView == null) return

        mainHandler.post {
            try {
                if (isAttached && rootView != null) {
                    windowManager.removeView(rootView)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error detaching side slider overlay: ${e.message}")
            } finally {
                rootView = null
                edgeSliderHandle = null
                sliderExpandedBar = null
                isAttached = false
            }
        }
    }

    fun destroy() {
        dismiss()
    }

    fun updateTimer(timeStr: String, isRecordingPaused: Boolean = false) {
        if (this.isPaused != isRecordingPaused) {
            setPaused(isRecordingPaused)
        }
        mainHandler.post {
            if (!isSaving && isAttached) {
                handleTimerText?.text = timeStr
                expandedTimerText?.text = timeStr
            }
        }
    }

    fun setPaused(paused: Boolean) {
        this.isPaused = paused
        mainHandler.post {
            pauseResumeBtn?.setImageResource(
                if (paused) R.drawable.ic_overlay_play else R.drawable.ic_overlay_pause
            )
            pauseResumeBtn?.contentDescription = if (paused) "Resume Recording" else "Pause Recording"
            wakeFromDock()
        }
    }

    fun notifyClipSaved(clipName: String) {
        mainHandler.post {
            shortClipBtn?.animate()?.scaleX(1.3f)?.scaleY(1.3f)?.setDuration(150)?.withEndAction {
                shortClipBtn?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()
            }?.start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViewHierarchy() {
        val metrics = getDisplayMetrics()
        val screenWidth = metrics.widthPixels
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        isOnRightEdge = true

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - dp(24) // Docked right against edge
            y = dp(180)
        }

        rootView = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        // =============================================================
        // 1. COLLAPSED EDGE TAB (Thin vertical handle flush to edge)
        // =============================================================
        edgeSliderHandle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), dp(6))
            visibility = View.VISIBLE
            alpha = DOCKED_ALPHA
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#EE16161E"))
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
            elevation = dp(6).toFloat()

            // Glowing red record dot
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FF3B30"))
                }
            }
            addView(dot)

            // Timer display
            handleTimerText = TextView(context).apply {
                text = "00:00"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }
            addView(handleTimerText)

            // Inward slide arrow indicator
            val arrow = TextView(context).apply {
                text = " ‹"
                setTextColor(Color.parseColor("#BBFFFFFF"))
                textSize = 15f
                setPadding(dp(2), 0, 0, 0)
            }
            addView(arrow)

            setOnClickListener {
                expandSlider()
            }
        }

        // =============================================================
        // 2. SLIDE-OUT CONTROL DRAWER (Expanded horizontal bar)
        // =============================================================
        sliderExpandedBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            visibility = View.GONE
            alpha = EXPANDED_ALPHA
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F512121A"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            elevation = dp(10).toFloat()

            // Vertical grip dots
            val grip = TextView(context).apply {
                text = "⋮"
                setTextColor(Color.parseColor("#88FFFFFF"))
                textSize = 16f
                setPadding(dp(2), 0, dp(6), 0)
            }
            addView(grip)

            // Live Timer
            expandedTimerText = TextView(context).apply {
                text = "00:00"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setPadding(dp(2), 0, dp(8), 0)
            }
            addView(expandedTimerText)

            // BUTTON 1: Pause / Resume Button
            pauseResumeBtn = createSliderButton(
                iconRes = if (isPaused) R.drawable.ic_overlay_play else R.drawable.ic_overlay_pause,
                tintColor = Color.WHITE,
                bgColor = Color.parseColor("#282834"),
                contentDesc = "Pause or Resume"
            ) {
                if (isSaving) return@createSliderButton
                if (isPaused) {
                    onResumeClick()
                } else {
                    onPauseClick()
                }
                resetAutoCollapseTimer()
            }
            addView(pauseResumeBtn)

            // BUTTON 2: Stop & Save (Prominent Red Button)
            stopSaveBtn = createSliderButton(
                iconRes = R.drawable.ic_overlay_stop,
                tintColor = Color.WHITE,
                bgColor = Color.parseColor("#E02424"),
                contentDesc = "Stop and Save Recording"
            ) {
                triggerFinishAndSave()
            }
            addView(stopSaveBtn)

            // BUTTON 3: Short Clip Button (Golden highlight icon)
            shortClipBtn = createSliderButton(
                iconRes = R.drawable.ic_overlay_clip,
                tintColor = Color.parseColor("#FFD60A"),
                bgColor = Color.parseColor("#2C2A1E"),
                contentDesc = "Take Short Clip"
            ) {
                if (isSaving) return@createSliderButton
                onShortClipClick()
                resetAutoCollapseTimer()
            }
            addView(shortClipBtn)

            // BUTTON 4: Slide back chevron (›)
            collapseChevron = TextView(context).apply {
                text = "›"
                setTextColor(Color.parseColor("#9E9EA4"))
                textSize = 22f
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener {
                    collapseToEdge()
                }
            }
            addView(collapseChevron)
        }

        rootView?.addView(edgeSliderHandle)
        rootView?.addView(sliderExpandedBar)

        // Vertical sliding along the screen edge (stays flush to edge)
        val edgeTouchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = layoutParams.y
                    initialTouchY = event.rawY
                    isVerticalDragging = false
                    mainHandler.removeCallbacks(autoCollapseRunnable)
                    wakeFromDock()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dy) > touchSlop) {
                        isVerticalDragging = true
                        layoutParams.y = initialY + dy
                        clampVerticalPosition()
                        try {
                            if (isAttached && rootView != null) {
                                windowManager.updateViewLayout(rootView, layoutParams)
                            }
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isVerticalDragging && !isExpanded) {
                        expandSlider()
                    }
                    resetAutoCollapseTimer()
                    true
                }
                else -> false
            }
        }

        edgeSliderHandle?.setOnTouchListener(edgeTouchListener)
    }

    private fun triggerFinishAndSave() {
        if (isSaving) return
        isSaving = true
        mainHandler.removeCallbacks(autoCollapseRunnable)

        expandedTimerText?.text = "Saving..."
        stopSaveBtn?.alpha = 0.5f
        pauseResumeBtn?.alpha = 0.5f
        shortClipBtn?.alpha = 0.5f

        // 1. Instantly dismiss the overlay completely so it never lingers or reappears
        dismiss()

        // 2. Trigger the stop and save in service
        onStopClick()
    }

    private fun createSliderButton(
        iconRes: Int,
        tintColor: Int,
        bgColor: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): ImageView {
        val density = context.resources.displayMetrics.density
        val btnSize = (40 * density).toInt()
        val iconPad = (8 * density).toInt()

        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            setPadding(iconPad, iconPad, iconPad, iconPad)
            setImageResource(iconRes)
            setColorFilter(tintColor)
            contentDescription = contentDesc
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }

            setOnClickListener {
                wakeFromDock()
                onClick()
            }
        }
    }

    private fun expandSlider() {
        isExpanded = true
        edgeSliderHandle?.visibility = View.GONE
        sliderExpandedBar?.visibility = View.VISIBLE
        sliderExpandedBar?.alpha = EXPANDED_ALPHA
        wakeFromDock()

        val metrics = getDisplayMetrics()
        val screenWidth = metrics.widthPixels
        val density = context.resources.displayMetrics.density
        val barWidth = (290 * density).toInt()

        // Keep pinned strictly to the right screen edge
        layoutParams.x = (screenWidth - barWidth).coerceAtLeast(0)

        try {
            if (isAttached && rootView != null) {
                windowManager.updateViewLayout(rootView, layoutParams)
            }
        } catch (_: Exception) {}

        resetAutoCollapseTimer()
    }

    private fun collapseToEdge() {
        isExpanded = false
        sliderExpandedBar?.visibility = View.GONE
        edgeSliderHandle?.visibility = View.VISIBLE
        edgeSliderHandle?.alpha = DOCKED_ALPHA

        val metrics = getDisplayMetrics()
        val screenWidth = metrics.widthPixels
        val density = context.resources.displayMetrics.density
        val handleWidth = (80 * density).toInt()

        layoutParams.x = (screenWidth - handleWidth).coerceAtLeast(0)

        try {
            if (isAttached && rootView != null) {
                windowManager.updateViewLayout(rootView, layoutParams)
            }
        } catch (_: Exception) {}
    }

    private fun wakeFromDock() {
        edgeSliderHandle?.alpha = 0.95f
        sliderExpandedBar?.alpha = EXPANDED_ALPHA
        resetAutoCollapseTimer()
    }

    private fun resetAutoCollapseTimer() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, IDLE_AUTO_COLLAPSE_MS)
    }

    private fun clampVerticalPosition() {
        val metrics = getDisplayMetrics()
        val maxY = metrics.heightPixels - (120 * context.resources.displayMetrics.density).toInt()
        layoutParams.y = layoutParams.y.coerceIn(80, maxY.coerceAtLeast(80))
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
