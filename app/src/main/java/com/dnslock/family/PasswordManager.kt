package com.dnslock.family

import android.content.Context
import java.security.MessageDigest

object PasswordManager {

    private const val PREFS_NAME = "pin_settings"
    private const val KEY_PASSWORD_HASH = "pin_hash"
    private const val KEY_DNS_SCREEN_LOCK_ENABLED = "dns_screen_lock_enabled"
    private const val KEY_DNS_UNLOCK_UNTIL = "dns_unlock_until"

    private const val MIN_PASSWORD_LENGTH = 4
    private const val MAX_PASSWORD_LENGTH = 64
    private const val DEFAULT_UNLOCK_DURATION_MS = 10 * 60 * 1000L

    fun isPasswordSet(context: Context): Boolean =
        prefs(context).contains(KEY_PASSWORD_HASH)

    fun setPassword(context: Context, password: String): Boolean {
        if (!isValidPassword(password)) return false
        prefs(context).edit()
            .putString(KEY_PASSWORD_HASH, hashPassword(password))
            .apply()
        return true
    }

    fun verifyPassword(context: Context, password: String): Boolean {
        if (!isPasswordSet(context)) return false
        val stored = prefs(context).getString(KEY_PASSWORD_HASH, null) ?: return false
        return stored == hashPassword(password)
    }

    fun isValidPassword(password: String): Boolean =
        password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH

    fun isDnsScreenLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DNS_SCREEN_LOCK_ENABLED, true)

    fun setDnsScreenLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DNS_SCREEN_LOCK_ENABLED, enabled)
            .apply()
        if (!enabled) {
            lockDns(context)
        }
    }

    fun isDnsUnlocked(context: Context): Boolean {
        val until = prefs(context).getLong(KEY_DNS_UNLOCK_UNTIL, 0L)
        if (until <= System.currentTimeMillis()) {
            if (until > 0L) {
                prefs(context).edit().remove(KEY_DNS_UNLOCK_UNTIL).apply()
            }
            return false
        }
        return true
    }

    fun unlockDns(context: Context, durationMs: Long = DEFAULT_UNLOCK_DURATION_MS) {
        prefs(context).edit()
            .putLong(KEY_DNS_UNLOCK_UNTIL, System.currentTimeMillis() + durationMs)
            .apply()
    }

    fun lockDns(context: Context) {
        prefs(context).edit()
            .remove(KEY_DNS_UNLOCK_UNTIL)
            .apply()
    }

    fun getDnsUnlockUntil(context: Context): Long =
        prefs(context).getLong(KEY_DNS_UNLOCK_UNTIL, 0L)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
