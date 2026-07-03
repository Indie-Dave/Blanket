package com.dnslock.family

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dnsResultText: TextView
    private lateinit var dnsStatusDot: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var blockedAppNameInput: EditText
    private lateinit var blockedAppsEmptyText: TextView
    private lateinit var blockedAppsListContainer: LinearLayout

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dnsResultText = findViewById(R.id.dnsResultText)
        dnsStatusDot = findViewById(R.id.dnsStatusDot)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        batteryStatusText = findViewById(R.id.batteryStatusText)
        blockedAppNameInput = findViewById(R.id.blockedAppNameInput)
        blockedAppsEmptyText = findViewById(R.id.blockedAppsEmptyText)
        blockedAppsListContainer = findViewById(R.id.blockedAppsListContainer)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.addBlockedAppButton).setOnClickListener {
            addBlockedApp()
        }

        findViewById<Button>(R.id.disableBatteryOptimizationButton).setOnClickListener {
            BatteryOptimizationHelper.requestExemption(this)
        }

        requestNotificationPermissionIfNeeded()
        ensureProtectionServiceRunning()
        refreshStatus()
        refreshBlockedAppsList()
    }

    override fun onResume() {
        super.onResume()
        ensureProtectionServiceRunning()
        refreshStatus()
        refreshBlockedAppsList()
    }

    private fun addBlockedApp() {
        val name = blockedAppNameInput.text.toString()
        if (BlockedAppsManager.addName(this, name)) {
            blockedAppNameInput.text.clear()
            refreshBlockedAppsList()
            Toast.makeText(this, getString(R.string.blocked_app_added, name.trim()), Toast.LENGTH_SHORT).show()
        } else if (name.trim().isNotEmpty()) {
            Toast.makeText(this, getString(R.string.blocked_app_duplicate, name.trim()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshBlockedAppsList() {
        val names = BlockedAppsManager.getBlockedNames(this)
        blockedAppsEmptyText.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        blockedAppsListContainer.removeAllViews()

        for (name in names) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }

            val label = TextView(this).apply {
                text = name
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeButton = Button(this).apply {
                text = getString(R.string.remove_blocked_app)
                setOnClickListener {
                    BlockedAppsManager.removeName(this@MainActivity, name)
                    refreshBlockedAppsList()
                }
            }

            row.addView(label)
            row.addView(removeButton)
            blockedAppsListContainer.addView(row)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun ensureProtectionServiceRunning() {
        if (!AccessibilityHelper.isServiceEnabled(this)) return
        val intent = Intent(this, ProtectionForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshStatus() {
        val isSet = DnsPolicyManager.isFamilyDnsSet(this)
        dnsStatusDot.setBackgroundResource(
            if (isSet) R.drawable.dns_indicator_dot_active
            else R.drawable.dns_indicator_dot_inactive
        )
        dnsResultText.text = DnsPolicyManager.formatDnsStatus(this)

        val serviceEnabled = AccessibilityHelper.isServiceEnabled(this)
        accessibilityStatusText.text = if (serviceEnabled) {
            getString(R.string.accessibility_status_enabled)
        } else {
            getString(R.string.accessibility_status_disabled)
        }

        val batteryExempt = BatteryOptimizationHelper.isIgnoringOptimizations(this)
        batteryStatusText.text = if (batteryExempt) {
            getString(R.string.battery_status_enabled)
        } else {
            getString(R.string.battery_status_disabled)
        }
    }
}
