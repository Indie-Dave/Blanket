package com.dnslock.family

import android.app.Application

class BlanketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        InstalledAppsCache.preload(this)
    }
}
