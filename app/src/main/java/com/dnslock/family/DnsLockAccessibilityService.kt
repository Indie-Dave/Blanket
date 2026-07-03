package com.dnslock.family

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Minimizes Settings when the screen title is "Weitere Verbindungseinstellungen"
 * (including Samsung's hyphenated "Weitere Verbindungs-einstellungen").
 */
class DnsLockAccessibilityService : AccessibilityService() {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val toolbarTitleViewIdSuffixes = listOf(
        "action_bar_title",
        "toolbar_title",
        "collapse_title"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in settingsPackages) return

        if (hasTargetScreenTitle(event)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun hasTargetScreenTitle(event: AccessibilityEvent): Boolean {
        for (i in 0 until event.text.size) {
            if (isTargetScreenTitle(event.text[i]?.toString().orEmpty())) return true
        }

        val root = rootInActiveWindow ?: return false
        return findScreenTitle(root) != null
    }

    private fun isTargetScreenTitle(text: String): Boolean {
        return normalizeTitle(text).equals(TARGET_TITLE, ignoreCase = true)
    }

    private fun normalizeTitle(text: String): String =
        text.trim()
            .replace("-", "")
            .replace(Regex("\\s+"), " ")

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
            findScreenTitle(node.getChild(i), depth + 1)?.let { return it }
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

    override fun onInterrupt() {}

    companion object {
        private const val TARGET_TITLE = "Weitere Verbindungseinstellungen"
    }
}
