package com.dnslock.family

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object BlockedAppsManager {

    private const val PREFS_NAME = "blocked_apps"
    private const val KEY_NAMES = "names"
    private const val MIN_PARTIAL_MATCH_LENGTH = 3

    fun getBlockedNames(context: Context): List<String> =
        readNames(context).sortedBy { it.lowercase() }

    fun addName(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false

        val names = readNames(context)
        if (names.any { it.equals(trimmed, ignoreCase = true) }) return false

        names.add(trimmed)
        writeNames(context, names)
        return true
    }

    fun removeName(context: Context, name: String) {
        val names = readNames(context)
        if (names.removeAll { it.equals(name, ignoreCase = true) }) {
            writeNames(context, names)
        }
    }

    fun isAppBlocked(
        context: Context,
        packageName: String,
        windowTitle: String? = null
    ): Boolean {
        val blocked = readNames(context)
        if (blocked.isEmpty()) return false

        val label = getAppLabel(context, packageName)
        val candidates = buildList {
            add(packageName)
            label?.let { add(it) }
            windowTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            add(packageName.substringAfterLast('.'))
        }

        return blocked.any { blockedName ->
            candidates.any { candidate -> namesMatch(blockedName, candidate) }
        }
    }

    private fun namesMatch(blockedName: String, candidate: String): Boolean {
        val blocked = blockedName.trim()
        val value = candidate.trim()
        if (blocked.isEmpty() || value.isEmpty()) return false
        if (blocked.equals(value, ignoreCase = true)) return true

        if (blocked.length >= MIN_PARTIAL_MATCH_LENGTH &&
            value.length >= MIN_PARTIAL_MATCH_LENGTH &&
            value.contains(blocked, ignoreCase = true)
        ) {
            return true
        }

        return false
    }

    private fun getAppLabel(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            }
            pm.getApplicationLabel(info).toString().trim()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun readNames(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_NAMES, emptySet()).orEmpty())
    }

    private fun writeNames(context: Context, names: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_NAMES, HashSet(names))
            .apply()
    }
}
