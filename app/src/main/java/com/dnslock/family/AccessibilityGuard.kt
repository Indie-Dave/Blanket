package com.dnslock.family

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects when the user has entered Blanket's own accessibility-service screen
 * (the page with the background / "use service" switch), not parent Accessibility
 * lists where the app name is merely visible.
 */
object AccessibilityGuard {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val toolbarTitleViewIdSuffixes = listOf(
        "action_bar_title",
        "toolbar_title",
        "collapse_title"
    )

    /** Labels that appear on the service detail page (EN + DE). */
    private val serviceScreenMarkers = listOf(
        "accessibility",
        "bedienungshilfen",
        "eingabehilfen",
        "use service",
        "dienst nutzen",
        "dienst verwenden",
        "shortcut",
        "tastenkombination",
        "verknüpfung"
    )

    /** How much of the service description must match to identify the detail page. */
    private const val DESCRIPTION_PROBE_LENGTH = 40

    fun isAccessibilityToggleScreen(
        context: Context,
        foregroundPackage: String,
        root: AccessibilityNodeInfo?
    ): Boolean {
        if (root == null) return false
        if (!isRelevantPackage(foregroundPackage)) return false

        val texts = mutableListOf<String>()
        collectTexts(root, texts = texts)
        if (texts.isEmpty()) return false

        // The service detail page renders our own accessibility description.
        if (texts.any { showsServiceDescription(context, it) }) return true

        // Toolbar / heading is the app name, same "entered this screen" idea as DNS titles.
        if (findServiceScreenTitle(context, root) == null) return false

        return texts.any { hasServiceScreenMarker(it) } || hasToggle(root)
    }

    private fun isRelevantPackage(packageName: String): Boolean {
        if (packageName in settingsPackages) return true
        return packageName.contains("settings", ignoreCase = true)
    }

    private fun mentionsOurApp(context: Context, text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false

        val appName = context.getString(R.string.app_name)
        if (value.contains(appName, ignoreCase = true)) return true
        return value.contains(context.packageName, ignoreCase = true)
    }

    private fun showsServiceDescription(context: Context, text: String): Boolean {
        val probe = normalize(context.getString(R.string.accessibility_service_description))
            .take(DESCRIPTION_PROBE_LENGTH)
        if (probe.length < DESCRIPTION_PROBE_LENGTH) return false
        return normalize(text).contains(probe)
    }

    private fun hasServiceScreenMarker(text: String): Boolean {
        val value = normalize(text)
        return serviceScreenMarkers.any { value.contains(it) }
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun hasToggle(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 16) return false

        if (node.isCheckable) return true
        val className = node.className?.toString().orEmpty()
        if (className.endsWith("Switch") || className.endsWith("CompoundButton")) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = hasToggle(child, depth + 1)
            child?.recycle()
            if (found) return true
        }
        return false
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, depth: Int = 0, texts: MutableList<String>) {
        if (node == null || depth > 16) return

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectTexts(child, depth + 1, texts)
            child?.recycle()
        }
    }

    private fun findServiceScreenTitle(
        context: Context,
        node: AccessibilityNodeInfo?,
        depth: Int = 0
    ): String? {
        if (node == null || depth > 12) return null

        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()

        for (candidate in listOf(text, desc)) {
            if (candidate.isEmpty() || !mentionsOurApp(context, candidate)) continue

            val looksLikeToolbar = toolbarTitleViewIdSuffixes.any { viewId.endsWith(it) }
            if (looksLikeToolbar || !isInsideClickableRow(node)) {
                return candidate
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findServiceScreenTitle(context, child, depth + 1)
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
}
