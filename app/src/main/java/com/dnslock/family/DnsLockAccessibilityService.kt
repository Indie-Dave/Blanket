package com.dnslock.family

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Navigates back one screen in Settings when the toolbar title matches a known
 * DNS settings screen for the current system locale (including Samsung hyphen variants).
 * Also redirects browsers away from blocked websites to Google.
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
    private var lastUninstallBlockAt = 0L
    private val blockedSuppressUntil = mutableMapOf<String, Long>()

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val browserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly"
    )

    private val urlBarViewIdSuffixes = listOf(
        "url_bar",
        "location_bar_edit_text",
        "mozac_browser_toolbar_url_view",
        "url_view",
        "sites_bar",
        "toolbar_url_view",
        "search_box_text",
        "omnibox_text",
        "url_bar_title"
    )

    private var lastBlockedUrlSuppressUntil = 0L
    private var redirectInProgress = false

    private val toolbarTitleViewIdSuffixes = listOf(
        "action_bar_title",
        "toolbar_title",
        "collapse_title"
    )

    private val recheckRunnable = Runnable { evaluateAndDismiss(fromRecheck = true) }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                evaluateBlockedApp(event)
                maybeBlockUninstall(event)
                evaluateBlockedSite(event)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                maybeBlockUninstall(event)
                evaluateBlockedSite(event)
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
        if (now - lastUninstallBlockAt < UNINSTALL_SUPPRESS_MS) return true

        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            lastUninstallBlockAt = now
            ProtectionInfoPopup.showUninstallBlocked(this)
        }
        return true
    }

    private fun maybeDismissBlockedApp(pkg: String, windowTitle: String? = null) {
        if (pkg == packageName) return
        val matchedKeyword = BlockedAppsManager.findMatchingBlockedName(this, pkg, windowTitle) ?: return

        val now = System.currentTimeMillis()
        if (now < blockedSuppressUntil.getOrDefault(pkg, 0L)) return

        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            blockedSuppressUntil[pkg] = now + BLOCKED_APP_SUPPRESS_MS
            val appName = BlockedAppsManager.getAppDisplayName(this, pkg, windowTitle)
            ProtectionInfoPopup.showBlockedApp(this, appName, matchedKeyword)
        }
    }

    private fun evaluateBlockedSite(event: AccessibilityEvent) {
        if (redirectInProgress) return

        val pkg = resolveForegroundPackage(event) ?: return
        if (pkg !in browserPackages) return

        // Typing / search-bar editing: do not redirect while the soft keyboard is up.
        // Block only once the keyboard is down (URL committed / page loading or loaded).
        if (isSoftKeyboardVisible()) return

        val now = System.currentTimeMillis()
        if (now < lastBlockedUrlSuppressUntil) return

        val urlBarTexts = LinkedHashSet<String>()

        event.text?.forEach { chunk ->
            chunk?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { urlBarTexts.add(it) }
        }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            urlBarTexts.add(it)
        }

        rootInActiveWindow?.let { root ->
            collectUrlCandidatesFromRoot(root, pkg, urlBarTexts)
        }

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                if (root.packageName?.toString() == pkg) {
                    collectUrlCandidatesFromRoot(root, pkg, urlBarTexts)
                }
            } finally {
                root.recycle()
            }
        }

        if (urlBarTexts.isEmpty()) return

        val urlBarHost = urlBarTexts.firstNotNullOfOrNull {
            BlockedSitesManager.extractHostPublic(it)
        }
        if (urlBarHost != null && BlockedSitesManager.isSafeRedirectHost(urlBarHost)) {
            // On Google itself: only block keywords in search/path URLs, not the bare homepage.
            if (!looksLikeGoogleSearchOrPath(urlBarTexts)) return

            val matchedKeywordOnGoogle = BlockedKeywordsManager.findMatchingKeyword(this, urlBarTexts)
                ?: return

            lastBlockedUrlSuppressUntil = now + BLOCKED_SITE_SUPPRESS_MS
            redirectInProgress = true
            redirectBrowserToGoogle(pkg, matchedKeyword = matchedKeywordOnGoogle)
            return
        }

        val matchedDomain = if (urlBarTexts.any { looksLikeUrlOrHost(it) }) {
            urlBarTexts.firstNotNullOfOrNull {
                BlockedSitesManager.findMatchingDomain(this, it)
            }
        } else {
            null
        }

        val matchedKeyword = BlockedKeywordsManager.findMatchingKeyword(this, urlBarTexts)

        if (matchedDomain == null && matchedKeyword == null) return

        lastBlockedUrlSuppressUntil = now + BLOCKED_SITE_SUPPRESS_MS
        redirectInProgress = true
        redirectBrowserToGoogle(
            browserPackage = pkg,
            matchedDomain = matchedDomain,
            matchedKeyword = matchedKeyword
        )
    }

    private fun isSoftKeyboardVisible(): Boolean {
        windows?.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
        }
        return false
    }

    private fun looksLikeGoogleSearchOrPath(texts: Collection<String>): Boolean {
        return texts.any { text ->
            val lower = text.lowercase()
            lower.contains("/search") ||
                lower.contains("q=") ||
                lower.contains("/url?") ||
                // path after host: google.com/something
                Regex("""google\.com/.+""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    private fun collectUrlCandidatesFromRoot(
        root: AccessibilityNodeInfo,
        pkg: String,
        out: MutableSet<String>
    ) {
        for (suffix in urlBarViewIdSuffixes) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix") ?: continue
            for (node in nodes) {
                try {
                    node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    }
                } finally {
                    node.recycle()
                }
            }
        }

        // Fallback: walk only for known address-bar view ids (not in-page link text).
        walkForUrlBarText(root, out, 0)
    }

    private fun walkForUrlBarText(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 24) return

        val viewId = node.viewIdResourceName.orEmpty()
        if (urlBarViewIdSuffixes.any { viewId.endsWith(it) }) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walkForUrlBarText(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun looksLikeUrlOrHost(value: String): Boolean {
        val v = value.lowercase()
        if (v.contains("://")) return true
        if (v.startsWith("www.")) return true
        return Regex("""^[a-z0-9][a-z0-9.\-]*\.[a-z]{2,}(/.*)?$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(v)
    }

    private fun redirectBrowserToGoogle(
        browserPackage: String,
        matchedDomain: String? = null,
        matchedKeyword: String? = null
    ) {
        // Try to rewrite the current tab's address bar; fall back to opening Google in-browser.
        if (!trySetUrlBarToGoogle(browserPackage)) {
            openGoogleViaIntent(browserPackage)
        }

        when {
            matchedDomain != null -> ProtectionInfoPopup.showBlockedSite(this, matchedDomain)
            matchedKeyword != null -> ProtectionInfoPopup.showBlockedKeyword(this, matchedKeyword)
        }
        handler.postDelayed({
            redirectInProgress = false
        }, REDIRECT_LOCK_MS)
    }

    private fun trySetUrlBarToGoogle(browserPackage: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val urlBar = findUrlBarNode(root, browserPackage) ?: return false

        try {
            urlBar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            urlBar.recycle()
        }

        // Omnibox needs a moment after focus before SET_TEXT works reliably.
        handler.postDelayed({
            val freshRoot = rootInActiveWindow ?: run {
                openGoogleViaIntent(browserPackage)
                return@postDelayed
            }
            val focusedBar = findUrlBarNode(freshRoot, browserPackage)
            if (focusedBar == null) {
                openGoogleViaIntent(browserPackage)
                return@postDelayed
            }
            try {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    SAFE_REDIRECT_URL
                )
                focusedBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                openGoogleViaIntent(browserPackage)
            } finally {
                focusedBar.recycle()
            }
        }, URL_BAR_FOCUS_DELAY_MS)

        return true
    }

    private fun findUrlBarNode(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (suffix in urlBarViewIdSuffixes) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix")
            if (!nodes.isNullOrEmpty()) {
                val match = nodes[0]
                for (i in 1 until nodes.size) nodes[i].recycle()
                return match
            }
        }
        return findUrlBarNodeByWalk(root, 0)
    }

    private fun findUrlBarNodeByWalk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 24) return null

        val viewId = node.viewIdResourceName.orEmpty()
        if (urlBarViewIdSuffixes.any { viewId.endsWith(it) }) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findUrlBarNodeByWalk(child, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun openGoogleViaIntent(browserPackage: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SAFE_REDIRECT_URL)).apply {
                setPackage(browserPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(SAFE_REDIRECT_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
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
        redirectInProgress = false
    }

    companion object {
        private const val SAFE_REDIRECT_URL = "https://www.google.com"
        private const val DISMISS_COOLDOWN_MS = 600L
        private const val BLOCKED_APP_SUPPRESS_MS = 4_000L
        private const val BLOCKED_SITE_SUPPRESS_MS = 5_000L
        private const val REDIRECT_LOCK_MS = 2_500L
        private const val URL_BAR_FOCUS_DELAY_MS = 200L
        private const val UNINSTALL_SUPPRESS_MS = 4_000L
        private const val RESET_DELAY_MS = 1200L
        private const val RECHECK_DELAY_MS = 200L
    }
}
