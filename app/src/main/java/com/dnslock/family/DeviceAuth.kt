package com.dnslock.family

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

object DeviceAuth {

    private const val SCRIM_TAG = "device_auth_scrim"

    @Volatile
    private var unlocked = false
    private var promptShowing = false
    private var lifecycleRegistered = false
    private var startedActivities = 0
    private val pendingUnlocks = mutableListOf<Pair<AppCompatActivity, () -> Unit>>()

    fun hideUntilUnlocked(activity: AppCompatActivity) {
        registerLifecycle(activity.application)
        if (!unlocked) {
            showScrim(activity)
        }
    }

    fun requireUnlock(activity: AppCompatActivity, onUnlocked: () -> Unit) {
        registerLifecycle(activity.application)

        if (unlocked) {
            hideScrim(activity)
            onUnlocked()
            return
        }

        showScrim(activity)
        if (activity.isFinishing) return

        pendingUnlocks.removeAll { it.first === activity }
        pendingUnlocks.add(activity to onUnlocked)
        if (promptShowing) return

        if (!isDeviceSecure(activity)) {
            showNoLockDialog(activity)
            return
        }

        showPrompt(activity)
    }

    private fun registerLifecycle(app: Application) {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                // Keep the in-progress system PIN/biometric prompt; lock only when the
                // whole app is backgrounded and no prompt is showing.
                if (startedActivities <= 0 && !promptShowing) {
                    unlocked = false
                    pendingUnlocks.clear()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun isDeviceSecure(activity: AppCompatActivity): Boolean {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true
    }

    private fun notifyUnlocked() {
        val callbacks = pendingUnlocks.toList()
        pendingUnlocks.clear()
        for ((activity, onUnlocked) in callbacks) {
            if (activity.isFinishing || activity.isDestroyed) continue
            hideScrim(activity)
            onUnlocked()
        }
    }

    private fun showPrompt(activity: AppCompatActivity) {
        promptShowing = true
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    unlocked = true
                    notifyUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                    if (activity.isFinishing || activity.isDestroyed) return
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            pendingUnlocks.clear()
                            activity.finishAffinity()
                        }
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> {
                            if (!isDeviceSecure(activity)) {
                                showNoLockDialog(activity)
                            } else {
                                pendingUnlocks.clear()
                                activity.finishAffinity()
                            }
                        }
                        else -> {
                            // System canceled (app backgrounded, credential screen, etc.).
                            // onResume will prompt again if still locked.
                        }
                    }
                }
            }
        )

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.device_lock_title))
            .setSubtitle(activity.getString(R.string.device_lock_subtitle))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }

        try {
            prompt.authenticate(builder.build())
        } catch (_: Exception) {
            promptShowing = false
            pendingUnlocks.clear()
            activity.finishAffinity()
        }
    }

    private fun showNoLockDialog(activity: AppCompatActivity) {
        if (promptShowing) return
        promptShowing = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.device_lock_not_set_title)
            .setMessage(R.string.device_lock_not_set_message)
            .setCancelable(false)
            .setPositiveButton(R.string.device_lock_set_lock) { _, _ ->
                promptShowing = false
                val intent = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
                try {
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                promptShowing = false
                pendingUnlocks.clear()
                activity.finishAffinity()
            }
            .show()
    }

    private fun showScrim(activity: AppCompatActivity) {
        val decor = activity.window.decorView as ViewGroup
        if (decor.findViewWithTag<View>(SCRIM_TAG) != null) return
        val scrim = View(activity).apply {
            tag = SCRIM_TAG
            setBackgroundColor(resolveBackgroundColor(activity))
            elevation = 1000f
            isClickable = true
            isFocusable = true
        }
        decor.addView(
            scrim,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun hideScrim(activity: AppCompatActivity) {
        val decor = activity.window.decorView as ViewGroup
        val scrim = decor.findViewWithTag<View>(SCRIM_TAG) ?: return
        decor.removeView(scrim)
    }

    private fun resolveBackgroundColor(activity: AppCompatActivity): Int {
        val typedValue = TypedValue()
        return if (activity.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
            typedValue.data
        } else {
            0xFFFFFFFF.toInt()
        }
    }
}
