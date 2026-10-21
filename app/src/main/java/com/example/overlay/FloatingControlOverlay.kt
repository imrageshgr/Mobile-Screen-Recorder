package com.example.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.R
import com.example.audio.MicrophoneController
import com.example.audio.VoiceMode

/**
 * Premium floating control overlay button in dark charcoal & red styling.
 *
 * Keeps pause and stop buttons exactly as before, and introduces:
 * 1. An independent microphone control that switches voice on/off instantly without affecting app audio.
 * 2. Visual state feedback: bright microphone with red accent when voice is recorded, crossed-out gray when muted.
 * 3. A collapsed bubble microphone indicator dot displaying live voice state even when panel is closed.
 * 4. Dual voice modes: "Toggle mode" (tap on/off) and "Hold to talk mode" (records while holding, silent when lifted).
 * 5. Dedicated mode switcher chip that persists user preference.
 * 6. Keeps the panel open and prevents idle fading while holding the mic in Hold to talk mode.
 * 7. Edge-snapping, drag physics, 3.5s idle handle fade, and game-friendly touch targets (>=44-48dp).
 */
class FloatingControlOverlay(
    private val context: Context,
    private val micController: MicrophoneController,
    private val onStopClick: () -> Unit,
    private val onPauseResumeClick: () -> Unit
) {
    companion object {
        private const val TAG = "FloatingControlOverlay"
        private const val IDLE_TIMEOUT_MS = 3500L
        private const val PANEL_COLLAPSE_TIMEOUT_MS = 4000L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: FrameLayout? = null
    private var pillContainer: LinearLayout? = null
    private var expandedPanel: LinearLayout? = null

    private var timerTextView: TextView? = null
    private var expandedTimerTextView: TextView? = null
    private var pauseResumeIcon: ImageView? = null
    private var micButton: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var modeBadge: TextView? = null
    private var micIndicatorDot: View? = null
    private var pulsingDotView: View? = null
    private var pulseAnimator: ValueAnimator? = null

    private lateinit var layoutParams: WindowManager.LayoutParams
    private var isExpanded = false
    private var isPaused = false
    private var isFaded = false
    private var isHoldingMicButton = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private var isAttached = false
    private var isDockedToLeft = false

    private val idleFadeRunnable = Runnable {
        if (!isHoldingMicButton) {
            fadeToEdgeHandle()
        }
    }

    private val autoCollapseRunnable = Runnable {
        if (!isHoldingMicButton) {
            collapsePanel()
        }
    }

    private val micStateListener = object : MicrophoneController.Listener {
        override fun onMicStateChanged(isVoiceActive: Boolean, mode: VoiceMode, isPermitted: Boolean) {
            mainHandler.post {
                updateMicViews(isVoiceActive, mode, isPermitted)
            }
        }
    }

    fun show() {
        if (isAttached) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        try {
            buildViewHierarchy()
            windowManager.addView(rootView, layoutParams)
            isAttached = true
            micController.addListener(micStateListener)
            startPulsingAnimation()
            resetIdleTimer()
            Log.d(TAG, "Floating control overlay attached to window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach floating overlay: ${e.message}", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViewHierarchy() {
        val metrics = getDisplayMetrics()
        val screenWidth = metrics.widthPixels
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - dp(112)
            y = dp(160)
        }

        rootView = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        // -------------------------------------------------------------
        // 1. Collapsed Pill Container (Pulsing Red Dot + Timer + Mic Indicator Dot)
        // -------------------------------------------------------------
        pillContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#E6161922"))
                setStroke(dp(1), Color.parseColor("#50FF3B30"))
            }
            elevation = dp(8).toFloat()

            // Subtle red pulsing recording dot
            val dotContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    marginEnd = dp(6)
                }
            }
            pulsingDotView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(10), dp(10)).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FF3B30"))
                }
            }
            dotContainer.addView(pulsingDotView)
            addView(dotContainer)

            // Live recording timer text
            timerTextView = TextView(context).apply {
                text = "00:00"
                setTextColor(Color.WHITE)
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
            }
            addView(timerTextView)

            // Very small microphone indicator dot so the user always knows whether
            // their voice is being recorded, even when the panel is closed
            val micDotContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    marginStart = dp(6)
                }
            }
            micIndicatorDot = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(7), dp(7)).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    val initialColor = if (micController.isVoiceActive && micController.isPermitted) {
                        Color.parseColor("#FF3B30")
                    } else {
                        Color.parseColor("#606470")
                    }
                    setColor(initialColor)
                }
            }
            micDotContainer.addView(micIndicatorDot)
            addView(micDotContainer)
        }

        // -------------------------------------------------------------
        // 2. Expanded Control Panel (Timer, Pause/Resume, Mic Control, Stop, Collapse)
        // -------------------------------------------------------------
        expandedPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F5161922"))
                setStroke(dp(1), Color.parseColor("#60FF3B30"))
            }
            elevation = dp(12).toFloat()

            // Expanded timer
            expandedTimerTextView = TextView(context).apply {
                text = "00:00"
                setTextColor(Color.parseColor("#F2F4F8"))
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setPadding(dp(4), 0, dp(6), 0)
            }
            addView(expandedTimerTextView)

            // Action 1: Pause / Resume Button (kept exactly as it was)
            pauseResumeIcon = createActionButton(
                iconRes = if (isPaused) R.drawable.ic_overlay_play else R.drawable.ic_overlay_pause,
                tintColor = Color.WHITE,
                contentDesc = "Pause or Resume Recording"
            ) {
                onPauseResumeClick()
                resetIdleTimer()
            }
            addView(pauseResumeIcon)

            // Action 2: Microphone Control Container (Mic Button + Voice Mode Chip)
            val micContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            }

            // Microphone Button (Touch Target >= 44dp, bright red when active, crossed-out gray when muted)
            val btnOuterSize = dp(38)
            micButton = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnOuterSize, btnOuterSize)
                contentDescription = "Microphone Voice Control"

                micIcon = ImageView(context).apply {
                    val iconSize = dp(22)
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(
                        if (micController.isVoiceActive && micController.isPermitted)
                            R.drawable.ic_overlay_mic
                        else
                            R.drawable.ic_overlay_mic_off
                    )
                }
                addView(micIcon)

                // High-responsiveness Touch Listener supporting both Toggle and Hold-to-Talk modes
                setOnTouchListener { _, event ->
                    wakeFromIdle()
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            if (!micController.isPermitted) {
                                showPermissionDeniedToast()
                                return@setOnTouchListener true
                            }

                            if (micController.voiceMode == VoiceMode.HOLD_TO_TALK) {
                                isHoldingMicButton = true
                                mainHandler.removeCallbacks(idleFadeRunnable)
                                mainHandler.removeCallbacks(autoCollapseRunnable)
                                micController.onHoldPressed()
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!micController.isPermitted) {
                                return@setOnTouchListener true
                            }

                            if (micController.voiceMode == VoiceMode.HOLD_TO_TALK) {
                                isHoldingMicButton = false
                                micController.onHoldReleased()
                                resetIdleTimer()
                            } else {
                                micController.onMicTapped()
                                resetIdleTimer()
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (micController.voiceMode == VoiceMode.HOLD_TO_TALK) {
                                isHoldingMicButton = false
                                micController.onHoldReleased()
                                resetIdleTimer()
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            micContainer.addView(micButton)

            // Voice Mode Switch Chip ("TOGGLE" vs "HOLD")
            modeBadge = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(26)
                ).apply {
                    marginStart = dp(4)
                }
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(2), dp(6), dp(2))
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E0E4EC"))
                text = if (micController.voiceMode == VoiceMode.TOGGLE) "TOGGLE" else "HOLD"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(13).toFloat()
                    setColor(Color.parseColor("#252934"))
                    setStroke(dp(1), Color.parseColor("#454C5C"))
                }

                setOnClickListener {
                    val newMode = micController.toggleVoiceMode()
                    text = if (newMode == VoiceMode.TOGGLE) "TOGGLE" else "HOLD"
                    resetIdleTimer()
                }
            }
            micContainer.addView(modeBadge)

            addView(micContainer)

            // Action 3: Stop Recording Button (kept exactly as it was)
            val stopButton = FrameLayout(context).apply {
                val btnSize = dp(36)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#3B1819"))
                    setStroke(dp(1), Color.parseColor("#FF3B30"))
                }
                contentDescription = "Stop Recording"

                val stopSquare = View(context).apply {
                    val sqSize = dp(12)
                    layoutParams = FrameLayout.LayoutParams(sqSize, sqSize).apply {
                        gravity = Gravity.CENTER
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(2).toFloat()
                        setColor(Color.parseColor("#FF3B30"))
                    }
                }
                addView(stopSquare)
                setOnClickListener {
                    collapsePanel()
                    onStopClick()
                }
            }
            addView(stopButton)

            // Action 4: Collapse Panel Button
            val collapseButton = createActionButton(
                iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                tintColor = Color.parseColor("#A0A5B2"),
                contentDesc = "Collapse Controls"
            ) {
                collapsePanel()
            }
            addView(collapseButton)
        }

        rootView?.addView(expandedPanel)
        rootView?.addView(pillContainer)

        setupTouchListener()

        // Apply initial mic visual state
        updateMicViews(micController.isVoiceActive, micController.voiceMode, micController.isPermitted)
    }

    private fun createActionButton(
        iconRes: Int,
        tintColor: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): ImageView {
        val density = context.resources.displayMetrics.density
        val size = (36 * density).toInt()
        val margin = (3 * density).toInt()

        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, 0, margin, 0)
            }
            contentDescription = contentDesc
            setImageResource(iconRes)
            setColorFilter(tintColor)
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#252932"))
            }
            setOnClickListener { onClick() }
        }
    }

    /**
     * Updates microphone UI components across both expanded panel and collapsed bubble.
     */
    private fun updateMicViews(isVoiceActive: Boolean, mode: VoiceMode, isPermitted: Boolean) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 1. Update very small indicator dot in collapsed pill
        micIndicatorDot?.let { dot ->
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (!isPermitted) {
                    setColor(Color.parseColor("#4B5563"))
                } else if (isVoiceActive) {
                    setColor(Color.parseColor("#FF3B30")) // Bright red accent when voice is recorded
                } else {
                    setColor(Color.parseColor("#606470")) // Subtle muted gray
                }
            }
            dot.background = dotBg
        }

        // 2. Update expanded microphone button styling
        micButton?.let { btn ->
            if (!isPermitted) {
                // Disabled state when permission denied
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1C1E26"))
                    setStroke(dp(1), Color.parseColor("#383C48"))
                }
                btn.alpha = 0.5f
                micIcon?.setImageResource(R.drawable.ic_overlay_mic_off)
                micIcon?.setColorFilter(Color.parseColor("#686D7C"))
            } else if (isVoiceActive) {
                // Bright microphone icon with red accent when voice is being recorded
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#3B1819"))
                    setStroke(dp(1.5f.toInt()), Color.parseColor("#FF3B30"))
                }
                btn.alpha = 1.0f
                micIcon?.setImageResource(R.drawable.ic_overlay_mic)
                micIcon?.setColorFilter(Color.parseColor("#FF453A"))
            } else {
                // Crossed-out gray microphone icon when muted
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#252932"))
                    setStroke(dp(1), Color.parseColor("#3D4250"))
                }
                btn.alpha = 1.0f
                micIcon?.setImageResource(R.drawable.ic_overlay_mic_off)
                micIcon?.setColorFilter(Color.parseColor("#9E9E9E"))
            }
        }

        // 3. Update voice mode badge text
        modeBadge?.let { badge ->
            badge.text = if (mode == VoiceMode.TOGGLE) "TOGGLE" else "HOLD"
            badge.setTextColor(
                if (mode == VoiceMode.TOGGLE) Color.parseColor("#D8DCED") else Color.parseColor("#FF7B72")
            )
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(Color.parseColor("#20232E"))
                setStroke(
                    dp(1),
                    if (mode == VoiceMode.TOGGLE) Color.parseColor("#3E4456") else Color.parseColor("#6B2526")
                )
            }
        }
    }

    private fun showPermissionDeniedToast() {
        Toast.makeText(
            context,
            "Microphone permission is required to record voice. Tap App Settings to allow access.",
            Toast.LENGTH_LONG
        ).show()

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun startPulsingAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
            duration = 850
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                val alphaVal = va.animatedValue as Float
                pulsingDotView?.alpha = alphaVal
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        val touchHandler: (View, MotionEvent) -> Boolean = { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeFromIdle()
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        clampPosition()
                        windowManager.updateViewLayout(rootView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap detected
                        if (isExpanded) {
                            collapsePanel()
                        } else {
                            expandPanel()
                        }
                    } else {
                        snapToNearestEdge()
                    }
                    resetIdleTimer()
                    true
                }
                else -> false
            }
        }

        pillContainer?.setOnTouchListener(touchHandler)
    }

    private fun expandPanel() {
        isExpanded = true
        pillContainer?.visibility = View.GONE
        expandedPanel?.visibility = View.VISIBLE
        snapToNearestEdge()
        resetIdleTimer()
    }

    private fun collapsePanel() {
        if (isHoldingMicButton) return // Never collapse while user is holding mic in Hold to talk mode!
        isExpanded = false
        expandedPanel?.visibility = View.GONE
        pillContainer?.visibility = View.VISIBLE
        snapToNearestEdge()
        resetIdleTimer()
    }

    private fun snapToNearestEdge() {
        val metrics = getDisplayMetrics()
        val screenWidth = metrics.widthPixels
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()

        val activeViewWidth = if (isExpanded) {
            expandedPanel?.width ?: (220 * density).toInt()
        } else {
            pillContainer?.width ?: (96 * density).toInt()
        }

        val currentX = layoutParams.x
        val targetX = if (currentX + activeViewWidth / 2 < screenWidth / 2) {
            isDockedToLeft = true
            margin
        } else {
            isDockedToLeft = false
            screenWidth - activeViewWidth - margin
        }

        animateX(targetX)
    }

    private fun animateX(targetX: Int) {
        val startX = layoutParams.x
        val animator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                if (isAttached && rootView != null) {
                    layoutParams.x = va.animatedValue as Int
                    windowManager.updateViewLayout(rootView, layoutParams)
                }
            }
        }
        animator.start()
    }

    private fun clampPosition() {
        val metrics = getDisplayMetrics()
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = context.resources.displayMetrics.density
        val topBound = (56 * density).toInt()
        val bottomBound = screenHeight - (100 * density).toInt()

        layoutParams.y = layoutParams.y.coerceIn(topBound, bottomBound)
        layoutParams.x = layoutParams.x.coerceIn(0, screenWidth - (30 * density).toInt())
    }

    private fun fadeToEdgeHandle() {
        if (!isAttached || isDragging || isHoldingMicButton) return
        if (isExpanded) {
            collapsePanel()
            return
        }

        isFaded = true
        val view = pillContainer ?: return
        val targetTranslateX = if (isDockedToLeft) {
            -view.width * 0.70f
        } else {
            view.width * 0.70f
        }

        view.animate()
            .alpha(0.20f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .translationX(targetTranslateX)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun wakeFromIdle(onAwake: (() -> Unit)? = null) {
        if (!isAttached) return
        val view = pillContainer ?: return
        isFaded = false

        view.animate()
            .alpha(1.0f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationX(0f)
            .setDuration(160)
            .withEndAction {
                onAwake?.invoke()
            }
            .start()

        resetIdleTimer()
    }

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.removeCallbacks(autoCollapseRunnable)

        // Holding the mic button in Hold to talk mode prevents fading or collapsing
        if (isHoldingMicButton) return

        if (!isExpanded) {
            mainHandler.postDelayed(idleFadeRunnable, IDLE_TIMEOUT_MS)
        } else {
            mainHandler.postDelayed(autoCollapseRunnable, PANEL_COLLAPSE_TIMEOUT_MS)
        }
    }

    fun updateTimer(timeText: String, isRecordingPaused: Boolean) {
        isPaused = isRecordingPaused
        mainHandler.post {
            timerTextView?.text = timeText
            expandedTimerTextView?.text = timeText
            pauseResumeIcon?.setImageResource(
                if (isRecordingPaused) R.drawable.ic_overlay_play
                else R.drawable.ic_overlay_pause
            )
            if (isRecordingPaused) {
                pulseAnimator?.pause()
                pulsingDotView?.alpha = 0.5f
            } else {
                pulseAnimator?.resume()
            }
        }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        micController.removeListener(micStateListener)
        pulseAnimator?.cancel()
        pulseAnimator = null

        if (isAttached && rootView != null) {
            try {
                windowManager.removeViewImmediate(rootView)
                Log.d(TAG, "Floating control overlay removed from window")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }
        }
        isAttached = false
        rootView = null
        pillContainer = null
        expandedPanel = null
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
