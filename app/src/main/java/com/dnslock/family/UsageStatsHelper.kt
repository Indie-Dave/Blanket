package com.dnslock.family

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * Reads today's screen time from Android's usage-stats store — the same data
 * Digital Wellbeing / Samsung Digital Wellbeing show for "time used today".
 *
 * Uses [UsageStatsManager.queryEvents] (not daily UsageStats buckets), which
 * matches Wellbeing far more closely.
 */
object UsageStatsHelper {

    private const val CACHE_TTL_MS = 15_000L

    @Volatile
    private var cachedMap: Map<String, Long> = emptyMap()

    @Volatile
    private var cachedAt = 0L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun getTodayUsageMs(
        context: Context,
        packageName: String,
        forceRefresh: Boolean = false
    ): Long {
        if (!hasUsageAccess(context)) return 0L
        return getTodayUsageMap(context, forceRefresh)[packageName] ?: 0L
    }

    fun getTodayUsageMap(context: Context, forceRefresh: Boolean = false): Map<String, Long> {
        if (!hasUsageAccess(context)) return emptyMap()

        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedMap.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) {
            return cachedMap
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()

        val startOfDay = startOfTodayMs()
        val map = computeTodayUsageFromEvents(usm, startOfDay, now)

        cachedMap = map
        cachedAt = now
        return map
    }

    fun invalidateCache() {
        cachedAt = 0L
    }

    /**
     * Sums foreground intervals from midnight to [endMs] using activity
     * resume/pause events — same underlying feed Digital Wellbeing uses.
     */
    private fun computeTodayUsageFromEvents(
        usm: UsageStatsManager,
        startMs: Long,
        endMs: Long
    ): Map<String, Long> {
        val events = usm.queryEvents(startMs, endMs) ?: return emptyMap()
        val event = UsageEvents.Event()

        // package -> timestamp when it last entered foreground (open session)
        val sessionStart = HashMap<String, Long>()
        val totals = HashMap<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg.isEmpty()) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                @Suppress("DEPRECATION")
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // Only start a new session if one isn't already open
                    // (activity switches within the same app).
                    if (!sessionStart.containsKey(pkg)) {
                        sessionStart[pkg] = event.timeStamp.coerceAtLeast(startMs)
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                @Suppress("DEPRECATION")
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val started = sessionStart.remove(pkg) ?: continue
                    val duration = event.timeStamp - started
                    if (duration > 0L) {
                        totals[pkg] = (totals[pkg] ?: 0L) + duration
                    }
                }
            }
        }

        // Apps still in the foreground right now: count through "now".
        for ((pkg, started) in sessionStart) {
            val duration = endMs - started
            if (duration > 0L) {
                totals[pkg] = (totals[pkg] ?: 0L) + duration
            }
        }

        return totals
    }

    private fun startOfTodayMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
