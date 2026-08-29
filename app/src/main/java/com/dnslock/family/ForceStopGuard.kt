package com.dnslock.family

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects Blanket's App info screen (and Force Stop confirmation).
 * Uses text search rather than toolbar view IDs — OEM App info pages
 * often use collapsing headers / Compose and do not expose action_bar_title.
 */
object ForceStopGuard {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val appInfoActivityMarkers = listOf(
        "installedappdetails",
        "appinfodashboard",
        "appinfosettings",
        "appinfoactivity",
        "appinfobase",
        "applicationdetails"
    )

    private val appInfoActionQueries = listOf(
        "Force stop",
        "Force Stop",
        "Beenden erzwingen",
        "Anhalten erzwingen",
        "Uninstall",
        "Deinstallieren"
    )

    fun isBlockedAppInfoScreen(
        context: Context,
        foregroundPackage: String,
        root: AccessibilityNodeInfo?,
        extraTexts: Collection<String> = emptyList(),
        windowClassName: String? = null
    ): Boolean {
        if (root == null) return false
        if (foregroundPackage.isNotEmpty() && !isRelevantPackage(foregroundPackage)) return false

        val mentionsApp = mentionsOurAppInTree(context, root, extraTexts)
        if (!mentionsApp) return false

        if (looksLikeAppInfoActivity(windowClassName)) return true
        if (hasAppInfoAction(root, extraTexts)) return true
        if (hasAppInfoTitle(root, extraTexts)) return true
        if (isForceStopPrompt(context, foregroundPackage, root, extraTexts)) return true

        return false
    }

    fun isForceStopPrompt(
        context: Context,
        foregroundPackage: String,
        root: AccessibilityNodeInfo?,
        extraTexts: Collection<String> = emptyList()
    ): Boolean {
        if (root == null) return false
        if (foregroundPackage.isNotEmpty() && !isRelevantPackage(foregroundPackage)) return false
        if (!mentionsOurAppInTree(context, root, extraTexts)) return false

        val combined = extraTexts + collectShortTexts(root)
        return combined.any { hasForceStopKeyword(it) }
    }

    fun looksLikeAppInfoActivity(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        val value = className.lowercase()
        if (value.startsWith("android.widget")) return false
        if (value.startsWith("android.view")) return false
        return appInfoActivityMarkers.any { value.contains(it) }
    }

    fun isRelevantPackage(packageName: String): Boolean {
        if (packageName in settingsPackages) return true
        if (packageName == "android") return true
        if (packageName == "com.android.systemui") return true
        if (packageName.contains("packageinstaller", ignoreCase = true)) return true
        if (packageName.contains("settings", ignoreCase = true)) return true
        if (packageName.contains("securitycenter", ignoreCase = true)) return true
        if (packageName.contains("appmanager", ignoreCase = true)) return true
        return false
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
        return collectShortTexts(root).any { mentionsOurApp(appName, packageName, it) }
    }

    private val appInfoTitleQueries = listOf(
        "App info",
        "App-Info",
        "Anwendungsinfo",
        "App details",
        "App information"
    )

    private fun hasAppInfoTitle(
        root: AccessibilityNodeInfo,
        extraTexts: Collection<String>
    ): Boolean {
        if (extraTexts.any { isAppInfoTitleLabel(it) }) return true
        for (query in appInfoTitleQueries) {
            if (hasNodeWithText(root, query)) return true
        }
        return false
    }

    private fun isAppInfoTitleLabel(text: String): Boolean {
        val value = normalize(text)
        return appInfoTitleQueries.any { normalize(it) == value || value.startsWith(normalize(it)) }
    }

    private fun hasAppInfoAction(
        root: AccessibilityNodeInfo,
        extraTexts: Collection<String>
    ): Boolean {
        if (extraTexts.any { isAppInfoActionLabel(it) }) return true
        for (query in appInfoActionQueries) {
            if (hasNodeWithText(root, query)) return true
        }
        return collectShortTexts(root).any { isAppInfoActionLabel(it) }
    }

    private fun hasNodeWithText(root: AccessibilityNodeInfo, query: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(query) ?: return false
        val found = nodes.isNotEmpty()
        for (node in nodes) node.recycle()
        return found
    }

    private fun mentionsOurApp(appName: String, packageName: String, text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (value.equals(appName, ignoreCase = true)) return true
        if (value.contains(appName, ignoreCase = true)) return true
        return value.contains(packageName, ignoreCase = true)
    }

    private fun isAppInfoActionLabel(text: String): Boolean {
        val value = normalize(text)
        if (value.isEmpty() || value.length > 64) return false
        return appInfoActionQueries.any { query ->
            val q = normalize(query)
            value == q || value.contains(q)
        }
    }

    private fun hasForceStopKeyword(text: String): Boolean {
        val value = normalize(text)
        if (value.isEmpty()) return false
        return value.contains("force stop") ||
            value.contains("force stopping") ||
            value.contains("beenden erzwingen") ||
            value.contains("anhalten erzwingen") ||
            value.contains("erzwungenes beenden") ||
            value.contains("zwangsbeenden")
    }

    private fun normalize(text: String): String =
        text.trim()
            .replace("?", "")
            .replace("!", "")
            .replace(",", " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .lowercase()

    private fun collectShortTexts(node: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
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
}
