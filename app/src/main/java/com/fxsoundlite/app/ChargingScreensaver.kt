package com.fxsoundlite.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * Decides when the Screensaver should pop up over the lock screen, and
 * launches it.
 *
 * Trigger: the charger is plugged in, or the screen turns on while charging,
 * AND the keyguard (lock screen) is showing. If the phone is unlocked and
 * being used, nothing happens -- the feature only takes over the lock screen.
 *
 * Launching: on Android 9 and below the Activity is started directly. Since
 * Android 10, apps can't start an Activity from the background, and the
 * sanctioned way to open one over the lock screen is a full-screen-intent
 * notification, so that's what is posted there. On Android
 * 14+ the user must also allow "full-screen notifications" for the app (see
 * SettingsActivity), otherwise [show] does nothing.
 *
 * The actual broadcasts are received by EffectService (ACTION_POWER_CONNECTED
 * and ACTION_SCREEN_ON can't be declared in the manifest).
 */
object ChargingScreensaver {

    private const val CHANNEL_ID = "fxsoundlite_charging_channel"
    /** Must differ from MusicService (2) and EffectService (1): cancelling this one used to remove the music notification. */
    private const val NOTIF_ID = 2307

    fun intentFilter() = IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_SCREEN_ON)
    }

    fun isCharging(context: Context): Boolean {
        // Sticky broadcast: passing a null receiver just reads the last value.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    fun onSystemEvent(context: Context, action: String?) {
        if (!ScreensaverPrefs.autoOnCharge(context)) return
        if (ChargingScreensaverActivity.isShowing) return
        // The sticky battery state can lag behind POWER_CONNECTED, so trust the event itself.
        val charging = action == Intent.ACTION_POWER_CONNECTED || isCharging(context)
        if (!charging) return
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) return
        show(context)
    }

    private fun show(context: Context) {
        // Android 9 and below (API < 29) still allow starting an Activity straight from
        // the background, so skip the full-screen-intent notification there. That
        // notification path depends on lock-screen notification settings and the channel
        // being allowed, which is exactly what tends to break with a password/PIN lock.
        // If the direct start throws for any reason, fall through to the notification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                context.startActivity(
                    Intent(context, ChargingScreensaverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                return
            } catch (e: Exception) {
                // fall through to the notification route below
            }
        }

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) return

        val strings = LocaleHelper.localizedContext(context)
        val channel = NotificationChannel(
            CHANNEL_ID, strings.getString(R.string.charging_channel_name),
            NotificationManager.IMPORTANCE_HIGH // full-screen intents need HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val launch = PendingIntent.getActivity(
            context, 10,
            Intent(context, ChargingScreensaverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(strings.getString(R.string.app_name))
            .setContentText(strings.getString(R.string.charging_notif_text))
            .setFullScreenIntent(launch, true)
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000) // only a launch vehicle; don't leave it lingering
            .build()
        try {
            nm.notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission revoked -- nothing we can do until re-granted.
        }
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}
