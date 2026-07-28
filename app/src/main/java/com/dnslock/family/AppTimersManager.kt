package com.dnslock.family

import android.content.Context
import android.graphics.drawable.Drawable
import org.json.JSONObject

/**
 * Daily per-app usage limits (Digital Wellbeing–style). Timers can be set and
 * changed freely without a password.
 */
object AppTimersManager {

    private const val PREFS_NAME = "app_timers"
    private const val KEY_LIMITS_JSON = "limits_json"

    data class AppTimerEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        /** Daily limit in minutes; 0 means no limit. */
        val limitMinutes: Int,
        val usedTodayMs: Long
    ) {
        val remainingMs: Long
            get() = (limitMinutes * 60_000L - usedTodayMs).coerceAtLeast(0L)

        val isExceeded: Boolean
            get() = limitMinutes > 0 && usedTodayMs >= limitMinutes * 60_000L
    }

    fun getLimitMinutes(context: Context, packageName: String): Int =
        readLimits(context)[packageName] ?: 0

    fun setLimitMinutes(context: Context, packageName: String, minutes: Int) {
        val limits = readLimits(context)
        if (minutes <= 0) {
            limits.remove(packageName)
        } else {
            limits[packageName] = minutes
        }
        writeLimits(context, limits)
    }

    fun removeLimit(context: Context, packageName: String) {
        setLimitMinutes(context, packageName, 0)
    }

    fun getTimedPackages(context: Context): Set<String> = readLimits(context).keys.toSet()

    fun hasAnyTimer(context: Context): Boolean = readLimits(context).isNotEmpty()

    fun isLimitExceeded(context: Context, packageName: String): Boolean {
        return remainingMs(context, packageName, forceRefresh = true) <= 0L &&
            getLimitMinutes(context, packageName) > 0
    }

    /**
     * Remaining time for today:
     *   timer limit − today's screen time from Android usage stats
     *   (same source Digital Wellbeing uses).
     *
     * While the app is open, [sessionBaselineUsageMs] + elapsed fills gaps
     * where usage stats lag behind the live session.
     */
    fun remainingMs(
        context: Context,
        packageName: String,
        sessionBaselineUsageMs: Long = -1L,
        sessionStartedAtMs: Long = 0L,
        forceRefresh: Boolean = false
    ): Long {
        val limitMinutes = getLimitMinutes(context, packageName)
        if (limitMinutes <= 0) return Long.MAX_VALUE
        val limitMs = limitMinutes * 60_000L
        val used = effectiveUsedTodayMs(
            context = context,
            packageName = packageName,
            sessionBaselineUsageMs = sessionBaselineUsageMs,
            sessionStartedAtMs = sessionStartedAtMs,
            forceRefresh = forceRefresh
        )
        return (limitMs - used).coerceAtLeast(0L)
    }

    /**
     * Today's used time from the phone's usage stats, optionally blended with
     * the current foreground session so the countdown keeps moving.
     */
    fun effectiveUsedTodayMs(
        context: Context,
        packageName: String,
        sessionBaselineUsageMs: Long = -1L,
        sessionStartedAtMs: Long = 0L,
        forceRefresh: Boolean = false
    ): Long {
        val systemUsed = UsageStatsHelper.getTodayUsageMs(context, packageName, forceRefresh)
        if (sessionStartedAtMs <= 0L || sessionBaselineUsageMs < 0L) {
            return systemUsed
        }
        val sessionElapsed =
            (System.currentTimeMillis() - sessionStartedAtMs).coerceAtLeast(0L)
        // Always prefer the higher value: system stats are the source of truth
        // for prior usage; session elapsed covers live time until stats catch up.
        return maxOf(systemUsed, sessionBaselineUsageMs + sessionElapsed)
    }

    /**
     * Builds timer rows from the preloaded launcher cache. Does not query PackageManager.
     * Pass [usageMap] from a background thread when possible (system usage today).
     */
    fun buildEntries(
        context: Context,
        cachedApps: List<InstalledAppsCache.CachedApp>,
        usageMap: Map<String, Long> = emptyMap()
    ): List<AppTimerEntry> {
        val limits = readLimits(context)
        return cachedApps.map { app ->
            AppTimerEntry(
                packageName = app.packageName,
                label = app.label,
                icon = app.icon,
                limitMinutes = limits[app.packageName] ?: 0,
                usedTodayMs = usageMap[app.packageName] ?: 0L
            )
        }.sortedWith(
            compareByDescending<AppTimerEntry> { it.limitMinutes > 0 }
                .thenBy { it.label.lowercase() }
        )
    }

    fun formatDuration(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** Used / remaining time including seconds (e.g. 1h 2m 5s, 3m 12s, 45s). */
    fun formatDurationMs(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSec / 3_600L
        val minutes = (totalSec % 3_600L) / 60L
        val seconds = totalSec % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** Compact countdown clock: H:MM:SS or M:SS. */
    fun formatCountdown(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSec / 3_600L
        val minutes = (totalSec % 3_600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun formatRemaining(remainingMs: Long): String = formatDurationMs(remainingMs)

    private fun readLimits(context: Context): MutableMap<String, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIMITS_JSON, null) ?: return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Int>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val minutes = json.optInt(key, 0)
                if (minutes > 0) map[key] = minutes
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeLimits(context: Context, limits: Map<String, Int>) {
        val json = JSONObject()
        for ((pkg, minutes) in limits) {
            if (minutes > 0) json.put(pkg, minutes)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIMITS_JSON, json.toString())
            .apply()
    }
}
