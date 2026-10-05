package com.dnslock.family

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Daily per-app usage limits (Digital Wellbeing–style) and optional complete
 * block windows. Timers and schedules can be set and changed freely without
 * a password.
 */
object AppTimersManager {

    private const val PREFS_NAME = "app_timers"
    private const val KEY_LIMITS_JSON = "limits_json"
    private const val KEY_WINDOWS_JSON = "windows_json"
    private const val KEY_EXCLUDED_IDLE_JSON = "excluded_idle_json"
    private const val KEY_EXCLUDED_IDLE_DAY = "excluded_idle_day"
    private const val MINUTES_PER_DAY = 24 * 60

    data class BlockWindow(
        /** Minutes from midnight, 0–1439. */
        val startMinutes: Int,
        /** Minutes from midnight, 0–1439. End is exclusive. Overnight wrap is allowed. */
        val endMinutes: Int
    ) {
        fun contains(minutesOfDay: Int): Boolean {
            val start = startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
            val end = endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
            if (start == end) return false
            return if (start < end) {
                minutesOfDay in start until end
            } else {
                minutesOfDay >= start || minutesOfDay < end
            }
        }
    }

    data class AppTimerEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        /** Daily limit in minutes; 0 means no limit. */
        val limitMinutes: Int,
        val usedTodayMs: Long,
        val blockWindows: List<BlockWindow>
    ) {
        val remainingMs: Long
            get() = (limitMinutes * 60_000L - usedTodayMs).coerceAtLeast(0L)

        val isExceeded: Boolean
            get() = limitMinutes > 0 && usedTodayMs >= limitMinutes * 60_000L

        val hasRestrictions: Boolean
            get() = limitMinutes > 0 || blockWindows.isNotEmpty()

        val isBlockedNow: Boolean
            get() = blockWindows.any { it.contains(currentMinutesOfDay()) }
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

    fun getBlockWindows(context: Context, packageName: String): List<BlockWindow> =
        readWindows(context)[packageName].orEmpty()

    fun hasBlockWindows(context: Context, packageName: String): Boolean =
        getBlockWindows(context, packageName).isNotEmpty()

    fun setBlockWindows(context: Context, packageName: String, windows: List<BlockWindow>) {
        val map = readWindows(context)
        val cleaned = windows.mapNotNull { window ->
            val start = window.startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
            val end = window.endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
            if (start == end) null else BlockWindow(start, end)
        }.distinct()
        if (cleaned.isEmpty()) {
            map.remove(packageName)
        } else {
            map[packageName] = cleaned
        }
        writeWindows(context, map)
    }

    fun isInBlockWindow(context: Context, packageName: String): Boolean {
        val windows = getBlockWindows(context, packageName)
        if (windows.isEmpty()) return false
        val now = currentMinutesOfDay()
        return windows.any { it.contains(now) }
    }

    fun getTimedPackages(context: Context): Set<String> =
        readLimits(context).keys + readWindows(context).keys

    fun hasAnyTimer(context: Context): Boolean = getTimedPackages(context).isNotEmpty()

    fun isLimitExceeded(context: Context, packageName: String): Boolean {
        return remainingMs(context, packageName, forceRefresh = true) <= 0L &&
            getLimitMinutes(context, packageName) > 0
    }

    /**
     * Remaining time for today:
     *   timer limit − today's screen time from Android usage stats
     *   (same source Digital Wellbeing uses).
     *
     * While the app is open, [sessionBaselineUsageMs] + screen-on elapsed fills
     * gaps where usage stats lag. Time with the screen off is not counted:
     * pass [sessionElapsedOverrideMs] (screen-on only) and [idleMsToExclude].
     */
    fun remainingMs(
        context: Context,
        packageName: String,
        sessionBaselineUsageMs: Long = -1L,
        sessionStartedAtMs: Long = 0L,
        forceRefresh: Boolean = false,
        sessionElapsedOverrideMs: Long = -1L,
        idleMsToExclude: Long = 0L
    ): Long {
        val limitMinutes = getLimitMinutes(context, packageName)
        if (limitMinutes <= 0) return Long.MAX_VALUE
        val limitMs = limitMinutes * 60_000L
        val used = effectiveUsedTodayMs(
            context = context,
            packageName = packageName,
            sessionBaselineUsageMs = sessionBaselineUsageMs,
            sessionStartedAtMs = sessionStartedAtMs,
            forceRefresh = forceRefresh,
            sessionElapsedOverrideMs = sessionElapsedOverrideMs,
            idleMsToExclude = idleMsToExclude
        )
        return (limitMs - used).coerceAtLeast(0L)
    }

    /**
     * Today's used time from the phone's usage stats, optionally blended with
     * the current foreground session so the countdown keeps moving.
     *
     * [sessionElapsedOverrideMs] replaces wall-clock elapsed so screen-off time
     * is not consumed. [idleMsToExclude] drops that same idle span if usage
     * stats still bill an open session through "now".
     */
    fun effectiveUsedTodayMs(
        context: Context,
        packageName: String,
        sessionBaselineUsageMs: Long = -1L,
        sessionStartedAtMs: Long = 0L,
        forceRefresh: Boolean = false,
        sessionElapsedOverrideMs: Long = -1L,
        idleMsToExclude: Long = 0L
    ): Long {
        val systemUsed = (
            UsageStatsHelper.getTodayUsageMs(context, packageName, forceRefresh) -
                screenOffOvercountMs(context, packageName) -
                idleMsToExclude
            ).coerceAtLeast(0L)
        val hasLiveSession = sessionBaselineUsageMs >= 0L &&
            (sessionStartedAtMs > 0L || sessionElapsedOverrideMs >= 0L)
        if (!hasLiveSession) {
            return systemUsed
        }
        val sessionElapsed = if (sessionElapsedOverrideMs >= 0L) {
            sessionElapsedOverrideMs.coerceAtLeast(0L)
        } else {
            (System.currentTimeMillis() - sessionStartedAtMs).coerceAtLeast(0L)
        }
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
        val windows = readWindows(context)
        return cachedApps.map { app ->
            AppTimerEntry(
                packageName = app.packageName,
                label = app.label,
                icon = app.icon,
                limitMinutes = limits[app.packageName] ?: 0,
                usedTodayMs = usageMap[app.packageName] ?: 0L,
                blockWindows = windows[app.packageName].orEmpty()
            )
        }.sortedWith(
            compareByDescending<AppTimerEntry> { it.hasRestrictions }
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

    fun formatClock(context: Context, minutesOfDay: Int): String {
        val minutes = minutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60)
        cal.set(Calendar.MINUTE, minutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return DateFormat.getTimeFormat(context).format(cal.time)
    }

    fun formatWindow(context: Context, window: BlockWindow): String =
        "${formatClock(context, window.startMinutes)}–${formatClock(context, window.endMinutes)}"

    fun formatWindows(context: Context, windows: List<BlockWindow>): String =
        windows.joinToString(", ") { formatWindow(context, it) }

    fun formatActiveBlockWindow(context: Context, packageName: String): String {
        val windows = getBlockWindows(context, packageName)
        if (windows.isEmpty()) return ""
        val now = currentMinutesOfDay()
        val active = windows.filter { it.contains(now) }
        return formatWindows(context, active.ifEmpty { windows })
    }

    /**
     * Usage-stats time that fell during screen-off while a timed app stayed open.
     * Subtracted from later readings so that idle span is not billed again.
     */
    fun screenOffOvercountMs(context: Context, packageName: String): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_EXCLUDED_IDLE_DAY, 0L) != startOfTodayMs()) return 0L
        return readExcludedIdle(prefs)[packageName] ?: 0L
    }

    fun addScreenOffOvercount(context: Context, packageName: String, extraMs: Long) {
        if (extraMs <= 0L || packageName.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = startOfTodayMs()
        val map = if (prefs.getLong(KEY_EXCLUDED_IDLE_DAY, 0L) == today) {
            readExcludedIdle(prefs)
        } else {
            mutableMapOf()
        }
        map[packageName] = (map[packageName] ?: 0L) + extraMs
        val json = JSONObject()
        for ((pkg, ms) in map) {
            if (ms > 0L) json.put(pkg, ms)
        }
        prefs.edit()
            .putLong(KEY_EXCLUDED_IDLE_DAY, today)
            .putString(KEY_EXCLUDED_IDLE_JSON, json.toString())
            .apply()
    }

    fun currentMinutesOfDay(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun startOfTodayMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun readExcludedIdle(prefs: SharedPreferences): MutableMap<String, Long> {
        val raw = prefs.getString(KEY_EXCLUDED_IDLE_JSON, null) ?: return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Long>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val ms = json.optLong(key, 0L)
                if (ms > 0L) map[key] = ms
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

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

    private fun readWindows(context: Context): MutableMap<String, List<BlockWindow>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_WINDOWS_JSON, null) ?: return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, List<BlockWindow>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val arr = json.optJSONArray(pkg) ?: continue
                val list = mutableListOf<BlockWindow>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val start = obj.optInt("s", -1)
                    val end = obj.optInt("e", -1)
                    if (start in 0 until MINUTES_PER_DAY &&
                        end in 0 until MINUTES_PER_DAY &&
                        start != end
                    ) {
                        list.add(BlockWindow(start, end))
                    }
                }
                if (list.isNotEmpty()) map[pkg] = list
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeWindows(context: Context, windows: Map<String, List<BlockWindow>>) {
        val json = JSONObject()
        for ((pkg, list) in windows) {
            if (list.isEmpty()) continue
            val arr = JSONArray()
            for (window in list) {
                arr.put(
                    JSONObject()
                        .put("s", window.startMinutes)
                        .put("e", window.endMinutes)
                )
            }
            json.put(pkg, arr)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WINDOWS_JSON, json.toString())
            .apply()
    }
}
