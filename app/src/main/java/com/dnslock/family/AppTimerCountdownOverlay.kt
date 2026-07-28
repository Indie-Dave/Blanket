package com.dnslock.family

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Floating countdown shown while a timed app is in the foreground.
 */
object AppTimerCountdownOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var labelView: TextView? = null
    private var windowManager: WindowManager? = null

    private var activePackage: String? = null
    private var appName: String = ""
    private var remainingProvider: (() -> Long)? = null
    private var onExpired: (() -> Unit)? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val remaining = remainingProvider?.invoke() ?: 0L
            if (remaining <= 0L) {
                val expired = onExpired
                stop()
                expired?.invoke()
                return
            }
            labelView?.text = formatLabel(appName, remaining)
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
            if (activePackage == packageName && overlayView != null) {
                this.appName = appName
                this.remainingProvider = remainingProvider
                this.onExpired = onExpired
                return@post
            }

            stopInternal()

            this.activePackage = packageName
            this.appName = appName
            this.remainingProvider = remainingProvider
            this.onExpired = onExpired

            val remaining = remainingProvider()
            if (remaining <= 0L) {
                stopInternal()
                onExpired()
                return@post
            }

            val view = LayoutInflater.from(context)
                .inflate(R.layout.app_timer_countdown_overlay, null)
            val label = view.findViewById<TextView>(R.id.countdownLabel)
            label.text = formatLabel(appName, remaining)

            val wm = context.getSystemService(WindowManager::class.java)
            val topInset = (48 * context.resources.displayMetrics.density).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topInset
            }

            wm.addView(view, params)
            overlayView = view
            labelView = label
            windowManager = wm

            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, 1_000L)
        }
    }

    fun stop() {
        handler.post { stopInternal() }
    }

    private fun stopInternal() {
        handler.removeCallbacks(tickRunnable)
        activePackage = null
        remainingProvider = null
        onExpired = null
        appName = ""

        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        overlayView = null
        labelView = null
        windowManager = null
    }

    private fun formatLabel(name: String, remainingMs: Long): String {
        return "$name · ${AppTimersManager.formatCountdown(remainingMs)}"
    }
}
