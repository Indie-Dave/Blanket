package com.dnslock.family

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

object UninstallGuard {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val uninstallConfirmKeywords = listOf(
        "do you want to uninstall",
        "uninstall this app",
        "uninstall app",
        "diese app deinstallieren",
        "app deinstallieren",
        "want to uninstall"
    )

    fun isUninstallAttempt(context: Context, foregroundPackage: String, root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (!isRelevantPackage(foregroundPackage)) return false

        val texts = mutableListOf<String>()
        collectTexts(root, texts = texts)

        val mentionsApp = texts.any { mentionsOurApp(context, it) }
        if (!mentionsApp) return false

        val isInstaller = foregroundPackage.contains("packageinstaller", ignoreCase = true)
        val hasConfirm = texts.any { text ->
            val value = text.lowercase()
            uninstallConfirmKeywords.any { keyword -> value.contains(keyword) }
        }
        if (isInstaller || hasConfirm) return true

        return false
    }

    private fun isRelevantPackage(packageName: String): Boolean {
        if (packageName in settingsPackages) return true
        if (packageName.contains("packageinstaller", ignoreCase = true)) return true
        if (packageName.contains("settings", ignoreCase = true)) return true
        return false
    }

    private fun mentionsOurApp(context: Context, text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false

        val appName = context.getString(R.string.app_name)
        if (value.equals(appName, ignoreCase = true)) return true
        if (value.contains(appName, ignoreCase = true)) return true
        if (value.contains(context.packageName, ignoreCase = true)) return true
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
}
