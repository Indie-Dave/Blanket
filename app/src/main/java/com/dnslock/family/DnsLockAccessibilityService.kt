package com.dnslock.family

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Navigates back one screen in Settings when the toolbar title matches a known
 * DNS settings screen for the current system locale (including Samsung hyphen variants).
 * Also redirects browsers away from blocked websites to Google, and blocks browser
 * Secure DNS / DoH settings screens when those menus are entered.
 */
class DnsLockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val targetTitles: Set<String> by lazy {
        resources.getStringArray(R.array.dns_lock_target_titles)
            .map(::normalizeTitle)
            .toSet()
    }
    private val browserDnsScreenTitles: Set<String> by lazy {
        resources.getStringArray(R.array.browser_dns_screen_markers)
            .map(::normalizeTitle)
            .toSet()
    }
    private var onTargetScreen = false
    private var onBrowserDnsScreen = false
    private var onAccessibilityScreen = false
    private var onForceStopScreen = false
    private var lastDismissAt = 0L
    private var lastUninstallBlockAt = 0L
    private var lastAccessibilityBlockAt = 0L
    private var lastForceStopBlockAt = 0L
    private var lastForceStopEvalAt = 0L
    private var lastAppInfoWindowClass: String? = null
    private val lastAppInfoExtraTexts = mutableListOf<String>()
    private var lastA11yWindowClass: String? = null
    private var lastA11yWindowTitle: String? = null
    private val lastA11yExtraTexts = mutableListOf<String>()
    private var lastBrowserDnsBlockAt = 0L
    private val blockedSuppressUntil = mutableMapOf<String, Long>()

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    private val urlBarViewIdSuffixes = listOf(
        "url_bar",
        "url_bar_title",
        "url_bar_text",
        "url_edit_text",
        "url_field",
        "url_input",
        "url_view",
        "location_bar_edit_text",
        "location_bar",
        "mozac_browser_toolbar_url_view",
        "sites_bar",
        "toolbar_url_view",
        "search_box_text",
        "search_field",
        "omnibox_text",
        "omnibox",
        "omnibarTextInput",
        "omnibar_text_input",
        "address_bar",
        "addressbar",
        "address_bar_edit_text"
    )

    private val urlBarViewIdTokens = listOf(
        "url_bar",
        "urlbar",
        "url_edit",
        "url_field",
        "url_input",
        "url_view",
        "omnibox",
        "omnibar",
        "address_bar",
        "addressbar",
        "location_bar",
        "toolbar_url",
        "search_box_text"
    )

    private var lastBlockedUrlSuppressUntil = 0L
    private var redirectInProgress = false
    private var monitoredTimerPackage: String? = null
    private var timerSessionStartedAt = 0L
    private var timerSessionBaselineUsageMs = 0L
    private var screenOnSegmentStartMs = 0L
    private var screenOnAccumulatedMs = 0L
    private var screenOffAtMs = 0L
    private var sessionScreenOffMs = 0L
    private var youtubeForceCloseAttempts = 0
    private var lastUsageStatsRefreshAt = 0L
    private var lastAppTimerEvalAt = 0L
    private var lastShortFormEvalAt = 0L
    private var lastShortFormBlockAt = 0L

    private val toolbarTitleViewIdSuffixes = listOf(
        "action_bar_title",
        "toolbar_title",
        "collapse_title"
    )

    private val recheckRunnable = Runnable { evaluateAndDismiss(fromRecheck = true) }
    private val accessibilityRecheckRunnable = Runnable { evaluateAccessibilityAndDismiss(fromRecheck = true) }
    private val forceStopRecheckRunnable = Runnable {
        evaluateForceStopAndDismiss(event = null, fromRecheck = true, force = true)
    }
    private val browserDnsRecheckRunnable = Runnable { blockBrowserDnsScreen(fromRecheck = true) }
    private val appTimerRecheckRunnable = Runnable { evaluateAppTimerLimit(fromRecheck = true) }
    private val clearAppTimerRunnable = Runnable { clearAppTimerMonitor() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onTimerScreenOff()
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> onDeviceWoke()
            }
        }
    }

    private val suggestionContainerIdTokens = listOf(
        "suggestion",
        "autocomplete",
        "omnibox_results",
        "awesomebar"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        BrowserApps.preload(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                rememberAppInfoEvent(event)
                rememberAccessibilityEvent(event)
                evaluateBlockedApp(event)
                evaluateAppTimer(event)
                evaluateAccessibilityAndDismiss(fromRecheck = false)
                evaluateForceStopAndDismiss(event, fromRecheck = false, force = true)
                maybeBlockUninstall(event)
                evaluateBlockedSite(event)
                evaluateShortForm(event, force = true)
                blockBrowserDnsScreen(fromRecheck = false)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Throttle — Instagram fires this constantly and was recreating the widget.
                val now = System.currentTimeMillis()
                if (now - lastAppTimerEvalAt >= APP_TIMER_EVENT_THROTTLE_MS) {
                    lastAppTimerEvalAt = now
                    evaluateAppTimer(event)
                }
                evaluateAccessibilityAndDismiss(fromRecheck = false)
                evaluateForceStopAndDismiss(event, fromRecheck = false, force = false)
                maybeBlockUninstall(event)
                evaluateBlockedSite(event)
                evaluateShortForm(event, force = false)
                blockBrowserDnsScreen(fromRecheck = false)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                evaluateAccessibilityAndDismiss(fromRecheck = false)
                evaluateForceStopAndDismiss(event, fromRecheck = false, force = false)
                maybeBlockUninstall(event)
                evaluateBlockedSite(event)
                evaluateShortForm(event, force = false)
                // Fragment navigations often only emit content/focus changes.
                blockBrowserDnsScreen(fromRecheck = false)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                maybeBlockUninstall(event)
                evaluateBlockedSite(event)
                evaluateShortForm(event, force = false)
            }
        }

        val pkg = event.packageName?.toString()
        if (pkg != null && pkg !in settingsPackages) {
            onTargetScreen = false
            onAccessibilityScreen = false
            handler.removeCallbacks(recheckRunnable)
            handler.removeCallbacks(accessibilityRecheckRunnable)
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

    private fun evaluateAppTimer(event: AccessibilityEvent) {
        val eventPkg = resolveForegroundPackage(event) ?: return
        val monitored = monitoredTimerPackage
        // Prefer the already-monitored timed app when noisy overlays steal the event package.
        val pkg = if (
            monitored != null &&
            eventPkg != monitored &&
            !isLauncherPackage(eventPkg) &&
            (isOverlayTransientPackage(eventPkg) || isTimedAppStillForeground(monitored))
        ) {
            monitored
        } else {
            eventPkg
        }
        evaluateAppTimerForPackage(pkg)
    }

    private fun evaluateAppTimerLimit(fromRecheck: Boolean) {
        val pkg = monitoredTimerPackage ?: return
        evaluateAppTimerForPackage(pkg, fromRecheck = fromRecheck)
    }

    private fun evaluateAppTimerForPackage(pkg: String, fromRecheck: Boolean = false) {
        if (pkg == packageName) {
            scheduleClearAppTimerMonitor()
            return
        }

        // Leaving to the launcher means the timed app is no longer open.
        if (isLauncherPackage(pkg)) {
            clearAppTimerMonitor()
            return
        }

        // Status bar / permission / IME flashes shouldn't cancel an active countdown.
        if (isOverlayTransientPackage(pkg)) {
            val monitored = monitoredTimerPackage
            if (monitored != null) {
                cancelScheduledClearAppTimer()
                enforceAppTimerForPackage(monitored, fromRecheck = true)
            }
            return
        }

        enforceAppTimerForPackage(pkg, fromRecheck)
    }

    private fun enforceAppTimerForPackage(pkg: String, fromRecheck: Boolean) {
        if (isTimerStandby()) {
            pauseTimerForStandby()
            return
        }

        val hasLimit = AppTimersManager.getLimitMinutes(this, pkg) > 0
        val hasWindows = AppTimersManager.hasBlockWindows(this, pkg)

        if (!hasLimit && !hasWindows) {
            val monitored = monitoredTimerPackage
            if (monitored != null && monitored != pkg && isTimedAppStillForeground(monitored)) {
                cancelScheduledClearAppTimer()
                enforceAppTimerForPackage(monitored, fromRecheck = true)
                return
            }
            if (monitored != null) {
                scheduleClearAppTimerMonitor()
            }
            return
        }

        cancelScheduledClearAppTimer()

        if (AppTimersManager.isInBlockWindow(this, pkg)) {
            AppTimerCountdownOverlay.stop()
            if (monitoredTimerPackage != pkg) {
                monitoredTimerPackage = pkg
                timerSessionStartedAt = 0L
                timerSessionBaselineUsageMs = 0L
            }
            if (!maybeDismissScheduleBlockedApp(pkg)) {
                scheduleAppTimerRecheck(APP_TIMER_TICK_MS)
            }
            return
        }

        if (!hasLimit) {
            if (monitoredTimerPackage != pkg) {
                AppTimerCountdownOverlay.stop()
                monitoredTimerPackage = pkg
                timerSessionStartedAt = 0L
                timerSessionBaselineUsageMs = 0L
            }
            scheduleAppTimerRecheck(APP_TIMER_TICK_MS)
            return
        }

        if (!isTimedAppStillForeground(pkg)) {
            // Screen is on, but this app is not in front. Keep the bar hidden
            // and do not keep the live clock running.
            if (screenOnSegmentStartMs > 0L) {
                val now = System.currentTimeMillis()
                screenOnAccumulatedMs += (now - screenOnSegmentStartMs).coerceAtLeast(0L)
                screenOnSegmentStartMs = 0L
            }
            AppTimerCountdownOverlay.stop()
            return
        }
        resumeTimerSegment()

        if (!UsageStatsHelper.hasUsageAccess(this)) {
            beginTimerSessionIfNeeded(pkg, 0L)
            startOrRefreshCountdown(pkg)
            scheduleAppTimerRecheck(APP_TIMER_TICK_MS)
            return
        }

        // Always re-read today's used time from Android usage stats (Digital Wellbeing source).
        val now = System.currentTimeMillis()
        val forceRefresh = monitoredTimerPackage != pkg ||
            !fromRecheck ||
            now - lastUsageStatsRefreshAt >= USAGE_STATS_REFRESH_MS
        val systemUsedToday = UsageStatsHelper.getTodayUsageMs(this, pkg, forceRefresh = forceRefresh)
        if (forceRefresh) lastUsageStatsRefreshAt = now
        beginTimerSessionIfNeeded(pkg, systemUsedToday)

        val remaining = liveRemainingMs(pkg)
        startOrRefreshCountdown(pkg)
        if (remaining <= 0L) {
            maybeDismissTimedOutApp(pkg)
            return
        }

        if (!fromRecheck || monitoredTimerPackage == pkg) {
            scheduleAppTimerRecheck(APP_TIMER_TICK_MS)
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        return pkg.startsWith("com.android.launcher") ||
            pkg.startsWith("com.google.android.apps.nexuslauncher") ||
            pkg.startsWith("com.sec.android.app.launcher") ||
            pkg.contains("quickstep")
    }

    private fun isOverlayTransientPackage(pkg: String): Boolean {
        return pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.intentresolver" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.google.android.permissioncontroller" ||
            pkg == "com.samsung.android.permissioncontroller" ||
            pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.packageinstaller" ||
            pkg.startsWith("com.samsung.android.honeyboard") ||
            pkg.contains("inputmethod") ||
            pkg.contains("screenshot") ||
            pkg.endsWith(".permissioncontroller")
    }

    /** True if [pkg] owns an active/focused application window right now. */
    private fun isTimedAppStillForeground(pkg: String): Boolean {
        if (rootInActiveWindow?.packageName?.toString() == pkg) return true

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            if (!window.isActive && !window.isFocused) return@forEach
            val root = window.root ?: return@forEach
            try {
                if (root.packageName?.toString() == pkg) return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun startOrRefreshCountdown(pkg: String) {
        if (isTimerStandby() || !isTimedAppStillForeground(pkg)) {
            AppTimerCountdownOverlay.stop()
            return
        }
        val appName = BlockedAppsManager.getAppDisplayName(this, pkg)
        AppTimerCountdownOverlay.start(
            context = this,
            packageName = pkg,
            appName = appName,
            remainingProvider = {
                // remaining = timer limit − today's phone usage while the screen is on
                liveRemainingMs(pkg)
            },
            onExpired = { maybeDismissTimedOutApp(pkg) }
        )
    }

    private fun beginTimerSessionIfNeeded(pkg: String, systemUsedTodayMs: Long) {
        if (monitoredTimerPackage == pkg && timerSessionStartedAt > 0L) {
            // Keep baseline aligned with phone usage if stats report more time used.
            // Screen-off time is excluded so it is not baked into the baseline.
            val sessionElapsed = screenOnSessionElapsedMs()
            val adjustedSystem = (
                systemUsedTodayMs -
                    AppTimersManager.screenOffOvercountMs(this, pkg) -
                    sessionIdleMs()
                ).coerceAtLeast(0L)
            val impliedBaseline = (adjustedSystem - sessionElapsed).coerceAtLeast(0L)
            if (impliedBaseline > timerSessionBaselineUsageMs) {
                timerSessionBaselineUsageMs = impliedBaseline
            }
            return
        }
        if (monitoredTimerPackage != null && monitoredTimerPackage != pkg) {
            // Don't tear down the overlay here — start() will replace it once for the new app.
            persistScreenOffOvercount(monitoredTimerPackage!!)
            cancelScheduledClearAppTimer()
        }
        monitoredTimerPackage = pkg
        timerSessionStartedAt = System.currentTimeMillis()
        // Baseline = already-used time from the phone, minus screen-off spans.
        timerSessionBaselineUsageMs = (
            systemUsedTodayMs - AppTimersManager.screenOffOvercountMs(this, pkg)
            ).coerceAtLeast(0L)
        resetScreenOnAccounting()
        youtubeForceCloseAttempts = 0
    }

    /**
     * If usage stats billed screen-off time for this visit, remember that extra
     * so the next session does not count it again. No-op when stats paused
     * correctly while the screen was off.
     */
    private fun persistScreenOffOvercount(pkg: String) {
        if (timerSessionStartedAt <= 0L || timerSessionBaselineUsageMs < 0L) return
        val gross = UsageStatsHelper.getTodayUsageMs(this, pkg, forceRefresh = true)
        val already = AppTimersManager.screenOffOvercountMs(this, pkg)
        val net = (gross - already).coerceAtLeast(0L)
        val accounted = timerSessionBaselineUsageMs + screenOnSessionElapsedMs()
        val over = (net - accounted).coerceAtLeast(0L)
        AppTimersManager.addScreenOffOvercount(this, pkg, over)
    }

    private fun liveRemainingMs(pkg: String): Long {
        val sessionActive = timerSessionStartedAt > 0L
        // While asleep or on the lock screen, ignore usage stats. They keep billing
        // an open session through "now" and would jump the countdown by the sleep.
        if (sessionActive && (isTimerStandby() || screenOffAtMs > 0L)) {
            val limitMinutes = AppTimersManager.getLimitMinutes(this, pkg)
            if (limitMinutes <= 0) return Long.MAX_VALUE
            val used = timerSessionBaselineUsageMs + screenOnAccumulatedMs
            return (limitMinutes * 60_000L - used).coerceAtLeast(0L)
        }
        return AppTimersManager.remainingMs(
            context = this,
            packageName = pkg,
            sessionBaselineUsageMs = timerSessionBaselineUsageMs,
            sessionStartedAtMs = timerSessionStartedAt,
            forceRefresh = false,
            sessionElapsedOverrideMs = if (sessionActive) screenOnSessionElapsedMs() else -1L,
            idleMsToExclude = if (sessionActive) sessionIdleMs() else 0L
        )
    }

    private fun isScreenInteractive(): Boolean {
        val power = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }

    private fun isKeyguardLocked(): Boolean {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return keyguard.isKeyguardLocked
    }

    /** Screen off, doze/sleep (!interactive), or the lock screen. */
    private fun isTimerStandby(): Boolean = !isScreenInteractive() || isKeyguardLocked()

    private fun onTimerScreenOff() {
        pauseTimerForStandby()
    }

    /** Screen on or unlocked. Count and show the bar only if the timed app is in front. */
    private fun onDeviceWoke() {
        if (isTimerStandby()) {
            pauseTimerForStandby()
            return
        }
        val pkg = monitoredTimerPackage ?: return
        if (!isTimedAppStillForeground(pkg)) {
            AppTimerCountdownOverlay.stop()
            return
        }
        evaluateAppTimerLimit(fromRecheck = true)
    }

    /**
     * Freeze the screen-on clock, hide the countdown bar, and stop timer ticks.
     * The gap stays open in [screenOffAtMs] so wake-up does not bill it.
     */
    private fun pauseTimerForStandby() {
        val now = System.currentTimeMillis()
        if (screenOnSegmentStartMs > 0L) {
            screenOnAccumulatedMs += (now - screenOnSegmentStartMs).coerceAtLeast(0L)
            screenOnSegmentStartMs = 0L
        }
        if (timerSessionStartedAt > 0L && screenOffAtMs == 0L) {
            screenOffAtMs = now
        }
        handler.removeCallbacks(appTimerRecheckRunnable)
        AppTimerCountdownOverlay.stop()
    }

    /** Start the screen-on clock again. Closes the standby gap first. */
    private fun resumeTimerSegment() {
        if (isTimerStandby()) return
        val now = System.currentTimeMillis()
        if (screenOffAtMs > 0L) {
            sessionScreenOffMs += (now - screenOffAtMs).coerceAtLeast(0L)
            screenOffAtMs = 0L
        }
        if (monitoredTimerPackage != null && screenOnSegmentStartMs == 0L && timerSessionStartedAt > 0L) {
            screenOnSegmentStartMs = now
        }
    }

    private fun resetScreenOnAccounting() {
        screenOnAccumulatedMs = 0L
        sessionScreenOffMs = 0L
        screenOffAtMs = 0L
        screenOnSegmentStartMs = if (isScreenInteractive()) System.currentTimeMillis() else 0L
    }

    /** Foreground time in this visit while the screen was on. Frozen while asleep. */
    private fun screenOnSessionElapsedMs(): Long {
        // Backstop if ACTION_SCREEN_OFF has not arrived yet.
        if (screenOnSegmentStartMs > 0L && isTimerStandby()) {
            val now = System.currentTimeMillis()
            screenOnAccumulatedMs += (now - screenOnSegmentStartMs).coerceAtLeast(0L)
            screenOnSegmentStartMs = 0L
            if (timerSessionStartedAt > 0L && screenOffAtMs == 0L) screenOffAtMs = now
        }
        val running = if (screenOnSegmentStartMs > 0L && !isTimerStandby()) {
            (System.currentTimeMillis() - screenOnSegmentStartMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return screenOnAccumulatedMs + running
    }

    /** Screen-off / lock-screen time during this visit, including an ongoing standby. */
    private fun sessionIdleMs(): Long {
        val ongoing = if (screenOffAtMs > 0L) {
            (System.currentTimeMillis() - screenOffAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return sessionScreenOffMs + ongoing
    }

    private fun scheduleAppTimerRecheck(delayMs: Long) {
        handler.removeCallbacks(appTimerRecheckRunnable)
        handler.postDelayed(appTimerRecheckRunnable, delayMs)
    }

    private fun scheduleClearAppTimerMonitor() {
        handler.removeCallbacks(clearAppTimerRunnable)
        handler.postDelayed(clearAppTimerRunnable, APP_TIMER_CLEAR_DEBOUNCE_MS)
    }

    private fun cancelScheduledClearAppTimer() {
        handler.removeCallbacks(clearAppTimerRunnable)
    }

    private fun clearAppTimerMonitor() {
        monitoredTimerPackage?.let { persistScreenOffOvercount(it) }
        cancelScheduledClearAppTimer()
        monitoredTimerPackage = null
        timerSessionStartedAt = 0L
        timerSessionBaselineUsageMs = 0L
        screenOnSegmentStartMs = 0L
        screenOnAccumulatedMs = 0L
        screenOffAtMs = 0L
        sessionScreenOffMs = 0L
        youtubeForceCloseAttempts = 0
        handler.removeCallbacks(appTimerRecheckRunnable)
        AppTimerCountdownOverlay.stop()
    }

    private fun maybeDismissTimedOutApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now < blockedSuppressUntil.getOrDefault(pkg, 0L)) return

        AppTimerCountdownOverlay.stop()
        if (ShortFormBlockManager.isYoutubePackage(pkg)) {
            val gone = forceCloseYoutube(pkg)
            if (!gone && youtubeForceCloseAttempts < YOUTUBE_FORCE_CLOSE_ATTEMPTS) {
                youtubeForceCloseAttempts++
                scheduleAppTimerRecheck(YOUTUBE_CLOSE_RETRY_MS)
                return
            }
            youtubeForceCloseAttempts = 0
            handler.postDelayed({ sweepYoutubePictureInPicture(pkg) }, YOUTUBE_CLOSE_RETRY_MS)
        } else if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            scheduleAppTimerRecheck(APP_TIMER_TICK_MS)
            return
        }

        blockedSuppressUntil[pkg] = now + BLOCKED_APP_SUPPRESS_MS
        clearAppTimerMonitor()
        val appName = BlockedAppsManager.getAppDisplayName(this, pkg)
        ProtectionInfoPopup.showAppTimerExceeded(this, appName)
    }

    /**
     * Home alone leaves YouTube in the player, a second activity, or picture-in-picture.
     * Back out of the player, go home, then dismiss PiP. Returns true once nothing
     * of [pkg] is still on screen.
     */
    private fun forceCloseYoutube(pkg: String): Boolean {
        if (isPackageInPictureInPicture(pkg)) {
            dismissPictureInPicture(pkg)
            performGlobalAction(GLOBAL_ACTION_HOME)
            openLauncherHome()
            return !isYoutubeStillOpen(pkg)
        }
        if (isTimedAppStillForeground(pkg)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        openLauncherHome()
        dismissPictureInPicture(pkg)
        return !isYoutubeStillOpen(pkg)
    }

    /** YouTube enters PiP a moment after Home. Close that window if it appears. */
    private fun sweepYoutubePictureInPicture(pkg: String) {
        if (!isPackageInPictureInPicture(pkg) && !isTimedAppStillForeground(pkg)) return
        dismissPictureInPicture(pkg)
        if (isTimedAppStillForeground(pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            openLauncherHome()
        }
        dismissPictureInPicture(pkg)
    }

    private fun openLauncherHome() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun isYoutubeStillOpen(pkg: String): Boolean {
        return isTimedAppStillForeground(pkg) || isPackageWindowVisible(pkg) || isPackageInPictureInPicture(pkg)
    }

    private fun isPackageWindowVisible(pkg: String): Boolean {
        val wins = windows ?: return false
        for (window in wins) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() == pkg) return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun isPackageInPictureInPicture(pkg: String): Boolean {
        val wins = windows ?: return false
        for (window in wins) {
            if (!window.isInPictureInPictureMode) continue
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() == pkg) return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun dismissPictureInPicture(pkg: String) {
        val wins = windows ?: return
        for (window in wins) {
            if (!window.isInPictureInPictureMode) continue
            val root = window.root ?: continue
            try {
                val windowPkg = root.packageName?.toString()
                if (windowPkg != pkg && windowPkg != "com.android.systemui") continue
                root.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                clickPipClose(root, 0, windowIsPip = true)
            } finally {
                root.recycle()
            }
        }
        clickSystemPipClose()
    }

    private fun clickSystemPipClose() {
        val wins = windows ?: return
        for (window in wins) {
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() != "com.android.systemui") continue
                clickPipClose(root, 0, windowIsPip = false)
            } finally {
                root.recycle()
            }
        }
    }

    private fun clickPipClose(
        node: AccessibilityNodeInfo,
        depth: Int,
        windowIsPip: Boolean
    ): Boolean {
        if (depth > 12) return false
        val id = node.viewIdResourceName.orEmpty().lowercase()
        val label = (
            node.contentDescription?.toString().orEmpty() + " " +
                node.text?.toString().orEmpty()
            ).lowercase()
        val pipRelated = windowIsPip ||
            id.contains("pip") ||
            label.contains("picture-in-picture") ||
            label.contains("bild-im-bild") ||
            label.contains("bild im bild")
        val close = label.contains("close") ||
            label.contains("dismiss") ||
            label.contains("schließen") ||
            label.contains("schliessen") ||
            id.endsWith("/dismiss") ||
            id.contains("close")
        if (pipRelated && close) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (clickPipClose(child, depth + 1, windowIsPip)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    /** @return true if the app was sent home. False means retry on the next tick. */
    private fun maybeDismissScheduleBlockedApp(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < blockedSuppressUntil.getOrDefault(pkg, 0L)) return false

        AppTimerCountdownOverlay.stop()
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) return false

        blockedSuppressUntil[pkg] = now + BLOCKED_APP_SUPPRESS_MS
        clearAppTimerMonitor()
        val appName = BlockedAppsManager.getAppDisplayName(this, pkg)
        val windowLabel = AppTimersManager.formatActiveBlockWindow(this, pkg)
        ProtectionInfoPopup.showAppScheduleBlocked(this, appName, windowLabel)
        return true
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        event?.packageName?.toString()?.let { return it }

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

    /**
     * Leaves Blanket's accessibility-service screen with the same enter/back/recheck
     * pattern as Private DNS. Skipped while the password unlock window is active.
     */
    private fun evaluateAccessibilityAndDismiss(fromRecheck: Boolean) {
        if (PasswordManager.isAccessibilityUnlocked(this)) return
        if (!PasswordManager.isPasswordSet(this)) return

        val foregroundPackage = resolveForegroundPackage(null)
        if (foregroundPackage != null &&
            foregroundPackage !in settingsPackages &&
            !foregroundPackage.contains("settings", ignoreCase = true)
        ) {
            onAccessibilityScreen = false
            handler.removeCallbacks(accessibilityRecheckRunnable)
            return
        }

        val entered = isOnBlanketAccessibilityScreen(event = null)

        if (!entered) {
            onAccessibilityScreen = false
            if (!fromRecheck) {
                handler.removeCallbacks(accessibilityRecheckRunnable)
                handler.postDelayed(accessibilityRecheckRunnable, RECHECK_DELAY_MS)
            }
            return
        }

        handler.removeCallbacks(accessibilityRecheckRunnable)

        if (onAccessibilityScreen) return

        val now = System.currentTimeMillis()
        if (now - lastAccessibilityBlockAt < DISMISS_COOLDOWN_MS) return

        onAccessibilityScreen = true
        val left = performGlobalAction(GLOBAL_ACTION_BACK)
        if (left) {
            lastAccessibilityBlockAt = now
            ProtectionInfoPopup.showAccessibilityDisableBlocked(this)
            handler.postDelayed({
                if (isOnBlanketAccessibilityScreen(event = null)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
                onAccessibilityScreen = false
            }, 400L)
        } else if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            lastAccessibilityBlockAt = now
            ProtectionInfoPopup.showAccessibilityDisableBlocked(this)
            handler.postDelayed({ onAccessibilityScreen = false }, RESET_DELAY_MS)
        } else {
            onAccessibilityScreen = false
            handler.postDelayed(accessibilityRecheckRunnable, RECHECK_DELAY_MS)
        }
    }

    private fun rememberAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (pkg != null &&
            pkg !in settingsPackages &&
            !pkg.contains("settings", ignoreCase = true)
        ) {
            lastA11yWindowClass = null
            lastA11yWindowTitle = null
            lastA11yExtraTexts.clear()
            return
        }

        val cls = event.className?.toString().orEmpty()
        if (AccessibilityGuard.looksLikeServiceActivity(cls)) {
            lastA11yWindowClass = cls
        } else if (cls.isNotEmpty() &&
            !cls.startsWith("android.widget") &&
            !cls.startsWith("android.view") &&
            !cls.startsWith("androidx.")
        ) {
            lastA11yWindowClass = null
        }

        lastA11yExtraTexts.clear()
        event.text?.forEach { chunk ->
            chunk?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                lastA11yExtraTexts.add(it)
            }
        }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            lastA11yExtraTexts.add(it)
        }
        lastA11yWindowTitle = lastA11yExtraTexts.firstOrNull()
    }

    private fun isOnBlanketAccessibilityScreen(event: AccessibilityEvent?): Boolean {
        val extras = ArrayList<String>(lastA11yExtraTexts)
        event?.text?.forEach { chunk ->
            chunk?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { extras.add(it) }
        }
        val className = lastA11yWindowClass ?: event?.className?.toString()
        val screenTitle = lastA11yWindowTitle

        rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString().orEmpty()
            if (AccessibilityGuard.isAccessibilityToggleScreen(
                    this, pkg, root, extras, className, screenTitle
                )
            ) {
                return true
            }
        }

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                val windowPkg = root.packageName?.toString().orEmpty()
                if (AccessibilityGuard.isAccessibilityToggleScreen(
                        this, windowPkg, root, extras, className, screenTitle
                    )
                ) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }
        return false
    }

    /**
     * Leaves Blanket's App info screen with Back, then Home if the page is still there.
     */
    private fun evaluateForceStopAndDismiss(
        event: AccessibilityEvent?,
        fromRecheck: Boolean,
        force: Boolean = true
    ) {
        event?.let { rememberAppInfoEvent(it) }

        val nowEval = System.currentTimeMillis()
        if (!force && !fromRecheck && nowEval - lastForceStopEvalAt < FORCE_STOP_EVAL_THROTTLE_MS) {
            return
        }
        lastForceStopEvalAt = nowEval

        val entered = isOnBlanketAppInfoScreen(event)

        if (!entered) {
            onForceStopScreen = false
            val pkg = resolveForegroundPackage(event)
            if (pkg != null && !ForceStopGuard.isRelevantPackage(pkg)) {
                handler.removeCallbacks(forceStopRecheckRunnable)
                lastAppInfoWindowClass = null
                return
            }
            if (!fromRecheck) {
                handler.removeCallbacks(forceStopRecheckRunnable)
                handler.postDelayed(forceStopRecheckRunnable, RECHECK_DELAY_MS)
            }
            return
        }

        handler.removeCallbacks(forceStopRecheckRunnable)

        if (onForceStopScreen) return

        val now = System.currentTimeMillis()
        if (now - lastForceStopBlockAt < DISMISS_COOLDOWN_MS) return

        onForceStopScreen = true
        val left = performGlobalAction(GLOBAL_ACTION_BACK)
        if (left) {
            lastForceStopBlockAt = now
            ProtectionInfoPopup.showForceStopBlocked(this)
            handler.postDelayed({
                if (isOnBlanketAppInfoScreen(null)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
                onForceStopScreen = false
            }, 400L)
        } else if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            lastForceStopBlockAt = now
            ProtectionInfoPopup.showForceStopBlocked(this)
            handler.postDelayed({ onForceStopScreen = false }, RESET_DELAY_MS)
        } else {
            onForceStopScreen = false
            handler.postDelayed(forceStopRecheckRunnable, RECHECK_DELAY_MS)
        }
    }

    private fun rememberAppInfoEvent(event: AccessibilityEvent) {
        val cls = event.className?.toString().orEmpty()
        if (ForceStopGuard.looksLikeAppInfoActivity(cls)) {
            lastAppInfoWindowClass = cls
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            cls.isNotEmpty() &&
            !cls.startsWith("android.widget") &&
            !cls.startsWith("android.view") &&
            !cls.startsWith("androidx.")
        ) {
            val pkg = event.packageName?.toString()
            if (pkg == null || ForceStopGuard.isRelevantPackage(pkg)) {
                lastAppInfoWindowClass = null
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastAppInfoExtraTexts.clear()
            event.text?.forEach { chunk ->
                chunk?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    lastAppInfoExtraTexts.add(it)
                }
            }
            event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                lastAppInfoExtraTexts.add(it)
            }
        }
    }

    private fun extraAppInfoTexts(event: AccessibilityEvent?): List<String> {
        val extras = ArrayList<String>(lastAppInfoExtraTexts)
        event?.text?.forEach { chunk ->
            chunk?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { extras.add(it) }
        }
        event?.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            extras.add(it)
        }
        return extras
    }

    private fun isOnBlanketAppInfoScreen(event: AccessibilityEvent?): Boolean {
        val extras = extraAppInfoTexts(event)
        val className = lastAppInfoWindowClass
            ?: event?.className?.toString()

        rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString().orEmpty()
            if (ForceStopGuard.isBlockedAppInfoScreen(this, pkg, root, extras, className)) {
                return true
            }
        }

        windows?.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            val root = window.root ?: return@forEach
            try {
                val windowPkg = root.packageName?.toString().orEmpty()
                if (ForceStopGuard.isBlockedAppInfoScreen(this, windowPkg, root, extras, className)) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }
        return false
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

    private fun evaluateShortForm(event: AccessibilityEvent, force: Boolean) {
        if (!ShortFormBlockManager.isAnyEnabled(this)) return
        if (redirectInProgress) return

        val now = System.currentTimeMillis()
        if (!force && now - lastShortFormEvalAt < SHORT_FORM_EVAL_THROTTLE_MS) return
        lastShortFormEvalAt = now
        if (now - lastShortFormBlockAt < SHORT_FORM_SUPPRESS_MS) return

        val pkg = resolveForegroundPackage(event) ?: return
        val youtubeEnabled = ShortFormBlockManager.isYoutubeShortsBlocked(this)
        val instagramEnabled = ShortFormBlockManager.isInstagramReelsBlocked(this)
        val inBrowser = BrowserApps.isBrowser(this, pkg)
        val relevant = inBrowser ||
            (youtubeEnabled && ShortFormBlockManager.isYoutubePackage(pkg)) ||
            (instagramEnabled && ShortFormBlockManager.isInstagramPackage(pkg))
        if (!relevant) return

        if (inBrowser && isSoftKeyboardVisible()) return

        val extraTexts = LinkedHashSet<String>()
        event.text?.forEach { chunk ->
            chunk?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { extraTexts.add(it) }
        }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            extraTexts.add(it)
        }

        if (inBrowser) {
            rootInActiveWindow?.let { root ->
                try {
                    if (root.packageName?.toString() == pkg) {
                        collectUrlCandidatesFromRoot(root, pkg, extraTexts)
                    }
                } finally {
                    root.recycle()
                }
            }
        }

        if (youtubeEnabled && extraTexts.any { ShortFormBlockManager.urlLooksLikeYoutubeShorts(it) }) {
            dismissShortForm(pkg, inBrowser, ShortFormBlockManager.Kind.YOUTUBE_SHORTS)
            return
        }
        if (instagramEnabled && extraTexts.any { ShortFormBlockManager.urlLooksLikeInstagramReels(it) }) {
            dismissShortForm(pkg, inBrowser, ShortFormBlockManager.Kind.INSTAGRAM_REELS)
            return
        }

        var detected = detectShortFormInRoot(
            rootInActiveWindow,
            pkg,
            extraTexts,
            youtubeEnabled,
            instagramEnabled,
            inBrowser
        )
        if (detected == null) {
            windows?.forEach { window ->
                if (detected != null) return@forEach
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                val root = window.root ?: return@forEach
                try {
                    if (root.packageName?.toString() == pkg) {
                        detected = ShortFormBlockManager.detectInTree(
                            root = root,
                            packageName = pkg,
                            extraTexts = extraTexts,
                            youtubeEnabled = youtubeEnabled,
                            instagramEnabled = instagramEnabled,
                            inBrowser = inBrowser
                        )
                    }
                } finally {
                    root.recycle()
                }
            }
        }

        val kind = detected ?: return
        dismissShortForm(pkg, inBrowser, kind)
    }

    private fun detectShortFormInRoot(
        root: AccessibilityNodeInfo?,
        pkg: String,
        extraTexts: Collection<String>,
        youtubeEnabled: Boolean,
        instagramEnabled: Boolean,
        inBrowser: Boolean
    ): ShortFormBlockManager.Kind? {
        root ?: return null
        return try {
            if (root.packageName?.toString() != pkg) return null
            ShortFormBlockManager.detectInTree(
                root = root,
                packageName = pkg,
                extraTexts = extraTexts,
                youtubeEnabled = youtubeEnabled,
                instagramEnabled = instagramEnabled,
                inBrowser = inBrowser
            )
        } finally {
            root.recycle()
        }
    }

    private fun dismissShortForm(
        pkg: String,
        inBrowser: Boolean,
        kind: ShortFormBlockManager.Kind
    ) {
        lastShortFormBlockAt = System.currentTimeMillis()
        if (inBrowser) {
            redirectInProgress = true
            val url = when (kind) {
                ShortFormBlockManager.Kind.YOUTUBE_SHORTS -> YOUTUBE_HOME_URL
                ShortFormBlockManager.Kind.INSTAGRAM_REELS -> INSTAGRAM_HOME_URL
            }
            redirectBrowserToUrl(pkg, url)
            ProtectionInfoPopup.showBlockedShortForm(this, kind)
            handler.postDelayed({ redirectInProgress = false }, REDIRECT_LOCK_MS)
            return
        }

        val left = performGlobalAction(GLOBAL_ACTION_BACK) ||
            performGlobalAction(GLOBAL_ACTION_HOME)
        if (left) {
            ProtectionInfoPopup.showBlockedShortForm(this, kind)
        }
    }

    private fun evaluateBlockedSite(event: AccessibilityEvent) {
        if (redirectInProgress) return

        val pkg = resolveForegroundPackage(event) ?: return
        if (!BrowserApps.isBrowser(this, pkg)) return

        // Typing / search-bar editing: do not redirect while the soft keyboard is up.
        // Block only once the keyboard is down (URL committed / page loading or loaded).
        if (isSoftKeyboardVisible()) return

        val now = System.currentTimeMillis()
        if (now < lastBlockedUrlSuppressUntil) return

        val urlBarTexts = LinkedHashSet<String>()
        val eventTexts = LinkedHashSet<String>()
        val collectState = UrlCollectState()

        event.text?.forEach { chunk ->
            chunk?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { eventTexts.add(it) }
        }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            eventTexts.add(it)
        }
        var sourceIsSuggestion = false
        event.source?.let { source ->
            try {
                sourceIsSuggestion = isInSuggestionUi(source)
                if (sourceIsSuggestion) {
                    collectState.suggestionVisible = true
                } else if (isAddressBarNode(source)) {
                    noteAddressBarNode(source, urlBarTexts, collectState)
                }
            } finally {
                source.recycle()
            }
        }

        rootInActiveWindow?.let { root ->
            collectUrlCandidatesFromRoot(root, pkg, urlBarTexts, collectState)
        }

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                if (root.packageName?.toString() == pkg) {
                    collectUrlCandidatesFromRoot(root, pkg, urlBarTexts, collectState)
                }
            } finally {
                root.recycle()
            }
        }

        // Suggestion rows and in-progress typing are not a committed navigation.
        if (!sourceIsSuggestion && !collectState.suggestionVisible) {
            urlBarTexts.addAll(eventTexts)
        }

        if (urlBarTexts.isEmpty() && collectState.committedQueries.isEmpty()) return

        val keywordTexts = LinkedHashSet<String>()
        if (!collectState.suggestionVisible && !collectState.addressBarEditing) {
            keywordTexts.addAll(collectState.committedQueries)
            for (text in urlBarTexts) {
                if (isSubmittedSearchText(text)) keywordTexts.add(text)
            }
        }

        val urlBarHost = urlBarTexts.firstNotNullOfOrNull {
            BlockedSitesManager.extractHostPublic(it)
        }
        if (urlBarHost != null && BlockedSitesManager.isSafeRedirectHost(urlBarHost)) {
            // On Google itself: only block keywords in a submitted search, not the homepage
            // and not omnibox suggestions.
            if (keywordTexts.isEmpty()) return
            val submittedSearch = looksLikeGoogleSearchOrPath(urlBarTexts) ||
                collectState.committedQueries.isNotEmpty()
            if (!submittedSearch) return

            val matchedKeywordOnGoogle = BlockedKeywordsManager.findMatchingKeyword(this, keywordTexts)
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

        val matchedKeyword = if (keywordTexts.isEmpty()) {
            null
        } else {
            BlockedKeywordsManager.findMatchingKeyword(this, keywordTexts)
        }

        if (matchedDomain == null && matchedKeyword == null) return

        lastBlockedUrlSuppressUntil = now + BLOCKED_SITE_SUPPRESS_MS
        redirectInProgress = true
        redirectBrowserToGoogle(
            browserPackage = pkg,
            matchedDomain = matchedDomain,
            matchedKeyword = matchedKeyword
        )
    }

    /**
     * Blocks Secure DNS / DoH when that settings menu is entered (toolbar title
     * match), same pattern as system Private DNS screen dismiss — not when the
     * preference label is merely visible on a parent settings page.
     */
    private fun blockBrowserDnsScreen(fromRecheck: Boolean) {
        if (!PasswordManager.isDnsScreenLockEnabled(this)) return
        if (PasswordManager.isDnsUnlocked(this)) return

        val pkg = resolveForegroundPackage(null) ?: return
        if (!BrowserApps.isBrowser(this, pkg)) {
            onBrowserDnsScreen = false
            handler.removeCallbacks(browserDnsRecheckRunnable)
            return
        }

        val entered = hasBrowserDnsScreenTitle(pkg)

        if (!entered) {
            onBrowserDnsScreen = false
            if (!fromRecheck) {
                handler.removeCallbacks(browserDnsRecheckRunnable)
                handler.postDelayed(browserDnsRecheckRunnable, RECHECK_DELAY_MS)
            }
            return
        }

        handler.removeCallbacks(browserDnsRecheckRunnable)

        // Already handled this entry — don't keep blocking while still on-screen.
        if (onBrowserDnsScreen) return

        val now = System.currentTimeMillis()
        if (now - lastBrowserDnsBlockAt < BROWSER_DNS_BLOCK_COOLDOWN_MS) return

        onBrowserDnsScreen = true
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            lastBrowserDnsBlockAt = now
            ProtectionInfoPopup.showBlockedDnsSettings(this)
            handler.postDelayed({ onBrowserDnsScreen = false }, RESET_DELAY_MS)
        } else {
            onBrowserDnsScreen = false
            handler.postDelayed(browserDnsRecheckRunnable, RECHECK_DELAY_MS)
        }
    }

    private fun hasBrowserDnsScreenTitle(pkg: String): Boolean {
        rootInActiveWindow?.let { root ->
            try {
                if (root.packageName?.toString() == pkg && findBrowserDnsScreenTitle(root) != null) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            try {
                if (root.packageName?.toString() == pkg && findBrowserDnsScreenTitle(root) != null) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun findBrowserDnsScreenTitle(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 12) return null

        val viewId = node.viewIdResourceName.orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()

        for (candidate in listOf(text, desc)) {
            if (candidate.isEmpty()) continue
            if (normalizeTitle(candidate) !in browserDnsScreenTitles) continue

            val looksLikeToolbar = toolbarTitleViewIdSuffixes.any { viewId.endsWith(it) }
            // Only treat as "menu entered" when this is the screen title, not a
            // clickable preference row on a parent page.
            if (looksLikeToolbar || !isInsideClickableRow(node)) {
                return candidate
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findBrowserDnsScreenTitle(child, depth + 1)
            child?.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isSoftKeyboardVisible(): Boolean {
        windows?.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
        }
        return false
    }

    private fun looksLikeGoogleSearchOrPath(texts: Collection<String>): Boolean {
        return texts.any { text ->
            if (isAutocompleteTraffic(text)) return@any false
            val lower = text.lowercase()
            lower.contains("/search") ||
                lower.contains("q=") ||
                lower.contains("/url?") ||
                // path after host: google.com/something
                Regex("""google\.com/.+""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Omnibox autocomplete endpoints and suggestion APIs. These are not a search
     * the user submitted.
     */
    private fun isAutocompleteTraffic(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("/complete/search") ||
            lower.contains("suggestqueries") ||
            lower.contains("google.com/complete") ||
            lower.contains("/ac?") ||
            lower.contains("opensearch") ||
            lower.contains("search/suggest") ||
            lower.contains("/suggest?") ||
            lower.contains("client=chrome-omni") ||
            lower.contains("gs_ri=")
    }

    /** A loaded search URL (q=, /search, …), not a partially typed query. */
    private fun isSubmittedSearchText(text: String): Boolean {
        if (isAutocompleteTraffic(text)) return false
        val lower = text.lowercase()
        val navigated = lower.contains("://") || looksLikeUrlOrHost(text)
        if (!navigated) return false
        val hasQuery = lower.contains("q=") ||
            lower.contains("query=") ||
            lower.contains("search_query=") ||
            lower.contains("?p=") ||
            lower.contains("&p=")
        if (!hasQuery) return false
        return lower.contains("search") ||
            lower.contains("google.") ||
            lower.contains("bing.") ||
            lower.contains("duckduckgo.") ||
            lower.contains("yahoo.") ||
            lower.contains("ecosia.") ||
            lower.contains("startpage.") ||
            lower.contains("brave.com") ||
            lower.contains("kagi.com")
    }

    private fun isSuggestionContainer(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName.orEmpty().lowercase()
        val cls = node.className?.toString().orEmpty().lowercase()
        if (cls.contains("autocomplete") || cls.contains("suggestion")) return true
        return suggestionContainerIdTokens.any { token -> id.contains(token) }
    }

    private fun isInSuggestionUi(node: AccessibilityNodeInfo): Boolean {
        if (isSuggestionContainer(node)) return true
        var parent = node.parent ?: return false
        var depth = 0
        while (true) {
            val matches = isSuggestionContainer(parent)
            val next = if (depth < 5) parent.parent else null
            parent.recycle()
            if (matches) {
                next?.recycle()
                return true
            }
            if (next == null) return false
            parent = next
            depth++
        }
    }

    private fun collectUrlCandidatesFromRoot(
        root: AccessibilityNodeInfo,
        pkg: String,
        out: MutableSet<String>,
        state: UrlCollectState? = null
    ) {
        for (suffix in urlBarViewIdSuffixes) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix") ?: continue
            for (node in nodes) {
                try {
                    noteAddressBarNode(node, out, state)
                } finally {
                    node.recycle()
                }
            }
        }

        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            try {
                if (isAddressBarNode(focused)) {
                    noteAddressBarNode(focused, out, state)
                }
            } finally {
                focused.recycle()
            }
        }

        // Walk for address-bar ids that differ by browser, plus top toolbar fields.
        // Skip WebView/Gecko trees so in-page links are not treated as the URL bar.
        walkForUrlBarText(root, out, 0, state)
    }

    private fun noteAddressBarNode(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        state: UrlCollectState?
    ) {
        if (state != null && isInSuggestionUi(node)) {
            state.suggestionVisible = true
            return
        }
        collectNodeAddressText(node, out)
        if (state == null) return
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) {
            state.addressBarEditing = true
        }
        if (!node.isEditable && !node.isFocused) {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() &&
                !looksLikeUrlOrHost(text) &&
                !looksLikeAddressBarDescription(text) &&
                !isAutocompleteTraffic(text)
            ) {
                state.committedQueries.add(text)
            }
        }
    }

    private fun collectNodeAddressText(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
    }

    private fun walkForUrlBarText(
        node: AccessibilityNodeInfo?,
        out: MutableSet<String>,
        depth: Int,
        state: UrlCollectState? = null
    ) {
        if (node == null || depth > 24) return
        if (isWebContentContainer(node)) return
        if (state != null && isSuggestionContainer(node)) {
            state.suggestionVisible = true
            return
        }

        if (isAddressBarNode(node)) {
            noteAddressBarNode(node, out, state)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walkForUrlBarText(child, out, depth + 1, state)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isWebContentContainer(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return cls.contains("WebView") ||
            cls.contains("GeckoView") ||
            cls.contains("GeckoEngineView") ||
            cls.contains("GeckoSession") ||
            cls.endsWith("EngineView") ||
            cls.contains("AwContents") ||
            cls.contains("CompositorView") ||
            cls.contains("ContentView")
    }

    private fun isAddressBarNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false

        val viewId = node.viewIdResourceName.orEmpty()
        if (looksLikeUrlBarViewId(viewId)) return true

        val desc = node.contentDescription?.toString().orEmpty()
        if (looksLikeAddressBarDescription(desc) &&
            (node.isEditable || isInTopToolbarBand(node))
        ) {
            return true
        }

        if (node.isEditable && isInTopToolbarBand(node)) {
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("EditText") ||
                cls.contains("AutoComplete") ||
                cls.contains("TextView")
            ) {
                return true
            }
        }

        if (!node.isEditable && isInTopToolbarBand(node)) {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text.length < 500 && looksLikeUrlOrHost(text)) {
                return true
            }
        }
        return false
    }

    private fun looksLikeUrlBarViewId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        val id = viewId.substringAfterLast('/').lowercase()
        if (urlBarViewIdSuffixes.any { id.endsWith(it.lowercase()) }) return true
        return urlBarViewIdTokens.any { id.contains(it) }
    }

    private fun looksLikeAddressBarDescription(value: String): Boolean {
        val v = value.lowercase()
        return v.contains("address bar") ||
            v.contains("address and search") ||
            v.contains("search or type") ||
            v.contains("search or enter") ||
            v.contains("type a url") ||
            v.contains("type url") ||
            v.contains("enter url") ||
            v.contains("enter address") ||
            v.contains("omnibox") ||
            v.contains("url bar") ||
            v.contains("suchbegriff oder url") ||
            v.contains("suche oder url") ||
            v.contains("adresse eingeben")
    }

    private fun isInTopToolbarBand(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        if (bounds.top > screenHeight * 0.28f) return false
        val maxHeightPx = (88 * resources.displayMetrics.density).toInt()
        if (bounds.height() > maxHeightPx) return false
        return true
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
        redirectBrowserToUrl(browserPackage, SAFE_REDIRECT_URL)

        when {
            matchedDomain != null -> ProtectionInfoPopup.showBlockedSite(this, matchedDomain)
            matchedKeyword != null -> ProtectionInfoPopup.showBlockedKeyword(this, matchedKeyword)
        }
        handler.postDelayed({
            redirectInProgress = false
        }, REDIRECT_LOCK_MS)
    }

    private fun redirectBrowserToUrl(browserPackage: String, url: String) {
        if (!trySetUrlBarToUrl(browserPackage, url)) {
            openUrlViaIntent(browserPackage, url)
        }
    }

    private fun trySetUrlBarToUrl(browserPackage: String, url: String): Boolean {
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
                openUrlViaIntent(browserPackage, url)
                return@postDelayed
            }
            val focusedBar = findUrlBarNode(freshRoot, browserPackage)
            if (focusedBar == null) {
                openUrlViaIntent(browserPackage, url)
                return@postDelayed
            }
            try {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    url
                )
                focusedBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                openUrlViaIntent(browserPackage, url)
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
        if (isWebContentContainer(node)) return null

        if (isAddressBarNode(node)) {
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

    private fun openUrlViaIntent(browserPackage: String, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(browserPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
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
        handler.removeCallbacks(accessibilityRecheckRunnable)
        handler.removeCallbacks(forceStopRecheckRunnable)
        handler.removeCallbacks(browserDnsRecheckRunnable)
        handler.removeCallbacks(appTimerRecheckRunnable)
        handler.removeCallbacks(clearAppTimerRunnable)
        onTargetScreen = false
        onBrowserDnsScreen = false
        onAccessibilityScreen = false
        onForceStopScreen = false
        lastAppInfoWindowClass = null
        lastAppInfoExtraTexts.clear()
        lastA11yWindowClass = null
        lastA11yWindowTitle = null
        lastA11yExtraTexts.clear()
        redirectInProgress = false
        monitoredTimerPackage = null
        timerSessionStartedAt = 0L
        timerSessionBaselineUsageMs = 0L
        screenOnSegmentStartMs = 0L
        screenOnAccumulatedMs = 0L
        screenOffAtMs = 0L
        sessionScreenOffMs = 0L
        youtubeForceCloseAttempts = 0
        AppTimerCountdownOverlay.stop()
    }

    private class UrlCollectState {
        val committedQueries = LinkedHashSet<String>()
        var addressBarEditing = false
        var suggestionVisible = false
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
        private const val BROWSER_DNS_BLOCK_COOLDOWN_MS = 800L
        private const val APP_TIMER_TICK_MS = 1_000L
        private const val YOUTUBE_CLOSE_RETRY_MS = 450L
        private const val YOUTUBE_FORCE_CLOSE_ATTEMPTS = 6
        private const val USAGE_STATS_REFRESH_MS = 15_000L
        private const val APP_TIMER_EVENT_THROTTLE_MS = 400L
        private const val APP_TIMER_CLEAR_DEBOUNCE_MS = 800L
        private const val FORCE_STOP_EVAL_THROTTLE_MS = 300L
        private const val SHORT_FORM_EVAL_THROTTLE_MS = 350L
        private const val SHORT_FORM_SUPPRESS_MS = 2_500L
        private const val YOUTUBE_HOME_URL = "https://www.youtube.com"
        private const val INSTAGRAM_HOME_URL = "https://www.instagram.com"
    }
}
