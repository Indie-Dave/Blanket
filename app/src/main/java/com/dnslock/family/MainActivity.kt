package com.dnslock.family

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var dnsResultText: TextView
    private lateinit var dnsLockSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        dnsResultText = findViewById(R.id.dnsResultText)
        dnsLockSwitch = findViewById(R.id.dnsLockSwitch)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.copyAdbButton).setOnClickListener {
            val cmd = "adb shell dpm set-device-owner ${packageName}/.DnsDeviceAdminReceiver"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("adb command", cmd))
            Toast.makeText(this, "Copied: $cmd", Toast.LENGTH_LONG).show()
        }

        dnsLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            val success = if (isChecked) {
                DnsPolicyManager.lockFamilyDns(this)
            } else {
                DnsPolicyManager.unlockDns(this)
            }

            if (!success) {
                dnsResultText.text = "Failed: this app is not the Device Owner yet. " +
                        "Provision it via adb first (see button below), then retry."
                // Revert the switch since the change didn't actually take effect.
                dnsLockSwitch.setOnCheckedChangeListener(null)
                dnsLockSwitch.isChecked = false
                dnsLockSwitch.setOnCheckedChangeListener { _, checked ->
                    if (checked) DnsPolicyManager.lockFamilyDns(this) else DnsPolicyManager.unlockDns(this)
                }
            } else {
                dnsResultText.text = if (isChecked) {
                    "DNS locked to ${DnsPolicyManager.FAMILY_DNS_HOST}. " +
                            "The Private DNS field in Settings is now managed and cannot be edited."
                } else {
                    "DNS lock released. Private DNS returned to opportunistic mode."
                }
            }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val isOwner = DnsPolicyManager.isDeviceOwner(this)
        statusText.text = if (isOwner) {
            "Device Owner: ACTIVE — DNS lock is fully enforceable."
        } else {
            "Device Owner: NOT active — the toggle below will not work until " +
                    "you provision this app as Device Owner via adb. See the button below."
        }

        dnsLockSwitch.setOnCheckedChangeListener(null)
        dnsLockSwitch.isChecked = DnsPolicyManager.isLockEnabledPref(this)
        dnsLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            val success = if (isChecked) DnsPolicyManager.lockFamilyDns(this) else DnsPolicyManager.unlockDns(this)
            dnsResultText.text = if (success) {
                if (isChecked) "DNS locked to ${DnsPolicyManager.FAMILY_DNS_HOST}."
                else "DNS lock released."
            } else {
                "Failed: not Device Owner yet."
            }
        }
    }
}
