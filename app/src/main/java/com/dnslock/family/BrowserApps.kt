package com.dnslock.family

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import java.util.concurrent.Executors

/**
 * Discovers installed internet browsers via PackageManager, instead of a
 * hardcoded Chrome / Samsung / Firefox / Brave list.
 */
object BrowserApps {

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var cachedPackages: Set<String>? = null

    /**
     * Always treated as browsers so site/keyword blocking still works if a
     * device's PackageManager query is incomplete.
     */
    private val knownBrowsers = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "org.torproject.torbrowser",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser",
        "com.vivaldi.browser",
        "com.android.browser",
        "com.huawei.browser",
        "com.mi.globalbrowser",
        "com.coloros.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "org.chromium.chrome",
        "com.ecosia.android",
        "com.yandex.browser"
    )

    private val excludedPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.android.systemui",
        "com.google.android.googlequicksearchbox",
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )

    fun invalidate() {
        cachedPackages = null
    }

    fun preload(context: Context) {
        if (cachedPackages != null) return
        val appContext = context.applicationContext
        executor.execute { getPackages(appContext) }
    }

    fun isBrowser(context: Context, packageName: String): Boolean {
        if (packageName in excludedPackages) return false
        if (packageName == context.packageName) return false
        if (packageName in knownBrowsers) return true
        return packageName in getPackages(context)
    }

    fun getPackages(context: Context): Set<String> {
        cachedPackages?.let { return it }
        synchronized(lock) {
            cachedPackages?.let { return it }
            val loaded = query(context.applicationContext)
            cachedPackages = loaded
            return loaded
        }
    }

    private fun query(context: Context): Set<String> {
        val pm = context.packageManager
        val found = HashSet<String>(knownBrowsers)

        val viewIntents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        )
        for (intent in viewIntents) {
            addResolvedPackages(pm, intent, found)
        }

        val appBrowser = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_BROWSER)
        }
        addResolvedPackages(pm, appBrowser, found)

        found.removeAll(excludedPackages)
        found.remove(context.packageName)
        return found
    }

    private fun addResolvedPackages(
        pm: PackageManager,
        intent: Intent,
        out: MutableSet<String>
    ) {
        val infos = queryActivities(pm, intent)
        for (info in infos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg.isNotEmpty()) out.add(pkg)
        }
    }

    private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
