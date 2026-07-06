package com.dnslock.family

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Navigates back one screen in Settings when the toolbar title matches a known
 * DNS settings screen for the current system locale (including Samsung hyphen variants).
 */
class DnsLockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val targetTitles: Set<String> by lazy {
        resources.getStringArray(R.array.dns_lock_target_titles)
            .map(::normalizeTitle)
            .toSet()
    }
    private var onTargetScreen = false
    private var lastDismissAt = 0L
    private var lastBlockedDismissAt = 0L
    private var lastBlockedPackage: String? = null
    private var lastUninstallBlockAt = 0L

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val toolbarTitleViewIdSuffixes = listOf(
        "action_bar_title",
        "toolbar_title",
        "collapse_title"
    )

    private val recheckRunnable = Runnable { evaluateAndDismiss(fromRecheck = true) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startProtectionService()
    }

    override fun onDestroy() {
        handler.removeCallbacks(recheckRunnable)
        stopService(Intent(this, ProtectionForegroundService::class.java))
        super.onDestroy()
    }

    private fun startProtectionService() {
        val intent = Intent(this, ProtectionForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                evaluateBlockedApp(event)
                maybeBlockUninstall(event)
            }
        }

        val pkg = event.packageName?.toString()
        if (pkg != null && pkg !in settingsPackages) {
            onTargetScreen = false
            handler.removeCallbacks(recheckRunnable)
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> evaluateAndDismiss(fromRecheck = false)
        }
    }

    private fun evaluateBlockedApp(event: AccessibilityEvent) {
        val pkg = resolveForegroundPackage(event) ?: return
        maybeDismissBlockedApp(pkg, findWindowTitle())
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent): String? {
        event.packageName?.toString()?.let { return it }

        rootInActiveWindow?.packageName?.toString()?.let { return it }

        windows?.forEach { window ->
            if (!window.isActive || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                root.packageName?.toString()?.let { return it }
            } finally {
                root.recycle()
            }
        }
        return null
    }

    private fun findWindowTitle(): String? {
        rootInActiveWindow?.let { title -> findToolbarTitle(title)?.let { return it } }

        windows?.forEach { window ->
            if (!window.isActive || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                findToolbarTitle(root)?.let { return it }
            } finally {
                root.recycle()
            }
        }
        return null
    }

    private fun findToolbarTitle(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 12) return null

        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()

        for (candidate in listOf(text, desc)) {
            if (candidate.isEmpty()) continue
            val looksLikeToolbar = toolbarTitleViewIdSuffixes.any { viewId.endsWith(it) }
            if (looksLikeToolbar) return candidate
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findToolbarTitle(child, depth + 1)
            child?.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun maybeBlockUninstall(event: AccessibilityEvent) {
        val pkg = resolveForegroundPackage(event) ?: return

        rootInActiveWindow?.let { root ->
            try {
                if (blockUninstallIfNeeded(pkg, root)) return
            } finally {
                root.recycle()
            }
        }

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                val windowPkg = root.packageName?.toString() ?: pkg
                if (blockUninstallIfNeeded(windowPkg, root)) return
            } finally {
                root.recycle()
            }
        }
    }

    private fun blockUninstallIfNeeded(foregroundPackage: String, root: AccessibilityNodeInfo): Boolean {
        if (!UninstallGuard.isUninstallAttempt(this, foregroundPackage, root)) return false

        val now = System.currentTimeMillis()
        if (now - lastUninstallBlockAt < DISMISS_COOLDOWN_MS) return true

        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            lastUninstallBlockAt = now
        }
        return true
    }

    private fun maybeDismissBlockedApp(pkg: String, windowTitle: String? = null) {
        if (pkg == packageName) return
        if (!BlockedAppsManager.isAppBlocked(this, pkg, windowTitle)) return

        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPackage && now - lastBlockedDismissAt < DISMISS_COOLDOWN_MS) return

        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            lastBlockedDismissAt = now
            lastBlockedPackage = pkg
        }
    }

    private fun evaluateAndDismiss(fromRecheck: Boolean) {
        if (!PasswordManager.isDnsScreenLockEnabled(this)) return
        if (PasswordManager.isDnsUnlocked(this)) return

        val isTarget = hasTargetScreenTitle()

        if (!isTarget) {
            onTargetScreen = false
            if (!fromRecheck) {
                handler.removeCallbacks(recheckRunnable)
                handler.postDelayed(recheckRunnable, RECHECK_DELAY_MS)
            }
            return
        }

        handler.removeCallbacks(recheckRunnable)

        if (onTargetScreen) return

        val now = System.currentTimeMillis()
        if (now - lastDismissAt < DISMISS_COOLDOWN_MS) return

        onTargetScreen = true
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            lastDismissAt = now
            handler.postDelayed({ onTargetScreen = false }, RESET_DELAY_MS)
        } else {
            onTargetScreen = false
            handler.postDelayed(recheckRunnable, RECHECK_DELAY_MS)
        }
    }

    private fun hasTargetScreenTitle(): Boolean {
        rootInActiveWindow?.let { if (findScreenTitle(it) != null) return true }

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                val pkg = root.packageName?.toString() ?: return@forEach
                if (pkg in settingsPackages && findScreenTitle(root) != null) return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun isTargetScreenTitle(text: String): Boolean {
        return normalizeTitle(text) in targetTitles
    }

    private fun normalizeTitle(text: String): String =
        text.trim()
            .replace("-", "")
            .replace(Regex("\\s+"), " ")
            .lowercase()

    private fun findScreenTitle(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 12) return null

        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()

        for (candidate in listOf(text, desc)) {
            if (candidate.isEmpty() || !isTargetScreenTitle(candidate)) continue

            val looksLikeToolbar = toolbarTitleViewIdSuffixes.any { viewId.endsWith(it) }
            if (looksLikeToolbar || !isInsideClickableRow(node)) {
                return candidate
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findScreenTitle(child, depth + 1)
            child?.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isInsideClickableRow(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return true
            current = current.parent
            depth++
        }
        return false
    }

    override fun onInterrupt() {
        handler.removeCallbacks(recheckRunnable)
        onTargetScreen = false
    }

    companion object {
        private const val DISMISS_COOLDOWN_MS = 600L
        private const val RESET_DELAY_MS = 1200L
        private const val RECHECK_DELAY_MS = 200L
    }
}
