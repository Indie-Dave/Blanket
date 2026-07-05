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
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var dnsResultText: TextView
    private lateinit var dnsStatusDot: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var blockedAppNameInput: EditText
    private lateinit var blockedAppsEmptyText: TextView
    private lateinit var blockedAppsListContainer: LinearLayout
    private lateinit var passwordStatusText: TextView
    private lateinit var setPasswordButton: Button
    private lateinit var dnsScreenLockSwitch: SwitchMaterial
    private lateinit var dnsLockStatusText: TextView
    private lateinit var unlockDnsButton: Button

    private var suppressDnsSwitchCallback = false

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
        passwordStatusText = findViewById(R.id.passwordStatusText)
        setPasswordButton = findViewById(R.id.setPasswordButton)
        dnsScreenLockSwitch = findViewById(R.id.dnsScreenLockSwitch)
        dnsLockStatusText = findViewById(R.id.dnsLockStatusText)
        unlockDnsButton = findViewById(R.id.unlockDnsButton)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.addBlockedAppButton).setOnClickListener {
            addBlockedApp()
        }

        findViewById<Button>(R.id.disableBatteryOptimizationButton).setOnClickListener {
            BatteryOptimizationHelper.requestExemption(this)
        }

        setPasswordButton.setOnClickListener {
            PasswordDialog.showSetPassword(this) { refreshPasswordAndDnsStatus() }
        }

        dnsScreenLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressDnsSwitchCallback) return@setOnCheckedChangeListener
            onDnsScreenLockToggled(isChecked)
        }

        unlockDnsButton.setOnClickListener {
            onUnlockDnsClicked()
        }

        requestNotificationPermissionIfNeeded()
        ensureProtectionServiceRunning()
        refreshStatus()
        refreshBlockedAppsList()
        refreshPasswordAndDnsStatus()
    }

    override fun onResume() {
        super.onResume()
        ensureProtectionServiceRunning()
        refreshStatus()
        refreshBlockedAppsList()
        refreshPasswordAndDnsStatus()
    }

    private fun onDnsScreenLockToggled(enable: Boolean) {
        if (enable) {
            PasswordManager.setDnsScreenLockEnabled(this, true)
            refreshPasswordAndDnsStatus()
            return
        }

        if (!PasswordManager.isPasswordSet(this)) {
            revertDnsSwitch(true)
            Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show()
            return
        }

        PasswordDialog.showVerify(
            this,
            getString(R.string.enter_password_title),
            onSuccess = {
                PasswordManager.setDnsScreenLockEnabled(this, false)
                refreshPasswordAndDnsStatus()
            },
            onCancel = { revertDnsSwitch(true) }
        )
    }

    private fun onUnlockDnsClicked() {
        if (!PasswordManager.isDnsScreenLockEnabled(this)) return

        if (PasswordManager.isDnsUnlocked(this)) {
            PasswordManager.lockDns(this)
            refreshPasswordAndDnsStatus()
            return
        }

        if (!PasswordManager.isPasswordSet(this)) {
            Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show()
            return
        }

        PasswordDialog.showVerify(this, getString(R.string.enter_password_title), onSuccess = {
            PasswordManager.unlockDns(this)
            refreshPasswordAndDnsStatus()
        })
    }

    private fun revertDnsSwitch(checked: Boolean) {
        suppressDnsSwitchCallback = true
        dnsScreenLockSwitch.isChecked = checked
        suppressDnsSwitchCallback = false
    }

    private fun refreshPasswordAndDnsStatus() {
        val passwordSet = PasswordManager.isPasswordSet(this)
        passwordStatusText.text = if (passwordSet) {
            getString(R.string.password_status_set)
        } else {
            getString(R.string.password_status_not_set)
        }
        setPasswordButton.text = if (passwordSet) {
            getString(R.string.change_password)
        } else {
            getString(R.string.set_password)
        }

        val dnsLockEnabled = PasswordManager.isDnsScreenLockEnabled(this)
        revertDnsSwitch(dnsLockEnabled)

        if (!dnsLockEnabled) {
            dnsLockStatusText.text = getString(R.string.dns_unlock_disabled)
            unlockDnsButton.visibility = View.GONE
            return
        }

        unlockDnsButton.visibility = View.VISIBLE
        if (PasswordManager.isDnsUnlocked(this)) {
            val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(
                Date(PasswordManager.getDnsUnlockUntil(this))
            )
            dnsLockStatusText.text = getString(R.string.dns_unlocked_status, until)
            unlockDnsButton.text = getString(R.string.lock_dns_block)
        } else {
            dnsLockStatusText.text = getString(R.string.dns_locked_status)
            unlockDnsButton.text = getString(R.string.unlock_dns_block)
        }
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

    private fun removeBlockedApp(name: String) {
        val doRemove = {
            BlockedAppsManager.removeName(this, name)
            refreshBlockedAppsList()
        }

        if (PasswordManager.isPasswordSet(this)) {
            PasswordDialog.showVerify(this, getString(R.string.enter_password_title), onSuccess = doRemove)
        } else {
            doRemove()
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
                setOnClickListener { removeBlockedApp(name) }
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
