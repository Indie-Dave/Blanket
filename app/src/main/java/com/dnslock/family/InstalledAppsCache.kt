package com.dnslock.family

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads launcher apps (label + icon) once via PackageManager and keeps them in memory.
 */
object InstalledAppsCache {

    data class CachedApp(
        val packageName: String,
        val label: String,
        val icon: Drawable
    )

    private val lock = Any()
    private val loading = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var cachedApps: List<CachedApp> = emptyList()

    @Volatile
    private var ready = false

    fun preload(context: Context) {
        if (ready || loading.get()) return
        val appContext = context.applicationContext
        executor.execute { ensureLoaded(appContext) }
    }

    fun isReady(): Boolean = ready

    fun getApps(context: Context): List<CachedApp> = ensureLoaded(context.applicationContext)

    fun getAppsIfReady(): List<CachedApp>? = if (ready) cachedApps else null

    private fun ensureLoaded(context: Context): List<CachedApp> {
        if (ready) return cachedApps
        synchronized(lock) {
            if (ready) return cachedApps
            loading.set(true)
            try {
                cachedApps = loadApps(context)
                ready = true
            } finally {
                loading.set(false)
            }
            return cachedApps
        }
    }

    private fun loadApps(context: Context): List<CachedApp> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launchIntent, 0)
        }

        val seen = HashSet<String>()
        val apps = ArrayList<CachedApp>(resolveInfos.size)

        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            if (pkg == context.packageName) continue
            if (isSystemUtility(pkg)) continue

            val label = try {
                info.loadLabel(pm)?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
                    ?: pkg.substringAfterLast('.')
            } catch (_: Exception) {
                pkg.substringAfterLast('.')
            }

            val icon = try {
                info.loadIcon(pm)?.mutate() ?: pm.defaultActivityIcon
            } catch (_: Exception) {
                pm.defaultActivityIcon
            }

            apps.add(CachedApp(packageName = pkg, label = label, icon = icon))
        }

        return apps.sortedBy { it.label.lowercase() }
    }

    private fun isSystemUtility(packageName: String): Boolean {
        return packageName in setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"
        )
    }
}
