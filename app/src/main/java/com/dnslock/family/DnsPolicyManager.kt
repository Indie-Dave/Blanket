package com.dnslock.family

import android.content.Context
import android.provider.Settings

/**
 * Read-only inspection of the device's Private DNS setting via Settings.Global.
 */
object DnsPolicyManager {

    const val TARGET_HOST = "family.cloudflare-dns.com"

    private const val KEY_PRIVATE_DNS_MODE = "private_dns_mode"
    private const val KEY_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"
    private const val MODE_OFF = "off"
    private const val MODE_OPPORTUNISTIC = "opportunistic"
    private const val MODE_PROVIDER_HOSTNAME = "hostname"

    fun getPrivateDnsMode(context: Context): String =
        Settings.Global.getString(context.contentResolver, KEY_PRIVATE_DNS_MODE)
            ?: MODE_OFF

    fun getPrivateDnsHost(context: Context): String? {
        if (getPrivateDnsMode(context) != MODE_PROVIDER_HOSTNAME) {
            return null
        }
        return Settings.Global.getString(context.contentResolver, KEY_PRIVATE_DNS_SPECIFIER)
            ?.trim()?.lowercase()?.removeSuffix(".")
    }

    fun isFamilyDnsSet(context: Context): Boolean {
        if (getPrivateDnsMode(context) != MODE_PROVIDER_HOSTNAME) {
            return false
        }
        return getPrivateDnsHost(context) == TARGET_HOST
    }

    fun formatDnsStatus(context: Context): String {
        return when (val mode = getPrivateDnsMode(context)) {
            MODE_OFF -> context.getString(R.string.dns_status_off)
            MODE_OPPORTUNISTIC -> context.getString(R.string.dns_status_automatic)
            MODE_PROVIDER_HOSTNAME -> {
                val host = getPrivateDnsHost(context)
                    ?: context.getString(R.string.dns_status_unknown_host)
                context.getString(R.string.dns_status_host, host)
            }
            else -> context.getString(R.string.dns_status_mode, mode)
        }
    }
}
