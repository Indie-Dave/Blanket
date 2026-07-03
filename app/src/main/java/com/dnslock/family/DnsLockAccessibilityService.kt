package com.dnslock.family

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Backup enforcement layer.
 *
 * This service does NOT set DNS -- only Device Owner + setGlobalPrivateDns()
 * can do that (see DnsPolicyManager). What this does is watch for the
 * system Settings app opening a screen that looks like the Private DNS
 * screen, and immediately press "back" to exit it while the lock is
 * enabled -- as a second line of defense on top of the Device Owner
 * lock (which, once active, should already grey out the field).
 *
 * CAVEATS:
 * - Samsung's One UI reskins Settings but keeps the underlying package name
 *   "com.android.settings" in most firmware builds; the exact Activity/
 *   Fragment class name for the Private DNS screen can still vary by One UI
 *   version. If this doesn't trigger on your S23 Ultra, connect via adb
 *   while sitting on that screen and run:
 *       adb shell dumpsys window windows | grep mCurrentFocus
 *   then add the class name you see to `dnsClassHints` below.
 * - This is a heuristic, not a hard guarantee -- a fast enough tap sequence
 *   could theoretically slip through before the event fires. The Device
 *   Owner lock is what actually prevents the DNS value from changing even
 *   if the screen is briefly visible.
 */
class DnsLockAccessibilityService : AccessibilityService() {

    private val settingsPackages = setOf(
        "com.android.settings"
    )

    private val dnsClassHints = listOf(
        "Dns",
        "NetworkDashboard",
        "NetworkProviderSettings",
        "ConnectedDeviceDashboard"
    )

    private val dnsTextHints = listOf(
        "Private DNS",
        "private DNS"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!DnsPolicyManager.isLockEnabledPref(this)) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in settingsPackages) return

        val className = event.className?.toString().orEmpty()
        val classLooksLikeDns = dnsClassHints.any { className.contains(it, ignoreCase = true) }

        val screenLooksLikeDns = classLooksLikeDns || containsDnsText(rootInActiveWindow, depth = 0)

        if (screenLooksLikeDns) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun containsDnsText(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 12) return false
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (dnsTextHints.any { text.contains(it) || desc.contains(it) }) return true

        for (i in 0 until node.childCount) {
            if (containsDnsText(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    override fun onInterrupt() {}
}
