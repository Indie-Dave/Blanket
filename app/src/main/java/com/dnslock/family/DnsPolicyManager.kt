package com.dnslock.family

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * Wraps the DevicePolicyManager calls needed to actually control the
 * system-wide Private DNS setting.
 *
 * IMPORTANT: setGlobalPrivateDns() only works if this app has been
 * provisioned as the device's Device Owner. A normal, non-privileged app
 * has no API that can touch this setting -- that's intentional on
 * Android's part, so a rogue app can't silently hijack your DNS.
 *
 * See README.md for how to provision Device Owner via adb.
 */
object DnsPolicyManager {

    const val FAMILY_DNS_HOST = "family.cloudflare-dns.com"
    private const val PREFS_NAME = "dns_lock_prefs"
    private const val KEY_ENABLED = "dns_lock_enabled"

    private fun admin(context: Context) =
        ComponentName(context, DnsDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(context: Context): Boolean {
        return dpm(context).isDeviceOwnerApp(context.packageName)
    }

    /** Locks Private DNS to family.cloudflare-dns.com. Returns true on success. */
    fun lockFamilyDns(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!isDeviceOwner(context)) return false

        dpm(context).setGlobalPrivateDns(
            admin(context),
            DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
            FAMILY_DNS_HOST
        )
        setPrefEnabled(context, true)
        return true
    }

    /** Releases the DNS lock, returning the device to opportunistic (default) mode. */
    fun unlockDns(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!isDeviceOwner(context)) return false

        dpm(context).setGlobalPrivateDns(
            admin(context),
            DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
        )
        setPrefEnabled(context, false)
        return true
    }

    fun isLockEnabledPref(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    private fun setPrefEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
