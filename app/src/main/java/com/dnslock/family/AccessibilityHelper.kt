package com.dnslock.family

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityHelper {

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DnsLockAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry.trim()) == expected
        }
    }
}
