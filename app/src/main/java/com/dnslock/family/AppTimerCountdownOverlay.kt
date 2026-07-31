package com.dnslock.family

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating countdown shown while a timed app is in the foreground.
 * Left-edge widget: drag horizontally to open/close, vertically to reposition.
 * When closed, only the arrow knob remains visible.
 */
object AppTimerCountdownOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var timerPanel: View? = null
    private var labelView: TextView? = null
    private var arrowLabel: TextView? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var activePackage: String? = null
    private var appName: String = ""
    private var remainingProvider: (() -> Long)? = null
    private var onExpired: (() -> Unit)? = null

    private var minY = 0
    private var maxY = 0
    private var currentY = 0
    private var expanded = false
    private var userDragging = false
    private var snapAnimator: ValueAnimator? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val remaining = remainingProvider?.invoke() ?: 0L
            updateLabel(remaining)
            if (remaining <= 0L) {
                val expired = onExpired
                stop()
                expired?.invoke()
                return
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    fun start(
        context: Context,
        packageName: String,
        appName: String,
        remainingProvider: () -> Long,
        onExpired: () -> Unit
    ) {
        handler.post {
            // Already showing for this app — only refresh data, never recreate.
            if (activePackage == packageName && overlayView != null) {
                this.appName = appName
                this.remainingProvider = remainingProvider
                this.onExpired = onExpired
                updateLabel(remainingProvider().coerceAtLeast(0L))
                return@post
            }

            val keepY = currentY.takeIf { activePackage == packageName || overlayView != null }
            stopInternal(clearPosition = false)

            this.activePackage = packageName
            this.appName = appName
            this.remainingProvider = remainingProvider
            this.onExpired = onExpired
            this.expanded = false
            this.userDragging = false

            val density = context.resources.displayMetrics.density
            val screenHeight = context.resources.displayMetrics.heightPixels
            val defaultY = (screenHeight * 0.28f).toInt()
            minY = (48 * density).toInt()
            maxY = (screenHeight - 80 * density).toInt().coerceAtLeast(minY)
            currentY = (keepY ?: defaultY).coerceIn(minY, maxY)

            val remaining = remainingProvider().coerceAtLeast(0L)

            val view = LayoutInflater.from(context)
                .inflate(R.layout.app_timer_countdown_overlay, null)
            val panel = view.findViewById<View>(R.id.timerPanel)
            val label = view.findViewById<TextView>(R.id.countdownLabel)
            val arrow = view.findViewById<TextView>(R.id.arrowLabel)
            label.text = formatLabel(appName, remaining)
            updateArrow(expand = false, arrow)

            // Measure so we can start collapsed without a visible flash.
            view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            view.translationX = -panel.measuredWidth.toFloat()

            val wm = context.getSystemService(WindowManager::class.java)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = currentY
            }

            attachDragHandling(view, panel)

            wm.addView(view, params)
            overlayView = view
            timerPanel = panel
            labelView = label
            arrowLabel = arrow
            windowManager = wm
            layoutParams = params

            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, if (remaining <= 0L) 0L else 1_000L)
        }
    }

    fun stop() {
        handler.post { stopInternal(clearPosition = true) }
    }

    private fun updateLabel(remainingMs: Long) {
        val label = labelView ?: return
        val panel = timerPanel ?: return
        val view = overlayView ?: return
        label.text = formatLabel(appName, remainingMs)
        // Keep only the knob on-screen if the label width changed while collapsed.
        if (!expanded && !userDragging && snapAnimator?.isRunning != true) {
            view.post {
                if (overlayView !== view || expanded || userDragging) return@post
                val panelWidth = panel.width
                if (panelWidth <= 0) return@post
                view.translationX = -panelWidth.toFloat()
            }
        }
    }

    private fun collapsedTranslation(panel: View): Float = -panel.width.toFloat()

    private fun attachDragHandling(view: View, panel: View) {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        val minFlingVelocity = ViewConfiguration.get(view.context).scaledMinimumFlingVelocity
        var tracking = false
        var dragging = false
        var horizontalDrag = false
        var downRawX = 0f
        var downRawY = 0f
        var startTranslationX = 0f
        var startY = 0
        var velocityTracker: VelocityTracker? = null

        view.setOnTouchListener { v, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    snapAnimator = null
                    tracking = true
                    dragging = false
                    horizontalDrag = false
                    userDragging = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTranslationX = v.translationX
                    startY = params.y
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - downRawX
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        userDragging = true
                        horizontalDrag = abs(dx) >= abs(dy)
                    }
                    if (dragging) {
                        if (horizontalDrag) {
                            val collapsed = collapsedTranslation(panel)
                            v.translationX = (startTranslationX + dx).coerceIn(collapsed, 0f)
                        } else {
                            params.y = (startY + dy).coerceIn(minY, maxY)
                            currentY = params.y
                            try {
                                wm.updateViewLayout(v, params)
                            } catch (_: IllegalArgumentException) {
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!tracking) return@setOnTouchListener false
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (dragging && horizontalDrag) {
                        val collapsed = collapsedTranslation(panel)
                        val travel = abs(collapsed).coerceAtLeast(1f)
                        val collapseLine = -travel * 0.20f
                        val collapseFling = minFlingVelocity * 0.45f
                        val shouldExpand = when {
                            velocityX > minFlingVelocity -> true
                            velocityX < -collapseFling -> false
                            else -> v.translationX > collapseLine
                        }
                        snapTo(shouldExpand)
                    } else if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        snapTo(!expanded)
                    }

                    tracking = false
                    dragging = false
                    userDragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun snapTo(expand: Boolean) {
        val view = overlayView ?: return
        val panel = timerPanel ?: return
        expanded = expand
        updateArrow(expand, arrowLabel)
        val target = if (expand) 0f else collapsedTranslation(panel)
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(view.translationX, target).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val current = overlayView ?: return@addUpdateListener
                current.translationX = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun updateArrow(expand: Boolean, arrow: TextView?) {
        arrow?.text = if (expand) "‹" else "›"
    }

    private fun stopInternal(clearPosition: Boolean) {
        handler.removeCallbacks(tickRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        activePackage = null
        remainingProvider = null
        onExpired = null
        appName = ""
        expanded = false
        userDragging = false
        if (clearPosition) {
            currentY = 0
        }

        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        overlayView = null
        timerPanel = null
        labelView = null
        arrowLabel = null
        windowManager = null
        layoutParams = null
    }

    private fun formatLabel(name: String, remainingMs: Long): String {
        return "$name · ${AppTimersManager.formatCountdown(remainingMs)}"
    }
}
