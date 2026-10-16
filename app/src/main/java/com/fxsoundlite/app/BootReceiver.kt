package com.fxsoundlite.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WakeAlarm.schedule(context) // alarms do not survive a reboot
            val mgr = AudioEffectManager(context)
            // Also needed when only the charging screensaver is on: the service is what listens for the charger.
            if (!mgr.isEnabled && !ScreensaverPrefs.autoOnCharge(context)) return
            val svcIntent = Intent(context, EffectService::class.java)
            if (mgr.isEnabled) {
                context.startForegroundService(svcIntent)
            } else {
                // Only the charging screensaver is on: run quietly, no notification.
                try {
                    context.startService(svcIntent)
                } catch (e: Exception) {
                    // Background start refused by the system; it starts when the app is next opened.
                }
            }
        }
    }
}
