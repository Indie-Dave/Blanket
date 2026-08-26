package com.dnslock.family

import android.content.Context
import java.security.MessageDigest

object PasswordManager {

    private const val PREFS_NAME = "pin_settings"
    private const val KEY_PASSWORD_HASH = "pin_hash"
    private const val KEY_DNS_SCREEN_LOCK_ENABLED = "dns_screen_lock_enabled"
    private const val KEY_DNS_UNLOCK_UNTIL = "dns_unlock_until"
    private const val KEY_ACCESSIBILITY_UNLOCK_UNTIL = "accessibility_unlock_until"

    private const val MIN_PASSWORD_LENGTH = 4
    private const val MAX_PASSWORD_LENGTH = 64
    private const val DEFAULT_UNLOCK_DURATION_MS = 10 * 60 * 1000L
    private const val ACCESSIBILITY_UNLOCK_DURATION_MS = 10 * 60 * 1000L

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

    fun isDnsUnlocked(context: Context): Boolean =
        isUnlocked(context, KEY_DNS_UNLOCK_UNTIL)

    fun unlockDns(context: Context, durationMs: Long = DEFAULT_UNLOCK_DURATION_MS) {
        unlock(context, KEY_DNS_UNLOCK_UNTIL, durationMs)
    }

    fun lockDns(context: Context) {
        lock(context, KEY_DNS_UNLOCK_UNTIL)
    }

    fun getDnsUnlockUntil(context: Context): Long =
        prefs(context).getLong(KEY_DNS_UNLOCK_UNTIL, 0L)

    fun isAccessibilityUnlocked(context: Context): Boolean =
        isUnlocked(context, KEY_ACCESSIBILITY_UNLOCK_UNTIL)

    fun unlockAccessibility(
        context: Context,
        durationMs: Long = ACCESSIBILITY_UNLOCK_DURATION_MS
    ) {
        unlock(context, KEY_ACCESSIBILITY_UNLOCK_UNTIL, durationMs)
    }

    fun lockAccessibility(context: Context) {
        lock(context, KEY_ACCESSIBILITY_UNLOCK_UNTIL)
    }

    private fun isUnlocked(context: Context, key: String): Boolean {
        val until = prefs(context).getLong(key, 0L)
        if (until <= System.currentTimeMillis()) {
            if (until > 0L) {
                prefs(context).edit().remove(key).apply()
            }
            return false
        }
        return true
    }

    private fun unlock(context: Context, key: String, durationMs: Long) {
        prefs(context).edit()
            .putLong(key, System.currentTimeMillis() + durationMs)
            .apply()
    }

    private fun lock(context: Context, key: String) {
        prefs(context).edit()
            .remove(key)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
