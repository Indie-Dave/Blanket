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

object ProtectionInfoPopup {

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var dismissRunnable: Runnable? = null
    private var lastAlertKey: String? = null
    private var lastAlertAt = 0L

    fun showBlockedApp(context: Context, appName: String, blockedKeyword: String) {
        show(
            context = context,
            title = context.getString(R.string.app_closed_title),
            message = context.getString(R.string.app_closed_blocked_reason, appName, blockedKeyword)
        )
    }

    fun showUninstallBlocked(context: Context) {
        show(
            context = context,
            title = context.getString(R.string.app_closed_title),
            message = context.getString(R.string.app_closed_uninstall_reason)
        )
    }

    private fun show(context: Context, title: String, message: String) {
        val alertKey = "$title|$message"
        val now = System.currentTimeMillis()
        if (alertKey == lastAlertKey && now - lastAlertAt < ALERT_DEBOUNCE_MS) return

        handler.post {
            if (alertKey == lastAlertKey && System.currentTimeMillis() - lastAlertAt < ALERT_DEBOUNCE_MS) {
                return@post
            }
            lastAlertKey = alertKey
            lastAlertAt = System.currentTimeMillis()

            removeOverlay()

            val view = LayoutInflater.from(context).inflate(R.layout.protection_alert_overlay, null)
            view.findViewById<TextView>(R.id.alertTitle).text = title
            view.findViewById<TextView>(R.id.alertMessage).text = message
            view.setOnClickListener { removeOverlay() }

            val wm = context.getSystemService(WindowManager::class.java)
            val topInset = (56 * context.resources.displayMetrics.density).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topInset
            }

            wm.addView(view, params)
            overlayView = view
            windowManager = wm

            dismissRunnable = Runnable { removeOverlay() }
            handler.postDelayed(dismissRunnable!!, ALERT_TIMEOUT_MS)
        }
    }

    private fun removeOverlay() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null

        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        overlayView = null
        windowManager = null
    }

    private const val ALERT_TIMEOUT_MS = 6_000L
    private const val ALERT_DEBOUNCE_MS = 4_000L
}
