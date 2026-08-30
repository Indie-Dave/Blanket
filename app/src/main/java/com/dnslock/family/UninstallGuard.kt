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

    /** Dialogs are small; larger trees are the Settings page behind the dialog. */
    private const val MAX_DIALOG_TEXT_NODES = 32
    private const val MAX_DIALOG_WALK = 10
    private const val MAX_TREE_DEPTH = 24

    fun isUninstallAttempt(context: Context, foregroundPackage: String, root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (!isRelevantPackage(foregroundPackage)) return false

        val appName = context.getString(R.string.app_name)
        val ourPackage = context.packageName

        val confirmNodes = mutableListOf<AccessibilityNodeInfo>()
        findConfirmNodes(root, confirmNodes)
        try {
            for (node in confirmNodes) {
                if (confirmTargetsOurApp(node, appName, ourPackage)) return true
            }
        } finally {
            for (node in confirmNodes) {
                node.recycle()
            }
        }
        return false
    }

    private fun isRelevantPackage(packageName: String): Boolean {
        if (packageName in settingsPackages) return true
        if (packageName.contains("packageinstaller", ignoreCase = true)) return true
        if (packageName.contains("settings", ignoreCase = true)) return true
        return false
    }

    /**
     * Only treat this as uninstalling Blanket when the app name appears in the
     * confirm dialog itself, not merely somewhere else in the same window
     * (for example the installed-apps list behind the dialog).
     */
    private fun confirmTargetsOurApp(
        confirmNode: AccessibilityNodeInfo,
        appName: String,
        ourPackage: String
    ): Boolean {
        val confirmText = nodeText(confirmNode)
        if (namesOurApp(confirmText, appName, ourPackage) && looksLikeUninstallConfirm(confirmText)) {
            return true
        }

        var current: AccessibilityNodeInfo? = confirmNode.parent
        var steps = 0
        while (current != null && steps < MAX_DIALOG_WALK) {
            val texts = mutableListOf<String>()
            collectTexts(current, depth = 0, texts = texts, maxTexts = MAX_DIALOG_TEXT_NODES + 1)
            if (texts.size > MAX_DIALOG_TEXT_NODES) {
                current.recycle()
                break
            }
            if (isOurAppUninstallTarget(texts, appName, ourPackage)) {
                current.recycle()
                return true
            }
            val parent = current.parent
            current.recycle()
            current = parent
            steps++
        }
        return false
    }

    private fun isOurAppUninstallTarget(
        texts: List<String>,
        appName: String,
        ourPackage: String
    ): Boolean {
        val hasConfirm = texts.any { looksLikeUninstallConfirm(it) }
        if (!hasConfirm) return false
        return texts.any { namesOurApp(it, appName, ourPackage) }
    }

    private fun namesOurApp(text: String, appName: String, ourPackage: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (value.equals(appName, ignoreCase = true)) return true
        if (value.equals(ourPackage, ignoreCase = true)) return true
        val lower = value.lowercase()
        if (looksLikeUninstallConfirm(lower) && lower.contains(appName.lowercase())) return true
        return lower.contains(ourPackage.lowercase())
    }

    private fun looksLikeUninstallConfirm(text: String): Boolean {
        val value = text.lowercase()
        if (value.isEmpty()) return false
        return uninstallConfirmKeywords.any { keyword -> value.contains(keyword) }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        return "$text $description".trim()
    }

    private fun findConfirmNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (node == null || depth > MAX_TREE_DEPTH) return
        if (looksLikeUninstallConfirm(nodeText(node))) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            findConfirmNodes(child, out, depth + 1)
            child?.recycle()
        }
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        depth: Int,
        texts: MutableList<String>,
        maxTexts: Int
    ) {
        if (node == null || depth > MAX_TREE_DEPTH || texts.size >= maxTexts) return

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }

        for (i in 0 until node.childCount) {
            if (texts.size >= maxTexts) return
            val child = node.getChild(i)
            collectTexts(child, depth + 1, texts, maxTexts)
            child?.recycle()
        }
    }
}
