package com.solarmonitor.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.solarmonitor.app.repo

/** Restarts background monitoring after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (context.repo.prefs.value.background) MonitorService.start(context)
        }
    }
}
