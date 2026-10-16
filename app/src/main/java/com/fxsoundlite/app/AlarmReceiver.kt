package com.fxsoundlite.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings

/**
 * The wake-up alarm went off. Opens [WakeAlarmActivity] over the lock screen through a full-screen-intent
 * notification (the only way to start an Activity from the background on Android 10+). That notification
 * also carries the system alarm sound, so even when the screen cannot open you are still woken up;
 * the Activity cancels the notification as soon as it is showing.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!intent.getBooleanExtra(WakeAlarm.EXTRA_SNOOZED, false)) WakeAlarm.onFired(app)

        val launch = Intent(app, WakeAlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val strings = LocaleHelper.localizedContext(app)

        val channel = NotificationChannel(
            CHANNEL_ID, strings.getString(R.string.alarm_notif_channel), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)

        val pi = PendingIntent.getActivity(
            app, 44, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setLargeIcon(logoBitmap(app))
            .setContentTitle(strings.getString(R.string.alarm_title))
            .setContentText(strings.getString(R.string.alarm_notif_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing until the screen opens / it is dismissed
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            // Notification permission revoked: nothing more we can do from here.
        }
    }

    /** The app logo, scaled down for the notification's large icon (null if it cannot be decoded). */
    private fun logoBitmap(c: Context): android.graphics.Bitmap? = try {
        val size = (64 * c.resources.displayMetrics.density).toInt()
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        android.graphics.BitmapFactory.decodeResource(c.resources, R.drawable.ic_launcher_background, opts)
            ?.let { android.graphics.Bitmap.createScaledBitmap(it, size, size, true) }
    } catch (e: Exception) { null }

    companion object {
        private const val CHANNEL_ID = "starrysound_wake_alarm"
        const val NOTIF_ID = 44

        fun cancelNotification(c: Context) {
            c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }
}
