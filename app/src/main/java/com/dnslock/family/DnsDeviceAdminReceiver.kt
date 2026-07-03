package com.dnslock.family

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Minimal device admin receiver. Its only real job is to exist so this app
 * can be provisioned as Device Owner via:
 *   adb shell dpm set-device-owner com.dnslock.family/.DnsDeviceAdminReceiver
 *
 * Once that succeeds, DnsPolicyManager can call setGlobalPrivateDns().
 */
class DnsDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Family DNS Lock: admin enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Family DNS Lock: admin disabled", Toast.LENGTH_SHORT).show()
    }
}
