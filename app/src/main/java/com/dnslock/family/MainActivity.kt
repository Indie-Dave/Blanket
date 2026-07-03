package com.dnslock.family

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dnsResultText: TextView
    private lateinit var dnsStatusDot: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var batteryStatusText: TextView

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

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.disableBatteryOptimizationButton).setOnClickListener {
            BatteryOptimizationHelper.requestExemption(this)
        }

        requestNotificationPermissionIfNeeded()
        ensureProtectionServiceRunning()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        ensureProtectionServiceRunning()
        refreshStatus()
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
