package com.dnslock.family

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects Blanket's accessibility-service screen, including
 * Accessibility → Installed apps → Blanket (Eingabehilfe → Installierte Apps → Blanket).
 * The Installed apps list is not blocked just because Blanket appears as a row.
 */
object AccessibilityGuard {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val serviceActivityMarkers = listOf(
        "toggleaccessibilityservice",
        "accessibilityservicepreference",
        "volumeshortcuttoggle"
    )

    private val installedAppsListTitles = listOf(
        "installed apps",
        "installierte apps",
        "downloaded apps",
        "heruntergeladene apps",
        "installed services",
        "installierte dienste"
    )

    private val useServiceQueries = listOf(
        "Use service",
        "Use Service",
        "Dienst nutzen",
        "Dienst verwenden"
    )

    fun isAccessibilityToggleScreen(
        context: Context,
        foregroundPackage: String,
        root: AccessibilityNodeInfo?,
        extraTexts: Collection<String> = emptyList(),
        windowClassName: String? = null,
        screenTitle: String? = null
    ): Boolean {
        if (root == null) return false
        if (foregroundPackage.isNotEmpty() && !isRelevantPackage(foregroundPackage)) return false

        val toolbarTitle = findToolbarTitle(root)?.let { normalize(it) }.orEmpty()
        val title = toolbarTitle.ifEmpty {
            normalize(screenTitle ?: extraTexts.firstOrNull().orEmpty())
        }
        if (isInstalledAppsListTitle(title)) return false

        val appName = normalize(context.getString(R.string.app_name))
        // Opened the Blanket option — same "screen entered" idea as DNS / App info.
        if (appName.isNotEmpty() && (title == appName || title.startsWith("$appName "))) {
            return true
        }

        if (showsServiceDescription(context, root, extraTexts)) return true

        val mentionsApp = mentionsOurAppInTree(context, root, extraTexts)
        if (!mentionsApp) return false

        if (looksLikeServiceActivity(windowClassName)) return true
        if (hasUseServiceControl(root, extraTexts)) return true

        return false
    }

    fun looksLikeServiceActivity(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        val value = className.lowercase()
        if (value.startsWith("android.widget")) return false
        if (value.startsWith("android.view")) return false
        return serviceActivityMarkers.any { value.contains(it) }
    }

    private fun isRelevantPackage(packageName: String): Boolean {
        if (packageName in settingsPackages) return true
        return packageName.contains("settings", ignoreCase = true)
    }

    private fun findToolbarTitle(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 14) return null

        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val looksLikeToolbar = toolbarTitleViewIdSuffixes.any { viewId.endsWith(it) }

        for (candidate in listOf(text, desc)) {
            if (candidate.isEmpty()) continue
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

    private val toolbarTitleViewIdSuffixes = listOf(
        "action_bar_title",
        "toolbar_title",
        "collapse_title",
        "collapsing_toolbar",
        "sesl_action_bar_title",
        "extended_title",
        "entity_header_title"
    )

    private fun isInstalledAppsListTitle(normalizedTitle: String): Boolean {
        if (normalizedTitle.isEmpty()) return false
        return installedAppsListTitles.any {
            normalizedTitle == it || normalizedTitle.startsWith(it)
        }
    }

    private fun mentionsOurAppInTree(
        context: Context,
        root: AccessibilityNodeInfo,
        extraTexts: Collection<String>
    ): Boolean {
        val appName = context.getString(R.string.app_name)
        val packageName = context.packageName
        if (extraTexts.any { mentionsOurApp(appName, packageName, it) }) return true
        if (hasNodeWithText(root, appName)) return true
        if (hasNodeWithText(root, packageName)) return true
        return collectTexts(root).any { mentionsOurApp(appName, packageName, it) }
    }

    private fun mentionsOurApp(appName: String, packageName: String, text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (value.equals(appName, ignoreCase = true)) return true
        if (value.contains(appName, ignoreCase = true)) return true
        return value.contains(packageName, ignoreCase = true)
    }

    private fun showsServiceDescription(
        context: Context,
        root: AccessibilityNodeInfo,
        extraTexts: Collection<String>
    ): Boolean {
        val description = context.getString(R.string.accessibility_service_description).trim()
        val probe = description.take(28)
        if (probe.length < 16) return false
        if (extraTexts.any { normalize(it).contains(normalize(probe)) }) return true
        if (hasNodeWithText(root, probe)) return true
        return collectTexts(root).any { normalize(it).contains(normalize(probe)) }
    }

    private fun hasUseServiceControl(
        root: AccessibilityNodeInfo,
        extraTexts: Collection<String>
    ): Boolean {
        if (extraTexts.any { isUseServiceLabel(it) }) return true
        for (query in useServiceQueries) {
            if (hasNodeWithText(root, query)) return true
        }
        return collectTexts(root).any { isUseServiceLabel(it) }
    }

    private fun isUseServiceLabel(text: String): Boolean {
        val value = normalize(text)
        if (value.length > 48) return false
        return useServiceQueries.any {
            val q = normalize(it)
            value == q || value.contains(q)
        }
    }

    private fun hasNodeWithText(root: AccessibilityNodeInfo, query: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(query) ?: return false
        val found = nodes.isNotEmpty()
        for (node in nodes) node.recycle()
        return found
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
        val texts = mutableListOf<String>()
        collectTexts(node, depth, texts)
        return texts
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, depth: Int, texts: MutableList<String>) {
        if (node == null || depth > 32) return

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectTexts(child, depth + 1, texts)
            child?.recycle()
        }
    }

    private fun normalize(text: String): String =
        text.trim().replace("-", " ").replace(Regex("\\s+"), " ").lowercase()
}
